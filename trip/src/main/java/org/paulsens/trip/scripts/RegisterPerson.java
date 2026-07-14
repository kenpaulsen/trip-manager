package org.paulsens.trip.scripts;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.paulsens.trip.action.RegistrationCommands;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AdmissionOption;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;

/**
 * Non-interactive command-line tool that registers a person for a trip, writing the same
 * DynamoDB records the webapp writes when someone registers via
 * {@code /trip/register.jsf?trip=...} (including the account-creation step when the person
 * does not exist yet). Intended to be driven by migration scripts — see
 * {@code trip/scripts/register-person.sh}.
 *
 * <p>Tables written (mirroring the web flow's free-registration path):</p>
 * <ul>
 *   <li>{@code people} — only when the person does not already exist (profile fields required)</li>
 *   <li>{@code pass} — only when the person is created (random 10-char password, printed)</li>
 *   <li>{@code registrations} — status {@code Pending}, options exactly like the web
 *       ({@code _registrantType} derived from age, {@code _admission}, per-trip question answers,
 *       optional {@code _discountCode})</li>
 *   <li>{@code transactions} + {@code bindings} — only when {@code --pay-amount} is supplied,
 *       mirroring what PayCommands.captureAndSave persists after a PayPal payment</li>
 * </ul>
 *
 * <p>Deliberate differences from the web flow: no emails are sent, status is always
 * {@code Pending}, and {@code --discount-code} is stored as given (it does not need to match a
 * configured DiscountCode — useful for marking imported user types like {@code speaker}).</p>
 *
 * <p>Exit codes: 0 success; 64 usage error; 3 trip not found; 4 write failed;
 * 5 already registered.</p>
 */
public final class RegisterPerson {
    static final String DEFAULT_TRIP_ID = "cd0b85e9-6722-4ac0-831e-90b40ba3be9a";
    static final int PASSWORD_LENGTH = 10;
    private static final String PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    // Exit codes
    static final int OK = 0;
    static final int EXIT_USAGE = 64;
    static final int EXIT_NO_TRIP = 3;
    static final int EXIT_WRITE_FAILED = 4;
    static final int EXIT_ALREADY_REGISTERED = 5;

    private final DAO dao;
    private final RegistrationCommands regCommands = new RegistrationCommands(); // pure helpers only

    RegisterPerson(final DAO dao) {
        this.dao = dao;
    }

    public static void main(final String[] args) {
        final CliArgs cli;
        try {
            cli = CliArgs.parse(args);
        } catch (final IllegalArgumentException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            System.err.println();
            System.err.println(usage());
            System.exit(EXIT_USAGE);
            return;
        }
        if (cli.help) {
            System.out.println(usage());
            return;
        }
        System.exit(new RegisterPerson(DAO.createStandaloneDynamoDao()).run(cli));
    }

    int run(final CliArgs cli) {
        try {
            return runInternal(cli);
        } catch (final IOException | RuntimeException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            return EXIT_WRITE_FAILED;
        }
    }

