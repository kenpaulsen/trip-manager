package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.chat.ChatNotifications;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.PhotoRejectedException;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatAppearance;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatEmoji;
import org.paulsens.trip.model.chat.ChatInvite;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMentions;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatNotifyPref;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatQuote;
import org.paulsens.trip.model.chat.ChatReaction;
import org.paulsens.trip.model.chat.ChatReactionSummary;
import org.paulsens.trip.model.chat.ChatSettings;
import org.paulsens.trip.model.chat.ChatVisibility;
import org.paulsens.trip.security.Digests;
import org.paulsens.trip.util.RandomData;
import org.paulsens.trip.util.ScopeUtil;

/**
 * Chat operations for both JSF pages and the JAX-RS resource. Capture {@link AuditActor} at the top of any
 * method that may hop threads.
 */
@Slf4j
@Named("chat")
@ApplicationScoped
public class ChatCommands {

    private static final String CHAT_ADMIN_PRIV = "chatAdmin";
    private static final String CHAT_MGR_PRIV = "chatMgr";

    public static final String MEDIA_TYPE_V1 = "application/vnd.trip.chat.v1+json";
    public static final String CSRF_HEADER = "X-Trip-Chat";

    /** How often a non-administrator may have an @all actually emailed to the trip. */
    private static final java.time.Duration EVERYONE_WINDOW = java.time.Duration.ofHours(24);

    /**
     * The instance used off the JSF request path (the REST edge, and any future socket). Held statically because
     * {@code getChatCommands()} previously constructed a fresh instance per call whenever no {@code FacesContext}
     * existed -- which is every REST request. Anything instance-scoped therefore reset on every request.
     */
    private static volatile ChatCommands shared;

    private final ChatRateLimiter rateLimiter;
    private final ConfigCommands config;

    public ChatCommands() {
        this(new ChatRateLimiter(DAO.getInstance().getCacheClient()));
    }

    /** Test constructor. */
    public ChatCommands(final ChatRateLimiter rateLimiter) {
        this(rateLimiter, new ConfigCommands());
    }

    public ChatCommands(final ChatRateLimiter rateLimiter, final ConfigCommands config) {
        this.rateLimiter = rateLimiter;
        this.config = config;
    }

