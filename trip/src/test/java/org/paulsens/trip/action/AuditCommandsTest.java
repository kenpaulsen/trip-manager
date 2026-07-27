package org.paulsens.trip.action;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The page-facing audit API.
 *
 * <p>Two things are worth pinning here, both of which used to be wrong in production:
 *
 * <ul>
 *   <li>A null name, email or cell must not blow up message building. In the old {@code .concat()} chains it
 *       threw, which aborted the whole JSFT event block -- so a traveller with no cell number silently lost the
 *       audit record AND the registration email that followed it.</li>
 *   <li>The message has to name the person the action was about, since that is the entire point of the record.</li>
 * </ul>
 */
public class AuditCommandsTest {

    private final AuditCommands audit = new AuditCommands();

    @BeforeClass
    public void warmUpAudit() {
        // Audit's class initializer prints which sink it chose; keep that banner out of the captures below.
        org.paulsens.trip.audit.Audit.builder(org.paulsens.trip.model.AuditAction.UNKNOWN,
                org.paulsens.trip.model.AuditOutcome.UNKNOWN).message("warm-up").build();
    }

    @Test
    public void describeHandlesAFullyPopulatedPerson() {
        Assert.assertEquals(AuditCommands.describe(person("Bob", "Robert", "Smith", "bob@example.com")),
                "Bob Smith [bob@example.com]");
    }

    @Test
    public void describeSurvivesEveryFieldBeingNull() {
        // THE regression. A person with no cell threw in the old concat chain; a person with no email or no
        // name is just as possible, and none of them may cost us the record.
        Assert.assertNotNull(AuditCommands.describe(person(null, null, null, null)));
        Assert.assertNotNull(AuditCommands.describe(null));
        Assert.assertEquals(AuditCommands.describe(person(null, "Robert", "Smith", null)), "Robert Smith");
        Assert.assertEquals(AuditCommands.describe(person(null, null, null, "x@example.com")), "[x@example.com]");
    }

    @Test
    public void describePrefersThePreferredName() {
        Assert.assertTrue(AuditCommands.describe(person("Bobby", "Robert", "Smith", "b@example.com"))
                .startsWith("Bobby"), "The preferred name is what a human recognises");
    }

    @Test
    public void registrationRecordsNameTripAndTarget() {
        final Person traveller = person("Ann", "Annette", "Jones", "ann@example.com");
        final String out = captureStdout(() -> audit.registered(traveller, trip("Medjugorje 2026")));

        Assert.assertTrue(out.contains("Ann Jones"), out);
        Assert.assertTrue(out.contains("Medjugorje 2026"), out);
        Assert.assertTrue(out.contains("REGISTRATION"), out);
        Assert.assertTrue(out.contains("target=person:ann@example.com"),
                "The traveller is the TARGET of the action, not its actor: " + out);
    }

    @Test
    public void registrationOfAPersonWithNoEmailStillRecords() {
        final String out = captureStdout(() -> audit.registered(person("Ann", null, "Jones", null),
                trip("Medjugorje 2026")));
        Assert.assertTrue(out.contains("REGISTRATION"), "A missing email must not cost us the record: " + out);
    }

    @Test
    public void nullTripDoesNotBreakTheRecord() {
        final String out = captureStdout(() -> audit.registered(person("Ann", null, "Jones", "a@x.com"), null));
        Assert.assertTrue(out.contains("REGISTRATION"), out);
    }

    @Test
    public void everyMethodReturnsTheMessageItRecorded() {
        // Pages send this same text as a notification email, which is why they used to build it themselves.
        final Person target = person("Ann", null, "Jones", "ann@example.com");
        final String msg = audit.registrationRemoved(target, trip("Medjugorje 2026"));
        Assert.assertNotNull(msg);
        Assert.assertTrue(msg.contains("Ann Jones") && msg.contains("Medjugorje 2026"), msg);
    }

    @Test
    public void passwordResetRecordsOutcomeRatherThanProse() {
        final String failed = captureStdout(() -> audit.passwordReset("a@x.com", false, "last name did not match"));
        Assert.assertTrue(failed.contains("outcome=FAILURE"),
                "A failed reset must be a FIELD, not words in the message: " + failed);

        final String ok = captureStdout(() -> audit.passwordReset("a@x.com", true, null));
        Assert.assertTrue(ok.contains("outcome=SUCCESS"), ok);
    }

    @Test
    public void impersonationIsRecorded() {
        // Previously invisible: after this switch every action looks like the impersonated user's own.
        final String out = captureStdout(() -> audit.impersonation(person("Ann", null, "Jones", "ann@example.com")));
        Assert.assertTrue(out.contains("IMPERSONATION"), out);
        Assert.assertTrue(out.contains("target=person:ann@example.com"), out);
    }

    @Test
    public void deprecatedStringApiStillRecords() {
        // Unconverted pages must keep working while the conversion finishes.
        final String out = captureStdout(() -> audit.log("a@x.com", "LOGIN", "did a thing"));
        Assert.assertTrue(out.contains("LOGIN") && out.contains("did a thing"), out);
    }

    /** {@code preferred} is stored as the nickname: getPreferredName() derives from it, falling back to first. */
    private static Person person(final String preferred, final String first, final String last, final String email) {
        return Person.builder()
                .id(Person.Id.newInstance())
                .nickname(preferred)
                .first(first)
                .last(last)
                .email(email)
                .build();
    }

    private static Trip trip(final String title) {
        return Trip.builder().id("t1").title(title).build();
    }

    private static String captureStdout(final Runnable action) {
        final PrintStream original = System.out;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
