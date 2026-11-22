package org.paulsens.trip.action;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.ReminderDefaults;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.content.StarterTranslations;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.TemplateKind;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Language versions of an EMAIL template ({@code docs/content-templates.md}, "Language versions of an email"): a
 * version is its own MAIL row under the template's id plus {@code @} and a language tag, resolved for the
 * recipient's language by {@link TemplateCommands#resolveForOrg(String, String, Locale)}, managed on the Templates
 * page under the template's own scope and privileges. Beta Corp's seeded site offers English, Spanish and
 * Croatian; the shared site and every other organization offer English alone.
 */
public class MailTemplateVersionsTest {

    private static final String ACME = FakeData.ACME_ORG_ID;
    private static final String BETA = FakeData.BETA_ORG_ID;
    private static final Locale ES = Language.Spanish.getLocale();
    private static final Locale HR = Language.Croatian.getLocale();
    /** A content editor for Beta Corp's site only. */
    private static final Person.Id BETA_EDITOR = Person.Id.from("mtv-beta-editor");

    private final TemplateCommands templates = as(TestCallers.siteAdmin());

    @BeforeClass
    public void seed() {
        DAO.getInstance();
        FakeData.addFakeData();
        final PrivilegeCommands priv = new PrivilegeCommands();
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.CONTENT_ADMIN, "beta content", BETA,
                List.of(BETA_EDITOR)));
    }

    private static TemplateCommands as(final Caller caller) {
        final TemplateCommands commands = new TemplateCommands();
        commands.setCallerSource(() -> caller);
        return commands;
    }

    private static TemplateCommands betaEditor() {
        return as(TestCallers.person(BETA_EDITOR));
    }

    /** A MAIL row saved straight through the DAO, in {@code orgId}'s scope (null: shared). */
    private static void mail(final String id, final String subject, final String orgId) {
        final ContentTemplate template = new ContentTemplate(id, 0, subject, "fixture", "<p>" + subject + "</p>",
                List.of(), null, null, TemplateKind.MAIL, null, null, null);
        template.setOrgId(orgId);
        Assert.assertTrue(DAO.getInstance().saveTemplate(template, 5), id);
    }

    private static void delete(final String... ids) {
        for (final String id : ids) {
            DAO.getInstance().deleteTemplate(id);
        }
    }

    private String subject(final String id, final String orgId, final Locale locale) {
        final ContentTemplate resolved = templates.resolveForOrg(id, orgId, locale);
        return resolved == null ? null : resolved.getName();
    }

    private static String uniqueId() {
        return "mtv-" + UUID.randomUUID();
    }

    @Test
    public void theLadderIsOrgLanguageThenOrgThenSharedLanguageThenShared() {
        final String id = uniqueId();
        final String sharedEs = id + "@es";
        final String betaCopy = id + "-beta";
        final String betaEs = betaCopy + "@es";
        try {
            mail(id, "Shared", null);
            Assert.assertEquals(subject(id, BETA, ES), "Shared", "nothing in Spanish yet: the shared row");

            mail(sharedEs, "Compartida", null);
            Assert.assertEquals(subject(id, BETA, ES), "Compartida", "shared + language");
            Assert.assertEquals(subject(id, null, ES), "Compartida", "no organization: still the reader's language");
            Assert.assertEquals(subject(id, BETA, Locale.forLanguageTag("es-MX")), "Compartida",
                    "the locale's LANGUAGE counts, not its region");
            Assert.assertEquals(subject(id, BETA, HR), "Shared", "no Croatian version: the shared row");
            Assert.assertEquals(subject(id, BETA, Locale.ENGLISH), "Shared", "English is the row itself");
            Assert.assertEquals(subject(id, BETA, null), "Shared");
            Assert.assertEquals(subject(id, BETA, Locale.FRENCH), "Shared", "a language this build does not speak");
            Assert.assertEquals(subject(id, BETA, Locale.forLanguageTag("xx")), "Shared", "an unknown tag");

            mail(betaCopy, "Beta", BETA);
            Assert.assertEquals(subject(id, BETA, ES), "Beta",
                    "the organization's own wording outranks the site's translation of words it replaced");
            Assert.assertEquals(subject(id, ACME, ES), "Compartida", "another organization still reads the shared");

            mail(betaEs, "Beta es", BETA);
            Assert.assertEquals(subject(id, BETA, ES), "Beta es", "org + language leads");
            Assert.assertEquals(subject(id, BETA, HR), "Beta", "the org's copy for a language it has no version in");
            Assert.assertEquals(subject(id, BETA, Locale.ENGLISH), "Beta");
            Assert.assertEquals(templates.resolveForOrg(id, BETA).getName(), "Beta",
                    "the two-argument form is English, unchanged");
            Assert.assertNull(templates.resolveForOrg(null, BETA, ES));
            Assert.assertNull(templates.resolveForOrg(" ", BETA, ES));
            Assert.assertNull(templates.resolveForOrg("no-such-" + id, BETA, ES));
        } finally {
            delete(betaEs, betaCopy, sharedEs, id);
        }
    }

    /** Every level checks its OWNER: no tenant gets another's version, and an org-owned "shared" one goes to nobody. */
    @Test
    public void anotherOrganizationsVersionIsNeverServed() {
        final String id = uniqueId();
        final String betaShaped = id + "-beta@es";
        final String sharedShaped = id + "@es";
        try {
            mail(id, "Shared", null);
            mail(betaShaped, "Acme impostor", ACME);
            Assert.assertEquals(subject(id, BETA, ES), "Shared", "Beta's version id owned by Acme is not Beta's");
            mail(sharedShaped, "Acme claims the shared Spanish", ACME);
            Assert.assertEquals(subject(id, BETA, ES), "Shared", "a 'shared' version an org owns is nobody's");
            Assert.assertEquals(subject(id, ACME, ES), "Shared", "...not even that org's");
            Assert.assertEquals(subject(id, null, ES), "Shared");
        } finally {
            delete(sharedShaped, betaShaped, id);
        }
    }

    /** The sender hands SES the version in the language it was asked for, and the English row otherwise. */
    @Test
    public void theSenderSendsTheVersionInTheRecipientsLanguage() {
        final MailCommands mail = Mockito.spy(new MailCommands());
        Mockito.doReturn(null).when(mail).send(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        final Map<String, Object> values = Map.of("orgName", "Beta Corp", "siteName", "Beta Corp",
                "siteHost", "beta.localhost", "createAccountUrl", "https://beta.localhost/login.jsf");

        Assert.assertTrue(mail.sendManagedTemplateForOrg(StarterTemplates.ORG_INVITE_ID, BETA, ES, values,
                "ana@example.org", "from@example.org", null, null, AuditActor.system()));
        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.eq("ana@example.org"),
                ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("Beta Corp te invita a beta.localhost"),
                ArgumentMatchers.contains("Inicia sesión o crea tu cuenta"), ArgumentMatchers.any());

        Mockito.clearInvocations(mail);
        Assert.assertTrue(mail.sendManagedTemplateForOrg(StarterTemplates.ORG_INVITE_ID, BETA, null, values,
                "bob@example.org", "from@example.org", null, null, AuditActor.system()));
        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.eq("bob@example.org"),
                ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("Beta Corp invites you to beta.localhost"), ArgumentMatchers.any(),
                ArgumentMatchers.any());
        Assert.assertEquals(mail.renderManagedTemplateForOrg(StarterTemplates.PAYMENT_FAILED_ID, BETA, HR,
                Map.of("tripTitle", "Roma")).subject(), "Uplata nije izvršena - Roma");
    }

    // ================================================================ the manager page

    @Test
    public void onlyTheLanguagesTheSiteOffersBesidesEnglishAreOffered() {
        Assert.assertEquals(templates.versionLanguages(BETA), List.of(Language.Spanish, Language.Croatian));
        Assert.assertEquals(templates.versionLanguages(ACME), List.of(), "an English-only site sees nothing new");
        Assert.assertEquals(templates.versionLanguages(""), List.of(), "the shared site offers English alone");
        Assert.assertEquals(templates.versionLanguages("no-such-org"), List.of());

        final ContentTemplate invite = templates.getTemplate(StarterTemplates.ORG_INVITE_ID);
        Assert.assertTrue(templates.versionsOf(invite, ACME).isEmpty());
        Assert.assertTrue(templates.versionsOf(templates.getTemplate(StarterTemplates.TEXT_ONLY_ID), BETA).isEmpty(),
                "page content is translated by page versions, not here");
        Assert.assertTrue(templates.versionsOf(null, BETA).isEmpty());
        Assert.assertTrue(templates.versionsOf(templates.getTemplate(StarterTemplates.ORG_INVITE_ID + "@es"), BETA)
                .isEmpty(), "a version has no versions of its own");

        final List<TemplateCommands.LanguageVersion> versions = templates.versionsOf(invite, BETA);
        Assert.assertEquals(versions.stream().map(TemplateCommands.LanguageVersion::getTag).toList(),
                List.of("es", "hr"));
        Assert.assertEquals(versions.get(0).getLanguageName(), "Español");
        Assert.assertEquals(versions.get(0).getId(), StarterTemplates.ORG_INVITE_ID + "@es");
        Assert.assertTrue(versions.get(0).isWritten(), "the shipped starters' versions are installed");
        Assert.assertTrue(versions.get(0).isEditable(), "...and site staff edit them");
        Assert.assertFalse(versions.get(0).isAddable());

        final List<TemplateCommands.LanguageVersion> asBetaEditor = betaEditor().versionsOf(invite, BETA);
        Assert.assertTrue(asBetaEditor.get(0).isWritten());
        Assert.assertFalse(asBetaEditor.get(0).isEditable(), "the shared version is as read-only as its template");
        Assert.assertFalse(asBetaEditor.get(0).isAddable());
    }

    /** The version rows never list as templates of their own: they sit under their template. */
    @Test
    public void versionsAreNotRowsOfTheirOwn() {
        final List<String> ids = templates.getTemplatesFor("MAIL", "").stream().map(ContentTemplate::getId).toList();
        Assert.assertTrue(ids.contains(StarterTemplates.ORG_INVITE_ID));
        Assert.assertFalse(ids.stream().anyMatch(id -> id.contains("@")), ids.toString());
        Assert.assertFalse(templates.getTemplates("MAIL").stream().anyMatch(t -> t.getId().contains("@")));
    }

    @Test
    public void addingAVersionCopiesTheTemplateIntoItsScope() {
        final String id = uniqueId();
        final String betaCopy = id + "-beta";
        try {
            mail(id, "Shared", null);
            Assert.assertEquals(templates.customize(id, BETA), betaCopy);
            final TemplateCommands editor = betaEditor();
            final ContentTemplate own = DAO.getInstance().getTemplate(betaCopy, Cached.NO).orElseThrow();
            Assert.assertTrue(editor.versionsOf(own, BETA).get(0).isAddable(), "Beta's own row: Beta may add");

            Assert.assertEquals(editor.addVersion(betaCopy, "es", BETA), betaCopy + "@es");
            final ContentTemplate version = DAO.getInstance().getTemplate(betaCopy + "@es", Cached.NO).orElseThrow();
            Assert.assertEquals(version.getOrgId(), BETA, "in the template's own scope");
            Assert.assertEquals(version.getName(), "Shared", "starting as a copy of the template");
            Assert.assertEquals(version.getKind(), TemplateKind.MAIL);
            Assert.assertEquals(subject(id, BETA, ES), "Shared");
            final TemplateCommands.LanguageVersion written = editor.versionsOf(own, BETA).get(0);
            Assert.assertTrue(written.isWritten() && written.isEditable() && !written.isAddable());

            Assert.assertEquals(editor.addVersion(betaCopy, "es", BETA), "", "already written");
            Assert.assertEquals(editor.addVersion(betaCopy, "fr", BETA), "", "a language this build does not speak");
            Assert.assertEquals(editor.addVersion(betaCopy, "es", ACME), "", "a language Acme's site does not offer");
            Assert.assertEquals(editor.addVersion(id, "hr", BETA), "", "the shared row is not Beta's to translate");
            Assert.assertEquals(editor.addVersion(StarterTemplates.TEXT_ONLY_ID, "hr", BETA), "", "not an email");
            Assert.assertEquals(editor.addVersion(betaCopy + "@es", "hr", BETA), "", "not a version of a version");
            Assert.assertEquals(editor.addVersion("no-such-" + id, "hr", BETA), "");
            Assert.assertEquals(editor.addVersion(null, "hr", BETA), "");

            Assert.assertTrue(editor.deleteTemplate(betaCopy + "@es"), "Beta deletes its own version");
            Assert.assertTrue(DAO.getInstance().getTemplate(betaCopy + "@es", Cached.NO).isEmpty());
        } finally {
            delete(betaCopy + "@es", betaCopy, id);
        }
    }

    /**
     * A version keeps its template's scope: an organization's editor may not plant its wording under a SHARED
     * template's version id (every organization's Spanish readers would get it), nor move a version elsewhere.
     */
    @Test
    public void aVersionKeepsItsTemplatesScope() {
        final String id = uniqueId();
        try {
            mail(id, "Shared", null);
            final ContentTemplate planted = new ContentTemplate(id + "@es", 0, "Beta's", "x", "<p>x</p>",
                    List.of(), null, null, TemplateKind.MAIL, null, null, null);
            planted.setOrgId(BETA);
            Assert.assertFalse(betaEditor().saveTemplate(planted));
            Assert.assertFalse(templates.saveTemplate(planted), "not even site staff: the scope is the template's");
            Assert.assertTrue(DAO.getInstance().getTemplate(id + "@es", Cached.NO).isEmpty());
            planted.setOrgId(null);
            Assert.assertTrue(templates.saveTemplate(planted), "in the template's scope it saves");
        } finally {
            delete(id + "@es", id);
        }
    }

    /**
     * Customize takes the shared versions along (otherwise the org's English copy would outrank the site's
     * Spanish and switch Spanish readers to English), and Revert takes the org's versions away again.
     */
    @Test
    public void customizeCopiesTheVersionsAndRevertDropsThem() {
        final String id = uniqueId();
        final String betaCopy = id + "-beta";
        try {
            mail(id, "Shared", null);
            mail(id + "@es", "Compartida", null);
            Assert.assertEquals(templates.customize(id, BETA), betaCopy);
            final ContentTemplate copied = DAO.getInstance().getTemplate(betaCopy + "@es", Cached.NO).orElseThrow();
            Assert.assertEquals(copied.getOrgId(), BETA);
            Assert.assertEquals(subject(id, BETA, ES), "Compartida", "Spanish readers keep reading Spanish");
            Assert.assertTrue(DAO.getInstance().getTemplate(betaCopy + "@hr", Cached.NO).isEmpty(),
                    "nothing to copy in Croatian");

            Assert.assertTrue(templates.revertToSiteDefault(betaCopy, BETA));
            Assert.assertTrue(DAO.getInstance().getTemplate(betaCopy + "@es", Cached.NO).isEmpty());
            Assert.assertTrue(DAO.getInstance().getTemplate(id + "@es", Cached.NO).isPresent(), "the shared one stays");
        } finally {
            delete(betaCopy + "@es", betaCopy, id + "@es", id);
        }
    }

    // ================================================================ the shipped versions

    /** Every shipped version keeps its English starter's tokens exactly, says it is a draft, and is installed. */
    @Test
    public void theShippedVersionsKeepTheirStartersTokens() {
        Assert.assertEquals(StarterTranslations.VERSIONS.size(), 22, "eleven emails, in Spanish and Croatian");
        final Map<String, ContentTemplate> starters = StarterTemplates.all().stream()
                .collect(java.util.stream.Collectors.toMap(ContentTemplate::getId, t -> t));
        for (final StarterTranslations.Version version : StarterTranslations.VERSIONS) {
            final ContentTemplate english = starters.get(version.baseId());
            Assert.assertEquals(tokens(version.subject() + version.body()),
                    tokens(english.getName() + english.getBody()), version.id());
            final ContentTemplate installed = templates.getTemplate(version.id());
            Assert.assertEquals(installed.getKind(), TemplateKind.MAIL, version.id());
            Assert.assertTrue(installed.getDescription().startsWith("DRAFT"), version.id());
            Assert.assertFalse((version.subject() + version.body()).contains("—"), "no em dash: " + version.id());
        }
        Assert.assertNull(StarterTranslations.of(StarterTemplates.ORG_INVITE_ID, Language.English));
        Assert.assertNull(StarterTranslations.of(StarterTemplates.TEXT_ONLY_ID, Language.Spanish));
    }

    /** A Spanish trip's suggested reminders start from the Spanish text; the built-in fallback is Spanish too. */
    @Test
    public void aTripsSuggestedRemindersStartInTheTripsLanguage() {
        final ReminderDefaults.Text spanish =
                ReminderCommands.startingText(ReminderDefaults.WAIVER_ID, BETA, Language.Spanish);
        Assert.assertEquals(spanish.subject(), "Firma tus documentos de {{tripTitle}}");
        Assert.assertEquals(ReminderCommands.startingText(ReminderDefaults.WAIVER_ID, BETA, null).subject(),
                "Please sign your documents for {{tripTitle}}");
        Assert.assertEquals(ReminderDefaults.text(ReminderDefaults.COUNTDOWN_ID, Language.Croatian).body(),
                StarterTranslations.of(ReminderDefaults.COUNTDOWN_ID, Language.Croatian).body());
        Assert.assertEquals(ReminderDefaults.text(ReminderDefaults.COUNTDOWN_ID, null),
                ReminderDefaults.text(ReminderDefaults.COUNTDOWN_ID));
        Assert.assertNull(ReminderDefaults.text("no-such-id", Language.Spanish));
    }

    private static List<String> tokens(final String text) {
        return java.util.regex.Pattern.compile("\\{\\{[^}]+}}").matcher(text).results()
                .map(java.util.regex.MatchResult::group).sorted().distinct().toList();
    }
}