    public static ChatCommands getChatCommands() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            final Map<String, Object> appMap = ctx.getExternalContext().getApplicationMap();
            return (ChatCommands) appMap.computeIfAbsent("chat", key -> new ChatCommands());
        }
        return sharedInstance();
    }

    private static ChatCommands sharedInstance() {
        ChatCommands local = shared;
        if (local == null) {
            synchronized (ChatCommands.class) {
                local = shared;
                if (local == null) {
                    local = new ChatCommands();
                    shared = local;
                }
            }
        }
        return local;
    }

    // --- channel lifecycle ---

    public ChatChannel ensureChannel(final String tripId, final AuditActor actor) {
        final ChatChannel.Id id = ChatChannel.Id.forTrip(tripId);
        final Optional<ChatChannel> existing = dao().getChatChannel(id);
        if (existing.isPresent()) {
            return existing.get();
        }
        final Trip trip = dao().getTrip(tripId).orElse(null);
        final String title = trip == null || trip.getTitle() == null ? "Trip chat" : trip.getTitle() + " chat";
        final ChatChannel created = new ChatChannel(
                id, tripId, ChatChannel.Kind.TRIP, title, null, null,
                ChatSettings.defaults(), Instant.now(),
                actor == null ? null : actor.id(), null, null);
        dao().saveChatChannel(created);
        audit(AuditAction.CHAT_ADMIN, actor, AuditEventBuilder.TARGET_CHAT_CHANNEL, id.getValue(),
                "channel created");
        return created;
    }

    /**
     * The channel for rendering a page: the stored one if it exists, otherwise an unsaved default so the page has
     * something to read settings from.
     *
     * <p>Deliberately does <b>not</b> persist. Rendering a page is not an administrative act, and creating here
     * wrote a "channel created" audit record attributed to whoever opened the tab first -- or, when the page passed
     * no actor, to nobody at all. The channel becomes real on the first send.
     */
    public ChatChannel channelForPage(final String tripId) {
        final ChatChannel existing = getChannel(tripId);
        if (existing != null) {
            return existing;
        }
        final Trip trip = dao().getTrip(tripId).orElse(null);
        final String title = trip == null || trip.getTitle() == null ? "Trip chat" : trip.getTitle() + " chat";
        return new ChatChannel(ChatChannel.Id.forTrip(tripId), tripId, ChatChannel.Kind.TRIP, title,
                null, null, ChatSettings.defaults(), Instant.EPOCH, null, null, null);
    }

    public ChatChannel getChannel(final String tripId) {
        return dao().getChatChannel(ChatChannel.Id.forTrip(tripId)).orElse(null);
    }

    // --- authorization helpers ---

    /**
     * Whether the SIGNED-IN user may administer this chat (the JSF/EL entry).
     *
     * <p>This used to take a {@code Person.Id} it never consulted -- every call site happened to pass the
     * current user's id, so the answers were right by coincidence while the signature promised a per-person
     * check it did not perform. There is deliberately no id parameter now: a caller that needs "may THIS
     * person administer" has no method to mistake for it, and would add one against
     * {@code PrivilegeCommands.check} (noting that the site-admin role lives in the HTTP session, so it is
     * only knowable for the signed-in user).
     */
    public boolean canAdminister(final String tripId) {
        // JSF path: the role is reachable through FacesContext here, which is what Caller.current() reads.
        return canAdminister(tripId, Caller.current());
    }

    /**
     * Whether {@code caller} may administer this chat.
     *
     * <p>Takes a {@link Caller} rather than a {@code Person.Id} plus a {@code siteAdminHint} boolean. The hint
     * existed because the site-admin role lives in the HTTP session and the usual check reads it through
     * {@code FacesContext}, which does not exist on the REST edge -- so without it a site administrator was
     * silently refused every privileged action there. A boolean meaning "trust me, I checked" is a poor way to
     * carry that: it has to be threaded through every method, and a caller passing {@code false} by omission
     * looks identical to one that genuinely is not an administrator.
     *
     * <p>{@code Caller} resolves the same fact once, per edge, and caches its privilege answers -- so the two
     * lookups below cost one each per request no matter how many messages are checked.
     */
    public boolean canAdminister(final String tripId, final Caller caller) {
        if (caller == null || caller.personId() == null) {
            return false;
        }
        // Caller.has already short-circuits on site-admin, so the role is not consulted separately.
        return caller.has(CHAT_ADMIN_PRIV) || caller.has(CHAT_MGR_PRIV, tripId);
    }

    /**
     * Gate for every moderation operation. Enforced <b>here, in the bean</b>, not only at the REST resource and
     * certainly not only by an XHTML {@code rendered=} attribute -- that hides a button, it does not stop a
     * postback. Every mutating admin method below calls this before touching anything, so a caller that forgets
     * cannot escalate. Denials are audited as failures, because an attempted moderation is worth seeing.
     */
    /**
     * The actor a {@link Caller} represents.
     *
     * <p>These methods used to take an {@code AuditActor} AND a {@code Caller}, which is the same person asked
     * for twice -- and two parameters that must agree are two parameters that can disagree. A caller carries
     * its own actor, so identity and authorization now come from one object and cannot drift apart.
     *
     * <p>The types stay separate on purpose, though. {@code AuditActor} is an immutable value built to cross
     * async boundaries -- {@code MailCommands} carries one into an SES completion callback deliberately --
     * whereas a {@code Caller} holds a request-scoped privilege cache that must NOT outlive its request. Making
     * one a subtype of the other would remove the type system's only objection to carrying a Caller somewhere
     * its cached answers are already stale.
     */
    private static AuditActor actorOf(final Caller caller) {
        return caller == null ? AuditActor.current() : caller.auditActor();
    }

    private boolean denyUnlessAdmin(
            final String tripId, final String what, final Caller caller) {
        final AuditActor who = actorOf(caller);
        // Identity may come from the ACTOR rather than the caller. These methods are reachable with only an
        // AuditActor -- from a background thread, or a test -- where Caller.current() finds nobody; falling
        // back to the actor's own id is what lets those paths still authorize as the person they name.
        final Caller effective = (caller != null && caller.personId() != null) ? caller : Caller.forActor(who);
        if (canAdminister(tripId, effective)) {
            return false;
        }
        log.warn("Denied chat admin operation '{}' on trip {} for actor {}", what, tripId,
                who == null ? null : who.email());
        Audit.log(Audit.builder(AuditAction.CHAT_ADMIN, AuditOutcome.FAILURE)
                .actor(who == null ? AuditActor.current() : who)
                .target(AuditEventBuilder.TARGET_CHAT_CHANNEL, ChatChannel.Id.forTrip(tripId).getValue())
                .message("denied: " + what + " (chatMgr required)")
                .build());
        growlError("You do not have permission to administer this chat.");
        return true;
    }

    public boolean hasChatMgr(final String tripId) {
        final List<Person.Id> holders = new PrivilegeCommands()
                .getPeopleWithPriv(List.of("chatMgr"), tripId);
        return !holders.isEmpty();
    }

    public boolean isTripMember(final String tripId, final Person.Id personId) {
        if (tripId == null || personId == null) {
            return false;
        }
        final Trip trip = dao().getTrip(tripId).orElse(null);
        if (trip == null) {
            return false;
        }
        if (trip.getPeople().contains(personId)) {
            return true;
        }
        final PrivilegeCommands priv = new PrivilegeCommands();
        final PersonCommands people = PersonCommands.getPersonCommands();
        if (people.hasRole("admin") || priv.check("tripView", tripId, personId)) {
            return true;
        }
        // Full family membership: ANYONE in a family with someone on the trip participates in its chat as
        // themselves (author stays the signed-in user -- the send contract is untouched). The family row is
        // the source of truth; the managedUsers loop below stays only for legacy persons not yet migrated
        // into a family.
        final Person person = people.getPerson(personId);
        if (familyMemberOnTrip(person, trip)) {
            return true;
        }
        for (final Person.Id managedId : person.getManagedUsers()) {
            if (trip.getPeople().contains(managedId)) {
                return true;
            }
        }
        return false;
    }

    /** Whether anyone in this person's family (themselves included) is on the trip's roster. */
    private boolean familyMemberOnTrip(final Person person, final Trip trip) {
        if (person == null || person.getFamilyId() == null) {
            return false;
        }
        final Family family = dao().getFamily(person.getFamilyId()).orElse(null);
        return family != null && family.getMemberIds().stream().anyMatch(trip.getPeople()::contains);
    }

    /**
     * Everyone whose trips this person's chat list must include besides their own: the whole family, plus
     * legacy {@code managedUsers} for persons not yet migrated into a family. Never contains the person.
     */
    private Set<Person.Id> householdOf(final Person.Id personId) {
        final Person person = PersonCommands.getPersonCommands().getPerson(personId);
        final Set<Person.Id> out = new LinkedHashSet<>(person.getManagedUsers());
        if (person.getFamilyId() != null) {
            dao().getFamily(person.getFamilyId()).ifPresent(family -> out.addAll(family.getMemberIds()));
        }
        out.remove(personId);
        return out;
    }

    /**
     * The chat page's trip resolution, layered over {@code TripCommands.getTripForUser}: that method serves
     * page-level trip visibility, so for someone with chat access but no page access — an invite-link guest,
     * or a family member who manages nobody — it answers null, or worse, silently falls back to a DIFFERENT
     * trip the person can see (the URL said one trip and the page showed another). When {@code me} may
     * participate in the REQUESTED trip's chat, the requested trip wins. In Java rather than the page's init
     * script because the jsft parser cannot safely combine {@code ==} with a method call in one condition.
     */
    public Trip tripForChatPage(final Trip resolved, final String tripId, final Person.Id me) {
        if (resolved != null && tripId != null && tripId.equals(resolved.getId())) {
            return resolved;
        }
        if (canParticipate(tripId, me)) {
            return dao().getTrip(tripId).orElse(resolved);
        }
        return resolved;
    }

    /**
     * The one definition of "may participate in this trip's chat": a trip member in the {@link #isTripMember}
     * sense, or a guest whose invite-created membership row is still JOINED.
     *
     * <p>Only a <em>guest-marked</em> row grants access. A plain JOINED row (written by {@code rejoin} or the
     * roster backfill) never does, so no code path that materialises ordinary rows can become a back door.
     */
    public boolean canParticipate(final String tripId, final Person.Id personId) {
        if (isTripMember(tripId, personId)) {
            return true;
        }
        if (tripId == null || personId == null) {
            return false;
        }
        return guestJoined(dao().getChatMembership(ChatChannel.Id.forTrip(tripId), personId).orElse(null));
    }

    private static boolean guestJoined(final ChatMembership row) {
        return row != null && row.isJoined() && row.isGuest();
    }

    /**
     * The reader's membership row, or empty for an implicit member (absent row ⇒ JOINED, which is what makes
     * default opt-in free). Empty is therefore <em>not</em> "no access" — callers must pair this with
     * {@link #isTripMember} and {@link #canRead}, and pass the result to {@code ChatVisibility}, which is the one
     * place allowed to decide what an implicit member may see.
     */
    public Optional<ChatMembership> membershipRow(final ChatChannel.Id channelId, final Person.Id personId) {
        return dao().getChatMembership(channelId, personId);
    }

    /**
     * The membership row or {@code null}, for XHTML/JSFT expressions.
     *
     * <p>JSFT's EL has no notion of {@code Optional}: {@code membership != null} is true for an <em>empty</em>
     * Optional, so the next {@code .state} dereference throws and aborts the whole {@code initPage} block -- which
     * surfaces as the page silently redirecting home rather than as an error. Pages must use this, never the
     * Optional-returning form.
     */
    public ChatMembership membershipFor(final ChatChannel.Id channelId, final Person.Id personId) {
        return membershipRow(channelId, personId).orElse(null);
    }

    /*
     * The three accessors below exist so the chat pages can put ONLY strings and booleans in viewScope.
     *
     * A model object in viewScope is a model object in the HTTP session, and the session is replicated through the
     * Redisson session manager, which runs in Tomcat's SHARED classloader -- it cannot see webapp classes at all.
     * Storing a ChatChannel produced a steady drip of
     * "SEVERE [redisson-3-14] Unable to handle topic message ... ClassNotFoundException:
     * org.paulsens.trip.model.chat.ChatChannel"
     * because the attribute broadcast is deserialised on a Redisson thread. Note the failure mode: not a changed
     * class, not a serialVersionUID mismatch -- the class is simply absent from that classloader, so it can never
     * work, for any webapp type, no matter how Serializable it is.
     *
     * This is the rule the design already stated ("only channel id, admin flag and trip go in viewScope") and it
     * is easy to break, because putting the object there reads as the obvious thing to do.
     */

    /** The channel's history setting, for the page banner, without putting a channel in viewScope. */
    public boolean fullHistoryForTrip(final String tripId) {
        final ChatChannel channel = channelForPage(tripId);
        return channel != null && channel.getSettings().isFullHistoryForNewMembers();
    }

    /**
     * This person's membership state as a plain string, or {@code ""} when they have no row.
     *
     * <p>Empty is not "no access": an absent row means implicitly JOINED (§4). The page uses this only to decide
     * between the composer, a Rejoin button and "an administrator must re-add you".
     */
    public String membershipStateForTrip(final String tripId, final Person.Id personId) {
        final ChatMembership row = membershipFor(ChatChannel.Id.forTrip(tripId), personId);
        return row == null ? "" : row.getState().name();
    }

    /**
     * Why this person cannot post right now, as a sentence, or {@code ""} when they can.
     *
     * <p>The page hides the composer on a non-empty answer and shows the sentence instead. It asks the same
     * private denial the send path uses, so the control and the enforcement cannot drift into disagreeing —
     * a composer that accepts text and then refuses it is worse than one that is not there.
     */
    public String postDenialForTrip(final String tripId, final Person.Id personId) {
        final ChatChannel channel = channelForPage(tripId);
        if (channel == null || personId == null) {
            return "This chat is not available.";
        }
        final Instant now = Instant.now();
        if (ChatVisibility.isArchived(channel, tripOf(channel), now)) {
            return "This chat is closed. The trip is over, so it is read-only now.";
        }
        if (channel.getSettings().getPostPolicy() == ChatSettings.PostPolicy.ADMINS_ONLY
                && !canAdminister(tripId)) {
            return "Only trip administrators can post in this chat.";
        }
        final ChatMembership row = membershipFor(channel.getId(), personId);
        if (row != null && row.isMuted(now)) {
            return "You are muted and cannot post right now.";
        }
        return "";
    }

    /** The look this person gets for this chat: their override per field, else the channel's default. */
    // --- appearance choices and the mention roster ---

    /**
     * The background colours suggested by the settings dialog's color picker.
     *
     * <p>Suggestions rather than the whole choice: the picker offers these as its swatch row but accepts any
     * color, and {@link ChatAppearance}'s validator is what keeps a saved value safe for a {@code style}
     * attribute. Entries that would not survive that validator are dropped here, so a typo in the setting
     * removes one swatch rather than offering a suggestion that silently does nothing.
     */
    public List<String> getBackgroundColorChoices() {
        return Arrays.stream(config.getString(KnownSettings.CHAT_BACKGROUND_COLORS).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .filter(value -> new ChatAppearance(value, null).getBackgroundColor() != null)
                .distinct()
                .toList();
    }

    /** {@link #getBackgroundColorChoices} comma-joined the way the color picker's swatches attribute wants. */
    public String getBackgroundColorSwatches() {
        return String.join(",", getBackgroundColorChoices());
    }

    /** The image shown when neither the trip nor the person has chosen one. Blank means no image. */
    public String getDefaultBackgroundImage() {
        return new ChatAppearance(null, config.getString(KnownSettings.CHAT_BACKGROUND_IMAGE))
                .getBackgroundImageUrl();
    }

    /** The channel's description, shown above the conversation. Empty when there is none. */
    public String descriptionForTrip(final String tripId) {
        final ChatChannel channel = channelForPage(tripId);
        final String description = channel == null ? null : channel.getDescription();
        return description == null ? "" : description;
    }

    /**
     * The trip's people as a JSON array for the composer's mention autocomplete:
     * {@code [{"id":..,"name":label,"search":"first last preferred"}]}.
     *
     * <p>Sent with the page rather than fetched, because the roster is small and the alternative is a request on
     * the first keystroke of every mention.
     *
     * <p><b>{@code name} is unique across the roster, and that is load-bearing rather than cosmetic.</b> The
     * composer inserts the label as the visible token and swaps the tokens back to {@code @{id}} by exact text
     * replacement at send time, so two people sharing a label is not an ambiguity the reader has to resolve --
     * it silently mentions <em>one</em> of them twice, mailing the wrong person. {@link #uniqueLabels} therefore
     * disambiguates before the roster ever reaches the page; see it for the ladder.
     *
     * <p><b>Escaped for a script element, not just for JSON.</b> These are user-supplied names going inside
     * {@code &lt;script&gt;}, where a literal {@code &lt;/script&gt;} in a name would end the block and turn the
     * rest into markup -- valid JSON and stored XSS at the same time. Jackson does not escape {@code <} by
     * default, so it is replaced explicitly, along with the two line separators that are legal in JSON but not
     * in a JavaScript string literal.
     */
    public String rosterJsonForTrip(final String tripId) {
        final Trip trip = dao().getTrip(tripId).orElse(null);
        if (trip == null) {
            return "[]";
        }
        final PersonCommands people = PersonCommands.getPersonCommands();
        // Roster ∪ explicit JOINED rows: family managers participate without being on the trip roster, and
        // the mention autocomplete must be able to name anyone who can post here.
        final java.util.LinkedHashSet<Person.Id> ids = new java.util.LinkedHashSet<>(trip.getPeople());
        for (final ChatMembership row : dao().listChatMembers(ChatChannel.Id.forTrip(tripId))) {
            if (row.getState() == ChatMembership.MemberState.JOINED) {
                ids.add(row.getPersonId());
            }
        }
        final List<Person> members = new ArrayList<>();
        for (final Person.Id id : ids) {
            final Person person = people.getPerson(id);
            if (person != null) {
                members.add(person);
            }
        }
        final Map<Person.Id, String> labels = uniqueLabels(members);
        final List<Map<String, String>> roster = new ArrayList<>();
        for (final Person person : members) {
            roster.add(rosterEntry(person, labels.get(person.getId())));
        }
        return scriptSafeJson(roster);
    }

    /**
     * A display label per person that no two people on the trip share.
     *
     * <p>Three rungs, taking the first that is unique across this roster:
     * <ol>
     *   <li>preferred name and last name -- what a person is actually called, and enough on almost every trip;</li>
     *   <li>the same plus the email in parentheses -- email is unique system-wide, so this always separates two
     *       people who genuinely share a name;</li>
     *   <li>the person id, for a row with neither a name nor an email.</li>
     * </ol>
     *
     * <p>The email only appears for the people it is needed to tell apart, because a label is a token someone
     * types into a message: putting an address on every entry would broadcast the whole trip's emails into the
     * chat log to solve a problem most trips do not have.
     */
    static Map<Person.Id, String> uniqueLabels(final List<Person> members) {
        final Map<String, Long> preferredCounts = members.stream()
                .collect(Collectors.groupingBy(ChatCommands::preferredLabel, Collectors.counting()));
        final Map<Person.Id, String> labels = new LinkedHashMap<>();
        for (final Person person : members) {
            labels.put(person.getId(), labelFor(person, preferredCounts));
        }
        return labels;
    }

    private static String labelFor(final Person person, final Map<String, Long> preferredCounts) {
        final String preferred = preferredLabel(person);
        if (preferredCounts.getOrDefault(preferred, 0L) <= 1L && !preferred.isBlank()) {
            return preferred;
        }
        // A private email must not be broadcast to the roster just because someone shares a name -- such a
        // person falls through to the id rung, which is unique by construction and discloses nothing.
        final String email = (person.getEmail() == null || !person.getPrivacy().isEmailVisible())
                ? "" : person.getEmail().trim();
        if (!email.isBlank()) {
            return preferred.isBlank() ? email : preferred + " (" + email + ")";
        }
        return preferred.isBlank() ? person.getId().getValue() : preferred + " (" + person.getId().getValue() + ")";
    }

    /** Preferred name plus last name -- how the dropdown reads when nothing needs disambiguating. */
    private static String preferredLabel(final Person person) {
        final String preferred = person.getPreferredName() == null || person.getPreferredName().isBlank()
                ? (person.getFirst() == null ? "" : person.getFirst()) : person.getPreferredName();
        final String last = person.getLast() == null ? "" : person.getLast();
        return (preferred + " " + last).trim();
    }

    private static Map<String, String> rosterEntry(final Person person, final String label) {
        final String preferred = person.getPreferredName() == null ? "" : person.getPreferredName();
        final String first = person.getFirst() == null ? "" : person.getFirst();
        final String last = person.getLast() == null ? "" : person.getLast();
        return Map.of(
                "id", person.getId().getValue(),
                "name", label == null || label.isBlank() ? person.getId().getValue() : label,
                // Matched against as one lowercase haystack, so typing any of the three finds the person.
                "search", (first + " " + last + " " + preferred).toLowerCase(Locale.ROOT).trim());
    }

    private String scriptSafeJson(final Object value) {
        try {
            return DAO.getInstance().getMapper().writeValueAsString(value)
                    .replace("<", "\\u003C")
                    .replace("\u2028", "\\u2028")
                    .replace("\u2029", "\\u2029");
        } catch (final IOException ex) {
            log.error("Unable to build the chat mention roster", ex);
            return "[]";
        }
    }

    public ChatAppearance appearanceForTrip(final String tripId, final Person.Id personId) {
        final ChatChannel channel = channelForPage(tripId);
        if (channel == null) {
            return ChatAppearance.NONE;
        }
        final ChatSettings settings = channel.getSettings();
        final ChatAppearance channelDefault =
                new ChatAppearance(settings.getBackgroundColor(), settings.getBackgroundImageUrl());
        final ChatMembership row = membershipFor(channel.getId(), personId);
        return ChatAppearance.effective(row == null ? null : row.getAppearance(), channelDefault);
    }

    /**
     * The chat pane's {@code style} attribute value, built server-side.
     *
     * <p>Built here rather than assembled in EL because every part of it is validated in one place
     * ({@link ChatAppearance}) and because the image needs two stacked background layers to be shown at 50%:
     * a flat overlay of the background colour on top of the image. CSS cannot make a background image
     * translucent on its own, and an {@code opacity} would fade the messages with it.
     *
     * <p>The image is fixed to the viewport, sized to the full width with its height left to the aspect ratio,
     * and repeated down so a tall pane is still covered.
     */
    public String backgroundStyleForTrip(final String tripId, final Person.Id personId) {
        final ChatAppearance look = appearanceForTrip(tripId, personId);
        final String color = look.getBackgroundColor();
        // The site-wide default applies only when NOTHING has been chosen -- not merely when no image has.
        // Falling back on a bare "no image chosen" put the default image over a chosen colour, so picking a
        // colour appeared to do nothing at all and there was no way to tell what was covering it.
        //
        // Deliberately resolved HERE and not in appearanceForTrip: that method also fills the settings dialog,
        // and showing the default in the field would present it as the person's own choice -- which the next
        // Save would then make true, freezing them on today's default forever.
        final String image = look.isEmpty() ? getDefaultBackgroundImage() : look.getBackgroundImageUrl();
        if (image == null) {
            return color == null ? "" : "background-color:" + color + ";";
        }
        final String overlay = translucentOverlay(color);
        return "background-image:linear-gradient(" + overlay + "," + overlay + "),url('" + image + "');"
                + "background-repeat:repeat;background-size:100% auto;background-attachment:fixed;"
                + (color == null ? "" : "background-color:" + color + ";");
    }

    /**
     * The 50%-opacity wash laid over the image. Uses the chosen colour when it is a hex value so the image tints
     * toward it; otherwise white, which is the safe reading for an unknown keyword.
     */
    private static String translucentOverlay(final String color) {
        if (color != null && color.matches("#[0-9a-f]{6}")) {
            final int r = Integer.parseInt(color.substring(1, 3), 16);
            final int g = Integer.parseInt(color.substring(3, 5), 16);
            final int b = Integer.parseInt(color.substring(5, 7), 16);
            return "rgba(" + r + "," + g + "," + b + ",0.5)";
        }
        return "rgba(255,255,255,0.5)";
    }

    /**
     * Saves everything on the per-person settings dialog in one go: notification preference and look.
     *
     * <p>One method, and therefore one membership write, because they live on the same row — saving them
     * separately means the second read-modify-write can be built on a row the first one has already replaced,
     * and the loser's field silently reverts.
     */
    public boolean saveChatPrefsFromUi(
            final Boolean mentionEmail, final Boolean dailyDigest, final String color,
            final String imageUrl) {
        final boolean saved = saveChatPrefs(currentTripId(), currentUserId(),
                mentionEmail != null && mentionEmail, dailyDigest != null && dailyDigest, color, imageUrl);
        if (saved) {
            growlWarn("Chat settings saved.");
        } else {
            growlError("Unable to save your chat settings.");
        }
        return saved;
    }

    /**
     * The scope-free half of {@link #saveChatPrefsFromUi}, split out so it is testable off a request thread.
     *
     * <p>Creates the channel if it does not exist yet — the same reasoning as {@link #setEmailPrefs}: a channel
     * only becomes real on the first send, so requiring one to already exist here made Save fail in every chat
     * nobody had posted in yet. Still gated on {@link #canRead}, because this writes a membership row and a row
     * is what puts someone in the channel.
     */
    boolean saveChatPrefs(final String tripId, final Person.Id me, final boolean mentionEmail,
            final boolean dailyDigest, final String color, final String imageUrl) {
        if (tripId == null || me == null) {
            return false;
        }
        final ChatChannel channel = ensureChannel(tripId, AuditActor.current());
        if (channel == null || !canRead(channel, me)) {
            return false;
        }
        final ChatMembership row = membershipRow(channel.getId(), me)
                .orElseGet(() -> ChatMembership.joining(channel.getId(), me, channel.getCreated()));
        final ChatMembership updated = row
                .withNotify(row.getNotify().withEmail(mentionEmail, dailyDigest))
                .withAppearance(new ChatAppearance(color, imageUrl));
        return dao().saveChatMembership(updated);
    }

    public boolean canRead(final ChatChannel channel, final Person.Id me) {
        return readDenial(channel, me) == null;
    }

    /**
     * Why this person may not read, as a wire error code, or {@code null} when they may.
     *
     * <p>Returns the <em>specific</em> reason rather than a boolean because the remedies differ and the client
     * cannot infer them: a person who left gets a Rejoin button, a person who was removed must not, and a person
     * no longer on the trip gets neither. See {@code ChatErrors}.
     */
    public String readDenial(final ChatChannel channel, final Person.Id me) {
        if (channel == null || me == null) {
            return "NOT_AUTHENTICATED";
        }
        // Checked here, at the root of every read, rather than only where the tab is drawn: hiding a tab hides a
        // link, it does not stop a saved URL or a mobile client that already knows the endpoint. Refusing here
        // means "chat off" holds for the JSF page, the REST feed, the digest and the export alike.
        if (!chatEnabledFor(channel)) {
            return "CHAT_DISABLED";
        }
        // The membership row is read BEFORE any grant, and LEFT/REMOVED refuse before isTripMember allows:
        // an admin REMOVE must oust a trip member and a guest alike, and a guest-marked JOINED row is itself
        // a grant -- so the row can no longer be an afterthought consulted only for members. Exactly one
        // membership read either way.
        final ChatMembership row = dao().getChatMembership(channel.getId(), me).orElse(null);
        if (row != null && row.getState() == ChatMembership.MemberState.LEFT) {
            return "LEFT_CHANNEL";
        }
        if (row != null && row.getState() == ChatMembership.MemberState.REMOVED) {
            return "REMOVED_FROM_CHANNEL";
        }
        if (isTripMember(channel.getTripId(), me) || guestJoined(row)) {
            return null; // an absent row is implicit JOINED for trip members -- the default opt-in
        }
        return "NOT_A_TRIP_MEMBER";
    }

    /** Whether the trip behind this channel still has chat turned on. A missing trip is not a reason to refuse. */
    private boolean chatEnabledFor(final ChatChannel channel) {
        final Trip trip = tripOf(channel);
        return trip == null || trip.getChatEnabled();
    }

    /** Whether this trip has chat at all, for a page deciding whether to render or redirect. */
    public boolean chatEnabledForTrip(final String tripId) {
        final Trip trip = dao().getTrip(tripId).orElse(null);
        return trip == null || trip.getChatEnabled();
    }

    /**
     * The per-message photo cap the composer should enforce, or 0 when photos are off for this channel —
     * 0 is also what hides the Attach button, so "off" and "cap of none" deliberately look the same.
     *
     * <p>Null-safe because it is evaluated from a {@code rendered=} attribute: on a no-trip render (a
     * bookmark with no parameter and no session trip) the page is mid-REDIRECT, but attribute EL still
     * evaluates, and throwing here aborts the response half-written instead of letting the redirect land.
     */
    public int maxPhotosForTrip(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return 0;
        }
        final ChatSettings settings = channelForPage(tripId).getSettings();
        return settings.isAllowMedia() ? settings.getMaxAttachmentsPerMessage() : 0;
    }

    // --- send / feed ---

    /**
     * {@code authorId} MUST be the signed-in user. Every current caller passes exactly that, and two checks in
     * this path (the ADMINS_ONLY post policy and the @all allowance exemption) resolve adminship through
     * {@code Caller.current()} -- so an authorId that is not the caller would have its membership and mute
     * checked as one person and its adminship as another. If sending on someone's behalf is ever needed, that
     * caller must bring its own authorization design, not this method.
     */
    public SendResult send(
            final String tripId,
            final Person.Id authorId,
            final String body,
            final String clientMessageId,
            final ChatMessage.Id replyToId,
            final AuditActor actor) {
        return send(tripId, authorId, body, clientMessageId, replyToId, actor, List.of());
    }

    /**
     * As above, with photos. {@code attachmentRefs} name staged uploads (see {@code ChatPhotos}); a reference
     * that was not staged by THIS author for THIS trip fails the send — the staging registry is the only
     * authority on what a message may attach.
     */
    public SendResult send(
            final String tripId,
            final Person.Id authorId,
            final String body,
            final String clientMessageId,
            final ChatMessage.Id replyToId,
            final AuditActor actor,
            final List<ChatPhotos.AttachmentRef> attachmentRefs) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        final Instant now = Instant.now();
        final ChatChannel channel = ensureChannel(tripId, who);
        // One membership read serves the whole send: the authorization chain, the denial reason, and the reply
        // quote's visibility check. It was read four times per send, each a separate blocking round trip.
        final ChatMembership row = dao().getChatMembership(channel.getId(), authorId).orElse(null);
        final SendResult denial = postDenial(channel, authorId, row, now);
        if (denial != null) {
            return denial;
        }
        final List<ChatAttachment> attachments;
        if (attachmentRefs == null || attachmentRefs.isEmpty()) {
            attachments = List.of();
        } else {
            final ChatSettings settings = channel.getSettings();
            if (!settings.isAllowMedia()) {
                return SendResult.fail("attachment", "Photos are turned off for this chat.");
            }
            if (attachmentRefs.size() > settings.getMaxAttachmentsPerMessage()) {
                return SendResult.fail("too_many_photos", "At most "
                        + settings.getMaxAttachmentsPerMessage() + " photos per message.");
            }
            try {
                attachments = ChatPhotos.getChatPhotos().resolveStaged(tripId, authorId, attachmentRefs);
            } catch (final PhotoRejectedException ex) {
                return SendResult.fail("attachment", ex.getMessage());
            }
        }
        final String text = body == null ? "" : body;
        final int cps = text.codePointCount(0, text.length());
        final int max = channel.getSettings().getMaxMessageChars();
        if (cps == 0 && attachments.isEmpty()) {
            return SendResult.fail("empty", "Message cannot be empty.");
        }
        if (cps > max) {
            return SendResult.fail("too_long", "Message is too long (max " + max + " characters).");
        }

        final ChatRateLimiter.Decision decision = rateLimiter.check(channel, authorId, now);
        if (decision.getAutoMuteUntil() != null) {
            applyMute(tripId, authorId, decision.getAutoMuteUntil(),
                    "auto-mute after repeated rate-limit hits", who);
            // Report it as a mute, not a rate limit: the two must stay distinguishable to the user, and from
            // here on it is a mute that is blocking them.
            growlWarn("You have been muted until " + decision.getAutoMuteUntil()
                    + " after repeated rate-limit hits.");
            return SendResult.fail("muted", "You are muted and cannot post right now.");
        }
        if (!decision.isAllowed()) {
            growlWarn(decision.userMessage());
            return SendResult.rateLimited(decision);
        }

        ChatQuote quote = null;
        if (replyToId != null) {
            final Optional<ChatMessage> original = dao().getVisibleChatMessage(
                    channel.getId(), replyToId, row, channel, tripOf(channel), now);
            if (original.isPresent()) {
                final Person author = PersonCommands.getPersonCommands().getPerson(original.get().getAuthorId());
                final String name = author == null ? "Someone" : author.getPreferredName();
                quote = ChatQuote.from(original.get(), name);
            }
        }

        final ChatMessage draft = new ChatMessage(
                null, channel.getId(), authorId, null,
                attachments.isEmpty() ? ChatMessage.MessageKind.TEXT : ChatMessage.MessageKind.MEDIA,
                text, quote, attachments, null,
                null, null, null, null, clientMessageId, null);
        Optional<ChatMessage> saved;
        try {
            saved = dao().saveChatMessage(draft, channel, tripOf(channel));
        } catch (final RuntimeException ex) {
            saved = logSaveFailure(ex);
        }
        if (saved.isEmpty()) {
            growlError("Message was not delivered. Try again.");
            return SendResult.fail("store", "Message was not delivered. Try again.");
        }
        // AFTER the durable write, never before: a notification about a message that failed to save would point at
        // nothing. Everything past this point is fire-and-forget on a pool thread and cannot fail the send.
        final ChatMessage stored = saved.get();
        if (!stored.getAttachments().isEmpty()) {
            // Consumed and recorded only for a saved message: a failed send leaves the photos staged, so the
            // composer's retry still owns them.
            final ChatPhotos photos = ChatPhotos.getChatPhotos();
            photos.consume(stored.getAttachments());
            final Trip trip = tripOf(channel);
            photos.recordAlbumRows(tripId,
                    trip == null || trip.getTitle() == null ? "trip" : trip.getTitle(),
                    authorId, authorDisplayName(authorId), stored.getAttachments(), who);
            // Eager per-photo comment channels, while the parent (this message) is simply known — a channel
            // created later, from the album, would have to scan the trip's history to find it.
            PhotoChatCommands.getPhotoChatCommands().ensureChannelsForMessage(stored, who);
        }
        if (allowanceSpentOnEveryone(channel, authorId, stored, now)) {
            // The message is posted and highlights for everyone in-app; only the mail fan-out is withheld. Told to
            // the sender rather than done quietly, because someone who thinks their @all reached inboxes and finds
            // out later that it did not is worse off than someone who is told now.
            ChatNotifications.mentionsFor(
                    withoutEveryone(stored), channel, tripOf(channel), authorDisplayName(authorId));
            return SendResult.ok(stored, "Only one @all a day is emailed. This one is posted in the chat, but "
                    + "nobody was emailed about it. Please use @all sparingly.");
        }
        ChatNotifications.mentionsFor(stored, channel, tripOf(channel), authorDisplayName(authorId));
        return SendResult.ok(stored);
    }

    /**
     * Whether this {@code @all} should be posted without emailing anyone.
     *
     * <p>One emailing {@code @all} per person per 24 hours, for anyone who is not a chat administrator. The cap is
     * on the <b>mail fan-out only</b> — the message is still posted and still highlights for everyone in the app,
     * because silently degrading what someone wrote is worse than not mailing about it.
     *
     * <p>Counted from the message history rather than a cache counter on purpose: the messages are the record, so
     * the limit cannot be reset by a cache flush or dodged by a deploy, and it is auditable after the fact.
     */
    private boolean allowanceSpentOnEveryone(
            final ChatChannel channel, final Person.Id authorId, final ChatMessage sent, final Instant now) {
        if (!ChatMentions.mentionsEveryone(sent.getBody())) {
            return false;
        }
        // The author IS the signed-in caller: this runs inside that author's own send (see send()'s
        // authorId contract), so the caller-based check is the author check.
        if (canAdminister(channel.getTripId())) {
            return false;
        }
        final ChatMessage.Id since = ChatMessage.Id.of(now.minus(EVERYONE_WINDOW).toEpochMilli());
        return dao().getChatMessagesSince(
                        channel.getId(), since, 200, null, channel, tripOf(channel), now)
                .getMessages().stream()
                .filter(m -> !m.getId().equals(sent.getId()))
                .filter(m -> authorId.equals(m.getAuthorId()))
                .anyMatch(m -> m.getDeletedAt() == null && ChatMentions.mentionsEveryone(m.getBody()));
    }

    /** The same message with {@code @all} neutralised, so only the people named by id are mailed. */
    private static ChatMessage withoutEveryone(final ChatMessage message) {
        return message.withBody(message.getBody().replaceAll("(?i)@all", "@ all"));
    }

    /**
     * Why this person may not post, as a {@link SendResult}, or {@code null} when they may. Takes the membership row
     * so the caller reads it once.
     *
     * <p>Order is deliberate: a mute is reported before anything else that would also be true, because a mute must
     * always reach the user AS a mute -- never as a rate limit and never hidden behind a channel-wide condition.
     */
    private SendResult postDenial(
            final ChatChannel channel, final Person.Id me, final ChatMembership row, final Instant now) {
        if (row != null && row.isMuted(now)) {
            return SendResult.fail("muted", "You are muted and cannot post right now.");
        }
        if (!isTripMember(channel.getTripId(), me) && !guestJoined(row)) {
            return SendResult.fail("forbidden", "You cannot post in this chat.");
        }
        if (row != null && (row.getState() == ChatMembership.MemberState.LEFT
                || row.getState() == ChatMembership.MemberState.REMOVED)) {
            return SendResult.fail("forbidden", "You are not in this chat.");
        }
        if (ChatVisibility.isArchived(channel, tripOf(channel), now)) {
            return SendResult.fail("archived", "This chat is archived and read-only.");
        }
        if (channel.getSettings().getPostPolicy() == ChatSettings.PostPolicy.ADMINS_ONLY
                && !canAdminister(channel.getTripId())) {
            return SendResult.fail("forbidden", "Only administrators can post in this chat.");
        }
        return null;
    }

    /** The upload gate's answer: the channel consulted, and why not — or {@code null} when allowed. */
    public record AttachGate(ChatChannel channel, String denial) {
    }

    /**
     * Whether {@code me} may stage a photo for this trip's chat right now: exactly the checks a send would
     * apply, plus the media switches. Consulted by the upload servlet BEFORE bytes are processed; the send
     * re-runs its own checks anyway, so a race (muted between attach and send) fails safe at send time.
     *
     * <p>Uses {@link #channelForPage} rather than {@link #ensureChannel} on purpose: staging a photo is
     * composing, not posting, and must not create the channel — the channel becomes real on the first send.
     */
    public AttachGate checkAttach(final String tripId, final Person.Id me) {
        final ChatChannel channel = channelForPage(tripId);
        final ChatMembership row = dao().getChatMembership(channel.getId(), me).orElse(null);
        final SendResult denial = postDenial(channel, me, row, Instant.now());
        if (denial != null) {
            return new AttachGate(channel, denial.getMessage());
        }
        final ChatSettings settings = channel.getSettings();
        if (!settings.isAllowMedia() || settings.getMaxAttachmentsPerMessage() <= 0) {
            return new AttachGate(channel, "Photos are turned off for this chat.");
        }
        return new AttachGate(channel, null);
    }

    public ChatPage feed(
            final String tripId,
            final Person.Id readerId,
            final ChatMessage.Id since,
            final int limit) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null || !canRead(channel, readerId)) {
            return ChatPage.empty();
        }
        final Optional<ChatMembership> member =
                dao().getChatMembership(channel.getId(), readerId);
        return withNames(dao().getChatMessagesSince(
                channel.getId(), since, limit, member.orElse(null), channel, tripOf(channel), Instant.now())
                );
    }

    public ChatPage history(
            final String tripId,
            final Person.Id readerId,
            final ChatMessage.Id before,
            final int limit) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null || !canRead(channel, readerId)) {
            return ChatPage.empty();
        }
        final Optional<ChatMembership> member =
                dao().getChatMembership(channel.getId(), readerId);
        return withNames(dao().getChatMessagesBefore(
                channel.getId(), before, limit, member.orElse(null), channel, tripOf(channel), Instant.now())
                );
    }

    /** The send is not acknowledged unless the durable write succeeded, so a failure must surface. */
    private Optional<ChatMessage> logSaveFailure(final Throwable ex) {
        log.error("Failed to save chat message", ex);
        return Optional.empty();
    }

    /**
     * Resolves every person id the page will render -- authors, quote authors and @mentions -- into display names.
     * Done once per page rather than per message so a 50-message page costs one pass, and done here rather than in
     * the DAO because resolving people is an action-layer concern.
     */
    private ChatPage withNames(final ChatPage page) {
        final Set<String> ids = new LinkedHashSet<>();
        for (final ChatMessage m : page.getMessages()) {
            if (m.getAuthorId() != null) {
                ids.add(m.getAuthorId().getValue());
            }
            if (m.getQuote() != null && m.getQuote().getAuthorId() != null) {
                ids.add(m.getQuote().getAuthorId().getValue());
            }
            for (final Person.Id mentioned : ChatMentions.extract(m.getBody())) {
                ids.add(mentioned.getValue());
            }
        }
        // Reactors too. They are frequently NOT authors on this page -- someone can react without ever posting --
        // so leaving them out puts a raw person id in the reaction chip's "who reacted" tooltip.
        for (final ChatReactionSummary summary : page.getReactions().values()) {
            addReactorIds(ids, summary);
        }
        if (ids.isEmpty()) {
            return page;
        }
        final PersonCommands people = PersonCommands.getPersonCommands();
        final Map<String, String> names = new LinkedHashMap<>();
        for (final String id : ids) {
            names.put(id, displayNameOrId(people, id));
        }
        return page.withDisplayNames(names);
    }

    /**
     * A person's display name, falling back to their id.
     *
     * <p>The fallback covers a null name, not just a missing person. {@code Person.getPreferredName()} returns
     * {@code first} when there is no nickname, and {@code first} is nullable — so a person with neither would put a
     * null into the display-name map, and {@code Map.copyOf} in the {@code ChatPage} constructor rejects null
     * values. That threw inside the feed, which means <b>one such person on a trip 500s the chat for everyone on
     * it</b>, not just for themselves.
     */
    private static String displayNameOrId(final PersonCommands people, final String id) {
        final Person person = people.getPerson(Person.Id.from(id));
        final String name = person == null ? null : person.getPreferredName();
        return name == null || name.isBlank() ? id : name;
    }

    /** The author's name for a notification subject line, resolved on the request thread while people are cheap. */
    private String authorDisplayName(final Person.Id authorId) {
        return authorId == null ? "Someone" : displayNameOrId(PersonCommands.getPersonCommands(), authorId.getValue());
    }

    private static void addReactorIds(final Set<String> ids, final ChatReactionSummary summary) {
        for (final List<Person.Id> who : summary.getByEmoji().values()) {
            for (final Person.Id id : who) {
                ids.add(id.getValue());
            }
        }
    }

    /** Display names for every person id appearing in a set of summaries, for the reaction-refetch response. */
    public Map<String, String> reactorNames(final Map<ChatMessage.Id, ChatReactionSummary> summaries) {
        final Set<String> ids = new LinkedHashSet<>();
        for (final ChatReactionSummary summary : summaries.values()) {
            addReactorIds(ids, summary);
        }
        final PersonCommands people = PersonCommands.getPersonCommands();
        final Map<String, String> names = new LinkedHashMap<>();
        for (final String id : ids) {
            names.put(id, displayNameOrId(people, id));
        }
        return names;
    }

    // --- author self-edit ---


    /**
     * Lets an author correct their own message inside {@link #EDIT_WINDOW_MINUTES}.
     *
     * <p>Author-only, and deliberately <b>not</b> available to administrators: an admin who dislikes a message can
     * remove it, which is visible as a tombstone and audited. Letting them silently rewrite someone else's words
     * would put unattributable text under that person's name — a different and much worse power.
     *
     * <p>Not audited, per the design: an author fixing their own typo inside 15 minutes is ordinary use, and
     * recording it would bury the moderation events the trail exists for.
     */
    public ReactResult editMessage(
            final String tripId, final Person.Id me, final ChatMessage.Id msgId, final String newBody) {
        if (msgId == null) {
            return ReactResult.fail("not_found", "Message not found.");
        }
        final Instant now = Instant.now();
        final ChatChannel channel = getChannel(tripId);
        if (channel == null) {
            return ReactResult.fail("not_found", "No chat for this trip.");
        }
        final String denial = editDenial(channel, me, now);
        if (denial != null) {
            return ReactResult.fail(denial, reactDenialMessage(denial));
        }
        final ChatMembership row = membershipFor(channel.getId(), me);
        final Optional<ChatMessage> target = dao().getVisibleChatMessage(
                channel.getId(), msgId, row, channel, tripOf(channel), now);
        if (target.isEmpty() || target.get().isDeleted()) {
            return ReactResult.fail("not_found", "Message not found.");
        }
        final ChatMessage original = target.get();
        if (original.getAuthorId() == null || !original.getAuthorId().equals(me)) {
            return ReactResult.fail("FORBIDDEN", "You can only edit your own messages.");
        }
        if (!withinEditWindow(original.getSentAt(), now)) {
            return ReactResult.fail("EDIT_WINDOW_CLOSED",
                    "Messages can only be edited within " + editWindowMinutes() + " minutes of sending.");
        }
        final String text = newBody == null ? "" : newBody;
        final int cps = text.codePointCount(0, text.length());
        if (cps == 0) {
            return ReactResult.fail("empty", "Message cannot be empty.");
        }
        if (cps > channel.getSettings().getMaxMessageChars()) {
            return ReactResult.fail("too_long",
                    "Message is too long (max " + channel.getSettings().getMaxMessageChars() + " characters).");
        }
        return dao().editChatMessage(channel.getId(), msgId, text).isPresent()
                ? ReactResult.success()
                : ReactResult.fail("store", "Edit was not saved. Try again.");
    }

    /**
     * Why this person may not edit here, or {@code null} when they may.
     *
     * <p>A mute blocks editing. Without that, a muted author could keep publishing by repeatedly rewriting a
     * message they sent before the mute — a hole that turns the edit window into an unmoderated channel.
     */
    private String editDenial(final ChatChannel channel, final Person.Id me, final Instant now) {
        final String readDenial = readDenial(channel, me);
        if (readDenial != null) {
            return readDenial;
        }
        if (!channel.getSettings().isAllowEdit()) {
            return "EDIT_DISABLED";
        }
        if (ChatVisibility.isArchived(channel, tripOf(channel), now)) {
            return "CHANNEL_ARCHIVED";
        }
        final ChatMembership row = membershipFor(channel.getId(), me);
        if (row != null && row.isMuted(now)) {
            return "MUTED";
        }
        return null;
    }

    /**
     * Whether a message sent at {@code sentAt} may still be edited at {@code now}. A missing timestamp is outside
     * the window: without one there is no evidence the message is recent, and the safe reading is "too late".
     */
    boolean withinEditWindow(final Instant sentAt, final Instant now) {
        return sentAt != null && !sentAt.plus(editWindowMinutes(), ChronoUnit.MINUTES).isBefore(now);
    }

    /**
     * How long an author may edit their own message. Short on purpose: an edit rewrites what other people have
     * already read, so the window is for fixing a typo, not for revising history.
     *
     * <p>Also read by the client, which uses it to decide whether to offer the button at all -- so a change here
     * must reach both, which is why it is read rather than captured at construction.
     */
    public long getEditWindowMinutes() {
        return editWindowMinutes();
    }

    private long editWindowMinutes() {
        return config.getInt(KnownSettings.CHAT_EDIT_WINDOW_MINUTES, 1, Integer.MAX_VALUE);
    }

    // --- notification preferences ---

    /**
     * Sets this person's email preference for a channel, materialising their membership row if they were only ever
     * an implicit member.
     *
     * <p>Materialising is required, not incidental: an absent row means JOINED with defaults, and the default is
     * {@code OFF} — so without writing a row the opt-in would appear to work and then silently not.
     */
    // --- the digest choice taken at registration ---

    /**
     * Where the registration form parks the daily-digest answer until the registration is approved.
     *
     * <p>Registration is not membership: registering creates a {@code Registration}, and a trip manager has to
     * approve it before the person is on the trip at all. So the answer cannot be written to a chat membership
     * row when it is given -- a row is what puts someone in the channel, and they are not in it yet. It rides on
     * the registration instead, and {@link #applyRegistrationDigestChoice} turns it into a real preference at
     * the moment of approval.
     */
    public static final String DIGEST_REG_OPTION = "chat.dailyDigest";

    /** Records the answer on the registration itself, so it survives until someone approves it. */
    public void setDigestChoice(final Registration registration, final boolean wantsDigest) {
        if (registration != null) {
            registration.getOptions().put(DIGEST_REG_OPTION, String.valueOf(wantsDigest));
        }
    }

    /**
     * {@link #setDigestChoice} fed straight from the page's draft map. Exists because the jsft script
     * parser cannot take {@code map.get(key) == true} as a method argument (a runtime ELParser
     * ParseException, which a jsft event turns into a silent redirect home) -- {@code ==} in those
     * scripts is only safe between a plain property and a literal. The null-safety is a bonus.
     */
    public void parkDigestChoice(final Registration registration, final Map<String, Object> digests,
            final String key) {
        setDigestChoice(registration, digests != null && Boolean.TRUE.equals(digests.get(key)));
    }

    /** The answer previously given, for redisplaying the form. Absent means "not asked yet", which reads as no. */
    public boolean digestChoice(final Registration registration) {
        return registration != null && Boolean.parseBoolean(registration.getOptions().get(DIGEST_REG_OPTION));
    }

    /**
     * What the registration form's toggle should START as: the stored answer when one was given, else ON.
     * Opt-out, not opt-in, by design (2026-08-10): most registrants want the daily summary, and the ones who
     * do not are looking at the switch when they decline it. Only the form initializer may use this --
     * {@link #digestChoice} stays strict so an absent answer is never mistaken for a given one.
     */
    public boolean digestChoiceOrDefault(final Registration registration) {
        if (registration == null || !registration.getOptions().containsKey(DIGEST_REG_OPTION)) {
            return true;
        }
        return digestChoice(registration);
    }

    /**
     * Applies the parked answer once the person is actually on the trip.
     *
     * <p><b>Takes the approving page's own {@link Trip}, not a trip id, and that is the whole point.</b> The
     * membership gate used to re-read the trip from the DAO — microseconds after the caller had added the
     * person to it and saved. A DAO write invalidates the shared cache asynchronously, so that read could
     * still return the pre-approval roster, the gate answered "not a trip member", and the opt-in the person
     * gave at registration was dropped without a word. It reproduced nowhere locally, because nothing local
     * has that delay. Approval already holds the authoritative roster; asking storage for it again can only
     * be wrong.
     *
     * <p>Absent answer means the question was never shown (a trip with chat off, or a registration predating
     * the question), and nothing is written -- which leaves the person on the shipped default rather than
     * opting them out of something they never declined.
     *
     * @return whether a preference was written; {@code false} covers "nothing to apply" as well as failure.
     */
    public boolean applyRegistrationDigestChoice(final Trip trip, final Registration registration) {
        if (trip == null || registration == null
                || !registration.getOptions().containsKey(DIGEST_REG_OPTION)) {
            return false;
        }
        final Person.Id who = registration.getUserId();
        if (!trip.getPeople().contains(who)) {
            // Still gated -- a membership row is what puts someone in the channel -- but against the roster in
            // hand, which approval has just made true, rather than against a cache that may not know yet.
            log.warn("Not applying the chat digest choice: {} is not on trip {}", who, trip.getId());
            return false;
        }
        final ChatChannel channel = ensureChannel(trip.getId(), AuditActor.current());
        if (channel == null) {
            return false;
        }
        final ChatMembership row = membershipRow(channel.getId(), who)
                .orElseGet(() -> ChatMembership.joining(channel.getId(), who, channel.getCreated()));
        final boolean digest = digestChoice(registration);
        return dao().saveChatMembership(
                row.withNotify(row.getNotify().withEmail(row.getNotify().isMentionEmail(), digest)));
    }

    /**
     * Stores this person's email preferences for a trip's chat.
     *
     * <p>Creates the channel if it does not exist yet. A preference can legitimately be applied before anyone has
     * ever opened the chat -- notably when a trip manager approves a registration, which is where the digest
     * opt-in taken at registration is applied -- and requiring a channel to already exist silently dropped it.
     *
     * <p>Still gated on {@link #canRead}: this writes a membership row, and a row is what puts someone in the
     * channel. Registration deliberately does not, which is why the answer given there is parked on the
     * registration and only applied at approval.
     */
    public boolean setEmailPrefs(
            final String tripId, final Person.Id me, final boolean mentionEmail, final boolean dailyDigest) {
        if (tripId == null || me == null) {
            return false;
        }
        final ChatChannel channel = ensureChannel(tripId, AuditActor.current());
        if (channel == null || !canRead(channel, me)) {
            return false;
        }
        final ChatMembership row = membershipRow(channel.getId(), me)
                .orElseGet(() -> ChatMembership.joining(channel.getId(), me, channel.getCreated()));
        return dao().saveChatMembership(row.withNotify(row.getNotify().withEmail(mentionEmail, dailyDigest)))
                ;
    }

    /** Whether this person gets a mail when named. Implicit members hold the defaults, so this is on by default. */
    public boolean mentionEmailForTrip(final String tripId, final Person.Id personId) {
        return notifyPrefFor(tripId, personId).isMentionEmail();
    }

    /** Whether this person gets the daily summary. A positive opt-in, so off unless they asked. */
    public boolean dailyDigestForTrip(final String tripId, final Person.Id personId) {
        return notifyPrefFor(tripId, personId).isDailyDigest();
    }

    /**
     * This person's preferences for this channel.
     *
     * <p>An implicit member has no row and therefore holds the DEFAULTS -- not "everything off". Reporting off
     * would make the settings screen disagree with the notifier, which reads the same defaults and mails them.
     */
    private ChatNotifyPref notifyPrefFor(final String tripId, final Person.Id personId) {
        return membershipRow(ChatChannel.Id.forTrip(tripId), personId)
                .map(ChatMembership::getNotify)
                .orElseGet(ChatNotifyPref::defaults);
    }

    /**
     * Takes people off the chat roster when they come off the trip.
     *
     * <p>Marked {@code LEFT} rather than {@code REMOVED}: coming off a trip is usually a registration change, not
     * moderation, so if they are added back they can rejoin themselves without an administrator. This does
     * overwrite whatever chat state they had — a mute set before removal is not preserved — which is the accepted
     * cost of keeping the two rosters agreeing.
     *
     * <p>Never throws and never blocks the caller's save: the trip edit that triggered this has already
     * succeeded, and a chat roster left slightly stale is not worth failing it for.
     */
    public void leaveOnTripRemoval(final String tripId, final List<Person.Id> removed) {
        if (tripId == null || removed == null || removed.isEmpty()) {
            return;
        }
        final ChatChannel channel = getChannel(tripId);
        if (channel == null) {
            return;
        }
        final Instant now = Instant.now();
        for (final Person.Id person : removed) {
            try {
                final ChatMembership row = membershipRow(channel.getId(), person)
                        .orElseGet(() -> ChatMembership.joining(channel.getId(), person, channel.getCreated()));
                dao().saveChatMembership(row.withLeft(now, "removed from the trip"));
            } catch (final RuntimeException ex) {
                log.warn("Unable to take {} off the chat for trip {}", person, tripId, ex);
            }
        }
    }

    /** Records how far this person has read, which is what clears the unread dot. */
    public boolean markRead(final String tripId, final Person.Id me, final ChatMessage.Id cursor) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null || me == null || cursor == null || !canRead(channel, me)) {
            return false;
        }
        return dao().saveChatCursor(channel.getId(), me, cursor);
    }

    public boolean hasUnread(final String tripId, final Person.Id me) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null || me == null || !canRead(channel, me)) {
            return false;
        }
        return unreadAgainst(channel, me, dao().getChatLastActivity());
    }

    private boolean unreadAgainst(
            final ChatChannel channel, final Person.Id me, final Map<String, String> lastActivity) {
        final long activity = parseLong(lastActivity.get(channel.getId().getValue()), 0L);
        if (activity <= 0L) {
            return false;
        }
        final ChatMessage.Id cursor = dao().getChatCursor(channel.getId(), me).orElse(null);
        // No cursor means they have never opened this chat. Anything at all is unread -- which is the correct
        // first-time signal, and it is also why an absent cursor must not be read as "caught up".
        return cursor == null || cursor.getEpochMilli() < activity;
    }

    /** Unread state for the trip tab, addressed by trip id so the XHTML needs no person id. */
    public boolean isUnreadForCurrentUser(final String tripId) {
        return hasUnread(tripId, currentUserId());
    }

    // --- reactions ---

    /**
     * Adds a reaction. Idempotent: the row's key <em>is</em> {@code (message, person, emoji)}, so a double click is
     * a no-op rather than a double count, and no lock is needed anywhere in this path.
     */
    public ReactResult react(
            final String tripId, final Person.Id me, final ChatMessage.Id msgId, final String emoji) {
        return toggle(tripId, me, msgId, emoji, true);
    }

    /**
     * Removes a reaction. It genuinely disappears — unlike a deleted message, a removed reaction has no historical
     * value worth a tombstone.
     */
    public ReactResult unreact(
            final String tripId, final Person.Id me, final ChatMessage.Id msgId, final String emoji) {
        return toggle(tripId, me, msgId, emoji, false);
    }

    private ReactResult toggle(
            final String tripId,
            final Person.Id me,
            final ChatMessage.Id msgId,
            final String emoji,
            final boolean add) {
        if (!ChatEmoji.isAllowed(emoji, getEmojiPalette())) {
            return ReactResult.fail("bad_emoji", "That reaction is not available.");
        }
        if (msgId == null) {
            return ReactResult.fail("not_found", "Message not found.");
        }
        final Instant now = Instant.now();
        final ChatChannel channel = getChannel(tripId);
        if (channel == null) {
            return ReactResult.fail("not_found", "No chat for this trip.");
        }
        final String denial = reactDenial(channel, me, now);
        if (denial != null) {
            return ReactResult.fail(denial, reactDenialMessage(denial));
        }
        // The target must be visible to this reader, not merely present. Reacting to a message they cannot see
        // would confirm it exists and put their name on it in everyone else's chip tooltip.
        final ChatMembership row = membershipFor(channel.getId(), me);
        final Optional<ChatMessage> target = dao().getVisibleChatMessage(
                channel.getId(), msgId, row, channel, tripOf(channel), now);
        if (target.isEmpty()) {
            return ReactResult.fail("not_found", "Message not found.");
        }
        final boolean ok = add
                ? dao().putChatReaction(new ChatReaction(
                        channel.getId(), msgId, me, emoji, now, target.get().getExpiresAt()))
                : dao().deleteChatReaction(channel.getId(), msgId, me, emoji);
        return ok ? ReactResult.success() : ReactResult.fail("store", "Reaction was not saved. Try again.");
    }

    /**
     * Why this person may not react, as a wire code, or {@code null} when they may.
     *
     * <p>A mute blocks reacting as well as posting. A muted person who could still react keeps a voice in the
     * channel through a control that was never meant to be one, which defeats the moderation action.
     */
    private String reactDenial(final ChatChannel channel, final Person.Id me, final Instant now) {
        final String readDenial = readDenial(channel, me);
        if (readDenial != null) {
            return readDenial;
        }
        if (!channel.getSettings().isAllowReactions()) {
            return "REACTIONS_DISABLED";
        }
        if (ChatVisibility.isArchived(channel, tripOf(channel), now)) {
            return "CHANNEL_ARCHIVED";
        }
        final ChatMembership row = membershipFor(channel.getId(), me);
        if (row != null && row.isMuted(now)) {
            return "MUTED";
        }
        return null;
    }

    private static String reactDenialMessage(final String code) {
        return switch (code) {
            case "REACTIONS_DISABLED" -> "Reactions are turned off in this chat.";
            case "EDIT_DISABLED" -> "Editing is turned off in this chat.";
            case "CHANNEL_ARCHIVED" -> "This chat is archived and read-only.";
            case "MUTED" -> "You are muted and cannot post, react or edit right now.";
            default -> "You cannot do that in this chat.";
        };
    }

    /**
     * Summaries for a window of messages, for a client refetching after {@code reactionsVersion} changed. Bounded by
     * the window the client actually has on screen rather than the whole channel.
     */
    public Map<ChatMessage.Id, ChatReactionSummary> reactionWindow(
            final String tripId, final Person.Id me, final ChatMessage.Id oldest, final ChatMessage.Id newest) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null || !canRead(channel, me)) {
            return Map.of();
        }
        return dao().getChatReactionWindow(channel.getId(), oldest, newest);
    }

    public long reactionsVersion(final String tripId) {
        final ChatChannel channel = getChannel(tripId);
        return channel == null ? 0L : dao().getChatReactionsVersion(channel.getId());
    }

    /** The reaction palette, for the picker and for validating what a client sends back. */
    public List<String> getEmojiPalette() {
        return ChatEmoji.parsePalette(config.getString(KnownSettings.CHAT_REACTIONS_PALETTE));
    }

    // --- join / leave ---

    public boolean leave(final String tripId, final Person.Id personId, final AuditActor actor) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        final ChatChannel channel = ensureChannel(tripId, who);
        final Instant now = Instant.now();
        final ChatMembership existing = dao().getChatMembership(channel.getId(), personId)
                .orElseGet(() -> new ChatMembership(
                        channel.getId(), personId, ChatMembership.MemberState.JOINED,
                        ChatMembership.MemberRole.MEMBER, channel.getCreated(), null, null, null,
                        null, null, null, null, null, null, null, null, null));
        final ChatMembership left = existing.withLeft(now, "self");
        final boolean ok = dao().saveChatMembership(left);
        if (ok) {
            audit(AuditAction.CHAT_LEAVE, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(), "left");
        }
        return ok;
    }

    public boolean rejoin(final String tripId, final Person.Id personId, final AuditActor actor) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        final ChatChannel channel = ensureChannel(tripId, who);
        final Instant now = Instant.now();
        final Optional<ChatMembership> existing = dao().getChatMembership(channel.getId(), personId);
        if (existing.isPresent() && existing.get().getState() == ChatMembership.MemberState.REMOVED) {
            return false; // admin must re-add
        }
        // A JOINED row is about to mean something (a guest-marked one grants access), so nobody may write
        // themselves one without standing: trip members always may, and a departed guest keeps the guest
        // marker on their LEFT row, which is their ticket back in. Everyone else is refused and audited --
        // before this check, any authenticated session could join any trip's channel.
        if (!isTripMember(tripId, personId) && !existing.map(ChatMembership::isGuest).orElse(false)) {
            Audit.log(Audit.builder(AuditAction.CHAT_JOIN, AuditOutcome.FAILURE)
                    .actor(who)
                    .target(AuditEventBuilder.TARGET_CHAT_CHANNEL, channel.getId().getValue())
                    .message("denied: not a trip member and not an invited guest")
                    .build());
            return false;
        }
        final ChatMembership base = existing.orElseGet(() -> new ChatMembership(
                channel.getId(), personId, ChatMembership.MemberState.JOINED,
                ChatMembership.MemberRole.MEMBER, now, null, null, null,
                null, null, null, null, null, null, null, null, null));
        // joinedAt immutable: withRejoined keeps original joinedAt
        final ChatMembership rejoined = existing.isEmpty()
                ? base
                : base.withRejoined(now, who.id());
        final boolean ok = dao().saveChatMembership(rejoined);
        if (ok) {
            final String msg = existing.isPresent() && existing.get().getLeftAt() != null
                    ? "re-joined after leaving on " + existing.get().getLeftAt()
                    : "joined";
            audit(AuditAction.CHAT_JOIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(), msg);
        }
        return ok;
    }

    // --- invite links ---

    /** Session key prefix caching the caller's own most recently minted invite URL, per trip. */
    static final String INVITE_URL_SESSION_PREFIX = "chatInviteUrl:";

    public boolean invitesEnabled() {
        return config.getBoolean(KnownSettings.CHAT_INVITES_ENABLED);
    }

    /** Whether the signed-in user may mint an invite for this trip: anyone who can post, plus chat admins. */
    public boolean canInvite(final String tripId, final Person.Id me) {
        if (!invitesEnabled() || tripId == null || me == null) {
            return false;
        }
        final ChatChannel channel = channelForPage(tripId);
        final ChatMembership row = dao().getChatMembership(channel.getId(), me).orElse(null);
        return postDenial(channel, me, row, Instant.now()) == null || canAdminister(tripId);
    }

    /**
     * The Invite button's action: resolves the invite URL and stashes it in viewScope for the dialog (a plain
     * String — the Redisson session-replication rule). Separate from {@link #createInviteFromUi} so the JSF
     * side stays scope-plumbing only.
     */
    public void prepareInviteFromUi() {
        final String url = createInviteFromUi(currentTripId());
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            ctx.getViewRoot().getViewMap().put("inviteUrl", url == null ? "" : url);
        }
    }

    /**
     * The invite URL for the JSF dialog. Reuses the URL this session already minted for this trip — the row
     * stores only SHA-256(validator), so a link is only ever known in full to the session that minted it, and
     * re-minting on every dialog open would burn the outstanding-links cap for nothing.
     */
    public String createInviteFromUi(final String tripId) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final String sessionKey = INVITE_URL_SESSION_PREFIX + tripId;
        if (ctx != null) {
            final Object cached = ctx.getExternalContext().getSessionMap().get(sessionKey);
            if (cached instanceof String url && !url.isBlank()) {
                return url;
            }
        }
        final String url = createInvite(tripId, currentUserId(), AuditActor.current());
        if (url == null) {
            growlError("Unable to create an invite link for this chat.");
            return null;
        }
        if (ctx != null) {
            ctx.getExternalContext().getSessionMap().put(sessionKey, url);
        }
        return url;
    }

    /**
     * Mints a multi-use invite link and returns the full URL, or {@code null} when refused. The stored row
     * keeps only the validator's hash, so this return value is the only copy of the working link.
     */
    String createInvite(final String tripId, final Person.Id me, final AuditActor actor) {
        if (tripId == null || me == null || !canInvite(tripId, me)) {
            return null;
        }
        final ChatChannel channel = ensureChannel(tripId, actor);
        final Instant now = Instant.now();
        if (ChatVisibility.isArchived(channel, tripOf(channel), now)) {
            return null;
        }
        if (countOutstanding(channel.getId(), now) >= config.getInt(KnownSettings.CHAT_INVITE_MAX_OUTSTANDING)) {
            return null;
        }
        final String selector = RandomData.genSecureToken(9);
        final String validator = RandomData.genSecureToken(32);
        final ChatInvite invite = new ChatInvite(channel.getId(), selector,
                Digests.sha256Base64(validator), me, now, inviteExpiry(channel, now), 0L);
        if (!dao().saveChatInvite(invite)) {
            return null;
        }
        audit(AuditAction.CHAT_INVITE, actor, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                channel.getId().getValue(), "minted invite " + selector);
        return inviteUrl(tripId, selector, validator);
    }

    /**
     * Unexpired links in this channel, pruning expired rows on the way past — DynamoDB TTL will get them
     * eventually, but "eventually" must not hold seats against the outstanding-links cap for two days.
     */
    private long countOutstanding(final ChatChannel.Id channelId, final Instant now) {
        long live = 0;
        for (final ChatInvite invite : dao().listChatInvites(channelId)) {
            if (invite.isExpired(now)) {
                dao().deleteChatInvite(channelId, invite.getSelector());
            } else {
                live++;
            }
        }
        return live;
    }

    /** Expiry: the configured lifetime, capped so a link never outlives the writable chat. */
    private long inviteExpiry(final ChatChannel channel, final Instant now) {
        final int days = config.getInt(KnownSettings.CHAT_INVITE_EXPIRY_DAYS);
        long expiry = now.plus(Math.max(1, days), ChronoUnit.DAYS).getEpochSecond();
        final Instant archive = archiveInstant(channel, tripOf(channel));
        if (archive != null && archive.getEpochSecond() < expiry) {
            expiry = archive.getEpochSecond();
        }
        return expiry;
    }

    /** When this channel freezes: stored {@code archivedAt}, else trip end + archiveAfterTripEndDays, else never. */
    private static Instant archiveInstant(final ChatChannel channel, final Trip trip) {
        if (channel.getArchivedAt() != null) {
            return channel.getArchivedAt();
        }
        if (trip == null || trip.getEndDate() == null) {
            return null;
        }
        return trip.getEndDate()
                .plusDays(channel.getSettings().getArchiveAfterTripEndDays())
                .toInstant(java.time.ZoneOffset.UTC);
    }

    private String inviteUrl(final String tripId, final String selector, final String validator) {
        final String base = config.getString(KnownSettings.CHAT_MAIL_BASE_URL);
        final String prefix = (base == null || base.isBlank()) ? "" : base.replaceAll("/+$", "");
        return prefix + "/trip/chatInvite.jsf?trip="
                + java.net.URLEncoder.encode(tripId, java.nio.charset.StandardCharsets.UTF_8)
                + "&token=" + selector + "." + validator;
    }

    /**
     * Redeems an invite link for the signed-in user, from the {@code chatInvite.jsf} landing page. Returns a
     * status token the page branches on: {@code ok} means joined (or already in), anything else names the
     * refusal for a friendly message. All branching is here, not in jsft, on purpose.
     */
    public String redeemInvite(final String tripId, final String token) {
        return redeemInvite(tripId, token, currentUserId(), AuditActor.current());
    }

    String redeemInvite(final String tripId, final String token, final Person.Id me, final AuditActor actor) {
        if (me == null) {
            return "not-signed-in";
        }
        if (!invitesEnabled()) {
            return "disabled";
        }
        if (tripId == null || tripId.isBlank() || token == null) {
            return "invalid";
        }
        final int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            return "invalid";
        }
        final ChatChannel.Id channelId = ChatChannel.Id.forTrip(tripId);
        final ChatInvite invite = dao().getChatInvite(channelId, token.substring(0, dot)).orElse(null);
        if (invite == null || !Digests.matches(invite.getValidatorHash(),
                Digests.sha256Base64(token.substring(dot + 1)))) {
            return auditRedeemFailure(actor, channelId, "invalid or revoked invite token");
        }
        final Instant now = Instant.now();
        if (invite.isExpired(now)) {
            return auditRedeemFailure(actor, channelId, "expired invite " + invite.getSelector());
        }
        if (!chatEnabledForTrip(tripId) || dao().getTrip(tripId).isEmpty()) {
            return "disabled";
        }
        final ChatChannel channel = ensureChannel(tripId, actor);
        if (ChatVisibility.isArchived(channel, tripOf(channel), now)) {
            return "archived";
        }
        final ChatMembership existing = dao().getChatMembership(channel.getId(), me).orElse(null);
        if (existing != null && existing.getState() == ChatMembership.MemberState.REMOVED) {
            // An invite must not bypass moderation: whoever an admin removed stays removed, whatever links
            // they collect afterwards.
            auditRedeemFailure(actor, channel.getId(), "removed member presented invite " + invite.getSelector());
            return "removed";
        }
        if (canParticipate(tripId, me)) {
            // Idempotent success; the reverse-row rewrite is the self-heal for a lost second write below.
            dao().addGuestChatChannel(me, channel.getId());
            return "ok";
        }
        // Membership row first, reverse row second: losing the second write only hides the chat from the
        // person's own list, and re-clicking the invite lands in the branch above and heals it.
        if (!dao().saveChatMembership(ChatMembership.guestJoining(
                channel.getId(), me, now, invite.getSelector()))) {
            return "error";
        }
        dao().addGuestChatChannel(me, channel.getId());
        dao().recordChatInviteUse(invite);
        audit(AuditAction.CHAT_JOIN, actor, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                channel.getId().getValue(), "joined as guest via invite " + invite.getSelector());
        return "ok";
    }

    private String auditRedeemFailure(
            final AuditActor actor, final ChatChannel.Id channelId, final String message) {
        Audit.log(Audit.builder(AuditAction.CHAT_JOIN, AuditOutcome.FAILURE)
                .actor(actor == null ? AuditActor.current() : actor)
                .target(AuditEventBuilder.TARGET_CHAT_CHANNEL, channelId.getValue())
                .message("denied: " + message)
                .build());
        return "invalid";
    }

    /** The channel's outstanding invites for the admin table (the JSF/EL entry), unexpired only, newest first. */
    public List<ChatInvite> listInvites(final String tripId) {
        return listInvites(tripId, Caller.current());
    }

    List<ChatInvite> listInvites(final String tripId, final Caller caller) {
        if (!canAdminister(tripId, caller)) {
            return List.of();
        }
        final Instant now = Instant.now();
        return dao().listChatInvites(ChatChannel.Id.forTrip(tripId)).stream()
                .filter(invite -> !invite.isExpired(now))
                .sorted(Comparator.comparing(ChatInvite::getCreated).reversed())
                .toList();
    }

    /** The admin table's Revoke button (the JSF/EL entry). */
    public boolean revokeInvite(final String tripId, final String selector) {
        return revokeInvite(tripId, selector, Caller.current());
    }

    boolean revokeInvite(final String tripId, final String selector, final Caller caller) {
        if (denyUnlessAdmin(tripId, "revoke invite " + selector, caller)) {
            return false;
        }
        final ChatChannel.Id channelId = ChatChannel.Id.forTrip(tripId);
        final boolean ok = dao().deleteChatInvite(channelId, selector);
        if (ok) {
            audit(AuditAction.CHAT_INVITE, actorOf(caller), AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channelId.getValue(), "revoked invite " + selector);
        }
        return ok;
    }

    // --- admin ---

    /**
     * Removes a message, leaving a tombstone.
     *
     * <p>Permitted to a chat administrator for any message, and to the <b>author of that message</b> for their own,
     * with no time limit — unlike editing, which is capped at 15 minutes. The asymmetry is deliberate: an edit
     * rewrites what someone said and becomes misleading once others have replied to it, whereas a delete only
     * withdraws it and leaves a visible tombstone saying so.
     *
     * <p>Author identity is resolved from the stored message, never from the caller, so "my own" cannot be claimed.
     */
    public boolean deleteMessage(
            final String tripId, final ChatMessage.Id msgId, final Caller caller) {
        final AuditActor who = actorOf(caller);
        final ChatChannel channel = getChannel(tripId);
        if (channel == null) {
            return false;
        }
        final Optional<ChatMessage> before = dao().getChatMessage(channel.getId(), msgId);
        if (!isOwnMessage(before, who)) {
            if (denyUnlessAdmin(tripId, "delete message " + (msgId == null ? "?" : msgId.getValue()),
                    caller)) {
                return false;
            }
        }
        final Optional<ChatMessage> tomb = dao().tombstoneChatMessage(
                channel.getId(), msgId, who.id() == null ? who.email() : who.id());
        if (tomb.isPresent()) {
            final String snap = before.map(m -> snapshot(m.getBody(), 120)).orElse("");
            final String author = before.map(m -> m.getAuthorId() == null ? "?" : m.getAuthorId().getValue())
                    .orElse("?");
            final List<ChatAttachment> photos = before.map(ChatMessage::getAttachments).orElse(List.of());
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_MESSAGE, msgId.getValue(),
                    "message deleted; author=" + author + "; body=" + snap
                            + (photos.isEmpty() ? "" : "; photos=" + photos.size()));
            // Album semantics (user decision 2026-08-07): photos SURVIVE retention expiry but NOT removal.
            // Removing a message removes its photos everywhere -- stored renditions, CDN cache, album rows --
            // or an admin moderating an image would have to hunt down three more copies by hand.
            ChatPhotos.getChatPhotos().deleteEverywhere(photos);
            return true;
        }
        return false;
    }

    /**
     * Whether this actor wrote this message.
     *
     * <p>Compared on the id the message carries, so an author deleting their own never depends on the caller
     * naming themselves. Empty when the message is gone or has no author (a SYSTEM message is nobody's).
     */
    private boolean isOwnMessage(final Optional<ChatMessage> message, final AuditActor who) {
        if (who == null || who.id() == null) {
            return false;
        }
        return message.map(ChatMessage::getAuthorId)
                .filter(Objects::nonNull)
                .map(authorId -> authorId.getValue().equals(who.id()))
                .orElse(false);
    }

    /** Whether this person may delete this message: their own, or any if they administer the chat. */
    public boolean canDelete(final String tripId, final ChatMessage.Id msgId, final Person.Id me) {
        if (me == null || msgId == null) {
            return false;
        }
        if (canAdminister(tripId)) {
            return true;
        }
        final ChatChannel channel = getChannel(tripId);
        return channel != null && dao().getChatMessage(channel.getId(), msgId)
                .map(ChatMessage::getAuthorId).filter(Objects::nonNull).map(me::equals).orElse(false);
    }

    /** JSF helper: mute for N minutes from now. */
    public boolean muteMinutes(
            final String tripId, final String personId, final Integer minutes,
            final String reason) {
        if (personId == null || minutes == null || minutes <= 0) {
            growlError("Person id and positive mute minutes are required.");
            return false;
        }
        return mute(tripId, Person.Id.from(personId),
                Instant.now().plusSeconds(minutes * 60L), reason, Caller.current());
    }

    public boolean unmuteUi(final String tripId, final String personId) {
        if (personId == null) {
            return false;
        }
        return unmute(tripId, Person.Id.from(personId), Caller.current());
    }

    public boolean removeMemberUi(final String tripId, final String personId, final String reason) {
        if (personId == null) {
            return false;
        }
        return removeMember(tripId, Person.Id.from(personId), reason, Caller.current());
    }

    /**
     * Saves the settings and returns to the chat itself.
     *
     * <p>The settings page is somewhere you visit to change one thing, not somewhere you stay — landing back on
     * the form with no visible result reads as though nothing happened. On failure it stays put, so the growl
     * explaining why is still on screen.
     */
    public void saveSettingsAndReturn(
            final String tripId,
            final Boolean fullHistory,
            final String postPolicy,
            final Long retentionSeconds,
            final Integer slowModeSeconds,
            final Integer burstLimit,
            final Integer burstWindowSeconds,
            final Integer sustainedLimit,
            final Integer sustainedWindowSeconds,
            final Integer maxMessageChars,
            final Boolean allowReactions,
            final String retentionPreset,
            final Integer archiveAfterTripEndDays,
            final String backgroundColor,
            final String backgroundImageUrl,
            final Boolean allowMedia,
            final Integer maxPhotos,
            final Integer maxPhotoMb) {
        final boolean saved = saveSettingsFromUi(tripId, fullHistory, postPolicy, retentionSeconds,
                slowModeSeconds, burstLimit, burstWindowSeconds, sustainedLimit, sustainedWindowSeconds,
                maxMessageChars, allowReactions, retentionPreset, archiveAfterTripEndDays,
                backgroundColor, backgroundImageUrl, allowMedia, maxPhotos, maxPhotoMb);
        if (saved) {
            redirectToChat(tripId);
        }
    }

    private void redirectToChat(final String tripId) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null) {
            return;
        }
        try {
            ctx.getExternalContext().redirect(
                    ctx.getExternalContext().getRequestContextPath() + "/trip/chat.jsf?trip=" + tripId);
        } catch (final IOException | RuntimeException ex) {
            // The save already succeeded, so failing to navigate is a nuisance, not a data problem.
            log.warn("Saved chat settings but could not return to the chat page for trip {}", tripId, ex);
        }
    }

    public boolean saveSettingsFromUi(
            final String tripId,
            final Boolean fullHistory,
            final String postPolicy,
            final Long retentionSeconds,
            final Integer slowModeSeconds,
            final Integer burstLimit,
            final Integer burstWindowSeconds,
            final Integer sustainedLimit,
            final Integer sustainedWindowSeconds,
            final Integer maxMessageChars,
            final Boolean allowReactions,
            final String retentionPreset,
            final Integer archiveAfterTripEndDays,
            final String backgroundColor,
            final String backgroundImageUrl,
            final Boolean allowMedia,
            final Integer maxPhotos,
            final Integer maxPhotoMb) {
        final ChatChannel channel = ensureChannel(tripId, AuditActor.current());
        final ChatSettings updated = settingsFromForm(channel.getSettings(), fullHistory, postPolicy,
                retentionSeconds, slowModeSeconds, burstLimit, burstWindowSeconds, sustainedLimit,
                sustainedWindowSeconds, maxMessageChars, allowReactions, retentionPreset,
                archiveAfterTripEndDays, backgroundColor, backgroundImageUrl,
                allowMedia, maxPhotos, maxPhotoMb);
        return updateSettings(tripId, updated, Caller.current());
    }

    /**
     * The admin form's values applied over the stored settings. Separate from the save so the mapping — the
     * null-means-default rules, MB-to-bytes, the appearance validation — is testable without a JSF session
     * behind {@code Caller.current()}.
     */
    static ChatSettings settingsFromForm(
            final ChatSettings current,
            final Boolean fullHistory,
            final String postPolicy,
            final Long retentionSeconds,
            final Integer slowModeSeconds,
            final Integer burstLimit,
            final Integer burstWindowSeconds,
            final Integer sustainedLimit,
            final Integer sustainedWindowSeconds,
            final Integer maxMessageChars,
            final Boolean allowReactions,
            final String retentionPreset,
            final Integer archiveAfterTripEndDays,
            final String backgroundColor,
            final String backgroundImageUrl,
            final Boolean allowMedia,
            final Integer maxPhotos,
            final Integer maxPhotoMb) {
        return current.toBuilder()
                // The photo fields write MODERN shapes only: photos-off keeps the new caps, so it can never
                // collide with the v1 reserved fingerprint (false/0/0) the constructor upgrades.
                .allowMedia(allowMedia == null || allowMedia)
                .maxAttachmentsPerMessage(maxPhotos == null
                        ? ChatSettings.DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE : Math.max(0, maxPhotos))
                .maxAttachmentBytes((maxPhotoMb == null || maxPhotoMb <= 0)
                        ? ChatSettings.DEFAULT_MAX_ATTACHMENT_BYTES : maxPhotoMb * 1024L * 1024L)
                .fullHistoryForNewMembers(fullHistory == null || fullHistory)
                .postPolicy(postPolicy == null ? ChatSettings.PostPolicy.ALL_MEMBERS
                        : ChatSettings.PostPolicy.valueOf(postPolicy))
                .retentionSeconds(retentionFromPreset(retentionPreset, retentionSeconds))
                .archiveAfterTripEndDays(archiveAfterTripEndDays == null
                        ? ChatSettings.DEFAULT_ARCHIVE_AFTER_TRIP_END_DAYS : archiveAfterTripEndDays)
                .slowModeSeconds(slowModeSeconds == null ? 0 : slowModeSeconds)
                .burstLimit(burstLimit == null ? ChatSettings.DEFAULT_BURST_LIMIT : burstLimit)
                .burstWindowSeconds(burstWindowSeconds == null
                        ? ChatSettings.DEFAULT_BURST_WINDOW_SECONDS : burstWindowSeconds)
                .sustainedLimit(sustainedLimit == null
                        ? ChatSettings.DEFAULT_SUSTAINED_LIMIT : sustainedLimit)
                .sustainedWindowSeconds(sustainedWindowSeconds == null
                        ? ChatSettings.DEFAULT_SUSTAINED_WINDOW_SECONDS : sustainedWindowSeconds)
                .maxMessageChars(maxMessageChars == null
                        ? ChatSettings.DEFAULT_MAX_MESSAGE_CHARS : maxMessageChars)
                .allowReactions(allowReactions == null || allowReactions)
                // Round-tripped through ChatAppearance so the admin form is validated by exactly the same rules
                // as a member's override -- an unchecked colour is CSS injection and an unchecked URL is stored
                // XSS, and a value an administrator typed is no safer than one a member typed.
                .backgroundColor(new ChatAppearance(backgroundColor, null).getBackgroundColor())
                .backgroundImageUrl(new ChatAppearance(null, backgroundImageUrl).getBackgroundImageUrl())
                .build();
    }

    /**
     * Mutes somebody, with the caller's site-admin status supplied explicitly.
     *
     * <p>Same reason {@code deleteMessage} has this overload: {@code canAdminister} falls back to
     * {@code PersonCommands.hasRole("admin")}, which resolves through {@code FacesContext} and therefore reports
     * false on a JAX-RS thread. Without the hint a site administrator is silently refused every moderation
     * action over the API unless they also hold an explicit {@code chatAdmin} or {@code chatMgr} row.
     */
    public boolean mute(
            final String tripId, final Person.Id target, final Instant until,
            final String reason, final Caller caller) {
        final AuditActor who = actorOf(caller);
        if (denyUnlessAdmin(tripId, "mute " + target.getValue(), caller)) {
            return false;
        }
        return applyMute(tripId, target, until, reason, who);
    }

    /**
     * Writes a mute without the admin gate. Separate from {@link #mute} because the automatic mute is applied on
     * behalf of the <em>offending</em> user's own request: the actor on that thread is the person being muted, so
     * running it through the admin gate would deny the system's own enforcement action.
     */
    private boolean applyMute(
            final String tripId, final Person.Id target, final Instant until,
            final String reason, final AuditActor who) {
        final ChatChannel channel = ensureChannel(tripId, who);
        final ChatMembership member = materialize(channel, target, Instant.now());
        final ChatMembership muted = member.withMute(until, who.id(), reason);
        final boolean ok = dao().saveChatMembership(muted);
        if (ok) {
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(),
                    "mute person=" + target.getValue() + " until=" + until + " reason=" + reason);
        }
        return ok;
    }

    /** @see #mute(String, Person.Id, Instant, String, AuditActor, boolean) for why the hint exists. */
    public boolean unmute(
            final String tripId, final Person.Id target, final Caller caller) {
        final AuditActor who = actorOf(caller);
        if (denyUnlessAdmin(tripId, "unmute " + target.getValue(), caller)) {
            return false;
        }
        final ChatChannel channel = ensureChannel(tripId, who);
        final Optional<ChatMembership> row = dao().getChatMembership(channel.getId(), target);
        if (row.isEmpty()) {
            return true;
        }
        final boolean ok = dao().saveChatMembership(row.get().withUnmuted());
        if (ok) {
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(), "unmute person=" + target.getValue());
        }
        return ok;
    }

    /** @see #mute(String, Person.Id, Instant, String, AuditActor, boolean) for why the hint exists. */
    public boolean removeMember(
            final String tripId, final Person.Id target, final String reason, final Caller caller) {
        final AuditActor who = actorOf(caller);
        if (denyUnlessAdmin(tripId, "remove " + target.getValue(), caller)) {
            return false;
        }
        final ChatChannel channel = ensureChannel(tripId, who);
        final Instant now = Instant.now();
        // Materialise even for implicit members — absent row ⇒ JOINED, so REMOVE must write a row.
        final ChatMembership member = materialize(channel, target, now);
        final ChatMembership removed = member.withRemoved(now, reason, who.id());
        final boolean ok = dao().saveChatMembership(removed);
        if (ok) {
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(),
                    "member removed person=" + target.getValue() + " reason=" + reason);
        }
        return ok;
    }

    /**
     * Admin add/re-add. {@code acknowledgement} is recorded verbatim in the audit trail.
     */
    /** @see #mute(String, Person.Id, Instant, String, AuditActor, boolean) for why the hint exists. */
    public boolean addMember(
            final String tripId, final Person.Id target, final String acknowledgement,
            final Caller caller) {
        final AuditActor who = actorOf(caller);
        if (denyUnlessAdmin(tripId, "add " + target.getValue(), caller)) {
            return false;
        }
        // The acknowledgement IS the control here: the requirement is that a person is never re-enabled without
        // their permission, so an add with nothing confirmed must fail rather than be recorded as unacknowledged.
        if (acknowledgement == null || acknowledgement.isBlank()) {
            growlError("Confirm you have this person's permission before adding them.");
            return false;
        }
        final ChatChannel channel = ensureChannel(tripId, who);
        final Instant now = Instant.now();
        final Optional<ChatMembership> existing = dao().getChatMembership(channel.getId(), target);
        final ChatMembership base = existing.orElseGet(() -> new ChatMembership(
                channel.getId(), target, ChatMembership.MemberState.JOINED,
                ChatMembership.MemberRole.MEMBER, now, null, null, null,
                null, null, null, null, null, null, null, null, null));
        final ChatMembership added = existing.isPresent()
                ? base.withRejoined(now, who.id())
                : base.withState(ChatMembership.MemberState.JOINED);
        final boolean ok = dao().saveChatMembership(added);
        if (ok) {
            final String prior = existing.map(m -> m.getState().name()).orElse("implicit");
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(),
                    "member added person=" + target.getValue() + " prior=" + prior
                            + " ack=" + acknowledgement);
        }
        return ok;
    }

    /** Records an export as the bulk disclosure it is: who, which channel, and how many messages left the app. */
    public void auditExport(final String tripId, final int messageCount, final AuditActor actor) {
        audit(AuditAction.CHAT_ADMIN, actor, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                ChatChannel.Id.forTrip(tripId).getValue(),
                "transcript exported; messages=" + messageCount);
    }

    public String addWarningText(final ChatChannel channel, final Person.Id target) {
        final Optional<ChatMembership> row = dao().getChatMembership(channel.getId(), target);
        final Person person = PersonCommands.getPersonCommands().getPerson(target);
        final String name = person == null ? target.getValue() : person.getPreferredName();
        if (row.isEmpty()) {
            return "Add " + name + " to this chat? Confirm you have their permission.";
        }
        final ChatMembership m = row.get();
        if (m.getState() == ChatMembership.MemberState.LEFT) {
            return name + " chose to leave this chat on " + m.getLeftAt()
                    + ". Adding them back will resume notifications and expose the full history. "
                    + "Confirm you have their permission.";
        }
        if (m.getState() == ChatMembership.MemberState.REMOVED) {
            // Names the administrator AND the reason. Also states plainly that re-adding exposes what was said
            // while they were out -- which may include discussion of the removal itself. Making that a conscious
            // human decision is this warning's whole job.
            final String by = m.getRemovedBy() == null ? "an administrator" : displayName(m.getRemovedBy());
            return name + " was removed by " + by + " on " + m.getLeftAt()
                    + (m.getLeftReason() == null ? "" : " (" + m.getLeftReason() + ")")
                    + ". Adding them back lets them read everything said while they were removed. "
                    + "Confirm you intend to reverse that.";
        }
        return "Add " + name + " to this chat? Confirm you have their permission.";
    }

    private String displayName(final String personId) {
        final Person person = PersonCommands.getPersonCommands().getPerson(Person.Id.from(personId));
        return person == null ? personId : person.getPreferredName();
    }

    /** @see #mute(String, Person.Id, Instant, String, AuditActor, boolean) for why the hint exists. */
    public boolean updateSettings(
            final String tripId,
            final ChatSettings newSettings,
            final Caller caller) {
        final AuditActor who = actorOf(caller);
        if (denyUnlessAdmin(tripId, "update settings", caller)) {
            return false;
        }
        final ChatChannel channel = ensureChannel(tripId, who);
        final String validation = validateSettings(newSettings);
        if (validation != null) {
            growlError(validation);
            return false;
        }
        final ChatSettings before = channel.getSettings();
        final ChatChannel updated = channel.withSettings(newSettings);
        // Turning fullHistory off backfills roster with joinedAt = channel.created
        if (before.isFullHistoryForNewMembers() && !newSettings.isFullHistoryForNewMembers()) {
            backfillRoster(channel);
        }
        final boolean ok = dao().saveChatChannel(updated);
        if (ok) {
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(), describeSettingsChange(before, newSettings));
        }
        return ok;
    }

    /**
     * Turns the admin page's retention preset into the stored number.
     *
     * <p>{@code "forever"} maps to {@code null}, and that distinction is load-bearing: {@code null} means no expiry
     * at all, while {@code 0} means "expires with the hot buffer" — visually adjacent in a dropdown, opposite in
     * effect. An unrecognised preset falls back to the caller's existing value rather than guessing, because
     * guessing here either deletes history or keeps what an admin asked to expire.
     */
    static Long retentionFromPreset(final String preset, final Long existing) {
        if (preset == null || preset.isBlank()) {
            return existing;
        }
        if ("forever".equalsIgnoreCase(preset.trim())) {
            return null;
        }
        try {
            return Long.valueOf(preset.trim());
        } catch (final NumberFormatException ex) {
            log.warn("Unrecognised chat retention preset {}; keeping the existing setting", preset);
            return existing;
        }
    }

    public static String validateSettings(final ChatSettings s) {
        if (s == null) {
            return "Settings required";
        }
        if (s.getBurstLimit() < 1 || s.getSustainedLimit() < 1) {
            return "Rate limits must be at least 1 (0 would mute the channel).";
        }
        if (s.getBurstLimit() > 10_000 || s.getSustainedLimit() > 10_000) {
            return "Rate limits cannot exceed 10,000.";
        }
        if (s.getBurstWindowSeconds() < 1 || s.getBurstWindowSeconds() > 3600
                || s.getSustainedWindowSeconds() < 1 || s.getSustainedWindowSeconds() > 3600) {
            return "Rate-limit windows must be between 1 second and 1 hour.";
        }
        if (s.getMaxMessageChars() < 1) {
            return "maxMessageChars must be at least 1.";
        }
        return null;
    }

    public String describeSettingsChange(final ChatSettings before, final ChatSettings after) {
        final StringBuilder sb = new StringBuilder("Saved settings");
        diff(sb, "retention", before.getRetentionSeconds(), after.getRetentionSeconds());
        diff(sb, "fullHistoryForNewMembers", before.isFullHistoryForNewMembers(),
                after.isFullHistoryForNewMembers());
        diff(sb, "postPolicy", before.getPostPolicy(), after.getPostPolicy());
        diff(sb, "burstLimit", before.getBurstLimit(), after.getBurstLimit());
        diff(sb, "slowModeSeconds", before.getSlowModeSeconds(), after.getSlowModeSeconds());
        return sb.toString();
    }

    private static void diff(final StringBuilder sb, final String name, final Object a, final Object b) {
        if (!Objects.equals(a, b)) {
            sb.append("; ").append(name).append(' ').append(a).append("→").append(b);
        }
    }

    private void backfillRoster(final ChatChannel channel) {
        final Trip trip = tripOf(channel);
        if (trip == null) {
            return;
        }
        for (final Person.Id pid : trip.getPeople()) {
            final Optional<ChatMembership> row = dao().getChatMembership(channel.getId(), pid);
            if (row.isEmpty()) {
                final ChatMembership m = ChatMembership.joining(channel.getId(), pid, channel.getCreated());
                dao().saveChatMembership(m);
            }
        }
    }

    // --- my chats ---

    public List<ChatSummary> myChats(final Person.Id personId) {
        // Union of my own trips, my whole family's trips, and my invite-guest channels (deduped): anyone in
        // a family with an on-trip member has full chat membership (isTripMember), so their My Chats must
        // list that trip too -- and a guest's channel is reachable through no trip of theirs at all, only
        // through the person:{id} reverse rows. The canRead filter below is what drops a removed guest.
        final Map<String, Trip> byId = new java.util.LinkedHashMap<>();
        for (final Trip trip : dao().getTripsForUser(personId)) {
            byId.put(trip.getId(), trip);
        }
        for (final Person.Id relativeId : householdOf(personId)) {
            for (final Trip trip : dao().getTripsForUser(relativeId)) {
                byId.putIfAbsent(trip.getId(), trip);
            }
        }
        for (final ChatChannel.Id guestChannelId : dao().getGuestChatChannelIds(personId)) {
            final String tripId = guestChannelId.tripIdOrNull();
            if (tripId != null && !byId.containsKey(tripId)) {
                dao().getTrip(tripId).ifPresent(trip -> byId.put(trip.getId(), trip));
            }
        }
        final List<Trip> trips = new ArrayList<>(byId.values());
        final Map<String, String> lastAct = dao().getCacheClient().getHash(CacheKeys.CHAT_LAST_ACTIVITY);
        final List<ChatSummary> out = new ArrayList<>();
        for (final Trip trip : trips) {
            final ChatChannel.Id cid = ChatChannel.Id.forTrip(trip.getId());
            final ChatChannel channel = dao().getChatChannel(cid).orElse(null);
            if (channel == null) {
                continue;
            }
            if (!canRead(channel, personId)) {
                continue;
            }
            final long activity = parseLong(lastAct.get(cid.getValue()), 0L);
            out.add(new ChatSummary(channel, trip.getTitle(), activity,
                    unreadAgainst(channel, personId, lastAct)));
        }
        out.sort(Comparator.comparingLong(ChatSummary::lastActivityMillis).reversed());
        return out;
    }

    public List<ChatMembership> roster(final String tripId) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null) {
            return List.of();
        }
        return dao().listChatMembers(channel.getId());
    }

    // --- JSF helpers ---

    public void sendFromUi(final String body) {
        final Person.Id me = currentUserId();
        final String tripId = currentTripId();
        if (me == null || tripId == null) {
            return;
        }
        final String clientId = ScopeUtil.getInstance().getViewMap("chatClientMessageId");
        final Object reply = ScopeUtil.getInstance().getViewMap("chatReplyTo");
        final ChatMessage.Id replyId = reply instanceof String s && !s.isBlank()
                ? ChatMessage.Id.from(s) : null;
        // The staged-photo tray, serialised into a hidden input by the Send button's onclick (the same
        // moment the mention tokens are swapped): [{key, title}, ...]. A string in viewScope, like
        // everything else here -- see the classloader note on the page.
        final Object attachRaw = ScopeUtil.getInstance().getViewMap("chatAttachments");
        final List<ChatPhotos.AttachmentRef> refs =
                ChatPhotos.parseRefs(attachRaw instanceof String json ? json : null);
        final SendResult result = send(tripId, me, body, clientId, replyId, AuditActor.current(), refs);
        if (result.isOk()) {
            final FacesContext ctx = FacesContext.getCurrentInstance();
            if (ctx != null && ctx.getViewRoot() != null) {
                ctx.getViewRoot().getViewMap().put("chatDraft", "");
                ctx.getViewRoot().getViewMap().put("chatReplyTo", null);
                ctx.getViewRoot().getViewMap().put("chatAttachments", "");
            }
        } else if (result.getMessage() != null) {
            growlWarn(result.getMessage());
        }
    }

    public void leaveFromUi() {
        final Person.Id me = currentUserId();
        final String tripId = currentTripId();
        if (me != null && tripId != null) {
            leave(tripId, me, AuditActor.current());
        }
    }

    public void rejoinFromUi() {
        final Person.Id me = currentUserId();
        final String tripId = currentTripId();
        if (me != null && tripId != null) {
            rejoin(tripId, me, AuditActor.current());
        }
    }

    // --- internals ---

    /**
     * The membership row, creating one for an implicit member if needed. Every state change must materialise a row:
     * because an absent row means JOINED, a removal or mute that fails to write one silently evaporates on the
     * person's next read.
     */
    private ChatMembership materialize(final ChatChannel channel, final Person.Id personId, final Instant now) {
        // An implicit member has been in the channel since it existed, so that is their joinedAt floor.
        final Instant since = channel.getCreated() == null ? now : channel.getCreated();
        return dao().getChatMembership(channel.getId(), personId)
                .orElseGet(() -> ChatMembership.joining(channel.getId(), personId, since));
    }

    private Trip tripOf(final ChatChannel channel) {
        if (channel == null || channel.getTripId() == null) {
            return null;
        }
        return dao().getTrip(channel.getTripId()).orElse(null);
    }

    private DAO dao() {
        return DAO.getInstance();
    }

    private Person.Id currentUserId() {
        final Object id = ScopeUtil.getInstance().getSessionMap(PersonCommands.ACTIVE_USER_ID);
        if (id instanceof Person.Id pid) {
            return pid;
        }
        if (id != null) {
            return Person.Id.from(id.toString());
        }
        return null;
    }

    private String currentTripId() {
        final Object trip = ScopeUtil.getInstance().getViewMap("theTrip");
        if (trip instanceof Trip t) {
            return t.getId();
        }
        final Object param = ScopeUtil.getInstance().getRequestMap("trip");
        // Also try request parameter map via FacesContext
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (param == null && ctx != null) {
            final String q = ctx.getExternalContext().getRequestParameterMap().get("trip");
            return q;
        }
        return param == null ? null : param.toString();
    }

    private void audit(
            final AuditAction action, final AuditActor actor,
            final String targetType, final String targetId, final String message) {
        Audit.log(Audit.builder(action, AuditOutcome.SUCCESS)
                .actor(actor == null ? AuditActor.current() : actor)
                .target(targetType, targetId)
                .message(message)
                .build());
    }

    private static String snapshot(final String body, final int maxChars) {
        if (body == null) {
            return "";
        }
        return body.length() <= maxChars ? body : body.substring(0, maxChars);
    }

    private static long parseLong(final String s, final long dflt) {
        if (s == null) {
            return dflt;
        }
        try {
            return Long.parseLong(s);
        } catch (final NumberFormatException ex) {
            return dflt;
        }
    }

    private void growlWarn(final String msg) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, msg, null);
    }

    private void growlError(final String msg) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, msg, null);
    }

    /**
     * A row of {@code chats.xhtml}, which stashes the whole list in {@code viewScope}.
     *
     * <p>{@code Serializable} is load-bearing, not decoration: viewScope lives in the HTTP session, and
     * production stores sessions in Valkey through Redisson. A non-serializable attribute makes the session
     * SAVE throw, so the response is torn down after the page has already rendered -- and then every later
     * request on that session fails too. The visible symptom is a site-wide outage, not a chat bug. Every field
     * here must stay serializable as well ({@link ChatChannel} already is).
     */
    public record ChatSummary(
            ChatChannel channel, String tripTitle, long lastActivityMillis, boolean unread)
            implements java.io.Serializable {
    }

    /** Outcome of a reaction toggle. Carries a code so the REST edge can map it to a status without re-deciding. */
    public record ReactResult(boolean ok, String code, String message) {

        public static ReactResult success() {
            return new ReactResult(true, null, null);
        }

        public static ReactResult fail(final String code, final String message) {
            return new ReactResult(false, code, message);
        }
    }

    public static final class SendResult {
        private final boolean ok;
        private final String code;
        private final String message;
        private final ChatMessage messageObj;
        private final ChatRateLimiter.Decision decision;

        private SendResult(
                final boolean ok, final String code, final String message,
                final ChatMessage messageObj, final ChatRateLimiter.Decision decision) {
            this.ok = ok;
            this.code = code;
            this.message = message;
            this.messageObj = messageObj;
            this.decision = decision;
        }

        public static SendResult ok(final ChatMessage msg) {
            return new SendResult(true, null, null, msg, null);
        }

        /** Sent, but with something the sender should be told — today, that their @all was not emailed. */
        public static SendResult ok(final ChatMessage msg, final String notice) {
            return new SendResult(true, null, notice, msg, null);
        }

        public static SendResult fail(final String code, final String message) {
            return new SendResult(false, code, message, null, null);
        }

        public static SendResult rateLimited(final ChatRateLimiter.Decision decision) {
            return new SendResult(false, "rate_limit", decision.userMessage(), null, decision);
        }

        public boolean isOk() {
            return ok;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public ChatMessage getMessageObj() {
            return messageObj;
        }

        public ChatRateLimiter.Decision getDecision() {
            return decision;
        }
    }
}
