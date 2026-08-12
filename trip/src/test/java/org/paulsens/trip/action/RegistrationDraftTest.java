package org.paulsens.trip.action;

import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationDraft;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * The session-held registration draft: typed answers must survive the add-a-family-member detour, and a
 * stale draft must never resurrect or overwrite what the store says. The session is a plain map behind a
 * mocked FacesContext (the ScopeUtil pattern); everything else is the real command + in-memory store.
 */
public class RegistrationDraftTest {
    private DAO dao;

    @BeforeClass
    public void init() {
        dao = DAO.getInstance();
    }

    @Test
    public void freshDraftDefaultsSelectTheCallerAndDigestOn() throws IOException {
        final Person me = savedPerson();
        final Person kid = savedPerson();
        final Trip trip = savedTrip();
        final Map<String, Object> session = new HashMap<>();

        try (MockedStatic<FacesContext> ignored = facesWithSession(session)) {
            final RegistrationDraft draft = commandsFor(me).loadDraft(trip, List.of(me, kid));
            assertEquals(draft.getSel().get(key(me)), Boolean.TRUE, "The caller starts selected");
            assertEquals(draft.getSel().get(key(kid)), Boolean.FALSE, "Everyone else starts unselected");
            assertEquals(draft.getDigest().get(key(me)), Boolean.TRUE, "Daily digest defaults ON");
            assertEquals(draft.getDigest().get(key(kid)), Boolean.TRUE);
            assertSame(session.get(RegistrationCommands.DRAFT_KEY_PREFIX + trip.getId()), draft,
                    "The draft is stored in the session under its trip key");
        }
    }

    @Test
    public void typedAnswersSurviveTheDetourAndNewMembersGetDefaults() throws IOException {
        final Person me = savedPerson();
        final Trip trip = savedTrip();
        final Map<String, Object> session = new HashMap<>();
        final RegistrationCommands reg = commandsFor(me);

        try (MockedStatic<FacesContext> ignored = facesWithSession(session)) {
            final RegistrationDraft first = reg.loadDraft(trip, List.of(me));
            first.getRegs().get(key(me)).getOptions().put("7", "Window seat");    // the page typing
            first.getSel().put(key(me), Boolean.TRUE);
            first.getDigest().put(key(me), Boolean.FALSE);

            // The detour: a family member is added, the page is re-entered with the larger party.
            final Person newKid = savedPerson();
            final RegistrationDraft second = reg.loadDraft(trip, List.of(me, newKid));
            assertSame(second.getRegs().get(key(me)), first.getRegs().get(key(me)),
                    "The drafted registration (with its typed answers) is carried, not rebuilt");
            assertEquals(second.getRegs().get(key(me)).getOptions().get("7"), "Window seat");
            assertEquals(second.getSel().get(key(me)), Boolean.TRUE, "The selection is carried");
            assertEquals(second.getDigest().get(key(me)), Boolean.FALSE, "The digest answer is carried");
            assertEquals(second.getSel().get(key(newKid)), Boolean.FALSE, "The new member gets defaults");
            assertEquals(second.getDigest().get(key(newKid)), Boolean.TRUE);
        }
    }

    @Test
    public void aDraftNeverResurrectsARegistrationTheStoreSaysHasMovedOn() throws IOException {
        final Person me = savedPerson();
        final Trip trip = savedTrip();
        final Map<String, Object> session = new HashMap<>();
        final RegistrationCommands reg = commandsFor(me);

        try (MockedStatic<FacesContext> ignored = facesWithSession(session)) {
            final RegistrationDraft first = reg.loadDraft(trip, List.of(me));
            first.getRegs().get(key(me)).getOptions().put("7", "stale answer");

            // Registered for real (another tab, another manager) while the draft sat in the session.
            assertTrue(dao.saveRegistration(
                    new Registration(trip.getId(), me.getId()).withStatusString("Pending")));

            final RegistrationDraft second = reg.loadDraft(trip, List.of(me));
            assertNotSame(second.getRegs().get(key(me)), first.getRegs().get(key(me)),
                    "The stored row wins over the stale draft");
            assertEquals(second.getRegs().get(key(me)).getStatus(), Registration.Status.PENDING);
        }
    }

