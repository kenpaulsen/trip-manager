package org.paulsens.trip.cache;

import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class RefreshPermitsTest {

    /** The cap is real: after MAX_CONCURRENT acquisitions the next is refused, and releases restore it. */
    @Test
    public void capRefusesBeyondMaxAndReleasesRestore() {
        final List<Boolean> held = new ArrayList<>();
        try {
            while (RefreshPermits.tryAcquire()) {
                held.add(Boolean.TRUE);
            }
            // Everything acquirable is now held (other tests' in-flight refreshes may hold a few).
            assertTrue(held.size() <= RefreshPermits.MAX_CONCURRENT);
            assertFalse(RefreshPermits.tryAcquire(), "an exhausted cap must refuse, never block");
            assertEquals(RefreshPermits.available(), 0);
        } finally {
            held.forEach(ignored -> RefreshPermits.release());
        }
        assertTrue(RefreshPermits.available() > 0, "released permits must be reusable");
        assertTrue(RefreshPermits.tryAcquire());
        RefreshPermits.release();
    }
}
