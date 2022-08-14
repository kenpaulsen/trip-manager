package org.paulsens.trip.action;

import java.time.LocalDateTime;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class TripUtilCommandsTest {
    private final TripUtilCommands tripUtil = new TripUtilCommands();

    @Test
    public void testWithTimeZone() {
        final LocalDateTime time = LocalDateTime.of(2022, 7, 14, 5, 0); // This should be 7/13 in PST
        assertEquals(tripUtil.withTimeZone(time, "America/Los_Angeles").getDayOfMonth(), 13);
        assertEquals(tripUtil.withTimeZone(time, "Europe/Paris").getDayOfMonth(), 14);
    }
}