package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripWaiver;
import org.paulsens.trip.model.WaiverAcceptance;
import org.paulsens.trip.model.WaiverDocument;
import org.paulsens.trip.model.WaiverVersion;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Trip documents ("waivers"): the ONE implementation behind the org library page, the trip editor's dialog, the
 * registration page, the admin registrations page and the REST resource. See {@code docs/waivers.md}.
 *
 * <ul>
 *   <li><b>Org library</b> -- documents belong to an organization and are authored by its admins
 *       ({@link OrgCommands#canManageOrg}). Every edit publishes the next version; no text is ever rewritten.</li>
 *   <li><b>Trip pins</b> -- a trip's registration admin or manager picks which of the trip's org's documents it
 *       requires, and which version; moving a pin is when they decide whether older signatures still count.</li>
 *   <li><b>Signing</b> -- the signer is always the signed-in person, signing for themselves or someone they
 *       manage, and must both agree and attest authority. A signature is a write-once row.</li>
 *   <li><b>The gate</b> -- {@link #isSatisfied} is what {@code RegistrationCommands.registerPartyOutcome}
 *       refuses on ({@code WAIVERS_REQUIRED}), so the page and the API cannot disagree.</li>
 * </ul>
 */
@Slf4j
@Named("waiver")
@ApplicationScoped
public class WaiverCommands {

    static final int MAX_TITLE = 120;
    static final int MAX_NOTE = 500;
    static final String CONFLICT_MESSAGE = "Saved by someone else: reload the page and try again.";
    private static final java.time.format.DateTimeFormatter SIGNED_DATE = java.time.format.DateTimeFormatter
            .ofPattern("MMM d, yyyy", java.util.Locale.ROOT).withZone(java.time.ZoneOffset.UTC);

    private final Supplier<Caller> callerSource;
    private final Supplier<OrgCommands> orgSource;
    private final Supplier<AuditCommands> auditSource;

    public WaiverCommands() {
        this(Caller::current);
    }

    /** Test seam (the RegistrationCommands pattern): {@code Caller.current()} needs a FacesContext tests lack. */
    public WaiverCommands(final Supplier<Caller> callerSource) {
        this(callerSource, () -> new OrgCommands(callerSource), AuditCommands::new);
    }

    WaiverCommands(final Supplier<Caller> callerSource, final Supplier<OrgCommands> orgSource,
            final Supplier<AuditCommands> auditSource) {
        this.callerSource = callerSource;
        this.orgSource = orgSource;
        this.auditSource = auditSource;
    }

    // ================================================================== vocabulary

    /** Where one traveler stands against one required document. */
    public enum Standing {
        /** Agreed to the pinned version (or a newer one). */
        SIGNED,
        /** Agreed to an earlier version, and the trip still accepts earlier signatures. */
        SIGNED_OLDER,
        /** Agreed only to an earlier version the trip no longer accepts: must sign again. */
        OUTDATED,
        /** Never agreed to any version. */
        MISSING;

        public boolean isSatisfied() {
            return this == SIGNED || this == SIGNED_OLDER;
        }
    }

    /** A coded outcome: the page growls {@code message}, the API sends {@code code} and {@code message}. */
    public record Result(String code, String message, WaiverDocument document) {
        public static final String OK = "OK";
        public static final String NOT_ALLOWED = "NOT_ALLOWED";
        public static final String NOT_FOUND = "NOT_FOUND";
        public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
        public static final String CONFLICT = "CONFLICT";
        public static final String STORE_FAILED = "STORE_FAILED";

        public boolean ok() {
            return OK.equals(code);
        }
    }

    /** How a signature went; {@code acceptance} is the stored row on success (new or already there). */
    public record AcceptResult(String code, String message, WaiverAcceptance acceptance) {
        public static final String OK = "OK";
        public static final String NOT_ALLOWED = "NOT_ALLOWED";
        public static final String NOT_FOUND = "NOT_FOUND";
        /** The trip does not require this document, or not this version (the pin moved: reload and read). */
        public static final String NOT_PINNED = "NOT_PINNED";
        /** One of the two boxes was not ticked. */
        public static final String MISSING_ATTESTATION = "MISSING_ATTESTATION";
        public static final String STORE_FAILED = "STORE_FAILED";

        public boolean ok() {
            return OK.equals(code);
        }
    }

    /** One traveler's answer to one document, as a registration submit carries it. */
    public record Agreement(String waiverId, int version, boolean agreed, boolean authorityAttested)
            implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** Where a signature came from, for the record. */
    public record ClientInfo(WaiverAcceptance.Client client, String ip, String userAgent) {

        /** The website's request, read from the current FacesContext (blank outside one). */
        public static ClientInfo web() {
            final FacesContext ctx = FacesContext.getCurrentInstance();
            final Object request = (ctx == null) ? null : ctx.getExternalContext().getRequest();
            return of(WaiverAcceptance.Client.WEB, request instanceof HttpServletRequest http ? http : null);
        }

        /** From a servlet request (the REST edge), {@code client} naming the app when it says which. */
        public static ClientInfo of(final WaiverAcceptance.Client client, final HttpServletRequest request) {
            if (request == null) {
                return new ClientInfo(client, null, null);
            }
            // RemoteIpValve has already replaced the ALB's address with the client's (X-Forwarded-For).
            return new ClientInfo(client, request.getRemoteAddr(), request.getHeader("User-Agent"));
        }
    }

    /** "I have read and agree to {title}." -- the first box, and the words the record and the PDF repeat. */
    public static String agreeText(final String title) {
        return "I have read and agree to " + (title == null ? "this document" : title) + ".";
    }

    /** The second box: the signer attests they are the traveler, or may sign for them. */
    public static String authorityText(final String travelerName) {
        final String name = (travelerName == null || travelerName.isBlank()) ? "this traveler" : travelerName;
        return "I attest that I am " + name + ", or that I am authorized to sign on " + name + "'s behalf.";
    }

    // ================================================================== org library

    /** Whether the signed-in user may author this organization's documents. */
    public boolean canManage(final String orgId) {
        return orgSource.get().canManageOrg(orgId);
    }

    /** The org's documents, title order, retired ones included; empty for anyone who may not manage them. */
    public List<WaiverDocument> documents(final String orgId) {
        if (!canManage(orgId)) {
            return List.of();
        }
        return DAO.getInstance().getWaiverDocuments(orgId, Cached.YES);
    }

    /** One of the org's documents; null when unknown or not manageable by the caller. */
    public WaiverDocument document(final String orgId, final String id) {
        if (!canManage(orgId)) {
            return null;
        }
        return DAO.getInstance().getWaiverDocument(orgId, id, Cached.YES).orElse(null);
    }

    /** The text of one version (the newest for 0); null when unknown or not manageable. */
    public WaiverVersion documentVersion(final String orgId, final String id, final int number) {
        final WaiverDocument doc = document(orgId, id);
        if (doc == null) {
            return null;
        }
        return (number <= 0) ? doc.getCurrent() : doc.version(number);
    }

    /** How many of the org's recent trips pin this document (the library's "used by" column). */
    public int tripsUsing(final String orgId, final String id) {
        if (!canManage(orgId)) {
            return 0;
        }
        int count = 0;
        for (final Trip trip : new TripCommands(orgSource).getTripsForOrg(orgId, 200)) {
            if (trip.waiver(id) != null) {
                count++;
            }
        }
        return count;
    }

    /** A new document at version 1. */
    public Result createDocument(final String orgId, final String title, final String bodyHtml, final String note) {
        if (!canManage(orgId)) {
            return refusal(Result.NOT_ALLOWED, "Organization admin required.");
        }
        final String problem = problemWith(title, bodyHtml, note);
        if (problem != null) {
            return refusal(Result.VALIDATION_FAILED, problem);
        }
        final Caller caller = callerSource.get();
        final LocalDateTime now = LocalDateTime.now();
        final WaiverDocument doc = WaiverDocument.builder()
                .orgId(orgId)
                .title(title.trim())
                .createdBy(caller.personId())
                .created(now)
                .versions(List.of(newVersion(1, title, bodyHtml, note, caller.personId(), now)))
                .build();
        return store(doc, "Created document '" + doc.getTitle() + "' (version 1).", caller);
    }

    /**
     * Publishes the next version: a changed title or text. The previous versions stay exactly as they were, and
     * no trip moves -- each trip keeps the version it pinned until its administrator moves it.
     */
    public Result publishVersion(final String orgId, final String id, final String title, final String bodyHtml,
            final String note) {
        if (!canManage(orgId)) {
            return refusal(Result.NOT_ALLOWED, "Organization admin required.");
        }
        final String problem = problemWith(title, bodyHtml, note);
        if (problem != null) {
            return refusal(Result.VALIDATION_FAILED, problem);
        }
        final WaiverDocument fresh = DAO.getInstance().getWaiverDocument(orgId, id, Cached.NO).orElse(null);
        if (fresh == null) {
            return refusal(Result.NOT_FOUND, "That document no longer exists.");
        }
        final WaiverVersion current = fresh.getCurrent();
        final String clean = WaiverHtml.sanitize(bodyHtml);
        if (current != null && clean.equals(current.getBodyHtml()) && title.trim().equals(current.getTitle())) {
            return refusal(Result.VALIDATION_FAILED, "Nothing changed: edit the title or the text first.");
        }
        final Caller caller = callerSource.get();
        final int next = fresh.getCurrentNumber() + 1;
        fresh.getVersions().add(newVersion(next, title, bodyHtml, note, caller.personId(), LocalDateTime.now()));
        fresh.setTitle(title.trim());
        return store(fresh, "Published version " + next + " of '" + fresh.getTitle() + "'.", caller);
    }

    /** Retires (hides from the trip pickers) or restores a document. Trips already requiring it keep it. */
    public Result setRetired(final String orgId, final String id, final boolean retired) {
        if (!canManage(orgId)) {
            return refusal(Result.NOT_ALLOWED, "Organization admin required.");
        }
        final WaiverDocument fresh = DAO.getInstance().getWaiverDocument(orgId, id, Cached.NO).orElse(null);
        if (fresh == null) {
            return refusal(Result.NOT_FOUND, "That document no longer exists.");
        }
        if (fresh.isRetired() == retired) {
            return new Result(Result.OK, null, fresh);
        }
        fresh.setRetired(retired);
        return store(fresh, (retired ? "Retired" : "Restored") + " document '" + fresh.getTitle() + "'.",
                callerSource.get());
    }

    // ------------------------------------------------------------------ the pages' growling wrappers
    // (the jsft command parser cannot branch on a returned record, so the pages call these)

    /**
     * The library page's Save: version 1 of a new document when {@code id} is blank, else the next version.
     *
     * @return the document's id when saved, null after growling the refusal.
     */
    public String saveFromPage(final String orgId, final String id, final String title, final String bodyHtml,
            final String note) {
        final Result result = (id == null || id.isBlank())
                ? createDocument(orgId, title, bodyHtml, note) : publishVersion(orgId, id, title, bodyHtml, note);
        if (result.ok()) {
            PageFeedback.info("Saved", result.message() + " Trips keep the version they pinned.");
            return result.document().getId();
        }
        PageFeedback.error("Not saved", result.message());
        return null;
    }

    /** The library page's Retire / Restore button. */
    public void retireFromPage(final String orgId, final String id, final boolean retired) {
        final Result result = setRetired(orgId, id, retired);
        if (result.ok()) {
            PageFeedback.info(retired ? "Retired" : "Restored", retired
                    ? "New trips can no longer add it; trips that require it keep it."
                    : "Trips can add it again.");
        } else {
            PageFeedback.error("Not changed", result.message());
        }
    }

    /** The trip dialog's buttons: growl the refusal, or a short confirmation. */
    public boolean changeFromPage(final Trip trip, final String action, final String waiverId, final int version,
            final boolean acceptOlder) {
        final String problem = switch (action == null ? "" : action) {
            case "add" -> addToTrip(trip, waiverId);
            case "move" -> moveVersion(trip, waiverId, version, acceptOlder);
            case "remove" -> removeFromTrip(trip, waiverId);
            default -> "Unknown change.";
        };
        if (problem == null) {
            PageFeedback.info("Required documents saved", "Changes here are saved immediately.");
            return true;
        }
        PageFeedback.error("Not saved", problem);
        return false;
    }

    private static String problemWith(final String title, final String bodyHtml, final String note) {
        if (title == null || title.isBlank()) {
            return "Give the document a title.";
        }
        if (title.trim().length() > MAX_TITLE) {
            return "The title is too long (at most " + MAX_TITLE + " characters).";
        }
        if (bodyHtml == null || WaiverHtml.isBlank(WaiverHtml.sanitize(bodyHtml))) {
            return "The document has no text.";
        }
        if (note != null && note.trim().length() > MAX_NOTE) {
            return "The note is too long (at most " + MAX_NOTE + " characters).";
        }
        return null;
    }

    private static WaiverVersion newVersion(final int number, final String title, final String bodyHtml,
            final String note, final Person.Id who, final LocalDateTime when) {
        final String clean = WaiverHtml.sanitize(bodyHtml);
        return WaiverVersion.builder()
                .number(number)
                .title(title.trim())
                .bodyHtml(clean)
                .sha256(WaiverHtml.sha256(clean))
                .publishedBy(who)
                .publishedAt(when)
                .note(note)
                .build();
    }

    private Result store(final WaiverDocument doc, final String message, final Caller caller) {
        try {
            if (!DAO.getInstance().saveWaiverDocument(doc)) {
                return refusal(Result.STORE_FAILED, "The document was not saved.");
            }
        } catch (final ConditionalCheckFailedException ex) {
            return refusal(Result.CONFLICT, CONFLICT_MESSAGE);
        } catch (final IllegalArgumentException ex) {
            return refusal(Result.VALIDATION_FAILED, ex.getMessage());
        } catch (final IOException ex) {
            log.error("Saving waiver document {} failed", doc.getId(), ex);
            return refusal(Result.STORE_FAILED, "The document was not saved.");
        }
        auditSource.get().waiver(AuditEventBuilder.TARGET_WAIVER, doc.getId(), doc.getOrgId(), message,
                caller.auditActor());
        return new Result(Result.OK, message, doc);
    }

    private static Result refusal(final String code, final String message) {
        return new Result(code, message, null);
    }

    // ================================================================== trip pins

    /** A trip's pin as its editor shows it: the document's title and whether a newer version exists. */
    public record PinRow(String waiverId, String title, int version, boolean acceptOlder, int latest,
            boolean retired, boolean missing) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        public boolean newerAvailable() {
            return !missing && latest > version;
        }
    }

    /** Whether the signed-in user may change which documents this trip requires. */
    public boolean canConfigure(final Trip trip) {
        if (trip == null || trip.getId() == null) {
            return false;
        }
        final Caller caller = callerSource.get();
        return caller.isAuthenticated()
                && (caller.has(PrivilegeCommands.REGISTRATION_ADMIN, trip.getId())
                    || caller.has(PrivilegeCommands.TRIP_MGR, trip.getId()));
    }

    /** The trip's pins with their documents' titles, in the trip's order. */
    public List<PinRow> pinRows(final Trip trip) {
        if (trip == null) {
            return List.of();
        }
        final List<PinRow> rows = new ArrayList<>();
        for (final TripWaiver pin : trip.getWaivers()) {
            final WaiverDocument doc = orgDocument(trip, pin.getWaiverId());
            rows.add(new PinRow(pin.getWaiverId(), doc == null ? "(missing document)" : titleAt(doc, pin.getVersion()),
                    pin.getVersion(), pin.isAcceptOlder(), doc == null ? 0 : doc.getCurrentNumber(),
                    doc != null && doc.isRetired(), doc == null));
        }
        return rows;
    }

    /** The trip org's documents a configurer may still add: not retired, not already required. */
    public List<WaiverDocument> choicesForTrip(final Trip trip) {
        if (!canConfigure(trip) || trip.getOrgId() == null) {
            return List.of();
        }
        return DAO.getInstance().getWaiverDocuments(trip.getOrgId(), Cached.YES).stream()
                .filter(doc -> !doc.isRetired() && trip.waiver(doc.getId()) == null)
                .toList();
    }

    /**
     * Requires a document on the trip at its newest version, older signatures accepted. Applied to the trip in
     * hand (the editor's draft) AND saved at once onto the stored trip, so the dialog's change is real whether
     * or not the rest of the editor is saved.
     *
     * @return null on success, else the refusal to show.
     */
    public String addToTrip(final Trip trip, final String waiverId) {
        if (!canConfigure(trip)) {
            return "Registration admin or trip manager required.";
        }
        final WaiverDocument doc = orgDocument(trip, waiverId);
        if (doc == null || doc.isRetired()) {
            return "That document is not available for this trip.";
        }
        if (trip.waiver(waiverId) != null) {
            return "The trip already requires '" + doc.getTitle() + "'.";
        }
        final List<TripWaiver> pins = new ArrayList<>(trip.getWaivers());
        pins.add(TripWaiver.builder().waiverId(waiverId).version(doc.getCurrentNumber()).acceptOlder(true)
                .addedBy(callerSource.get().personId()).added(LocalDateTime.now()).build());
        return persistPins(trip, pins, "Now requires '" + doc.getTitle() + "' v" + doc.getCurrentNumber() + ".");
    }

    /**
     * Moves a pin to another version of its document, with the administrator's answer to "do signatures of the
     * earlier version still count?" -- the decision the owner requires at exactly this moment.
     */
    public String moveVersion(final Trip trip, final String waiverId, final int version, final boolean acceptOlder) {
        if (!canConfigure(trip)) {
            return "Registration admin or trip manager required.";
        }
        final TripWaiver pin = trip.waiver(waiverId);
        final WaiverDocument doc = orgDocument(trip, waiverId);
        if (pin == null || doc == null || doc.version(version) == null) {
            return "That version is not available.";
        }
        final List<TripWaiver> pins = new ArrayList<>();
        for (final TripWaiver existing : trip.getWaivers()) {
            pins.add(existing.getWaiverId().equals(waiverId)
                    ? TripWaiver.builder().waiverId(waiverId).version(version).acceptOlder(acceptOlder)
                            .addedBy(existing.getAddedBy()).added(existing.getAdded()).build()
                    : existing);
        }
        return persistPins(trip, pins, "Moved '" + doc.getTitle() + "' to v" + version + " ("
                + (acceptOlder ? "earlier signatures still accepted" : "everyone must sign again") + ").");
    }

    /** Stops requiring a document. Signatures already given stay on record. */
    public String removeFromTrip(final Trip trip, final String waiverId) {
        if (!canConfigure(trip)) {
            return "Registration admin or trip manager required.";
        }
        if (trip.waiver(waiverId) == null) {
            return null;
        }
        final WaiverDocument doc = orgDocument(trip, waiverId);
        final List<TripWaiver> pins = new ArrayList<>(trip.getWaivers());
        pins.removeIf(pin -> waiverId.equals(pin.getWaiverId()));
        return persistPins(trip, pins,
                "No longer requires '" + (doc == null ? waiverId : doc.getTitle()) + "'.");
    }

    /**
     * Replaces every pin at once (the REST edge and the browser tests' fixtures): each must name one of the
     * trip org's documents and an existing version; a repeated document is refused.
     */
    public String replacePins(final Trip trip, final List<TripWaiver> wanted) {
        if (!canConfigure(trip)) {
            return "Registration admin or trip manager required.";
        }
        final Map<String, TripWaiver> byId = new LinkedHashMap<>();
        for (final TripWaiver pin : (wanted == null) ? List.<TripWaiver>of() : wanted) {
            final WaiverDocument doc = orgDocument(trip, pin.getWaiverId());
            if (doc == null || doc.version(pin.getVersion()) == null) {
                return "Unknown document or version: " + pin.getWaiverId() + " v" + pin.getVersion() + ".";
            }
            if (byId.containsKey(pin.getWaiverId())) {
                return "A document is listed twice: " + doc.getTitle() + ".";
            }
            final TripWaiver existing = trip.waiver(pin.getWaiverId());
            byId.put(pin.getWaiverId(), TripWaiver.builder().waiverId(pin.getWaiverId()).version(pin.getVersion())
                    .acceptOlder(pin.isAcceptOlder())
                    .addedBy(existing == null ? callerSource.get().personId() : existing.getAddedBy())
                    .added(existing == null ? LocalDateTime.now() : existing.getAdded()).build());
        }
        return persistPins(trip, new ArrayList<>(byId.values()), "Required documents set to "
                + byId.values().stream().map(p -> p.getWaiverId() + " v" + p.getVersion()).toList() + ".");
    }

    private String persistPins(final Trip trip, final List<TripWaiver> pins, final String message) {
        final DAO dao = DAO.getInstance();
        final Optional<Trip> stored = dao.getTrip(trip.getId(), Cached.NO);
        try {
            if (stored.isPresent()) {
                // Only the pins move on the stored row: the editor's other unsaved changes stay the editor's.
                final Trip fresh = stored.get();
                fresh.setWaivers(pins);
                if (!dao.saveTrip(fresh)) {
                    return "The trip was not saved.";
                }
            }
        } catch (final IOException | RuntimeException ex) {
            log.error("Saving the required documents of trip {} failed", trip.getId(), ex);
            return "The trip was not saved.";
        }
        trip.setWaivers(pins);
        auditSource.get().waiver(AuditEventBuilder.TARGET_TRIP, trip.getId(), trip.getOrgId(),
                "Trip '" + trip.getTitle() + "': " + message, callerSource.get().auditActor());
        return null;
    }

    private static WaiverDocument orgDocument(final Trip trip, final String waiverId) {
        if (trip == null || trip.getOrgId() == null || waiverId == null) {
            return null;
        }
        return DAO.getInstance().getWaiverDocument(trip.getOrgId(), waiverId, Cached.YES).orElse(null);
    }

    private static String titleAt(final WaiverDocument doc, final int version) {
        final WaiverVersion text = doc.version(version);
        return (text == null || text.getTitle() == null) ? doc.getTitle() : text.getTitle();
    }

    // ================================================================== standing

    /**
     * Every document the trip requires, as this traveler stands against it, in the trip's order. A pin whose
     * document has vanished is skipped (there is nothing to sign); no gate here, because the texts are what
     * any registrant reads and the standing is the subject's own -- callers gate the SUBJECT.
     */
    public List<WaiverRequirement> requirements(final Trip trip, final Person.Id personId) {
        if (trip == null || trip.getWaivers().isEmpty()) {
            return List.of();
        }
        final List<WaiverAcceptance> mine = (personId == null)
                ? List.of() : DAO.getInstance().getWaiverAcceptances(trip.getId(), personId);
        final List<WaiverRequirement> out = new ArrayList<>();
        for (final TripWaiver pin : trip.getWaivers()) {
            final WaiverDocument doc = orgDocument(trip, pin.getWaiverId());
            final WaiverVersion text = (doc == null) ? null : doc.version(pin.getVersion());
            if (text == null) {
                continue;
            }
            final List<WaiverAcceptance> forDoc = mine.stream()
                    .filter(a -> pin.getWaiverId().equals(a.getWaiverId())).toList();
            out.add(new WaiverRequirement(pin.getWaiverId(), titleAt(doc, pin.getVersion()), pin.getVersion(),
                    pin.isAcceptOlder(), text.getBodyHtml(), text.getSha256(), standingOf(pin, forDoc),
                    decidingAcceptance(pin, forDoc)));
        }
        return out;
    }

    /** The requirements this traveler still has to sign. */
    public List<WaiverRequirement> pendingFor(final Trip trip, final Person.Id personId) {
        return requirements(trip, personId).stream().filter(r -> !r.isSatisfied()).toList();
    }

    /** Whether this traveler may register as far as documents go: every requirement signed (or signed earlier). */
    public boolean isSatisfied(final Trip trip, final Person.Id personId) {
        return pendingFor(trip, personId).isEmpty();
    }

    /** The registrations page's one-cell summary: blank with no documents, else "Signed" or what is missing. */
    public String statusText(final Trip trip, final Person.Id personId) {
        final List<WaiverRequirement> reqs = requirements(trip, personId);
        if (reqs.isEmpty()) {
            return "";
        }
        final long missing = reqs.stream().filter(r -> r.getStanding() == Standing.MISSING).count();
        final long outdated = reqs.stream().filter(r -> r.getStanding() == Standing.OUTDATED).count();
        if (missing == 0 && outdated == 0) {
            return reqs.stream().anyMatch(r -> r.getStanding() == Standing.SIGNED_OLDER)
                    ? "Signed (earlier version)" : "Signed";
        }
        final List<String> parts = new ArrayList<>();
        if (missing > 0) {
            parts.add("Missing " + missing);
        }
        if (outdated > 0) {
            parts.add("Must re-sign " + outdated);
        }
        return String.join(", ", parts);
    }

    static Standing standingOf(final TripWaiver pin, final List<WaiverAcceptance> forDoc) {
        boolean older = false;
        for (final WaiverAcceptance a : forDoc) {
            if (a.getVersion() >= pin.getVersion()) {
                return Standing.SIGNED;
            }
            older = true;
        }
        if (!older) {
            return Standing.MISSING;
        }
        return pin.isAcceptOlder() ? Standing.SIGNED_OLDER : Standing.OUTDATED;
    }

    private static WaiverAcceptance decidingAcceptance(final TripWaiver pin, final List<WaiverAcceptance> forDoc) {
        return forDoc.stream()
                .filter(a -> a.getVersion() == pin.getVersion())
                .findFirst()
                .orElseGet(() -> forDoc.stream().max(Comparator.comparingInt(WaiverAcceptance::getVersion))
                        .orElse(null));
    }

    // ================================================================== signing

    /**
     * Records that the signed-in person agrees, for {@code subject}, to the trip's pinned version of a document.
     * Refused unless both boxes were ticked, the caller may act for the subject (themselves, someone they manage,
     * or a site admin -- the registration rule), and {@code version} is exactly the trip's pin. Signing the same
     * version twice answers the first row; nothing is ever overwritten.
     */
    public AcceptResult accept(final Trip trip, final Person.Id subject, final String waiverId, final int version,
            final boolean agreed, final boolean authorityAttested, final ClientInfo client) {
        final Caller caller = callerSource.get();
        if (!caller.isAuthenticated()) {
            return new AcceptResult(AcceptResult.NOT_ALLOWED, "Sign in to agree to documents.", null);
        }
        if (trip == null || subject == null) {
            return new AcceptResult(AcceptResult.NOT_FOUND, "Unknown trip or traveler.", null);
        }
        if (!agreed || !authorityAttested) {
            return new AcceptResult(AcceptResult.MISSING_ATTESTATION,
                    "Tick both boxes to agree to the document.", null);
        }
        final Person me = DAO.getInstance().getPerson(caller.personId(), Cached.NO).orElse(null);
        if (me == null || !mayActFor(caller, me, subject)) {
            // A signature names a real signer: an account with no person row (a bare site-admin login) cannot sign.
            return new AcceptResult(AcceptResult.NOT_ALLOWED, "You cannot sign for this traveler.", null);
        }
        final TripWaiver pin = trip.waiver(waiverId);
        if (pin == null || pin.getVersion() != version) {
            return new AcceptResult(AcceptResult.NOT_PINNED,
                    "This document has changed. Reload the page and read the current version.", null);
        }
        final WaiverDocument doc = orgDocument(trip, waiverId);
        final WaiverVersion text = (doc == null) ? null : doc.version(version);
        final Person traveler = DAO.getInstance().getPerson(subject, Cached.NO).orElse(null);
        if (text == null || traveler == null) {
            return new AcceptResult(AcceptResult.NOT_FOUND, "Unknown document or traveler.", null);
        }
        final ClientInfo from = (client == null) ? new ClientInfo(WaiverAcceptance.Client.API, null, null) : client;
        final WaiverAcceptance row = WaiverAcceptance.builder()
                .tripId(trip.getId())
                .personId(subject)
                .waiverId(waiverId)
                .version(version)
                .title(titleAt(doc, version))
                .sha256(text.getSha256())
                .signerId(me.getId())
                .signerName(fullName(me))
                .signerEmail(me.getEmail())
                .subjectName(fullName(traveler))
                .agreed(true)
                .authorityAttested(true)
                .acceptedAt(Instant.now())
                .client(from.client())
                .ip(from.ip())
                .userAgent(from.userAgent())
                .orgId(trip.getOrgId())
                .build();
        final boolean created;
        try {
            created = DAO.getInstance().saveWaiverAcceptance(row);
        } catch (final IOException | RuntimeException ex) {
            log.error("Recording {}'s agreement to {} v{} on trip {} failed", subject, waiverId, version,
                    trip.getId(), ex);
            return new AcceptResult(AcceptResult.STORE_FAILED, "Your agreement was not recorded. Try again.", null);
        }
        if (!created) {
            return new AcceptResult(AcceptResult.OK, null, existing(trip.getId(), subject, waiverId, version, row));
        }
        auditSource.get().waiverAccepted(traveler, trip, row.getTitle(), version, row.isOnBehalf(),
                caller.auditActor());
        return new AcceptResult(AcceptResult.OK, null, row);
    }

    /**
     * Applies a registration submit's answers for one traveler: every agreement with both boxes ticked whose
     * version is the trip's pin and is not yet satisfied. Anything else is ignored -- the gate after this decides.
     *
     * @return the refusals, by document title (empty when every applied agreement was recorded).
     */
    public List<String> applyAgreements(final Trip trip, final Person.Id subject, final List<Agreement> agreements,
            final ClientInfo client) {
        final List<String> problems = new ArrayList<>();
        if (trip == null || agreements == null || agreements.isEmpty()) {
            return problems;
        }
        for (final WaiverRequirement req : pendingFor(trip, subject)) {
            for (final Agreement agreement : agreements) {
                if (agreement != null && req.getWaiverId().equals(agreement.waiverId())
                        && agreement.version() == req.getVersion()
                        && agreement.agreed() && agreement.authorityAttested()) {
                    final AcceptResult result = accept(trip, subject, req.getWaiverId(), req.getVersion(), true,
                            true, client);
                    if (!result.ok()) {
                        problems.add(req.getTitle() + ": " + result.message());
                    }
                }
            }
        }
        return problems;
    }

    /**
     * The website's checkboxes, kept in the registration draft as one flat map, turned into agreements per
     * traveler. Keys are {@link #checkKey}s; a document counts only when BOTH of its boxes are ticked.
     */
    public static Map<String, List<Agreement>> agreementsFromChecks(final Map<String, Object> checks) {
        final Map<String, List<Agreement>> out = new LinkedHashMap<>();
        if (checks == null) {
            return out;
        }
        for (final Map.Entry<String, Object> entry : checks.entrySet()) {
            final String[] parts = entry.getKey().split("\\|");
            if (parts.length != 4 || !"agree".equals(parts[3]) || !Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            final String attestKey = parts[0] + "|" + parts[1] + "|" + parts[2] + "|attest";
            if (!Boolean.TRUE.equals(checks.get(attestKey))) {
                continue;
            }
            try {
                out.computeIfAbsent(parts[0], k -> new ArrayList<>())
                        .add(new Agreement(parts[1], Integer.parseInt(parts[2]), true, true));
            } catch (final NumberFormatException ex) {
                log.debug("Ignoring malformed waiver check key {}", entry.getKey());
            }
        }
        return out;
    }

    /** The draft-map key of one checkbox: {@code personId|waiverId|version|agree} or {@code ...|attest}. */
    public static String checkKey(final String personId, final String waiverId, final int version,
            final String part) {
        return personId + "|" + waiverId + "|" + version + "|" + part;
    }

    /**
     * The registration page's per-traveler "Sign" button for someone already registered: applies that traveler's
     * ticked boxes from the draft and growls the outcome.
     */
    public void signFromPage(final Trip trip, final String personId, final Map<String, Object> checks) {
        if (trip == null || personId == null) {
            return;
        }
        final List<Agreement> mine = agreementsFromChecks(checks).getOrDefault(personId, List.of());
        if (mine.isEmpty()) {
            PageFeedback.warn("Not signed", "Tick both boxes under each document first.");
            return;
        }
        final List<String> problems = applyAgreements(trip, Person.Id.from(personId), mine, ClientInfo.web());
        if (problems.isEmpty()) {
            PageFeedback.info("Signed", "Thank you. Your agreement has been recorded.");
        } else {
            PageFeedback.error("Not signed", String.join(" ", problems));
        }
    }

    private static boolean mayActFor(final Caller caller, final Person me, final Person.Id subject) {
        return caller.isSiteAdmin() || (me != null && PersonCommands.getPersonCommands().canAccessUserId(me, subject));
    }

    private static WaiverAcceptance existing(final String tripId, final Person.Id subject, final String waiverId,
            final int version, final WaiverAcceptance fallback) {
        return DAO.getInstance().getWaiverAcceptances(tripId, subject).stream()
                .filter(a -> waiverId.equals(a.getWaiverId()) && a.getVersion() == version)
                .findFirst().orElse(fallback);
    }

    static String fullName(final Person person) {
        final String first = (person.getFirst() == null) ? "" : person.getFirst().trim();
        final String last = (person.getLast() == null) ? "" : person.getLast().trim();
        final String full = (first + " " + last).trim();
        return full.isEmpty() ? person.getPreferredName() : full;
    }

    // ================================================================== records and the signed copy

    /**
     * Whether the signed-in user may read a traveler's signatures on a trip: the traveler, someone who manages
     * them, the trip's registration admin or manager, or a site admin.
     */
    public boolean canReadRecords(final Trip trip, final Person.Id subject) {
        final Caller caller = callerSource.get();
        if (trip == null || subject == null || !caller.isAuthenticated()) {
            return false;
        }
        if (caller.has(PrivilegeCommands.REGISTRATION_ADMIN, trip.getId())
                || caller.has(PrivilegeCommands.TRIP_MGR, trip.getId())) {
            return true;
        }
        final Person me = DAO.getInstance().getPerson(caller.personId(), Cached.NO).orElse(null);
        return mayActFor(caller, me, subject);
    }

    /** Every signature a traveler gave on this trip, oldest version first; empty for a caller who may not read. */
    public List<WaiverAcceptance> acceptancesFor(final Trip trip, final Person.Id subject) {
        if (!canReadRecords(trip, subject)) {
            return List.of();
        }
        return DAO.getInstance().getWaiverAcceptances(trip.getId(), subject);
    }

    /**
     * The signed copy as a PDF: the version's text and the signature record. Null when the caller may not read
     * it or there is no such signature.
     */
    public byte[] signedPdf(final String tripId, final Person.Id subject, final String waiverId, final int version) {
        final Trip trip = (tripId == null) ? null : DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        if (trip == null || waiverId == null || !canReadRecords(trip, subject)) {
            return null;
        }
        final WaiverAcceptance row = DAO.getInstance().getWaiverAcceptances(tripId, subject).stream()
                .filter(a -> waiverId.equals(a.getWaiverId()) && a.getVersion() == version)
                .findFirst().orElse(null);
        final WaiverDocument doc = orgDocument(trip, waiverId);
        final WaiverVersion text = (doc == null) ? null : doc.version(version);
        if (row == null || text == null) {
            return null;
        }
        final Organization org = trip.getOrganization();
        try {
            return WaiverPdf.render(trip, org == null ? null : org.getName(), text, row);
        } catch (final IOException ex) {
            log.error("Rendering the signed copy of {} v{} for {} failed", waiverId, version, subject, ex);
            return null;
        }
    }

    /** The website's download link for a signature (extension-less: {@code PdfRedirectFilter}). */
    public static String pdfPath(final WaiverAcceptance acceptance) {
        return "/waivers/signed?trip=" + acceptance.getTripId() + "&person=" + acceptance.getPersonId().getValue()
                + "&waiver=" + acceptance.getWaiverId() + "&version=" + acceptance.getVersion();
    }

    /** {@link #pdfPath} for EL, which cannot call a static method on a bean. */
    public String pdfLink(final WaiverAcceptance acceptance) {
        return (acceptance == null) ? null : pdfPath(acceptance);
    }

    /** {@link #checkKey} for EL. */
    public String key(final String personId, final String waiverId, final int version, final String part) {
        return checkKey(personId, waiverId, version, part);
    }

    /** {@link #authorityText} for EL. */
    public String authority(final String travelerName) {
        return authorityText(travelerName);
    }

    /** {@link #authorityText} naming a traveler by their full name, for EL. */
    public String authorityFor(final Person traveler) {
        return authorityText(traveler == null ? null : fullName(traveler));
    }

    /** "Signed v2 on Oct 27, 2026 by Ken Parent" (UTC date), for the registration page. */
    public String signedLine(final WaiverAcceptance acceptance) {
        if (acceptance == null) {
            return "";
        }
        final String when = (acceptance.getAcceptedAt() == null) ? "" : " on " + SIGNED_DATE.format(
                acceptance.getAcceptedAt());
        return "Signed v" + acceptance.getVersion() + when
                + (acceptance.getSignerName() == null ? "" : " by " + acceptance.getSignerName());
    }

    /** {@link #agreeText} for EL. */
    public String agree(final String title) {
        return agreeText(title);
    }
}
