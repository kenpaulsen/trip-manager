package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.LodgingViews.BlockForm;
import org.paulsens.trip.action.LodgingViews.BlockRow;
import org.paulsens.trip.action.LodgingViews.DayCell;
import org.paulsens.trip.action.LodgingViews.DayDetail;
import org.paulsens.trip.action.LodgingViews.DayRoom;
import org.paulsens.trip.action.LodgingViews.DayStay;
import org.paulsens.trip.action.LodgingViews.HotelCalendar;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomBlock;
import org.paulsens.trip.model.RoomType;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.pay.LodgingPricing;
import org.primefaces.event.SelectEvent;
import org.primefaces.model.DefaultScheduleEvent;
import org.primefaces.model.DefaultScheduleModel;
import org.primefaces.model.LazyScheduleModel;
import org.primefaces.model.ScheduleEvent;
import org.primefaces.model.ScheduleModel;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import static org.paulsens.trip.action.PageFeedback.info;
import static org.paulsens.trip.action.PageFeedback.refuse;

/**
 * The HOTEL's own view of itself, exposed to pages as {@code #{hotel}}: which nights its rooms are not
 * available to us ({@link RoomBlock}), and how full it is on any day across EVERY trip staying there.
 * Feature doc: {@code docs/lodging.md}, "The hotel's inventory".
 *
 * <p>Separate from {@link LodgingCommands} because the question is different. That bean answers "where do I
 * put this person on my trip"; this one answers "what does my hotel look like in September", which crosses
 * trips and organizations. Accommodations are the one GLOBAL entity, so this is the one place a read spans
 * the tenancy boundary -- and it is why nothing here carries a guest's NAME. A hotel sees counts, dates, and
 * whose trip it is when the caller could have seen that trip anyway; the names stay on each trip's own board.
 *
 * <p>Gates come from {@link LodgingCommands}: {@code canEditAccommodation} to change a block,
 * {@code canOpenLodgingAdmin} to read the calendar at all.
 */
@Slf4j
@Named("hotel")
@ApplicationScoped
public class HotelCommands {
    /** A block may not take out more rooms than the hotel has; a runaway multi-select is a mistake. */
    static final int MAX_BLOCK_ROOMS = LodgingCommands.MAX_ROOMS;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    /** Above this share of the rooms the day reads as "most"; at or above full it reads as full. */
    private static final double MOST = 0.8;

    private final Supplier<Caller> callerSource;
    private final Supplier<LodgingCommands> lodgingSource;
    private final Supplier<AuditCommands> auditSource;

    public HotelCommands() {
        this(Caller::current);
    }

    /** Test seam (the {@link LodgingCommands} pattern): {@code Caller.current()} needs a FacesContext. */
    public HotelCommands(final Supplier<Caller> callerSource) {
        this(callerSource, () -> new LodgingCommands(callerSource), AuditCommands::new);
    }

    HotelCommands(final Supplier<Caller> callerSource, final Supplier<LodgingCommands> lodgingSource,
            final Supplier<AuditCommands> auditSource) {
        this.callerSource = callerSource;
        this.lodgingSource = lodgingSource;
        this.auditSource = auditSource;
    }

    // ================================================================== blocks

    /** The hotel's blocks, soonest first; past ones stay, faded, so a mistake remains findable. */
    public List<BlockRow> blockRows(final String accId) {
        final List<BlockRow> rows = new ArrayList<>();
        final Accommodation acc = accommodation(accId);
        if (acc == null || !canRead(accId)) {
            return rows;
        }
        final LodgingCommands lodging = lodgingSource.get();
        for (final RoomBlock block : sortedBlocks(acc)) {
            rows.add(lodging.blockRow(acc, block));
        }
        return rows;
    }

    /** The dialog's form for an existing block, or a blank one for a new block. Never null. */
    public BlockForm blockFormFor(final String accId, final String blockId) {
        final BlockForm form = new BlockForm();
        final RoomBlock block = findBlock(accId, blockId);
        if (block != null) {
            form.setId(block.getId().getValue());
            form.setRoomIds(block.getRoomIds());
            form.setRange(datesOf(block.getStart(), block.getEnd()));
            form.setReason(block.getReason());
        }
        return form;
    }

    /** A new block already aimed at one room -- the board's room dialog, where the room is the context. */
    public BlockForm quickBlockFormFor(final String accId, final String roomId) {
        final BlockForm form = new BlockForm();
        if (roomId != null && !roomId.isBlank()) {
            form.setRoomIds(List.of(roomId));
        }
        return form;
    }

