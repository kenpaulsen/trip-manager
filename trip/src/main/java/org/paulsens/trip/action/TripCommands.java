package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;

import static java.time.temporal.ChronoUnit.DAYS;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.site.SiteContext;

@Slf4j
@Named("trip")
@ApplicationScoped
public class TripCommands {
    private static final long TIMEOUT = 5_000L;
    /** Cap for the admin/joinable trip-resolution fallbacks (a user's own trips come from the reverse index). */
    private static final int RECENT_TRIP_LIMIT = 100;
    /** How long a finished pilgrimage stays on the public landing page (user-set product rule). */
    private static final int PUBLIC_PAST_DAYS = 7;

    @Inject
    private BindingCommands bind;

    private final Supplier<OrgCommands> orgSource;

    public TripCommands() {
        this(() -> org.paulsens.trip.api.Beans.get(OrgCommands.class));
    }

    /** Test seam (the {@link TripDeleteCommands} pattern): {@code Beans.get} needs a CDI container. */
    TripCommands(final Supplier<OrgCommands> orgSource) {
        this.orgSource = orgSource;
    }

    public Trip createTrip() {
        return Trip.builder().build();
    }

    /**
     * A new (unsaved) trip belonging to the given org, or null when the caller may not create one there
     * ({@code OrgCommands.canCreateTripFor}: org admin or {@code addTrip@org}). This is the page-side gate for
     * trip creation -- the draft registry only ever receives a trip minted here, and draft tokens are
     * owner-bound, so Save cannot persist a trip an unauthorized user conjured. REST enforces separately.
     */
    public Trip createTripFor(final String orgId) {
        if (!orgSource.get().canCreateTripFor(orgId)) {
            return null;
        }
        final Trip trip = Trip.builder().build();
        trip.setOrgId(orgId.trim());
        // "Show on homepage?" starts OFF: a half-written trip must never appear on the public landing
        // page because its creator missed a toggle (user decision 2026-08-24).
        trip.setOpenToPublic(false);
        return trip;
    }

    /**
     * The org's trips, newest first, for its Trips page. View-gated like the page itself. The raw fetch is
     * over-sized because the org filter runs after the recency cap -- one busy tenant must not push another
     * tenant's trips out of their own list. Legacy trips with no orgId never appear here (expected).
     */
    public List<Trip> getTripsForOrg(final String orgId, final int limit) {
        if (orgId == null || orgId.isBlank() || !orgSource.get().canViewOrgTrips(orgId)) {
            return Collections.emptyList();
        }
        // Deliberately NOT site-gated: the org's own trips page is keyed and gated by the org, and a site
        // admin opens it from the shared host's Organizations page; the page links each trip to the host
        // that reaches it (SiteCommands.hostFor) rather than this list going empty there.
        return recentTripsAnywhere(limit * 4).stream()
                .filter(trip -> orgId.equals(trip.getOrgId()))
                .limit(limit)
                .toList();
    }

    /** Trip count for the org hub's card. Same gate as {@link #getTripsForOrg}. */
    public int getTripCountForOrg(final String orgId) {
        return getTripsForOrg(orgId, RECENT_TRIP_LIMIT).size();
    }

    /**
     * The trip-event types, for the event editor's Type menu.
     *
     * <p>Exposed here rather than read off a {@code TripEvent} instance because the list is a property of the
     * enum, not of any event. Reaching it through an event forces the menu to be populated from whatever event is
     * being edited, and anything that resolves the event at view-build time gets a null on the first open -- the
     * component tree is built during RESTORE_VIEW, before the action that selects the event has run. Binding the
     * menu straight to this accessor keeps it correct whenever it renders.</p>
     */
    public List<TripEvent.Type> getTripEventTypes() {
        return List.of(TripEvent.Type.values());
    }

