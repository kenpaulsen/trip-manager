package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@Slf4j
public class TransactionDAO {
    private static final String CONTENT = "content";
    private static final String TRANSACTION_TABLE = "transactions";
    private static final String TX_ID = "txId";
    private static final String USER_ID = "userId";

    private final Map<Person.Id, Map<String, Transaction>> txCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Persistence persistence;

    protected TransactionDAO(final ObjectMapper mapper, final Persistence persistence) {
        this.mapper = mapper;
        this.persistence = persistence;
    }

    protected CompletableFuture<List<Transaction>> getTransactions(final Person.Id userId) {
        return getTxCacheForUser(userId)
                .thenApply(map -> persistence.sortList(map.values(), Comparator.comparing(Transaction::getTxDate)));
    }

    protected CompletableFuture<Optional<Transaction>> getTransaction(final Person.Id userId, final String txId) {
        return getTxCacheForUser(userId) // Ensure user transactions are already loaded into memory
                .thenApply(map -> map.get(txId))  // Read from cache
                .thenApply(Optional::ofNullable);
    }

    protected CompletableFuture<Boolean> saveTransaction(final Transaction tx) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(USER_ID, AttributeValue.builder().s(tx.getUserId().getValue()).build());
        map.put(TX_ID, AttributeValue.builder().s(tx.getTxId()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(tx)).build());
        return persistence.putItem(b -> b.tableName(TRANSACTION_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenCompose(r -> cacheOneTxAsync(r, tx));
    }

    /* Package-private for testing */
    CompletableFuture<Map<String, Transaction>> getTxCacheForUser(final Person.Id userId) {
        final Map<String, Transaction> result = txCache.get(userId);
        return (result == null) ? cacheTxData(userId) : CompletableFuture.completedFuture(result);
    }

    public void clearCache() {
        txCache.clear();
    }

    private CompletableFuture<Boolean> cacheOneTxAsync(final boolean success, final Transaction tx) {
        return getTxCacheForUser(tx.getUserId())
                .thenApply(userTxs -> cacheOneTx(userTxs, success, tx));
    }

    private boolean cacheOneTx(final Map<String, Transaction> userTxs, final boolean success, final Transaction tx) {
        if (success) {
            if (tx.getDeleted() == null) {
                persistence.cacheOne(userTxs, tx, tx.getTxId(), true); // Normal, just cache it
            } else {
                userTxs.remove(tx.getTxId()); // It is now deleted, remove it from cache
            }
        } else {
            persistence.clearCache(userTxs, false); // Error... clear all cache values
        }
        return success;
    }

    private CompletableFuture<Map<String, Transaction>> cacheTxData(final Person.Id userId) {
        return loadUserTxData(userId)
                .thenApply(cache -> {
                    txCache.put(userId, cache);
                    return cache;
                });
    }

    private CompletableFuture<Map<String, Transaction>> loadUserTxData(final Person.Id userId) {
        log.info("Cache Miss for tx data for userId: {}", userId.getValue());
        // Use a map that preserves order for sorting
        final Map<String, Transaction> result = new ConcurrentSkipListMap<>();
        return persistence.query(qb -> txByUserId(qb, userId))
                .thenApply(resp -> resp.items().stream()
                        .map(m -> toTransaction(m.get(CONTENT)))
                        .filter(tx -> (tx != null) && (tx.getDeleted() == null))
                        .sorted(Comparator.comparing(Transaction::getTxDate))
                        .toList())
                .thenApply(list -> persistence.cacheAll(result, list, Transaction::getTxId))
                .thenApply(na -> result);
    }

    private Transaction toTransaction(final AttributeValue content) {
        try {
            return mapper.readValue(content.s(), Transaction.class);
        } catch (IOException ex) {
            log.error("Unable to parse record: " + content, ex);
            return null;
        }
    }

    private void txByUserId(final QueryRequest.Builder qb, final Person.Id userId) {
        qb.tableName(TRANSACTION_TABLE)
                .keyConditionExpression("userId = :userIdVal")
                .expressionAttributeValues(
                        Map.of(":userIdVal", AttributeValue.builder().s(userId.getValue()).build()));
    }
}
