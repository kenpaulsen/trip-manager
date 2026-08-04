package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link MailCommands}' recipient handling -- the half that decides who an email reaches.
 *
 * <p>The sending half is covered by {@code MailLocalModeTest} (local mode refuses to send) and
 * {@code MailAuditActorTest} (the actor crosses the SES completion boundary). What is pinned here is address
 * parsing and list building, where a mistake means mail goes to the wrong person or silently to nobody.
 */
public class MailRecipientsTest {

    private final MailCommands mail = new MailCommands();
    private final PersonCommands people = new PersonCommands();

    /** preferredName is derived (nickname, else first), not settable -- so the nickname is what is set here. */
    private static Person person(final String preferred, final String last, final String email) {
        final Person who = new Person();
        who.setNickname(preferred);
        who.setLast(last);
        who.setEmail(email);
        return who;
    }

    @Test
    public void formatEmailProducesTheDisplayForm() {
        Assert.assertEquals(mail.formatEmail(person("Ken", "Paulsen", "ken@example.org")),
                "Ken Paulsen <ken@example.org>");
    }

    @Test
    public void formatEmailAnswersNullForAnUnusableAddress() {
        Assert.assertNull(mail.formatEmail(person("Ken", "Paulsen", null)));
        Assert.assertNull(mail.formatEmail(person("Ken", "Paulsen", "   ")));
        Assert.assertNull(mail.formatEmail(person("Ken", "Paulsen", "not-an-address")));
    }

    /** Both names absent leaves the two separators behind -- cosmetic, but pinned so a "tidy-up" is deliberate. */
    @Test
    public void formatEmailToleratesMissingNames() {
        Assert.assertEquals(mail.formatEmail(person(null, null, "ken@example.org")), "  <ken@example.org>");
    }

    /** The display form must survive a round trip, or a reply-to becomes an unroutable string. */
    @Test
    public void bareEmailUnwrapsTheDisplayFormAndLeavesPlainAddressesAlone() {
        Assert.assertEquals(MailCommands.bareEmail("Ken Paulsen <ken@example.org>"), "ken@example.org");
        Assert.assertEquals(MailCommands.bareEmail("  ken@example.org  "), "ken@example.org");
        Assert.assertEquals(MailCommands.bareEmail("no-brackets-here"), "no-brackets-here");
        Assert.assertEquals(MailCommands.bareEmail("broken <ken@example.org"), "broken <ken@example.org");
    }

    /** Normalising TRIMS but does not lowercase, so stored addresses keep the case they were entered with. */
    @Test
    public void validateEmailTrimsWithoutLowercasingAndRejectsRubbish() {
        Assert.assertEquals(mail.validateEmail("  Ken@Example.ORG "), "Ken@Example.ORG");
        Assert.assertNull(mail.validateEmail(null));
        Assert.assertNull(mail.validateEmail(""));
        Assert.assertNull(mail.validateEmail("nope"));
    }

    @Test
    public void addRecipientsByPersonSkipsUnusableAddresses() {
        final Collection<String> recipients = new ArrayList<>();

        mail.addRecipientsByPerson(recipients, List.of(
                person("Good", "One", "good@example.org"),
                person("Bad", "Two", "not-an-address")));

        Assert.assertEquals(recipients, List.of("Good One <good@example.org>"),
                "An unusable address is dropped, not sent to");
    }

    @Test
    public void addRecipientsResolvesPeopleByIdAndSkipsUnknownOnes() {
        final Person saved = people.createPerson();
        saved.setFirst("Recip");
        saved.setLast("Ient");
        saved.setEmail("recip@example.org");
        Assert.assertTrue(people.savePerson(saved));

        final Collection<String> recipients = new ArrayList<>();
        mail.addRecipients(recipients, List.of(saved.getId(), Person.Id.from("no-such-person")));

        Assert.assertEquals(recipients.size(), 1);
        Assert.assertTrue(recipients.iterator().next().contains("recip@example.org"));
    }

    /**
     * An address that matches nobody still becomes a Person, carrying the address.
     *
     * <p>The mail-merge pages bind to a Person, so answering null here would drop a typed-in recipient on the
     * floor rather than sending to it.
     */
    @Test
    public void anUnknownAddressStillBecomesAPersonCarryingIt() {
        final Person stranger = mail.findPersonByEmail("stranger@example.org");

        Assert.assertNotNull(stranger);
        Assert.assertEquals(stranger.getEmail(), "stranger@example.org");
        Assert.assertNull(mail.findPersonByEmail(null));
    }

    @Test
    public void aKnownAddressResolvesToTheStoredPersonThroughEitherForm() {
        final Person saved = people.createPerson();
        saved.setFirst("Known");
        saved.setLast("Person");
        saved.setEmail("known@example.org");
        Assert.assertTrue(people.savePerson(saved));

        Assert.assertEquals(mail.findPersonByEmail("known@example.org").getId(), saved.getId());
        Assert.assertEquals(mail.findPersonByEmail("Known Person <known@example.org>").getId(), saved.getId(),
                "The display form must resolve to the same person");
    }

    @Test
    public void emailsToPeopleMapsTheWholeList() {
        final List<Person> found = mail.emailsToPeople(List.of("a@example.org", "b@example.org"));

        Assert.assertEquals(found.size(), 2);
        Assert.assertEquals(found.get(0).getEmail(), "a@example.org");
    }

    /** Template rendering off a Faces thread: renderTemplate works, and a bad expression THROWS by design. */
    @Test
    public void renderTemplateWorksWithoutAFacesContext() {
        final Person to = person("Ken", "Paulsen", "ken@example.org");

        Assert.assertEquals(mail.renderTemplate(to, "Hello #{to.preferredName}!"), "Hello Ken!");
        Assert.assertEquals(mail.renderTemplate(to, "No expressions here"), "No expressions here");
    }

    @Test
    public void aBadTemplateExpressionThrowsRatherThanRenderingBlank() {
        final Person to = person("Ken", "Paulsen", "ken@example.org");

        Assert.assertThrows(RuntimeException.class,
                () -> mail.renderTemplate(to, "#{to.noSuchPropertyAtAll}"));
    }
}
