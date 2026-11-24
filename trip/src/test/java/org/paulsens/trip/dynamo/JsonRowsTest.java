package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.util.TestWaits;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

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

    /**
     * A row holds names, addresses and money, and the log is read by people the row is not for: a row that does
     * not parse is logged by type and position only. Jackson's own message is not safe either -- it quotes the
     * unknown enum constant, the mistyped value and the unrecognized token.
     */
    @Test(dataProvider = "unparsableRows")
    public void aRowThatDoesNotParseIsLoggedWithoutItsContent(final String json, final String failure) {
        final String out = TestWaits.captureStdout(() -> Assert.assertNull(rows.parse(Config.class, json)));
        Assert.assertTrue(out.contains("Unable to parse Config record"), "the failure is logged: " + out);
        Assert.assertTrue(out.contains(failure), "with the kind of failure: " + out);
        Assert.assertTrue(out.contains("line 1, column"), "and where in the row: " + out);
        Assert.assertFalse(out.contains("Zagreb"), "but nothing the row holds: " + out);
    }

    @DataProvider
    public Object[][] unparsableRows() {
        return new Object[][] {
            {"{\"name\":\"n\",\"value\":\"12 Zagreb Street\",\"type\":\"ZagrebType\"}", "InvalidFormatException"},
            {"{\"name\":\"n\",\"value\":\"12 Zagreb Street\",\"modified\":\"Zagreb o'clock\"}",
                "InvalidFormatException"},
            {"{\"name\":\"n\",\"value\":\"12 Zagreb Street\",\"type\":{\"Zagreb\":1}}",
                "MismatchedInputException"},
            {"{\"name\":\"n\",\"value\":\"12 Zagreb Street\",\"ZagrebField\":1}",
                "UnrecognizedPropertyException"},
            {"{\"name\":\"12 Zagreb Street\",\"value\":Zagreb}", "JsonParseException"},
        };
    }

    /** The same on the way out: Jackson's reference chain quotes map keys, and a key can be an address. */
    @Test
    public void aValueThatDoesNotSerializeIsLoggedWithoutItsContent() {
        final Map<String, Object> cyclic = new java.util.HashMap<>();
        cyclic.put("zagreb@example.com", cyclic);
        final String out = TestWaits.captureStdout(() -> Assert.assertNull(rows.toJson(cyclic)));
        Assert.assertTrue(out.contains("Unable to serialize HashMap"), "the failure is logged: " + out);
        Assert.assertTrue(out.contains("JsonMappingException"), "with the kind of failure: " + out);
        Assert.assertFalse(out.contains("zagreb"), "but nothing the value holds: " + out);
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

    @Test
    public void parseItemReadsTheContentAttribute() {
        final Config config = sampleConfig();
        Assert.assertEquals(rows.parseItem(Config.class, itemOf(config)), config);
        Assert.assertNull(rows.parseItem(Config.class, Map.of()), "no row, or a row with no content");
    }

    @Test
    public void getReadsOneRowByKeyAndAnswersNullWhenThereIsNone() {
        final InMemoryPersistence store = new InMemoryPersistence();
        final Config config = sampleConfig();
        store.putItem(b -> b.tableName(ConfigDAO.CONFIG_TABLE).item(itemOf(config)));

        Assert.assertEquals(rows.get(store, ConfigDAO.CONFIG_TABLE, keyOf(config.getName()), Config.class), config);
        Assert.assertNull(rows.get(store, ConfigDAO.CONFIG_TABLE, keyOf("nobody"), Config.class));
        Assert.assertEquals(rows.tryGet(store, ConfigDAO.CONFIG_TABLE, keyOf(config.getName()), Config.class),
                Optional.of(config));
        Assert.assertEquals(rows.tryGet(store, ConfigDAO.CONFIG_TABLE, keyOf("nobody"), Config.class),
                Optional.empty());
    }

    /** The two point reads differ in exactly this: an outage propagates from one and reads as empty from the other. */
    @Test
    public void getPropagatesAStoreFailureAndTryGetSwallowsIt() {
        final Persistence down = new Persistence() {
            @Override
            public GetItemResponse getItem(final Consumer<GetItemRequest.Builder> request) {
                throw new IllegalStateException("store down");
            }
        };
        Assert.assertThrows(IllegalStateException.class,
                () -> rows.get(down, ConfigDAO.CONFIG_TABLE, keyOf("x"), Config.class));
        Assert.assertEquals(rows.tryGet(down, ConfigDAO.CONFIG_TABLE, keyOf("x"), Config.class), Optional.empty());
    }

    /** The scan behind a PartitionScanCache must be strongly consistent: its rebuild removes what it misses. */
    @Test
    public void scanAllIsOneConsistentScanOfTheTable() {
        final RecordingPersistence store = new RecordingPersistence();
        Assert.assertEquals(rows.scanAll(store, ConfigDAO.CONFIG_TABLE, Config.class), List.of());
        Assert.assertEquals(store.scans.size(), 1);
        Assert.assertEquals(store.scans.get(0).tableName(), ConfigDAO.CONFIG_TABLE);
        Assert.assertEquals(store.scans.get(0).consistentRead(), Boolean.TRUE);

        final InMemoryPersistence seeded = new InMemoryPersistence();
        final Config config = sampleConfig();
        seeded.putItem(b -> b.tableName(ConfigDAO.CONFIG_TABLE).item(itemOf(config)));
        Assert.assertEquals(rows.scanAll(seeded, ConfigDAO.CONFIG_TABLE, Config.class), List.of(config));
    }

    private static Config sampleConfig() {
        return new Config("jsonRows.row", "value", Config.Type.STRING, "A test row.", null, null);
    }

    private Map<String, AttributeValue> itemOf(final Config config) {
        return Map.of("name", AttributeValue.builder().s(config.getName()).build(),
                "content", AttributeValue.builder().s(rows.toJson(config)).build());
    }

    private static Map<String, AttributeValue> keyOf(final String name) {
        return Map.of("name", AttributeValue.builder().s(name).build());
    }
}
