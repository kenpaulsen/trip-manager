package org.paulsens.trip.action;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The mail-address slot machinery behind the Settings page's Email addresses section: sentinel
 * resolution (org/site/custom, org falling back to the Site email), the "Name &lt;addr&gt;" parsing, and
 * the edit-state/apply round trip with its validation. Domains come through the test seam -- the real
 * source is SES.
 */
public class MailAddressCommandsTest {

    private static final List<String> DOMAINS = List.of("visitqueenofpeace.com", "example.org");

    private MailAddressCommands commands() {
        return new MailAddressCommands(() -> DOMAINS);
    }

    // ------------------------------------------------------------------ resolution

    @Test
    public void sentinelsResolveAgainstTheSiteAndOrgAddresses() throws IOException {
        final MailAddressCommands mailAddr = commands();
        final String siteFrom = KnownSettings.SITE_MAIL_EMAIL.getDefaultValue();
        final String siteBare = MailAddressCommands.addressOf(siteFrom);

        // From: 'site' (and a stray 'org') mean the full Site email; a literal passes through.
        assertEquals(mailAddr.from(KnownSettings.REG_MAIL_FROM), siteFrom, "reg from defaults to site");
        assertEquals(mailAddr.from(KnownSettings.CHAT_MAIL_FROM),
                KnownSettings.CHAT_MAIL_FROM.getDefaultValue(), "chat from stays its literal default");

        // Recipients: org resolves the trip's owning org, falling back to the bare site address.
        // (reg.notify.email defaults to 'facilitators'; with none assigned it falls back to org.)
        final Trip orgTrip = tripInOrgWithEmail("office@mailorg.example.com");
        assertEquals(mailAddr.recipient(KnownSettings.REG_NOTIFY_EMAIL, orgTrip),
                "office@mailorg.example.com", "no facilitators: the org contact email wins");
        assertEquals(mailAddr.replyTo(KnownSettings.REG_MAIL_REPLY_TO, orgTrip),
                "office@mailorg.example.com");
        assertEquals(mailAddr.recipient(KnownSettings.REG_NOTIFY_EMAIL, tripInOrgWithEmail(null)),
                siteBare, "org without a contact email: the site address");
        assertEquals(mailAddr.recipient(KnownSettings.REG_NOTIFY_EMAIL, savedTrip()), siteBare,
                "org-less trip: the site address");
        assertEquals(mailAddr.recipient(KnownSettings.REG_NOTIFY_EMAIL, null), siteBare,
                "no trip at all never throws");
        assertEquals(mailAddr.recipient(KnownSettings.TX_NOTIFY_EMAIL, null),
                KnownSettings.TX_NOTIFY_EMAIL.getDefaultValue(), "a literal default passes through");
        assertEquals(mailAddr.recipient(KnownSettings.SUPPORT_MAIL_REPLY_TO, null), siteBare,
                "site mode resolves bare for recipients");

        // The EL entry points resolve by key; an unknown key is a hard error, not a silent null.
        assertEquals(mailAddr.fromFor("reg.mail.from"), siteFrom);
        assertEquals(mailAddr.recipientFor("reg.notify.email", orgTrip), "office@mailorg.example.com");
        assertEquals(mailAddr.replyToFor("reg.mail.replyTo", null), siteBare);
        try {
            mailAddr.fromFor("no.such.setting");
            assertTrue(false, "an unknown key must throw");
        } catch (final IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no.such.setting"));
        }
        assertNull(mailAddr.orgContactEmail(null));
    }