    @Test
    public void aSingleTravelerVisitDoesNotWipeTheRestOfThePartysDraft() throws IOException {
        final Person me = savedPerson();
        final Person kid = savedPerson();
        final Trip trip = savedTrip();
        final Map<String, Object> session = new HashMap<>();
        final RegistrationCommands reg = commandsFor(me);

        try (MockedStatic<FacesContext> ignored = facesWithSession(session)) {
            final RegistrationDraft party = reg.loadDraft(trip, List.of(me, kid));
            party.getRegs().get(key(kid)).getOptions().put("7", "Aisle");

            reg.loadSingleDraft(trip, me);      // an ?id= visit narrows the member list to one

            final RegistrationDraft back = reg.loadDraft(trip, List.of(me, kid));
            assertEquals(back.getRegs().get(key(kid)).getOptions().get("7"), "Aisle",
                    "The kid's typed answer survives a single-traveler visit in between");
        }
    }

    @Test
    public void draftingToleratesMissingContextAndNulls() throws IOException {
        final Person me = savedPerson();
        // No FacesContext at all: still returns a usable draft (nothing to carry it in, but no crash).
        final RegistrationDraft draft = commandsFor(me).loadDraft(savedTrip(), List.of(me));
        assertEquals(draft.getRegs().size(), 1);
        assertTrue(commandsFor(me).loadDraft(null, List.of(me)).getRegs().isEmpty());
        assertTrue(commandsFor(me).loadSingleDraft(savedTrip(), null).getRegs().isEmpty());
    }

    @Test
    public void digestChoiceOrDefaultIsOnOnlyUntilAnAnswerExists() {
        final ChatCommands chat = new ChatCommands();
        final Registration reg = new Registration("t", Person.Id.from("p"));
        assertTrue(chat.digestChoiceOrDefault(null), "No registration yet: the form starts ON");
        assertTrue(chat.digestChoiceOrDefault(reg), "Never asked: the form starts ON");
        chat.setDigestChoice(reg, false);
        assertFalse(chat.digestChoiceOrDefault(reg), "A given NO is respected");
        chat.setDigestChoice(reg, true);
        assertTrue(chat.digestChoiceOrDefault(reg));
        assertFalse(chat.digestChoice(new Registration("t2", Person.Id.from("p2"))),
                "digestChoice itself stays strict: an absent answer reads as no");

        // parkDigestChoice: the jsft-safe page entry point (map.get(x) == true breaks the script parser).
        final Map<String, Object> digests = new HashMap<>();
        digests.put("p", Boolean.TRUE);
        chat.parkDigestChoice(reg, digests, "p");
        assertTrue(chat.digestChoice(reg));
        chat.parkDigestChoice(reg, digests, "someone-else");
        assertFalse(chat.digestChoice(reg), "An absent draft entry parks NO");
        chat.parkDigestChoice(reg, null, "p");
        assertFalse(chat.digestChoice(reg), "A null map parks NO rather than crashing");
    }

