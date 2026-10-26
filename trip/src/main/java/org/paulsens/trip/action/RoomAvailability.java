package org.paulsens.trip.action;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomBlock;
import org.paulsens.trip.pay.LodgingPricing;

/**
 * The night arithmetic shared by the trip's room board ({@link LodgingCommands}) and the hotel's availability
 * view ({@link HotelCommands}): which nights of a room a {@link RoomBlock} holds, how a clash reads, and whether a
 * room already priced under one lodging option may take a stay under another ({@link #optionProblem}: no).
 *
 * <p>Pure and static on purpose -- it takes the rows it needs and touches no DAO, so both beans can call it
 * inside a render loop without either owning the other. Nights follow the stay convention everywhere:
 * {@code [start, end)} on calendar dates, times informational.
 */
final class RoomAvailability {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private RoomAvailability() {
    }

    /** The blocks holding {@code roomId} on any night of {@code [from, to)}, in the order given. */
    static List<RoomBlock> blocksOn(final String roomId, final List<RoomBlock> blocks, final LocalDateTime from,
            final LocalDateTime to) {
        final List<RoomBlock> hits = new ArrayList<>();
        if (roomId == null || blocks == null) {
            return hits;
        }
        final LocalDate low = (from == null) ? null : from.toLocalDate();
        final LocalDate high = (to == null) ? null : to.toLocalDate();
        for (final RoomBlock block : blocks) {
            if (block.holds(roomId) && block.overlapsNights(low, high)) {
                hits.add(block);
            }
        }
        return hits;
    }

    /** The nights of {@code [from, to)} on which some block holds the room. */
    static Set<LocalDate> blockedNights(final String roomId, final List<RoomBlock> blocks, final LocalDateTime from,
            final LocalDateTime to) {
        final Set<LocalDate> nights = new LinkedHashSet<>();
        for (final RoomBlock block : blocksOn(roomId, blocks, from, to)) {
            for (final LocalDate night : LodgingPricing.nightsOf(from, to)) {
                if (block.covers(roomId, night)) {
                    nights.add(night);
                }
            }
        }
        return nights;
    }

    /** "Sep 23 – Sep 25: another group", the blocks over a window joined for a card or a tooltip. */
    static String label(final String roomId, final List<RoomBlock> blocks, final LocalDateTime from,
            final LocalDateTime to) {
        final List<String> parts = new ArrayList<>();
        for (final RoomBlock block : blocksOn(roomId, blocks, from, to)) {
            parts.add(describe(block));
        }
        return String.join("; ", parts);
    }

    /** "Sep 23 – Sep 25: another group" for one block; the reason is omitted when it is blank. */
    static String describe(final RoomBlock block) {
        final String dates = nights(block);
        final String reason = (block.getReason() == null) ? "" : block.getReason().trim();
        return reason.isEmpty() ? dates : dates + ": " + reason;
    }

    /** "Sep 23 – Sep 25", a block's nights as the pages write every other date range. */
    static String nights(final RoomBlock block) {
        if (block.getStart() == null || block.getEnd() == null) {
            return "";
        }
        return block.getStart().format(DAY) + " – " + block.getEnd().format(DAY);
    }

    /**
     * Why this room cannot take a stay over {@code [start, end)}, as a sentence; null when it is free. A
     * refusal, not a warning: over-capacity is our own call to overrule, but a room another group holds is
     * not ours to hand out.
     */
    static String blockProblem(final Room room, final List<RoomBlock> blocks, final LocalDateTime start,
            final LocalDateTime end) {
        if (room == null || start == null || end == null) {
            return null;
        }
        for (final RoomBlock block : blocksOn(room.getId(), blocks, start, end)) {
            return "Room " + room.getRoomNumber() + " is not available " + describe(block)
                    + ". Change the block on the hotel's Availability tab first.";
        }
        return null;
    }

