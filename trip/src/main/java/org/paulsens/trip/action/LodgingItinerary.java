package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.paulsens.trip.action.LodgingViews.ItineraryRow;
import org.paulsens.trip.action.LodgingViews.RoomingRow;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomType;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;

/**
 * What the trip's OTHER pages derive from its reservations: the rooming list ({@code trip/rooms.xhtml} and
 * the reports) and the itinerary's rows ({@code trip/itinerary.xhtml}, its print page and the badge).
 *
 * <p>Split out of {@link LodgingCommands}, which answers "where do I put this person"; this answers "what do
 * the traveller's own pages say about where they sleep". Pages still bind to {@code #{lodging}}, which
 * delegates here -- the seam is for reading, not for the EL.
 *
 * <p>A person may hold SEVERAL stays on one trip (they leave and come back, or they change rooms part-way),
 * so both answers are per-stay: the rooming list emits a row per occupant per stay, and the itinerary emits
 * a row per stay in date order rather than one row spanning the gap between them.
 */
final class LodgingItinerary {
    private final LodgingCommands lodging;
    private final TripCommands trips;

    LodgingItinerary(final LodgingCommands lodging, final TripCommands trips) {
        this.lodging = lodging;
        this.trips = trips;
    }

    /** The rooming list: everyone with a reservation, room then name; the unreserved at the bottom. */
    public List<RoomingRow> roomingList(final String tripId) {
        final List<RoomingRow> rows = new ArrayList<>();
        final Trip trip = trips.getTrip(tripId);
        if (!tripId.equals(trip.getId())) {
            return rows;
        }
        final Set<Person.Id> reserved = new HashSet<>();
        for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.YES)) {
            if (!res.isActive()) {
                continue;
            }
            final Accommodation acc = lodging.findAccommodation(LodgingCommands.idValue(res.getAccommodationId()));
            final ReservationOffer offer = lodging.findOffer(tripId, LodgingCommands.idValue(res.getOfferId()));
            final Room room = (acc == null) ? null : acc.room(res.getRoomId());
            // The ASSIGNED room's type when there is one (a Triple stays a Triple), else the offer's.
            final String typeId = (room != null) ? room.getRoomTypeId()
                    : (offer == null ? null : offer.firstRoomTypeId());
            final RoomType type = (acc == null) ? null : acc.roomType(typeId);
            for (final Person.Id person : res.getOccupants()) {
                reserved.add(person);
                rows.add(new RoomingRow(person.getValue(), lodging.displayName(person), cellOf(person),
                        acc == null ? "" : acc.getName(), room == null ? "" : nullSafe(room.getFloor()),
                        room == null ? "" : room.getRoomNumber(), type == null ? "" : type.getName(),
                        res.getStart(), res.getEnd(), res.getNotes(), answersLine(trip, person), true));
            }
        }
        rows.sort((a, b) -> {
            final int byRoom = LodgingCommands.naturalCompare(a.getRoom(), b.getRoom());
            return (byRoom != 0) ? byRoom : nullSafe(a.getName()).compareToIgnoreCase(nullSafe(b.getName()));
        });
        // The unreserved: their room, if any, is the LEGACY free-text one (the person_data row the old
        // rooms page wrote), so past trips keep printing what they always did.
        final List<RoomingRow> rest = new ArrayList<>();
        for (final Person.Id person : trip.getPeople()) {
            if (!reserved.contains(person)) {
                rest.add(new RoomingRow(person.getValue(), lodging.displayName(person), cellOf(person), "", "",
                        legacyRoom(tripId, person), "", null, null, null, answersLine(trip, person), false));
            }
        }
        rest.sort((a, b) -> nullSafe(a.getName()).compareToIgnoreCase(nullSafe(b.getName())));
        rows.addAll(rest);
        return rows;
    }

    /** The old rooms page's free-text room for someone with no reservation, or "". */
    static String legacyRoom(final String tripId, final Person.Id person) {
        final PersonDataValue pdv = PersonDataValueCommands.getPersonDataValue(person,
                RegistrationCommands.tripRoomDataId(tripId));
        return (pdv == null || pdv.getContent() == null) ? "" : pdv.getContent().toString();
    }

    private String cellOf(final Person.Id person) {
        return DAO.getInstance().getPerson(person, Cached.YES).map(Person::getCell).map(LodgingItinerary::nullSafe)
                .orElse("");
    }

    private String answersLine(final Trip trip, final Person.Id person) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, String> entry : lodging.answersFor(trip, person).entrySet()) {
            sb.append(entry.getKey()).append(" <b>").append(LodgingCommands.escape(entry.getValue()))
                    .append("</b><br />");
        }
        return sb.toString();
    }

    /**
     * The person's room label on this trip from their ACTIVE reservations ("114", or "114 / 201" across two
     * stays), or null when no reservation names a room -- what {@code RegistrationCommands.getRoomPDV} asks
     * before falling back to the legacy free-text room.
     */
    public String roomLabelFor(final String tripId, final Person.Id personId) {
        if (tripId == null || personId == null) {
            return null;
        }
        final List<Reservation> active = lodging.activeReservationsFor(tripId, personId);
        active.sort((a, b) -> LodgingCommands.compareNullable(a.getStart(), b.getStart()));
        final Set<String> labels = new LinkedHashSet<>();
        for (final Reservation res : active) {
            final Accommodation acc = lodging.findAccommodation(LodgingCommands.idValue(res.getAccommodationId()));
            final String label = (acc == null) ? null : acc.roomLabel(res.getRoomId());
            if (label != null && !label.isBlank()) {
                labels.add(label);
            }
        }
        return labels.isEmpty() ? null : String.join(" / ", labels);
    }


    /**
     * The itinerary's rows in the page's FROZEN event order: every event as it is, except a LODGING event for
     * which the person holds ACTIVE reservations on offers that track it -- those become ONE ROW PER STAY,
     * each carrying its own dates, room, nights and notes.
     */
    public List<ItineraryRow> itineraryRows(final Trip trip, final List<String> frozenEventIds,
            final Person.Id personId) {
        final List<ItineraryRow> rows = new ArrayList<>();
        if (trip == null || personId == null) {
            return rows;
        }
        final Map<String, List<Reservation>> byEvent = reservationsByEvent(trip.getId(), personId);
        final boolean showRooms = trip.getRoomNumbersShown();
        for (final TripEvent event : trips.eventsForFrozenIds(trip, frozenEventIds)) {
            rows.addAll(rowsFor(event, personId, byEvent.get(event.getId()), showRooms));
        }
        // By the date the row SHOWS, not the event's own: a late arriver's reservation starts days after the
        // group's hotel event, so ordering by the event put her hotel above the flights that got her there.
        // A stable sort, so events sharing a moment keep the order the trip gave them; undated rows sink.
        rows.sort(Comparator.comparing(ItineraryRow::getEffectiveStart,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /** The GET-only print pages: every event of the person, unfrozen, same rows. */
    public List<ItineraryRow> itineraryRowsFor(final Trip trip, final Person.Id personId) {
        if (trip == null || personId == null) {
            return new ArrayList<>();
        }
        return itineraryRows(trip, trips.eventIdsOf(trip.getTripEventsForUser(personId)), personId);
    }

    private Map<String, List<Reservation>> reservationsByEvent(final String tripId, final Person.Id personId) {
        final Map<String, List<Reservation>> byEvent = new HashMap<>();
        final Map<ReservationOffer.Id, ReservationOffer> offers = new HashMap<>();
        for (final ReservationOffer offer : DAO.getInstance().getReservationOffers(tripId, Cached.YES)) {
            offers.put(offer.getId(), offer);
        }
        for (final Reservation res : lodging.activeReservationsFor(tripId, personId)) {
            final ReservationOffer offer = offers.get(res.getOfferId());
            if (offer != null && offer.getTripEventId() != null) {
                byEvent.computeIfAbsent(offer.getTripEventId(), k -> new ArrayList<>()).add(res);
            }
        }
        return byEvent;
    }

    /**
     * The rows one event contributes: itself, or ONE PER STAY when the person holds reservations on it.
     *
     * <p>Two stays on one hotel event is the leave-and-return shape (Sep 21 to Oct 1, away, Oct 3 to Oct 4).
     * Folding them into one row read as thirteen nights and hid the gap, and the sort then placed the whole
     * block at the first date, above the flight that took them home in between. Each stay is its own row,
     * ordered among the trip's other events by the date it shows; what the EVENT itself says (its notes and
     * the per-person note the manager edits, which rides the event id) belongs on the first one only.
     */
    private List<ItineraryRow> rowsFor(final TripEvent event, final Person.Id personId,
            final List<Reservation> mine, final boolean showRooms) {
        if (mine == null || mine.isEmpty()) {
            return List.of(plainRow(event, personId));
        }
        final List<Reservation> stays = new ArrayList<>(mine);
        stays.sort((a, b) -> LodgingCommands.compareNullable(a.getStart(), b.getStart()));
        final List<ItineraryRow> rows = new ArrayList<>();
        for (int i = 0; i < stays.size(); i++) {
            final ItineraryRow row = plainRow(event, personId);
            row.setStayIndex(i + 1);
            row.setStayCount(stays.size());
            row.setFirstStay(i == 0);
            applyStay(row, event, stays.get(i), showRooms);
            rows.add(row);
        }
        return rows;
    }

    /** The event's own row, with no reservation on it. */
    private ItineraryRow plainRow(final TripEvent event, final Person.Id personId) {
        final ItineraryRow row = new ItineraryRow();
        row.setId(event.getId());
        row.setType(event.getType() == null ? "EVENT" : event.getType().name());
        row.setTitle(event.getTitle());
        row.setNotes(event.getNotes());
        row.setStart(event.getStart());
        row.setEnd(event.getEnd());
        row.setEffectiveStart(event.getStart());
        row.setEffectiveEnd(event.getEnd());
        row.setParticipantCount(event.getParticipants().size());
        row.getParticipantIds().addAll(event.getParticipants());
        row.setPrivNote(nullSafe(event.getPrivNotes().get(personId)));
        row.setLodging(event.getType() == TripEvent.Type.LODGING);
        row.setFirstStay(true);
        return row;
    }

    /** Lays one stay over the event's row: its dates, its room, its nights, its note. */
    private void applyStay(final ItineraryRow row, final TripEvent event, final Reservation res,
            final boolean showRooms) {
        final Accommodation acc = lodging.findAccommodation(LodgingCommands.idValue(res.getAccommodationId()));
        final ReservationOffer offer = lodging.findOffer(res.getTripId(), LodgingCommands.idValue(res.getOfferId()));
        if (acc != null) {
            row.setAccommodationName(acc.getName());
            final Room room = acc.room(res.getRoomId());
            final RoomType type = acc.roomType(room != null ? room.getRoomTypeId()
                    : (offer == null ? null : offer.firstRoomTypeId()));
            if (type != null) {
                row.setRoomTypeName(type.getName());
            }
            // The number is withheld while the trip's admin is still planning (the Assignments tab's
            // switch); the type and the nights are still theirs to see.
            final String label = acc.roomLabel(res.getRoomId());
            row.setRoomLabel(!showRooms ? null : label);
        }
        row.setReservationId(res.getId().getValue());
        row.setEffectiveStart(res.getStart() == null ? event.getStart() : res.getStart());
        row.setEffectiveEnd(res.getEnd() == null ? event.getEnd() : res.getEnd());
        // "Overridden" means the DATES differ from the group's; a reservation on the group dates still gets
        // its room line, but no "dates from your reservation" hint.
        row.setOverridden(!Objects.equals(row.getEffectiveStart(), event.getStart())
                || !Objects.equals(row.getEffectiveEnd(), event.getEnd()));
        final String notes = res.getNotes();
        row.setReservationNotes(notes == null || notes.isBlank() ? null : notes);
        row.setNights(Reservation.nightsBetween(row.getEffectiveStart(), row.getEffectiveEnd()));
    }

    private static String nullSafe(final String value) {
        return (value == null) ? "" : value;
    }
}
