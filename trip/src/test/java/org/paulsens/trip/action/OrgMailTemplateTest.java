package org.paulsens.trip.action;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.TemplateKind;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Per-organization EMAIL copy: an organization customizes a shared MAIL template and its own mail is what
 * goes out, on every path that sends on the org's behalf.
 *
 * <p>The resolution order is one method -- {@link TemplateCommands#resolveForOrg} -- so the Templates page
 * and every sender agree by construction: the org's own copy when it has one, else the shared row, and a
 * row under the copy's id that belongs to somebody ELSE is never served.
 */
public class OrgMailTemplateTest {

    private static final String ACME = FakeData.ACME_ORG_ID;
    private static final String BETA = FakeData.BETA_ORG_ID;
    /** CFPW is deliberately slug-less (shared-tier, as in production): its copies key on the org id. */
    private static final String CFPW = FakeData.CFPW_ORG_ID;

    private final TemplateCommands templates = siteStaff();

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        FakeData.addFakeData();
    }

    private static TemplateCommands siteStaff() {
        final TemplateCommands commands = new TemplateCommands();
        commands.setCallerSource(TestCallers::siteAdmin);
        return commands;
    }

    /** A shared MAIL template with a unique id, saved straight through the DAO. */
    private static ContentTemplate sharedMail(final String id, final String subject, final String body) {
        final ContentTemplate template = new ContentTemplate(id, 0, subject, "fixture", body,
                List.of(), null, null, TemplateKind.MAIL, null, null, null);
        Assert.assertTrue(DAO.getInstance().saveTemplate(template, 5));
        return template;
    }

    private static String uniqueId() {
        return "mailtpl-" + UUID.randomUUID();
    }

    @Test
    public void theOrgsOwnCopyWinsAndEverythingElseFallsBackToTheShared() {
        final String id = uniqueId();
        sharedMail(id, "Shared subject", "<p>shared</p>");
        final String acmeCopyId = id + "-acme";
        try {
            Assert.assertEquals(templates.resolveForOrg(id, null).getName(), "Shared subject",
                    "no organization: the shared row (background work with nothing to take an org from)");
            Assert.assertEquals(templates.resolveForOrg(id, "").getName(), "Shared subject");
            Assert.assertEquals(templates.resolveForOrg(id, ACME).getName(), "Shared subject",
                    "an org that has not customized it gets the shared row too");

            Assert.assertEquals(templates.customize(id, ACME), acmeCopyId);
            final ContentTemplate copy = DAO.getInstance().getTemplate(acmeCopyId, Cached.NO).orElseThrow();
            copy.setName("Acme subject");
            copy.setBody("<p>acme</p>");
            Assert.assertTrue(templates.saveTemplate(copy));

            Assert.assertEquals(templates.resolveForOrg(id, ACME).getName(), "Acme subject",
                    "the org's own copy wins");
            Assert.assertEquals(templates.resolveForOrg(id, ACME).getBody(), "<p>acme</p>");
            Assert.assertEquals(templates.resolveForOrg(id, BETA).getName(), "Shared subject",
                    "another tenant is never served Acme's copy");
            Assert.assertEquals(templates.resolveForOrg(id, null).getName(), "Shared subject",
                    "and neither is the org-less path");
        } finally {
            DAO.getInstance().deleteTemplate(acmeCopyId);
            DAO.getInstance().deleteTemplate(id);
        }
    }

    /**
     * A row sitting under another organization's copy id is ignored -- resolution checks the OWNER, not
     * just the name. Without that check, saving a template named {@code x-beta} into Acme's scope would
     * hand Beta's registrants Acme's wording.
     */
    @Test
    public void aCopyOwnedByAnotherOrganizationIsNotServed() {
        final String id = uniqueId();
        sharedMail(id, "Shared subject", "<p>shared</p>");
        final String betaShapedId = id + "-beta";
        final ContentTemplate impostor = new ContentTemplate(betaShapedId, 0, "Impostor", "x",
                "<p>impostor</p>", List.of(), null, null, TemplateKind.MAIL, null, null, null);
        impostor.setOrgId(ACME);
        try {
            Assert.assertTrue(DAO.getInstance().saveTemplate(impostor, 5));
            Assert.assertEquals(templates.resolveForOrg(id, BETA).getName(), "Shared subject",
                    "Beta's id shape owned by Acme is nobody's customization");
            Assert.assertEquals(templates.resolveForOrg(id, ACME).getName(), "Shared subject",
                    "...and it is not Acme's either: Acme's copy id ends in -acme");
        } finally {
            DAO.getInstance().deleteTemplate(betaShapedId);
            DAO.getInstance().deleteTemplate(id);
        }
    }

    /** An unknown id resolves to nothing at all (senders treat that as "do not send"), and so does a blank. */
    @Test
    public void anUnknownOrBlankIdResolvesToNothing() {
        Assert.assertNull(templates.resolveForOrg(null, ACME));
        Assert.assertNull(templates.resolveForOrg("  ", ACME));
        Assert.assertNull(templates.resolveForOrg("no-such-template-" + UUID.randomUUID(), ACME));
        Assert.assertNull(templates.resolveForOrg("no-such-template-" + UUID.randomUUID(), null));
        Assert.assertEquals(templates.resolveForOrg(StarterTemplates.ORG_INVITE_ID, "no-such-org").getId(),
                StarterTemplates.ORG_INVITE_ID, "an unreadable organization still gets the shared row");
    }

    /** An org with no subdomain still customizes: the copy keys on its id where a slug would go. */
    @Test
    public void aSlugLessOrganizationCustomizesToo() {
        final String id = uniqueId();
        sharedMail(id, "Shared subject", "<p>shared</p>");
        final String copyId = id + "-" + CFPW;
        try {
            Assert.assertEquals(templates.customize(id, CFPW), copyId,
                    "a shared-tier org has no slug; the copy keys on the organization id");
            final ContentTemplate copy = DAO.getInstance().getTemplate(copyId, Cached.NO).orElseThrow();
            copy.setName("CFPW subject");
            Assert.assertTrue(templates.saveTemplate(copy));
            Assert.assertEquals(templates.resolveForOrg(id, CFPW).getName(), "CFPW subject");
            Assert.assertTrue(templates.isCustomization(copy));
            Assert.assertEquals(templates.siteDefaultOf(copyId).getId(), id);
        } finally {
            DAO.getInstance().deleteTemplate(copyId);
            DAO.getInstance().deleteTemplate(id);
        }
    }

    /**
     * The customized subject and body are what the SENDER hands to SES -- the whole point of the feature.
     * A spy on {@code MailCommands} captures the send below the managed-template layer (local mode makes
     * the real {@code send} a logged no-op, so what it was ASKED to send is the only observable).
     */
    @Test
    public void theCustomizedCopyIsWhatGetsSent() {
        final String id = uniqueId();
        sharedMail(id, "Shared subject", "<p>shared</p>");
        final String acmeCopyId = id + "-acme";
        try {
            Assert.assertEquals(templates.customize(id, ACME), acmeCopyId);
            final ContentTemplate copy = DAO.getInstance().getTemplate(acmeCopyId, Cached.NO).orElseThrow();
            copy.setName("Acme says {{who}}");
            copy.setBody("<p>Acme body for {{who}}</p>");
            Assert.assertTrue(templates.saveTemplate(copy));

            final MailCommands mail = Mockito.spy(new MailCommands());
            Mockito.doReturn(null).when(mail).send(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any());

            Assert.assertTrue(mail.sendManagedTemplateForOrg(id, ACME, Map.of("who", "Ann"),
                    "ann@example.org", "from@example.org", null, null, AuditActor.system()));
            Mockito.verify(mail).send(ArgumentMatchers.eq("from@example.org"),
                    ArgumentMatchers.eq("ann@example.org"), ArgumentMatchers.isNull(),
                    ArgumentMatchers.isNull(), ArgumentMatchers.eq("Acme says Ann"),
                    ArgumentMatchers.eq("<p>Acme body for Ann</p>"), ArgumentMatchers.any());

            Mockito.clearInvocations(mail);
            Assert.assertTrue(mail.sendManagedTemplateForOrg(id, BETA, Map.of("who", "Bob"),
                    "bob@example.org", "from@example.org", null, null, AuditActor.system()));
            Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq("Shared subject"),
                    ArgumentMatchers.eq("<p>shared</p>"), ArgumentMatchers.any());

            // The org-less forms still resolve the shared row, which is what the support notice wants.
            Assert.assertEquals(mail.renderManagedTemplate(id, Map.of()).subject(), "Shared subject");
            Assert.assertEquals(mail.renderManagedSubjectForOrg(id, ACME, Map.of("who", "X")),
                    "Acme says X");
            Assert.assertEquals(mail.renderManagedBodyForOrg(id, ACME, Map.of("who", "X")),
                    "<p>Acme body for X</p>");
            Assert.assertEquals(mail.renderManagedSubjectForOrg("no-such-" + id, ACME, Map.of()), "",
                    "a missing template previews empty rather than erroring");
            Assert.assertEquals(mail.renderManagedBodyForOrg("no-such-" + id, ACME, Map.of()), "");
            Assert.assertFalse(mail.sendManagedTemplateForOrg("no-such-" + id, ACME, Map.of(),
                    "x@example.org", "f@example.org", null, null, AuditActor.system()));
            Assert.assertFalse(mail.sendManagedTemplateForOrg(id, ACME, Map.of(), " ",
                    "f@example.org", null, null, AuditActor.system()), "no recipient, no send");
        } finally {
            DAO.getInstance().deleteTemplate(acmeCopyId);
            DAO.getInstance().deleteTemplate(id);
        }
    }

    /** A send that throws below the template layer is contained: the flow that wanted the mail carries on. */
    @Test
    public void aFailingSendIsContained() {
        final MailCommands mail = Mockito.spy(new MailCommands());
        Mockito.doThrow(new IllegalStateException("boom")).when(mail).send(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
        Assert.assertFalse(mail.sendManagedTemplateForOrg(StarterTemplates.ORG_INVITE_ID, ACME, Map.of(),
                "x@example.org", "f@example.org", null, null, AuditActor.system()));
    }

    /**
     * One row per use case in an organization's scope: the customized row REPLACES the shared one rather
     * than sitting beside it, and the site-wide page keeps showing only the shared rows.
     */
    @Test
    public void theManagerShowsOneRowPerUseCase() {
        final String id = uniqueId();
        sharedMail(id, "Shared subject", "<p>shared</p>");
        final String acmeCopyId = id + "-acme";
        try {
            Assert.assertTrue(idsIn(templates.getTemplatesFor("MAIL", ACME)).contains(id),
                    "uncustomized: the shared row is the org's row");
            Assert.assertEquals(templates.customize(id, ACME), acmeCopyId);

            final List<String> acmeMail = idsIn(templates.getTemplatesFor("MAIL", ACME));
            Assert.assertTrue(acmeMail.contains(acmeCopyId), "customized: the org's own row");
            Assert.assertFalse(acmeMail.contains(id), "...and the shared original is NOT listed beside it");

            final List<String> siteMail = idsIn(templates.getTemplatesFor("MAIL", ""));
            Assert.assertTrue(siteMail.contains(id), "the site-wide page shows the site default");
            Assert.assertFalse(siteMail.contains(acmeCopyId), "...and no organization's copy");
            Assert.assertFalse(idsIn(templates.getTemplatesFor(null, null)).contains(acmeCopyId),
                    "a null scope is the site-wide page too");

            // The kind filter still narrows in both scopes; a bogus kind fails open to everything.
            Assert.assertFalse(idsIn(templates.getTemplatesFor("STANDARD", ACME)).contains(acmeCopyId));
            Assert.assertTrue(idsIn(templates.getTemplatesFor("STANDARD", ACME))
                    .contains(StarterTemplates.TEXT_ONLY_ID));
            Assert.assertTrue(idsIn(templates.getTemplatesFor("bogus", ACME)).contains(acmeCopyId));
            Assert.assertTrue(idsIn(templates.getTemplatesFor("MAIL", "no-such-org")).contains(id),
                    "an unreadable scope degrades to the shared list rather than emptying the page");
        } finally {
            DAO.getInstance().deleteTemplate(acmeCopyId);
            DAO.getInstance().deleteTemplate(id);
        }
    }

    /** An org template that customizes nothing (one the org authored itself) is still its own row. */
    @Test
    public void anOrgsOwnTemplateIsListedEvenThoughItCustomizesNothing() {
        final String id = "acme-original-" + UUID.randomUUID();
        final ContentTemplate own = new ContentTemplate(id, 0, "Acme Original", "x", "<p>x</p>",
                List.of(), null, null, TemplateKind.MAIL, null, null, null);
        own.setOrgId(ACME);
        try {
            Assert.assertTrue(DAO.getInstance().saveTemplate(own, 5));
            final ContentTemplate stored = DAO.getInstance().getTemplate(id, Cached.NO).orElseThrow();
            Assert.assertTrue(idsIn(templates.getTemplatesFor("MAIL", ACME)).contains(id));
            Assert.assertFalse(templates.isCustomization(stored), "it customizes no site default");
            Assert.assertNull(templates.siteDefault(stored));
            Assert.assertFalse(templates.revertToSiteDefault(id, ACME),
                    "reverting would leave the use case with nothing: refused");
            Assert.assertTrue(DAO.getInstance().getTemplate(id, Cached.NO).isPresent(), "...and kept");
        } finally {
            DAO.getInstance().deleteTemplate(id);
        }
    }

    /** Revert drops the org's copy and nothing else, so the use case falls back to the site default. */
    @Test
    public void revertRemovesOnlyTheOrgsOwnCopy() {
        final String id = uniqueId();
        sharedMail(id, "Shared subject", "<p>shared</p>");
        final String acmeCopyId = id + "-acme";
        try {
            Assert.assertEquals(templates.customize(id, ACME), acmeCopyId);
            Assert.assertFalse(templates.revertToSiteDefault(acmeCopyId, BETA),
                    "another tenant cannot revert Acme's copy");
            Assert.assertFalse(templates.revertToSiteDefault(acmeCopyId, ""), "nor a scopeless page");
            Assert.assertFalse(templates.revertToSiteDefault(id, ACME),
                    "the shared row is not an org's copy to drop");
            Assert.assertFalse(templates.revertToSiteDefault(null, ACME));
            Assert.assertFalse(templates.revertToSiteDefault("no-such-" + id, ACME));

            Assert.assertTrue(templates.revertToSiteDefault(acmeCopyId, ACME));
            Assert.assertTrue(DAO.getInstance().getTemplate(acmeCopyId, Cached.NO).isEmpty());
            Assert.assertEquals(templates.resolveForOrg(id, ACME).getName(), "Shared subject",
                    "the site default is in use again");
            Assert.assertTrue(DAO.getInstance().getTemplate(id, Cached.NO).isPresent(),
                    "the shared row is untouched");
        } finally {
            DAO.getInstance().deleteTemplate(acmeCopyId);
            DAO.getInstance().deleteTemplate(id);
        }
    }

    /** The page's scope resolution: the explicit ?orgId= first, then the site's org, then nothing. */
    @Test
    public void theScopeIsTheParameterThenTheSiteThenNothing() throws Exception {
        Assert.assertEquals(templates.orgScope(ACME), ACME, "the parameter wins");
        Assert.assertEquals(templates.orgScope(""), "", "off an org site, the site-wide page");
        Assert.assertEquals(templates.orgScope(null), "");
        Assert.assertEquals(ScopedValue.where(org.paulsens.trip.audit.RequestContext.SCOPE,
                org.paulsens.trip.audit.RequestContext.of(null, null,
                        org.paulsens.trip.site.SiteContext.org(
                                org.paulsens.trip.model.Organization.Id.from(ACME), "acme", "acme.localhost")))
                .call(() -> templates.orgScope(null)), ACME, "an org's own site scopes itself");

        Assert.assertEquals(templates.orgScopeName(ACME), "Acme Inc");
        Assert.assertEquals(templates.orgScopeName(""), "");
        Assert.assertEquals(templates.orgScopeName(null), "");
        Assert.assertEquals(templates.orgScopeName("no-such-org"), "");
        Assert.assertNull(templates.siteDefaultOf(null));
        Assert.assertNull(templates.siteDefaultOf(" "));
        Assert.assertNull(templates.siteDefaultOf(StarterTemplates.TEXT_ONLY_ID), "a shared row has none");
    }

    private static List<String> idsIn(final List<ContentTemplate> rows) {
        return rows.stream().map(ContentTemplate::getId).toList();
    }
}
