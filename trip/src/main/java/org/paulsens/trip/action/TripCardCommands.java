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
import org.paulsens.trip.model.Registration;
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
 * opens can never disagree about Full or Closed. A viewer with a registration of their own reads its status instead:
 * Registered once it is confirmed, Pending while it waits for approval (as the old listing's chip said; a check mark
 * there would read as accepted).
 *
 * <p><b>No picture</b> falls back to the owning organization's link-preview image, then its background image, each
 * only when it is one the organization uploaded here (never a typed URL on another host), then nothing, which the
 * page draws as a generated cover in the site's own colors. Never a stock photo the organization
 * did not choose, and never another organization's picture: the fallback is read from the TRIP's organization.
 *
 * <p>Anonymous-safe and render-only: a null viewer looks up no registration, nothing here touches a session or a
 * view, and the list is memoized for the request because a page asks for it several times.
 */
@Named("tripCards")
@ApplicationScoped
public class TripCardCommands {

    /**
     * The widest rendition a card ever offers (the middle one), whatever the screen's density: performance before
     * sharpness on a listing (owner, 2026-11-17). The trip's own page still offers the full-size picture.
     */
    public static final int CARD_MAX_WIDTH = TripPicture.WIDTHS.get(1);

    /** The one registration state a card shows. */
    public enum State {
        /** Open, and the viewer is not on it. */
        REGISTER("Register"),
        /** The viewer's own registration here is confirmed. */
        REGISTERED("Registered"),
        /** The viewer's own registration here waits for approval. */
        PENDING("Pending"),
        /** Every place is taken. */
        FULL("Full"),
        /** Under way or over, or priced checkout's registration window has closed (as {@code Phase.CLOSED}). */
        CLOSED("Closed"),
        /**
         * Priced checkout's registration window has not opened yet: the chip reads "Opens Dec 1"
         * ({@link Card#getStateLabel}), this label being only its first word.
         */
        NOT_OPEN("Opens");

        private final String label;

        State(final String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        /** The lower-case name without its underscore, for the chip's style class ({@code tripChip-notopen}). */
        public String getStyle() {
            return name().replace("_", "").toLowerCase(Locale.ROOT);
        }

        /**
         * The chip's icon classes, "" for none: a check only once a registration is confirmed, a clock while one
         * waits for approval (a check reads as accepted). Here, beside the label and the style, so a new state
         * gets its icon where it gets its words.
         */
        public String getIcon() {
            return switch (this) {
                case REGISTERED -> "pi pi-check";
                case PENDING -> "pi pi-clock";
                case NOT_OPEN -> "pi pi-calendar";
                case REGISTER, FULL, CLOSED -> "";
            };
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
        /** What the state chip says: the state's label, or "Opens Dec 1" for a window not open yet. */
        String stateLabel;
        /** The cover picture, or null when the trip has none. */
        TripPicture cover;
        /** The cover's default {@code src} (the middle rendition); null without a cover. */
        String coverUrl;
        /** The cover's {@code srcset}, up to {@link #CARD_MAX_WIDTH}; null without a cover. */
        String coverSrcset;
        /** The organization's own image standing in for a missing cover; null when it has none either. */
        String fallbackUrl;
        /** The offering organization's name on a shared site; null on an organization's own site. */
        String orgName;
        /** Whether this is the section's next departure, drawn large. */
        boolean featured;

        /** What a screen reader says for the picture: its description, else the trip's title. */
        public String getAlt() {
            return TripPictureCommands.altText(cover, trip);
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
        final Registration.Status status = viewer == null ? Registration.Status.NOT_REGISTERED
                : RegistrationCommands.statusOf(registrations.get().getRegistration(trip.getId(), viewer));
        final boolean registered = status != Registration.Status.NOT_REGISTERED;
        final State state = stateOf(trip, status);
        return new Card(trip, trips.get().publicTripUrlFor(trip, registered), state, labelOf(state, trip), cover,
                cover == null ? null : pics.url(cover, CARD_MAX_WIDTH),
                cover == null ? null : pics.srcsetUpTo(cover, CARD_MAX_WIDTH),
                cover == null ? fallbackOf(trip) : null, orgName, featured);
    }

    private State stateOf(final Trip trip, final Registration.Status status) {
        if (status == Registration.Status.PENDING) {
            return State.PENDING;
        }
        if (status != Registration.Status.NOT_REGISTERED) {
            return State.REGISTERED;
        }
        final TripPageCommands.RegistrationInfo info = pages.get().registrationInfo(trip);
        return stateOf(info == null ? TripPageCommands.Phase.OPEN : info.phase());
    }

    /** The card's state IS the trip page's phase (an external trip's typed count is part of that rule). */
    static State stateOf(final TripPageCommands.Phase phase) {
        return switch (phase) {
            case CLOSED -> State.CLOSED;
            case NOT_OPEN -> State.NOT_OPEN;
            case FULL -> State.FULL;
            case OPEN -> State.REGISTER;
        };
    }

    /** The chip's words: the state's label, or for a window not open yet its day, "Opens Dec 1". */
    private String labelOf(final State state, final Trip trip) {
        return state == State.NOT_OPEN
                ? state.getLabel() + " " + pages.get().registrationInfo(trip).opensDate()
                : state.getLabel();
    }

    /**
     * How many registrations on a trip wait for approval: the staff-only chip. Memoized for the request, because
     * the page asks twice per card (whether to draw the chip, then what it says) and each ask is an uncached read.
     */
    public int pending(final Trip trip) {
        return RequestMemo.get(requestMap.get(), "tripCards.pending:" + trip.getId(),
                () -> registrations.get().getNumPending(trip.getId()));
    }

    /** The owning organization's own image for a trip with no picture, or null. */
    private String fallbackOf(final Trip trip) {
        return TripCommands.ownerOf(trip.getOrgId()).map(orgImage).orElse(null);
    }

    /**
     * An organization's link-preview image, else its background image, each only when it is an image the
     * organization UPLOADED here ({@link BrandingPhotos#isUploadOf}); else null. Both settings also take a typed
     * URL, and a card is drawn on listings other organizations' visitors see: a typed one would make every such
     * visitor's browser call a host only that organization chose.
     */
    private static String brandImageOf(final Organization org) {
        return brandImageOf(org, Beans.get(BrandCommands.class), Beans.get(BrandingPhotos.class));
    }

    /**
     * The picture an organization's trip with none of its own falls back to, by the cards' rule
     * ({@link #brandImageOf(Organization)}), for a client that draws the cover itself (the apps, through
     * {@code TripDto.fallbackPicture}); null for none, no organization or an unknown one.
     */
    public static String orgPictureOf(final String orgId) {
        return TripCommands.ownerOf(orgId).map(TripCardCommands::brandImageOf).orElse(null);
    }

    /** {@link #brandImageOf(Organization)} with its two beans explicit: the half a unit test can run. */
    static String brandImageOf(final Organization org, final BrandCommands brand, final BrandingPhotos photos) {
        final BrandCommands.OrgLook look = brand.lookOf(org);
        return photos.firstUploadOf(org.getId().getValue(), look.ogImageUrl(), look.backgroundUrl());
    }
}
