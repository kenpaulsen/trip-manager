package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Privilege-only org-site editing: {@code contentAdmin} / {@code mediaAdmin} scoped to an organization edit
 * that org's own page, templates and media, and nothing shared, nothing of another tenant's -- and being
 * an org ADMIN grants none of it.
 */
public class OrgSiteEditorAuthzTest {

    private static final Person.Id EDITOR = Person.Id.from("acme-site-editor");
    private static final Person.Id ORG_ADMIN_ONLY = Person.Id.from("acme-org-admin-no-priv");
    private static final Person.Id PLATFORM_EDITOR = Person.Id.from("platform-site-editor");
    private static final Organization.Id ACME = Organization.Id.from(FakeData.ACME_ORG_ID);
    private static final Organization.Id BETA = Organization.Id.from(FakeData.BETA_ORG_ID);
    private static final SiteContext ACME_SITE = SiteContext.org(ACME, "acme", "acme.localhost");
    private static final SiteContext BETA_SITE = SiteContext.org(BETA, "beta", "beta.localhost");

    private final PrivilegeCommands priv = new PrivilegeCommands();

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        FakeData.addFakeData();
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.CONTENT_ADMIN, "acme content",
                FakeData.ACME_ORG_ID, List.of(EDITOR)));
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.MEDIA_ADMIN, "acme media",
                FakeData.ACME_ORG_ID, List.of(EDITOR)));
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.CONTENT_ADMIN, "platform content",
                FakeData.PLATFORM_ORG_ID, List.of(PLATFORM_EDITOR)));
    }

    /**
     * The marketing page is the platform organization's: its own org-scoped editor edits it (and only it),
     * while another tenant's editor still cannot -- the page key is fixed, the ownership comes from the
     * org that holds the www slug.
     */
    @Test
    public void thePlatformOrgsEditorEditsTheMarketingPage() {
        final ContentCommands editor = contentAs(PLATFORM_EDITOR);
        Assert.assertTrue(editor.canEdit(OrgPageBootstrap.MARKETING_PAGE_KEY, PLATFORM_EDITOR));
        Assert.assertTrue(editor.canEditPage(OrgPageBootstrap.MARKETING_PAGE_KEY, PLATFORM_EDITOR));
        Assert.assertFalse(editor.canEdit(FakeData.PAGE_KEY, PLATFORM_EDITOR), "never the shared page");
        Assert.assertFalse(editor.canEdit(OrgPageBootstrap.pageKey(ACME), PLATFORM_EDITOR), "never a tenant's");
        Assert.assertFalse(contentAs(EDITOR).canEdit(OrgPageBootstrap.MARKETING_PAGE_KEY, EDITOR));
    }

    private static ContentCommands contentAs(final Person.Id who) {
        final ContentCommands content = new ContentCommands();
        content.setCallerSource(() -> TestCallers.person(who));
        return content;
    }

    private static TemplateCommands templatesAs(final Person.Id who) {
        final TemplateCommands templates = new TemplateCommands();
        templates.setCallerSource(() -> TestCallers.person(who));
        return templates;
    }

    private static MediaCommands mediaAs(final Person.Id who) {
        final MediaCommands media = new MediaCommands();
        media.setCallerSource(() -> TestCallers.person(who));
        return media;
    }

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    private static ContentInstance section(final String key) {
        return new ContentInstance(UUID.randomUUID().toString(), key, "t", "text-only", 1,
                new HashMap<>(Map.of("body", "<p>x</p>")), null, 0, 0, null, "test");
    }

    @Test
    public void anOrgScopedContentAdminEditsOnlyThatOrgsPage() {
        final ContentCommands editor = contentAs(EDITOR);
        final String acmePage = OrgPageBootstrap.pageKey(ACME);
        Assert.assertTrue(editor.canEdit(acmePage, EDITOR));
        Assert.assertTrue(editor.canEditPage(acmePage, EDITOR));
        Assert.assertFalse(editor.canEdit(FakeData.PAGE_KEY, EDITOR), "never the shared page");
        Assert.assertFalse(editor.canEdit(OrgPageBootstrap.MARKETING_PAGE_KEY, EDITOR), "never the marketing page");
        Assert.assertFalse(editor.canEdit(OrgPageBootstrap.pageKey(BETA), EDITOR), "never another tenant's page");
        Assert.assertFalse(editor.canEdit("events", EDITOR), "a shared-page container is the shared page's");

        Assert.assertTrue(editor.saveContent(section(acmePage)), "saves into the org's own page");
        Assert.assertFalse(editor.saveContent(section(FakeData.PAGE_KEY)), "refused on the shared page");
        Assert.assertFalse(editor.saveContent(section("events")), "a forged shared-page container section");
        Assert.assertFalse(editor.saveContent(section(OrgPageBootstrap.pageKey(BETA))));

        // A container on the org's page: its children (section = the container id) are the org's too.
        final ContentInstance holder = new ContentInstance(UUID.randomUUID().toString(), acmePage, "Holder",
                "container", 1, new HashMap<>(), null, 1, 0, null, "test");
        Assert.assertTrue(DAO.getInstance().saveContent(holder, 5));
        Assert.assertTrue(editor.canEdit(holder.getId(), EDITOR));
        Assert.assertTrue(editor.saveContent(section(holder.getId())));
        Assert.assertEquals(ContentCommands.orgOf(holder.getId(), holder), ACME);
        Assert.assertNull(ContentCommands.orgOf("events", DAO.getInstance().getContent("events", Cached.NO)
                .orElse(null)));

        // Being the org's ADMIN grants nothing: editing is privilege-only.
        final ContentCommands orgAdmin = contentAs(ORG_ADMIN_ONLY);
        Assert.assertFalse(orgAdmin.canEdit(acmePage, ORG_ADMIN_ONLY));
        Assert.assertFalse(orgAdmin.saveContent(section(acmePage)));
    }

    @Test
    public void anOrgScopedContentAdminAuthorsOnlyThatOrgsTemplates() {
        final TemplateCommands editor = templatesAs(EDITOR);
        final ContentTemplate own = template("acme-own-" + UUID.randomUUID(), FakeData.ACME_ORG_ID);
        Assert.assertTrue(editor.saveTemplate(own), "an Acme-scoped template");
        Assert.assertFalse(editor.saveTemplate(template("shared-" + UUID.randomUUID(), null)), "never shared");
        Assert.assertFalse(editor.saveTemplate(template("beta-" + UUID.randomUUID(), FakeData.BETA_ORG_ID)));

        // Re-scoping is seizure: a stored shared template cannot be saved under the org's scope.
        final ContentTemplate seized = template("text-only", FakeData.ACME_ORG_ID);
        Assert.assertFalse(editor.saveTemplate(seized), "a shared template may not be pulled into the org");
        Assert.assertNull(DAO.getInstance().getTemplate("text-only", Cached.NO).orElseThrow().getOrgId());

        final List<String> visible = editor.getTemplates().stream().map(ContentTemplate::getId).toList();
        Assert.assertTrue(visible.contains(own.getId()));
        Assert.assertTrue(visible.contains("text-only"), "shared templates are visible");
        final ContentTemplate betas = template("beta-hidden-" + UUID.randomUUID(), FakeData.BETA_ORG_ID);
        Assert.assertTrue(DAO.getInstance().saveTemplate(betas, 5));
        Assert.assertFalse(editor.getTemplates().stream().anyMatch(t -> t.getId().equals(betas.getId())),
                "another tenant's templates are invisible");
        Assert.assertEquals(editor.getScopeChoices().stream().map(o -> o.getId().getValue()).toList(),
                List.of(FakeData.ACME_ORG_ID), "the Scope menu offers only the org whose site they edit");
        Assert.assertTrue(editor.deleteTemplate(own.getId()), "deletes its own (unreferenced) template");
        Assert.assertFalse(editor.deleteTemplate("text-only"), "never a shared one");
        Assert.assertFalse(editor.installStarterTemplates() > 0, "starters are site staff's");

        final TemplateCommands orgAdmin = templatesAs(ORG_ADMIN_ONLY);
        Assert.assertFalse(orgAdmin.saveTemplate(template("x-" + UUID.randomUUID(), FakeData.ACME_ORG_ID)));
    }

    /**
     * "Customize": an org editor makes a SHARED template their org's own by cloning it into the org's scope
     * -- the original is untouched, the copy is theirs to edit, revert and delete. Refused for an org admin
     * without the grant, for another tenant's org, with no org scope at all, for a template that is not
     * shared, and when the copy already exists.
     *
     * <p>The org is now an explicit SCOPE (the {@code ?orgId=} the org hub links with, or the site's own
     * org), not the request's host: the manager page itself is gated by {@code priv.checkHere}, which on a
     * shared host counts an org-scoped grant for nothing, so the host check that used to live here would
     * only have blocked site staff working from the hub. The privilege is the boundary, as it always was.
     */
    @Test
    public void anOrgEditorCustomizesASharedTemplateForTheirOrg() {
        final String sourceId = "copy-src-" + UUID.randomUUID();
        final ContentTemplate source = template(sourceId, null);
        source.setName("Copy Source");
        source.setDescription("shared source");
        Assert.assertTrue(DAO.getInstance().saveTemplate(source, 5));
        final String copyId = sourceId + "-acme";
        final TemplateCommands editor = templatesAs(EDITOR);
        try {
            Assert.assertFalse(editor.mayAuthor(source), "a shared row is not the editor's to edit");
            Assert.assertFalse(editor.mayCustomize(source, ""), "no organization scope, nothing to customize for");
            Assert.assertEquals(editor.customize(sourceId, ""), "");
            Assert.assertEquals(editor.customize(sourceId, null), "");
            Assert.assertFalse(editor.mayCustomize(source, FakeData.BETA_ORG_ID), "never another tenant's");
            Assert.assertEquals(editor.customize(sourceId, FakeData.BETA_ORG_ID), "");
            Assert.assertTrue(editor.mayCustomize(source, FakeData.ACME_ORG_ID));
            Assert.assertEquals(templatesAs(ORG_ADMIN_ONLY).customize(sourceId, FakeData.ACME_ORG_ID), "",
                    "an org admin without the grant copies nothing");
            Assert.assertFalse(templatesAs(ORG_ADMIN_ONLY).mayCustomize(source, FakeData.ACME_ORG_ID));

            Assert.assertEquals(editor.customize(sourceId, FakeData.ACME_ORG_ID), copyId);
            final ContentTemplate copy = DAO.getInstance().getTemplate(copyId, Cached.NO).orElseThrow();
            Assert.assertEquals(copy.getOrgId(), FakeData.ACME_ORG_ID, "scoped to the org");
            Assert.assertEquals(copy.getName(), "Copy Source (Acme Inc)");
            Assert.assertEquals(copy.getDescription(), source.getDescription());
            Assert.assertEquals(copy.getBody(), source.getBody());
            Assert.assertEquals(copy.getPlaceholders(), source.getPlaceholders());
            Assert.assertEquals(copy.getKind(), source.getKind());
            Assert.assertEquals(copy.getVersion(), 1, "a fresh row, not the source's history");
            Assert.assertTrue(editor.mayAuthor(copy), "the copy is the org's: editable");
            Assert.assertTrue(editor.isCustomization(copy), "...and badged as a customization");
            Assert.assertEquals(editor.siteDefault(copy).getId(), sourceId);
            Assert.assertFalse(editor.mayCustomize(copy, FakeData.ACME_ORG_ID), "an org's row is no source");
            Assert.assertNull(DAO.getInstance().getTemplate(sourceId, Cached.NO).orElseThrow().getOrgId(),
                    "the shared original is untouched");

            Assert.assertEquals(editor.customize(sourceId, FakeData.ACME_ORG_ID), "", "already copied");
            Assert.assertEquals(editor.customize(copyId, FakeData.ACME_ORG_ID), "", "an org's template is no source");
            Assert.assertEquals(editor.customize("no-such-template-" + UUID.randomUUID(),
                    FakeData.ACME_ORG_ID), "");
            Assert.assertEquals(editor.customize(" ", FakeData.ACME_ORG_ID), "");

            // A site admin may customize for an org too (seeding an org's copy on their behalf).
            final TemplateCommands admin = new TemplateCommands();
            admin.setCallerSource(TestCallers::siteAdmin);
            Assert.assertTrue(admin.mayCustomize(source, FakeData.BETA_ORG_ID));
            Assert.assertTrue(admin.mayAuthor(source), "...while keeping full editing");
            Assert.assertTrue(editor.deleteTemplate(copyId), "the copy is the org's to delete");
        } finally {
            DAO.getInstance().deleteTemplate(copyId);
            DAO.getInstance().deleteTemplate(sourceId);
        }
    }

    /**
     * MAIL templates are customized like any other kind now (they were refused while mail resolution was by
     * fixed id): the copy is what the org's own mail resolves to, and its NAME carries no "({org})" suffix
     * because a MAIL template's name IS the subject line -- the suffix would ship in every subject.
     */
    @Test
    public void anEmailTemplateIsCustomizedWithNoOrgSuffixInItsSubject() {
        final TemplateCommands editor = templatesAs(EDITOR);
        final String copyId = StarterTemplates.ORG_INVITE_ID + "-acme";
        final ContentTemplate shared =
                DAO.getInstance().getTemplate(StarterTemplates.ORG_INVITE_ID, Cached.NO).orElseThrow();
        try {
            Assert.assertTrue(editor.mayCustomize(shared, FakeData.ACME_ORG_ID), "email templates copy too");
            Assert.assertEquals(editor.customize(StarterTemplates.ORG_INVITE_ID, FakeData.ACME_ORG_ID), copyId);
            final ContentTemplate copy = DAO.getInstance().getTemplate(copyId, Cached.NO).orElseThrow();
            Assert.assertEquals(copy.getName(), shared.getName(),
                    "a MAIL template's name is its subject line: no organization suffix");
            Assert.assertEquals(copy.getKind(), org.paulsens.trip.model.TemplateKind.MAIL);
            Assert.assertEquals(copy.getOrgId(), FakeData.ACME_ORG_ID);
        } finally {
            DAO.getInstance().deleteTemplate(copyId);
        }
    }

    private static ContentTemplate template(final String id, final String orgId) {
        final ContentTemplate template = new ContentTemplate(id, 0, id, null, "<p>{{msg}}</p>",
                List.of(new Placeholder("msg", Placeholder.Type.TEXT, "Msg", null, true)), LocalDateTime.now(),
                "test");
        template.setOrgId(orgId);
        return template;
    }

    @Test
    public void anOrgScopedMediaAdminManagesOnlyThatOrgsItemsAndUploadsOnlyOnItsSite() throws Exception {
        final MediaCommands editor = mediaAs(EDITOR);
        final MediaItem siteLevel = DAO.getInstance().getMedia("fake-doc-1", Cached.NO).orElseThrow();
        final MediaItem acmes = DAO.getInstance().getMedia("fake-acme-doc", Cached.NO).orElseThrow();
        // The site half of the write rule first: off Acme's host, not even Acme's own rows are writable.
        Assert.assertFalse(editor.mayManage(acmes), "shared host: nothing, Acme's rows included");
        Assert.assertNull(editor.getManageable("fake-acme-doc"));
        Assert.assertFalse(onSite(BETA_SITE, () -> editor.mayManage(acmes)), "another tenant's host: nothing");
        onSite(ACME_SITE, () -> {
            Assert.assertTrue(editor.mayManage(acmes));
            Assert.assertFalse(editor.mayManage(siteLevel), "site-level items are the site's");
            Assert.assertFalse(editor.mayManage(acmes.withOrg(FakeData.BETA_ORG_ID)), "never another tenant's");
            Assert.assertNotNull(editor.getManageable("fake-acme-doc"));
            Assert.assertNull(editor.getManageable("fake-doc-1"), "a foreign row reads as absent");
            Assert.assertFalse(editor.setHidden("fake-doc-1", true, "x"), "writes re-check ownership");
            Assert.assertFalse(editor.update("fake-doc-1", null, "Renamed", null, "home-docs", null, false, "x"));
            Assert.assertFalse(editor.assignToSlot("fake-doc-1", "home-docs", "x"));
            Assert.assertFalse(editor.delete("fake-doc-1", "x"));
            Assert.assertFalse(mediaAs(ORG_ADMIN_ONLY).mayManage(acmes), "an org admin without the grant: nothing");
            return null;
        });

        // A trip's manager moderates the trip's chat album whoever owns the rows.
        final String tripId = UUID.randomUUID().toString();
        final MediaItem chat = new MediaItem("chat-" + tripId, "chat/" + tripId + "/p.jpg", "p", null,
                "image/jpeg", 1L, "tripChat-" + tripId, 0, LocalDateTime.now(), "x", null, null,
                FakeData.BETA_ORG_ID);
        Assert.assertFalse(editor.mayManage(chat));
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.TRIP_MGR, "mgr", tripId, List.of(EDITOR)));
        Assert.assertTrue(mediaAs(EDITOR).mayManage(chat), "trip managers moderate their own album");

        Assert.assertFalse(editor.mayUploadHere(), "not on the shared site");
        Assert.assertTrue(onSite(ACME_SITE, editor::mayUploadHere), "on the org's own site");
        Assert.assertFalse(onSite(BETA_SITE, editor::mayUploadHere), "never on another tenant's");
        Assert.assertTrue(TestCallers.mediaAsSiteAdmin().mayManage(siteLevel));
        Assert.assertTrue(TestCallers.mediaAsSiteAdmin().mayUploadHere());
    }

    @Test
    public void checkHereAndTheHubFollowTheSameRule() throws Exception {
        final PrivilegeCommands here = new PrivilegeCommands();
        here.setCallerSource(() -> TestCallers.person(EDITOR));
        Assert.assertFalse(here.checkHere(PrivilegeCommands.CONTENT_ADMIN, EDITOR),
                "shared site: an org grant counts for nothing");
        Assert.assertTrue(onSite(ACME_SITE, () -> here.checkHere(PrivilegeCommands.CONTENT_ADMIN, EDITOR)));
        Assert.assertFalse(onSite(BETA_SITE, () -> here.checkHere(PrivilegeCommands.CONTENT_ADMIN, EDITOR)));
        Assert.assertFalse(here.checkHere(null, EDITOR));
        final PrivilegeCommands asAdmin = new PrivilegeCommands();
        asAdmin.setCallerSource(TestCallers::siteAdmin);
        Assert.assertTrue(asAdmin.checkHere("anything", TestCallers.SITE_ADMIN_ID), "a site admin holds everything");
        Assert.assertFalse(asAdmin.checkHere("anything", EDITOR), "...for themselves only");

        // Caller.hasHere is the same rule from the caller's side.
        final Caller editor = TestCallers.person(EDITOR);
        Assert.assertFalse(editor.hasHere(PrivilegeCommands.MEDIA_ADMIN));
        Assert.assertTrue(onSite(ACME_SITE,
                () -> TestCallers.person(EDITOR).hasHere(PrivilegeCommands.MEDIA_ADMIN)));

        // The org dashboard opens for an editor too: it is where the org's templates are managed, and the
        // site-wide Templates entry is global-only, so this is their only route to their own work. The hub
        // itself grants nothing -- every card on it is gated separately.
        final OrgCommands orgs = new OrgCommands(() -> TestCallers.person(EDITOR));
        Assert.assertTrue(orgs.canViewOrgHub(FakeData.ACME_ORG_ID));
        Assert.assertFalse(orgs.canManageOrg(FakeData.ACME_ORG_ID), "reaching the hub is not managing the org");
        Assert.assertFalse(orgs.canViewOrgPeople(FakeData.ACME_ORG_ID), "and opens no card they lack");
        Assert.assertTrue(PrivilegeCommands.ORG_HUB_BASES.contains(PrivilegeCommands.CONTENT_ADMIN));
        Assert.assertTrue(PrivilegeCommands.ORG_SCOPED_BASES.contains(PrivilegeCommands.CONTENT_ADMIN));
        Assert.assertTrue(PrivilegeCommands.ORG_SCOPED_BASES.contains(PrivilegeCommands.MEDIA_ADMIN));
        Assert.assertTrue(PrivilegeCommands.GLOBAL_BASES.contains(PrivilegeCommands.CONTENT_ADMIN), "still global too");
        Assert.assertTrue(Caller.bound().personId() == null, "off a request, nobody");

        // The audit trail follows the same shape: global rows read every trail, an org-scoped row reads
        // that organization's, and the grant opens the dashboard (its Audit Trail card is the route).
        Assert.assertTrue(PrivilegeCommands.ORG_SCOPED_BASES.contains(PrivilegeCommands.AUDIT_ADMIN));
        Assert.assertTrue(PrivilegeCommands.ORG_HUB_BASES.contains(PrivilegeCommands.AUDIT_ADMIN));
        Assert.assertTrue(PrivilegeCommands.GLOBAL_BASES.contains(PrivilegeCommands.AUDIT_ADMIN));

        // The dashboard's Media card: the org's own media editor and site admins, not its admin as such.
        Assert.assertTrue(orgs.canViewOrgMedia(FakeData.ACME_ORG_ID));
        Assert.assertFalse(orgs.canViewOrgMedia(FakeData.BETA_ORG_ID), "another tenant's library");
        Assert.assertFalse(orgs.canViewOrgMedia(null));
        Assert.assertTrue(new OrgCommands(TestCallers::siteAdmin).canViewOrgMedia(FakeData.BETA_ORG_ID));
    }
}
