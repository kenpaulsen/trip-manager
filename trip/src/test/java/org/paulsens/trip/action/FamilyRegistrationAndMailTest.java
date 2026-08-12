package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.chat.MailTemplates;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.TemplateKind;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Phase-4 behaviors: the family party registration (ordinary rows + reserved keys), the approval-email
 * recipient ladder, and the runtime-editable MAIL template machinery.
 */
public class FamilyRegistrationAndMailTest {
    private DAO dao;

    @BeforeClass
    public void init() {
        dao = DAO.getInstance();
        // The same installer the admin button and the bootstrap script use; conditional, so re-runs no-op.
        new TemplateCommands().installStarterTemplates();
    }

    // ------------------------------------------------------------------ registerParty

    @Test
    public void registerPartyWritesOrdinaryRowsWithThePartyStamps() throws IOException {
        final Person owner = savedPerson("own");
        final FamilyCommands family = familyFor(owner);
        final Person kid = family.createFamilyMember("Kid", "Party", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        final Person other = family.createFamilyMember("Other", "Party", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        assertNotNull(kid);
        assertNotNull(other);
        final Trip trip = savedTrip();

        final Map<String, Registration> regs = new HashMap<>();
        final Map<String, Object> selected = new HashMap<>();
        for (final Person traveler : List.of(owner, kid)) {
            regs.put(traveler.getId().getValue(), new Registration(trip.getId(), traveler.getId()));
            selected.put(traveler.getId().getValue(), Boolean.TRUE);
        }
        // A third family member with a form but NOT selected must be left alone.
        regs.put(other.getId().getValue(), new Registration(trip.getId(), other.getId()));
        selected.put(other.getId().getValue(), Boolean.FALSE);

        final List<Person> registered = regCommandsFor(owner).registerParty(trip, selected, regs);
        assertEquals(registered.size(), 2);

        final Registration kidRow = dao.getRegistration(trip.getId(), kid.getId()).orElseThrow();
        final Registration ownerRow = dao.getRegistration(trip.getId(), owner.getId()).orElseThrow();
        assertEquals(kidRow.getStatus(), Registration.Status.PENDING);
        assertEquals(kidRow.getRegisteredBy(), owner.getId().getValue());
        assertNotNull(kidRow.getParty());
        assertEquals(kidRow.getParty(), ownerRow.getParty(), "One shared party id per submit");
        assertTrue(dao.getRegistration(trip.getId(), other.getId()).isEmpty()
                        || dao.getRegistration(trip.getId(), other.getId()).orElseThrow().getStatus()
                                == Registration.Status.NOT_REGISTERED,
                "The unselected traveler is untouched");
    }

    @Test
    public void registerPartyRefusesStrangersAndRespectsCanJoin() throws IOException {
        final Person owner = savedPerson("own2");
        final FamilyCommands family = familyFor(owner);
        final Person kid = family.createFamilyMember("Kid2", "Party", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        assertNotNull(kid);
        final Person stranger = savedPerson("str");
        final Trip trip = savedTrip();

        final Map<String, Registration> regs = new HashMap<>();
        final Map<String, Object> selected = new HashMap<>();
        regs.put(stranger.getId().getValue(), new Registration(trip.getId(), stranger.getId()));
        selected.put(stranger.getId().getValue(), Boolean.TRUE);
        assertTrue(regCommandsFor(owner).registerParty(trip, selected, regs).isEmpty(),
                "A traveler outside the caller's reach is refused");

        // A trip that already started cannot be joined.
        final Trip started = Trip.builder().id("started-" + RandomData.genAlpha(8)).title("Started")
                .startDate(LocalDateTime.now().minusDays(1)).endDate(LocalDateTime.now().plusDays(5))
                .build();
        assertTrue(dao.saveTrip(started));
        final Map<String, Registration> regs2 = new HashMap<>();
        final Map<String, Object> selected2 = new HashMap<>();
        regs2.put(kid.getId().getValue(), new Registration(started.getId(), kid.getId()));
        selected2.put(kid.getId().getValue(), Boolean.TRUE);
        assertTrue(regCommandsFor(owner).registerParty(started, selected2, regs2).isEmpty());
    }

    @Test
    public void siteAdminsMayRegisterAnyoneAndTheUiGateAgrees() throws IOException {
        final Person admin = savedPerson("adm");
        final Person stranger = savedPerson("str2");
        final Trip trip = savedTrip();
        final RegistrationCommands asAdmin = new RegistrationCommands(() -> adminCallerFor(admin));

        assertTrue(asAdmin.canRegister(stranger), "the page's checkbox gate must match the write gate");
        final Map<String, Registration> regs = new HashMap<>();
        final Map<String, Object> selected = new HashMap<>();
        regs.put(stranger.getId().getValue(), new Registration(trip.getId(), stranger.getId()));
        selected.put(stranger.getId().getValue(), Boolean.TRUE);
        assertEquals(asAdmin.registerParty(trip, selected, regs).size(), 1,
                "a site admin registers someone outside their own managedUsers");

        final Person plain = savedPerson("pl");
        assertFalse(regCommandsFor(plain).canRegister(stranger), "plain users keep the old reach");
        assertTrue(regCommandsFor(plain).canRegister(plain), "self is always registrable");
        assertFalse(regCommandsFor(plain).canRegister(null));
    }

    // ------------------------------------------------------------------ recipients + mail values

    @Test
    public void approvalRecipientLadder() throws IOException {
        final RegistrationCommands reg = new RegistrationCommands();
        final Person withEmail = savedPerson("mail");
        final Registration plain = new Registration("t", withEmail.getId());
        assertEquals(reg.approvalRecipient(plain, withEmail), withEmail.getEmail(),
                "A person with a usable address gets their own mail");

        final Person owner = savedPerson("fallb");
        final Person noEmail = Person.builder().first("No").last("Email").build();
        assertTrue(dao.savePerson(noEmail));
        final Registration stamped = new Registration("t", noEmail.getId());
        stamped.getOptions().put(Registration.OPT_REGISTERED_BY, owner.getId().getValue());
        assertEquals(reg.approvalRecipient(stamped, noEmail), owner.getEmail(),
                "No address falls back to whoever registered them");

        final Registration unstamped = new Registration("t", noEmail.getId());
        assertNull(reg.approvalRecipient(unstamped, noEmail), "Nobody reachable: no mail, no error");
    }

    @Test
    public void mailValueHelpersProduceTheDeclaredTokens() throws IOException {
        final RegistrationCommands reg = new RegistrationCommands();
        final Trip trip = savedTrip();
        final Person traveler = savedPerson("tok");

        final Map<String, Object> received = reg.receivedMailValues(trip, List.of(traveler));
        assertEquals(received.get("tripTitle"), trip.getTitle());
        assertTrue(received.get("tripUrl").toString().endsWith("/trip/tripDetails.jsf?trip=" + trip.getId()));
        assertTrue(received.get("travelersBlock").toString().contains(traveler.getPreferredName()));

        final Map<String, Object> approved = reg.approvedMailValues(trip, traveler);
        assertEquals(approved.get("firstName"), traveler.getPreferredName());
        assertTrue(approved.get("profileUrl").toString().contains(traveler.getId().getValue()));
    }

    // ------------------------------------------------------------------ managed MAIL templates

    @Test
    public void managedTemplatesRenderWithEscapingAndSubjectFromTheName() {
        final MailCommands mail = new MailCommands();
        final MailCommands.ManagedMail rendered = mail.renderManagedTemplate(
                StarterTemplates.REGISTRATION_RECEIVED_ID,
                Map.of("tripTitle", "Fall <Pilgrimage>",
                        "tripUrl", "http://x/trip",
                        "travelersBlock", new MailTemplates.Raw("<ul><li>Kid</li></ul>")));
        assertNotNull(rendered);
        assertEquals(rendered.subject(), "Registration received - Fall &lt;Pilgrimage&gt;",
                "The template NAME is the subject line, same tokens, same escaping");
        assertTrue(rendered.body().contains("Fall &lt;Pilgrimage&gt;"), "Strings are escaped");
        assertTrue(rendered.body().contains("<ul><li>Kid</li></ul>"), "Raw blocks are verbatim");
    }

    @Test
    public void aMissingOrWrongKindTemplateAnswersNullAndSendSkips() {
        final MailCommands mail = new MailCommands();
        assertNull(mail.renderManagedTemplate("no-such-template", Map.of()));
        assertNull(mail.renderManagedTemplate(StarterTemplates.CONTAINER_ID, Map.of()),
                "A non-MAIL template must be refused, whatever its id");
        assertFalse(mail.sendManagedTemplate("no-such-template", Map.of(), "a@b.example",
                "x <no@x>", "no@x", org.paulsens.trip.audit.AuditActor.system()));
        assertFalse(mail.sendManagedTemplate(StarterTemplates.REGISTRATION_RECEIVED_ID, Map.of(),
                null, "x <no@x>", "no@x", org.paulsens.trip.audit.AuditActor.system()),
                "No recipient: quietly skipped");
    }

    @Test
    public void aDomainObjectTokenValueIsALoudProgrammingError() {
        final MailCommands mail = new MailCommands();
        try {
            mail.renderManagedTemplate(StarterTemplates.REGISTRATION_RECEIVED_ID,
                    Map.of("tripTitle", savedPerson("bad")));
            throw new AssertionError("Binding a Person must throw");
        } catch (final IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Person"));
        }
    }

    @Test
    public void registrationOptionKeyMatchesTheOptionsMapConvention() {
        // The family page binds inputs with opt.key; the stored map keys are stringified ids.
        assertEquals(new org.paulsens.trip.model.RegistrationOption(7, "s", "l", true).getKey(), "7");
    }

    @Test
    public void mailStartersAreDeclaredAndExcludedFromContentPickers() {
        assertTrue(StarterTemplates.IDS.containsAll(List.of(
                StarterTemplates.REGISTRATION_RECEIVED_ID, StarterTemplates.REGISTRATION_APPROVED_ID,
                StarterTemplates.SUPPORT_REQUEST_ID)));
        final List<ContentTemplate> choices = new ContentCommands().getTemplateChoicesFor("page:trip-index");
        assertTrue(choices.stream().noneMatch(t -> t.getKind() == TemplateKind.MAIL),
                "No Add dialog may ever offer a MAIL template");
    }

    // ------------------------------------------------------------------ helpers

    private Person savedPerson(final String tag) {
        final Person person = Person.builder()
                .first(tag + RandomData.genAlpha(4)).last(RandomData.genAlpha(6))
                .email(tag + "." + RandomData.genAlpha(8) + "@example.com")
                .build();
        try {
            assertTrue(dao.savePerson(person));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return person;
    }

    private Trip savedTrip() throws IOException {
        final Trip trip = Trip.builder().id("party-" + RandomData.genAlpha(8)).title("Party Trip")
                .startDate(LocalDateTime.now().plusDays(30)).endDate(LocalDateTime.now().plusDays(40))
                .build();
        assertTrue(dao.saveTrip(trip));
        return trip;
    }

    private FamilyCommands familyFor(final Person person) {
        return new FamilyCommands(new ConfigCommands(), new AuditCommands(), () -> callerFor(person));
    }

    private RegistrationCommands regCommandsFor(final Person person) {
        return new RegistrationCommands(() -> callerFor(person));
    }

    private Caller adminCallerFor(final Person person) {
        return new Caller(person.getId(), true,
                new org.paulsens.trip.audit.AuditActor(person.getEmail(), person.getId().getValue()),
                new PrivilegeCommands());
    }

    private Caller callerFor(final Person person) {
        final PrivilegeCommands none = org.mockito.Mockito.mock(PrivilegeCommands.class);
        org.mockito.Mockito.when(none.check(org.mockito.Mockito.any(), org.mockito.Mockito.any(),
                org.mockito.Mockito.any())).thenReturn(false);
        return new Caller(person.getId(), false,
                new org.paulsens.trip.audit.AuditActor(person.getEmail(), person.getId().getValue()), none);
    }
}
