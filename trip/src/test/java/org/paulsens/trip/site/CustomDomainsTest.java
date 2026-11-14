package org.paulsens.trip.site;

import java.util.List;
import org.paulsens.trip.model.Organization;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The custom-domain grammar the writer, the host index and the mail rule share. */
public class CustomDomainsTest {

    private static final List<String> SHARED = List.of("unitetrip.com", "visitqueenofpeace.com");

    @Test
    public void normalizeStoresOneSpellingOfWhatAnAdminTyped() {
        Assert.assertNull(CustomDomains.normalize(null));
        Assert.assertNull(CustomDomains.normalize("   "));
        Assert.assertNull(CustomDomains.normalize("https://"));
        Assert.assertEquals(CustomDomains.normalize(" Example.ORG "), "example.org");
        Assert.assertEquals(CustomDomains.normalize("https://www.example.org/trips?x=1"), "example.org");
        Assert.assertEquals(CustomDomains.normalize("trips.example.org:443"), "trips.example.org");
        Assert.assertEquals(CustomDomains.normalize("example.org.."), "example.org");
        Assert.assertEquals(CustomDomains.normalize("http://www.example.org#top"), "example.org#top",
                "a fragment is not stripped: the grammar check refuses it");
    }

    @Test
    public void problemAcceptsPublicHostNames() {
        for (final String ok : List.of("example.org", "trips.example.org", "a-b.example.co.uk", "beta-corp.test")) {
            Assert.assertNull(CustomDomains.problem(ok, "unitetrip.com", SHARED), ok);
        }
    }

    @Test
    public void problemRefusesWhatNoSingleOrgMayClaim() {
        Assert.assertNotNull(CustomDomains.problem(null, "unitetrip.com", SHARED));
        for (final String bad : List.of("example", "-x.example.org", "x_y.example.org", "example.org#top",
                "1.2.3.4", "x".repeat(250) + ".org")) {
            Assert.assertNotNull(CustomDomains.problem(bad, "unitetrip.com", SHARED), bad);
        }
        Assert.assertTrue(CustomDomains.problem("my.local", "unitetrip.com", SHARED).contains("public"));
        Assert.assertTrue(CustomDomains.problem("unitetrip.com", "unitetrip.com", List.of()).contains("subdomain"));
        Assert.assertTrue(CustomDomains.problem("acme.unitetrip.com", "UniteTrip.com", List.of())
                .contains("subdomain"));
        Assert.assertTrue(CustomDomains.problem("visitqueenofpeace.com", "unitetrip.com", SHARED)
                .contains("shared"));
        Assert.assertTrue(CustomDomains.problem("files.visitqueenofpeace.com", "unitetrip.com", SHARED)
                .contains("shared"));
        Assert.assertNull(CustomDomains.problem("example.org", null, List.of()), "no base configured");
    }

    @Test
    public void claimProblemAlsoRefusesAnotherOrgsDomain() {
        final Organization mine = new Organization();
        final Organization other = new Organization();
        other.setCustomDomain("www.example.org");
        final List<Organization> orgs = List.of(mine, other);
        Assert.assertTrue(CustomDomains.claimProblem("example.org", mine.getId(), orgs, "unitetrip.com", SHARED)
                .contains("Another organization"));
        Assert.assertNull(CustomDomains.claimProblem("example.org", other.getId(), orgs, "unitetrip.com", SHARED),
                "re-saving your own domain is fine");
        Assert.assertNotNull(CustomDomains.claimProblem("bad", mine.getId(), orgs, "unitetrip.com", SHARED));
    }

    @Test
    public void hostsAndListsParse() {
        Assert.assertEquals(CustomDomains.hostsOf("example.org"), List.of("example.org", "www.example.org"));
        Assert.assertEquals(CustomDomains.parseList(null), List.of());
        Assert.assertEquals(CustomDomains.parseList(" "), List.of());
        Assert.assertEquals(CustomDomains.parseList("UniteTrip.com, visitqueenofpeace.com,,unitetrip.com  x.org"),
                List.of("unitetrip.com", "visitqueenofpeace.com", "x.org"));
    }
}
