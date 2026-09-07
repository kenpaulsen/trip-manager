package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class FloorMapTest {

    @Test
    public void noArgInstanceIsEmptyAndJsonRoundTrips() throws Exception {
        final FloorMap empty = new FloorMap();
        assertNull(empty.getFloor());
        assertNull(empty.getMediaId());
        final FloorMap map = new FloorMap("2", "media-9");
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        assertEquals(mapper.readValue(mapper.writeValueAsString(map), FloorMap.class), map);
        assertEquals(map.getFloor(), "2");
        assertEquals(map.getMediaId(), "media-9");
    }
}