    public boolean saveTrip(final Trip trip) {
        boolean result;
        // Read the roster BEFORE the write, so anyone dropped in this edit can be taken off the chat too. Done
        // here rather than at each Remove button because there are several ways off a trip and they must all
        // agree: a chat still listing people who are no longer on the trip is the wrong answer everywhere.
        final List<Person.Id> removed = peopleRemovedBy(trip);
        syncProviderFromOrg(trip);
        try {
            result = DAO.getInstance().saveTrip(sortTripPeople(trip));
        } catch (final RuntimeException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Error saving '" + trip.getId()
                    + "': " + trip.getTitle(), ex.getMessage());
            log.error("Error while saving trip: ", ex);
            result = false;
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Unable to save '" + trip.getId() + "': "
                    + trip.getTitle(), ex.getMessage());
            log.warn("Error while saving trip: ", ex);
            result = false;
        }
        if (result) {
            ChatCommands.getChatCommands().leaveOnTripRemoval(trip.getId(), removed);
        }
        return result;
    }

    /**
     * Keeps {@code Trip.provider} -- the display string the public renderers read -- synced from the owning
     * {@link org.paulsens.trip.model.Organization}'s name. Runs on EVERY save path (page and REST) so the two
     * can never drift while both exist; a trip with no org (legacy) keeps whatever provider string it has.
     * NB {@code Trip.isCfpw()} deliberately does NOT read this string: recognition keys on the org's short
     * name, because this sync writes the FULL name and production CFPW is "Center for Peace West".
     */
    private void syncProviderFromOrg(final Trip trip) {
        if (trip.getOrgId() == null || trip.getOrgId().isBlank()) {
            return;
        }
        final Organization owner = DAO.getInstance()
                .getOrganization(Organization.Id.from(trip.getOrgId()), Cached.YES)
                .orElse(null);
        if (owner == null) {
            log.warn("Trip {} points at unknown organization {}; provider left as-is",
                    trip.getId(), trip.getOrgId());
            return;
        }
        trip.setProvider(owner.getName());
    }

    /**
     * Who is on the stored trip but not on the one about to be saved.
     *
     * <p>Best effort by design: if the previous version cannot be read, nobody is reported and the chat roster is
     * left alone. Failing to tidy the chat must never fail the trip save that prompted it.
     */
    private List<Person.Id> peopleRemovedBy(final Trip updated) {
        if (updated == null || updated.getId() == null) {
            return List.of();
        }
        // Advisory pre-read: the save it precedes must survive a failure here.
        final Trip stored = advisoryGetTrip(updated.getId());
        if (stored == null) {
            return List.of();
        }
        final List<Person.Id> keeping = updated.getPeople();
        return stored.getPeople().stream().filter(id -> id != null && !keeping.contains(id)).toList();
    }

    /**
     * Sets exactly which of a trip's events a person takes part in, and saves the trip once.
     *
     * <p>Exists because the page could not safely do this itself. The itinerary's event picker passes its values
     * through {@code TripEventConverter}, which loads from the DAO -- and since the persistence redesign a DAO
     * read deserializes a <b>new</b> object rather than handing back a shared one. The page mutated that detached
     * copy and then saved the trip, which serializes the instances the trip holds, so the edit was written
     * nowhere. It looked like it worked, because the table it updated was bound to the selection rather than to
     * anything stored. Resolving each event by id inside the trip is what makes the change part of the save.
     *
     * <p>Equality is no defence here: {@code TripEvent} is a {@code @Data} class, so the detached copy is
     * {@code equals} to the trip's own instance and every {@code contains} check still behaved. Only identity
     * differs, and only identity matters at save time.
     *
     * @param trip      the trip to modify and save.
     * @param selected  the events the person should be in; any not listed they are removed from.
     * @param personId  whose participation this is.
     * @return true when saved, or when there was nothing to change.
     */
    public boolean setEventParticipation(
            final Trip trip, final Collection<TripEvent> selected, final Person.Id personId) {
        if (trip == null || personId == null) {
            return false;
        }
        final Set<String> wanted = (selected == null) ? Set.of() : selected.stream()
                .filter(Objects::nonNull)
                .map(TripEvent::getId)
                .collect(Collectors.toSet());
        boolean changed = false;
        // Iterate the TRIP's events, never the submitted ones -- these are the objects saveTrip will serialize.
        for (final TripEvent event : trip.getTripEvents()) {
            changed |= setParticipation(event, personId, wanted.contains(event.getId()));
        }
        // Saving unconditionally would write every event on the trip on any stray ajax event, which is both
        // wasteful and a needless way to lose a concurrent edit.
        return !changed || saveTrip(trip);
    }

    /**
     * Sets one person's private note on a trip event and saves the trip.
     *
     * <p>Same trap as {@link #setEventParticipation}, reached a different way. The itinerary's event table is
     * bound to {@code viewScope.userEvents}, which is <b>also</b> the event picker's value -- so the moment
     * someone uses the picker, JSF replaces that list with converter output, and every row in the table is then a
     * detached copy. A note typed after that was written onto an object the following {@code saveTrip} does not
     * serialize. On a freshly loaded page the same edit works, because the list still holds the trip's own
     * instances, which is what makes it look intermittent rather than broken.
     *
     * <p>Resolving by id inside the trip makes the note land on the object that gets written, whatever the row
     * happened to be bound to.
     *
     * @return true when saved; false when the event is not part of this trip.
     */
    public boolean saveEventNote(
            final Trip trip, final TripEvent event, final Person.Id personId, final String note) {
        if (trip == null || event == null || personId == null) {
            return false;
        }
        final TripEvent owned = trip.getTripEvent(event.getId());
        if (owned == null) {
            log.warn("Refusing to note event {}: not part of trip {}", event.getId(), trip.getId());
            return false;
        }
        owned.getPrivNotes().put(personId, (note == null) ? "" : note);
        return saveTrip(trip);
    }

    /** @return whether this event's participant list actually changed. */
    private boolean setParticipation(final TripEvent event, final Person.Id personId, final boolean participating) {
        final List<Person.Id> current = event.getParticipants();
        if (current.contains(personId) == participating) {
            return false;
        }
        final List<Person.Id> updated = new ArrayList<>(current);
        if (participating) {
            updated.add(personId);
        } else {
            updated.remove(personId);
        }
        event.setParticipants(updated);
        return true;
    }

    public Trip sortTripPeople(final Trip trip) {
        final List<Person.Id> sortedIdList = trip.getPeople().stream()
                        .map(id -> DAO.getInstance().getPerson(id, Cached.NO))
                        .map(opt -> opt.orElse(null))
                        .filter(Objects::nonNull)
                        .sorted()
                        .map(Person::getId)
                        .toList();
        trip.setPeople(new ArrayList<>(sortedIdList));
        return trip;
    }

    /** The active trips this site reaches ({@link #reachesHere}). */
    public List<Trip> getActiveTrips(final int pastDaysToCountAsActive) {
        return here(activeTripsAnywhere(pastDaysToCountAsActive));
    }

    /** Every active trip regardless of site: the raw index the site-scoped listings narrow themselves. */
    private List<Trip> activeTripsAnywhere(final int pastDaysToCountAsActive) {
        try {
            return DAO.getInstance().getActiveTrips(LocalDateTime.now().minus(pastDaysToCountAsActive, DAYS),
                    Cached.YES);
        } catch (final RuntimeException ex) {
            log.error("Failed to get active trips!", ex);
            return Collections.emptyList();
        }
    }

    /**
     * The active trips the site's Trips MENU may list for this viewer -- {@link #getActiveTrips(int)} narrowed
     * by {@link #listsInMenu}. The menu itself still applies its own public/member/admin guard; this is the
     * tenant boundary underneath it, so an org host's menu never names another tenant's trip.
     */
    public List<Trip> getMenuTrips(final int pastDaysToCountAsActive, final Person.Id userId,
            final boolean showAll) {
        return getMenuTrips(pastDaysToCountAsActive, userId, showAll, null);
    }

    /** {@link #getMenuTrips(int, Person.Id, boolean)} narrowed by the site admin's org selector. */
    public List<Trip> getMenuTrips(final int pastDaysToCountAsActive, final Person.Id userId,
            final boolean showAll, final String selectedOrgId) {
        return activeTripsAnywhere(pastDaysToCountAsActive).stream()
                .filter(trip -> listsInMenu(trip, userId, showAll, selectedOrgId))
                .toList();
    }

    /** The past trips the Trips menu may list for this viewer -- the whole-system list, site-narrowed. */
    public List<Trip> getMenuOldTrips(final Person.Id userId, final boolean showAll,
            final int pastDaysStillActive, final int limit) {
        return getMenuOldTrips(userId, showAll, pastDaysStillActive, limit, null);
    }

    /** {@link #getMenuOldTrips(Person.Id, boolean, int, int)} narrowed by the site admin's org selector. */
    public List<Trip> getMenuOldTrips(final Person.Id userId, final boolean showAll,
            final int pastDaysStillActive, final int limit, final String selectedOrgId) {
        return getInactiveTrips(userId, true, pastDaysStillActive, limit).stream()
                .filter(trip -> listsInMenu(trip, userId, showAll, selectedOrgId))
                .toList();
    }

    boolean listsInMenu(final Trip trip, final Person.Id userId, final boolean showAll) {
        return listsInMenu(trip, userId, showAll, null);
    }

    /**
     * Whether a trip may appear in this site's menu. An organization's site lists only its own trips,
     * whoever is looking. A SHARED site's menus list only the organizations that SHARE it: an org with a
     * site of its own (or one that opted out of shared sites) never appears here -- not for its members,
     * not for a site admin; they see that content on the org's own site. The site admin's org selector
     * (the topbar chip, {@code sessionScope.currentOrgId}) then narrows the menus to one sharing org; the
     * menu's own public/member/admin guard still applies on top. Properties of the site and the selector,
     * never permissions.
     */
    boolean listsInMenu(final Trip trip, final Person.Id userId, final boolean showAll,
            final String selectedOrgId) {
        final SiteContext site = SiteContext.current();
        if (site.isOrg()) {
            return site.admits(trip.getOrgId());
        }
        if (!ListingScope.forSite().shows(trip.getOrgId())) {
            return false;
        }
        return selectedOrgId == null || selectedOrgId.isBlank() || selectedOrgId.equals(trip.getOrgId());
    }

    /**
     * The landing-page listing: publicly-listed trips ({@code openToPublic}), kept for
     * {@link #PUBLIC_PAST_DAYS} days after they end, oldest start date first, on the SITE the request is for
     * ({@link ListingScope}: an organization's site lists only its own trips; a shared site the orgs it
     * curates). Everything the public page, menu and sidebar show derives from this one index-cached read.
     */
    public List<Trip> getPublicTrips() {
        final ListingScope scope = ListingScope.forSite();
        return publicTripsAnywhere().stream().filter(trip -> scope.shows(trip.getOrgId())).toList();
    }

    /** Every publicly-listed trip regardless of site: the raw index the site-scoped views narrow. */
    private List<Trip> publicTripsAnywhere() {
        return activeTripsAnywhere(PUBLIC_PAST_DAYS).stream()
                .filter(trip -> Boolean.TRUE.equals(trip.getOpenToPublic()))
                .sorted(Comparator.comparing(Trip::getStartDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * {@link #getPublicTrips()} for one language. Takes the enum constant's name as a string so EL can pass
     * {@code lang.name()}; a trip with no stored language folds into English (the site default) rather than
     * vanishing from every section.
     */
    public List<Trip> getPublicTrips(final String language) {
        final Language wanted = parseLanguage(language);
        return getPublicTrips().stream()
                .filter(trip -> languageOf(trip) == wanted)
                .toList();
    }

    /** The distinct languages present in {@link #getPublicTrips()}, in declaration (display) order --
     *  drives which language sections the landing page renders, so a new language appears automatically. */
    public List<Language> getPublicTripLanguages() {
        final Set<Language> present = getPublicTrips().stream()
                .map(TripCommands::languageOf)
                .collect(Collectors.toSet());
        return Arrays.stream(Language.values()).filter(present::contains).toList();
    }

    /**
     * The sidebar's link list: {@link #getPublicTrips(String)} restricted to CFPW-hosted trips on the shared
     * site. An organization's own site links its own trips -- there is no other provider there, and the
     * CFPW badge is a shared-site notion.
     */
    public List<Trip> getPublicCfpwTrips(final String language) {
        final List<Trip> listed = getPublicTrips(language);
        return SiteContext.current().isOrg() ? listed : listed.stream().filter(Trip::isCfpw).toList();
    }

    /**
     * The pilgrimages a "Pilgrimage Listings" programmatic content instance shows: its admin-provided
     * properties (language, CFPW-only, max count, and on a shared site the curated {@code includeOrgs}
     * list) applied to the raw public listing through {@link ListingScope}. Blank or unparsable properties
     * fall back to everything the site may show -- a public page renders permissively, never errors. On an
     * ORGANIZATION's site the listing is always that org's own trips, whatever the instance says: tenant
     * isolation is a property of the site, not an option an editor could forget to tick.
     */
    public List<Trip> getPublicTripsFor(final ContentInstance instance) {
        if (instance == null) {
            return List.of();
        }
        final Map<String, String> values = instance.getValues();
        final ListingScope scope = ListingScope.forInstance(values);
        final Language wanted = parseLanguage(values.get("language"));
        final boolean cfpwOnly = Boolean.parseBoolean(values.get("cfpwOnly")) && !SiteContext.current().isOrg();
        final int max = parsePositive(values.get("maxCount"), Integer.MAX_VALUE);
        return publicTripsAnywhere().stream()
                .filter(trip -> scope.shows(trip.getOrgId()))
                .filter(trip -> languageOf(trip) == wanted)
                .filter(trip -> !cfpwOnly || trip.isCfpw())
                .limit(max)
                .toList();
    }

    private static int parsePositive(final String raw, final int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            final int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (final NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * The sidebar's countdown cards: for each language, the next CFPW trip that has not started, PLUS any
     * public CFPW trip starting within {@code soonDays} -- deduped, soonest first. CFPW-only on purpose:
     * countdown cards link to the hosted trip-details page, which external pilgrimages do not have.
     */
    public List<Trip> getCountdownTrips(final int soonDays) {
        final LocalDateTime now = LocalDateTime.now();
        final LocalDateTime soon = now.plusDays(soonDays);
        // The CFPW-only cut is a shared-site notion; an organization's site counts down to its own trips.
        final boolean orgSite = SiteContext.current().isOrg();
        final List<Trip> upcoming = getPublicTrips().stream()
                .filter(trip -> orgSite || trip.isCfpw())
                .filter(trip -> trip.getStartDate() != null && trip.getStartDate().isAfter(now))
                .toList();
        final Map<Language, Trip> nextPerLanguage = new LinkedHashMap<>();
        for (final Trip trip : upcoming) {
            nextPerLanguage.putIfAbsent(languageOf(trip), trip);
        }
        return upcoming.stream()
                .filter(trip -> nextPerLanguage.containsValue(trip) || !trip.getStartDate().isAfter(soon))
                .toList();
    }

    // No server-side daysUntil: "days until" is CALENDAR-day math in the VIEWER's timezone, which only the
    // browser knows. The sidebar renders each trip's zone-naive start date (Trip.getStartDateIso) into a
    // data- attribute and a small script computes the number client-side. A server-computed count was tried
    // and read one day low for most of each day (elapsed-period truncation + the container's UTC clock).

    /** The languages a trip can be offered in, for the trip editor's menu (replaces a hardcoded list). */
    public List<Language> getLanguages() {
        return List.of(Language.values());
    }

    private static Language languageOf(final Trip trip) {
        return trip.getLanguage() == null ? Language.English : trip.getLanguage();
    }

    private static Language parseLanguage(final String language) {
        if (language == null || language.isBlank()) {
            return Language.English;
        }
        try {
            return Language.valueOf(language.trim());
        } catch (final IllegalArgumentException ex) {
            return Language.English;
        }
    }

    /**
     * Inactive (past) trips. Admins get every past trip capped at {@code limit} (most recent first); non-admins get
     * only their own past trips. {@code limit} is supplied by the caller (e.g. the menu page) so it is configurable
     * without a code change; non-positive means no cap.
     */
    public List<Trip> getInactiveTrips(
            final Person.Id userId, final boolean isAdmin, final int pastDaysStillActive, final int limit) {
        final LocalDateTime cutoff = LocalDateTime.now().minus(pastDaysStillActive, DAYS);
        if (isAdmin) {
            try {
                return here(DAO.getInstance().getInactiveTrips(cutoff, limit, Cached.YES));
            } catch (final RuntimeException ex) {
                log.error("Failed to get inactive trips!", ex);
                return Collections.emptyList();
            }
        }
        return getTripsForUser(userId).stream()
                .filter(trip -> trip.getEndDate() != null && trip.getEndDate().isBefore(cutoff))
                .sorted(Comparator.comparing(Trip::getStartDate).reversed())
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .toList();
    }

    /**
     * The {@code limit} most-recently-ending trips (admin pickers), served from the trip index -- not a full-table
     * scan. Callers (e.g. XHTML dropdowns) pass the cap so it is configurable without a code change.
     */
    public List<Trip> getRecentTrips(final int limit) {
        return here(recentTripsAnywhere(limit));
    }

    /** {@link #getRecentTrips} before the site gate -- for the org-keyed reads, which bound themselves. */
    private List<Trip> recentTripsAnywhere(final int limit) {
        try {
            return DAO.getInstance().getRecentTrips(limit, Cached.YES);
        } catch (final RuntimeException ex) {
            log.error("Failed to get recent trips!", ex);
            return Collections.emptyList();
        }
    }

    /**
     * The trip by id, or a blank trip (fresh id, null title) when there is no such trip -- OR when the site
     * this request is for does not reach it ({@link #reachesHere}): a hosted organization's trip on the
     * shared site, another org's trip on an org site. Pages prove existence with {@code id.equals(...)} /
     * {@code title == null} and REST with {@code BaseResource.findTrip}, so one gate here makes an
     * out-of-site trip behave exactly like an unknown one everywhere those read through.
     */
    public Trip getTrip(final String id) {
        try {
            return hereOrBlank(DAO.getInstance().getTrip(id, Cached.YES).orElse(null));
        } catch (final RuntimeException ex) {
            log.error("Failed to get trip '" + id + "'!", ex);
            return Trip.builder().build();
        }
    }

    /**
     * The trip an EDIT page seeds its working draft from ({@code TripEditDrafts}), always read fresh: the
     * draft becomes the save payload wholesale, so seeding it from the near-cache would let a stale copy
     * overwrite fields somebody else just changed. Display resolution stays on {@link #getTrip}; the site
     * gate is the same.
     */
    public Trip getTripForEdit(final String id) {
        try {
            return hereOrBlank(DAO.getInstance().getTrip(id, Cached.NO).orElse(null));
        } catch (final RuntimeException ex) {
            log.error("Failed to get trip '" + id + "' for editing!", ex);
            return Trip.builder().build();
        }
    }

    /**
     * Whether the site this request is for serves pages about this trip -- {@code ListingScope.reaches}
     * on the trip's org: an org site reaches only its own trips, a shared site the orgs it lists (its
     * sharing tenants, org-less legacy trips, and a hosted org only while the shared page curates it).
     * Everything is reachable off a bound request (mail, digests, schedulers). Every trip READ on this
     * bean applies it, so lists never offer what {@link #getTrip} would then answer blank.
     */
    static boolean reachesHere(final Trip trip) {
        return ListingScope.reachable(trip.getOrgId());
    }

    private static Trip hereOrBlank(final Trip trip) {
        return trip == null || !reachesHere(trip) ? Trip.builder().build() : trip;
    }

    /** {@code trips} narrowed to what this site reaches. */
    private static List<Trip> here(final List<Trip> trips) {
        return trips.stream().filter(TripCommands::reachesHere).toList();
    }

    public Trip getBoundTrip(final String id, final String bindingType) {
        return getBind().getBoundThing(id, bindingType, BindingType.TRIP, this::getTrip);
    }

    public TripEvent getBoundTripEvent(final String id, final String bindingType) {
        return getBind().getBoundThing(id, bindingType, BindingType.TRIP_EVENT, this::getTripEvent);
    }

    /**
     * This is used to help determine the correct trip to show for the particular user. The chosen trip depends on the
     * user's permissions, what trips they are part of, and whether they already have the trip they need.
     *
     * @param currTrip  The resolved trip, which may already be calculated, if supplied this will be returned.
     * @param userId    The userId.
     * @param tripId    The desired tripId -- will be returned if it exists and the user is part of the trip or admin.
     * @param showAll   True if the user is an admin (can see all).
     *
     * @return  The trip to display, or null if the user should not see any trips.
     */
    public Trip getTripForUser(final Trip currTrip, final Person.Id userId, final Boolean showAll,
            final String tripId) {
        Trip result;
        if (canSeeTrip(currTrip, userId, showAll) && !isBlankAnswer(currTrip)) {
            result = currTrip;                          // Use current trip
        } else if ((tripId != null) && canSeeTrip(findTrip(tripId), userId, showAll)) {
            result = findTrip(tripId);                  // Use requested trip
        } else {
            // Anything the user can see... or null. The user's own trips come from the reverse index (unbounded
            // per user); the admin "see any trip" and joinable fallbacks only need recent trips (joinable trips
            // start in the future, so they are always among the most recent).
            result = findTrip(getTripsForUser(userId), userId, false); // Try w/o considering admin privs
            if (result == null && showAll) {
                result = findTrip(getRecentTrips(RECENT_TRIP_LIMIT), userId, showAll);
            }
            if (result == null) {
                // See if there's anything they can join
                result = getRecentTrips(RECENT_TRIP_LIMIT).stream()
                        .filter(trip -> trip.canJoin(userId)).findAny().orElse(null);
            }
        }
        return result;
    }

    /**
     * Whether this is the bean convention's "no such trip" answer ({@link #getTrip} on a miss or an
     * out-of-site trip): a page passes it straight back in as {@code currTrip} after reading
     * {@code sessionScope.lastTripId}, and an admin "can see" any trip -- so without this the site's
     * "current trip" would be the blank one instead of the next trip the site actually lists. Recognized by
     * its minted id, which nothing ever stored (a title can legitimately be missing; an unstored id cannot).
     */
    private boolean isBlankAnswer(final Trip trip) {
        return findTrip(trip.getId()) == null;
    }

    /** The ids of these events, in order -- the view-held SCALAR anchor for frozen-order row resolution. */
    public List<String> eventIdsOf(final List<TripEvent> events) {
        final List<String> ids = new ArrayList<>();
        if (events != null) {
            for (final TripEvent event : events) {
                ids.add(event.getId());
            }
        }
        return ids;
    }

    /**
     * The trip's CURRENT copies of the given events, in the FROZEN order (vanished ids are skipped): a
     * cell-editing table binds straight into its row objects, so decode must see the same row at the same
     * position the render produced -- which a per-request derive alone cannot promise once the trip's
     * event list changes underneath the open view.
     */
    public List<TripEvent> eventsForFrozenIds(final Trip trip, final List<String> ids) {
        final List<TripEvent> events = new ArrayList<>();
        if (trip == null || ids == null) {
            return events;
        }
        for (final String id : ids) {
            for (final TripEvent event : trip.getTripEvents()) {
                if (event.getId().equals(id)) {
                    events.add(event);
                    break;
                }
            }
        }
        return events;
    }

    private Trip advisoryGetTrip(final String tripId) {
        try {
            return DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    /**
     * This person's {@code limit} most recent trips (by start date, newest first) -- the profile page's
     * family section shows each member's recent travel at a glance. Roster membership only; upcoming trips
     * sort first because they have the latest start dates, which is the order a "recent trips" list reads
     * naturally in.
     */
    public List<Trip> recentTripsFor(final Person.Id userId, final int limit) {
        return getTripsForUser(userId).stream()
                .filter(trip -> trip.getStartDate() != null)
                .sorted(Comparator.comparing(Trip::getStartDate).reversed())
                .limit(limit > 0 ? limit : 5)
                .toList();
    }

    public List<Trip> getTripsForUser(final Person.Id userId) {
        try {
            return here(DAO.getInstance().getTripsForUser(userId, Cached.YES));
        } catch (final RuntimeException ex) {
            log.error("Failed to get trips for user!", ex);
            return Collections.emptyList();
        }
    }

    public TripEvent getTripEvent(final String eventId) {
        try {
            return DAO.getInstance().getTripEvent(eventId, Cached.YES);
        } catch (final RuntimeException ex) {
            return logAndReturn(ex, null);
        }
    }

    // This only works for flights
    public LocalDateTime getLodgingArrivalDate(final Collection<TripEvent> events, final TripEvent lodgingEvent) {
        if (events == null || lodgingEvent == null) {
            return null;
        }
        final List<TripEvent> sorted = new ArrayList<>(
                events.stream().filter(e -> e.getType() == TripEvent.Type.FLIGHT).toList());
        sorted.sort(Comparator.comparing(TripEvent::getEnd));
        if (sorted.isEmpty()) {
            return null;
        }
        LocalDateTime result = sorted.get(0).getEnd();
        for (final TripEvent te : sorted) {
            if (te.getType() != TripEvent.Type.FLIGHT) {
                continue;
            }
            final LocalDateTime teEnd = te.getEnd();
            if (teEnd.isBefore(lodgingEvent.getEnd()) && teEnd.isAfter(result)
                    // This tries to guess arrival based on layovers < 36 hours (not perfect)
                    && Duration.between(result, te.getStart()).toHours() < 36) {
                result = teEnd;
            }
        }
        return result;
    }

    // This only works for flights
    public LocalDateTime getLodgingDepartureDate(final Collection<TripEvent> events, final TripEvent lodgingEvent) {
        if (events == null || events.isEmpty() || lodgingEvent == null) {
            return null;
        }
        // Backup 4 hours in case we're slightly past midnight
        final LocalDateTime lodgingStart = getLodgingArrivalDate(events, lodgingEvent);
        // Default to staying the whole time
        LocalDateTime result = lodgingEvent.getEnd();
        for (final TripEvent te : events) {
            if (te.getType() != TripEvent.Type.FLIGHT) {
                continue;
            }
            final LocalDateTime flight = te.getStart();
            if (flight.isBefore(result) && flight.isAfter(lodgingStart)) {
                // Soonest flight we've seen after we've arrived
                result = flight;
            }
        }
        return result;
    }

    public long getLodgingDays(final LocalDateTime start, final LocalDateTime end) {
        if (start == null || end == null) {
            return -1L;
        }
        final LocalDateTime adjustedStart = ((start.getHour() < 4) ? start.minusHours(4) : start).truncatedTo(DAYS);
        final LocalDateTime adjustedEnd = end.truncatedTo(DAYS);
        return Duration.between(adjustedStart, adjustedEnd).toDays();
    }

    /**
     * This findTrip method looks for any trip the user can see. It's the last resort way to resolve the trip to show
     * the user.
     * @param trips     All the possible trips.
     * @param userId    The userId.
     * @param showAll   True if an admin (admins can see everything).
     * @return  The trip to show the user, if any. {@code null} if none.
     */
    private Trip findTrip(final List<Trip> trips, final Person.Id userId, final Boolean showAll) {
        if (trips == null) {
            return null;
        }
        final List<Trip> ans = trips.stream().filter(t -> canSeeTrip(t, userId, showAll)).collect(Collectors.toList());
        return ans.isEmpty() ? null : getFirstActiveOrLastTrip(ans);
    }

    /**
     *  This method requires a non-null, non-empty List of trips. It will return the first active trip. If none
     *  exist, it will return the last trip in the list (typically the last trip that started).
     */
    private Trip getFirstActiveOrLastTrip(final List<Trip> trips) {
        final List<Trip> active = filterActiveTrips(trips, 0);
        return active.isEmpty() ? trips.get(trips.size() - 1) : active.get(0);
    }

    private List<Trip> filterActiveTrips(final List<Trip> trips, final int pastDaysToCountAsActive) {
        final LocalDateTime cutoff = LocalDateTime.now().minusDays(pastDaysToCountAsActive);
        return trips.stream()
                .filter(trip -> trip.getEndDate().isAfter(cutoff))
                .collect(Collectors.toList());
    }

    /**
     * This findTrip method looks for a specific Trip by id. Only used for the
     * {@link #getTripForUser(Trip, Person.Id, Boolean, String)} method.
     *
     * @param tripId    The trip id.
     * @return The trip or null if not found.
     */
    private Trip findTrip(final String tripId) {
        final Trip trip = DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        return trip == null || !reachesHere(trip) ? null : trip;
    }

    /**
     * Ensures the trip either contains the person, or the person is an admin.
     * @param trip      The trip to check.
     * @param userId    The userId to check.
     * @param priv      The user's privileges.
     * @return  True if the user is allowed to see this Trip.
     */
    private boolean canSeeTrip(final Trip trip, final Person.Id userId, final Boolean priv) {
        if ((trip == null) || (userId == null)) {
            return false;
        }
        return trip.getPeople().contains(userId) || ((priv != null) && priv)
                || managesSomeoneOn(trip, userId);
    }

    /**
     * The family case the old FIXME declined: a parent whose kid is on the trip -- but not themselves -- may
     * see the trip. Resolved through {@code managedUsers} (kept in sync from the family row), so admin-granted
     * visibility rides along identically.
     */
    private boolean managesSomeoneOn(final Trip trip, final Person.Id userId) {
        final Person person = PersonCommands.getPersonCommands().getPerson(userId);
        for (final Person.Id managedId : person.getManagedUsers()) {
            if (trip.getPeople().contains(managedId)) {
                return true;
            }
        }
        return false;
    }

    public BindingCommands getBind() {
        if (bind == null) {
            log.warn("Did not getting BindingCommands injected!");
            bind = new BindingCommands();
        }
        return bind;
    }

    private <T> T logAndReturn(final Throwable ex, final T result) {
        log.warn("Exception!", ex);
        return result;
    }
}
