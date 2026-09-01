package org.paulsens.trip.content;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The org-site page keys and the once-only starter page an organization's site begins with. */
public class OrgPageBootstrapTest {

    private static final Organization.Id ORG = Organization.Id.from("0f9a2b3c-4d5e-4f60-8a71-b2c3d4e5f607");

    @Test
    public void pageKeysCarryTheOrgIdAndRoundTrip() {
        final String key = OrgPageBootstrap.pageKey(ORG);
        Assert.assertEquals(key, "page:org:" + ORG.getValue() + ":home");
        Assert.assertEquals(OrgPageBootstrap.orgOf(key), ORG);
    }

    @Test
    public void onlyOrgPageKeysResolveToAnOrg() {
        Assert.assertNull(OrgPageBootstrap.orgOf(null));
        Assert.assertNull(OrgPageBootstrap.orgOf(V2PageBootstrap.PAGE_KEY), "the shared page has no org");
        Assert.assertNull(OrgPageBootstrap.orgOf(OrgPageBootstrap.MARKETING_PAGE_KEY));
        Assert.assertNull(OrgPageBootstrap.orgOf("events"), "a container id is not a page key");
        Assert.assertNull(OrgPageBootstrap.orgOf("page:org::home"), "an empty id is not an org");
        Assert.assertNull(OrgPageBootstrap.orgOf("page:org:" + ORG.getValue()), "the suffix is part of the key");
    }

    @Test
    public void theStarterPageIsSmallPinnedToCurrentTemplatesAndUniquelyIdentified() {
        final Organization org = new Organization();
        org.setName("Acme <Widgets> & \"Co\"");
        final List<ContentInstance> rows = OrgPageBootstrap.rows(org, id -> 7);

        Assert.assertEquals(rows.size(), 3, "less is more: welcome, trips, pictures");
        final Set<String> ids = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            final ContentInstance row = rows.get(i);
            Assert.assertEquals(row.getSection(), OrgPageBootstrap.pageKey(org.getId()));
            Assert.assertEquals(row.getPosition(), i, "display order follows list order");
            Assert.assertEquals(row.getTemplateVersion(), 7, "pinned to the CURRENT template version");
            Assert.assertEquals(row.getVersion(), 0, "unsaved: the save path assigns v1");
            Assert.assertEquals(row.getModifiedBy(), OrgPageBootstrap.SEED_AUTHOR);
            Assert.assertTrue(ids.add(row.getId()), "minted ids never collide");
            Assert.assertTrue(row.getId().length() >= 32, "a UUID, never a guessable page anchor");
        }
        Assert.assertEquals(rows.get(0).getTemplateId(), StarterTemplates.TEXT_ONLY_ID);
        Assert.assertEquals(rows.get(1).getTemplateId(), StarterTemplates.PILGRIMAGES_ID);
        Assert.assertEquals(rows.get(1).getValues().get("language"), "English");
        Assert.assertEquals(rows.get(1).getValues().get("cfpwOnly"), "false");
        Assert.assertEquals(rows.get(2).getTemplateId(), StarterTemplates.PHOTO_ALBUMS_ID);
    }

    @Test
    public void theWelcomeEscapesTheOrgNameAndReadsAsAPlaceholder() {
        final String body = OrgPageBootstrap.welcomeBody("Acme <Widgets> & \"Co\"");
        Assert.assertTrue(body.contains("Welcome to Acme &lt;Widgets&gt; &amp; &quot;Co&quot;"),
                "the org name is data inside authored HTML: " + body);
        Assert.assertFalse(body.contains("<Widgets>"), "raw markup from a name must never reach the page");
        Assert.assertTrue(body.contains("Replace this introduction"), "obviously a placeholder");
        Assert.assertEquals(OrgPageBootstrap.escape(null), "");
    }
}
