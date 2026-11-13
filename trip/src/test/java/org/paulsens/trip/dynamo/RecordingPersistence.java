package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * A store that remembers what it was asked. For the parts of a request the in-memory fake cannot vouch for
 * because it does not model them: a strongly consistent read or scan, the exact condition on a put. Reads and
 * scans answer empty; puts succeed unless {@link #failPuts} or {@link #conflictPuts} is set.
 */
final class RecordingPersistence implements Persistence {
    final List<GetItemRequest> reads = new ArrayList<>();
    final List<PutItemRequest> puts = new ArrayList<>();
    final List<ScanRequest> scans = new ArrayList<>();
    boolean failPuts;
    /** Every put loses its version check, as against a row somebody else just saved. */
    boolean conflictPuts;

    /** The one read a test expects, checked to be a strongly consistent read of {@code table}. */
    GetItemRequest onlyConsistentReadOf(final String table) {
        if (reads.size() != 1 || !table.equals(reads.get(0).tableName())
                || !Boolean.TRUE.equals(reads.get(0).consistentRead())) {
            throw new AssertionError("Expected one consistent read of " + table + " but saw " + reads);
        }
        return reads.get(0);
    }

    @Override
    public ScanResponse scan(final Consumer<ScanRequest.Builder> request) {
        final ScanRequest.Builder builder = ScanRequest.builder();
        request.accept(builder);
        scans.add(builder.build());
        return Persistence.super.scan(request);
    }

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
        if (conflictPuts) {
            throw ConditionalCheckFailedException.builder().message("version check lost").build();
        }
        return Persistence.super.putItem(request);
    }
}