    /** A new block already aimed at one night -- the calendar, where the day is the context. */
    public BlockForm blockFormOn(final String accId, final LocalDate date) {
        final BlockForm form = new BlockForm();
        if (date != null) {
            form.setRange(List.of(date, date.plusDays(1)));
        }
        return form;
    }

    /**
     * Creates or edits a block. Refuses a block with no rooms or no nights: an empty one would look applied
     * and hold nothing.
     */
    public boolean saveBlock(final String accId, final BlockForm form) {
        if (!canEdit(accId)) {
            return refuse("Not allowed: only this hotel's managers can change what it has available.");
        }
        final Accommodation acc = accommodation(accId);
        if (acc == null || form == null) {
            return refuse("That accommodation no longer exists.");
        }
        final String problem = blockProblem(acc, form);
        if (problem != null) {
            return refuse(problem);
        }
        final RoomBlock block = findBlock(accId, form.getId());
        final RoomBlock saving = (block == null)
                ? RoomBlock.builder().accommodationId(acc.getId()).createdBy(caller().personId())
                        .created(LocalDateTime.now()).build()
                : block;
        saving.setRoomIds(form.getRoomIds());
        saving.setStart(form.start());
        saving.setEnd(form.end());
        saving.setReason(form.getReason());
        if (!store(saving)) {
            return false;
        }
        final String what = (block == null ? "Blocked rooms " : "Changed the block on rooms ")
                + roomLabels(acc, saving) + ": " + RoomAvailability.describe(saving);
        audit(acc, what);
        info((block == null ? "Blocked " : "Updated the block on ") + roomLabels(acc, saving) + ", "
                + RoomAvailability.nights(saving) + ".");
        return true;
    }

    /** Frees the rooms a block was holding. The rooms themselves are untouched. */
    public boolean deleteBlock(final String accId, final String blockId) {
        if (!canEdit(accId)) {
            return refuse("Not allowed: only this hotel's managers can change what it has available.");
        }
        final Accommodation acc = accommodation(accId);
        final RoomBlock block = findBlock(accId, blockId);
        if (acc == null || block == null) {
            return refuse("That block is already gone.");
        }
        if (!Boolean.TRUE.equals(DAO.getInstance().deleteRoomBlock(acc.getId(), block.getId()))) {
            return refuse("The block could not be removed.");
        }
        audit(acc, "Removed the block on rooms " + roomLabels(acc, block) + ": "
                + RoomAvailability.describe(block));
        info("Rooms " + roomLabels(acc, block) + " are available again " + RoomAvailability.nights(block) + ".");
        return true;
    }

    private String blockProblem(final Accommodation acc, final BlockForm form) {
        if (form.getRoomIds().isEmpty()) {
            return "Choose at least one room to block.";
        }
        if (form.getRoomIds().size() > MAX_BLOCK_ROOMS) {
            return "A block holds at most " + MAX_BLOCK_ROOMS + " rooms.";
        }
        for (final String roomId : form.getRoomIds()) {
            if (acc.room(roomId) == null) {
                return "One of those rooms is not at " + acc.getName() + " any more; reopen the dialog.";
            }
        }
        if (form.start() == null || form.end() == null || !form.end().isAfter(form.start())) {
            return "Pick the first blocked night and the morning the rooms are free again.";
        }
        return null;
    }

    private boolean store(final RoomBlock block) {
        try {
            return Boolean.TRUE.equals(DAO.getInstance().saveRoomBlock(block))
                    || refuse("The block could not be saved.");
        } catch (final ConditionalCheckFailedException ex) {
            return refuse("Saved by someone else: reload the page and try again.");
        } catch (final IOException ex) {
            log.error("Unable to save room block {}", block.getId(), ex);
            return refuse("The block could not be saved: " + ex.getMessage());
        }
    }

    // ================================================================== the availability calendar

    /**
     * How full the hotel is on every day of {@code month}, over every trip staying there. One index query
     * plus the hotel's blocks; nothing is stored, so the page may build it per render.
     *
     * @param month ISO {@code yyyy-MM}; blank or unparseable reads as the current month.
     */
    public HotelCalendar calendar(final String accId, final String month) {
        final HotelCalendar calendar = new HotelCalendar();
        final YearMonth when = monthOf(month);
        calendar.setMonth(when.format(MONTH));
        final Accommodation acc = accommodation(accId);
        if (acc == null || !canRead(accId)) {
            return calendar;
        }
        calendar.setAccommodationId(acc.getId().getValue());
        calendar.setAccommodationName(acc.getName());
        calendar.setRoomsTotal(acc.getRooms().size());
        final LocalDate first = when.atDay(1);
        final LocalDate afterLast = when.plusMonths(1).atDay(1);
        final List<Reservation> stays = staysOver(acc, first.atStartOfDay());
        final List<RoomBlock> blocks = blocks(acc);
        for (LocalDate night = first; night.isBefore(afterLast); night = night.plusDays(1)) {
            calendar.getDays().add(cellFor(acc, night, stays, blocks));
        }
        return calendar;
    }

