package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.PartitionCache;
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

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final CacheClient cacheClient;
    private final PartitionCache<String, Transaction> cache;

    protected TransactionDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected TransactionDAO(final ObjectMapper mapper, final Persistence persistence,
            final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        this.cache = PartitionCache.<String, Transaction>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.TX_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .idGetter(Transaction::getTxId)
                .idFormatter(txId -> txId)
                .serializer(this::toJson)
                .deserializer(this::parseTransaction)
                .order(Comparator.comparing(Transaction::getTxId))
                .build();
    }

    protected CompletableFuture<List<Transaction>> getTransactions(final Person.Id userId) {
        return cache.getAll(userId.getValue(), () -> loadUserTxData(userId))
                .thenApply(list -> persistence.sortList(list, Comparator.comparing(Transaction::getTxDate)));
    }

    protected CompletableFuture<Optional<Transaction>> getTransaction(final Person.Id userId, final String txId) {
        return cache.getOne(userId.getValue(), txId, () -> loadUserTxData(userId));
    }

    protected CompletableFuture<Boolean> saveTransaction(final Transaction tx) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(USER_ID, AttributeValue.builder().s(tx.getUserId().getValue()).build());
        map.put(TX_ID, AttributeValue.builder().s(tx.getTxId()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(tx)).build());
        return persistence.putItem(b -> b.tableName(TRANSACTION_TABLE).item(map))
                .thenCompose(resp -> resp.sdkHttpResponse().isSuccessful()
                        ? updateCacheForTx(tx)
                        : CompletableFuture.completedFuture(false));
    }

    public void clearCache() {
        cacheClient.clearNamespace(CacheKeys.TX_PREFIX).join();
    }

    private CompletableFuture<Boolean> updateCacheForTx(final Transaction tx) {
        final String partition = tx.getUserId().getValue();
        return (tx.getDeleted() == null)
                ? cache.put(partition, tx)
                : cache.remove(partition, tx.getTxId());
    }

    private CompletableFuture<List<Transaction>> loadUserTxData(final Person.Id userId) {
        log.info("Cache Miss for tx data for userId: {}", userId.getValue());
        return persistence.queryAll(qb -> txByUserId(qb, userId))
                .thenApply(items -> items.stream()
                        .map(m -> toTransaction(m.get(CONTENT)))
                        .filter(tx -> (tx != null) && (tx.getDeleted() == null))
                        .toList());
    }

    private Transaction toTransaction(final AttributeValue content) {
        return (content == null) ? null : parseTransaction(content.s());
    }

    private Transaction parseTransaction(final String json) {
        try {
            return mapper.readValue(json, Transaction.class);
        } catch (final IOException ex) {
            log.error("Unable to parse record: " + json, ex);
            return null;
        }
    }

    private String toJson(final Transaction tx) {
        try {
            return mapper.writeValueAsString(tx);
        } catch (final IOException ex) {
            log.error("Unable to serialize transaction: " + tx.getTxId(), ex);
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
