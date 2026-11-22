package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.media.UploadRateLimiter;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Mail the code writes itself is in its READER's language ({@code docs/i18n.md}): the sign-in code in the
 * language of the page it was asked for on; the office's notices (a new registration, a deleted account, a
 * content report) in the office's own saved language, else English. Each English mail is held to exactly what
 * the code wrote before its words moved to the {@code mail} bundle; the Spanish halves use Beta Corp's seeded
 * site, the one that offers Spanish.
 */
public class CodeMailLanguageTest {

    private static final Locale ES = Language.Spanish.getLocale();

    @BeforeClass
    public void seed() {
        // The DAO first: it is what seeds the fixtures the first time, and addFakeData reads them.
        DAO.getInstance();
        FakeData.addFakeData();
    }

    // ------------------------------------------------------------------ the sign-in code

    @Test
    public void theSignInCodeIsByteForByteWhatItWasInEnglish() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        new LoginCodeCommands(new InMemoryCacheClient(), new ConfigCommands(), mail).requestCode("user2");
        final String[] sent = sentBy(mail);
        final long minutes = new ConfigCommands().getInt(KnownSettings.LOGIN_CODE_TTL_SECONDS, 60, 1800) / 60;
        Assert.assertEquals(sent[0], "Your sign-in code");
        Assert.assertEquals(sent[1], "<p>Your sign-in code is:</p>"
                + "<p style=\"font-size:2em;font-weight:bold;letter-spacing:0.2em;\">"
                + LoginCodeCommands.LOCAL_MODE_CODE + "</p>"
                + "<p>It works for the next " + minutes + " minutes, once.</p>"
                + "<p>If you didn't request this, you can ignore this email; nothing has changed on your "
                + "account.</p>");
    }

    /** The person asking is on the page, so the page's language is the mail's. */
    @Test
    public void theSignInCodeIsInThePagesLanguage() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        final LoginCodeCommands commands = new LoginCodeCommands(new InMemoryCacheClient(), new ConfigCommands(),
                mail);
        ScopedValue.where(RequestContext.SCOPE, new RequestContext(AuditActor.system(), null, null, ES))
                .run(() -> commands.requestCode("user2"));
        final String[] sent = sentBy(mail);
        Assert.assertEquals(sent[0], "Tu código de inicio de sesión");
        Assert.assertTrue(sent[1].startsWith("<p>Tu código de inicio de sesión es:</p>"), sent[1]);
        Assert.assertTrue(sent[1].contains("una sola vez"), sent[1]);
    }

    // ------------------------------------------------------------------ the office's registration notice

    @Test
    public void aRegistrationNoticeIsInTheOfficesLanguage() throws IOException {
        final Person owner = saved("Owner", null);
        final Trip trip = betaTrip(Language.Spanish);

        // A shared mailbox no account holds reads English, whatever the trip's language.
        final String[] english = registrationNotice(owner, trip, "office-" + System.nanoTime() + "@example.org");
        Assert.assertEquals(english[0], "New Registration - Roma 2027");
        Assert.assertEquals(english[1], "New registration for 'Roma 2027':\n - "
                + AuditCommands.describe(owner) + "\n");

        final Person office = saved("Office", "es");
        final String[] spanish = registrationNotice(owner, trip, office.getEmail());
        Assert.assertEquals(spanish[0], "Nueva inscripción - Roma 2027");
        Assert.assertTrue(spanish[1].startsWith("Nueva inscripción en 'Roma 2027':\n"), spanish[1]);
    }

    private static String[] registrationNotice(final Person owner, final Trip trip, final String office) {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        final MailAddressCommands addresses = Mockito.mock(MailAddressCommands.class);
        Mockito.when(addresses.fromFor("reg.notify.from")).thenReturn("notify@example.org");
        Mockito.when(addresses.recipientFor("reg.notify.email", trip)).thenReturn(office);
        new RegistrationCommands(() -> TestCallers.person(owner.getId()), TripCommands::new, AuditCommands::new,
                () -> mail, () -> addresses).sendRegistrationMail(trip, List.of(owner));
        final ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).send(ArgumentMatchers.eq("notify@example.org"), ArgumentMatchers.eq(office),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), subject.capture(), body.capture(),
                ArgumentMatchers.any());
        return new String[] {subject.getValue(), body.getValue()};
    }

    // ------------------------------------------------------------------ the account-deletion notice

    @Test
    public void theDeletionNoticeIsByteForByteWhatItWasInEnglish() {
        final AccountDeletionCommands.Notice notice = notice();
        final String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US));
        Assert.assertEquals(AccountDeletionCommands.noticeSubject(notice.person(), Locale.ENGLISH),
                "Account deleted: Ann O'Hara (ann@example.org)");
        final String table = "<table cellpadding=\"4\" border=\"1\" cellspacing=\"0\">";
        // The one shared escaper (MailTemplates.html) writes an apostrophe as &#39;: the same text in every reader.
        Assert.assertEquals(AccountDeletionCommands.noticeBody(notice, Locale.ENGLISH),
                "<p><b>Ann O&#39;Hara</b> (ann@example.org, id ann-1) deleted their account on " + today
                        + " from the UniteTrip app. This notice covers their history with <b>Rome &amp; Co</b>.</p>"
                        + "<p>Deleted with them, as family members they managed with no login of their own: "
                        + "Kid O&#39;Hara.</p>"
                        + "<h3>Trips</h3>" + table + "<tr><th align=\"left\">Trip</th><th>Dates</th>"
                        + "<th>Registration</th><th>When</th></tr><tr><td>Assisi</td><td>2027-03-05 – 2027-03-07"
                        + "</td><td>Confirmed</td><td><b>Upcoming / in progress</b></td></tr>"
                        + "<tr><td>Fatima</td><td> – </td><td>On the trip</td><td>Past</td></tr></table>"
                        + "<h3>Transactions</h3>" + table + "<tr><th>Date</th><th>Type</th>"
                        + "<th align=\"left\">Description</th><th align=\"right\">Amount</th></tr>"
                        + "<tr><td>2026-10-01</td><td>Payment</td><td>Deposit – by check</td>"
                        + "<td align=\"right\">$50.00</td></tr></table>"
                        + "<p style=\"font-size:1.1em\"><b>AMOUNT OWED TO TRAVELER: $50.00</b></p>"
                        + "<p>Their profile is now anonymized (\"Deleted Account\"); the transaction rows above stay "
                        + "in the system under the same id for the organization's accounting. Their registrations, "
                        + "login, messages, photos and comments are gone and cannot be restored.</p>");
        final AccountDeletionCommands.Notice none = new AccountDeletionCommands.Notice(notice.person(), List.of(),
                null, List.of(), new ArrayList<>());
        Assert.assertTrue(AccountDeletionCommands.noticeBody(none, Locale.ENGLISH).contains(
                "<h3>Trips</h3><p>None.</p><h3>Transactions</h3><p>None.</p>"
                        + "<p style=\"font-size:1.1em\"><b>Balance: settled</b></p>"));
    }

    @Test
    public void theDeletionNoticeReadsInTheOfficesLanguage() {
        final AccountDeletionCommands.Notice notice = notice();
        Assert.assertEquals(AccountDeletionCommands.noticeSubject(notice.person(), ES),
                "Cuenta eliminada: Ann O'Hara (ann@example.org)");
        final String body = AccountDeletionCommands.noticeBody(notice, ES);
        for (final String expected : List.of("eliminó su cuenta el", "<h3>Viajes</h3>", "<th>Fechas</th>",
                "<td>Confirmada</td>", "<b>Próximo / en curso</b>", "<td>En el viaje</td>",
                "<h3>Transacciones</h3>", "IMPORTE QUE SE DEBE AL VIAJERO: $50.00", "(\"Cuenta eliminada\")")) {
            Assert.assertTrue(body.contains(expected), "missing '" + expected + "' in " + body);
        }
        Assert.assertFalse(body.contains("Trips"), body);
    }

    private static AccountDeletionCommands.Notice notice() {
        final Person ann = Person.builder().id(Person.Id.from("ann-1")).first("Ann").last("O'Hara")
                .email("ann@example.org").build();
        final Person kid = Person.builder().first("Kid").last("O'Hara").build();
        final Organization org = new Organization();
        org.setName("Rome & Co");
        final List<AccountDeletionCommands.TripLine> trips = List.of(
                new AccountDeletionCommands.TripLine("t1", "Assisi", null, LocalDateTime.of(2027, 3, 5, 8, 0),
                        LocalDateTime.of(2027, 3, 7, 8, 0), "Confirmed", false),
                new AccountDeletionCommands.TripLine("t2", "Fatima", null, null, null, "On the trip", true));
        final Transaction tx = new Transaction(ann.getId(), null, Transaction.Type.Tx);
        tx.setAmount(50f);
        tx.setTxDate(LocalDateTime.of(2026, 10, 1, 9, 0));
        tx.setCategory("Deposit");
        tx.setNote("by check");
        return new AccountDeletionCommands.Notice(ann, List.of(kid), org, trips, new ArrayList<>(List.of(tx)));
    }

    // ------------------------------------------------------------------ the content report

    @Test
    public void aContentReportIsInTheRecipientsLanguage() throws IOException {
        final Trip trip = betaTrip(Language.English);
        trip.setTitle("Reportable");
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        final Person reporter = saved("Reporter", null);
        final Person office = saved("Moderator", "es");
        final MailCommands mail = Mockito.mock(MailCommands.class);
        final MailAddressCommands addresses = Mockito.mock(MailAddressCommands.class);
        Mockito.when(addresses.recipient(ArgumentMatchers.eq(KnownSettings.MODERATION_REPORT_EMAIL),
                ArgumentMatchers.any())).thenReturn(office.getEmail());
        Mockito.when(addresses.from(KnownSettings.MODERATION_REPORT_FROM)).thenReturn("reports@example.org");
        final ModerationCommands moderation = new ModerationCommands(new ConfigCommands(), () -> mail,
                () -> addresses, () -> Mockito.mock(ChatCommands.class), () -> Mockito.mock(PhotoChatCommands.class),
                new UploadRateLimiter(5, 3600));

        final ModerationCommands.ReportOutcome outcome = moderation.report(new ModerationCommands.ReportTarget(
                ModerationCommands.TargetKind.PHOTO, trip.getId(), null, "chat/" + trip.getId() + "/x.jpg"), " ",
                TestCallers.person(reporter.getId()));

        Assert.assertTrue(outcome.ok(), outcome.message());
        final String[] sent = sentBy(mail);
        Assert.assertEquals(sent[0], "Reporte de contenido: Reportable");
        for (final String expected : List.of("Alguien reportó contenido", "<th align=\"left\" valign=\"top\">Viaje",
                "<td>Una foto</td>", "<td>(sin motivo)</td>", "Abre la página del chat")) {
            Assert.assertTrue(sent[1].contains(expected), "missing '" + expected + "' in " + sent[1]);
        }
    }

    // ------------------------------------------------------------------ a page's office notice

    @Test
    public void aPagesOfficeNoticeIsInTheOfficesLanguage() throws IOException {
        final TextCommands text = new TextCommands();
        final Trip trip = betaTrip(Language.English);
        Assert.assertEquals(text.forStaff("nobody-" + System.nanoTime() + "@example.org", trip,
                "mail.account.createdInApp", "Ann Lee", "ann@example.org"),
                "Ann Lee (ann@example.org) created a new account in the UniteTrip app.");
        final Person office = saved("Office", "es");
        Assert.assertEquals(text.forStaff(office.getEmail(), trip, "mail.account.createdSubject"),
                "Nueva cuenta creada");
        Assert.assertEquals(text.forStaff(office.getEmail(), null, "mail.account.createdSubject"),
                "New Account Created", "the shared site offers English only, so its office reads English");
    }

    // ------------------------------------------------------------------ fixtures

    private static String[] sentBy(final MailCommands mail) {
        final ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), subject.capture(), body.capture(), ArgumentMatchers.any());
        return new String[] {subject.getValue(), body.getValue()};
    }

    private static Person saved(final String first, final String language) throws IOException {
        final Person person = Person.builder().first(first).last("Mailer")
                .email(first.toLowerCase(Locale.ROOT) + "-" + System.nanoTime() + "@example.org").build();
        person.setLanguage(language);
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    /** An unsaved trip of Beta Corp's, the seeded site that offers en, es and hr. */
    private static Trip betaTrip(final Language language) {
        final Trip trip = Trip.builder().id("cm-" + System.nanoTime()).title("Roma 2027").language(language)
                .people(new ArrayList<>()).build();
        trip.setOrgId(FakeData.BETA_ORG_ID);
        return trip;
    }
}