    /** One day of the hotel: who holds each room, and what is booked into it. */
    public DayDetail dayDetail(final String accId, final LocalDate date) {
        final DayDetail detail = new DayDetail();
        final Accommodation acc = accommodation(accId);
        if (acc == null || date == null || !canRead(accId)) {
            return detail;
        }
        detail.setDate(date);
        final List<Reservation> stays = staysOver(acc, date.atStartOfDay());
        final List<RoomBlock> blocks = blocks(acc);
        detail.setCell(cellFor(acc, date, stays, blocks));
        for (final Room room : LodgingCommands.sortedRooms(acc)) {
            detail.getRooms().add(dayRoom(acc, room, date, stays, blocks));
        }
        return detail;
    }

    private DayRoom dayRoom(final Accommodation acc, final Room room, final LocalDate night,
            final List<Reservation> stays, final List<RoomBlock> blocks) {
        final DayRoom row = new DayRoom();
        row.setRoomId(room.getId());
        row.setRoomNumber(room.getRoomNumber());
        row.setFloor(room.getFloor());
        final RoomType type = acc.roomType(room.getRoomTypeId());
        row.setTypeName(type == null ? "" : type.getName());
        for (final RoomBlock block : blocks) {
            if (block.covers(room.getId(), night)) {
                row.setBlocked(true);
                row.setBlockReason(block.getReason());
            }
        }
        for (final Reservation res : stays) {
            if (res.isActive() && room.getId().equals(res.getRoomId()) && sleepsOn(res, night)) {
                row.getStays().add(stayOf(res));
            }
        }
        return row;
    }

    /** What the hotel may be told about one trip's stay: whose trip, how many, when. Never who. */
    private DayStay stayOf(final Reservation res) {
        final Trip trip = DAO.getInstance().getTrip(res.getTripId(), Cached.YES).orElse(null);
        final boolean mine = trip != null && lodgingSource.get().canManageTripLodging(trip.getId());
        return new DayStay(label(trip, mine), orgName(trip, mine), res.getOccupants().size(), res.getStart(),
                res.getEnd());
    }

    private static String label(final Trip trip, final boolean mine) {
        if (trip == null) {
            return "a trip that no longer exists";
        }
        return mine ? trip.getTitle() : "another organization's trip";
    }

    private String orgName(final Trip trip, final boolean mine) {
        if (trip == null || !mine || trip.getOrgId() == null) {
            return null;
        }
        return DAO.getInstance().getOrganization(Organization.Id.from(trip.getOrgId()), Cached.YES)
                .map(Organization::getName).orElse(null);
    }

    private DayCell cellFor(final Accommodation acc, final LocalDate night, final List<Reservation> stays,
            final List<RoomBlock> blocks) {
        final Set<String> occupiedRooms = new LinkedHashSet<>();
        final Set<Person.Id> people = new LinkedHashSet<>();
        int unplaced = 0;
        for (final Reservation res : stays) {
            if (!res.isActive() || !sleepsOn(res, night)) {
                continue;
            }
            people.addAll(res.getOccupants());
            if (res.getRoomId() == null) {
                unplaced += res.getOccupants().size();
            } else {
                occupiedRooms.add(res.getRoomId());
            }
        }
        final Set<String> blockedRooms = new LinkedHashSet<>();
        for (final RoomBlock block : blocks) {
            for (final String roomId : block.getRoomIds()) {
                if (block.covers(roomId, night) && acc.room(roomId) != null) {
                    blockedRooms.add(roomId);
                }
            }
        }
        int conflicts = 0;
        for (final String roomId : blockedRooms) {
            if (occupiedRooms.contains(roomId)) {
                conflicts++;
            }
        }
        final int total = acc.getRooms().size();
        return new DayCell(night, occupiedRooms.size(), total, blockedRooms.size(), people.size(), unplaced,
                conflicts, load(occupiedRooms.size() + blockedRooms.size(), total));
    }

