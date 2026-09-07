package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class RoomTypeTest {

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        final RoomType built = RoomType.builder().build();
        final RoomType fresh = new RoomType();
        built.setId(fresh.getId());
        assertEquals(built, fresh);
        assertEquals(fresh.getMinPeople(), 1);
        assertEquals(fresh.getMaxPeople(), RoomType.DEFAULT_MAX_PEOPLE, "An unset capacity is a two-person room");
        assertNotNull(fresh.getId());
        assertTrue(fresh.getPhotoIds().isEmpty());
    }

    @Test
    public void capacityIsNormalizedAndFitsChecksTheBand() {
        final RoomType type = RoomType.builder().name(" Triple ").minPeople(3).maxPeople(2).build();
        assertEquals(type.getName(), "Triple", "Names are trimmed");
        assertEquals(type.getMinPeople(), 3);
        assertEquals(type.getMaxPeople(), 3, "A max below min is raised to min, never silently inverted");
        assertFalse(type.fits(2));
        assertTrue(type.fits(3));
        assertFalse(type.fits(4));
        assertEquals(type.getDisplayLabel(), "Triple (3-3)");
        assertEquals(RoomType.builder().minPeople(-4).maxPeople(0).build().getMinPeople(), 1);
    }

    @Test
    public void photoIdsAreCopiedNotShared() {
        final List<String> ids = new java.util.ArrayList<>(List.of("m1"));
        final RoomType type = RoomType.builder().photoIds(ids).build();
        ids.add("m2");
        assertEquals(type.getPhotoIds(), List.of("m1"));
    }

    @Test
    public void jacksonRoundTripPreservesEverything() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final RoomType type = RoomType.builder().id("rt-1").name("Two Queen").description("Two queen beds")
                .photoIds(List.of("m1", "m2")).minPeople(1).maxPeople(4).build();
        final String json = mapper.writeValueAsString(type);
        assertFalse(json.contains("displayLabel"), "Derived properties are not persisted");
        assertEquals(mapper.readValue(json, RoomType.class), type);
    }
}
