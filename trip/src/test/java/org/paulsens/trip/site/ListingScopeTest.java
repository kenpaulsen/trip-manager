package org.paulsens.trip.site;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The one rule behind every public listing: own-only on an org site, double-gated on a shared site. */
public class ListingScopeTest {

    private static final String HOSTED = "11111111-1111-4111-8111-111111111111";      // has a subdomain
    private static final String SHARED_ONLY = "22222222-2222-4222-8222-222222222222"; // no site of its own
    private static final String OPTED_OUT = "33333333-3333-4333-8333-333333333333";   // hosted, opted out
    private static final String UNKNOWN = "44444444-4444-4444-8444-444444444444";

    private final Map<String, Organization> orgs = new HashMap<>();

    public ListingScopeTest() {
        orgs.put(HOSTED, org(HOSTED, "acme", null));
        orgs.put(SHARED_ONLY, org(SHARED_ONLY, null, null));
        orgs.put(OPTED_OUT, org(OPTED_OUT, "quiet", Boolean.FALSE));
    }

    private static Organization org(final String id, final String slug, final Boolean allowShared) {
        final Organization org = new Organization();
        org.setId(Organization.Id.from(id));
        org.setName(id.substring(0, 4));
        org.setSlug(slug);
        org.setAllowSharedSites(allowShared);
        return org;
    }

    private ListingScope scope(final SiteContext site, final List<String> include) {
        return new ListingScope(site, include, id -> Optional.ofNullable(orgs.get(id.getValue())));
    }

    @Test
    public void anOrgSiteListsOnlyItsOwnContent() {
        final ListingScope acme = scope(SiteContext.org(Organization.Id.from(HOSTED), "acme", "acme.x"), null);
        Assert.assertTrue(acme.shows(HOSTED));
        Assert.assertFalse(acme.shows(SHARED_ONLY));
        Assert.assertFalse(acme.shows(null), "org-less legacy content belongs to nobody");
        // Curation lists are a shared-site concept; they never widen an org site.
        Assert.assertFalse(scope(SiteContext.org(Organization.Id.from(HOSTED), "acme", "acme.x"),
                List.of(SHARED_ONLY)).shows(SHARED_ONLY));
    }

    @Test
    public void aSharedSiteWithoutACurationListShowsTheOrgsThatHaveNoSiteOfTheirOwn() {
        final ListingScope shared = scope(SiteContext.shared("www.visitqueenofpeace.com"), null);
        Assert.assertTrue(shared.shows(null), "legacy org-less content keeps listing");
        Assert.assertTrue(shared.shows(SHARED_ONLY), "today's tenants keep listing");
        Assert.assertFalse(shared.shows(HOSTED), "a newly hosted org is off the shared site until picked");
        Assert.assertFalse(shared.shows(UNKNOWN), "an unknown org id is listed only by explicit pick");
        Assert.assertFalse(shared.shows(OPTED_OUT));
        Assert.assertTrue(scope(SiteContext.marketing("unitetrip.com"), List.of()).shows(SHARED_ONLY),
                "an empty list is no list");
    }

    @Test
    public void aCurationListIsTheSiteSideOfTheDoubleGate() {
        final ListingScope picked = scope(SiteContext.shared("localhost"), List.of(HOSTED, OPTED_OUT, UNKNOWN));
        Assert.assertTrue(picked.shows(HOSTED), "picked by the site admin");
        Assert.assertFalse(picked.shows(SHARED_ONLY), "a list is exhaustive: unpicked orgs drop off");
        Assert.assertFalse(picked.shows(OPTED_OUT), "the org side wins: opted out is never shown");
        Assert.assertTrue(picked.shows(UNKNOWN), "an explicit pick lists even an org the cache cannot read");
        Assert.assertTrue(picked.shows(null), "legacy org-less content is unaffected by curation");
    }

    @Test
    public void theRequestBoundFactoryReadsTheSiteAndTheCachedOrgs() throws Exception {
        DAO.getInstance();
        // Unbound = the shared site: CFPW (no site of its own) lists by default, hosted Acme does not.
        Assert.assertTrue(ListingScope.forSite().shows(null));
        Assert.assertTrue(ListingScope.forSite().shows(FakeData.CFPW_ORG_ID));
        Assert.assertFalse(ListingScope.forSite().shows(FakeData.ACME_ORG_ID));
        Assert.assertEquals(ListingScope.parseIds(" a, b ,,c b "), List.of("a", "b", "c"));
        Assert.assertEquals(ListingScope.parseIds(null), List.of());
        Assert.assertFalse(ListingScope.forInstance(Map.of("other", "x")).shows(FakeData.ACME_ORG_ID));
        Assert.assertTrue(ListingScope.forInstance(Map.of(ListingScope.INCLUDE_ORGS_PROPERTY,
                FakeData.ACME_ORG_ID)).shows(FakeData.ACME_ORG_ID));
        Assert.assertFalse(ListingScope.forInstance(null).shows(FakeData.ACME_ORG_ID));
        final Organization.Id acme = Organization.Id.from(FakeData.ACME_ORG_ID);
        final Boolean onAcme = ScopedValue.where(RequestContext.SCOPE,
                RequestContext.of(null, null, SiteContext.org(acme, "acme", "acme.localhost")))
                .call(() -> ListingScope.forSite(List.of(FakeData.CFPW_ORG_ID)).shows(FakeData.CFPW_ORG_ID));
        Assert.assertFalse(onAcme, "on Acme's site, CFPW's content never lists, list or no list");
        Assert.assertTrue(ListingScope.forSite(List.of(FakeData.ACME_ORG_ID)).shows(FakeData.ACME_ORG_ID),
                "the shared site lists Acme once a site admin picks it (Acme allows shared sites)");
        Assert.assertFalse(ListingScope.forSite().shows("not-a-real-org-id"));
    }
}