    @Test
    public void facilitatorsResolveToTheirEmailsWithTheOrgFallbackChain() throws IOException {
        final MailAddressCommands mailAddr = commands();
        final String siteBare = MailAddressCommands.addressOf(
                KnownSettings.SITE_MAIL_EMAIL.getDefaultValue());

        // Two mailable facilitators: both addresses, comma-joined for the send's recipient split.
        final Trip staffed = tripInOrgWithEmail("office@mailorg.example.com");
        final Person leaderA = savedPerson("leader.a@example.com");
        final Person leaderB = savedPerson("leader.b@example.com");
        staffed.addFacilitatorId(leaderA.getId());
        staffed.addFacilitatorId(leaderB.getId());
        assertTrue(DAO.getInstance().saveTrip(staffed));
        assertEquals(mailAddr.recipient(KnownSettings.REG_NOTIFY_EMAIL, staffed),
                "leader.a@example.com, leader.b@example.com",
                "the notice goes to the same contacts the registrant is shown");

        // A facilitator with no usable address is skipped, not sent to.
        final Person noEmail = savedPerson(null);
        staffed.addFacilitatorId(noEmail.getId());
        assertTrue(DAO.getInstance().saveTrip(staffed));
        assertEquals(mailAddr.recipient(KnownSettings.REG_NOTIFY_EMAIL, staffed),
                "leader.a@example.com, leader.b@example.com");

        // No mailable facilitator at all: the org contact email, then the site address.
        final Trip unmailable = tripInOrgWithEmail("office2@mailorg.example.com");
        unmailable.addFacilitatorId(savedPerson(null).getId());
        assertTrue(DAO.getInstance().saveTrip(unmailable));
        assertEquals(mailAddr.recipient(KnownSettings.REG_NOTIFY_EMAIL, unmailable),
                "office2@mailorg.example.com");
        final Trip bare = savedTrip();
        bare.addFacilitatorId(savedPerson(null).getId());
        assertTrue(DAO.getInstance().saveTrip(bare));
        assertEquals(mailAddr.recipient(KnownSettings.REG_NOTIFY_EMAIL, bare), siteBare);
        assertEquals(mailAddr.recipient(KnownSettings.REG_NOTIFY_EMAIL, null), siteBare,
                "no trip at all never throws");
        assertNull(mailAddr.facilitatorEmails(null));
        assertNull(mailAddr.facilitatorEmails(savedTrip()));
    }

    @Test
    public void addressParsingHandlesBothShapes() {
        assertEquals(MailAddressCommands.addressOf("Visit Queen of Peace <no-reply@vqp.com>"),
                "no-reply@vqp.com");
        assertEquals(MailAddressCommands.addressOf("bare@vqp.com"), "bare@vqp.com");
        assertEquals(MailAddressCommands.addressOf(null), "");
        assertEquals(MailAddressCommands.displayNameOf("Visit Queen of Peace <no-reply@vqp.com>"),
                "Visit Queen of Peace");
        assertEquals(MailAddressCommands.displayNameOf("bare@vqp.com"), "");
        assertEquals(MailAddressCommands.displayNameOf(null), "");
        assertEquals(MailAddressCommands.localOf("Name <no-reply@vqp.com>"), "no-reply");
        assertEquals(MailAddressCommands.domainOf("Name <no-reply@VQP.com>"), "vqp.com");
        assertEquals(MailAddressCommands.domainOf("nodomain"), "");
    }

    // ------------------------------------------------------------------ the Settings page model

    @Test
    public void editStateSeedsModesAndCustomStartingPoints() {
        final Map<String, String> edit = commands().editState();
        final String siteDefault = KnownSettings.SITE_MAIL_EMAIL.getDefaultValue();
        assertEquals(edit.get("site.name"), MailAddressCommands.displayNameOf(siteDefault));
        assertEquals(edit.get("site.local"), MailAddressCommands.localOf(siteDefault));
        assertEquals(edit.get("site.domain"), MailAddressCommands.domainOf(siteDefault));
        // Unset slots read as mode "" (default), with the house starting point pre-seeded for Custom.
        assertEquals(edit.get("reg.mail.from.mode"), "");
        assertEquals(edit.get("reg.mail.from.local"), "no-reply");
        assertEquals(edit.get("reg.notify.email.mode"), "");
        assertEquals(edit.get("reg.notify.email.addr"), "");
        assertNotNull(edit.get("chat.mail.from.name"));
    }

