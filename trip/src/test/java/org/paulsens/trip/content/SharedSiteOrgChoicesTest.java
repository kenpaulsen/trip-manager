package org.paulsens.trip.content;

import java.util.List;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.site.ListingScope;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** The one shared-site curation prompt both org-listing types declare, and its org options. */
public class SharedSiteOrgChoicesTest {

    @BeforeClass
    public void init() {
        DAO.getInstance();
        FakeData.addFakeData();
    }

    @Test
    public void bothListingTypesDeclareTheSamePromptAndAnswerTheSameOptions() {
        final Placeholder property = SharedSiteOrgChoices.property();
        Assert.assertEquals(property.getName(), ListingScope.INCLUDE_ORGS_PROPERTY);
        Assert.assertEquals(property.getType(), Placeholder.Type.MULTI_CHOICE);
        Assert.assertFalse(property.isRequired(), "no pick = the no-list default");
        for (final String typeId : List.of("pilgrimages", "photo-albums")) {
            final ProgrammaticContentTemplate type = ProgrammaticTypes.byId(typeId).orElseThrow();
            Assert.assertTrue(type.getProperties().stream()
                    .anyMatch(ph -> ListingScope.INCLUDE_ORGS_PROPERTY.equals(ph.getName())), typeId);
            final List<ProgrammaticContentTemplate.Choice> choices =
                    type.choicesFor(ListingScope.INCLUDE_ORGS_PROPERTY);
            Assert.assertTrue(choices.stream().anyMatch(c -> FakeData.ACME_ORG_ID.equals(c.value())), typeId);
            Assert.assertTrue(choices.stream().anyMatch(c -> FakeData.CFPW_ORG_ID.equals(c.value())), typeId);
            Assert.assertTrue(type.choicesFor("nonsense").isEmpty());
        }
    }

    @Test
    public void sharedSiteOnlyPromptsAreHiddenOnAnOrgsOwnSite() throws Exception {
        final ContentInstance pilgrimages = new ContentInstance("ssp-1", "page:x", "Trips", "pilgrimages", 1,
                new java.util.HashMap<>(), null, 0, 1, null, null);
        final ContentInstance albums = new ContentInstance("ssp-2", "page:x", "Pics", "photo-albums", 1,
                new java.util.HashMap<>(), null, 0, 1, null, null);
        final ContentInstance text = new ContentInstance("ssp-3", "page:x", "Intro", "text-only", 1,
                new java.util.HashMap<>(), null, 0, 1, null, null);
        final org.paulsens.trip.action.ContentCommands content = new org.paulsens.trip.action.ContentCommands();
        // Shared site: every prompt shows.
        Assert.assertTrue(content.showsProperty(pilgrimages, "cfpwOnly"));
        Assert.assertTrue(content.showsProperty(pilgrimages, ListingScope.INCLUDE_ORGS_PROPERTY));
        Assert.assertTrue(content.showsProperty(pilgrimages, "language"));
        // An org's own site: provider and curation prompts are meaningless and hidden; the rest show.
        final org.paulsens.trip.site.SiteContext acme = org.paulsens.trip.site.SiteContext.org(
                Organization.Id.from(FakeData.ACME_ORG_ID), "acme", "acme.localhost");
        ScopedValue.where(org.paulsens.trip.audit.RequestContext.SCOPE,
                org.paulsens.trip.audit.RequestContext.of(null, null, acme)).call(() -> {
            Assert.assertFalse(content.showsProperty(pilgrimages, "cfpwOnly"));
            Assert.assertFalse(content.showsProperty(pilgrimages, ListingScope.INCLUDE_ORGS_PROPERTY));
            Assert.assertTrue(content.showsProperty(pilgrimages, "language"));
            Assert.assertTrue(content.showsProperty(pilgrimages, "maxCount"));
            Assert.assertFalse(content.showsProperty(albums, ListingScope.INCLUDE_ORGS_PROPERTY));
            Assert.assertTrue(content.showsProperty(albums, "windowDays"));
            Assert.assertTrue(content.showsProperty(text, "body"), "a STANDARD template has no such prompts");
            return null;
        });
        Assert.assertTrue(ProgrammaticTypes.byId("file").orElseThrow().sharedSiteOnlyProperties().isEmpty());
    }

    @Test
    public void anOptedOutOrgStaysPickableButSaysSo() throws Exception {
        final Organization acme = DAO.getInstance()
                .getOrganization(Organization.Id.from(FakeData.ACME_ORG_ID), Cached.NO).orElseThrow();
        acme.setAllowSharedSites(Boolean.FALSE);
        Assert.assertTrue(DAO.getInstance().saveOrganization(acme));
        try {
            final String label = SharedSiteOrgChoices.choices().stream()
                    .filter(c -> FakeData.ACME_ORG_ID.equals(c.value()))
                    .map(ProgrammaticContentTemplate.Choice::label)
                    .findFirst().orElseThrow();
            Assert.assertTrue(label.contains("opted out"), "the org side of the gate is visible: " + label);
        } finally {
            final Organization restore = DAO.getInstance()
                    .getOrganization(Organization.Id.from(FakeData.ACME_ORG_ID), Cached.NO).orElseThrow();
            restore.setAllowSharedSites(null);
            Assert.assertTrue(DAO.getInstance().saveOrganization(restore));
        }
        Assert.assertTrue(SharedSiteOrgChoices.choices().stream()
                .filter(c -> FakeData.ACME_ORG_ID.equals(c.value()))
                .noneMatch(c -> c.label().contains("opted out")), "restored");
    }
}
