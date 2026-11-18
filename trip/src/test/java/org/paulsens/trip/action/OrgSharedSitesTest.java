package org.paulsens.trip.action;

import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link OrgSharedSites}: the REST write of the Shared sites choice, seeded and stored as the Profile tab does. */
public class OrgSharedSitesTest {

    private static OrgCommands asSiteAdmin() {
        final Caller admin = new Caller(Person.Id.from("shared-admin-" + RandomData.genAlpha(10)), true,
                new AuditActor("admin@test", "admin"), Mockito.mock(PrivilegeCommands.class));
        return new OrgCommands(() -> admin);
    }

    @Test
    public void offIsStoredAsFalseAndOnAsTheAbsentValue() throws Exception {
        final OrgCommands orgs = asSiteAdmin();
        final Organization org = orgs.createOrganization("Shared " + RandomData.genAlpha(10), null, null);
        Assert.assertNotNull(org);
        final String orgId = org.getId().getValue();

        Assert.assertEquals(OrgSharedSites.save(orgs, orgId, false), OrgSharedSites.Outcome.SAVED);
        Assert.assertEquals(DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow()
                .getAllowSharedSites(), Boolean.FALSE);

        Assert.assertEquals(OrgSharedSites.save(orgs, " " + orgId + " ", true), OrgSharedSites.Outcome.SAVED,
                "the id is trimmed, as the page's edit seed trims it");
        Assert.assertNull(DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow()
                .getAllowSharedSites(), "allowed is never a stored true");
    }

    @Test
    public void anOrganizationThatCannotBeReadIsNotFound() {
        Assert.assertEquals(OrgSharedSites.save(asSiteAdmin(), "no-such-org-" + RandomData.genAlpha(8), false),
                OrgSharedSites.Outcome.NOT_FOUND);
        Assert.assertEquals(OrgSharedSites.save(asSiteAdmin(), " ", false), OrgSharedSites.Outcome.NOT_FOUND);
    }

    @Test
    public void aSaveTheCallerMayNotMakeFails() throws Exception {
        final Organization org = asSiteAdmin().createOrganization("Locked " + RandomData.genAlpha(10), null, null);
        final Caller stranger = new Caller(Person.Id.from("stranger-" + RandomData.genAlpha(10)), false,
                new AuditActor("x@test", "x"), Mockito.mock(PrivilegeCommands.class));
        Assert.assertEquals(OrgSharedSites.save(new OrgCommands(() -> stranger), org.getId().getValue(), false),
                OrgSharedSites.Outcome.FAILED, "the bean's own gate still decides");
        Assert.assertNull(DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow()
                .getAllowSharedSites(), "nothing was stored");
    }
}