    @Test
    public void registerPartyParksTheDigestAnswerAndSkipsTheAlreadyRegistered() throws IOException {
        final Person me = savedPerson();
        final Person kid = savedPerson();
        me.getManagedUsers().add(kid.getId());
        assertTrue(dao.savePerson(me));
        final Trip trip = savedTrip();      // chatEnabled defaults ON
        assertTrue(dao.saveRegistration(
                new Registration(trip.getId(), kid.getId()).withStatusString("Pending")));

        final RegistrationCommands reg = commandsFor(me);
        final Map<String, Object> sel = new HashMap<>();
        sel.put(key(me), Boolean.TRUE);
        sel.put(key(kid), Boolean.TRUE);            // selected, but the store says already Pending
        final Map<String, Registration> regs = new HashMap<>();
        regs.put(key(me), new Registration(trip.getId(), me.getId()));
        regs.put(key(kid), reg.getRegistration(trip.getId(), kid.getId()));
        final Map<String, Object> digests = new HashMap<>();
        digests.put(key(me), Boolean.TRUE);

        final List<Person> registered = reg.registerParty(trip, sel, regs, digests);
        assertEquals(registered.size(), 1, "The already-registered kid is skipped, not re-registered");
        assertEquals(registered.get(0).getId(), me.getId());
        final Registration stored = dao.getRegistration(trip.getId(), me.getId()).orElseThrow();
        assertTrue(new ChatCommands().digestChoice(stored),
                "The digest answer was parked on the saved registration");
        assertTrue(reg.registerParty(null, sel, regs, digests).isEmpty(), "No trip: nothing registered");
    }

    @Test
    public void smallHelpersCoverTheirNullAndEdgeBranches() throws IOException {
        final Person me = savedPerson();
        final RegistrationCommands reg = commandsFor(me);
        final Trip trip = savedTrip();

        assertFalse(reg.anyUnregistered(null));
        final Map<String, Registration> regs = new HashMap<>();
        regs.put("gone", null);
        assertFalse(reg.anyUnregistered(regs), "Null entries do not count as unregistered");
        regs.put("new", new Registration(trip.getId(), me.getId()));
        assertTrue(reg.anyUnregistered(regs));

        assertEquals(reg.getChipLabel(null, me.getId()), "Register");
        assertEquals(reg.getChipLabel(trip.getId(), null), "Register");
        assertEquals(reg.getChipLabel(trip.getId(), me.getId()), "Register", "Unregistered reads Register");
        assertFalse(reg.isChipRegistered(null, me.getId()));
        assertFalse(reg.isChipRegistered(trip.getId(), null));
        assertFalse(reg.isChipRegistered(trip.getId(), me.getId()));

        assertEquals(reg.getNumPending(trip.getId()), 0);
        assertEquals(reg.registeredByLabel(null), "");
        final Registration selfMade = new Registration(trip.getId(), me.getId());
        selfMade.getOptions().put(Registration.OPT_REGISTERED_BY, me.getId().getValue());
        assertEquals(reg.registeredByLabel(selfMade), "", "Self-registered rows show no registered-by");
    }

    @Test
    public void aRegisteredRowsResponsesAreReEditedOntoTheStoredRowOnly() throws IOException {
        final Person me = savedPerson();
        final Trip trip = savedTripWithOption();
        final RegistrationCommands reg = commandsFor(me);

        final Map<String, Object> sel = new HashMap<>();
        sel.put(key(me), Boolean.TRUE);
        final Map<String, Registration> regs = new HashMap<>();
        final Registration mine = new Registration(trip.getId(), me.getId());
        mine.getOptions().put("1", "Window");
        regs.put(key(me), mine);
        final Map<String, Object> digests = new HashMap<>();
        digests.put(key(me), Boolean.TRUE);
        assertEquals(reg.registerParty(trip, sel, regs, digests).size(), 1);

        // The page's draft copy edits an answer and flips the digest; meanwhile an admin CONFIRMS the
        // row in the store. The edit must land on the STORED (confirmed) row -- saving the draft object
        // wholesale would silently revert the approval.
        final Registration draftReg = dao.getRegistration(trip.getId(), me.getId()).orElseThrow();
        assertTrue(dao.saveRegistration(dao.getRegistration(trip.getId(), me.getId()).orElseThrow()
                .withStatusString("Confirmed")));
        draftReg.getOptions().put("1", "Aisle");
        regs.put(key(me), draftReg);            // the draft still says Pending
        digests.put(key(me), Boolean.FALSE);
        sel.put(key(me), Boolean.FALSE);

        assertTrue(reg.registerParty(trip, sel, regs, digests).isEmpty(), "Nothing NEW registered");
        final Registration stored = dao.getRegistration(trip.getId(), me.getId()).orElseThrow();
        assertEquals(stored.getStatus(), Registration.Status.CONFIRMED,
                "A response edit must never touch the status");
        assertEquals(stored.getOptions().get("1"), "Aisle", "The option edit landed");
        assertFalse(new ChatCommands().digestChoice(stored), "The digest edit landed");
        assertEquals(stored.getRegisteredBy(), me.getId().getValue(), "Reserved keys survive the edit");
        // The page's draft entry was swapped for the row that was actually saved (DAO reads are
        // copies, so identity with a re-read cannot be asserted -- state can).
        assertEquals(regs.get(key(me)).getStatus(), Registration.Status.CONFIRMED,
                "The page's draft copy follows the saved row");
        assertEquals(regs.get(key(me)).getOptions().get("1"), "Aisle");

        // An untouched re-submit writes nothing (and would otherwise message "Changes saved").
        assertTrue(reg.registerParty(trip, sel, regs, digests).isEmpty());
    }