    @Test
    public void applyEditsComposesValidatesAndCollapsesDefaults() {
        final MailAddressCommands mailAddr = commands();
        final Map<String, String> edit = mailAddr.editState();
        final Map<String, String> vals = new HashMap<>();

        // A default site widget collapses to "" (unset); explicit modes and customs store their values.
        edit.put("reg.notify.email.mode", "custom");
        edit.put("reg.notify.email.addr", "office@partner-org.example");
        edit.put("reg.mail.replyTo.mode", "site");
        edit.put("tx.notify.email.mode", "org");
        edit.put("chat.mail.replyTo.mode", "facilitators");
        edit.put("login.mail.from.mode", "custom");
        edit.put("login.mail.from.name", "Queen of Peace Login");
        edit.put("login.mail.from.local", "login");
        edit.put("login.mail.from.domain", "example.org");
        assertTrue(mailAddr.applyEdits(edit, vals));
        assertEquals(vals.get("site.mail.email"), "", "unchanged site email collapses to unset");
        assertEquals(vals.get("reg.notify.email"), "office@partner-org.example");
        assertEquals(vals.get("reg.mail.replyTo"), "site");
        assertEquals(vals.get("tx.notify.email"), "org");
        assertEquals(vals.get("chat.mail.replyTo"), "facilitators");
        assertEquals(vals.get("login.mail.from"), "Queen of Peace Login <login@example.org>");
        assertEquals(vals.get("account.notify.email"), "", "untouched slots stay unset");

        // Picking a slot's own default mode collapses to unset, like every other default-equal value.
        final Map<String, String> facDefault = mailAddr.editState();
        facDefault.put("reg.notify.email.mode", "facilitators");
        final Map<String, String> facVals = new HashMap<>();
        assertTrue(mailAddr.applyEdits(facDefault, facVals));
        assertEquals(facVals.get("reg.notify.email"), "");

        // Composing a custom equal to the default also collapses to unset.
        final Map<String, String> same = mailAddr.editState();
        same.put("tx.notify.email.mode", "custom");
        same.put("tx.notify.email.addr", KnownSettings.TX_NOTIFY_EMAIL.getDefaultValue());
        final Map<String, String> vals2 = new HashMap<>();
        assertTrue(mailAddr.applyEdits(same, vals2));
        assertEquals(vals2.get("tx.notify.email"), "");

        assertTrue(mailAddr.applyEdits(null, vals2), "null edit is a no-op, not a failure");
    }

    @Test
    public void applyEditsRefusesBadInputWithoutTouchingVals() {
        final MailAddressCommands mailAddr = commands();
        final Map<String, String> vals = new HashMap<>();

        // A malformed custom recipient.
        final Map<String, String> badAddr = mailAddr.editState();
        badAddr.put("reg.notify.email.mode", "custom");
        badAddr.put("reg.notify.email.addr", "not-an-address");
        assertFalse(mailAddr.applyEdits(badAddr, vals));
        assertTrue(vals.isEmpty(), "a refused save must not half-apply");

        // A From on a domain SES has not verified.
        final Map<String, String> badDomain = mailAddr.editState();
        badDomain.put("reg.mail.from.mode", "custom");
        badDomain.put("reg.mail.from.local", "no-reply");
        badDomain.put("reg.mail.from.domain", "unverified.example");
        assertFalse(mailAddr.applyEdits(badDomain, vals));

        // A From with a broken local part, and one with markup in the display name.
        final Map<String, String> badLocal = mailAddr.editState();
        badLocal.put("reg.mail.from.mode", "custom");
        badLocal.put("reg.mail.from.local", "no reply!");
        badLocal.put("reg.mail.from.domain", "example.org");
        assertFalse(mailAddr.applyEdits(badLocal, vals));
        final Map<String, String> badName = mailAddr.editState();
        badName.put("reg.mail.from.mode", "custom");
        badName.put("reg.mail.from.name", "Evil <script>");
        badName.put("reg.mail.from.local", "no-reply");
        badName.put("reg.mail.from.domain", "example.org");
        assertFalse(mailAddr.applyEdits(badName, vals));

        // Org mode on a slot with no org context (account creation), and an empty domain list.
        final Map<String, String> badOrg = mailAddr.editState();
        badOrg.put("account.notify.email.mode", "org");
        assertFalse(mailAddr.applyEdits(badOrg, vals));
        final Map<String, String> badFac = mailAddr.editState();
        badFac.put("account.notify.email.mode", "facilitators");
        assertFalse(mailAddr.applyEdits(badFac, vals), "facilitators need a trip in hand too");
        final MailAddressCommands noDomains = new MailAddressCommands(List::of);
        assertFalse(noDomains.applyEdits(noDomains.editState(), vals),
                "the site From cannot compose against an empty verified-domain list");
        assertTrue(vals.isEmpty());
    }

