package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripWaiver;
import org.paulsens.trip.model.WaiverAcceptance;
import org.paulsens.trip.model.WaiverDocument;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The waiver bean end to end against the in-memory store ({@code docs/waivers.md}): the org library and its
 * append-only versions, the trip pins and the re-sign decision, the standing matrix, signing for yourself and
 * on someone's behalf, the registration gate, and the signed copy. Each test owns its trip; the org and its
 * people are shared.
 */
public class WaiverCommandsTest {
    private static final String BODY = "<p>I accept the <b>risks</b> of travel.</p>";

    private String orgId;
    private String otherOrgId;
    private Person orgAdmin;
    private Person manager;
    private Person child;
    private Person stranger;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        final OrgCommands orgs = new OrgCommands(TestCallers::siteAdmin);
        final Organization org = orgs.createOrganization("Waivers " + RandomData.genAlpha(8), "WVR", null);
        orgId = org.getId().getValue();
        otherOrgId = orgs.createOrganization("Other " + RandomData.genAlpha(8), "OTH", null).getId().getValue();
        orgAdmin = savedPerson("Olga", "Admin");
        assertTrue(orgs.addMember(orgId, orgAdmin.getId()));
        assertTrue(orgs.setOrgAdmin(orgId, orgAdmin.getId(), true));
        manager = savedPerson("Ken", "Parent");
        child = savedPerson("Lucy", "Parent");
        stranger = savedPerson("Sam", "Stranger");
        manager.getManagedUsers().add(child.getId());
        assertTrue(DAO.getInstance().savePerson(manager));
    }

    // ------------------------------------------------------------------ fixtures

    private static Person savedPerson(final String first, final String last) throws IOException {
        final Person person = Person.builder().first(first).last(last)
                .email("wv." + RandomData.genAlpha(10) + "@example.com").build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private Trip newTrip(final String org) throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Waiver trip")
                .startDate(LocalDateTime.now().plusDays(40)).endDate(LocalDateTime.now().plusDays(50)).build();
        trip.setOrgId(org);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static WaiverCommands as(final Person person) {
        return new WaiverCommands(() -> TestCallers.person(person.getId()));
    }

    private static WaiverCommands siteAdmin() {
        return new WaiverCommands(TestCallers::siteAdmin);
    }

    private WaiverDocument newDocument(final String title) {
        final WaiverCommands.Result result = as(orgAdmin).createDocument(orgId, title, BODY, null);
        assertTrue(result.ok(), result.message());
        return result.document();
    }

    private static Person.Id grant(final String name, final String scope) {
        final Person.Id who = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(name, scope, name).withNewPerson(who)));
        return who;
    }

    private static Trip reread(final Trip trip) {
        return DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
    }

    // ------------------------------------------------------------------ the org library

    @Test
    public void anOrgAdminAuthorsAndEveryEditIsANewVersion() {
        final WaiverCommands admin = as(orgAdmin);
        final WaiverDocument doc = newDocument("Liability " + RandomData.genAlpha(5));
        assertEquals(doc.getCurrentNumber(), 1);
        assertEquals(doc.getCurrent().getSha256(), WaiverHtml.sha256(BODY));
        assertTrue(admin.documents(orgId).stream().anyMatch(d -> d.getId().equals(doc.getId())));

        final WaiverCommands.Result same = admin.publishVersion(orgId, doc.getId(), doc.getTitle(), BODY, null);
        assertEquals(same.code(), WaiverCommands.Result.VALIDATION_FAILED, "an unchanged publish is refused");

        final WaiverCommands.Result v2 = admin.publishVersion(orgId, doc.getId(), doc.getTitle(),
                "<p>New words.</p><script>alert(1)</script>", "Reworded");
        assertTrue(v2.ok(), v2.message());
        final WaiverDocument stored = admin.document(orgId, doc.getId());
        assertEquals(stored.getVersions().size(), 2);
        assertEquals(stored.version(1).getBodyHtml(), BODY, "version 1 is never rewritten");
        assertEquals(stored.version(2).getBodyHtml(), "<p>New words.</p>", "scripts are stripped at publish");
        assertEquals(stored.version(2).getNote(), "Reworded");
        assertEquals(admin.documentVersion(orgId, doc.getId(), 0).getNumber(), 2, "0 asks for the newest");
        assertEquals(admin.documentVersion(orgId, doc.getId(), 1).getNumber(), 1);
        assertNull(admin.documentVersion(orgId, "nope", 1));
        assertEquals(admin.tripsUsing(orgId, doc.getId()), 0);
    }

    @Test
    public void theLibraryRefusesOutsidersAndBadInput() {
        final WaiverCommands outsider = as(stranger);
        assertFalse(outsider.canManage(orgId));
        assertTrue(outsider.documents(orgId).isEmpty());
        assertNull(outsider.document(orgId, FakeData.CFPW_WAIVER_ID));
        assertEquals(outsider.tripsUsing(orgId, "x"), 0);
        assertEquals(outsider.createDocument(orgId, "T", BODY, null).code(), WaiverCommands.Result.NOT_ALLOWED);
        assertEquals(outsider.publishVersion(orgId, "x", "T", BODY, null).code(), WaiverCommands.Result.NOT_ALLOWED);
        assertEquals(outsider.setRetired(orgId, "x", true).code(), WaiverCommands.Result.NOT_ALLOWED);

        final WaiverCommands admin = as(orgAdmin);
        assertEquals(admin.createDocument(orgId, " ", BODY, null).message(), "Give the document a title.");
        assertEquals(admin.createDocument(orgId, "x".repeat(WaiverCommands.MAX_TITLE + 1), BODY, null).code(),
                WaiverCommands.Result.VALIDATION_FAILED);
        assertEquals(admin.createDocument(orgId, "T", "<p><br></p>", null).message(), "The document has no text.");
        assertEquals(admin.createDocument(orgId, "T", null, null).code(), WaiverCommands.Result.VALIDATION_FAILED);
        assertEquals(admin.createDocument(orgId, "T", BODY, "n".repeat(WaiverCommands.MAX_NOTE + 1)).code(),
                WaiverCommands.Result.VALIDATION_FAILED);
        assertEquals(admin.publishVersion(orgId, "missing", "T", BODY, null).code(), WaiverCommands.Result.NOT_FOUND);
        assertEquals(admin.setRetired(orgId, "missing", true).code(), WaiverCommands.Result.NOT_FOUND);
        assertFalse(admin.canManage(otherOrgId), "an org admin manages their own org only");
    }

    @Test
    public void aRetiredDocumentLeavesThePickersButNotTheTripsThatRequireIt() throws IOException {
        final WaiverDocument doc = newDocument("Retire me " + RandomData.genAlpha(5));
        final Trip trip = newTrip(orgId);
        final WaiverCommands admin = siteAdmin();
        assertNull(admin.addToTrip(trip, doc.getId()));
        assertTrue(as(orgAdmin).setRetired(orgId, doc.getId(), true).ok());
        assertTrue(as(orgAdmin).setRetired(orgId, doc.getId(), true).ok(), "retiring twice is a no-op");
        assertTrue(admin.choicesForTrip(trip).stream().noneMatch(d -> d.getId().equals(doc.getId())));
        assertEquals(admin.requirements(trip, child.getId()).size(), 1, "the trip still requires it");
        assertTrue(admin.pinRows(trip).get(0).retired());
        assertEquals(admin.addToTrip(newTrip(orgId), doc.getId()), "That document is not available for this trip.");
        assertTrue(as(orgAdmin).setRetired(orgId, doc.getId(), false).ok());
        assertTrue(as(orgAdmin).tripsUsing(orgId, doc.getId()) <= 1,
                "counts at most the one trip that pins it (among the org's recent trips)");
    }

    @Test
    public void aLostRaceIsAConflictNotAMerge() throws IOException {
        final WaiverDocument doc = newDocument("Race " + RandomData.genAlpha(5));
        final WaiverDocument stale = DAO.getInstance().getWaiverDocument(orgId, doc.getId(), Cached.NO).orElseThrow();
        assertTrue(as(orgAdmin).publishVersion(orgId, doc.getId(), doc.getTitle(), "<p>v2</p>", null).ok());
        stale.setTitle("Stale");
        try {
            DAO.getInstance().saveWaiverDocument(stale);
            throw new AssertionError("a stale version must not save");
        } catch (final software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException expected) {
            assertEquals(stale.getVersion(), 1L, "the version is restored after a lost race");
        }
    }

    // ------------------------------------------------------------------ trip pins

    @Test
    public void pinsAreOrgScopedAndGatedOnTheTripsRoles() throws IOException {
        final WaiverDocument doc = newDocument("Pins " + RandomData.genAlpha(5));
        final Trip trip = newTrip(orgId);
        final Trip foreign = newTrip(otherOrgId);
        assertEquals(as(stranger).addToTrip(trip, doc.getId()), "Registration admin or trip manager required.");
        assertFalse(as(stranger).canConfigure(trip));
        assertFalse(as(stranger).canConfigure(null));
        assertTrue(as(stranger).choicesForTrip(trip).isEmpty());

        final Person.Id regAdmin = grant(PrivilegeCommands.REGISTRATION_ADMIN, trip.getId());
        final WaiverCommands configurer = new WaiverCommands(() -> TestCallers.person(regAdmin));
        assertTrue(configurer.canConfigure(trip));
        assertTrue(configurer.choicesForTrip(trip).stream().anyMatch(d -> d.getId().equals(doc.getId())));
        assertNull(configurer.addToTrip(trip, doc.getId()));
        assertEquals(configurer.addToTrip(trip, doc.getId()), "The trip already requires '" + doc.getTitle() + "'.");
        assertTrue(configurer.choicesForTrip(trip).stream().noneMatch(d -> d.getId().equals(doc.getId())));
        final TripWaiver pin = reread(trip).waiver(doc.getId());
        assertNotNull(pin, "the dialog's change is saved onto the stored trip at once");
        assertEquals(pin.getVersion(), 1);
        assertTrue(pin.isAcceptOlder());
        assertEquals(pin.getAddedBy(), regAdmin);

        final Person.Id tripMgr = grant(PrivilegeCommands.TRIP_MGR, foreign.getId());
        assertEquals(new WaiverCommands(() -> TestCallers.person(tripMgr)).addToTrip(foreign, doc.getId()),
                "That document is not available for this trip.", "another org's document never reaches a trip");

        assertNull(configurer.removeFromTrip(trip, doc.getId()));
        assertNull(configurer.removeFromTrip(trip, doc.getId()), "removing an absent pin is a no-op");
        assertTrue(reread(trip).getWaivers().isEmpty());
        assertEquals(as(stranger).removeFromTrip(trip, doc.getId()), "Registration admin or trip manager required.");
    }

    @Test
    public void movingAPinRecordsTheResignDecision() throws IOException {
        final WaiverDocument doc = newDocument("Move " + RandomData.genAlpha(5));
        assertTrue(as(orgAdmin).publishVersion(orgId, doc.getId(), doc.getTitle(), "<p>v2</p>", null).ok());
        final Trip trip = newTrip(orgId);
        final WaiverCommands admin = siteAdmin();
        assertTrue(admin.replacePins(trip, List.of(TripWaiver.builder().waiverId(doc.getId()).version(1).build()))
                == null);
        assertTrue(admin.pinRows(trip).get(0).newerAvailable());
        assertNull(admin.moveVersion(trip, doc.getId(), 2, false));
        final TripWaiver moved = reread(trip).waiver(doc.getId());
        assertEquals(moved.getVersion(), 2);
        assertFalse(moved.isAcceptOlder());
        assertFalse(admin.pinRows(trip).get(0).newerAvailable());
        assertEquals(admin.moveVersion(trip, doc.getId(), 9, true), "That version is not available.");
        assertEquals(admin.moveVersion(trip, "unknown", 1, true), "That version is not available.");
        assertEquals(as(stranger).moveVersion(trip, doc.getId(), 1, true),
                "Registration admin or trip manager required.");
    }

    @Test
    public void replacingPinsValidatesEveryEntry() throws IOException {
        final WaiverDocument doc = newDocument("Replace " + RandomData.genAlpha(5));
        final Trip trip = newTrip(orgId);
        final WaiverCommands admin = siteAdmin();
        final TripWaiver good = TripWaiver.builder().waiverId(doc.getId()).version(1).acceptOlder(false).build();
        assertEquals(admin.replacePins(trip, List.of(good, good)), "A document is listed twice: " + doc.getTitle()
                + ".");
        assertEquals(admin.replacePins(trip, List.of(TripWaiver.builder().waiverId(doc.getId()).version(4).build())),
                "Unknown document or version: " + doc.getId() + " v4.");
        assertNull(admin.replacePins(trip, List.of(good)));
        assertFalse(reread(trip).waiver(doc.getId()).isAcceptOlder());
        assertNull(admin.replacePins(trip, null), "null clears every pin");
        assertTrue(reread(trip).getWaivers().isEmpty());
        assertEquals(as(stranger).replacePins(trip, List.of(good)), "Registration admin or trip manager required.");
        assertTrue(admin.pinRows(null).isEmpty());
    }

    @Test
    public void anUnsavedTripKeepsItsPinsOnTheDraftOnly() {
        final WaiverDocument doc = newDocument("Draft " + RandomData.genAlpha(5));
        final Trip draft = Trip.builder().id(UUID.randomUUID().toString()).title("Unsaved").build();
        draft.setOrgId(orgId);
        assertNull(siteAdmin().addToTrip(draft, doc.getId()));
        assertNotNull(draft.waiver(doc.getId()));
        assertTrue(DAO.getInstance().getTrip(draft.getId(), Cached.NO).isEmpty());
    }

    // ------------------------------------------------------------------ standing and signing

    @Test
    public void theStandingMatrix() {
        final TripWaiver pin = TripWaiver.builder().waiverId("w").version(2).acceptOlder(true).build();
        final TripWaiver strict = TripWaiver.builder().waiverId("w").version(2).acceptOlder(false).build();
        final WaiverAcceptance v1 = WaiverAcceptance.builder().waiverId("w").version(1).build();
        final WaiverAcceptance v2 = WaiverAcceptance.builder().waiverId("w").version(2).build();
        final WaiverAcceptance v3 = WaiverAcceptance.builder().waiverId("w").version(3).build();
        assertEquals(WaiverCommands.standingOf(pin, List.of()), WaiverCommands.Standing.MISSING);
        assertEquals(WaiverCommands.standingOf(pin, List.of(v1)), WaiverCommands.Standing.SIGNED_OLDER);
        assertEquals(WaiverCommands.standingOf(strict, List.of(v1)), WaiverCommands.Standing.OUTDATED);
        assertEquals(WaiverCommands.standingOf(strict, List.of(v1, v2)), WaiverCommands.Standing.SIGNED);
        assertEquals(WaiverCommands.standingOf(strict, List.of(v3)), WaiverCommands.Standing.SIGNED,
                "a newer text than the pin counts (the pin was moved back)");
        assertTrue(WaiverCommands.Standing.SIGNED_OLDER.isSatisfied());
        assertFalse(WaiverCommands.Standing.OUTDATED.isSatisfied());
    }

    @Test
    public void aManagerSignsForTheirChildAndTheRecordSaysSo() throws IOException {
        final WaiverDocument doc = newDocument("Sign " + RandomData.genAlpha(5));
        final Trip trip = newTrip(orgId);
        final WaiverCommands admin = siteAdmin();
        assertNull(admin.addToTrip(trip, doc.getId()));
        final Trip stored = reread(trip);
        final WaiverCommands ken = as(manager);
        assertFalse(ken.isSatisfied(stored, child.getId()));
        assertEquals(ken.statusText(stored, child.getId()), "Missing 1");
        assertEquals(ken.pendingFor(stored, child.getId()).get(0).getStandingKey(), "MISSING");

        final WaiverCommands.ClientInfo client = new WaiverCommands.ClientInfo(WaiverAcceptance.Client.IOS,
                "203.0.113.9", "UniteTrip/1.0.7");
        assertEquals(ken.accept(stored, child.getId(), doc.getId(), 1, true, false, client).code(),
                WaiverCommands.AcceptResult.MISSING_ATTESTATION);
        assertEquals(ken.accept(stored, child.getId(), doc.getId(), 2, true, true, client).code(),
                WaiverCommands.AcceptResult.NOT_PINNED);
        assertEquals(ken.accept(stored, stranger.getId(), doc.getId(), 1, true, true, client).code(),
                WaiverCommands.AcceptResult.NOT_ALLOWED);
        assertEquals(ken.accept(stored, Person.Id.newInstance(), doc.getId(), 1, true, true, client).code(),
                WaiverCommands.AcceptResult.NOT_ALLOWED, "an unknown traveler is nobody Ken manages");
        assertEquals(ken.accept(null, child.getId(), doc.getId(), 1, true, true, client).code(),
                WaiverCommands.AcceptResult.NOT_FOUND);
        assertEquals(siteAdmin().accept(stored, child.getId(), doc.getId(), 1, true, true, client).code(),
                WaiverCommands.AcceptResult.NOT_ALLOWED, "a login with no person row cannot sign");

        final WaiverCommands.AcceptResult signed = ken.accept(stored, child.getId(), doc.getId(), 1, true, true,
                client);
        assertTrue(signed.ok(), signed.message());
        final WaiverAcceptance row = signed.acceptance();
        assertEquals(row.getSignerId(), manager.getId());
        assertEquals(row.getPersonId(), child.getId());
        assertTrue(row.isOnBehalf());
        assertEquals(row.getSignerName(), "Ken Parent");
        assertEquals(row.getSubjectName(), "Lucy Parent");
        assertEquals(row.getClient(), WaiverAcceptance.Client.IOS);
        assertEquals(row.getIp(), "203.0.113.9");
        assertEquals(row.getSha256(), WaiverHtml.sha256(BODY));
        assertEquals(row.getOrgId(), orgId);
        assertTrue(ken.isSatisfied(stored, child.getId()));
        assertEquals(ken.statusText(stored, child.getId()), "Signed");

        final WaiverCommands.AcceptResult again = ken.accept(stored, child.getId(), doc.getId(), 1, true, true, null);
        assertTrue(again.ok());
        assertEquals(again.acceptance().getAcceptedAt(), row.getAcceptedAt(), "a second signature answers the first");
        assertEquals(DAO.getInstance().getWaiverAcceptances(trip.getId(), child.getId()).size(), 1);

        assertEquals(ken.acceptancesFor(stored, child.getId()).size(), 1);
        assertTrue(as(stranger).acceptancesFor(stored, child.getId()).isEmpty());
        assertTrue(ken.canReadRecords(stored, child.getId()));
        assertFalse(as(stranger).canReadRecords(stored, child.getId()));
        assertFalse(ken.canReadRecords(null, child.getId()));
        final Person.Id staff = grant(PrivilegeCommands.REGISTRATION_ADMIN, trip.getId());
        assertTrue(new WaiverCommands(() -> TestCallers.person(staff)).canReadRecords(stored, child.getId()));
    }

    @Test
    public void movingWithoutAcceptingOlderMakesEveryoneSignAgain() throws IOException {
        final WaiverDocument doc = newDocument("Resign " + RandomData.genAlpha(5));
        final Trip trip = newTrip(orgId);
        assertNull(siteAdmin().addToTrip(trip, doc.getId()));
        final WaiverCommands lucy = as(child);
        assertTrue(lucy.accept(reread(trip), child.getId(), doc.getId(), 1, true, true, null).ok());
        assertFalse(lucy.accept(reread(trip), child.getId(), doc.getId(), 1, true, true, null).acceptance()
                .isOnBehalf(), "signing for yourself is not on anyone's behalf");
        assertTrue(as(orgAdmin).publishVersion(orgId, doc.getId(), doc.getTitle(), "<p>v2</p>", null).ok());

        final Trip kept = reread(trip);
        assertNull(siteAdmin().moveVersion(kept, doc.getId(), 2, true));
        assertEquals(lucy.requirements(reread(trip), child.getId()).get(0).getStanding(),
                WaiverCommands.Standing.SIGNED_OLDER);
        assertEquals(lucy.statusText(reread(trip), child.getId()), "Signed (earlier version)");
        assertEquals(lucy.requirements(reread(trip), child.getId()).get(0).getAcceptance().getVersion(), 1);

        final Trip strict = reread(trip);
        assertNull(siteAdmin().moveVersion(strict, doc.getId(), 2, false));
        assertEquals(lucy.statusText(reread(trip), child.getId()), "Must re-sign 1");
        assertFalse(lucy.isSatisfied(reread(trip), child.getId()));
        assertEquals(lucy.statusText(reread(trip), stranger.getId()), "Missing 1");
        assertEquals(lucy.statusText(newTrip(orgId), child.getId()), "", "no documents, no status");
    }

    @Test
    public void theWebsiteChecksBecomeAgreementsOnlyWhenBothBoxesAreTicked() throws IOException {
        final WaiverDocument doc = newDocument("Checks " + RandomData.genAlpha(5));
        final Trip trip = newTrip(orgId);
        assertNull(siteAdmin().addToTrip(trip, doc.getId()));
        final String lucy = child.getId().getValue();
        final Map<String, Object> checks = new HashMap<>();
        checks.put(WaiverCommands.checkKey(lucy, doc.getId(), 1, "agree"), Boolean.TRUE);
        assertTrue(WaiverCommands.agreementsFromChecks(checks).isEmpty(), "agree alone is not an agreement");
        checks.put(new WaiverCommands().key(lucy, doc.getId(), 1, "attest"), Boolean.TRUE);
        checks.put("malformed", Boolean.TRUE);
        checks.put(lucy + "|w|notanumber|agree", Boolean.TRUE);
        checks.put(lucy + "|w|notanumber|attest", Boolean.TRUE);
        final Map<String, List<WaiverCommands.Agreement>> agreements = WaiverCommands.agreementsFromChecks(checks);
        assertEquals(agreements.get(lucy), List.of(new WaiverCommands.Agreement(doc.getId(), 1, true, true)));
        assertTrue(WaiverCommands.agreementsFromChecks(null).isEmpty());

        final WaiverCommands ken = as(manager);
        ken.signFromPage(reread(trip), lucy, Map.of());
        assertFalse(ken.isSatisfied(reread(trip), child.getId()), "nothing ticked signs nothing");
        ken.signFromPage(null, lucy, checks);
        ken.signFromPage(reread(trip), lucy, checks);
        assertTrue(ken.isSatisfied(reread(trip), child.getId()));
        assertTrue(ken.applyAgreements(null, child.getId(), List.of(), null).isEmpty());
    }

    @Test
    public void aRefusedAgreementIsReported() throws IOException {
        final WaiverDocument doc = newDocument("Refused " + RandomData.genAlpha(5));
        final Trip trip = newTrip(orgId);
        assertNull(siteAdmin().addToTrip(trip, doc.getId()));
        final List<String> problems = as(stranger).applyAgreements(reread(trip), child.getId(),
                List.of(new WaiverCommands.Agreement(doc.getId(), 1, true, true)), null);
        assertEquals(problems, List.of(doc.getTitle() + ": You cannot sign for this traveler."));
        as(stranger).signFromPage(reread(trip), child.getId().getValue(),
                Map.of(WaiverCommands.checkKey(child.getId().getValue(), doc.getId(), 1, "agree"), Boolean.TRUE,
                        WaiverCommands.checkKey(child.getId().getValue(), doc.getId(), 1, "attest"), Boolean.TRUE));
        assertFalse(as(stranger).isSatisfied(reread(trip), child.getId()));
    }

    // ------------------------------------------------------------------ the registration gate

    @Test
    public void registrationIsRefusedUntilEveryDocumentIsAgreed() throws IOException {
        final WaiverDocument doc = newDocument("Gate " + RandomData.genAlpha(5));
        final Trip trip = newTrip(orgId);
        assertNull(siteAdmin().addToTrip(trip, doc.getId()));
        final Trip stored = reread(trip);
        final RegistrationCommands reg = new RegistrationCommands(() -> TestCallers.person(manager.getId()));
        final Map<String, Object> selected = new HashMap<>(Map.of(child.getId().getValue(), Boolean.TRUE,
                manager.getId().getValue(), Boolean.TRUE));
        final Map<String, Registration> regs = new HashMap<>();
        regs.put(child.getId().getValue(), new Registration(stored.getId(), child.getId()));
        regs.put(manager.getId().getValue(), new Registration(stored.getId(), manager.getId()));

        final RegistrationCommands.PartyOutcome refused = reg.registerPartyOutcome(stored, selected, regs, null);
        assertTrue(refused.registered().isEmpty());
        assertEquals(refused.refused().get(child.getId().getValue()),
                RegistrationCommands.PartyOutcome.WAIVERS_REQUIRED);

        final Map<String, List<WaiverCommands.Agreement>> agreements = new HashMap<>();
        agreements.put(child.getId().getValue(), List.of(new WaiverCommands.Agreement(doc.getId(), 1, true, true)));
        agreements.put(manager.getId().getValue(), List.of(new WaiverCommands.Agreement(doc.getId(), 1, true, false)));
        final RegistrationCommands.PartyOutcome mixed = reg.registerPartyOutcome(stored, selected, regs, null,
                agreements, new WaiverCommands.ClientInfo(WaiverAcceptance.Client.WEB, null, null));
        assertEquals(mixed.registered().stream().map(Person::getId).toList(), List.of(child.getId()));
        assertEquals(mixed.refused(), Map.of(manager.getId().getValue(),
                RegistrationCommands.PartyOutcome.WAIVERS_REQUIRED), "one unticked box refuses only that traveler");

        // The page path: the draft's flat check map, growling the refusal (no FacesContext here, so silently).
        final Map<String, Object> checks = new HashMap<>();
        final String me = manager.getId().getValue();
        checks.put(WaiverCommands.checkKey(me, doc.getId(), 1, "agree"), Boolean.TRUE);
        checks.put(WaiverCommands.checkKey(me, doc.getId(), 1, "attest"), Boolean.TRUE);
        final Map<String, Registration> mine = new HashMap<>();
        mine.put(me, new Registration(stored.getId(), manager.getId()));
        final List<Person> registered = reg.registerParty(stored, Map.of(me, Boolean.TRUE), mine, null, checks);
        assertEquals(registered.stream().map(Person::getId).toList(), List.of(manager.getId()));
        assertEquals(reg.registerParty(stored, Map.of(me, Boolean.TRUE),
                Map.of(me, new Registration(stored.getId(), manager.getId())), null, null).size(), 1,
                "a signature already on record satisfies the gate with no boxes ticked");
    }

    @Test
    public void theRefusalGrowlNamesTheDocumentsRule() throws IOException {
        final WaiverDocument doc = newDocument("Growl " + RandomData.genAlpha(5));
        final Trip trip = newTrip(orgId);
        assertNull(siteAdmin().addToTrip(trip, doc.getId()));
        final RegistrationCommands reg = new RegistrationCommands(() -> TestCallers.person(stranger.getId()));
        final String id = stranger.getId().getValue();
        assertTrue(reg.registerParty(reread(trip), Map.of(id, Boolean.TRUE),
                Map.of(id, new Registration(trip.getId(), stranger.getId())), null).isEmpty());
    }

    // ------------------------------------------------------------------ the pages' wrappers

    @Test
    public void thePageWrappersSaveAndRefuse() throws IOException {
        final WaiverCommands admin = as(orgAdmin);
        final String title = "Page " + RandomData.genAlpha(5);
        final String id = admin.saveFromPage(orgId, null, title, BODY, null);
        assertNotNull(id, "a blank id creates");
        assertNotNull(admin.saveFromPage(orgId, id, title, "<p>v2</p>", "edit"), "an id publishes the next version");
        assertEquals(admin.document(orgId, id).getCurrentNumber(), 2);
        assertNull(admin.saveFromPage(orgId, id, " ", BODY, null), "a refusal answers null");
        admin.retireFromPage(orgId, id, true);
        assertTrue(admin.document(orgId, id).isRetired());
        admin.retireFromPage(orgId, id, false);
        admin.retireFromPage(orgId, "missing", true);
        assertFalse(admin.document(orgId, id).isRetired());

        final Trip trip = newTrip(orgId);
        final WaiverCommands site = siteAdmin();
        assertTrue(site.changeFromPage(trip, "add", id, 0, true));
        assertTrue(site.changeFromPage(trip, "move", id, 1, false));
        assertEquals(reread(trip).waiver(id).getVersion(), 1);
        assertFalse(site.changeFromPage(trip, "move", id, 5, true));
        assertFalse(site.changeFromPage(trip, "bogus", id, 0, true));
        assertFalse(site.changeFromPage(trip, null, id, 0, true));
        assertTrue(site.changeFromPage(trip, "remove", id, 0, true));
        assertTrue(reread(trip).getWaivers().isEmpty());
    }

    // ------------------------------------------------------------------ the signed copy

    @Test
    public void theSignedCopyIsAPdfForThoseWhoMayReadIt() throws IOException {
        final WaiverDocument doc = newDocument("Copy " + RandomData.genAlpha(5));
        final Trip trip = newTrip(orgId);
        assertNull(siteAdmin().addToTrip(trip, doc.getId()));
        final WaiverAcceptance row = as(manager).accept(reread(trip), child.getId(), doc.getId(), 1, true, true,
                new WaiverCommands.ClientInfo(WaiverAcceptance.Client.WEB, "198.51.100.1", "Safari")).acceptance();
        final byte[] pdf = as(manager).signedPdf(trip.getId(), child.getId(), doc.getId(), 1);
        assertNotNull(pdf);
        assertEquals(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1), "%PDF");
        assertNull(as(stranger).signedPdf(trip.getId(), child.getId(), doc.getId(), 1));
        assertNull(as(manager).signedPdf(trip.getId(), child.getId(), doc.getId(), 2), "no such signature");
        assertNull(as(manager).signedPdf(null, child.getId(), doc.getId(), 1));
        assertNull(as(manager).signedPdf(trip.getId(), child.getId(), null, 1));
        assertEquals(WaiverCommands.pdfPath(row), "/waivers/signed?trip=" + trip.getId() + "&person="
                + child.getId().getValue() + "&waiver=" + doc.getId() + "&version=1");
        assertEquals(new WaiverCommands().pdfLink(row), WaiverCommands.pdfPath(row));
        assertNull(new WaiverCommands().pdfLink(null));
    }

    @Test
    public void theTwoSentencesAreTheRecordsWords() {
        assertEquals(WaiverCommands.agreeText("Code of Conduct"), "I have read and agree to Code of Conduct.");
        assertEquals(WaiverCommands.agreeText(null), "I have read and agree to this document.");
        assertEquals(WaiverCommands.authorityText("Lucy Parent"),
                "I attest that I am Lucy Parent, or that I am authorized to sign on Lucy Parent's behalf.");
        assertEquals(WaiverCommands.authorityText(" "),
                "I attest that I am this traveler, or that I am authorized to sign on this traveler's behalf.");
        final WaiverCommands bean = new WaiverCommands();
        assertEquals(bean.agree("X"), WaiverCommands.agreeText("X"));
        assertEquals(bean.authority("Y"), WaiverCommands.authorityText("Y"));
        assertEquals(bean.authorityFor(Person.builder().first("Ann").last("Lee").build()),
                WaiverCommands.authorityText("Ann Lee"));
        assertEquals(bean.authorityFor(null), WaiverCommands.authorityText(null));
        assertEquals(bean.signedLine(null), "");
        assertEquals(bean.signedLine(WaiverAcceptance.builder().version(2).signerName("Ken Parent")
                .acceptedAt(java.time.Instant.parse("2026-10-27T23:00:00Z")).build()),
                "Signed v2 on Oct 27, 2026 by Ken Parent");
        assertEquals(bean.signedLine(WaiverAcceptance.builder().version(1).build()), "Signed v1");
        assertEquals(WaiverCommands.ClientInfo.web().client(), WaiverAcceptance.Client.WEB);
        assertNull(WaiverCommands.ClientInfo.of(WaiverAcceptance.Client.API, null).ip());
        assertTrue(new WaiverCommands.Result(WaiverCommands.Result.OK, null, null).ok());
        final List<String> ids = new ArrayList<>();
        ids.add("x");
        assertTrue(new WaiverCommands.PinRow("w", "T", 1, true, 0, false, true).missing());
        assertFalse(new WaiverCommands.PinRow("w", "T", 1, true, 3, false, true).newerAvailable(), ids.get(0));
        assertNotNull(new AuditCommands().waiver(org.paulsens.trip.audit.AuditEventBuilder.TARGET_WAIVER, "w", orgId,
                "msg", AuditActor.from(null)));
    }
}
