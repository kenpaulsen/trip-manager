package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.paulsens.trip.action.LodgingViews.AccommodationForm;
import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.action.LodgingViews.ReservationForm;
import org.paulsens.trip.action.LodgingViews.RoomTypeForm;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;

/**
 * Hotels, options, stays and operators for the lodging tests, written through the same beans the pages use.
 *
 * <p>The reason this is ONE class: creating a hotel runs discovery, and a single matching detail (name, email,
 * phone DIGITS, website host, or street) refuses the create as a duplicate. The suite shares one store, so
 * per-class counters and random digits collided across classes and failed whichever test came second. Every
 * discoverable detail here is drawn from one JVM-wide counter, so no class order can produce a duplicate.
 */
public final class LodgingFixtures {
    /** JVM-wide on purpose: see the class comment. Never reset it. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private LodgingFixtures() {
    }

    /** The next value of the shared counter, for a test that builds a hotel form of its own. */
    public static int next() {
        return SEQ.incrementAndGet();
    }

    /**
     * A phone number no other fixture has. Fixed width behind a prefix nothing else uses: discovery compares
     * digits only, so two variable-width parts side by side ("1" + "23" and "12" + "3") would read the same.
     */
    public static String phone() {
        return String.format(Locale.ROOT, "+1 000 %07d", next());
    }

    /** An unsaved hotel in Portland whose every discoverable detail is unique; the name starts with {@code name}. */
    public static AccommodationForm hotelForm(final String name) {
        final String tag = RandomData.genAlpha(8) + next();
        final AccommodationForm form = new AccommodationForm();
        form.setName(name + " " + tag);
        form.setEmail(tag.toLowerCase(Locale.ROOT) + "@hotel.example");
        form.setPhone(phone());
        form.setStreet(next() + " " + tag + " St");
        form.setCity("Portland");
        return form;
    }

    /** Saves the form into the organization and returns the new hotel's id; fails when discovery refused it. */
    public static String saveHotel(final LodgingCommands admin, final String orgId, final AccommodationForm form) {
        final String accId = admin.saveAccommodation(form, orgId);
        Assert.assertTrue(accId != null && !accId.isBlank(), "the hotel fixture was refused as a duplicate");
        return accId;
    }

    /** A saved hotel with one room type, a Double for one or two people, and no rooms. */
    public static String hotel(final LodgingCommands admin, final String orgId, final String name) {
        final String accId = saveHotel(admin, orgId, hotelForm(name));
        roomType(admin, accId, "Double", 1, 2);
        return accId;
    }

    public static void roomType(final LodgingCommands admin, final String accId, final String name, final int min,
            final int max) {
        final RoomTypeForm type = new RoomTypeForm();
        type.setName(name);
        type.setMinPeople(min);
        type.setMaxPeople(max);
        Assert.assertTrue(admin.saveRoomType(accId, type));
    }

    /**
     * An unsaved option at the hotel: its first room type, per room at 100 a night, on a lodging event of its
     * own. The caller sets whatever its test is about (a cost, another price) before {@link #saveOffer}.
     */
    public static OfferForm offerForm(final LodgingCommands admin, final String accId, final String name,
            final LocalDateTime start, final LocalDateTime end) {
        final OfferForm form = new OfferForm();
        form.setName(name);
        form.setAccommodationId(accId);
        form.setRoomTypeIds(new ArrayList<>(List.of(admin.roomTypeRows(accId).get(0).getId())));
        form.setTripEventId(OfferForm.NEW_EVENT);
        form.setPricingModel("PER_ROOM");
        form.setNightlyPrice(100.0);
        form.setDefaultStart(start);
        form.setDefaultEnd(end);
        return form;
    }

    /** Saves the option and returns it as stored, found by its name at its hotel. */
    public static ReservationOffer saveOffer(final LodgingCommands admin, final String tripId, final OfferForm form) {
        Assert.assertTrue(admin.saveOffer(tripId, form), form.getProblem());
        return DAO.getInstance().getReservationOffers(tripId, Cached.NO).stream()
                .filter(offer -> isOfferOf(offer, form)).findFirst().orElseThrow();
    }

    private static boolean isOfferOf(final ReservationOffer offer, final OfferForm form) {
        return form.getName().equals(offer.getName())
                && form.getAccommodationId().equals(offer.getAccommodationId().getValue());
    }

    /** One person's stay on the option, in no particular room. */
    public static void reserve(final LodgingCommands admin, final String tripId, final String offerId,
            final Person who, final LocalDateTime start, final LocalDateTime end) {
        final ReservationForm form = new ReservationForm();
        form.setOfferId(offerId);
        form.setPersonIds(List.of(who.getId().getValue()));
        form.setStart(start);
        form.setEnd(end);
        Assert.assertEquals(admin.createReservations(tripId, form), 1, "the stay fixture was refused");
    }

    /** Grants {@code accommodationAdmin} on the hotel: the Hotel Operator role. */
    public static void makeOperator(final Person who, final String accId) {
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.ACCOMMODATION_ADMIN, accId,
                PrivilegeCommands.ACCOMMODATION_ROLE_NAME).withNewPerson(who.getId())));
    }

    /** A saved trip of the organization with these people on its roster, ending two days after check-out. */
    public static Trip rosterTrip(final String orgId, final String title, final LocalDateTime checkIn,
            final LocalDateTime checkOut, final Person... people) throws IOException {
        final List<Person.Id> ids = new ArrayList<>();
        for (final Person person : people) {
            ids.add(person.getId());
        }
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title(title)
                .startDate(checkIn).endDate(checkOut.plusDays(2)).people(ids).build();
        trip.setOrgId(orgId);
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    /** A site-admin lodging bean whose audit trail the test can read. */
    public static LodgingCommands auditedLodging(final RecordingAudit audit) {
        return new LodgingCommands(TestCallers::siteAdmin,
                () -> new TripCommands(() -> new OrgCommands(TestCallers::siteAdmin)), TransactionsCommands::new,
                () -> new OrgCommands(TestCallers::siteAdmin), PrivilegeCommands::new, () -> audit,
                MediaCommands::new);
    }

    /** Records every lodging audit line, so a test can say what was NOT audited (a recompute, say). */
    public static final class RecordingAudit extends AuditCommands {
        private final List<String> lines = new ArrayList<>();

        @Override
        public String lodging(final String targetType, final String targetId, final String orgId, final String msg,
                final AuditActor who) {
            lines.add(msg);
            return super.lodging(targetType, targetId, orgId, msg, who);
        }

        /** The live list: a test clears it between the two halves of a comparison. */
        public List<String> lines() {
            return lines;
        }

        public boolean recomputed() {
            return lines.stream().anyMatch(line -> line.contains("recomputed"));
        }
    }
}
