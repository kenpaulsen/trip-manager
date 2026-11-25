package org.paulsens.trip.metrics;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import org.paulsens.trip.metrics.PoolMetricPublisher.PoolSample;
import software.amazon.awssdk.http.HttpMetric;
import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.metrics.MetricCollector;
import org.testng.annotations.Test;

/** The SDK's per-call pool numbers, folded into one minute's peaks; a report it cannot read never fails the call. */
public class PoolMetricPublisherTest {

    @Test
    public void eachMinuteKeepsThePeaksAndCountsTheCalls() {
        final PoolMetricPublisher publisher = new PoolMetricPublisher();
        publisher.publish(call(3, 0));
        publisher.publish(call(12, 2));
        publisher.publish(call(5, 0));
        assertEquals(publisher.drain(), new PoolSample(12, 2, 3, 200));
        assertEquals(publisher.drain(), new PoolSample(0, 0, 0, 200), "the next minute starts over; the size stays");
        publisher.close();
    }

    @Test
    public void aReportItCannotReadIsCountedAndDropped() {
        final PoolMetricPublisher publisher = new PoolMetricPublisher();
        final MetricCollection broken = mock(MetricCollection.class);
        when(broken.metricValues(HttpMetric.LEASED_CONCURRENCY)).thenThrow(new IllegalStateException("new SDK"));
        publisher.publish(broken);
        assertEquals(publisher.dropped(), 1L);
    }

    /** One API call's report, shaped as the SDK sends it: the HTTP numbers in a child collection. */
    static MetricCollection call(final int leased, final int pending) {
        final MetricCollector apiCall = MetricCollector.create("ApiCall");
        final MetricCollector http = apiCall.createChild("ApiCallAttempt").createChild("HttpClient");
        http.reportMetric(HttpMetric.LEASED_CONCURRENCY, leased);
        http.reportMetric(HttpMetric.PENDING_CONCURRENCY_ACQUIRES, pending);
        http.reportMetric(HttpMetric.MAX_CONCURRENCY, 200);
        return apiCall.collect();
    }
}
