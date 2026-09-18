package org.paulsens.trip.action;

import jakarta.faces.component.UIPanel;
import jakarta.faces.component.behavior.AjaxBehavior;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.action.LodgingViews.AccommodationForm;
import org.paulsens.trip.action.LodgingViews.BlockForm;
import org.paulsens.trip.action.LodgingViews.BlockRow;
import org.paulsens.trip.action.LodgingViews.DayCell;
import org.paulsens.trip.action.LodgingViews.DayDetail;
import org.paulsens.trip.action.LodgingViews.DayRoom;
import org.paulsens.trip.action.LodgingViews.HotelCalendar;
import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.action.LodgingViews.ReservationForm;
import org.paulsens.trip.action.LodgingViews.RoomTypeForm;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.primefaces.event.SelectEvent;
import org.primefaces.model.ScheduleEvent;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The hotel's own view: what it has blocked, and how full it is across every trip staying there. The point
 * of most of these is that the answer spans TRIPS (the index) while naming nobody.
 */
public class HotelCommandsTest {
    private static final LocalDateTime CHECK_IN = LocalDateTime.of(2027, 9, 21, 15, 0);
    private static final LocalDateTime CHECK_OUT = LocalDateTime.of(2027, 9, 24, 10, 0);
    private static final String MONTH = "2027-09";