    /** The CSS band; the pill always carries the numbers too, so the colour never stands alone. */
    static String load(final int taken, final int total) {
        if (total <= 0 || taken == 0) {
            return "cal-free";
        }
        if (taken >= total) {
            return "cal-full";
        }
        return (taken >= total * MOST) ? "cal-most" : "cal-some";
    }

    /**
     * The calendar as PrimeFaces renders it: an all-day summary pill per day that has anybody or anything on
     * it, plus one pill per block spanning its nights.
     *
     * <p>LAZY, and the laziness is the point rather than an optimization. The schedule fetches its events in
     * its own request, carrying the range it is about to draw, and paging the view is a client-side move the
     * server is never told about in a way it can trust: the {@code viewChange} event hands over the VIEW's
     * name, not a date. Loading what the calendar asks for is therefore the only way the month a user pages
     * to has anything in it, and it drops the stored month from the data path entirely.
     */
    public ScheduleModel schedule(final String accId) {
        return new HotelSchedule(this, accId);
    }

    /** Fills {@link #schedule}'s model with whatever range the calendar is about to draw. */
    private static final class HotelSchedule extends LazyScheduleModel {
        @Serial
        private static final long serialVersionUID = 1L;
        private final transient HotelCommands hotel;
        private final String accId;

        private HotelSchedule(final HotelCommands hotel, final String accId) {
            this.hotel = hotel;
            this.accId = accId;
        }

        @Override
        public void loadEvents(final LocalDateTime start, final LocalDateTime end) {
            hotel.fill(this, accId, start.toLocalDate(), end.toLocalDate());
        }
    }

    /**
     * The pills for every night in {@code [from, to)}: one summary per day anybody is here, one per block.
     * The grid a month view draws spills into the months either side, which is why this works in days.
     */
    void fill(final DefaultScheduleModel model, final String accId, final LocalDate from, final LocalDate to) {
        final Accommodation acc = accommodation(accId);
        if (acc == null || !canRead(accId)) {
            return;
        }
        final List<Reservation> stays = staysOver(acc, from.atStartOfDay());
        final List<RoomBlock> blocks = blocks(acc);
        for (LocalDate night = from; night.isBefore(to); night = night.plusDays(1)) {
            final DayCell day = cellFor(acc, night, stays, blocks);
            if (day.getPeople() > 0 || day.getRoomsOccupied() > 0) {
                model.addEvent(summaryEvent(day));
            }
        }
        for (final RoomBlock block : sortedBlocks(acc)) {
            // A block reaching past the drawn range still gets ONE pill, spanning its own nights.
            if (block.overlapsNights(from, to) && model.getEvent("block-" + block.getId().getValue()) == null) {
                model.addEvent(blockEvent(acc, block));
            }
        }
    }

    private static ScheduleEvent<String> summaryEvent(final DayCell day) {
        return DefaultScheduleEvent.<String>builder()
                .id("day-" + day.getDate())
                .title(day.getSummary())
                .startDate(day.getDate().atStartOfDay())
                .endDate(day.getDate().plusDays(1).atStartOfDay())
                .allDay(true)
                .editable(false)
                .draggable(false)
                .resizable(false)
                .styleClass("cal-day " + day.getLoad() + (day.getConflicts() > 0 ? " cal-conflict" : ""))
                .data(day.getDate().toString())
                .build();
    }

    private ScheduleEvent<String> blockEvent(final Accommodation acc, final RoomBlock block) {
        final String reason = (block.getReason() == null || block.getReason().isBlank())
                ? "not available" : block.getReason();
        return DefaultScheduleEvent.<String>builder()
                .id("block-" + block.getId().getValue())
                .title("⊘ " + roomLabels(acc, block) + ": " + reason)
                .startDate(block.getStart().atStartOfDay())
                .endDate(block.getEnd().atStartOfDay())
                .allDay(true)
                .editable(false)
                .draggable(false)
                .resizable(false)
                .styleClass("cal-block")
                .data(block.getId().getValue())
                .build();
    }

    // ---------------------------------------------------------------- the calendar's ajax events
    // A SelectEvent carries its payload as an object, which a JSFT command script cannot read, so these
    // three listeners put the scalar the dialogs need into the view (the ChatCommands precedent).

    /** Clicking an empty day: open that day's breakdown. */
    public void onDateSelect(final SelectEvent<LocalDateTime> event) {
        onDateSelect(event, HotelCommands::putInView);
    }

    // The sink is a seam: a test has no FacesContext to hold a view map, and what this promises is WHICH
    // scalar lands under which key, not how the view stores it.

