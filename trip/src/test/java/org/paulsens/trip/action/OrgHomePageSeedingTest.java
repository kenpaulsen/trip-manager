package org.paulsens.trip.action;

import java.util.List;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * An organization's default home page appears exactly once: on the subdomain's first assignment (or the
 * first dashboard visit of an org slugged before seeding existed), and never again -- an org that empties
 * its page keeps it empty.
 */
public class OrgHomePageSeedingTest {

    private static final Person.Id SITE_ADMIN = Person.Id.from("seed-test-site-admin");
    private static final Person.Id STRANGER = Person.Id.from("seed-test-stranger");

    @BeforeClass
    public void init() {
        DAO.getInstance();
        FakeData.addFakeData();     // the starter templates the rows pin
    }

    private static OrgCommands as(final Person.Id who, final boolean siteAdmin) {
        return new OrgCommands(() -> new Caller(who, siteAdmin,
                new AuditActor(who.getValue() + "@example.com", who.getValue()), new PrivilegeCommands()));
    }

    private static Organization newOrg(final OrgCommands admin, final String name) {
        final Organization org = admin.createOrganization(name, null, null);
        Assert.assertNotNull(org, "fixture org");
        return org;
    }

    private static List<ContentInstance> page(final Organization org) {
        return DAO.getInstance().getContentForSection(OrgPageBootstrap.pageKey(org.getId()), Cached.NO);
    }

    private static Organization reread(final Organization org) {
        return DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow();
    }

    @Test
    public void assigningASubdomainSeedsTheStarterPageOnce() {
        final OrgCommands admin = as(SITE_ADMIN, true);
        final Organization org = newOrg(admin, "Seed Once " + UUID.randomUUID());
        Assert.assertTrue(page(org).isEmpty(), "no page before the org has a site");
        Assert.assertFalse(admin.ensureHomePage(org.getId().getValue()),
                "an org without a subdomain has no site to seed a page for");

        final String slug = "seed" + UUID.randomUUID().toString().substring(0, 8);
        Assert.assertTrue(admin.saveOrgEdits(org.getId().getValue(), org.getName(), null, null, null, null, slug));
        final List<ContentInstance> seeded = page(org);
        Assert.assertEquals(seeded.size(), 3, "the starter page: welcome, trips, pictures");
        Assert.assertTrue(seeded.stream().allMatch(row -> row.getVersion() == 1), "saved through the DAO");
        Assert.assertTrue(seeded.getFirst().getValues().get("body").contains("Welcome to " + org.getName()));
        Assert.assertNotNull(reread(org).getHomePageSeededAt(), "the org row records the seeding");

        // Idempotent: a second assignment, the lazy dashboard call, a rename -- nothing seeds twice.
        Assert.assertTrue(admin.ensureHomePage(org.getId().getValue()));
        Assert.assertTrue(admin.saveOrgEdits(org.getId().getValue(), org.getName() + "!", null, null, null,
                null, slug));
        Assert.assertEquals(page(org).size(), 3, "still exactly the three starter rows");
    }

    @Test
    public void anEmptiedPageStaysEmptyAndAHandAuthoredPageIsNeverOverwritten() {
        final OrgCommands admin = as(SITE_ADMIN, true);
        final Organization org = newOrg(admin, "Seed Keep " + UUID.randomUUID());
        final String slug = "keep" + UUID.randomUUID().toString().substring(0, 8);
        Assert.assertTrue(admin.saveOrgEdits(org.getId().getValue(), org.getName(), null, null, null, null, slug));
        for (final ContentInstance row : page(org)) {
            Assert.assertTrue(DAO.getInstance().deleteContent(row.getId()));
        }
        Assert.assertTrue(page(org).isEmpty());
        Assert.assertTrue(admin.ensureHomePage(org.getId().getValue()), "already seeded = true...");
        Assert.assertTrue(page(org).isEmpty(), "...and an emptied page is a choice, not a defect");

        // An org slugged before seeding existed, whose page someone already authored by hand.
        final Organization authored = newOrg(admin, "Seed Authored " + UUID.randomUUID());
        final String key = OrgPageBootstrap.pageKey(authored.getId());
        final ContentInstance own = new ContentInstance(UUID.randomUUID().toString(), key, "Mine", "text-only",
                1, new java.util.HashMap<>(java.util.Map.of("body", "<p>mine</p>")), null, 0, 0, null, "me");
        Assert.assertTrue(DAO.getInstance().saveContent(own, 5));
        authored.setSlug("hand" + UUID.randomUUID().toString().substring(0, 8));
        Assert.assertTrue(admin.saveOrganization(authored));
        Assert.assertTrue(admin.ensureHomePage(authored.getId().getValue()));
        final List<ContentInstance> kept = page(authored);
        Assert.assertEquals(kept.size(), 1, "the hand-authored page is left alone");
        Assert.assertEquals(kept.getFirst().getTitle(), "Mine");
        Assert.assertNotNull(reread(authored).getHomePageSeededAt(), "...but counts as seeded from now on");
    }

    @Test
    public void onlyTheOrgsManagersCanTriggerSeeding() {
        final OrgCommands admin = as(SITE_ADMIN, true);
        final Organization org = newOrg(admin, "Seed Authz " + UUID.randomUUID());
        org.setSlug("authz" + UUID.randomUUID().toString().substring(0, 8));
        Assert.assertTrue(admin.saveOrganization(org));
        Assert.assertFalse(as(STRANGER, false).ensureHomePage(org.getId().getValue()),
                "a non-admin visitor of the dashboard must not write the org's page");
        Assert.assertTrue(page(org).isEmpty());
        Assert.assertFalse(admin.ensureHomePage("no-such-org"));
        Assert.assertTrue(admin.ensureHomePage(org.getId().getValue()), "a site admin may");
        Assert.assertEquals(page(org).size(), 3);
    }
}
