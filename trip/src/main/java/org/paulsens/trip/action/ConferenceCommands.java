package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.model.TripPageOptions;
import org.paulsens.trip.pay.MoneyMath;
import org.paulsens.trip.site.ListingScope;

/**
 * The conference page's views (docs/conference.md): which trip a section is about, the public agenda, and the
 * registration block. Everything here runs on the PUBLIC render path of an anonymous visitor, so it reads only
 * the cached DAO lookups (RenderPathCacheTest) and touches no session or view scope. Results are memoized per
 * request, because a fragment's EL evaluates the same value several times while it renders.
 */
@Slf4j
@Named("conference")
@ApplicationScoped
public class ConferenceCommands {
    /** The programmatic property naming an explicit trip (blank means "this page's trip"). */
    public static final String PROP_TRIP_ID = "tripId";

    private final Supplier<Map<String, Object>> requestMap;
    private final Supplier<LocalDateTime> clock;
    private final Supplier<Boolean> orgFullWidth;

    public ConferenceCommands() {
        this(ConferenceCommands::facesRequestMap, LocalDateTime::now, () -> new BrandCommands().isFullWidth());
    }

    /** Test seam: a request map (null for none) and the current time. */
    ConferenceCommands(final Supplier<Map<String, Object>> requestMap, final Supplier<LocalDateTime> clock) {
        this(requestMap, clock, () -> new BrandCommands().isFullWidth());
    }

    /** Test seam adding the host organization's layout choice ({@code BrandCommands.isFullWidth}). */
    ConferenceCommands(final Supplier<Map<String, Object>> requestMap, final Supplier<LocalDateTime> clock,
            final Supplier<Boolean> orgFullWidth) {
        this.requestMap = requestMap;
        this.clock = clock;
        this.orgFullWidth = orgFullWidth;
    }

    // ------------------------------------------------------------------ which trip

    /**
     * The trip a conference section is about, or null when there is none this site may show: the instance's own
     * {@value #PROP_TRIP_ID} when set (a section placed on an org's home page), else the trip its page key names,
     * else the host page's trip ({@code hostTripId}, for a section inside a container). Never a trip outside
     * {@link ListingScope#reachable}.
     */
    public Trip tripFor(final ContentInstance instance, final String hostTripId) {
        final String id = tripIdFor(instance, hostTripId);
        if (id == null) {
            return null;
        }
        return memo("conferenceTrip:" + id, () -> reachableTrip(id));
    }

    static String tripIdFor(final ContentInstance instance, final String hostTripId) {
        final String explicit = instance == null ? null : instance.getValues().get(PROP_TRIP_ID);
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        final String fromKey = instance == null ? null : OrgPageBootstrap.tripOf(instance.getSection());
        if (fromKey != null) {
            return fromKey;
        }
        return (hostTripId == null || hostTripId.isBlank()) ? null : hostTripId.trim();
    }

    private static Trip reachableTrip(final String id) {
        try {
            return DAO.getInstance().getTrip(id, Cached.YES)
                    .filter(trip -> ListingScope.reachable(trip.getOrgId()))
                    .orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to resolve the trip for a conference section: " + id, ex);
            return null;
        }
    }

    // ------------------------------------------------------------------ the public agenda

    /** One day of the public schedule. */
    public record AgendaDay(LocalDate date, String label, List<AgendaItem> items) {
    }

    /** One session: never a participant, never a private note. */
    public record AgendaItem(String time, String title, String presenter, String location, String track,
            String notes) {
    }

    /**
     * The trip's PUBLIC schedule: its EVENT-type events not hidden from the agenda, in start order, grouped by day.
     * Flights, buses and lodging are travel logistics, not program, and never appear.
     */
    public List<AgendaDay> publicAgenda(final Trip trip) {
        if (trip == null) {
            return List.of();
        }
        return memo("conferenceAgenda:" + trip.getId(), () -> buildAgenda(trip));
    }

