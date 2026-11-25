package org.paulsens.trip.dynamo;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import org.paulsens.trip.metrics.PoolMetricPublisher;
import org.paulsens.trip.metrics.PoolMetricPublisher.PoolSample;
import org.testng.annotations.Test;

/**
 * The real DynamoDB client (against DynamoDB Local) reports its connection pool to the metrics publisher on every
 * call: the proof the {@code ddb.pool} metric rests on, since local mode and the webtests never build this client.
 */
public class PoolMetricsDynamoLocalTest {

    @Test
    public void everyCallReportsThePool() {
        final Persistence persistence = DynamoLocal.persistence();
        PoolMetricPublisher.INSTANCE.drain();
        final String table = DynamoLocal.tableNames().get(0);
        persistence.scan(scan -> scan.tableName(table).limit(1));
        persistence.scan(scan -> scan.tableName(table).limit(1));
        final PoolSample minute = PoolMetricPublisher.INSTANCE.drain();
        assertTrue(minute.leasedMax() >= 1, "a call leases a connection: " + minute);
        assertEquals(minute.calls(), 2L);
        assertEquals(minute.limit(), 200L, "the pool's size, MAX_CONNECTIONS");
        assertEquals(PoolMetricPublisher.INSTANCE.dropped(), 0L);
    }
}
