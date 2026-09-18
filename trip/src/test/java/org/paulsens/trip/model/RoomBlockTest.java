package org.paulsens.trip.model;

import java.time.LocalDate;
import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/** The night arithmetic a room block promises: {@code [start, end)}, like every other stay in the app. */
public class RoomBlockTest {
    private static final LocalDate SEP21 = LocalDate.of(2026, 9, 21);
    private static final LocalDate SEP26 = LocalDate.of(2026, 9, 26);

    @Test
    public void aBlockHoldsTheNightsBetweenItsDates() {
        final RoomBlock block = block(SEP21, SEP26, "r-101", "r-102");
        assertEquals(block.getNights(), 5, "Sep 21 to Sep 26 is five nights, free again on the 26th");
        assertTrue(block.covers("r-101", SEP21));
        assertTrue(block.covers("r-101", SEP26.minusDays(1)));
        assertFalse(block.covers("r-101", SEP26), "the end date is the morning they are free");
        assertFalse(block.covers("r-101", SEP21.minusDays(1)));
        assertFalse(block.covers("r-999", SEP21), "a room it does not hold");
        assertFalse(block.covers(null, SEP21));
        assertFalse(block.covers("r-101", null));
        assertTrue(block.holds("r-102"));
        assertFalse(block.holds(null));
    }

    @Test
    public void adegenerateBlockHoldsNothing() {
        assertEquals(block(SEP21, SEP21, "r-101").getNights(), 0, "same day: no night");
        assertEquals(block(SEP26, SEP21, "r-101").getNights(), 0, "backwards: no night");
        assertEquals(block(null, SEP26, "r-101").getNights(), 0);
        assertEquals(block(SEP21, null, "r-101").getNights(), 0);
        assertFalse(block(SEP21, SEP21, "r-101").overlapsNights(SEP21, SEP26), "no night, no overlap");
    }

    @Test
    public void overlapIsHalfOpenAndANullBoundIsOpen() {
        final RoomBlock block = block(SEP21, SEP26, "r-101");
        assertTrue(block.overlapsNights(SEP21, SEP26));
        assertTrue(block.overlapsNights(SEP26.minusDays(1), SEP26.plusDays(5)), "the last night");
        assertFalse(block.overlapsNights(SEP26, SEP26.plusDays(5)), "starts the morning they are free");
        assertFalse(block.overlapsNights(SEP21.minusDays(5), SEP21), "ends the night before");
        assertTrue(block.overlapsNights(null, SEP26));
        assertTrue(block.overlapsNights(SEP21, null));
        assertTrue(block.overlapsNights(null, null));
    }

    @Test
    public void aBlockNormalizesWhatItIsGiven() {
        final RoomBlock blank = new RoomBlock();
        assertNotNull(blank.getId(), "a block mints its own id, like every other lodging row");
        assertNotNull(blank.getRoomIds(), "never null: the dialog binds a multi-select straight to it");
        assertEquals(RoomBlock.builder().reason("  another group  ").build().getReason(), "another group");
        final List<String> rooms = new java.util.ArrayList<>(List.of("r-1"));
        final RoomBlock copied = RoomBlock.builder().roomIds(rooms).build();
        rooms.add("r-2");
        assertEquals(copied.getRoomIds(), List.of("r-1"), "the list is copied, not aliased");
        assertEquals(RoomBlock.Id.from("b-1").compareTo(RoomBlock.Id.from("b-2")), -1);
        assertEquals(RoomBlock.Id.from("b-1"), RoomBlock.Id.from("b-1"));
    }

    private static RoomBlock block(final LocalDate start, final LocalDate end, final String... rooms) {
        return RoomBlock.builder().accommodationId(Accommodation.Id.from("acc-1")).roomIds(List.of(rooms))
                .start(start).end(end).reason("another group").build();
    }
}