    private final HotelCommands hotel = new HotelCommands(TestCallers::siteAdmin);
    private final LodgingCommands admin = new LodgingCommands(TestCallers::siteAdmin);
    private String orgId;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        final Organization org = new OrgCommands(TestCallers::siteAdmin)
                .createOrganization("Hotel " + RandomData.genAlpha(8), "HTL", null);
        assertNotNull(org);
        orgId = org.getId().getValue();
    }

    @Test
    public void aBlockIsSavedEditedAndRemoved() {
        final String accId = hotelWithRooms();
        final BlockForm blank = hotel.blockFormFor(accId, "");
        assertTrue(blank.getRoomIds().isEmpty(), "a new block starts empty");
        assertFalse(hotel.saveBlock(accId, blank), "no rooms, no block");

        final BlockForm form = new BlockForm();
        form.setRoomIds(List.of(roomId(accId, 0)));
        form.setRange(List.of(LocalDate.of(2027, 9, 22), LocalDate.of(2027, 9, 22)));
        assertFalse(hotel.saveBlock(accId, form), "the end must be after the first night");
        form.setRange(List.of(LocalDate.of(2027, 9, 22), LocalDate.of(2027, 9, 24)));
        form.setReason("another group");
        assertTrue(hotel.saveBlock(accId, form));

        final List<BlockRow> rows = hotel.blockRows(accId);
        assertEquals(rows.size(), 1);
        assertEquals(rows.get(0).getNights(), 2, "Sep 22 to Sep 24 is two nights");
        assertEquals(rows.get(0).getRoomsLabel(), "101");
        assertEquals(rows.get(0).getReason(), "another group");
        assertFalse(rows.get(0).isPast(), "2027 has not happened yet");

        final BlockForm edit = hotel.blockFormFor(accId, rows.get(0).getId());
        assertEquals(edit.getRoomIds(), List.of(roomId(accId, 0)));
        assertEquals(edit.start(), LocalDate.of(2027, 9, 22));
        edit.setReason("boiler");
        edit.setRoomIds(List.of(roomId(accId, 0), roomId(accId, 1)));
        assertTrue(hotel.saveBlock(accId, edit));
        assertEquals(hotel.blockRows(accId).get(0).getRoomsLabel(), "101, 102");
        assertEquals(hotel.blockRows(accId).get(0).getReason(), "boiler");
        assertEquals(hotel.blockRows(accId).size(), 1, "an edit is not a second block");

        assertTrue(hotel.deleteBlock(accId, rows.get(0).getId()));
        assertTrue(hotel.blockRows(accId).isEmpty());
        assertFalse(hotel.deleteBlock(accId, rows.get(0).getId()), "already gone");
    }

    @Test
    public void onlyTheHotelsOwnManagersChangeItsAvailability() {
        final String accId = hotelWithRooms();
        final HotelCommands stranger = new HotelCommands(() -> TestCallers.person(Person.Id.newInstance()));
        final BlockForm form = new BlockForm();
        form.setRoomIds(List.of(roomId(accId, 0)));
        form.setRange(List.of(LocalDate.of(2027, 9, 22), LocalDate.of(2027, 9, 24)));
        assertFalse(stranger.saveBlock(accId, form), "not this hotel's manager");
        assertFalse(stranger.deleteBlock(accId, "whatever"));
        assertFalse(stranger.canEdit(accId));
        assertTrue(stranger.blockRows(accId).isEmpty(), "and they are not shown what is blocked either");
        assertFalse(hotel.saveBlock("no-such-hotel", form));
        assertTrue(hotel.blockRows("no-such-hotel").isEmpty());
    }

    @Test
    public void aBlockRefusesARoomThatIsNotAtTheHotel() {
        final String accId = hotelWithRooms();
        final BlockForm form = new BlockForm();
        form.setRoomIds(List.of("not-a-room"));
        form.setRange(List.of(LocalDate.of(2027, 9, 22), LocalDate.of(2027, 9, 24)));
        assertFalse(hotel.saveBlock(accId, form));
    }

    /** The whole point of the index: one hotel, two organizations' trips, one set of counts. */
    @Test
    public void theCalendarCountsEveryTripStayingAtTheHotel() throws IOException {
        final String accId = hotelWithRooms();
        final Person ours = savedPerson("Ida");
        final Person theirs = savedPerson("Jon");
        final Trip mine = tripWith("Our trip", ours);
        final Trip other = tripWith("Their trip", theirs);
        reserveInto(mine, accId, ours, roomId(accId, 0));
        reserveInto(other, accId, theirs, roomId(accId, 1));

        final HotelCalendar calendar = hotel.calendar(accId, MONTH);
        assertEquals(calendar.getMonth(), MONTH);
        assertEquals(calendar.getRoomsTotal(), 2);
        assertEquals(calendar.getDays().size(), 30, "September");
        final DayCell night = dayOf(calendar, LocalDate.of(2027, 9, 22));
        assertEquals(night.getRoomsOccupied(), 2, "both trips count: the hotel has two rooms in use");
        assertEquals(night.getPeople(), 2);
        assertEquals(night.getLoad(), "cal-full");
        assertEquals(night.getSummary(), "2/2 rooms · 2 people");
        final DayCell after = dayOf(calendar, LocalDate.of(2027, 9, 24));
        assertEquals(after.getRoomsOccupied(), 0, "they check out on the 24th, so it is not a night");
        assertEquals(after.getLoad(), "cal-free");
        assertEquals(after.getSummary(), "0/2 rooms · 0 people");
    }

    @Test
    public void aBlockedNightCountsAgainstTheHotelAndAClashIsFlagged() throws IOException {
        final String accId = hotelWithRooms();
        final Person guest = savedPerson("Kai");
        final Trip trip = tripWith("Clash trip", guest);
        reserveInto(trip, accId, guest, roomId(accId, 0));
        final BlockForm form = new BlockForm();
        form.setRoomIds(List.of(roomId(accId, 0), roomId(accId, 1)));
        form.setRange(List.of(LocalDate.of(2027, 9, 22), LocalDate.of(2027, 9, 23)));
        form.setReason("another group");
        assertTrue(hotel.saveBlock(accId, form));

        final DayCell night = dayOf(hotel.calendar(accId, MONTH), LocalDate.of(2027, 9, 22));
        assertEquals(night.getRoomsBlocked(), 2);
        assertEquals(night.getConflicts(), 1, "101 is both blocked and slept in");
        assertEquals(night.getRoomsOccupied(), 1);
    }

    @Test
    public void anUnplacedStayCountsItsPeopleButNoRoom() throws IOException {
        final String accId = hotelWithRooms();
        final Person guest = savedPerson("Lux");
        final Trip trip = tripWith("Unplaced trip", guest);
        reserveInto(trip, accId, guest, null);
        final DayCell night = dayOf(hotel.calendar(accId, MONTH), LocalDate.of(2027, 9, 22));
        assertEquals(night.getPeople(), 1);
        assertEquals(night.getUnplaced(), 1, "work still to do, not a shortage");
        assertEquals(night.getRoomsOccupied(), 0);
    }

    /** The tenancy line: the hotel sees whose trip it is only when the caller could see that trip anyway. */
    @Test
    public void theDayDetailNamesTripsNotGuests() throws IOException {
        final String accId = hotelWithRooms();
        final Person guest = savedPerson("Mira");
        final Trip trip = tripWith("Named trip", guest);
        reserveInto(trip, accId, guest, roomId(accId, 0));

        final DayDetail mine = hotel.dayDetail(accId, LocalDate.of(2027, 9, 22));
        assertEquals(mine.getRooms().size(), 2);
        final DayRoom room101 = mine.getRooms().get(0);
        assertEquals(room101.getRoomNumber(), "101");
        assertEquals(room101.getStays().size(), 1);
        assertEquals(room101.getStays().get(0).getTripLabel(), "Named trip", "a site admin sees the trip");
        assertEquals(room101.getStays().get(0).getPeople(), 1);
        assertTrue(mine.getRooms().get(1).getStays().isEmpty());
        assertTrue(mine.toString().indexOf("Mira") < 0, "no guest name reaches the hotel's view");

        // The hotel's own manager, who is on no trip: they see that a room is taken, never by whom.
        final Person keeper = savedPerson("Nils");
        assertTrue(new PrivilegeCommands().savePrivilege(new PrivilegeCommands()
                .getOrCreate(PrivilegeCommands.ACCOMMODATION_ADMIN, accId, "hotel staff")
                .withNewPerson(keeper.getId())));
        final HotelCommands staff = new HotelCommands(() -> TestCallers.person(keeper.getId()));
        final DayDetail theirs = staff.dayDetail(accId, LocalDate.of(2027, 9, 22));
        assertEquals(theirs.getRooms().get(0).getStays().get(0).getTripLabel(),
                "another organization's trip", "the hotel is shared; the guest list is not");
        assertEquals(theirs.getRooms().get(0).getStays().get(0).getPeople(), 1, "the count still reaches them");
    }

    @Test
    public void theScheduleCarriesADayPillAndABlockPill() throws IOException {
        final String accId = hotelWithRooms();
        final Person guest = savedPerson("Otto");
        final Trip trip = tripWith("Schedule trip", guest);
        reserveInto(trip, accId, guest, roomId(accId, 0));
        final BlockForm form = new BlockForm();
        form.setRoomIds(List.of(roomId(accId, 1)));
        form.setRange(List.of(LocalDate.of(2027, 9, 27), LocalDate.of(2027, 9, 29)));
        form.setReason("maintenance");
        assertTrue(hotel.saveBlock(accId, form));

        final org.primefaces.model.LazyScheduleModel model =
                (org.primefaces.model.LazyScheduleModel) hotel.schedule(accId);
        model.loadEvents(LocalDate.of(2027, 9, 1).atStartOfDay(), LocalDate.of(2027, 10, 1).atStartOfDay());
        final List<ScheduleEvent<?>> events = model.getEvents();
        final ScheduleEvent<?> day = events.stream().filter(e -> e.getId().equals("day-2027-09-22"))
                .findFirst().orElseThrow();
        assertEquals(day.getTitle(), "1/2 rooms · 1 person");
        assertTrue(day.getStyleClass().contains("cal-some"), day.getStyleClass());
        assertTrue(day.isAllDay());
        final ScheduleEvent<?> block = events.stream().filter(e -> e.getId().startsWith("block-"))
                .findFirst().orElseThrow();
        assertTrue(block.getTitle().contains("102: maintenance"), block.getTitle());
        assertEquals(block.getStyleClass(), "cal-block");
        assertEquals(block.getStartDate(), LocalDate.of(2027, 9, 27).atStartOfDay());
        assertTrue(events.stream().noneMatch(e -> e.getId().equals("day-2027-09-28")),
                "a day with nobody on it gets no pill; the block's own pill says why it is unavailable");
    }

    /** A range the calendar asks for that holds nothing answers nothing, rather than a pill of zeroes. */
    @Test
    public void aQuietRangeProducesNoPills() {
        final String accId = hotelWithRooms();
        final org.primefaces.model.LazyScheduleModel model =
                (org.primefaces.model.LazyScheduleModel) hotel.schedule(accId);
        model.loadEvents(LocalDate.of(2027, 1, 1).atStartOfDay(), LocalDate.of(2027, 2, 1).atStartOfDay());
        assertTrue(model.getEvents().isEmpty());
        final org.primefaces.model.LazyScheduleModel refused =
                (org.primefaces.model.LazyScheduleModel) new HotelCommands(
                        () -> TestCallers.person(Person.Id.newInstance())).schedule(accId);
        refused.loadEvents(LocalDate.of(2027, 9, 1).atStartOfDay(), LocalDate.of(2027, 10, 1).atStartOfDay());
        assertTrue(refused.getEvents().isEmpty(), "and a caller who may not read it sees nothing at all");
    }

    @Test
    public void theMonthFallsBackToNowAndOpensOnItsFirstDay() {
        final String now = YearMonth.now().toString();
        assertEquals(hotel.monthOrNow(""), now);
        assertEquals(hotel.monthOrNow(null), now);
        assertEquals(hotel.monthOrNow("not-a-month"), now);
        assertEquals(hotel.monthOrNow("2027-09"), "2027-09");
        assertEquals(hotel.monthStart("2027-09"), LocalDate.of(2027, 9, 1), "initialDate needs a date, and a "
                + "String there throws inside the schedule renderer, mid-render");
        assertEquals(hotel.monthStart("nonsense"), YearMonth.now().atDay(1));
        assertTrue(hotel.calendar("no-such-hotel", MONTH).getDays().isEmpty());
        assertTrue(hotel.dayDetail("no-such-hotel", LocalDate.now()).getRooms().isEmpty());
        assertTrue(hotel.dayDetail(hotelWithRooms(), null).getRooms().isEmpty());
    }

    @Test
    public void theLoadBandsFollowHowMuchOfTheHotelIsTaken() {
        assertEquals(HotelCommands.load(0, 10), "cal-free");
        assertEquals(HotelCommands.load(1, 10), "cal-some");
        assertEquals(HotelCommands.load(8, 10), "cal-most");
        assertEquals(HotelCommands.load(10, 10), "cal-full");
        assertEquals(HotelCommands.load(11, 10), "cal-full", "over is still full");
        assertEquals(HotelCommands.load(3, 0), "cal-free", "a hotel with no rooms");
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Counts fixtures so every hotel's discoverable details differ. Duplicate detection compares name,
     * email, phone and street, and a hotel it thinks is a duplicate is REFUSED: a hash of a random tag
     * collided across a full-suite run, which showed up as a blank id three calls later.
     */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private String hotelWithRooms() {
        final AccommodationForm form = new AccommodationForm();
        final String tag = RandomData.genAlpha(8) + SEQ.incrementAndGet();
        form.setName("Hotel " + tag);
        form.setEmail(tag.toLowerCase(java.util.Locale.ROOT) + "@hotel.example");
        form.setPhone("+1 503 " + SEQ.incrementAndGet() + " " + RandomData.genAlpha(4));
        form.setStreet(SEQ.incrementAndGet() + " " + tag + " St");
        form.setCity("Portland");
        final String accId = admin.saveAccommodation(form, orgId);
        assertTrue(accId != null && !accId.isBlank(), "the hotel fixture was refused as a duplicate");
        final RoomTypeForm type = new RoomTypeForm();
        type.setName("Double");
        type.setMinPeople(1);
        type.setMaxPeople(2);
        assertTrue(admin.saveRoomType(accId, type));
        final String typeId = admin.roomTypeRows(accId).get(0).getId();
        assertEquals(admin.bulkAddRooms(accId, "", 101, 102, "1", typeId), 2);
        return accId;
    }

    private String roomId(final String accId, final int index) {
        return admin.roomRows(accId).get(index).getId();
    }

    private Trip tripWith(final String title, final Person person) throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title(title)
                .startDate(CHECK_IN).endDate(CHECK_OUT.plusDays(2))
                .people(new ArrayList<>(List.of(person.getId()))).build();
        trip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private void reserveInto(final Trip trip, final String accId, final Person person, final String roomId) {
        final OfferForm offerForm = new OfferForm();
        offerForm.setName("Room " + RandomData.genAlpha(4));
        offerForm.setAccommodationId(accId);
        offerForm.setRoomTypeIds(new ArrayList<>(List.of(admin.roomTypeRows(accId).get(0).getId())));
        offerForm.setTripEventId(OfferForm.NEW_EVENT);
        offerForm.setPricingModel("PER_ROOM");
        offerForm.setNightlyPrice(100.0);
        offerForm.setDefaultStart(CHECK_IN);
        offerForm.setDefaultEnd(CHECK_OUT);
        assertTrue(admin.saveOffer(trip.getId(), offerForm));
        final ReservationOffer offer = admin.getOffers(trip.getId()).stream()
                .filter(o -> o.getName().equals(offerForm.getName())).findFirst().orElseThrow();
        final ReservationForm res = admin.reservationFormFor(trip.getId(), null);
        res.setOfferId(offer.getId().getValue());
        res.setPersonIds(List.of(person.getId().getValue()));
        res.setStart(CHECK_IN);
        res.setEnd(CHECK_OUT);
        res.setRoomId(roomId);
        assertEquals(admin.createReservations(trip.getId(), res), 1);
    }

    private static Person savedPerson(final String first) throws IOException {
        final Person person = Person.builder().first(first).last(RandomData.genAlpha(8))
                .email(first.toLowerCase(java.util.Locale.ROOT) + "." + RandomData.genAlpha(8)
                        .toLowerCase(java.util.Locale.ROOT) + "@example.com")
                .birthdate(LocalDate.of(1990, 5, 5)).build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private static DayCell dayOf(final HotelCalendar calendar, final LocalDate date) {
        return calendar.getDays().stream().filter(d -> d.getDate().equals(date)).findFirst().orElseThrow();
    }

    /** The two dialogs that arrive with their subject already chosen: a room, or a day. */
    @Test
    public void aBlockFormCanArriveAimedAtARoomOrAtADay() {
        final String accId = hotelWithRooms();
        final BlockForm forRoom = hotel.quickBlockFormFor(accId, roomId(accId, 0));
        assertEquals(forRoom.getRoomIds(), List.of(roomId(accId, 0)));
        assertTrue(forRoom.getRange().isEmpty(), "the room is known, the nights are not");
        assertTrue(hotel.quickBlockFormFor(accId, " ").getRoomIds().isEmpty());

        final BlockForm forDay = hotel.blockFormOn(accId, LocalDate.of(2027, 9, 22));
        assertEquals(forDay.start(), LocalDate.of(2027, 9, 22));
        assertEquals(forDay.end(), LocalDate.of(2027, 9, 23), "one night, free again the next morning");
        assertTrue(hotel.blockFormOn(accId, null).getRange().isEmpty());
        assertTrue(hotel.blockFormFor(accId, "no-such-block").getRoomIds().isEmpty());
    }

    @Test
    public void theRoomPickerNamesEveryRoomWithItsType() {
        final String accId = hotelWithRooms();
        final java.util.Map<String, String> choices = hotel.roomChoices(accId);
        assertEquals(choices.size(), 2);
        assertEquals(choices.get(roomId(accId, 0)), "101 (Double)");
        assertTrue(hotel.roomChoices("no-such-hotel").isEmpty());
        assertTrue(hotel.roomChoices(null).isEmpty());
        assertTrue(hotel.roomChoices(" ").isEmpty());
    }

    /** Blocks list soonest first, and a past one stays, faded, so a mistake remains findable. */
    @Test
    public void blocksAreListedSoonestFirstAndPastOnesAreMarked() {
        final String accId = hotelWithRooms();
        saveBlock(accId, LocalDate.of(2027, 9, 25), LocalDate.of(2027, 9, 27), "later");
        saveBlock(accId, LocalDate.of(2027, 9, 21), LocalDate.of(2027, 9, 22), "sooner");
        saveBlock(accId, LocalDate.now().minusDays(10), LocalDate.now().minusDays(8), "over");
        final List<BlockRow> rows = hotel.blockRows(accId);
        assertEquals(rows.size(), 3);
        assertEquals(rows.get(0).getReason(), "over", "the oldest first");
        assertEquals(rows.get(1).getReason(), "sooner");
        assertEquals(rows.get(2).getReason(), "later");
        assertTrue(rows.get(0).isPast());
        assertFalse(rows.get(2).isPast());
    }

    /** The calendar's day listener hands the page a SCALAR, because a view may hold nothing else. */
    @Test
    public void theCalendarListenerPutsAScalarInTheView() {
        final java.util.Map<String, Object> view = new java.util.HashMap<>();
        hotel.onDateSelect(selectEvent(LocalDateTime.of(2027, 9, 22, 0, 0)), view::put);
        assertEquals(view.get("calDay"), LocalDate.of(2027, 9, 22));
        assertNull(view.get("calBlockId"));

        hotel.onDateSelect(selectEvent(null), view::put);
        assertNull(view.get("calDay"));
    }

    @Test
    public void aBlockOnAHotelThatVanishedIsRefusedRatherThanThrown() {
        final BlockForm form = new BlockForm();
        form.setRoomIds(List.of("r-1"));
        form.setRange(List.of(LocalDate.of(2027, 9, 22), LocalDate.of(2027, 9, 24)));
        assertFalse(hotel.saveBlock(null, form));
        assertFalse(hotel.saveBlock(hotelWithRooms(), null));
    }

    private void saveBlock(final String accId, final LocalDate from, final LocalDate to, final String reason) {
        final BlockForm form = new BlockForm();
        form.setRoomIds(List.of(roomId(accId, 0)));
        form.setRange(List.of(from, to));
        form.setReason(reason);
        assertTrue(hotel.saveBlock(accId, form));
    }

    private static SelectEvent<LocalDateTime> selectEvent(final LocalDateTime when) {
        return new SelectEvent<>(new UIPanel(), new AjaxBehavior(), when);
    }


}
