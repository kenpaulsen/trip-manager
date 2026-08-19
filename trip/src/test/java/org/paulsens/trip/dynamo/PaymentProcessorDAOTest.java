package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.util.List;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.ProcessorType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** {@code payment_processors} rows through the DAO facade against the in-memory fake. */
public class PaymentProcessorDAOTest {

    @Test
    public void saveGetDeleteRoundTripWithVersioning() throws IOException {
        final Organization.Id org = Organization.Id.newInstance();
        final PaymentProcessorConfig config = config(org, "Round Trip");
        assertTrue(DAO.getInstance().savePaymentProcessorConfig(config));
        assertEquals(config.getVersion(), 1L);

        final PaymentProcessorConfig read = DAO.getInstance()
                .getPaymentProcessorConfig(org, config.getId(), Cached.NO).orElseThrow();
        assertEquals(read, config);

        read.setLabel("Edited");
        assertTrue(DAO.getInstance().savePaymentProcessorConfig(read));

        // The first object still holds version 1: its save must lose, not clobber.
        config.setLabel("Stale write");
        assertThrows(ConditionalCheckFailedException.class,
                () -> DAO.getInstance().savePaymentProcessorConfig(config));
        assertEquals(config.getVersion(), 1L, "A rejected save restores the caller's version");

        assertTrue(DAO.getInstance().deletePaymentProcessorConfig(org, config.getId()));
        assertTrue(DAO.getInstance().getPaymentProcessorConfig(org, config.getId(), Cached.NO).isEmpty());
    }

    @Test
    public void orgPartitionsAreIsolated() throws IOException {
        final Organization.Id mine = Organization.Id.newInstance();
        final Organization.Id other = Organization.Id.newInstance();
        final PaymentProcessorConfig mineCfg = config(mine, "Mine");
        assertTrue(DAO.getInstance().savePaymentProcessorConfig(mineCfg));
        assertTrue(DAO.getInstance().savePaymentProcessorConfig(config(other, "Theirs")));

        assertTrue(DAO.getInstance().savePaymentProcessorConfig(config(mine, "Mine Too")));
        final List<PaymentProcessorConfig> listed =
                DAO.getInstance().getPaymentProcessorConfigs(mine, Cached.NO);
        assertEquals(listed.size(), 2, "Both of this org's configs, and only this org's");
        assertTrue(listed.stream().allMatch(cfg -> cfg.getOrgId().equals(mine)));
        // The composite get IS the tenancy check: a foreign config id misses.
        assertTrue(DAO.getInstance()
                .getPaymentProcessorConfig(other, mineCfg.getId(), Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getPaymentProcessorConfig(null, null, Cached.NO).isEmpty());
    }

    private static PaymentProcessorConfig config(final Organization.Id orgId, final String label) {
        return PaymentProcessorConfig.builder()
                .orgId(orgId).label(label).type(ProcessorType.FAKE).enabled(true).build();
    }
}
