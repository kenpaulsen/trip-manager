package org.paulsens.trip.web;

import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.mockito.Mockito;
import org.primefaces.util.Constants;
import org.primefaces.util.LimitedSizeHashMap;
import org.testng.Assert;
import org.testng.annotations.Test;
import sun.reflect.ReflectionFactory;

/**
 * That PrimeFaces' dynamic-content registry keeps working after a session has been through Kryo.
 *
 * <p>Kryo (and therefore every Valkey-stored session) rebuilds a Map subclass by allocating it <b>without
 * running a constructor</b> and replaying its entries; no field the constructor would have set survives. These
 * tests stand that step in with {@link ReflectionFactory}, which is the same allocation Objenesis performs on
 * HotSpot — Kryo itself is on Tomcat's classloader, not the webapp's, so it cannot be referenced from here (the
 * same constraint {@code SessionRecoveryFilterTest} works under).
 *
 * <p>The first test is the bug, kept as the specification: it fails the day someone "simplifies"
 * {@link DynamicResourceMap#MAX_ENTRIES} into a constructor argument, which is exactly the shape that broke
 * production on 2026-08-13.
 */
public class DynamicResourceMapTest {

    /** What Kryo does: allocate, no constructor, then replay the entries. */
    private static <T> T reconstructedWithoutConstructor(final Class<T> type, final Map<String, String> entries) {
        try {
            final Constructor<?> alloc = ReflectionFactory.getReflectionFactory()
                    .newConstructorForSerialization(type, Object.class.getDeclaredConstructor());
            final T instance = type.cast(alloc.newInstance());
            ((Map<String, String>) instance).putAll(entries);
            return instance;
        } catch (final ReflectiveOperationException ex) {
            throw new IllegalStateException("Could not stand in for Kryo's allocation of " + type, ex);
        }
    }

    /**
     * The defect this class replaces, asserted directly: PrimeFaces' own map comes back with a zero cap and then
     * silently discards everything ever put into it, which is what serves an empty 200 for a cropper image.
     */
    @Test
    public void primeFacesOwnMapIsEmptiedByTheKryoRoundTrip() {
        final Map<String, String> before = new HashMap<>();
        before.put("pfdrid", "#{pendingPhoto.cropperImage}");

        final LimitedSizeHashMap<String, String> after =
                reconstructedWithoutConstructor(LimitedSizeHashMap.class, before);

        Assert.assertTrue(after.isEmpty(), "The round trip is supposed to lose the cap and evict on insert");
        after.put("pfdrid2", "#{pendingPhoto.cropperImage}");
        Assert.assertNull(after.get("pfdrid2"), "A put into the flattened map is supposed to vanish");
    }

    /** The same round trip against ours: the cap is a constant, so there is nothing to lose. */
    @Test
    public void ourMapSurvivesTheKryoRoundTripWithItsEntriesAndItsCap() {
        final Map<String, String> before = new LinkedHashMap<>();
        before.put("pfdrid", "#{pendingPhoto.cropperImage}");

        final DynamicResourceMap after = reconstructedWithoutConstructor(DynamicResourceMap.class, before);

        Assert.assertEquals(after.get("pfdrid"), "#{pendingPhoto.cropperImage}", "The entry must survive");
        after.put("pfdrid2", "#{chatPhotoUpload.something}");
        Assert.assertEquals(after.get("pfdrid2"), "#{chatPhotoUpload.something}", "A later put must stick");
    }

    /** The cap still bounds the map, so a long-lived session cannot grow it without limit. */
    @Test
    public void theCapStillEvictsOldestFirst() {
        final DynamicResourceMap map = new DynamicResourceMap();
        for (int i = 0; i <= DynamicResourceMap.MAX_ENTRIES; i++) {
            map.put("k" + i, "v" + i);
        }
        Assert.assertEquals(map.size(), DynamicResourceMap.MAX_ENTRIES);
        Assert.assertNull(map.get("k0"), "The oldest entry is the one that goes");
        Assert.assertNotNull(map.get("k" + DynamicResourceMap.MAX_ENTRIES), "The newest entry stays");
    }

    @Test
    public void repairSeatsTheMapWhenTheSessionHasNone() {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(Constants.DYNAMIC_RESOURCES_MAPPING)).thenReturn(null);

        DynamicResourceMap.repair(session);

        Mockito.verify(session).setAttribute(Mockito.eq(Constants.DYNAMIC_RESOURCES_MAPPING),
                Mockito.any(DynamicResourceMap.class));
    }

    /** The healing case: a session restored from Valkey still holding the flattened PrimeFaces map. */
    @Test
    public void repairReplacesAFlattenedPrimeFacesMap() {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(Constants.DYNAMIC_RESOURCES_MAPPING))
                .thenReturn(new LimitedSizeHashMap<String, String>(1000));

        DynamicResourceMap.repair(session);

        Mockito.verify(session).setAttribute(Mockito.eq(Constants.DYNAMIC_RESOURCES_MAPPING),
                Mockito.any(DynamicResourceMap.class));
    }

    /** A healthy session must not have its registry thrown away on every request. */
    @Test
    public void repairLeavesAnAlreadyGoodMapAlone() {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(Constants.DYNAMIC_RESOURCES_MAPPING))
                .thenReturn(new DynamicResourceMap());

        DynamicResourceMap.repair(session);

        Mockito.verify(session, Mockito.never())
                .setAttribute(Mockito.eq(Constants.DYNAMIC_RESOURCES_MAPPING), Mockito.any());
    }

    /** An anonymous request has no session, and repairing must never create one. */
    @Test
    public void repairIgnoresAnAbsentSession() {
        DynamicResourceMap.repair(null);
    }
}
