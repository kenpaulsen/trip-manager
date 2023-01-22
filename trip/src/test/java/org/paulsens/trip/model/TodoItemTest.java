package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class TodoItemTest {
    @Test
    public void testTripId() {
        final String tripId = RandomData.genAlpha(15);
        final TodoItem todo = TodoItem.builder()
                .tripId(tripId)
                .dataId(DataId.newInstance())
                .description(RandomData.genAlpha(11))
                .build();
        assertEquals(todo.getTripId(), tripId);
    }

    @Test
    public void testDataId() {
        final DataId dataId = DataId.from(RandomData.genAlpha(15));
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(13))
                .dataId(dataId)
                .description(RandomData.genAlpha(11))
                .build();
        assertEquals(todo.getDataId(), dataId);
    }

    @Test
    public void testDescription() {
        final String description = RandomData.genAlpha(15);
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(13))
                .dataId(DataId.newInstance())
                .description(description)
                .build();
        assertEquals(todo.getDescription(), description);
    }

    @Test
    public void testMoreDetails() {
        final String moreDetails = RandomData.genAlpha(15);
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(13))
                .dataId(DataId.newInstance())
                .description(RandomData.genAlpha(11))
                .moreDetails(moreDetails)
                .build();
        assertEquals(todo.getMoreDetails(), moreDetails);
    }

    @Test
    public void testCreated() {
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(13))
                .dataId(DataId.newInstance())
                .description(RandomData.genAlpha(11))
                .build();
        assertNotNull(todo.getCreated());
        final LocalDateTime now = LocalDateTime.now();
        final TodoItem todoWithTime = TodoItem.builder()
                .tripId(RandomData.genAlpha(13))
                .dataId(DataId.newInstance())
                .description(RandomData.genAlpha(11))
                .created(now)
                .build();
        assertEquals(todoWithTime.getCreated(), now);
    }

    @Test
    public void canSerializeTodo() throws IOException {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final String tripId = RandomData.genAlpha(9);
        final DataId dataId = DataId.from(RandomData.genAlpha(8));
        final String desc = RandomData.genAlpha(7);
        final String more = RandomData.genAlpha(6);
        final TodoItem orig = TodoItem.builder()
                .tripId(tripId).dataId(dataId).description(desc).moreDetails(more).build();
        final String json = mapper.writeValueAsString(orig);
        final TodoItem restored = mapper.readValue(json, TodoItem.class);
        assertEquals(restored, orig);
        assertEquals(restored.getTripId(), tripId);
        assertEquals(restored.getDataId(), dataId);
        assertEquals(restored.getDescription(), desc);
        assertEquals(restored.getMoreDetails(), more);
    }

    @Test
    public void testTestEquals() {
        EqualsVerifier.forClass(TodoItem.class)
                .suppress(Warning.STRICT_INHERITANCE)
                .suppress(Warning.NONFINAL_FIELDS).verify();
    }
}