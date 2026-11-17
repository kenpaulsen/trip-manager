package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Value;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPicture;
import org.paulsens.trip.site.SiteContext;

/**
 * The cards a Trip Listings section draws (issue 154, Phase 2), exposed as {@code #{tripCards}}: one {@link Card}
 * per listed trip, carrying everything the page needs so the page holds no rules of its own.
 *
 * <p>WHICH trips is still {@link TripCommands#getPublicTripsFor}, so the section's language, max count, includeOrgs
 * and the org-site isolation rule are untouched. This adds, per trip: where the card links
 * ({@link TripCommands#publicTripUrl}), its ONE registration state, its cover (or what stands in for one), and
 * whether it is the section's featured next departure.
 *
 * <p><b>The state</b> is the trip page's own ({@link TripPageCommands#registrationInfo}): a card and the page it
 * opens can never disagree about Full or Closed. A viewer with a registration of their own reads Registered instead,
 * whatever its status; the detail page says which.
 *
 * <p><b>No picture</b> falls back to the owning organization's link-preview image, then its background image, then
 * nothing, which the page draws as a generated cover in the site's own colors. Never a stock photo the organization
 * did not choose, and never another organization's picture: the fallback is read from the TRIP's organization.
 *
 * <p>Anonymous-safe and render-only: a null viewer looks up no registration, nothing here touches a session or a
 * view, and the list is memoized for the request because a page asks for it several times.
 */
@Named("tripCards")
@ApplicationScoped
public class TripCardCommands {

    /** The one registration state a card shows. */
    public enum State {
        /** Open, and the viewer is not on it. */
        REGISTER("Register"),
        /** The viewer has a registration here, in any status. */
        REGISTERED("Registered"),
        /** Every place is taken. */
        FULL("Full"),
        /** Under way or over. */
        CLOSED("Closed");

        private final String label;