    private static List<AgendaDay> buildAgenda(final Trip trip) {
        final Locale locale = localeOf(trip);
        final DateTimeFormatter dayFormat = DateTimeFormatter.ofPattern("EEEE, MMMM d", locale);
        final DateTimeFormatter timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale);
        final Map<LocalDate, List<AgendaItem>> byDay = new LinkedHashMap<>();
        trip.getTripEvents().stream()
                .filter(event -> event.getType() == TripEvent.Type.EVENT && !event.isAgendaHidden())
                .filter(event -> event.getStart() != null)
                .sorted(Comparator.comparing(TripEvent::getStart))
                .forEach(event -> byDay.computeIfAbsent(event.getStart().toLocalDate(), day -> new ArrayList<>())
                        .add(itemOf(event, timeFormat)));
        final List<AgendaDay> days = new ArrayList<>();
        byDay.forEach((date, items) -> days.add(new AgendaDay(date, dayFormat.format(date), List.copyOf(items))));
        return days;
    }

    private static AgendaItem itemOf(final TripEvent event, final DateTimeFormatter timeFormat) {
        final String start = timeFormat.format(event.getStart());
        final boolean sameDayEnd = event.getEnd() != null && event.getEnd().isAfter(event.getStart())
                && event.getEnd().toLocalDate().equals(event.getStart().toLocalDate());
        final String time = sameDayEnd ? start + " - " + timeFormat.format(event.getEnd()) : start;
        return new AgendaItem(time, event.getTitle(), event.detail(TripEvent.Detail.PRESENTER),
                event.detail(TripEvent.Detail.LOCATION), event.detail(TripEvent.Detail.TRACK),
                blankToNull(event.getNotes()));
    }

    // ------------------------------------------------------------------ the registration block

    /** Where registration stands. */
    public enum Phase {
        /** The event has not started: registration is open. */
        OPEN,
        /** Every place is taken. */
        FULL,
        /** The event is under way or over. */
        CLOSED
    }

    /** One priced question's offer: its label and its choices as "Full weekend: $150.00". */
    public record PriceGroup(String question, List<String> choices) {
    }

    /**
     * The registration block's facts. {@code taken} counts CONFIRMED and PENDING registrations; {@code seatsLeft}
     * is null when the trip has no limit. {@code prices} are the trip's priced questions as configured; the
     * person-specific price (age bands, codes) is checkout's to compute, never this block's.
     */
    public record RegistrationInfo(Phase phase, int taken, Integer limit, Integer seatsLeft,
            List<PriceGroup> prices, String fallbackPrice, String startDateIso, String registerUrl,
            String registerLabel) {
    }

    /** The registration block for a conference (or any trip), or null for none. */
    public RegistrationInfo registrationInfo(final Trip trip) {
        if (trip == null) {
            return null;
        }
        return memo("conferenceReg:" + trip.getId(), () -> buildInfo(trip));
    }

    private RegistrationInfo buildInfo(final Trip trip) {
        final int taken = (int) registrations(trip).stream()
                .filter(reg -> reg.getStatus() != Registration.Status.NOT_REGISTERED)
                .count();
        final Integer limit = trip.getRegLimit();
        final Integer seatsLeft = limit == null ? null : Math.max(0, limit - taken);
        final Phase phase = phaseOf(trip, seatsLeft);
        return new RegistrationInfo(phase, taken, limit, seatsLeft, priceGroups(trip),
                blankToNull(trip.getEstimatedPrice()), trip.getStartDateIso(), registerUrl(trip),
                trip.getKind().getRegisterLabel());
    }

    private Phase phaseOf(final Trip trip, final Integer seatsLeft) {
        if (trip.getStartDate() != null && !trip.getStartDate().isAfter(clock.get())) {
            return Phase.CLOSED;
        }
        return (seatsLeft != null && seatsLeft == 0) ? Phase.FULL : Phase.OPEN;
    }

    private static List<Registration> registrations(final Trip trip) {
        try {
            return DAO.getInstance().getRegistrations(trip.getId(), Cached.YES);
        } catch (final RuntimeException ex) {
            log.error("Unable to count registrations for trip " + trip.getId(), ex);
            return List.of();
        }
    }

    private static List<PriceGroup> priceGroups(final Trip trip) {
        final List<PriceGroup> groups = new ArrayList<>();
        for (final RegistrationOption question : trip.getRegOptions()) {
            if (Boolean.TRUE.equals(question.getShow()) && question.isPriced()) {
                groups.add(new PriceGroup(question.getShortDesc(),
                        question.getOfferedChoices().stream().map(ConferenceCommands::priceLine).toList()));
            }
        }
        return groups;
    }

    private static String priceLine(final OptionChoice choice) {
        return choice.getLabel() + ": " + (choice.isPriced() ? MoneyMath.formatCents(choice.getPriceCents()) : "free");
    }

    /** The registration page for a trip: an external trip registers on its provider's site. */
    public String registerUrl(final Trip trip) {
        if (trip == null) {
            return "";
        }
        return trip.isExternal() ? trip.getNonHostedTripUrl() : "/trip/joinTrip.jsf?trip=" + trip.getId();
    }

    // ------------------------------------------------------------------ the page's look

    /**
     * How a conference page renders (docs/conference.md, "Look and feel"): the trip's own options laid over the
     * organization's Appearance settings. {@code rootStyle} is null when the trip sets no background of its own, so
     * the template keeps {@code brand.rootStyle}; {@code stylesheetUrl} is already screened for an attribute.
     */
    public record PageLook(boolean fullWidth, String rootStyle, boolean titleShown, boolean navShown,
            String stylesheetUrl) {
    }

    /** The one problem the field rules cannot see; its text is pinned here because the dialog's test reads it. */
    static final String STYLESHEET_RULE =
            "The stylesheet must be a CSS file in the organization's media library (upload it there first).";

    /**
     * The look of the conference page for {@code tripId}, or null when this host shows no such page (no such trip,
     * a pilgrimage, or a conference the site does not reach: the same rule as {@code SiteCommands.tripPageKey}).
     * Cached reads only, memoized per request: the page's build-time params (the layout, the stylesheet link) read
     * it from the request parameter before the page's initPage runs, and the render reads it again.
     */
    public PageLook lookFor(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return null;
        }
        final String id = tripId.trim();
        return memo("conferenceLook:" + id, () -> lookOf(reachableConference(id)));
    }

    private static Trip reachableConference(final String id) {
        final Trip trip = reachableTrip(id);
        return trip != null && trip.isConference() ? trip : null;
    }

    private PageLook lookOf(final Trip trip) {
        if (trip == null) {
            return null;
        }
        final TripPageOptions options = trip.pageOrNull() == null ? new TripPageOptions() : trip.pageOrNull();
        final boolean ownBackground = options.getBackgroundUrl() != null || options.getBackgroundColor() != null;
        return new PageLook(options.fullWidth(orgFullWidth.get()),
                ownBackground ? BrandCommands.rootStyleOf(options.getBackgroundUrl(), options.getBackgroundColor())
                        : null,
                options.isTitleShown(), options.isNavShown(), BrandCommands.safeLinkUrl(options.getStylesheetUrl()));
    }

    /**
     * What is wrong with the trip's page options as the editor left them: the field rules
     * ({@link TripPageOptions#problems}) plus the one that needs the DAO: a stylesheet must be a CSS file in the
     * trip's own organization's media library. Any other URL would let a trip manager load arbitrary CSS into the
     * org's site, which is the org's content admins' call; the library is where they hold it.
     */
    public List<String> pageProblems(final Trip trip) {
        if (trip == null || trip.pageOrNull() == null) {
            return List.of();
        }
        final List<String> problems = new ArrayList<>(trip.pageProblems());
        final String css = trip.pageOrNull().getStylesheetUrl();
        if (css != null && problems.stream().noneMatch(problem -> problem.contains("stylesheet"))) {
            final MediaItem item = new MediaCommands().libraryItemFor(trip.getOrgId(), css);
            if (item == null || !MediaCommands.isStylesheet(item)) {
                problems.add(STYLESHEET_RULE);
            }
        }
        return problems;
    }

    /**
     * The Conference Page dialog's Done: growls every problem and publishes {@code pageOk} so the dialog stays
     * open on a refusal (the Registration Pricing dialog's {@code pricingOk} pattern).
     */
    public boolean acceptPage(final Trip trip) {
        final List<String> problems = pageProblems(trip);
        problems.forEach(PageFeedback::warn);
        PageFeedback.callbackParam("pageOk", problems.isEmpty());
        return problems.isEmpty();
    }

    /** The property-sheet summary beside the editor's link: "the organization's look", or what differs from it. */
    public String pageSummary(final Trip trip) {
        final TripPageOptions options = trip == null ? null : trip.pageOrNull();
        if (options == null || options.isEmpty()) {
            return "the organization's look";
        }
        final List<String> parts = new ArrayList<>();
        if (options.getLayout() != null) {
            parts.add(KnownSettings.LAYOUT_FULL_WIDTH.equals(options.getLayout()) ? "full width" : "classic");
        }
        if (options.getBackgroundUrl() != null) {
            parts.add("background image");
        } else if (options.getBackgroundColor() != null) {
            parts.add("background " + options.getBackgroundColor());
        }
        if (options.isTitleShown()) {
            parts.add("title shown");
        }
        if (!options.isNavShown()) {
            parts.add("section links hidden");
        }
        if (options.getStylesheetUrl() != null) {
            parts.add("own stylesheet");
        }
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------ seeding the page

    /**
     * Seeds a conference's public page with its starter sections ({@code OrgPageBootstrap.tripRows}) the first time
     * the trip is saved as a conference, exactly once: the trip records when, so a page its editors emptied stays
     * empty, and a page someone authored first is left alone. While any starter template is missing (production
     * before {@code install-starter-templates.sh} ran) nothing is seeded and nothing stamped, so the next save
     * retries. The stamp is written onto {@code trip} itself, the caller's own object (an editor's draft), so a
     * later save of that draft cannot erase it.
     *
     * @return whether the trip now has (or already had) its seeded page
     */
    public boolean ensurePage(final Trip trip) {
        if (trip == null || !trip.isConference()) {
            return false;
        }
        if (trip.getPageSeededAt() != null) {
            return true;
        }
        final String missing = missingStarter(ConferenceCommands::installed);
        if (missing != null) {
            log.warn("Conference page for trip {} not seeded: starter template '{}' is not installed", trip.getId(),
                    missing);
            return false;
        }
        try {
            final LocalDateTime now = clock.get();
            final List<ContentInstance> rows =
                    OrgPageBootstrap.tripRows(trip, orgNameOf(trip), OrgCommands::currentTemplateVersion, now);
            if (!OrgCommands.seedIfEmpty(OrgPageBootstrap.tripPageKey(trip.getId()), rows)) {
                log.error("Conference page for trip {} could not be saved", trip.getId());
                return false;
            }
            trip.setPageSeededAt(now);
            return DAO.getInstance().saveTrip(trip);
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to seed the conference page of trip {}", trip.getId(), ex);
            return false;
        }
    }

    /** The first starter template the conference page needs that {@code installed} says is missing, or null. */
    static String missingStarter(final Predicate<String> installed) {
        return OrgPageBootstrap.tripRowTemplateIds().stream().filter(installed.negate()).findFirst().orElse(null);
    }

    private static boolean installed(final String templateId) {
        return DAO.getInstance().getTemplate(templateId, Cached.NO).isPresent();
    }

    private static String orgNameOf(final Trip trip) {
        return trip.getOrganization() == null ? "" : trip.getOrganization().getName();
    }

    // ------------------------------------------------------------------ helpers

    private static Locale localeOf(final Trip trip) {
        return (trip.getLanguage() == null ? Language.English : trip.getLanguage()).getLocale();
    }

    private static String blankToNull(final String text) {
        return (text == null || text.isBlank()) ? null : text;
    }

    @SuppressWarnings("unchecked")
    private <T> T memo(final String key, final Supplier<T> compute) {
        final Map<String, Object> map = requestMap.get();
        if (map == null) {
            return compute.get();
        }
        if (map.containsKey(key)) {
            return (T) map.get(key);
        }
        final T value = compute.get();
        map.put(key, value);
        return value;
    }

    private static Map<String, Object> facesRequestMap() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return ctx == null ? null : ctx.getExternalContext().getRequestMap();
    }
}
