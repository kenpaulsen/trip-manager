package org.paulsens.trip.model;

import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The imported rows are written by a Python script but read by Jackson. A format mismatch would not fail
 * loudly -- it would make five years of history unreadable while the table looked full.
 *
 * <p>These are verbatim rows taken from the real table after the import.
 */
public class ImportedRowTest {

    private static final String LEGACY_LOGIN = "{\"timestamp\": \"2021-10-26T03:26:36.010000Z\", "
            + "\"action\": \"LOGIN\", \"outcome\": \"UNKNOWN\", \"actorEmail\": \"kenapaulsen@gmail.com\", "
            + "\"actorId\": \"c411fff9-4bb2-4957-8e85-aa194e66a399\", "
            + "\"message\": \"User kenapaulsen@gmail.com logged in\", \"schemaVersion\": 1}";

    @Test
    public void importedRowDeserializes() throws Exception {
        final AuditEvent event = DAO.getInstance().getMapper().readValue(LEGACY_LOGIN, AuditEvent.class);

        Assert.assertEquals(event.getAction(), AuditAction.LOGIN);
        Assert.assertEquals(event.getOutcome(), AuditOutcome.UNKNOWN);
        Assert.assertEquals(event.getActorEmail(), "kenapaulsen@gmail.com");
        Assert.assertEquals(event.getActorId(), "c411fff9-4bb2-4957-8e85-aa194e66a399");
        Assert.assertTrue(event.isLegacy(), "schemaVersion 1 must read as limited-detail");
    }

    @Test
    public void importedKeysMatchWhatTheImporterWrote() throws Exception {
        // If these disagree, a row is stored under a key the app would never query for: invisible, not lost.
        final AuditEvent event = DAO.getInstance().getMapper().readValue(LEGACY_LOGIN, AuditEvent.class);
        Assert.assertEquals(event.getPartition(), "2021-10-26");
        Assert.assertEquals(event.getSortKey(), "1635218796010");
    }

    @Test
    public void sixDigitFractionalSecondsParse() throws Exception {
        // 32 lines in the history carry 6 fractional digits rather than 9.
        final String json = LEGACY_LOGIN.replace("03:26:36.010000Z", "03:26:36.010Z");
        Assert.assertNotNull(DAO.getInstance().getMapper().readValue(json, AuditEvent.class).getTimestamp());
    }

    @Test
    public void multilineMessageSurvives() throws Exception {
        // The PayPal records contain embedded newlines; JSON escapes them, and they must come back intact.
        final String json = LEGACY_LOGIN.replace("User kenapaulsen@gmail.com logged in",
                "PayPal payment. Order: 85R050362M8685157.\\nPayPal fee: $0.53. Net: $0.57.");
        final AuditEvent event = DAO.getInstance().getMapper().readValue(json, AuditEvent.class);
        Assert.assertTrue(event.getMessage().contains("\n"), "The continuation line must survive: "
                + event.getMessage());
    }
}