    @Test
    public void reEditsAreRefusedWhenTheSettingIsOff() throws IOException {
        final Person me = savedPerson();
        final Trip trip = savedTripWithOption();
        final RegistrationCommands reg = commandsFor(me);
        final Map<String, Object> sel = new HashMap<>();
        sel.put(key(me), Boolean.TRUE);
        final Map<String, Registration> regs = new HashMap<>();
        regs.put(key(me), new Registration(trip.getId(), me.getId()));
        assertEquals(reg.registerParty(trip, sel, regs, null).size(), 1);

        final ConfigCommands config = new ConfigCommands();
        assertTrue(config.save(new org.paulsens.trip.model.Config(
                "reg.allowEdits", "false", org.paulsens.trip.model.Config.Type.BOOLEAN, null, null, null),
                "test"));
        try {
            final Registration draftReg = dao.getRegistration(trip.getId(), me.getId()).orElseThrow();
            draftReg.getOptions().put("1", "should not stick");
            regs.put(key(me), draftReg);
            sel.put(key(me), Boolean.FALSE);
            assertTrue(reg.registerParty(trip, sel, regs, null).isEmpty());
            assertFalse("should not stick".equals(
                            dao.getRegistration(trip.getId(), me.getId()).orElseThrow().getOptions().get("1")),
                    "With reg.allowEdits off, the edit is not saved");
        } finally {
            assertTrue(config.save(new org.paulsens.trip.model.Config(
                    "reg.allowEdits", "true", org.paulsens.trip.model.Config.Type.BOOLEAN, null, null, null),
                    "test"));
        }
    }

    @Test
    public void anInProgressReEditSurvivesTheDetourUntilTheStatusChanges() throws IOException {
        final Person me = savedPerson();
        final Trip trip = savedTrip();
        final Map<String, Object> session = new HashMap<>();
        final RegistrationCommands reg = commandsFor(me);
        assertTrue(dao.saveRegistration(
                new Registration(trip.getId(), me.getId()).withStatusString("Pending")));

        try (MockedStatic<FacesContext> ignored = facesWithSession(session)) {
            final RegistrationDraft first = reg.loadDraft(trip, List.of(me));
            first.getRegs().get(key(me)).getOptions().put("7", "editing in progress");

            final RegistrationDraft second = reg.loadDraft(trip, List.of(me));
            assertSame(second.getRegs().get(key(me)), first.getRegs().get(key(me)),
                    "Same status in draft and store: the in-progress edit is carried");

            // The status moves on (approval elsewhere): the store wins, the unsaved edit is dropped.
            assertTrue(dao.saveRegistration(dao.getRegistration(trip.getId(), me.getId()).orElseThrow()
                    .withStatusString("Confirmed")));
            final RegistrationDraft third = reg.loadDraft(trip, List.of(me));
            assertNotSame(third.getRegs().get(key(me)), first.getRegs().get(key(me)));
            assertEquals(third.getRegs().get(key(me)).getStatus(), Registration.Status.CONFIRMED);
        }
    }

