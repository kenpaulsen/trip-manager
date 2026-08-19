package org.paulsens.trip.dynamo;

import java.io.IOException;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** {@code organizations} rows through the DAO facade against the in-memory fake. */
public class OrganizationDAOTest {

    @Test
    public void saveAtVersionZeroCreatesAndBumpsTheVersion() throws IOException {
        final Organization org = newOrg("Create " + RandomData.genAlpha(8));
        assertTrue(DAO.getInstance().saveOrganization(org));
        assertEquals(org.getVersion(), 1L, "The in-memory object tracks the stored version");

        final Organization read = DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow();
        assertEquals(read, org);
    }

    @Test
    public void secondCreateOfTheSameIdIsRejected() throws IOException {
        final Organization first = newOrg("First " + RandomData.genAlpha(8));
        assertTrue(DAO.getInstance().saveOrganization(first));

        final Organization imposter = newOrg("Imposter");
        imposter.setId(first.getId());
        assertThrows(ConditionalCheckFailedException.class,
                () -> DAO.getInstance().saveOrganization(imposter));
        assertEquals(imposter.getVersion(), 0L, "A rejected save restores the caller's version");
    }

    @Test
    public void staleVersionSaveIsRejectedAndVersionRestored() throws IOException {
        final Organization org = newOrg("Race " + RandomData.genAlpha(8));
        assertTrue(DAO.getInstance().saveOrganization(org));

        final Organization winner = DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow();
        winner.setContactEmail("winner@example.com");
        assertTrue(DAO.getInstance().saveOrganization(winner));

        // The losing writer still holds version 1; its save must fail, not silently clobber the winner.
        org.setContactEmail("loser@example.com");
        assertThrows(ConditionalCheckFailedException.class, () -> DAO.getInstance().saveOrganization(org));
        assertEquals(org.getVersion(), 1L, "A rejected save restores the caller's version");
        assertEquals(DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow()
                .getContactEmail(), "winner@example.com");
    }

    @Test
    public void listContainsEverySavedOrganization() throws IOException {
        final Organization a = newOrg("ListA " + RandomData.genAlpha(8));
        final Organization b = newOrg("ListB " + RandomData.genAlpha(8));
        assertTrue(DAO.getInstance().saveOrganization(a));
        assertTrue(DAO.getInstance().saveOrganization(b));

        assertTrue(DAO.getInstance().getOrganizations(Cached.NO).containsAll(java.util.List.of(a, b)));
    }

    @Test
    public void unknownIdReadsEmpty() {
        assertTrue(DAO.getInstance()
                .getOrganization(Organization.Id.newInstance(), Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getOrganization(null, Cached.NO).isEmpty());
    }

    private static Organization newOrg(final String name) {
        return Organization.builder().name(name).build();
    }
}
