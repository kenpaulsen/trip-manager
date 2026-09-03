package org.paulsens.trip.site;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.paulsens.trip.model.Organization;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Host resolution grammar and snapshot lifecycle. Everything here runs against the injectable constructor
 * -- no DAO, no cache -- because resolution is on EVERY request path and must be provably a map lookup.
 */
public class SiteIndexTest {

    private static final String BASE = "unitetrip.com";

    private static Organization orgWithSlug(final String name, final String slug) {
        final Organization org = new Organization();
        org.setName(name);
        org.setSlug(slug);
        return org;
    }

    private static SiteIndex index(final List<Organization> orgs) {
        return new SiteIndex(() -> orgs, () -> BASE, System::currentTimeMillis);
    }

    @Test
    public void apexAndWwwAreTheMarketingSite() {
        final SiteIndex index = index(List.of());
        Assert.assertTrue(index.resolve("unitetrip.com").isMarketing());
        Assert.assertTrue(index.resolve("www.unitetrip.com").isMarketing());
    }

    @Test
    public void thePlatformOrgOwnsTheApexAndWww() {
        final Organization platform = orgWithSlug("UniteTrip", Organization.PLATFORM_SLUG);
        final SiteIndex index = index(List.of(platform, orgWithSlug("Acme", "acme")));
        for (final String host : List.of("unitetrip.com", "www.unitetrip.com", "www.localhost")) {
            final SiteContext site = index.resolve(host);
            Assert.assertTrue(site.isOrg(), host + " is the platform org's site");
            Assert.assertTrue(site.isMarketing(), host + " is still the marketing site");
            Assert.assertTrue(site.isPlatformOrg(), host);
            Assert.assertEquals(site.slug(), Organization.PLATFORM_SLUG, host);
            Assert.assertEquals(site.orgId(), platform.getId(), host);
        }
        Assert.assertTrue(index.resolve("localhost").isShared(), "plain localhost stays the classic site");
        Assert.assertFalse(index.resolve("acme.unitetrip.com").isMarketing());
    }

    @Test
    public void aKnownSlugResolvesToItsOrganization() {
        final Organization acme = orgWithSlug("Acme", "acme");
        final SiteContext site = index(List.of(acme, orgWithSlug("Other", "other"))).resolve("acme.unitetrip.com");
        Assert.assertTrue(site.isOrg());
        Assert.assertEquals(site.orgId(), acme.getId());
        Assert.assertEquals(site.slug(), "acme");
        Assert.assertEquals(site.host(), "acme.unitetrip.com");
    }

    @Test
    public void unknownLabelsUnderTheBaseAreUnknownNeverShared() {
        // Serving the shared site on a typo'd label would look like an endorsement of the address --
        // and once orgs exist, it would render OTHER tenants' data under an org-shaped hostname.
        final SiteIndex index = index(List.of(orgWithSlug("Acme", "acme")));
        Assert.assertTrue(index.resolve("typo.unitetrip.com").isUnknown());
        Assert.assertTrue(index.resolve("a.b.unitetrip.com").isUnknown(),
                "multi-level labels are never org sites");
        Assert.assertTrue(index.resolve("evil.acme.unitetrip.com").isUnknown());
    }

    @Test
    public void hostsOffTheBaseDomainStaySharedAndNeverThrow() {
        final SiteIndex index = index(List.of(orgWithSlug("Acme", "acme")));
        Assert.assertTrue(index.resolve("visitqueenofpeace.com").isShared());
        Assert.assertTrue(index.resolve("www.centerforpeacewest.com").isShared());
        Assert.assertTrue(index.resolve("10.1.2.3").isShared());
        Assert.assertTrue(index.resolve("someunitetrip.com").isShared(),
                "a suffix match must be on the dot boundary, not a string suffix");
        Assert.assertTrue(index.resolve(null).isShared());
        Assert.assertTrue(index.resolve("  ").isShared());
    }

    @Test
    public void localhostGrammarMirrorsTheBaseButKeepsPlainLocalhostShared() {
        final SiteIndex index = index(List.of(orgWithSlug("Acme", "acme")));
        Assert.assertTrue(index.resolve("localhost").isShared(),
                "every local page, test and script depends on plain localhost staying the classic site");
        Assert.assertTrue(index.resolve("www.localhost").isMarketing());
        final SiteContext acme = index.resolve("acme.localhost");
        Assert.assertTrue(acme.isOrg());
        Assert.assertEquals(acme.slug(), "acme");
        Assert.assertTrue(index.resolve("nope.localhost").isUnknown());
    }

    @Test
    public void hostsNormalizeCaseAndTrailingDot() {
        final SiteIndex index = index(List.of(orgWithSlug("Acme", "acme")));
        Assert.assertTrue(index.resolve("ACME.UniteTrip.Com").isOrg());
        Assert.assertTrue(index.resolve("acme.unitetrip.com.").isOrg());
        Assert.assertTrue(index.resolve("WWW.UNITETRIP.COM").isMarketing());
    }