    private int runInternal(final CliArgs cli) throws IOException {
        final Trip trip = dao.getTrip(cli.tripId).join().orElse(null);
        if (trip == null) {
            System.err.println("ERROR: Trip not found: " + cli.tripId);
            return EXIT_NO_TRIP;
        }

        // Validate registration inputs against the trip before writing anything
        final String validationError = validateAgainstTrip(trip, cli);
        if (validationError != null) {
            System.err.println("ERROR: " + validationError);
            return EXIT_USAGE;
        }

        // ---- Person (create like /account/createAccount.jsf if missing) ----------------
        Person person = dao.getPersonByEmail(cli.email).join();
        final boolean createPerson = (person == null);
        String password = null;
        if (createPerson) {
            final String missing = missingProfileArgs(cli);
            if (missing != null) {
                System.err.println("ERROR: Person '" + cli.email + "' does not exist; " + missing);
                return EXIT_USAGE;
            }
            person = new Person(null, null, cli.first, null, cli.last,
                    (cli.sex == null) ? null : Person.Sex.valueOf(cli.sex),
                    cli.birthdate, cli.cell, cli.email, null, null, null, null, null, null, null, null);
            password = randomPassword();
        }

        // ---- Registration (like the free-registration path of /trip/register.jsf) ------
        final Registration existing = dao.getRegistration(trip.getId(), person.getId()).join().orElse(null);
        if ((existing != null) && (existing.getStatus() != Registration.Status.NOT_REGISTERED)) {
            System.err.println("ERROR: " + cli.email + " is already registered for '" + trip.getTitle()
                    + "' (status: " + existing.getStatus() + ").");
            return EXIT_ALREADY_REGISTERED;
        }
        final Registration registration = new Registration(trip.getId(), person.getId(), cli.created,
                Registration.Status.PENDING, buildRegistrationOptions(cli, person));

        // ---- Transaction + trip binding (like PayCommands.captureAndSave) --------------
        Transaction tx = null;
        if (cli.payAmount != null) {
            final LocalDateTime txDate = (cli.payDate != null) ? cli.payDate
                    : (cli.created != null) ? cli.created : LocalDateTime.now();
            final String note = (cli.payNote != null) ? cli.payNote : "Imported payment";
            tx = new Transaction(cli.payId, person.getId(), null, Transaction.Type.Tx,
                    Transaction.TransactionType.Payment, txDate, cli.payAmount, "Payment", note);
        }

        // ---- Write --------------------------------------------------------------------
        if (cli.dryRun) {
            System.out.println("DRY_RUN would write:");
        }
        if (createPerson) {
            if (!cli.dryRun) {
                if (!dao.savePerson(person).join()) {
                    System.err.println("ERROR: Failed to save person: " + cli.email);
                    return EXIT_WRITE_FAILED;
                }
                // Mirrors PassCommands.createCreds(email, pw): create, then force-set the password
                final Creds creds = dao.createCreds(cli.email).orElse(null);
                if (creds == null) {
                    System.err.println("ERROR: Failed to create credentials for: " + cli.email);
                    return EXIT_WRITE_FAILED;
                }
                creds.setPass(password);
                if (!dao.saveCreds(creds).join()) {
                    System.err.println("ERROR: Failed to save credentials for: " + cli.email);
                    return EXIT_WRITE_FAILED;
                }
            }
            System.out.println("CREATED_PERSON id=" + person.getId().getValue()
                    + " email=" + cli.email + " password=" + password);
        } else {
            System.out.println("EXISTING_PERSON id=" + person.getId().getValue() + " email=" + cli.email);
        }

        if (!cli.dryRun && !dao.saveRegistration(registration).join()) {
            System.err.println("ERROR: Failed to save registration for: " + cli.email);
            return EXIT_WRITE_FAILED;
        }
        System.out.println("REGISTERED trip=" + trip.getId() + " status=" + registration.getStatus()
                + " created=" + registration.getCreated() + " admission=" + cli.admission);

        if (tx != null) {
            if (!cli.dryRun) {
                if (!dao.saveTransaction(tx).join()) {
                    System.err.println("ERROR: Failed to save transaction for: " + cli.email);
                    return EXIT_WRITE_FAILED;
                }
                final String txBindKey = CompositeKey.builder()
                        .partitionKey(person.getId().getValue()).sortKey(tx.getTxId()).build().getValue();
                if (!dao.saveBinding(txBindKey, BindingType.TRANSACTION, trip.getId(), BindingType.TRIP, true)
                        .join()) {
                    System.err.println("ERROR: Failed to bind transaction to trip for: " + cli.email);
                    return EXIT_WRITE_FAILED;
                }
            }
            System.out.println("TRANSACTION txId=" + tx.getTxId() + " amount=" + tx.getAmount()
                    + " date=" + tx.getTxDate());
        }
        return OK;
    }