    @Test
    public void sendingDomainsSurviveALookupFailure() {
        final MailAddressCommands broken = new MailAddressCommands(MailAddressCommandsTest::sesIsDown);
        assertEquals(broken.getSendingDomains(), List.of(), "a lookup failure answers empty, not a throw");
        assertEquals(commands().getSendingDomains(), DOMAINS);
        assertEquals(commands().getSlots().size(), 14, "every address slot is registered");
    }

    @Test
    public void modeOptionsOfferOrgOnlyWhereItExists() {
        final MailAddressCommands mailAddr = commands();
        final List<Map<String, String>> withOrg = mailAddr.modeOptions(slot(mailAddr, "tx.notify.email"));
        assertEquals(withOrg.size(), 5, "default, facilitators, org, site, custom");
        assertTrue(withOrg.stream().anyMatch(option -> "org".equals(option.get("value"))));
        assertTrue(withOrg.stream().anyMatch(option -> "facilitators".equals(option.get("value"))));
        final List<Map<String, String>> noOrg = mailAddr.modeOptions(slot(mailAddr, "account.notify.email"));
        assertEquals(noOrg.size(), 3, "no org context at account creation, so no org option");
        assertFalse(noOrg.stream().anyMatch(option -> "org".equals(option.get("value"))));
        assertFalse(noOrg.stream().anyMatch(option -> "facilitators".equals(option.get("value"))));
        // The Default item humanizes sentinels and passes literals through, so unset is never a mystery.
        assertTrue(mailAddr.modeOptions(slot(mailAddr, "reg.notify.email")).get(0).get("label")
                .contains("Trip facilitators"));
        assertTrue(mailAddr.modeOptions(slot(mailAddr, "reg.mail.replyTo")).get(0).get("label")
                .contains("Organization email"));
        assertTrue(mailAddr.modeOptions(slot(mailAddr, "reg.mail.from")).get(0).get("label")
                .contains("Site email"));
        assertTrue(noOrg.get(0).get("label").contains("registration-notifications@"));
        // The config-handed constructor (the senders' path) resolves identically.
        assertEquals(new MailAddressCommands(new ConfigCommands()).siteBare(),
                MailAddressCommands.addressOf(KnownSettings.SITE_MAIL_EMAIL.getDefaultValue()));
    }

