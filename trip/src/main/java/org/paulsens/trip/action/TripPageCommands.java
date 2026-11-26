package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.checkout.CheckoutValidator;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.content.EmbedStyles;
import org.paulsens.trip.content.PageHtml;
import org.paulsens.trip.content.PageVersions;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.i18n.Formats;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.theme.ThemeResources;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.model.TripPageOptions;
import org.paulsens.trip.pay.MoneyMath;
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.util.Util;

/**
 * A trip's public page, every kind (docs/conference.md, "The trip page"): its look, seeding, the legacy
 * description page and its conversion, the embed's CSS, and the views its sections render (which trip a section is
 * about, the public agenda, the registration block). Everything here runs on the PUBLIC render path of an anonymous
 * visitor, so it reads only the cached DAO lookups (RenderPathCacheTest) and touches no session or view scope.
 * Results are memoized per request, because a fragment's EL evaluates the same value several times while it renders.
 */
@Slf4j
@Named("tripPage")
@ApplicationScoped
public class TripPageCommands {
    /** The programmatic property naming an explicit trip (blank means "this page's trip"). */
    public static final String PROP_TRIP_ID = "tripId";

    private final Supplier<Map<String, Object>> requestMap;
    private final Supplier<LocalDateTime> clock;
    private final Supplier<Boolean> orgFullWidth;

    public TripPageCommands() {
        this(RequestMemo::facesRequestMap, LocalDateTime::now, () -> new BrandCommands().isFullWidth());
    }

    /** Test seam: a request map (null for none) and the current time. */
    TripPageCommands(final Supplier<Map<String, Object>> requestMap, final Supplier<LocalDateTime> clock) {
        this(requestMap, clock, () -> new BrandCommands().isFullWidth());
    }

    /** Test seam adding the host organization's layout choice ({@code BrandCommands.isFullWidth}). */
    TripPageCommands(final Supplier<Map<String, Object>> requestMap, final Supplier<LocalDateTime> clock,
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
        final String explicit = Util.trimToNull(instance == null ? null : instance.getValues().get(PROP_TRIP_ID));
        if (explicit != null) {
            return explicit;
        }
        final String fromKey = instance == null ? null : OrgPageBootstrap.tripOf(instance.getSection());
        if (fromKey != null) {
            return fromKey;
        }
        return Util.trimToNull(hostTripId);
    }

    private static Trip reachableTrip(final String id) {
        try {
            return DAO.getInstance().getTrip(id, Cached.YES)
                    .filter(trip -> ListingScope.reachable(trip.getOrgId()))
                    .orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to read reachable trip " + id, ex);
            return null;
        }
    }

    // ------------------------------------------------------------------ the top block: pictures and facts

    /** The Trip Detail Pictures section's choice: blank for the gallery, {@value #PICTURES_COVER_ONLY} for one. */
    public static final String PROP_PICTURES_SHOW = "show";
    /** The {@value #PROP_PICTURES_SHOW} value that shows the cover alone, however many pictures the trip has. */
    public static final String PICTURES_COVER_ONLY = "cover";
    /** The Trip Details section's pick of facts: comma-separated {@link Fact#value}s; blank is the default line. */
    public static final String PROP_FACTS_SHOWN = "shown";

    /**
     * One fact the Trip Details section may show: its stored value, its label's message key, and whether the line
     * shows it when the section picks nothing (every fact but the dates, which the opening banner carries).
     */
    public record Fact(String value, String labelKey, boolean byDefault) {
    }

    /** Every fact, in the order the line shows them. */
    public static final List<Fact> FACTS = List.of(
            new Fact("dates", "common.dates", false),
            new Fact("destination", "tripedit.edit.destination", true),
            new Fact("director", "common.spiritualDirector", true),
            new Fact("guide", "trip.page.guide", true),
            new Fact("coordinators", "common.coordinators", true),
            new Fact("price", "trip.page.estimatedPrice", true),
            new Fact("flyer", "trip.page.flyer", true));

    /** Whether a Trip Detail Pictures section shows the cover alone (its choice), rather than the gallery. */
    public boolean coverOnly(final ContentInstance instance) {
        return instance != null && PICTURES_COVER_ONLY.equals(instance.getValues().get(PROP_PICTURES_SHOW));
    }