    /** @return an error message if the admission option doesn't fit the trip, else null. */
    private String validateAgainstTrip(final Trip trip, final CliArgs cli) {
        final List<String> validAdmissionIds = trip.getAdmissionOptions().stream()
                .map(AdmissionOption::getId).toList();
        if (!validAdmissionIds.contains(cli.admission)) {
            return "Unknown admission option '" + cli.admission + "'. Valid ids: " + validAdmissionIds;
        }
        // Question answers are optional (migrated data is often incomplete); unanswered questions
        // are simply omitted from the registration's options map. Warn about ids the trip doesn't
        // define so a typo (--opt 11=... on a 0-10 trip) doesn't silently write a junk key.
        final List<String> knownIds = trip.getRegOptions().stream()
                .map(opt -> String.valueOf(opt.getId())).toList();
        for (final String suppliedId : cli.questionAnswers.keySet()) {
            if (!knownIds.contains(suppliedId)) {
                System.err.println("WARNING: --opt " + suppliedId + "=... does not match any registration"
                        + " question on this trip (known ids: " + knownIds + "); writing it anyway.");
            }
        }
        return null;
    }

    private String missingProfileArgs(final CliArgs cli) {
        final List<String> missing = new ArrayList<>();
        if (cli.first == null) {
            missing.add("--first");
        }
        if (cli.last == null) {
            missing.add("--last");
        }
        return missing.isEmpty() ? null : "the following are required to create them: " + String.join(", ", missing);
    }

    /** Builds the registration options map exactly as register.xhtml populates it. */
    Map<String, String> buildRegistrationOptions(final CliArgs cli, final Person person) {
        final Map<String, String> options = new HashMap<>(cli.questionAnswers);
        options.put(Registration.OPT_REGISTRANT_TYPE, regCommands.deriveRegistrantType(person));
        options.put(Registration.OPT_ADMISSION, cli.admission);
        if (cli.discountCode != null) {
            options.put(Registration.OPT_DISCOUNT_CODE, cli.discountCode);
        }
        return options;
    }

