package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class PersonDataValueTest {
    @Test
    public void testUserId() {
        final Person.Id userId = Person.Id.from(RandomData.genAlpha(15));
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(userId)
                .dataId(DataId.newInstance())
                .type(RandomData.genAlpha(5))
                .content(RandomData.genAlpha(15))
                .build();
        assertEquals(pdv.getUserId(), userId);
    }

    @Test
    public void testDataId() {
        final DataId dataId = DataId.from(RandomData.genAlpha(15));
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.newInstance())
                .dataId(dataId)
                .type(RandomData.genAlpha(5))
                .content(RandomData.genAlpha(15))
                .build();
        assertEquals(pdv.getDataId(), dataId);
    }

    @Test
    public void testType() {
        final String type = RandomData.genAlpha(15);
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.newInstance())
                .dataId(DataId.newInstance())
                .type(type)
                .content(RandomData.genAlpha(15))
                .build();
        assertEquals(pdv.getType(), type);
    }

    @Test
    public void testDescription() {
        final String content = RandomData.genAlpha(15);
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.newInstance())
                .dataId(DataId.newInstance())
                .type(RandomData.genAlpha(13))
                .content(content)
                .build();
        assertEquals(pdv.getContent(), content);
    }

    @Test
    public void canHoldStringData() {
        final String content = RandomData.genAlpha(15);
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.newInstance())
                .dataId(DataId.newInstance())
                .type(RandomData.genAlpha(5))
                .content(content)
                .build();
        final String result = pdv.castContent();
        assertEquals(result, content);
    }

    @Test
    public void canHoldIntData() {
        final int content = RandomData.randomInt(Integer.MAX_VALUE);
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.newInstance())
                .dataId(DataId.newInstance())
                .type(RandomData.genAlpha(5))
                .content(content).build();
        final int result = pdv.castContent();
        assertEquals(result, content);
    }

    @Test
    public void canHoldIntegerData() {
        final Integer content = RandomData.randomInt(Integer.MAX_VALUE);
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.newInstance())
                .dataId(DataId.newInstance())
                .type(RandomData.genAlpha(5))
                .content(content).build();
        final Integer result = pdv.castContent();
        assertEquals(result, content);
    }

    @Test
    public void wrongCastThrows() {
        final Integer content = RandomData.randomInt(Integer.MAX_VALUE);
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.newInstance())
                .dataId(DataId.newInstance())
                .type(RandomData.genAlpha(5))
                .content(content).build();
        assertThrows(ClassCastException.class, () -> { long l = pdv.castContent(); });
    }

    @Test
    public void canSerializePDV() throws IOException {
        final ObjectMapper mapper = DynamoUtils.getInstance().getMapper();
        final Person.Id userId = Person.Id.from(RandomData.genAlpha(9));
        final DataId dataId = DataId.from(RandomData.genAlpha(8));
        final String type = RandomData.genAlpha(12);
        final String content = RandomData.genAlpha(7);
        final PersonDataValue orig = PersonDataValue.builder()
                .userId(userId)
                .dataId(dataId)
                .type(type)
                .content(content).build();
        final String json = mapper.writeValueAsString(orig);
        final PersonDataValue restored = mapper.readValue(json, PersonDataValue.class);
        assertEquals(restored, orig);
        assertEquals(restored.getUserId(), userId);
        assertEquals(restored.getDataId(), dataId);
        assertEquals(restored.getType(), type);
        assertEquals(restored.getContent(), content);
    }

    @Test
    public void testTestEquals() {
        EqualsVerifier.forClass(PersonDataValue.class)
                .suppress(Warning.NONFINAL_FIELDS)
                .suppress(Warning.STRICT_INHERITANCE).verify();
    }
}