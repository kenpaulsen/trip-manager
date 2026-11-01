package org.paulsens.trip.audit;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link DynamoAuditSink} only ever touches the DAO from {@code store}, which runs when a record is queued, so
 * constructing and closing one is safe without AWS.
 */
public class DynamoAuditSinkTest {

    @Test
    public void theWriterIsAVirtualThread() {
        final DynamoAuditSink sink = new DynamoAuditSink();
        try {
            Assert.assertTrue(sink.writerThreadForTest().isVirtual(),
                    "the writer must be virtual: it blocks on a queue poll for the life of the task");
        } finally {
            sink.close();
        }
    }
}