    /**
     * Why this room cannot take a stay under {@code offer} over {@code [start, end)}: it already holds an ACTIVE
     * stay of the trip under a DIFFERENT lodging option on one of those nights. Null when every stay sharing a
     * night is under the same option (the shared-room case) or nobody is in the way. A refusal with no override,
     * like a block (owner decision 2026-10-26): a room's nights are priced under ONE option -- a PER_ROOM rate is
     * split among that option's occupants and the Room Invoice charges each option its whole room -- so a second
     * option on the same night would bill the room twice.
     *
     * @param activeOnRoom the trip's ACTIVE stays in the room (other trips' guests are a capacity matter).
     * @param self         the reservation being edited or split, which never conflicts with itself; null to create.
     * @param names        person id to display name, for whoever is in the way.
     * @param offers       lodging option by id string, for its name; a deleted one reads "another lodging option".
     */
    static String optionProblem(final Room room, final ReservationOffer offer, final List<Reservation> activeOnRoom,
            final LocalDateTime start, final LocalDateTime end, final Reservation.Id self,
            final Function<Person.Id, String> names, final Function<String, ReservationOffer> offers) {
        if (room == null || offer == null || activeOnRoom == null || start == null || end == null) {
            return null;
        }
        for (final Reservation other : activeOnRoom) {
            if (!other.isActive() || other.getId().equals(self) || !room.getId().equals(other.getRoomId())
                    || Objects.equals(other.getOfferId(), offer.getId()) || !sharesANight(other, start, end)) {
                continue;
            }
            final ReservationOffer theirs = offers.apply(LodgingCommands.idValue(other.getOfferId()));
            return "Room " + room.getRoomNumber() + " already holds "
                    + LodgingPricing.joinNames(other.sortedOccupants(), names) + " under "
                    + (theirs == null ? "another lodging option" : "'" + theirs.getName() + "'") + " for "
                    + other.getStart().format(DAY) + " - " + other.getEnd().format(DAY_YEAR)
                    + "; a room's nights are priced under one lodging option. Pick another room, or move them first.";
        }
        return null;
    }

    /**
     * An edited reservation's room is not at its NEW option's hotel: named after the room's own hotel when
     * {@code here} (the stay's current one) has it, so the admin knows which of the two to change.
     */
    static String hotelProblem(final Accommodation here, final String roomId) {
        final Room room = (here == null) ? null : here.room(roomId);
        return (room == null) ? "That room is not at the accommodation."
                : "Room " + room.getRoomNumber() + " is at " + here.getName()
                        + "; pick an option at that hotel, or take them out of the room first.";
    }

    /**
     * Whether a stay shares a NIGHT with {@code [start, end)}, on calendar dates: one ending on the 26th and one
     * starting on the 26th share a date but no night, which is exactly the room-switch shape.
     */
    static boolean sharesANight(final Reservation other, final LocalDateTime start, final LocalDateTime end) {
        if (other.getStart() == null || other.getEnd() == null || start == null || end == null) {
            return false;
        }
        return start.toLocalDate().isBefore(other.getEnd().toLocalDate())
                && other.getStart().toLocalDate().isBefore(end.toLocalDate());
    }

    /** Whether any night of {@code [from, to)} has the room both blocked and slept in. */
    static boolean conflicts(final String roomId, final List<RoomBlock> blocks, final List<Reservation> onRoom,
            final LocalDateTime from, final LocalDateTime to) {
        final Set<LocalDate> blocked = blockedNights(roomId, blocks, from, to);
        if (blocked.isEmpty()) {
            return false;
        }
        for (final Reservation res : onRoom) {
            for (final LocalDate night : LodgingPricing.nightsOf(res.getStart(), res.getEnd())) {
                if (blocked.contains(night)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every ACTIVE stay in {@code roomId} that belongs to some OTHER trip and overlaps {@code [from, to)}. */
    static List<Reservation> otherTrips(final String roomId, final List<Reservation> atHotel, final String tripId,
            final LocalDateTime from, final LocalDateTime to) {
        final List<Reservation> others = new ArrayList<>();
        for (final Reservation res : atHotel) {
            if (res.isActive() && roomId != null && roomId.equals(res.getRoomId())
                    && !roomId.isBlank() && !tripId.equals(res.getTripId()) && overlaps(res, from, to)) {
                others.add(res);
            }
        }
        return others;
    }

    /** Half-open overlap on the stay's own instants; a null bound is open, as an unpinned window is. */
    static boolean overlaps(final Reservation res, final LocalDateTime from, final LocalDateTime to) {
        if (from == null || to == null || res.getStart() == null || res.getEnd() == null) {
            return true;
        }
        return res.getStart().isBefore(to) && res.getEnd().isAfter(from);
    }
}
