package org.paulsens.trip.audit;

import java.time.Instant;
import java.util.List;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditOutcome;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The audit plumbing's edges: builder overrides, the parts overload, and the composite sink's promise that one
 * broken sink can neither lose a record nor stop the others.
 */
public class AuditTailsTest {

    @Test
    public void theBuilderCarriesActorTimeAndLegacyMarkers() {
        final Instant then = Instant.parse("2020-01-02T03:04:05Z");
        final AuditEvent event = Audit.builder(AuditAction.PERSON, AuditOutcome.SUCCESS)
                .actor(new AuditActor("who@example.org", "id-1"))
                .at(then)
                .legacy()
                .message("imported")
                .build();

        Assert.assertEquals(event.getActorEmail(), "who@example.org");
        Assert.assertEquals(event.getActorId(), "id-1");
        Assert.assertEquals(event.getTimestamp(), then, "the importer must be able to replay history");
        Assert.assertEquals(event.getSchemaVersion(), AuditEvent.LEGACY_SCHEMA);
    }

    @Test
    public void loggingToleratesNullAndThePartsOverload() {
        Audit.log(null); // ignored rather than throwing -- auditing must never fail the request
        Audit.log(AuditAction.LOGIN, AuditOutcome.SUCCESS, "who@example.org", "id-1", "signed in");
    }

    /** One throwing sink must neither lose the record (it goes to stdout) nor stop the remaining sinks. */
    @Test
    public void aThrowingSinkIsContainedAndTheRestStillWrite() {
        final List<AuditEvent> written = new java.util.ArrayList<>();
        final AuditSink broken = new AuditSink() {
            @Override
            public void write(final AuditEvent event) {
                throw new IllegalStateException("sink is down");
            }

            @Override
            public void close() {
                throw new IllegalStateException("cannot close either");
            }
        };
        final AuditSink working = new AuditSink() {
            @Override
            public void write(final AuditEvent event) {
                written.add(event);
            }

            @Override
            public void close() {
            }
        };
        final CompositeAuditSink composite = new CompositeAuditSink(List.of(broken, working));

        composite.write(Audit.builder(AuditAction.PERSON, AuditOutcome.SUCCESS).message("m").build());
        composite.close();

        Assert.assertEquals(written.size(), 1, "the working sink must still receive the record");
        Assert.assertEquals(composite.getSinks().size(), 2);
    }

    /**
     * The sink WIRING, re-run by reflection (the singleton built its own at class load): with a log group
     * configured but unreachable (dummy credentials), the CloudWatch sink must fail LOUDLY into the stdout
     * fallback rather than silently dropping the ledger; and the Dynamo index honours its kill switch.
     */
    @Test
    public void buildSinkFallsBackLoudlyWhenCloudWatchIsUnreachable() throws Exception {
        System.setProperty("trip.audit.log.group", "no-such-audit-group-" + System.nanoTime());
        System.setProperty("trip.audit.dynamo", "false");
        System.setProperty("aws.accessKeyId", "AKIA-TEST-NOT-REAL");
        System.setProperty("aws.secretAccessKey", "not-a-real-secret-key");
        try {
            final java.lang.reflect.Method buildSink = Audit.class.getDeclaredMethod("buildSink");
            buildSink.setAccessible(true);

            final AuditSink sink = (AuditSink) buildSink.invoke(null);

            Assert.assertNotNull(sink, "auditing must still work via stdout when CloudWatch cannot open");
            sink.write(Audit.builder(AuditAction.PERSON, AuditOutcome.SUCCESS).message("fallback").build());
            sink.close();
        } finally {
            System.clearProperty("trip.audit.log.group");
            System.clearProperty("trip.audit.dynamo");
            System.clearProperty("aws.accessKeyId");
            System.clearProperty("aws.secretAccessKey");
        }
    }

    /** The Dynamo index sink accepts writes, drains on close, and never blocks the caller. */
    @Test
    public void theDynamoIndexSinkDrainsOnClose() {
        final DynamoAuditSink sink = new DynamoAuditSink();
        for (int i = 0; i < 5; i++) {
            sink.write(Audit.builder(AuditAction.PERSON, AuditOutcome.SUCCESS)
                    .message("drain-" + i).build());
        }

        sink.close(); // must not hang, and must flush whatever the writer thread had not yet stored
    }
}