    void onDateSelect(final SelectEvent<LocalDateTime> event, final BiConsumer<String, Object> view) {
        final LocalDateTime when = event.getObject();
        view.accept("calDay", when == null ? null : when.toLocalDate());
        view.accept("calBlockId", null);
    }

    /** The month the calendar OPENS on, as the scalar the view holds; blank or unparseable reads as now. */
    public String monthOrNow(final String month) {
        return monthOf(month).format(MONTH);
    }

    /**
     * The first day of the month shown, for the calendar's {@code initialDate}. A {@code LocalDate}, not the
     * {@code yyyy-MM} scalar the view holds: the schedule renderer casts that attribute to a LocalDate, and
     * a String there throws MID-RENDER, which arrives as a page that never finishes loading.
     */
    public LocalDate monthStart(final String month) {
        return monthOf(month).atDay(1);
    }


    // ================================================================== plumbing

    private static YearMonth monthOf(final String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month.trim(), MONTH);
        } catch (final DateTimeParseException ex) {
            return YearMonth.now();
        }
    }

    /** Whether the reservation covers the night of {@code night} ({@code [start, end)} on dates). */
    private static boolean sleepsOn(final Reservation res, final LocalDate night) {
        return !LodgingPricing.nightsOf(res.getStart(), res.getEnd()).isEmpty()
                && !night.isBefore(res.getStart().toLocalDate()) && night.isBefore(res.getEnd().toLocalDate());
    }

    private List<Reservation> staysOver(final Accommodation acc, final LocalDateTime from) {
        return lodgingSource.get().atHotel(acc.getId(), from);
    }

    private List<RoomBlock> blocks(final Accommodation acc) {
        return DAO.getInstance().getRoomBlocks(acc.getId(), Cached.NO);
    }

    private List<RoomBlock> sortedBlocks(final Accommodation acc) {
        final List<RoomBlock> blocks = new ArrayList<>(blocks(acc));
        blocks.sort((a, b) -> {
            if (a.getStart() == null || b.getStart() == null) {
                return (a.getStart() == null) ? 1 : -1;
            }
            return a.getStart().compareTo(b.getStart());
        });
        return blocks;
    }

    private RoomBlock findBlock(final String accId, final String blockId) {
        final Accommodation acc = accommodation(accId);
        if (acc == null || blockId == null || blockId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getRoomBlock(acc.getId(), RoomBlock.Id.from(blockId), Cached.NO).orElse(null);
    }

    private String roomLabels(final Accommodation acc, final RoomBlock block) {
        final List<String> labels = new ArrayList<>();
        for (final String roomId : block.getRoomIds()) {
            final String label = acc.roomLabel(roomId);
            labels.add(label == null ? roomId : label);
        }
        return String.join(", ", labels);
    }

    /** The room picker for the block dialog: every room, number first. */
    public Map<String, String> roomChoices(final String accId) {
        final Map<String, String> choices = new LinkedHashMap<>();
        final Accommodation acc = accommodation(accId);
        if (acc == null) {
            return choices;
        }
        for (final Room room : LodgingCommands.sortedRooms(acc)) {
            final RoomType type = acc.roomType(room.getRoomTypeId());
            choices.put(room.getId(), room.getRoomNumber() + (type == null ? "" : " (" + type.getName() + ")"));
        }
        return choices;
    }

    private static List<LocalDate> datesOf(final LocalDate start, final LocalDate end) {
        return (start == null || end == null) ? List.of() : List.of(start, end);
    }

    private Accommodation accommodation(final String accId) {
        if (accId == null || accId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getAccommodation(Accommodation.Id.from(accId.trim()), Cached.NO).orElse(null);
    }

    /** May change this hotel's availability: the same people who may edit its rooms. */
    public boolean canEdit(final String accId) {
        return lodgingSource.get().canEditAccommodation(accId);
    }

    /** May see the hotel's occupancy: its own managers, and the lodging admins who book into it. */
    public boolean canRead(final String accId) {
        return canEdit(accId) || lodgingSource.get().canOpenLodgingAdmin();
    }

    private void audit(final Accommodation acc, final String what) {
        auditSource.get().lodging(AuditEventBuilder.TARGET_ACCOMMODATION, acc.getId().getValue(), null,
                what + " at '" + acc.getName() + "'", caller().auditActor());
    }

    private Caller caller() {
        return callerSource.get();
    }

    private static void putInView(final String key, final Object value) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.getViewRoot() != null) {
            ctx.getViewRoot().getViewMap().put(key, value);
        }
    }

    private static String nullSafe(final String value) {
        return (value == null) ? "" : value;
    }
}
