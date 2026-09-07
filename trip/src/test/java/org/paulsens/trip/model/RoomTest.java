package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class RoomTest {

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        final Room built = Room.builder().build();
        final Room fresh = new Room();
        built.setId(fresh.getId());
        assertEquals(built, fresh);
        assertNotNull(fresh.getId());
        assertFalse(fresh.isMapped());
        assertNull(fresh.getRoomNumber());
    }

    @Test
    public void numberAndFloorAreTrimmedAndBlankIdIsMinted() {
        final Room room = Room.builder().id(" ").roomNumber(" 114 ").floor(" 1 ").roomTypeId("rt").build();
        assertEquals(room.getRoomNumber(), "114");
        assertEquals(room.getFloor(), "1");
        assertFalse(room.getId().isBlank());
    }

    @Test
    public void mapRegionValidityRequiresAPositiveBoxInsideTheImage() {
        assertTrue(Room.MapRegion.rect(10, 10, 5, 5).isValid());
        assertTrue(Room.MapRegion.rect(0, 0, 100, 100).isValid());
        assertFalse(Room.MapRegion.rect(96, 10, 5, 5).isValid(), "Runs past the right edge");
        assertFalse(Room.MapRegion.rect(10, 96, 5, 5).isValid(), "Runs past the bottom edge");
        assertFalse(Room.MapRegion.rect(-1, 10, 5, 5).isValid());
        assertFalse(Room.MapRegion.rect(10, 10, 0, 5).isValid(), "Zero width");
        assertFalse(Room.MapRegion.rect(10, 10, 5, -2).isValid());
        assertEquals(Room.MapRegion.rect(1, 2, 3, 4).getKind(), Room.MapRegion.RECT);
        assertTrue(Room.builder().mapRegion(Room.MapRegion.rect(1, 2, 3, 4)).build().isMapped());
    }

    @Test
    public void jacksonRoundTripPreservesTheRegion() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Room room = Room.builder().id("r-1").roomTypeId("rt-1").roomNumber("114").floor("1")
                .notes("Mountain view").adminNotes("Creaky floor").mapRegion(Room.MapRegion.rect(12.5, 20.25, 8, 6))
                .build();
        final String json = mapper.writeValueAsString(room);
        assertFalse(json.contains("\"mapped\""), "Derived properties are not persisted");
        assertTrue(json.contains("\"kind\":\"rect\""));
        assertEquals(mapper.readValue(json, Room.class), room);
    }
}