    static String randomPassword() {
        final StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    static String usage() {
        return """
            Usage: register-person.sh --email <email> --admission <optionId> [options]

            Registers a person for a trip exactly as the website's registration flow would,
            creating their account first if it does not exist. Non-interactive; intended to be
            driven by migration scripts. Prints machine-readable lines on stdout:
              CREATED_PERSON id=... email=... password=...   (only when the person was created)
              EXISTING_PERSON id=... email=...
              REGISTERED trip=... status=Pending ...
              TRANSACTION txId=... amount=... date=...       (only with --pay-amount)

            Required:
              --email <email>            Person's email address (account login)
              --admission <optionId>     Admission option id defined on the trip

            Required only when the person does not exist yet:
              --first <name>             First name
              --last <name>              Last name

            Optional:
              --sex <Male|Female>        Sex (only used when creating the person)
              --birthdate <yyyy-MM-dd>   Birth date (only used when creating the person; without
                                         it the registrant type defaults to adult)
              --opt <id>=<value>         Answer to a trip registration question (repeat per
                                         question); unanswered questions are omitted
              --trip <tripId>            Trip id (default: SummerFest 2026 Orlando)
              --cell <phone>             Cell phone (only used when creating the person)
              --created <ISO date-time>  Registration created timestamp, e.g. 2026-03-10T13:07:00
                                         (default: now; use the original date for migrated data)
              --discount-code <text>     Stored under options['_discountCode']; does NOT need to
                                         match a configured code (use to tag speakers/helpers/etc)
              --pay-amount <amount>      Record a payment Transaction of this amount (e.g. 295.00)
              --pay-date <ISO date-time> Transaction date (default: --created, else now)
              --pay-note <text>          Transaction note (default: "Imported payment")
              --pay-id <id>              Transaction id, e.g. original PayPal id (default: random)
              --dry-run                  Validate and print what would be written, write nothing
              --help                     Show this help
            """;
    }

    /** Parsed command-line arguments. Package-private for tests. */
    static final class CliArgs {
        String email;
        String tripId = DEFAULT_TRIP_ID;
        String first;
        String last;
        String sex;
        LocalDate birthdate;
        String cell;
        String admission;
        String discountCode;
        LocalDateTime created;
        Float payAmount;
        LocalDateTime payDate;
        String payNote;
        String payId;
        boolean dryRun;
        boolean help;
        final Map<String, String> questionAnswers = new TreeMap<>();

        static CliArgs parse(final String[] args) {
            final CliArgs cli = new CliArgs();
            for (int i = 0; i < args.length; i++) {
                final String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> cli.help = true;
                    case "--dry-run" -> cli.dryRun = true;
                    case "--email" -> cli.email = value(args, ++i, arg).toLowerCase().trim();
                    case "--trip" -> cli.tripId = value(args, ++i, arg);
                    case "--first" -> cli.first = value(args, ++i, arg);
                    case "--last" -> cli.last = value(args, ++i, arg);
                    case "--sex" -> cli.sex = parseSex(value(args, ++i, arg));
                    case "--birthdate" -> cli.birthdate = parseDate(value(args, ++i, arg), arg);
                    case "--cell" -> cli.cell = value(args, ++i, arg);
                    case "--admission" -> cli.admission = value(args, ++i, arg);
                    case "--discount-code" -> cli.discountCode = value(args, ++i, arg);
                    case "--created" -> cli.created = parseDateTime(value(args, ++i, arg), arg);
                    case "--pay-amount" -> cli.payAmount = parseAmount(value(args, ++i, arg));
                    case "--pay-date" -> cli.payDate = parseDateTime(value(args, ++i, arg), arg);
                    case "--pay-note" -> cli.payNote = value(args, ++i, arg);
                    case "--pay-id" -> cli.payId = value(args, ++i, arg);
                    case "--opt" -> {
                        final String pair = value(args, ++i, arg);
                        final int eq = pair.indexOf('=');
                        if (eq <= 0) {
                            throw new IllegalArgumentException("--opt expects <id>=<value>, got: " + pair);
                        }
                        cli.questionAnswers.put(pair.substring(0, eq).trim(), pair.substring(eq + 1));
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            if (cli.help) {
                return cli;
            }
            if (cli.email == null || cli.email.isBlank()) {
                throw new IllegalArgumentException("--email is required.");
            }
            if (cli.admission == null || cli.admission.isBlank()) {
                throw new IllegalArgumentException("--admission is required.");
            }
            return cli;
        }

        private static String value(final String[] args, final int idx, final String arg) {
            if (idx >= args.length) {
                throw new IllegalArgumentException(arg + " requires a value.");
            }
            return args[idx];
        }

        private static String parseSex(final String raw) {
            for (final Person.Sex sex : Person.Sex.values()) {
                if (sex.name().equalsIgnoreCase(raw)) {
                    return sex.name();
                }
            }
            throw new IllegalArgumentException("--sex must be Male or Female, got: " + raw);
        }

        private static LocalDate parseDate(final String raw, final String arg) {
            try {
                return LocalDate.parse(raw);
            } catch (final DateTimeParseException ex) {
                throw new IllegalArgumentException(arg + " expects yyyy-MM-dd, got: " + raw);
            }
        }

        private static LocalDateTime parseDateTime(final String raw, final String arg) {
            try {
                return LocalDateTime.parse(raw);
            } catch (final DateTimeParseException ex) {
                throw new IllegalArgumentException(
                        arg + " expects an ISO local date-time like 2026-03-10T13:07:00, got: " + raw);
            }
        }

        private static Float parseAmount(final String raw) {
            try {
                return Float.valueOf(raw.replace("$", "").replace(",", "").trim());
            } catch (final NumberFormatException ex) {
                throw new IllegalArgumentException("--pay-amount expects a number like 295.00, got: " + raw);
            }
        }
    }
}
