package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

/**
 * A store that remembers what it was asked. For the parts of a request the in-memory fake cannot vouch for
 * because it does not model them: a strongly consistent read, the exact condition on a put. Reads answer empty;
 * puts succeed unless {@link #failPuts} is set.
 */
final class RecordingPersistence implements Persistence {
    final List<GetItemRequest> reads = new ArrayList<>();
    final List<PutItemRequest> puts = new ArrayList<>();
    boolean failPuts;

    @Override
    public GetItemResponse getItem(final Consumer<GetItemRequest.Builder> request) {
        final GetItemRequest.Builder builder = GetItemRequest.builder();
        request.accept(builder);
        reads.add(builder.build());
        return GetItemResponse.builder().build();
    }

    @Override
    public PutItemResponse putItem(final Consumer<PutItemRequest.Builder> request) {
        final PutItemRequest.Builder builder = PutItemRequest.builder();
        request.accept(builder);
        puts.add(builder.build());
        if (failPuts) {
            throw new IllegalStateException("store down");
        }
        return Persistence.super.putItem(request);
    }
}
