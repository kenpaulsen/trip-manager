package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.Config;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The shared DAO codec: a mangled row reads as absent, an unserializable value writes as null, and a missing
 * attribute still throws (the per-DAO copies it replaced had no null guard, and callers rely on that).
 */
public class JsonRowsTest {

    private final JsonRows rows = new JsonRows(new ObjectMapper().findAndRegisterModules());

    @Test
    public void aRowRoundTrips() {
        final Config config = new Config("jsonRows.test", "value", Config.Type.STRING, "A test row.", null, null);
        final String json = rows.toJson(config);
        Assert.assertNotNull(json);
        Assert.assertEquals(rows.parse(Config.class, json), config);
    }

    @Test
    public void aMangledRowReadsAsNull() {
        Assert.assertNull(rows.parse(Config.class, "{ not json"));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void aMissingAttributeStillThrows() {
        rows.parse(Config.class, null);
    }

    @Test
    public void anUnserializableValueWritesAsNull() {
        // A self-referencing map fails Jackson's cycle check with a JsonMappingException (an IOException).
        final Map<String, Object> cyclic = new java.util.HashMap<>();
        cyclic.put("self", cyclic);
        Assert.assertNull(rows.toJson(cyclic));
        Assert.assertNull(rows.toJson(new Object()));
    }

    /** One mangled row, or one with no content at all, must not cost the list its good rows. */
    @Test
    public void parseAllSkipsRowsWithoutContentAndRowsThatDoNotParse() {
        final Config config = new Config("jsonRows.all", "value", Config.Type.STRING, "A test row.", null, null);
        final List<Map<String, AttributeValue>> items = List.of(
                Map.of("content", AttributeValue.builder().s(rows.toJson(config)).build()),
                Map.of("id", AttributeValue.builder().s("no content attribute").build()),
                Map.of("content", AttributeValue.builder().s("{ not json").build()));
        Assert.assertEquals(rows.parseAll(Config.class, items), List.of(config));
        Assert.assertEquals(rows.parseAll(Config.class, List.of()), List.of());
    }
}