    private static MailAddressCommands.Slot slot(final MailAddressCommands mailAddr, final String key) {
        return mailAddr.getSlots().stream()
                .filter(candidate -> candidate.getKey().equals(key))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ fixtures

    private Trip tripInOrgWithEmail(final String contactEmail) throws IOException {
        final OrgCommands orgs = new OrgCommands(() -> new Caller(Person.Id.from("admin-" + unique()),
                true, new AuditActor("admin@test", "admin"), new PrivilegeCommands()));
        final Organization org = orgs.createOrganization("Mail Org " + unique(), null, contactEmail);
        assertNotNull(org);
        final Trip trip = savedTrip();
        trip.setOrgId(org.getId().getValue());
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private Trip savedTrip() throws IOException {
        final Trip trip = Trip.builder().title("Addr Trip " + unique()).build();
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    /** A saved person; {@code email} may be null (a person-modeled staff entry with no login). */
    private Person savedPerson(final String email) throws IOException {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email(email)
                .build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    // ------------------------------------------------------------------ the shared From composer

    @Test
    public void composeAddressBuildsOnlyDeliverableFromAddresses() {
        final MailAddressCommands mailAddr = commands();
        assertEquals(mailAddr.composeAddress("Queen of Peace", "no-reply", "visitqueenofpeace.com",
                DOMAINS, "The From address"), "Queen of Peace <no-reply@visitqueenofpeace.com>");
        assertEquals(mailAddr.composeAddress(" ", " info ", " EXAMPLE.ORG ", DOMAINS, "x"),
                "info@example.org", "trimmed, and the domain lower-cased to match the dropdown");

        assertNull(mailAddr.composeAddress("Q", "no reply", "example.org", DOMAINS, "x"),
                "a space is not a mailbox");
        assertNull(mailAddr.composeAddress("Q", "", "example.org", DOMAINS, "x"));
        assertNull(mailAddr.composeAddress("Q", "info", "elsewhere.com", DOMAINS, "x"),
                "an unverified domain is the whole point of the dropdown");
        assertNull(mailAddr.composeAddress("Q", "info", "", DOMAINS, "x"));
        assertNull(mailAddr.composeAddress("a <b>", "info", "example.org", DOMAINS, "x"),
                "angle brackets in the display name would forge a second address");
        assertNull(mailAddr.composeAddress("Q", "info", "example.org", List.of(), "x"),
                "no domains at all is a refusal, not a free pass");
        assertNull(mailAddr.composeAddress("Q", "info", "example.org", null, "x"));
    }

    @Test
    public void isSendableAnswersWhetherSesCouldSendAsAnAddress() {
        final MailAddressCommands mailAddr = commands();
        assertTrue(mailAddr.isSendable("info@example.org", DOMAINS));
        assertTrue(mailAddr.isSendable("Name <info@example.org>", DOMAINS));
        assertFalse(mailAddr.isSendable("info@gmail.com", DOMAINS), "the parish-gmail case");
        assertFalse(mailAddr.isSendable("not-an-address", DOMAINS));
        assertFalse(mailAddr.isSendable(null, DOMAINS));
        assertFalse(mailAddr.isSendable("info@example.org", List.of()));
        assertFalse(mailAddr.isSendable("info@example.org", null));
    }

    @Test
    public void composerSeedsNeverPreselectADomainTheDropdownLacks() {
        final MailAddressCommands mailAddr = commands();
        assertEquals(mailAddr.composerName("Queen of Peace <a@b.com>"), "Queen of Peace");
        assertEquals(mailAddr.composerLocal("Queen of Peace <hello@b.com>"), "hello");
        assertEquals(mailAddr.composerLocal(""), "no-reply", "the house-style starting point");

        assertEquals(mailAddr.composerDomain("a@example.org", "visitqueenofpeace.com", DOMAINS),
                "example.org", "the address's own domain wins while it is still allowed");
        assertEquals(mailAddr.composerDomain("a@gone.com", "EXAMPLE.ORG", DOMAINS), "example.org",
                "else the org's preferred domain");
        assertEquals(mailAddr.composerDomain("a@gone.com", "also-gone.com", DOMAINS),
                DOMAINS.get(0), "else the first the dropdown offers, never a phantom selection");
        assertEquals(mailAddr.composerDomain("a@example.org", null, List.of()), "");
        assertEquals(mailAddr.composerDomain(null, null, null), "");
    }

    private static String unique() {
        return RandomData.genAlpha(10);
    }

    private static List<String> sesIsDown() {
        throw new IllegalStateException("SES down");
    }
}