    /**
     * Whether a Trip Details section shows {@code fact} (a {@link Fact#value}): the section's pick when it made
     * one, else the default line. A null instance asks about the default line. Says nothing about whether the trip
     * HAS the fact ({@link #hasFacts} and the fragment ask that).
     */
    public boolean showsFact(final ContentInstance instance, final String fact) {
        final String picked = instance == null ? null : Util.trimToNull(instance.getValues().get(PROP_FACTS_SHOWN));
        if (picked == null) {
            return FACTS.stream().anyMatch(each -> each.value().equals(fact) && each.byDefault());
        }
        for (final String part : picked.split(",")) {
            if (part.trim().equals(fact)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a Trip Details section has anything to show for {@code trip}: some shown fact the trip has. */
    public boolean hasFacts(final Trip trip, final ContentInstance instance) {
        return trip != null && FACTS.stream()
                .anyMatch(fact -> showsFact(instance, fact.value()) && hasFact(trip, fact.value()));
    }

    /** Whether {@code trip} has a value for {@code fact} (a {@link Fact#value}). */
    public boolean hasFact(final Trip trip, final String fact) {
        if (trip == null || fact == null) {
            return false;
        }
        return switch (fact) {
            case "dates" -> trip.getStartDate() != null;
            case "destination" -> Util.trimToNull(trip.getDestination()) != null;
            case "director" -> Util.trimToNull(trip.getDirector()) != null;
            case "guide" -> Util.trimToNull(trip.getLocalGuide()) != null;
            case "coordinators" -> Util.trimToNull(trip.getFacilitators()) != null;
            case "price" -> Util.trimToNull(trip.getEstimatedPrice()) != null;
            case "flyer" -> !new TripCommands().flyerUrlOf(trip).isEmpty();
            default -> false;
        };
    }

    /** The trip's dates for the facts line, in the reader's language ("March 1, 2027 to March 9, 2027"). */
    public String factDates(final Trip trip) {
        if (trip == null || trip.getStartDate() == null) {
            return "";
        }
        return Formats.longDateSpan(trip.getStartDate(), trip.getEndDate(), RequestContext.currentLocale());
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
        // The unmarked page's language (pageLocaleOf), so the labels match the page's own text. A written language
        // version of the page still shows them in the unmarked page's language: a known gap.
        final Locale locale = OrgPageBootstrap.pageLocaleOf(trip);
        final DateTimeFormatter dayFormat = Formats.formatter("MMMMEEEEd", locale);
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
                Util.blankToNull(RichTextHtml.cleanEventNote(event.getNotes())));
    }

    // ------------------------------------------------------------------ the registration block

    /**
     * Where registration stands. The window cases follow {@code CheckoutValidator}'s three refusals, in its order
     * and at its edges: started, not opened yet, closed.
     */
    public enum Phase {
        /** The event has not started and registration is within its window: registration is open. */
        OPEN,
        /** Every place is taken. */
        FULL,
        /** The event is under way or over, or priced checkout's registration window has closed. */
        CLOSED,
        /**
         * Priced checkout's registration window has not opened yet (before {@code opensAt}); the trip has not
         * started. Its own state, not CLOSED: "Registration opens Monday, December 1" invites a visitor back,
         * where "closed" tells them they missed it (owner, 2026-11-17).
         */
        NOT_OPEN;

        /** The phase as the public wire spells it (Discover's {@code registration}). */
        public String wireName() {
            return this == NOT_OPEN ? "notOpen" : name().toLowerCase(Locale.ROOT);
        }
    }

    /** One priced question's offer: its label and its choices as "Full weekend: $150.00". */
    public record PriceGroup(String question, List<String> choices) {
    }

    /**
     * The registration block's facts. {@code started} is whether the trip has begun by the calendar-day rule of
     * every countdown ({@link TripCommands#notStartedBy}): the countdown's test, kept apart from {@code phase},
     * whose start edge is checkout's instant (a trip whose window has not opened, or has closed, still counts down
     * to its first day). {@code opensOn} is the day registration opens, set only for {@link Phase#NOT_OPEN}.
     * {@code taken} is the count of CONFIRMED and PENDING registrations, or for an external trip the count its
     * editor typed ({@link Trip#getShownRegCount}): its registrations live on another site, so this site has none
     * to count. {@code seatsLeft} is null when the trip has no limit. {@code prices} are the trip's priced
     * questions as configured; the person-specific price (age bands, codes) is checkout's to compute, never this
     * block's.
     */
    public record RegistrationInfo(Phase phase, boolean started, LocalDate opensOn, int taken, Integer limit,
            Integer seatsLeft, List<PriceGroup> prices, String fallbackPrice, String startDateIso,
            String registerUrl, String registerLabel) {

        /** The opening day as the registration block says it, "Monday, December 1"; null unless NOT_OPEN. */
        public String opensDay() {
            return opensOn == null ? null : Formats.format(opensOn, OPENS_DAY, RequestContext.currentLocale());
        }

        /** The opening day as a card chip says it, "Dec 1"; null unless NOT_OPEN. */
        public String opensDate() {
            return opensOn == null ? null : Formats.format(opensOn, OPENS_DATE, RequestContext.currentLocale());
        }
    }

    // In the reader's language, like the sentences around them; no year, as the owner wrote the copy. The
    // registration info is built once per request, so the day is formatted when it is read, not when it is built.
    private static final String OPENS_DAY = "MMMMEEEEd";
    private static final String OPENS_DATE = "MMMd";

    /** The registration block for a conference (or any trip), or null for none. */
    public RegistrationInfo registrationInfo(final Trip trip) {
        if (trip == null) {
            return null;
        }
        return memo("conferenceReg:" + trip.getId(), () -> buildInfo(trip));
    }

    private RegistrationInfo buildInfo(final Trip trip) {
        // An EXTERNAL trip's registrations live on another site, so this site counts none of them: its places
        // taken are the count its editor typed. Here, in the ONE phase rule, so a card and the page agree on Full.
        final int taken = trip.isExternal() ? trip.getShownRegCount() : (int) registrations(trip).stream()
                .filter(reg -> reg.getStatus() != Registration.Status.NOT_REGISTERED)
                .count();
        final Integer limit = trip.getRegLimit();
        final Integer seatsLeft = limit == null ? null : Math.max(0, limit - taken);
        final LocalDateTime now = clock.get();
        final boolean started = trip.getStartDate() != null && !TripCommands.notStartedBy(trip, now.toLocalDate());
        final Phase phase = phaseOf(trip, seatsLeft, now);
        final LocalDate opensOn = phase == Phase.NOT_OPEN ? trip.getCheckout().getOpensAt().toLocalDate() : null;
        return new RegistrationInfo(phase, started, opensOn, taken, limit, seatsLeft, priceGroups(trip),
                Util.blankToNull(trip.getEstimatedPrice()), trip.getStartDateIso(), registerUrl(trip),
                trip.getKind().getRegisterLabel());
    }

    /**
     * The phase at {@code now}, from checkout's own window rule ({@link CheckoutValidator#windowAt}), so the form and
     * the phase share their edges: the start is an instant (the countdown's calendar day is {@code started}'s
     * business). Priced checkout's window ({@code CheckoutConfig.opensAt}/{@code closesAt}) counts only while
     * checkout is on (the free form ignores a window left stored on a trip with checkout off) and never for an
     * external trip, whose registration happens on another site: nothing else enforces it. Without the window,
     * Discover, the trip cards and the page's block said "open" while the form refused.
     */
    private static Phase phaseOf(final Trip trip, final Integer seatsLeft, final LocalDateTime now) {
        final CheckoutValidator.Window window = CheckoutValidator.windowAt(trip, now);
        if (window == CheckoutValidator.Window.STARTED) {
            return Phase.CLOSED;
        }
        if (!trip.isExternal() && trip.getCheckout().isOn() && window != CheckoutValidator.Window.OPEN) {
            return window == CheckoutValidator.Window.NOT_OPEN ? Phase.NOT_OPEN : Phase.CLOSED;
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
                        question.getOfferedChoices().stream().map(TripPageCommands::priceLine).toList()));
            }
        }
        return groups;
    }

    private static String priceLine(final OptionChoice choice) {
        return choice.isPriced() ? choice.getLabel() + ": " + MoneyMath.formatCents(choice.getPriceCents())
                : Text.get("feedback.tripPageCommands.priceLineFree", choice.getLabel());
    }

    /** The registration page for a trip: an external trip registers on its provider's site. */
    public String registerUrl(final Trip trip) {
        return registerTarget(trip);
    }

    /** {@link #registerUrl} for Java callers. */
    static String registerTarget(final Trip trip) {
        if (trip == null) {
            return "";
        }
        if (!trip.isExternal()) {
            return JOIN_URL + trip.getId();
        }
        // Screened for a link: a stored value that is no web address links nowhere new.
        final String external = TripCommands.externalUrl(trip);
        return external.isEmpty() ? TripCommands.DETAIL_URL + trip.getId() : external;
    }

    /**
     * Our own registration page for a trip, followed by its id: what the starter sections store for their Register
     * buttons (OrgPageBootstrap) and what {@link #contentLink} recognizes, so the two cannot drift apart.
     */
    public static final String JOIN_URL = "/trip/joinTrip.jsf?trip=";

    /** Exactly a {@link #JOIN_URL} for one trip id, nothing more: a staff link with {@code &id=} is not one. */
    private static final Pattern JOIN_LINK = Pattern.compile(Pattern.quote(JOIN_URL)
            + "((?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    /**
     * A content section's link target as it renders: a stored link to a trip's own registration page becomes
     * {@link #registerUrl} of that trip, so an EXTERNAL trip's Register button leaves for its provider. The starter
     * sections store that link (the hero's and the closing banner's buttons, frozen when the page is seeded), and
     * an editor may type it too; resolving it at render rather than at seeding is what keeps it right when the
     * External Trip URL is added, changed or removed later. Only a trip this site may show is resolved
     * ({@link #reachableTrip}, the conference sections' own gate): another organization's trip must not put its
     * registration on this organization's pages. Every other link is returned unchanged. A cached read only: this
     * runs on the public render path (RenderPathCacheTest).
     */
    public static String contentLink(final String url) {
        return contentLink(url, id -> Optional.ofNullable(reachableTrip(id)));
    }

    /**
     * {@link #contentLink(String)} with the trip lookup supplied: the testable half. A lookup that fails keeps the
     * stored link: thrown, it would blank the whole section ({@code ContentCommands.render}), hero and all. The
     * production lookup ({@link #reachableTrip}) already absorbs and logs a DAO failure, so this catch guards
     * {@link #registerTarget} and any other lookup handed to this seam.
     */
    static String contentLink(final String url, final Function<String, Optional<Trip>> trips) {
        final Matcher join = url == null ? null : JOIN_LINK.matcher(url.trim());
        if (join == null || !join.matches()) {
            return url;
        }
        try {
            return trips.apply(join.group(1)).map(TripPageCommands::registerTarget).orElse(url);
        } catch (final RuntimeException ex) {
            log.error("Unable to resolve the Register link of trip " + join.group(1), ex);
            return url;
        }
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
     * The look of the trip page for {@code tripId}, any kind, or null when this host shows no such page (no such
     * trip, or one the site does not reach: the same rule as {@code SiteCommands.tripPageKey}). With no options
     * set it is the organization's look exactly. Cached reads only, memoized per request: the page's build-time
     * params (the layout, the stylesheet link) read it from the request parameter before the page's initPage runs,
     * and the render reads it again.
     */
    public PageLook lookFor(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return null;
        }
        final String id = tripId.trim();
        return memo("conferenceLook:" + id, () -> lookOf(reachableTrip(id)));
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
                options.isTitleShown(), options.isNavShown(), stylesheetLink(trip, options.getStylesheetUrl()));
    }

    /**
     * The stylesheet URL the page links: the stored (bare) URL plus the library item's cache buster ({@code ?r=N}
     * once the editor has rewritten it in place; docs/conference.md "Look and feel"), from the CACHED listing only:
     * this is public-page render code. Screened for the attribute like every linked URL.
     */
    private static String stylesheetLink(final Trip trip, final String stored) {
        final String safe = BrandCommands.safeLinkUrl(stored);
        if (safe == null) {
            return null;
        }
        final MediaItem item = new MediaCommands().cachedLibraryItemFor(trip.getOrgId(), safe);
        return item == null || item.getCacheBuster().isEmpty() ? safe : safe + item.getCacheBuster();
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
                problems.add(Text.get("feedback.tripPageCommands.stylesheetRule"));
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
            return Text.get("feedback.tripPageCommands.lookOrganization");
        }
        final List<String> parts = new ArrayList<>();
        if (options.getLayout() != null) {
            parts.add(KnownSettings.LAYOUT_FULL_WIDTH.equals(options.getLayout())
                    ? Text.get("feedback.tripPageCommands.lookFullWidth")
                    : Text.get("feedback.tripPageCommands.lookClassic"));
        }
        if (options.getBackgroundUrl() != null) {
            parts.add(Text.get("feedback.tripPageCommands.lookBackgroundImage"));
        } else if (options.getBackgroundColor() != null) {
            parts.add(Text.get("feedback.tripPageCommands.lookBackgroundColor", options.getBackgroundColor()));
        }
        if (options.isTitleShown()) {
            parts.add(Text.get("feedback.tripPageCommands.lookTitleShown"));
        }
        if (!options.isNavShown()) {
            parts.add(Text.get("feedback.tripPageCommands.lookLinksHidden"));
        }
        if (options.getStylesheetUrl() != null) {
            parts.add(Text.get("feedback.tripPageCommands.lookOwnStylesheet"));
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
        if (trip == null) {
            return false;
        }
        if (trip.getPageSeededAt() != null) {
            return true;
        }
        if (!OrgPageBootstrap.isTripId(trip.getId())) {
            // Imported or hand-made rows with readable ids (and the unit tests' fixtures): no key, no page.
            log.info("Page for trip {} not seeded: not a UUID id", trip.getId());
            return false;
        }
        final String missing = missingStarter(this::starterInstalled);
        if (missing != null) {
            log.warn("Page for trip {} not seeded: starter template '{}' is not installed", trip.getId(), missing);
            return false;
        }
        try {
            final LocalDateTime now = clock.get();
            final String key = OrgPageBootstrap.tripPageKey(trip.getId());
            final boolean withTop = topInstalled() && DAO.getInstance().getContentForSection(key, Cached.NO).isEmpty();
            final List<ContentInstance> rows = OrgPageBootstrap.tripRows(trip, orgNameOf(trip),
                    OrgCommands::currentTemplateVersion, now, withTop);
            if (!OrgCommands.seedIfEmpty(key, rows)) {
                log.error("Conference page for trip {} could not be saved", trip.getId());
                return false;
            }
            trip.setPageSeededAt(now);
            if (withTop) {
                trip.setPageTopSectionsAt(now);
            }
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

    /** {@link #installed}, as {@link #convertLegacyPage} asks it. Seam for tests. */
    protected boolean starterInstalled(final String templateId) {
        return installed(templateId);
    }

    /**
     * Whether both templates of the trip's top block are installed ({@code OrgPageBootstrap.tripTopTemplateIds}):
     * only then does a page seed with its pictures and facts as sections. Optional, unlike the starters: before the
     * installer has run, a page still seeds, and it keeps the fixed top block (docs/trip-pictures.md).
     */
    boolean topInstalled() {
        return OrgPageBootstrap.tripTopTemplateIds().stream().allMatch(this::starterInstalled);
    }

    private static String orgNameOf(final Trip trip) {
        return trip.getOrganization() == null ? "" : trip.getOrganization().getName();
    }

    // ------------------------------------------------------------------ legacy pages

    /**
     * Whether this trip still carries its details the old way (docs/conference.md, "Legacy trips"): saved before
     * every trip had a page, so never stamped, with a description to show. Such a trip keeps rendering that
     * description read-only until {@link #convertLegacyPage} turns it into a section.
     */
    public boolean hasLegacyDescription(final Trip trip) {
        return trip != null && trip.getPageSeededAt() == null && trip.getDescription() != null
                && !trip.getDescription().isBlank();
    }

    /**
     * Whether the details page for {@code tripId} renders the OLD page (the title, the description if any, the
     * registration card) rather than sections: a trip never stamped with a page AND no content row under its key
     * yet (a page someone authored by hand wins). Cached reads only, memoized per request: the details page asks
     * at build time and at render.
     */
    public boolean isLegacyPage(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return false;
        }
        final String id = tripId.trim();
        return memo("tripLegacy:" + id, () -> legacyPageOf(reachableTrip(id)));
    }

    private static boolean legacyPageOf(final Trip trip) {
        if (trip == null || trip.getPageSeededAt() != null || !OrgPageBootstrap.isTripId(trip.getId())) {
            return false;
        }
        try {
            return DAO.getInstance()
                    .getContentForSection(OrgPageBootstrap.tripPageKey(trip.getId()), Cached.YES).isEmpty();
        } catch (final RuntimeException ex) {
            log.error("Unable to read the page rows of trip " + trip.getId(), ex);
            return false;
        }
    }

    /**
     * The "Edit page" click on a legacy page: gives the trip its page and stamps it, so the page is edited there
     * from now on. A trip with a description gets THAT as the page's first section ({@code
     * OrgPageBootstrap.legacyRows}: the description verbatim as one text-only section, the closest thing to the old
     * page; the field itself is kept but never edited again); a trip without one gets its kind's starter sections
     * ({@code OrgPageBootstrap.tripRows}). Only the page's own editors ({@code ContentCommands.canEditPage}: the
     * trip's managers, its org's content admins, site admins); idempotent (a stamped trip answers true); refuses
     * while a starter it needs is not installed. Saves the trip through the DAO like {@link #ensurePage}: a
     * details-page button must not run the editor's kind, pricing and provider rules.
     *
     * @return whether the trip now has a page to edit
     */
    public boolean convertLegacyPage(final String tripId, final Person.Id userId) {
        if (tripId == null || tripId.isBlank() || userId == null) {
            return false;
        }
        if (!OrgPageBootstrap.isTripId(tripId.trim())) {
            PageFeedback.warn(Text.get("feedback.tripPageCommands.cannotCarryPage"));
            return false;
        }
        final Trip trip = DAO.getInstance().getTrip(tripId.trim(), Cached.NO).orElse(null);
        if (trip == null) {
            PageFeedback.warn(Text.get("feedback.shared.unknownTrip"));
            return false;
        }
        final String key = OrgPageBootstrap.tripPageKey(trip.getId());
        if (!new ContentCommands().canEditPage(key, userId)) {
            PageFeedback.warn(Text.get("feedback.tripPageCommands.onlyManagersEdit"));
            return false;
        }
        if (trip.getPageSeededAt() != null) {
            return true;
        }
        final boolean described = hasLegacyDescription(trip);
        final List<String> needed = described ? OrgPageBootstrap.legacyRowTemplateIds()
                : OrgPageBootstrap.tripRowTemplateIds();
        final String missing = needed.stream().filter(id -> !starterInstalled(id)).findFirst().orElse(null);
        if (missing != null) {
            PageFeedback.warn(Text.get("feedback.tripPageCommands.starterMissing", missing));
            return false;
        }
        try {
            final LocalDateTime now = clock.get();
            final boolean withTop = topInstalled() && DAO.getInstance().getContentForSection(key, Cached.NO).isEmpty();
            final List<ContentInstance> rows = described
                    ? legacyRowsWithTop(trip, withTop)
                    : OrgPageBootstrap.tripRows(trip, orgNameOf(trip), OrgCommands::currentTemplateVersion, now,
                            withTop);
            // A page someone authored by hand before the trip was stamped is kept as it is; only the stamp is new.
            OrgCommands.seedIfEmpty(key, rows);
            trip.setPageSeededAt(now);
            if (withTop) {
                // The old page showed the pictures and facts above the description; the converted one keeps them,
                // as its first two sections.
                trip.setPageTopSectionsAt(now);
            }
            if (!DAO.getInstance().saveTrip(trip)) {
                PageFeedback.warn(Text.get("feedback.tripPageCommands.createdButTripNotSaved"));
                return false;
            }
            log.info("Trip {} given its page from its {}", trip.getId(), described ? "description" : "starters");
            return true;
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to create the page of trip {}", trip.getId(), ex);
            PageFeedback.warn(Text.get("feedback.tripPageCommands.notCreated"));
            return false;
        }
    }

    /** A described legacy trip's one converted section, under the trip's top block when {@code withTop}. */
    private static List<ContentInstance> legacyRowsWithTop(final Trip trip, final boolean withTop) {
        final List<ContentInstance> rows = OrgPageBootstrap.legacyRows(trip, OrgCommands::currentTemplateVersion);
        return withTop ? OrgPageBootstrap.withTopRows(trip, rows, OrgCommands::currentTemplateVersion) : rows;
    }

    // ------------------------------------------------------------------ copying another trip's page

    /** One trip the Trip Page's "Copy from another trip" menu offers. Render-time only, never in a view. */
    public record CloneSource(String id, String label) {
    }

    /**
     * How many recent trips the copy menu looks through, and the most it lists. Generous on purpose: an organization
     * copies last year's page, the menu has a filter box, and the list is read only once its dialog is opened.
     */
    private static final int CLONE_WINDOW = 1000;
    private static final int CLONE_MAX = 200;

    /**
     * The trips whose page {@code target}'s page may be copied from: the SAME organization only (the tenancy
     * boundary: a trip with no organization offers nothing), never the trip itself, a trip whose page has sections
     * (a legacy or empty page has nothing to copy), and one whose page the signed-in person may edit, since a copy
     * carries the page's hidden rows too. Newest first. Cached reads, memoized per request: the menu asks at render
     * and again at decode.
     */
    public List<CloneSource> cloneSources(final Trip target, final Person.Id userId) {
        if (target == null || target.getOrgId() == null || userId == null) {
            return List.of();
        }
        return memo("cloneSources:" + target.getId() + ":" + userId.getValue(),
                () -> findCloneSources(target, userId));
    }

    private static List<CloneSource> findCloneSources(final Trip target, final Person.Id userId) {
        final ContentCommands content = new ContentCommands();
        final List<CloneSource> found = new ArrayList<>();
        try {
            for (final Trip trip : DAO.getInstance().getRecentTrips(CLONE_WINDOW, Cached.YES)) {
                if (found.size() >= CLONE_MAX) {
                    break;
                }
                if (mayCopyFrom(trip, target, userId, content)) {
                    found.add(new CloneSource(trip.getId(), trip.getTitle()));
                }
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to list the pages trip " + target.getId() + " may copy", ex);
        }
        return List.copyOf(found);
    }

    /** {@link #cloneSources}' rule for one trip, and the copy's own re-check of the trip it was handed. */
    private static boolean mayCopyFrom(final Trip source, final Trip target, final Person.Id userId,
            final ContentCommands content) {
        if (source == null || target.getOrgId() == null || !target.getOrgId().equals(source.getOrgId())
                || source.getId().equals(target.getId()) || !OrgPageBootstrap.isTripId(source.getId())) {
            return false;
        }
        final String key = OrgPageBootstrap.tripPageKey(source.getId());
        return !DAO.getInstance().getContentForSection(key, Cached.YES).isEmpty() && content.canEdit(key, userId);
    }

    /**
     * The Trip Page's "Copy from another trip" (docs/conference.md, "Copying a page"): {@code draft}'s page becomes
     * a copy of {@code sourceId}'s, every language version of it, groups with their items. Both pages must be the
     * signed-in person's to edit ({@code ContentCommands.canEdit}) and both trips must belong to the same
     * organization, re-checked here whatever the menu offered.
     *
     * <p>COPY FIRST, then delete: the page's current rows are noted, the copies are saved, and only when every copy
     * was saved are the noted rows deleted (each with its own audit line, {@code ContentCommands
     * .deleteWithChildren}). A copy that fails part way deletes what it added and leaves the page as it was.
     * Deleting is permanent; the editor's confirm says so.
     *
     * <p>What names the other trip is rewritten for this one ({@link #rewriteForCopy}). The trip is stamped as
     * seeded, and as showing its top block in sections exactly when the source did, on the stored row AND on
     * {@code draft} (the editor's working copy, whose Save would otherwise put back the old stamps). With
     * {@code withLook}, the source page's look (layout, background, title, links, stylesheet) is copied into
     * {@code draft} only: the editor's Save keeps it, its Cancel drops it.
     *
     * @return whether the page is now the copy
     */
    public boolean clonePage(final Trip draft, final String sourceId, final boolean withLook,
            final Person.Id userId) {
        final Optional<String> refusal = cloneRefusal(draft, sourceId, userId);
        if (refusal.isPresent()) {
            PageFeedback.warn(refusal.get());
            PageFeedback.callbackParam("cloneOk", false);
            return false;
        }
        final Trip source = DAO.getInstance().getTrip(sourceId.trim(), Cached.NO).orElseThrow();
        final Trip stored = DAO.getInstance().getTrip(draft.getId(), Cached.NO).orElseThrow();
        final Caller caller = Caller.current();
        final LocalDateTime now = clock.get();
        final String targetBase = OrgPageBootstrap.tripPageKey(stored.getId());
        final List<String> old = rowIdsOfEveryVersion(targetBase);
        final List<String> added = new ArrayList<>();
        if (!copiedEveryVersion(caller, source, stored, added)) {
            added.forEach(id -> deleteQuietly(caller, id));
            PageFeedback.warn(Text.get("feedback.tripPageCommands.cloneFailed"));
            PageFeedback.callbackParam("cloneOk", false);
            return false;
        }
        boolean allGone = true;
        for (final String id : old) {
            allGone &= deleteQuietly(caller, id);
        }
        stored.setPageSeededAt(stored.getPageSeededAt() == null ? now : stored.getPageSeededAt());
        stored.setPageTopSectionsAt(source.getPageTopSectionsAt() == null ? null : now);
        draft.setPageSeededAt(stored.getPageSeededAt());
        draft.setPageTopSectionsAt(stored.getPageTopSectionsAt());
        if (withLook) {
            draft.setPage(copyOf(source.pageOrNull()));
        }
        saveStamps(stored, source);
        PageFeedback.info(Text.get(allGone ? "feedback.tripPageCommands.cloned"
                : "feedback.tripPageCommands.clonedOldLeft", source.getTitle()));
        PageFeedback.callbackParam("cloneOk", true);
        return true;
    }

    /** {@link #copyEveryVersion}, with a failure of the store counted as a failed copy (logged), never thrown. */
    private boolean copiedEveryVersion(final Caller caller, final Trip source, final Trip target,
            final List<String> added) {
        try {
            return copyEveryVersion(caller, source, target, added);
        } catch (final RuntimeException ex) {
            log.error("Unable to copy the page of trip " + source.getId() + " to trip " + target.getId(), ex);
            return false;
        }
    }

    /**
     * Saves a copied page's trip with its new stamps. The page is already the copy by then, so a failure is logged
     * rather than reported as a failed copy (the editor's Save still writes {@code pageSeededAt} from its working
     * copy; a lost top-block stamp only means the fixed block is drawn above the copied sections).
     */
    private static void saveStamps(final Trip stored, final Trip source) {
        try {
            if (!DAO.getInstance().saveTrip(stored)) {
                log.error("Page of trip {} copied from {}, but its stamps were not saved", stored.getId(),
                        source.getId());
            }
        } catch (final RuntimeException | IOException ex) {
            log.error("Page of trip " + stored.getId() + " copied, but its stamps were not saved", ex);
        }
    }

    /** Why {@link #clonePage} refuses, in the editor's words; empty when the copy may go ahead. */
    private Optional<String> cloneRefusal(final Trip draft, final String sourceId, final Person.Id userId) {
        if (draft == null || userId == null || sourceId == null || sourceId.isBlank()) {
            return Optional.of(Text.get("feedback.tripPageCommands.clonePickTrip"));
        }
        if (!OrgPageBootstrap.isTripId(draft.getId())) {
            return Optional.of(Text.get("feedback.tripPageCommands.cannotCarryPage"));
        }
        final Trip stored = DAO.getInstance().getTrip(draft.getId(), Cached.NO).orElse(null);
        final Trip source = DAO.getInstance().getTrip(sourceId.trim(), Cached.NO).orElse(null);
        if (stored == null || source == null) {
            return Optional.of(Text.get("feedback.shared.unknownTrip"));
        }
        final ContentCommands content = new ContentCommands();
        if (!content.canEdit(OrgPageBootstrap.tripPageKey(stored.getId()), userId)
                || !mayCopyFrom(source, stored, userId, content)) {
            return Optional.of(Text.get("feedback.tripPageCommands.cloneRefused"));
        }
        return Optional.empty();
    }

    /** The top-level row ids of a trip page and of each of its language versions. */
    private static List<String> rowIdsOfEveryVersion(final String baseKey) {
        final List<String> ids = new ArrayList<>();
        for (final String key : versionKeys(baseKey)) {
            DAO.getInstance().getContentForSection(key, Cached.NO).forEach(row -> ids.add(row.getId()));
        }
        return ids;
    }

    /** A page's own key, then the key of each language version, whether or not it holds rows. */
    private static List<String> versionKeys(final String baseKey) {
        final List<String> keys = new ArrayList<>();
        keys.add(baseKey);
        for (final Language language : Language.values()) {
            keys.add(PageVersions.keyFor(baseKey, language));
        }
        return keys;
    }

    /**
     * Copies every version of {@code source}'s page into the same version of {@code target}'s, noting each new
     * top-level row in {@code added}. Ids are minted for the whole tree first, so an anchor or a group's child
     * section can be rewritten to the copy's own row whatever order the rows are saved in.
     */
    private boolean copyEveryVersion(final Caller caller, final Trip source, final Trip target,
            final List<String> added) {
        final String sourceBase = OrgPageBootstrap.tripPageKey(source.getId());
        final String targetBase = OrgPageBootstrap.tripPageKey(target.getId());
        final List<String> sourceKeys = versionKeys(sourceBase);
        final List<String> targetKeys = versionKeys(targetBase);
        final Map<String, List<ContentInstance>> rowsByKey = new LinkedHashMap<>();
        final Map<String, String> ids = new LinkedHashMap<>();
        for (final String key : sourceKeys) {
            final List<ContentInstance> rows = DAO.getInstance().getContentForSection(key, Cached.NO);
            rowsByKey.put(key, rows);
            mintIds(rows, ids);
        }
        final int retain = ContentCommands.retainCount(new ConfigCommands());
        for (int i = 0; i < sourceKeys.size(); i++) {
            for (final ContentInstance row : rowsByKey.get(sourceKeys.get(i))) {
                // Noted before it is saved: a row whose children then fail is still taken back out, with them.
                added.add(ids.get(row.getId()));
                if (!copyTree(caller, row, targetKeys.get(i), ids, source, target, retain)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A new id for every row of {@code rows} and, beneath each, of its children, recorded old to new. */
    private static void mintIds(final List<ContentInstance> rows, final Map<String, String> ids) {
        for (final ContentInstance row : rows) {
            ids.put(row.getId(), UUID.randomUUID().toString());
            mintIds(DAO.getInstance().getContentForSection(row.getId(), Cached.NO), ids);
        }
    }

    /** One row into {@code section} under its minted id, then its children beneath the copy. */
    private boolean copyTree(final Caller caller, final ContentInstance row, final String section,
            final Map<String, String> ids, final Trip source, final Trip target, final int retain) {
        final String copyId = ids.get(row.getId());
        if (copyId == null) {
            // A row that appeared since the ids were minted is left out rather than saved under a random id.
            return true;
        }
        // A seeded placeholder keeps the seed's author, so its first save publishes it exactly like the original's.
        final String author = OrgPageBootstrap.isSeededPlaceholder(row) ? OrgPageBootstrap.SEED_AUTHOR
                : caller.auditActor().email();
        final ContentInstance copy = new ContentInstance(copyId, section, row.getTitle(), row.getTemplateId(),
                row.getTemplateVersion(), rewriteForCopy(row, ids, source, target), row.getEventDate(),
                row.getPosition(), 0, null, author,
                row.getEditorPrivileges() == null ? null : new ArrayList<>(row.getEditorPrivileges()),
                row.getAllowedChildTemplateIds() == null ? null : new ArrayList<>(row.getAllowedChildTemplateIds()),
                row.isStayOnLatest());
        if (!saveCopy(copy, retain)) {
            log.error("Unable to copy row {} of trip {}'s page", row.getId(), source.getId());
            return false;
        }
        auditCopy(caller, copy.getId(), "Copied '" + copy.getTitle() + "' into " + section + " from the page of trip "
                + source.getId());
        for (final ContentInstance child : DAO.getInstance().getContentForSection(row.getId(), Cached.NO)) {
            if (!copyTree(caller, child, copyId, ids, source, target, retain)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A copied row's values made about the trip it is copied to: a link naming the source trip by {@code ?trip=}
     * (its registration page, its own page) names the target; an anchor to a row of the source page ({@code #id})
     * names that row's copy; a trip section's explicit pick of the source trip becomes blank ("this page"); and the
     * opening banner's headline and date line, where they still read exactly as the source trip's were seeded
     * ({@code OrgPageBootstrap}), read as the target's. Everything else is copied as it is.
     */
    static Map<String, String> rewriteForCopy(final ContentInstance row, final Map<String, String> ids,
            final Trip source, final Trip target) {
        final Map<String, String> values = new HashMap<>();
        for (final Map.Entry<String, String> entry : row.getValues().entrySet()) {
            values.put(entry.getKey(), rewriteValue(entry.getValue(), ids, source, target));
        }
        if (source.getId().equals(values.get(PROP_TRIP_ID))) {
            values.put(PROP_TRIP_ID, "");
        }
        if (StarterTemplates.BAND_HERO_ID.equals(row.getTemplateId())) {
            replaceIfSeeded(values, "headline", source.getShortTitle(), target.getShortTitle());
            replaceIfSeeded(values, "subheadline", OrgPageBootstrap.dateRange(source),
                    OrgPageBootstrap.dateRange(target));
        }
        return values;
    }

    private static String rewriteValue(final String value, final Map<String, String> ids, final Trip source,
            final Trip target) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String out = value.replace("?trip=" + source.getId(), "?trip=" + target.getId());
        for (final Map.Entry<String, String> id : ids.entrySet()) {
            out = out.replace("#" + id.getKey(), "#" + id.getValue());
        }
        return out;
    }

    private static void replaceIfSeeded(final Map<String, String> values, final String name, final String seeded,
            final String replacement) {
        final String now = values.get(name);
        if (now != null && !now.isEmpty() && now.equals(seeded)) {
            values.put(name, replacement == null ? "" : replacement);
        }
    }

    /**
     * Stores one copied row. Seam for tests: the store refuses no well-formed new row, so a copy that fails part way
     * (and must take back what it added) can only be shown by a store that says no.
     */
    protected boolean saveCopy(final ContentInstance copy, final int retain) {
        return DAO.getInstance().saveContent(copy, retain);
    }

    /** A row and its children, deleted with an audit line each; false when it could not be (logged). */
    private static boolean deleteQuietly(final Caller caller, final String id) {
        try {
            final ContentInstance row = DAO.getInstance().getContent(id, Cached.NO).orElse(null);
            return row == null || ContentCommands.deleteWithChildren(caller, row);
        } catch (final RuntimeException ex) {
            log.error("Unable to delete row " + id + " while copying a trip page", ex);
            return false;
        }
    }

    private static void auditCopy(final Caller caller, final String id, final String message) {
        Audit.builder(AuditAction.CONTENT, AuditOutcome.SUCCESS)
                .actor(caller.auditActor())
                .target(AuditEventBuilder.TARGET_CONTENT, id)
                .message(message)
                .log();
    }

    /** An independent copy of a page's look, or null for none (the organization's). */
    private static TripPageOptions copyOf(final TripPageOptions look) {
        if (look == null) {
            return null;
        }
        return new TripPageOptions(look.getLayout(), look.getBackgroundUrl(), look.getBackgroundColor(),
                look.getShowTitle(), look.getShowNav(), look.getStylesheetUrl());
    }

    // ------------------------------------------------------------------ the embed render (the apps)

    /** Theme {@code :root} blocks and the two site stylesheets, read once per name; the embed inlines them. */
    private static final ConcurrentHashMap<String, String> EMBED_CSS_CACHE = new ConcurrentHashMap<>();
    /** The trip stylesheets the embed inlined, by key and rev: an S3 read once per version, never per render. */
    private static final ConcurrentHashMap<String, String> EMBED_SHEET_CACHE = new ConcurrentHashMap<>();
    private static final int EMBED_SHEET_CACHE_MAX = 64;

    /**
     * Everything the embedded trip page ({@code trip/tripPage.xhtml}, what the apps fetch and cache) needs in ONE
     * inline stylesheet, so a copy saved on a phone renders with its styles offline: the current theme's variables
     * (its {@code :root} block alone; the rest of the theme styles widgets the embed has none of), {@code site.css}
     * and {@code albums.css}, the trip's own stylesheet when it has one, and the embed's overrides
     * ({@link EmbedStyles#overrides}). Memoized per request; the pieces are cached statically.
     */
    public String embedCss(final String tripId) {
        return memo("embedCss:" + tripId, () -> embedCssOf(tripId));
    }

    /**
     * The trip's stylesheet URL for a live {@code <link>}, only when {@link #embedCss} could NOT inline it (no
     * bucket to read from, or a read failure); null otherwise.
     */
    public String embedStylesheetLink(final String tripId) {
        final PageLook look = lookFor(tripId);
        if (look == null || look.stylesheetUrl() == null) {
            return null;
        }
        return tripStylesheet(tripId, look) == null ? look.stylesheetUrl() : null;
    }

    private String embedCssOf(final String tripId) {
        final StringBuilder css = new StringBuilder();
        css.append(themeRoot()).append('\n');
        final BrandCommands brand = new BrandCommands();
        // An org's own palette: the custom theme's :root reads these (docs/org-admin.md, "Custom palettes"). Then the
        // TRIP's org's brand colors, whatever host the app asked on: event notes colored in an editor read them.
        // getPaletteVars is the palette alone (not getOrgColorVars), so each brand color is written once.
        final String orgVars = brand.getPaletteVars() + BrandColors.styleOf(tripOrg(tripId), brand.isDark());
        if (!orgVars.isEmpty()) {
            css.append(":root{").append(orgVars).append("}\n");
        }
        css.append(siteResource("/resources/css/site.css")).append('\n');
        css.append(siteResource("/resources/css/albums.css")).append('\n');
        final PageLook look = lookFor(tripId);
        final String own = look == null ? null : tripStylesheet(tripId, look);
        if (own != null) {
            css.append(own).append('\n');
        }
        css.append(EmbedStyles.overrides());
        return css.toString();
    }

    /** The organization of a trip this host reaches, or null (cached reads). */
    private static Organization tripOrg(final String tripId) {
        final Trip trip = tripId == null || tripId.isBlank() ? null : reachableTrip(tripId.trim());
        return trip == null || trip.getOrgId() == null ? null
                : DAO.getInstance().getOrganization(Organization.Id.from(trip.getOrgId()), Cached.YES).orElse(null);
    }

    /** The media bean the embed reads a trip's stylesheet through. Seam for tests. */
    protected MediaCommands media() {
        return new MediaCommands();
    }

    private static String themeRoot() {
        // The theme name needs the request's site; off-request BrandCommands answers the platform default.
        return EMBED_CSS_CACHE.computeIfAbsent("theme:" + new BrandCommands().getTheme(),
                TripPageCommands::loadThemeRoot);
    }

    private static String loadThemeRoot(final String cacheKey) {
        // The ONE theme jar reader, shared with the palette cards (review finding kenpaulsen/medjugorje#132).
        return EmbedStyles.rootBlockOf(ThemeResources.themeCss(cacheKey.substring("theme:".length())));
    }

    /** A webapp stylesheet's text (site.css, albums.css), read once; empty off-request or when missing. */
    static String siteResource(final String path) {
        return EMBED_CSS_CACHE.computeIfAbsent("resource:" + path, TripPageCommands::loadSiteResource);
    }

    private static String loadSiteResource(final String cacheKey) {
        final String path = cacheKey.substring("resource:".length());
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return ctx == null ? "" : readAll(ctx.getExternalContext().getResourceAsStream(path), path);
    }

    /** A stylesheet's text from a stream that may be null (missing), closed afterwards; empty on any failure. */
    private static String readAll(final InputStream stream, final String path) {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            log.warn("Unable to read the stylesheet " + path, ex);
            return "";
        }
    }

    /** The trip's own stylesheet text, or null when it has none or it could not be read (then link it live). */
    private String tripStylesheet(final String tripId, final PageLook look) {
        if (look.stylesheetUrl() == null) {
            return null;
        }
        final Trip trip = reachableTrip(tripId == null ? "" : tripId.trim());
        final MediaCommands media = media();
        final MediaItem item = trip == null ? null
                : media.cachedLibraryItemFor(trip.getOrgId(), look.stylesheetUrl());
        if (item == null || !MediaCommands.isStylesheet(item)) {
            return null;
        }
        final String cacheKey = item.getS3Key() + "|" + item.getRev();
        final String cached = EMBED_SHEET_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        final String text = media.getObject(item.getS3Key())
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                // A sheet that would close the inline style block is linked, never inlined.
                .filter(css -> !TripStylesheets.closesStyleBlock(css)).orElse(null);
        if (text != null) {
            if (EMBED_SHEET_CACHE.size() >= EMBED_SHEET_CACHE_MAX) {
                EMBED_SHEET_CACHE.clear();
            }
            EMBED_SHEET_CACHE.put(cacheKey, text);
        }
        return text;
    }

    // ------------------------------------------------------------------ helpers

    private <T> T memo(final String key, final Supplier<T> compute) {
        return RequestMemo.get(requestMap.get(), key, compute);
    }

    /**
     * A trip's description as its pages write it: through the allowlist ({@link PageHtml}, medjugorje#158), so
     * a row stored before the cleaner existed, or written past it, is still clean on the page. "" for none.
     */
    public String descriptionHtml(final Trip trip) {
        return trip == null ? "" : PageHtml.clean(trip.getDescription());
    }
}