        State(final String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /** The lower-case name, for the chip's style class. */
        public String getStyle() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One trip as a listing card. Request-scoped by use: never stored in a view or a session. */
    @Value
    public static class Card {
        /** The trip itself, for its title, dates, destination and price. */
        Trip trip;
        /** Where the whole card links. */
        String url;
        State state;
        /** The cover picture, or null when the trip has none. */
        TripPicture cover;
        /** The cover's default {@code src} (the middle rendition); null without a cover. */
        String coverUrl;
        /** The cover's {@code srcset}; null without a cover. */
        String coverSrcset;
        /** The organization's own image standing in for a missing cover; null when it has none either. */
        String fallbackUrl;
        /** The offering organization's name on a shared site; null on an organization's own site. */
        String orgName;
        /** Whether this is the section's next departure, drawn large. */
        boolean featured;

        /** What a screen reader says for the picture: its description, else the trip's title. */
        public String getAlt() {
            return cover == null || cover.getAlt() == null || cover.getAlt().isBlank()
                    ? trip.getShortTitle() : cover.getAlt();
        }
    }

    private final Supplier<TripCommands> trips;
    private final Supplier<RegistrationCommands> registrations;
    private final Supplier<TripPageCommands> pages;
    private final Supplier<TripPictureCommands> pictures;
    private final Function<Organization, String> orgImage;
    private final Supplier<SiteContext> site;
    private final Supplier<LocalDateTime> clock;
    private final Supplier<Map<String, Object>> requestMap;

    public TripCardCommands() {
        this(() -> Beans.get(TripCommands.class), () -> Beans.get(RegistrationCommands.class),
                () -> Beans.get(TripPageCommands.class), () -> Beans.get(TripPictureCommands.class),
                TripCardCommands::brandImageOf, SiteContext::current, LocalDateTime::now,
                RequestMemo::facesRequestMap);
    }

    /** Every collaborator explicit: the test seam. Eight, each one a bean a unit test cannot look up. */
    TripCardCommands(final Supplier<TripCommands> trips, final Supplier<RegistrationCommands> registrations,
            final Supplier<TripPageCommands> pages, final Supplier<TripPictureCommands> pictures,
            final Function<Organization, String> orgImage, final Supplier<SiteContext> site,
            final Supplier<LocalDateTime> clock, final Supplier<Map<String, Object>> requestMap) {
        this.trips = trips;
        this.registrations = registrations;
        this.pages = pages;
        this.pictures = pictures;
        this.orgImage = orgImage;
        this.site = site;
        this.clock = clock;
        this.requestMap = requestMap;
    }

    /** Every card of the section, in listing order, the featured one included. */
    public List<Card> cards(final ContentInstance instance, final Person.Id viewer) {
        if (instance == null) {
            return List.of();
        }
        final String key = "tripCards:" + instance.getId() + ":" + (viewer == null ? "" : viewer.getValue());
        return RequestMemo.get(requestMap.get(), key, () -> build(instance, viewer));
    }

    /** The section's featured card (its next departure), or null when no listed trip is still to start. */
    public Card featured(final ContentInstance instance, final Person.Id viewer) {
        return cards(instance, viewer).stream().filter(Card::isFeatured).findFirst().orElse(null);
    }

    /** The section's other cards, in listing order. */
    public List<Card> rest(final ContentInstance instance, final Person.Id viewer) {
        return cards(instance, viewer).stream().filter(card -> !card.isFeatured()).toList();
    }

    private List<Card> build(final ContentInstance instance, final Person.Id viewer) {
        final List<Trip> listed = trips.get().getPublicTripsFor(instance);
        final Trip next = nextDeparture(listed);
        final List<Card> cards = new ArrayList<>();
        for (final Trip trip : listed) {
            cards.add(cardOf(trip, viewer, trip == next));
        }
        return cards;
    }

    /**
     * The first listed trip that has not started by the server's CALENDAR ({@link TripCommands#notStartedBy}, the
     * sidebar countdown's rule); the listing is already soonest first. Null when none. Never an instant cut: the
     * server's clock is UTC, and that would drop a trip the afternoon before it starts for a viewer in the Americas.
     */
    private Trip nextDeparture(final List<Trip> listed) {
        final LocalDate today = clock.get().toLocalDate();
        return listed.stream().filter(trip -> TripCommands.notStartedBy(trip, today)).findFirst().orElse(null);
    }

    private Card cardOf(final Trip trip, final Person.Id viewer, final boolean featured) {
        final TripPicture cover = trip.getCover();
        final TripPictureCommands pics = (cover == null) ? null : pictures.get();
        final String orgName = site.get().isOrg() || trip.getProvider() == null || trip.getProvider().isBlank()
                ? null : trip.getProvider();
        // ONE registration lookup per card (an uncached read): the link and the state both turn on it.
        final boolean registered = viewer != null && registrations.get().isChipRegistered(trip.getId(), viewer);
        return new Card(trip, trips.get().publicTripUrlFor(trip, registered), stateOf(trip, registered), cover,
                cover == null ? null : pics.url(cover, TripPicture.WIDTHS.get(1)),
                cover == null ? null : pics.srcset(cover),
                cover == null ? fallbackOf(trip) : null, orgName, featured);
    }

    private State stateOf(final Trip trip, final boolean registered) {
        if (registered) {
            return State.REGISTERED;
        }
        final TripPageCommands.RegistrationInfo info = pages.get().registrationInfo(trip);
        return stateOf(trip, info == null ? TripPageCommands.Phase.OPEN : info.phase());
    }

    /**
     * The card's state from the trip page's phase. An EXTERNAL trip's registrations live on another site, so this
     * site counts none of them: its fullness is the count its editor typed against its limit.
     */
    static State stateOf(final Trip trip, final TripPageCommands.Phase phase) {
        if (phase == TripPageCommands.Phase.CLOSED) {
            return State.CLOSED;
        }
        final boolean externallyFull = trip.isExternal() && trip.getRegLimit() != null
                && trip.getShownRegCount() >= trip.getRegLimit();
        return phase == TripPageCommands.Phase.FULL || externallyFull ? State.FULL : State.REGISTER;
    }

    /** The owning organization's own image for a trip with no picture, or null. */
    private String fallbackOf(final Trip trip) {
        return TripCommands.ownerOf(trip.getOrgId()).map(orgImage).map(TripCardCommands::httpsOnly).orElse(null);
    }

    /** An organization's link-preview image, else its background image, else null. */
    private static String brandImageOf(final Organization org) {
        final BrandCommands.OrgLook look = Beans.get(BrandCommands.class).lookOf(org);
        return firstNonBlank(look.ogImageUrl(), look.backgroundUrl());
    }

    /**
     * The fallback image only when it is served over https. The settings behind it admit http too, and this is
     * the one tenant-typed image a card loads on a listing other organizations' visitors see: an http one would be
     * blocked or broken on an https page, where the generated cover is the better card.
     */
    static String httpsOnly(final String url) {
        return url != null && url.regionMatches(true, 0, "https://", 0, 8) ? url : null;
    }

    static String firstNonBlank(final String first, final String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }
}