    @Test
    public void aRosterMemberCanStillFileTheirRegistrationRow() throws IOException {
        final Person me = savedPerson();
        // Already on the roster of a trip that has STARTED: canJoin is false on both counts, and the
        // roster bypass is what keeps the registration row maintainable (the pre-merge single page
        // never checked canJoin at all).
        final Trip trip = Trip.builder().id("draft-trip-" + RandomData.genAlpha(8)).title("Started Trip")
                .startDate(LocalDateTime.now().minusDays(1)).endDate(LocalDateTime.now().plusDays(5))
                .people(new java.util.ArrayList<>(List.of(me.getId())))
                .build();
        assertTrue(dao.saveTrip(trip));

        final Map<String, Object> sel = new HashMap<>();
        sel.put(key(me), Boolean.TRUE);
        final Map<String, Registration> regs = new HashMap<>();
        regs.put(key(me), new Registration(trip.getId(), me.getId()));
        assertEquals(commandsFor(me).registerParty(trip, sel, regs, null).size(), 1,
                "A roster member files their row even though canJoin says no");

        // A NON-roster stranger on the same started trip is still refused.
        final Person late = savedPerson();
        final Map<String, Object> sel2 = new HashMap<>();
        sel2.put(key(late), Boolean.TRUE);
        final Map<String, Registration> regs2 = new HashMap<>();
        regs2.put(key(late), new Registration(trip.getId(), late.getId()));
        assertTrue(commandsFor(late).registerParty(trip, sel2, regs2, null).isEmpty(),
                "canJoin still gates everyone not on the roster");
    }

    // ------------------------------------------------------------------ helpers

    private static String key(final Person person) {
        return person.getId().getValue();
    }

    private RegistrationCommands commandsFor(final Person person) {
        return new RegistrationCommands(() -> new Caller(person.getId(), false,
                new AuditActor(person.getEmail(), person.getId().getValue()), grantsNothing()));
    }

    private static PrivilegeCommands grantsNothing() {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(none.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return none;
    }

    private Person savedPerson() {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("draft." + RandomData.genAlpha(10) + "@example.com")
                .build();
        try {
            if (!dao.savePerson(person)) {
                throw new IllegalStateException("could not save test person");
            }
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return person;
    }

    private Trip savedTrip() throws IOException {
        final Trip trip = Trip.builder().id("draft-trip-" + RandomData.genAlpha(8)).title("Draft Trip")
                .startDate(LocalDateTime.now().plusDays(30)).endDate(LocalDateTime.now().plusDays(40))
                .build();
        assertTrue(dao.saveTrip(trip));
        return trip;
    }

    private Trip savedTripWithOption() throws IOException {
        final Trip trip = Trip.builder().id("draft-trip-" + RandomData.genAlpha(8)).title("Draft Trip")
                .startDate(LocalDateTime.now().plusDays(30)).endDate(LocalDateTime.now().plusDays(40))
                .regOptions(new java.util.ArrayList<>(List.of(
                        new org.paulsens.trip.model.RegistrationOption(1, "Seat", "Seat preference", true))))
                .build();
        assertTrue(dao.saveTrip(trip));
        return trip;
    }

    private static MockedStatic<FacesContext> facesWithSession(final Map<String, Object> session) {
        final MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class);
        final FacesContext ctx = Mockito.mock(FacesContext.class);
        final ExternalContext ext = Mockito.mock(ExternalContext.class);
        Mockito.when(ctx.getExternalContext()).thenReturn(ext);
        Mockito.when(ext.getSessionMap()).thenReturn(session);
        faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
        return faces;
    }
}