    @Test
    public void blankAndNullSlugsNeverIndex() {
        final SiteIndex index = index(List.of(orgWithSlug("NoSite", null), orgWithSlug("Blank", "  ")));
        Assert.assertTrue(index.resolve("nosite.unitetrip.com").isUnknown());
    }

    @Test
    public void snapshotIsReusedUntilStaleAndRefreshForcesARebuild() {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicLong now = new AtomicLong(1_000_000L);
        final SiteIndex index = new SiteIndex(
                () -> {
                    loads.incrementAndGet();
                    return List.of(orgWithSlug("Acme", "acme"));
                },
                () -> BASE, now::get);
        index.resolve("acme.unitetrip.com");
        index.resolve("acme.unitetrip.com");
        Assert.assertEquals(loads.get(), 1, "resolves inside the interval must not reload");
        now.addAndGet(SiteIndex.REFRESH_INTERVAL.toMillis() + 1);
        index.resolve("acme.unitetrip.com");
        Assert.assertEquals(loads.get(), 2, "a stale snapshot rebuilds on the next resolve");
        index.refresh();
        Assert.assertEquals(loads.get(), 3, "refresh() rebuilds immediately -- the org-save hook");
    }

    @Test
    public void aFailedRebuildKeepsServingThePreviousSlugsAndRetriesSoon() {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicLong now = new AtomicLong(0L);
        final Supplier<List<Organization>> loader = () -> {
            if (loads.incrementAndGet() == 2) {
                throw new IllegalStateException("cache outage");
            }
            return List.of(orgWithSlug("Acme", "acme"));
        };
        final SiteIndex index = new SiteIndex(loader, () -> BASE, now::get);
        Assert.assertTrue(index.resolve("acme.unitetrip.com").isOrg());
        now.addAndGet(SiteIndex.REFRESH_INTERVAL.toMillis() + 1);
        Assert.assertTrue(index.resolve("acme.unitetrip.com").isOrg(),
                "the outage rebuild keeps the previous snapshot's slugs");
        Assert.assertEquals(loads.get(), 2);
        now.addAndGet(SiteIndex.FAILURE_RETRY.toMillis() + 1);
        Assert.assertTrue(index.resolve("acme.unitetrip.com").isOrg());
        Assert.assertEquals(loads.get(), 3, "a failed rebuild retries on the shorter interval");
    }

    @Test
    public void aColdStartFailureAnswersUnknownUnderTheBaseNotShared() {
        final SiteIndex index = new SiteIndex(
                () -> {
                    throw new IllegalStateException("cold cache outage");
                },
                () -> BASE, System::currentTimeMillis);
        Assert.assertTrue(index.resolve("acme.unitetrip.com").isUnknown(),
                "with no snapshot, an org label answers a cheap 404 -- never another tenant's site");
        Assert.assertTrue(index.resolve("visitqueenofpeace.com").isShared());
    }

    @Test
    public void aFailedBaseDomainReadFallsBackToThePreviousBase() {
        final AtomicInteger baseReads = new AtomicInteger();
        final AtomicLong now = new AtomicLong(0L);
        final SiteIndex index = new SiteIndex(
                () -> List.of(orgWithSlug("Acme", "acme")),
                () -> {
                    if (baseReads.incrementAndGet() > 1) {
                        throw new IllegalStateException("config outage");
                    }
                    return BASE;
                },
                now::get);
        Assert.assertTrue(index.resolve("acme.unitetrip.com").isOrg());
        now.addAndGet(SiteIndex.REFRESH_INTERVAL.toMillis() + 1);
        Assert.assertTrue(index.resolve("acme.unitetrip.com").isOrg(),
                "the previous base domain survives a settings-read failure");
    }

    @Test
    public void theSingletonSeesAnOrgSaveImmediately() throws Exception {
        // Not the FakeData seeds -- suite-mates may clear the local cache (which IS the datastore here).
        // A fresh save must be resolvable at once: DAO.saveOrganization's refresh hook is the contract
        // that makes slug assignment an ONLINE tier change.
        final String slug = "s" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        final Organization saved = new Organization();
        saved.setName("SiteIndex singleton " + slug);
        saved.setSlug(slug);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(saved));
        final SiteContext site = SiteIndex.getInstance().resolve(slug + ".localhost");
        Assert.assertTrue(site.isOrg());
        Assert.assertEquals(site.orgId(), saved.getId());
        Assert.assertTrue(SiteIndex.getInstance().resolve("unitetrip.com").isMarketing(),
                "the shipped default base domain drives the singleton's marketing host");
    }

    @Test
    public void contextFactoriesAnswerTheirModes() {
        Assert.assertTrue(SiteContext.shared("x").isShared());
        Assert.assertTrue(SiteContext.marketing("x").isMarketing());
        Assert.assertTrue(SiteContext.unknown("x").isUnknown());
        final SiteContext org = SiteContext.org(Organization.Id.from("o-1"), "acme", "acme.unitetrip.com");
        Assert.assertTrue(org.isOrg());
        Assert.assertFalse(org.isShared());
        Assert.assertEquals(new SiteContext(null, null, null, "h").mode(), SiteContext.Mode.SHARED,
                "a null mode normalizes to SHARED -- resolution must never produce a null-mode context");
    }
}
