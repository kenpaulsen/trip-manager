package org.paulsens.trip.action;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link OrgEditAudit}: an organization edit's audit row names a change of "Shared sites" and its direction, since
 * that checkbox is the organization's consent to being listed across organizations (medjugorje#155).
 */
public class OrgEditAuditTest {

    private static Organization org(final Boolean allowShared) {
        final Organization org = Organization.builder().name("Holy Redeemer Parish").build();
        org.setAllowSharedSites(allowShared);
        return org;
    }

    @Test
    public void anEditThatLeavesSharedSitesAloneSaysOnlyEdited() {
        Assert.assertEquals(OrgEditAudit.message(org(null), org(null)), "Organization 'Holy Redeemer Parish' edited");
        Assert.assertEquals(OrgEditAudit.message(org(Boolean.FALSE), org(Boolean.FALSE)),
                "Organization 'Holy Redeemer Parish' edited");
        Assert.assertEquals(OrgEditAudit.message(org(null), org(Boolean.TRUE)),
                "Organization 'Holy Redeemer Parish' edited", "never set and explicitly on are the same answer");
        Assert.assertEquals(OrgEditAudit.message(null, org(Boolean.FALSE)),
                "Organization 'Holy Redeemer Parish' edited", "nothing to compare with names no change");
    }

    @Test
    public void turningSharedSitesOffOrOnIsNamedWithItsDirection() {
        final String off = OrgEditAudit.message(org(null), org(Boolean.FALSE));
        Assert.assertTrue(off.startsWith("Organization 'Holy Redeemer Parish' edited; Shared sites turned off"), off);
        Assert.assertTrue(off.contains("Discover"), off);

        final String on = OrgEditAudit.message(org(Boolean.FALSE), org(null));
        Assert.assertTrue(on.startsWith("Organization 'Holy Redeemer Parish' edited; Shared sites turned on"), on);
        Assert.assertNotEquals(on, off, "the direction is in the row");
    }

    /**
     * The message is only as right as the two organizations handed to it. Through the REAL saves (the Profile
     * tab's, then the REST profile's shape: a fresh read, one field, {@code saveOrganization}), the row an
     * organization's trail gets says which way the box went, and a save that leaves it alone says so too: swapping
     * the arguments, or comparing the saved row with itself, fails here and nowhere else.
     */
    @Test
    public void aRealSaveAuditsWhichWaySharedSitesWent() throws IOException {
        final List<String> audited = new ArrayList<>();
        final OrgCommands cmds = recording(audited);
        final Organization org = cmds.createOrganization("Audited " + RandomData.genAlpha(10), null, null);
        Assert.assertNotNull(org);
        final String orgId = org.getId().getValue();
        final String edited = "Organization '" + org.getName() + "' edited";
        audited.clear();

        Assert.assertTrue(
                cmds.saveOrgProfile(orgId, org.getName(), null, null, null, null, null, Boolean.FALSE, null, null));
        Assert.assertEquals(audited.size(), 1, "one save, one row: " + audited);
        Assert.assertTrue(audited.get(0).startsWith(edited + "; Shared sites turned off"), audited.get(0));

        final Organization fresh = DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow();
        fresh.setSharedSitesAllowed(true);
        Assert.assertTrue(cmds.saveOrganization(fresh));
        Assert.assertEquals(audited.size(), 2, audited.toString());
        Assert.assertTrue(audited.get(1).startsWith(edited + "; Shared sites turned on"), audited.get(1));

        Assert.assertTrue(
                cmds.saveOrgProfile(orgId, org.getName(), null, null, null, null, null, Boolean.TRUE, null, null));
        Assert.assertEquals(audited.size(), 3, audited.toString());
        Assert.assertEquals(audited.get(2), edited, "left on: the row names no change");
    }

    /** A site admin's bean whose audit rows land in {@code audited} (the real row needs no reading back). */
    private static OrgCommands recording(final List<String> audited) {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        final Caller admin = new Caller(Person.Id.from("audit-admin-" + RandomData.genAlpha(10)), true,
                new AuditActor("admin@test", "admin"), none);
        return new OrgCommands(() -> admin) {
            @Override
            void audit(final Caller current, final Organization about, final String message) {
                audited.add(message);
                super.audit(current, about, message);
            }
        };
    }
}
