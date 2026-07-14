package org.paulsens.trip.scripts;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AdmissionOption;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for the {@link RegisterPerson} migration CLI, run against the in-memory fake
 * persistence used in tests (DAO.getInstance() without a JSF context).
 */
public class RegisterPersonTest {

    private final DAO dao = DAO.getInstance();

    // ===== Argument parsing ===========================================================

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void parse_requiresEmail() {
        RegisterPerson.CliArgs.parse(new String[] {"--admission", "full"});
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void parse_requiresAdmission() {
        RegisterPerson.CliArgs.parse(new String[] {"--email", "a@b.com"});
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void parse_rejectsUnknownArgument() {
        RegisterPerson.CliArgs.parse(new String[] {"--email", "a@b.com", "--admission", "full", "--bogus", "x"});
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void parse_rejectsBadOptFormat() {
        RegisterPerson.CliArgs.parse(new String[] {"--email", "a@b.com", "--admission", "full", "--opt", "noEquals"});
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void parse_rejectsBadDateTime() {
        RegisterPerson.CliArgs.parse(
                new String[] {"--email", "a@b.com", "--admission", "full", "--created", "3/10/2026 13:07"});
    }

    @Test
    public void parse_acceptsFullArgumentSet() {
        final RegisterPerson.CliArgs cli = RegisterPerson.CliArgs.parse(new String[] {
                "--email", "NSBonsby@AOL.com", "--trip", "t1", "--first", "Nanette", "--last", "Bonsby",
                "--sex", "female", "--birthdate", "1971-04-02", "--cell", "301-366-7301",
                "--admission", "full-rosen", "--discount-code", "speaker",
                "--created", "2026-03-10T13:07:00", "--opt", "1=Yes", "--opt", "2=Diocese of Venice",
                "--pay-amount", "$295.00", "--pay-date", "2026-03-10T13:07:00",
                "--pay-note", "Rosen Centre Guest Discount (Early Bird)", "--pay-id", "AV78WB5U622D4",
                "--dry-run"});
        Assert.assertEquals(cli.email, "nsbonsby@aol.com");   // lowercased like the webapp
        Assert.assertEquals(cli.sex, "Female");               // normalized to enum casing
        Assert.assertEquals(cli.payAmount, 295.00f);          // $ and commas stripped
        Assert.assertEquals(cli.questionAnswers.get("2"), "Diocese of Venice");
        Assert.assertEquals(cli.created, LocalDateTime.of(2026, 3, 10, 13, 7));
        Assert.assertTrue(cli.dryRun);
    }

    // ===== Full runs against the fake persistence =====================================

    @Test
    public void run_createsPersonCredsRegistrationTransactionAndBinding() throws Exception {
        final Trip trip = savedTrip();
        final String email = RandomData.genAlpha(8).toLowerCase() + "@example.com";
        final int code = run(
                "--email", email, "--trip", trip.getId(),
                "--first", "Nanette", "--last", "Bonsby", "--sex", "Female", "--birthdate", "1971-04-02",
                "--admission", "full-rosen", "--opt", "1=Yes", "--discount-code", "speaker",
                "--created", "2026-03-10T13:07:00",
                "--pay-amount", "295.00", "--pay-date", "2026-03-10T13:07:00", "--pay-note", "Early Bird");
        Assert.assertEquals(code, RegisterPerson.OK);

        final Person person = dao.getPersonByEmail(email).join();
        Assert.assertNotNull(person, "person should have been created");
        Assert.assertEquals(person.getLast(), "Bonsby");

        final Registration reg = dao.getRegistration(trip.getId(), person.getId()).join().orElse(null);
        Assert.assertNotNull(reg, "registration should have been created");
        Assert.assertEquals(reg.getStatus(), Registration.Status.PENDING);
        Assert.assertEquals(reg.getCreated(), LocalDateTime.of(2026, 3, 10, 13, 7));
        Assert.assertEquals(reg.getAdmissionId(), "full-rosen");
        Assert.assertEquals(reg.getDiscountCodeId(), "speaker");
        Assert.assertEquals(reg.getOptions().get("1"), "Yes");
        Assert.assertEquals(reg.getRegistrantType(), Registration.RegistrantType.ADULT);

        final List<Transaction> txs = dao.getTransactions(person.getId()).join();
        Assert.assertEquals(txs.size(), 1);
        Assert.assertEquals(txs.get(0).getAmount(), 295.00f);
        Assert.assertEquals(txs.get(0).getTxDate(), LocalDateTime.of(2026, 3, 10, 13, 7));
        Assert.assertEquals(txs.get(0).getCategory(), "Payment");
        Assert.assertEquals(txs.get(0).getNote(), "Early Bird");

        final String bindKey = CompositeKey.builder()
                .partitionKey(person.getId().getValue()).sortKey(txs.get(0).getTxId()).build().getValue();
        final List<String> bound = dao.getBindings(bindKey, BindingType.TRANSACTION, BindingType.TRIP).join();
        Assert.assertEquals(bound, List.of(trip.getId()), "transaction should be bound to the trip");
    }

    @Test
    public void run_existingPersonIsRegisteredWithoutProfileArgs() throws Exception {
        final Trip trip = savedTrip();
        final Person person = savedPerson();
        final int code = run("--email", person.getEmail(), "--trip", trip.getId(),
                "--admission", "full", "--opt", "1=No");
        Assert.assertEquals(code, RegisterPerson.OK);
        final Registration reg = dao.getRegistration(trip.getId(), person.getId()).join().orElse(null);
        Assert.assertNotNull(reg);
        Assert.assertEquals(reg.getStatus(), Registration.Status.PENDING);
    }

    @Test
    public void run_failsWhenAlreadyRegistered() throws Exception {
        final Trip trip = savedTrip();
        final Person person = savedPerson();
        Assert.assertEquals(run("--email", person.getEmail(), "--trip", trip.getId(),
                "--admission", "full", "--opt", "1=No"), RegisterPerson.OK);
        Assert.assertEquals(run("--email", person.getEmail(), "--trip", trip.getId(),
                "--admission", "full", "--opt", "1=No"), RegisterPerson.EXIT_ALREADY_REGISTERED);
    }

    @Test
    public void run_failsWhenPersonMissingAndProfileIncomplete() throws Exception {
        final Trip trip = savedTrip();
        final int code = run("--email", RandomData.genAlpha(8) + "@example.com", "--trip", trip.getId(),
                "--admission", "full", "--opt", "1=No", "--first", "OnlyFirst");   // no --last
        Assert.assertEquals(code, RegisterPerson.EXIT_USAGE);
    }

    @Test
    public void run_createsPersonWithoutSexOrBirthdate() throws Exception {
        final Trip trip = savedTrip();
        final String email = RandomData.genAlpha(8).toLowerCase() + "@example.com";
        final int code = run("--email", email, "--trip", trip.getId(),
                "--first", "Leilani", "--last", "Rodriguez", "--admission", "full");
        Assert.assertEquals(code, RegisterPerson.OK);
        final Person person = dao.getPersonByEmail(email).join();
        Assert.assertNotNull(person);
        Assert.assertNull(person.getSex());
        Assert.assertNull(person.getBirthdate());
        final Registration reg = dao.getRegistration(trip.getId(), person.getId()).join().orElse(null);
        Assert.assertNotNull(reg);
        // Unknown age is treated as adult, matching the webapp's adult-by-default behavior
        Assert.assertEquals(reg.getRegistrantType(), Registration.RegistrantType.ADULT);
    }

    @Test
    public void run_failsOnUnknownAdmission() throws Exception {
        final Trip trip = savedTrip();
        final Person person = savedPerson();
        Assert.assertEquals(run("--email", person.getEmail(), "--trip", trip.getId(),
                "--admission", "nope", "--opt", "1=No"), RegisterPerson.EXIT_USAGE);
    }

    @Test
    public void run_unansweredQuestionsAreOmittedNotRequired() throws Exception {
        final Trip trip = savedTrip();
        final Person person = savedPerson();
        // No --opt at all: registers fine, and no numeric question keys are written
        Assert.assertEquals(run("--email", person.getEmail(), "--trip", trip.getId(),
                "--admission", "full"), RegisterPerson.OK);
        final Registration reg = dao.getRegistration(trip.getId(), person.getId()).join().orElse(null);
        Assert.assertNotNull(reg);
        Assert.assertFalse(reg.getOptions().containsKey("1"), "unanswered question must be omitted");
        Assert.assertEquals(reg.getAdmissionId(), "full");
    }

    @Test
    public void run_failsWhenTripNotFound() throws Exception {
        Assert.assertEquals(run("--email", "x@example.com", "--trip", "no-such-trip",
                "--admission", "full", "--opt", "1=No"), RegisterPerson.EXIT_NO_TRIP);
    }

    @Test
    public void run_dryRunWritesNothing() throws Exception {
        final Trip trip = savedTrip();
        final String email = RandomData.genAlpha(8).toLowerCase() + "@example.com";
        final int code = run("--email", email, "--trip", trip.getId(),
                "--first", "Dry", "--last", "Run", "--sex", "Male", "--birthdate", "1980-01-01",
                "--admission", "full", "--opt", "1=No", "--pay-amount", "10.00", "--dry-run");
        Assert.assertEquals(code, RegisterPerson.OK);
        Assert.assertNull(dao.getPersonByEmail(email).join(), "dry run must not create the person");
    }

    @Test
    public void randomPassword_is10AlphaNumericChars() {
        for (int i = 0; i < 20; i++) {
            final String pw = RegisterPerson.randomPassword();
            Assert.assertEquals(pw.length(), RegisterPerson.PASSWORD_LENGTH);
            Assert.assertTrue(pw.matches("[A-Za-z0-9]{10}"), "unexpected password: " + pw);
        }
    }

    // ===== Helpers =====================================================================

    private int run(final String... args) {
        return new RegisterPerson(dao).run(RegisterPerson.CliArgs.parse(args));
    }

    private Trip savedTrip() throws Exception {
        final Trip trip = Trip.builder()
                .id(RandomData.genAlpha(10))
                .title("Test Fest " + RandomData.genAlpha(4))
                .admissionOptions(List.of(
                        new AdmissionOption("full", "Full Weekend", new BigDecimal("340.00"),
                                List.of("FRI", "SAT", "SUN"), false, true),
                        new AdmissionOption("full-rosen", "Full Weekend (Rosen Guest)", new BigDecimal("295.00"),
                                List.of("FRI", "SAT", "SUN"), true, true)))
                .regOptions(List.of(
                        new RegistrationOption(1, "First time attending?", null, true),
                        new RegistrationOption(2, "Hidden question", null, false)))
                .build();
        Assert.assertTrue(dao.saveTrip(trip).join());
        return trip;
    }

    private Person savedPerson() throws Exception {
        final Person person = new Person(null, null, RandomData.genAlpha(6), null, RandomData.genAlpha(8),
                Person.Sex.Female, java.time.LocalDate.of(1980, 5, 5), null,
                RandomData.genAlpha(8).toLowerCase() + "@example.com",
                null, null, null, null, null, null, null, null);
        Assert.assertTrue(dao.savePerson(person).join());
        return person;
    }
}
