package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

/**
 * The raw, conditional trip-event access a one-time data repair uses ({@code migrate/NoteElFlatten}): every row's
 * stored JSON, and a write that only lands on a row still holding the text the repair read.
 */
public class TripEventRawContentTest {

    /** A store holding ONE row whose content is "old"; a put is refused unless it expects "old". */
    private static final class OneRow implements Persistence {
        private final List<PutItemRequest> puts = new ArrayList<>();

        @Override
        public List<Map<String, AttributeValue>> scanAll(final Consumer<ScanRequest.Builder> scanRequest) {
            return List.of(
                    Map.of("id", AttributeValue.builder().s("e1").build(),
                            "content", AttributeValue.builder().s("old").build()),
                    Map.of("id", AttributeValue.builder().s("no-content").build()));
        }

        @Override
        public PutItemResponse putItem(final Consumer<PutItemRequest.Builder> putItemRequest) {
            final PutItemRequest.Builder builder = PutItemRequest.builder();
            putItemRequest.accept(builder);
            final PutItemRequest put = builder.build();
            puts.add(put);
            if (!"old".equals(put.expressionAttributeValues().get(":expected").s())) {
                throw ConditionalCheckFailedException.builder().message("changed").build();
            }
            return Persistence.super.putItem(putItemRequest);
        }
    }

    @Test
    public void rowsAreReadRawAndWrittenOnlyIfUnchanged() {
        final OneRow store = new OneRow();
        final TripEventDAO dao = new TripEventDAO(new ObjectMapper(), store);

        Assert.assertEquals(dao.rawTripEventContents(), Map.of("e1", "old"), "a row with no content is skipped");
        Assert.assertTrue(dao.replaceTripEventContent("e1", "old", "new"));
        Assert.assertEquals(store.puts.get(0).conditionExpression(), "#c = :expected");
        Assert.assertEquals(store.puts.get(0).item().get("content").s(), "new");
        Assert.assertFalse(dao.replaceTripEventContent("e1", "stale", "newer"), "a changed row is never overwritten");
    }
}
