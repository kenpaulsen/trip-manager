package org.paulsens.trip.dynamo;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * What the versioned DAOs share (family, budget, expense report, organization, payment processor config, waiver
 * document and the five lodging rows): each saves with a conditional put on the row's {@code version} and
 * answers a lost check with "reload and try again".
 */
@Slf4j
final class VersionedWrites {
    /** Every versioned table keeps the row's JSON in this attribute. */
    private static final String CONTENT = "content";

    /** Every versioned table duplicates the row's version top-level, beside the JSON, so a condition can see it. */
    static final String VERSION = "version";

    private VersionedWrites() {
    }

    /**
     * The put every versioned save makes. Version 0 may only CREATE: refused when {@code createAttr} already
     * exists on a stored row with this key. Any other version may only replace that exact stored version. Both
     * names are aliased, sidestepping any reserved-word concern.
     *
     * @param createAttr a key attribute of the table, the one each DAO has always named. It is the sort key
     *                   where there is one, and any key attribute says the same thing: a row with this key exists.
     * @param expected   the version the caller's object was loaded with ({@code item} carries the next one).
     */
    static void conditionalPut(final PutItemRequest.Builder b, final String table,
            final Map<String, AttributeValue> item, final String createAttr, final long expected) {
        b.tableName(table).item(item);
        if (expected == 0L) {
            b.conditionExpression("attribute_not_exists(#i)").expressionAttributeNames(Map.of("#i", createAttr));
        } else {
            b.conditionExpression("#v = :expected")
                    .expressionAttributeNames(Map.of("#v", VERSION))
                    .expressionAttributeValues(Map.of(
                            ":expected", AttributeValue.builder().n(Long.toString(expected)).build()));
        }
    }

    /**
     * After a lost version check: put the row the STORE holds into the cache. The caller is told to reload and
     * try again, and that reload reads the cache; an entry still at the losing version (a write-through SET
     * that failed, which the caches deliberately survive) would hand back the same stale version every time,
     * so the record could not be edited until the soft TTL. A reload, never a removal: in local mode the cache
     * is the datastore.
     *
     * <p>The re-read is STRONGLY CONSISTENT, and is made here rather than handed in so that no DAO can forget
     * it. The check was lost milliseconds ago to a write that very likely wrote the winning row through to this
     * cache already; a default read can land on a replica that has not caught up, and putting that older row
     * would replace the winner's fresh entry with the version that just lost.</p>
     *
     * <p>Never lets its own failure hide the conflict being reported: a read or a cache put that throws is
     * logged, and the caller goes on to rethrow the conflict.</p>
     *
     * @param table    the table the refused put was for.
     * @param key      the row's full primary key.
     * @param parser   JSON to row; answers null (never throws) for content it cannot read.
     * @param cachePut the DAO's own write-through for a row of this type.
     */
    static <T> void recacheStored(final Persistence persistence, final String table,
            final Map<String, AttributeValue> key, final Function<String, T> parser, final Consumer<T> cachePut) {
        try {
            final AttributeValue content = persistence.getItem(b -> b.tableName(table).key(key).consistentRead(true))
                    .item().get(CONTENT);
            final T stored = (content == null) ? null : parser.apply(content.s());
            if (stored != null) {
                cachePut.accept(stored);
            }
        } catch (final RuntimeException ex) {
            log.warn("Unable to refresh the cached {} row after a version conflict", table, ex);
        }
    }
}
