package org.paulsens.trip.content;

import java.util.Optional;
import java.util.Set;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link PageVersions}: a page's language versions, and that a version belongs to whatever its page belongs to. */
public class PageVersionsTest {

    private static final Organization.Id ORG = Organization.Id.from("3f2c1d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f");
    private static final String TRIP = "0c7e1a52-9b3d-4f60-8a21-6d5e4c3b2a19";
    private static final String ABOUT = OrgPageBootstrap.pageKey(ORG, "about");

    @Test
    public void aVersionKeyIsThePagesKeyPlusItsLanguage() {
        final String spanish = PageVersions.keyFor(ABOUT, Language.Spanish);
        Assert.assertEquals(spanish, ABOUT + "@es");
        Assert.assertEquals(PageVersions.baseOf(spanish), ABOUT);
        Assert.assertEquals(PageVersions.languageOf(spanish), Optional.of(Language.Spanish));
        Assert.assertEquals(PageVersions.keyFor(spanish, Language.Croatian), ABOUT + "@hr",
                "a version of a version is a version of the page: never two marks");
    }

    @Test
    public void aPagesOwnKeyAndAnythingElseHaveNoLanguage() {
        Assert.assertEquals(PageVersions.languageOf(ABOUT), Optional.empty());
        Assert.assertEquals(PageVersions.baseOf(ABOUT), ABOUT);
        Assert.assertEquals(PageVersions.languageOf(null), Optional.empty());
        Assert.assertNull(PageVersions.baseOf(null));
        Assert.assertEquals(PageVersions.languageOf(ABOUT + "@de"), Optional.empty(), "not a language we speak");
        Assert.assertEquals(PageVersions.languageOf(ABOUT + "@ES"), Optional.empty(), "exact tags only");
        Assert.assertEquals(PageVersions.languageOf(ABOUT + "@es-MX"), Optional.empty());
        Assert.assertEquals(PageVersions.baseOf(ABOUT + "@de"), ABOUT + "@de", "left alone, so it matches no page");
        Assert.assertEquals(PageVersions.languageOf("@es"), Optional.empty(), "a mark with no page before it");
    }

    @Test
    public void aReaderGetsTheirVersionWhenWrittenElseTheWholeOwnPage() {
        final Set<String> written = Set.of(ABOUT + "@es");
        Assert.assertEquals(resolve(Language.English, false, written), ABOUT, "the page's own language");
        Assert.assertEquals(resolve(Language.Spanish, false, written), ABOUT + "@es");
        Assert.assertEquals(resolve(Language.Croatian, false, written), ABOUT, "no Croatian version: fall back");
        Assert.assertEquals(resolve(Language.Croatian, true, written), ABOUT + "@hr",
                "an editor who names a version gets its key while it is still empty");
        Assert.assertEquals(resolve(null, false, written), ABOUT);
        Assert.assertNull(PageVersions.resolve(null, Language.English, Language.Spanish, true, written::contains));
    }

    @Test
    public void aVersionBelongsToTheSameOrganizationOrTripAsItsPage() {
        Assert.assertEquals(OrgPageBootstrap.orgOf(ABOUT + "@es"), ORG);
        Assert.assertEquals(OrgPageBootstrap.orgOf(OrgPageBootstrap.pageKey(ORG) + "@hr"), ORG);
        final String tripPage = OrgPageBootstrap.tripPageKey(TRIP);
        Assert.assertEquals(OrgPageBootstrap.tripOf(tripPage + "@es"), TRIP);
        Assert.assertNull(OrgPageBootstrap.orgOf(tripPage + "@es"), "a trip page is still not an org page");
        Assert.assertTrue(OrgPageBootstrap.isMarketingPageKey(OrgPageBootstrap.MARKETING_PAGE_KEY + "@es"));
        Assert.assertNull(OrgPageBootstrap.orgOf(V2PageBootstrap.PAGE_KEY + "@es"), "the shared page has no org");
    }

    @Test
    public void aMarkCannotSmuggleAnotherOrganizationIntoAKey() {
        final Organization.Id other = Organization.Id.from("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");
        // Whatever follows the page name, the org is read from the part BEFORE the mark and nothing after it.
        Assert.assertEquals(OrgPageBootstrap.orgOf(ABOUT + "@es"), ORG);
        Assert.assertNull(OrgPageBootstrap.orgOf(ABOUT + "@page:org:" + other.getValue() + ":about"),
                "not a language tag, so not a version and not a page name either");
        Assert.assertNull(OrgPageBootstrap.orgOf("page:org:" + other.getValue() + "@es:about@es"),
                "a mark inside the id leaves no valid org id");
    }

    private static String resolve(final Language wanted, final boolean explicit, final Set<String> written) {
        return PageVersions.resolve(ABOUT, Language.English, wanted, explicit, written::contains);
    }
}
