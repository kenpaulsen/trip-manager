package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.WaiverAcceptance;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * {@link OrgWaiversResource} and {@link TripWaiversResource}: the wire shapes, the codes and the gates over the
 * REAL bean against the fake store; {@code WaiverCommandsTest} owns the rules.
 */
public class WaiversResourceTest extends ResourceTestSupport {
    private static final String BODY = "<p>Travel has risks.</p>";

    private String orgId;
    private Person admin;
    private Person manager;
    private Person child;
    private Person stranger;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        final Person.Id siteAdminId = Person.Id.newInstance();
        final OrgCommands orgs = new OrgCommands(() -> new Caller(siteAdminId, true,
                new AuditActor("root@example.com", siteAdminId.getValue()), new PrivilegeCommands()));
        orgId = orgs.createOrganization("Api waivers " + RandomData.genAlpha(8), "AWV", null).getId().getValue();
        admin = savedPerson("Ada", "Admin");
        assertTrue(orgs.addMember(orgId, admin.getId()));
        assertTrue(orgs.setOrgAdmin(orgId, admin.getId(), true));
        manager = savedPerson("Ken", "Api");
        child = savedPerson("Lucy", "Api");
        stranger = savedPerson("Sam", "Api");
        manager.getManagedUsers().add(child.getId());
        assertTrue(DAO.getInstance().savePerson(manager));
    }

    @BeforeMethod
    public void bindBeans() {
        bind(PersonCommands.class, new PersonCommands());
        bind(TripCommands.class, new TripCommands());
        bind(PrivilegeCommands.class, new PrivilegeCommands());
    }

    private static Person savedPerson(final String first, final String last) throws IOException {
        final Person person = Person.builder().first(first).last(last)
                .email("awv." + RandomData.genAlpha(10) + "@example.com").build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private Trip newTrip() throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Api waiver trip")
                .startDate(LocalDateTime.now().plusDays(30)).endDate(LocalDateTime.now().plusDays(40)).build();
        trip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private Caller currentCaller() {
        return new Caller(manager.getId(), false, AuditActor.system(), new PrivilegeCommands());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(final Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    private String createdDocument() {
        signedInAs(admin.getId());
        final Response created = resource(new OrgWaiversResource()).create(orgId, CSRF_OK,
                Map.of("title", "Release " + RandomData.genAlpha(5), "bodyHtml", BODY));
        assertOk(created);
        return (String) body(created).get("id");
    }

    // ------------------------------------------------------------------ the org library

    @Test
    @SuppressWarnings("unchecked")
    public void anOrgAdminDrivesTheLibrary() {
        final String id = createdDocument();
        final OrgWaiversResource lib = resource(new OrgWaiversResource());
        assertError(lib.create(orgId, null, Map.of()), 403, ApiErrors.CSRF);
        assertError(lib.create(orgId, CSRF_OK, Map.of("title", "No text")), 400, ApiErrors.VALIDATION_FAILED);

        final Response published = lib.publish(orgId, id, CSRF_OK, Map.of("bodyHtml", "<p>v2</p>", "note", "n"));
        assertOk(published);
        assertEquals(body(published).get("currentVersion"), 2);
        assertEquals(((List<Map<String, Object>>) body(published).get("versions")).get(1).get("note"), "n");
        assertError(lib.publish(orgId, id, null, Map.of()), 403, ApiErrors.CSRF);
        assertError(lib.publish(orgId, "missing", CSRF_OK, Map.of("title", "T", "bodyHtml", BODY)), 404,
                ApiErrors.NOT_FOUND);

        final Response v1 = lib.get(orgId, id, 1);
        assertOk(v1);
        assertEquals(body(v1).get("bodyHtml"), BODY);
        assertEquals(body(lib.get(orgId, id, null)).get("version"), 2);
        assertError(lib.get(orgId, id, 7), 404, ApiErrors.NOT_FOUND);
        assertTrue(((List<Map<String, Object>>) lib.list(orgId).getEntity()).stream()
                .anyMatch(doc -> id.equals(doc.get("id"))));

        final Response retired = lib.update(orgId, id, CSRF_OK, Map.of("retired", true));
        assertOk(retired);
        assertEquals(body(retired).get("retired"), Boolean.TRUE);
        assertError(lib.update(orgId, id, null, Map.of()), 403, ApiErrors.CSRF);
        assertError(lib.update(orgId, "missing", CSRF_OK, Map.of("retired", true)), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void outsidersAreRefused() {
        signedInAs(stranger.getId());
        final OrgWaiversResource lib = resource(new OrgWaiversResource());
        assertError(lib.list(orgId), 403, ApiErrors.FORBIDDEN);
        assertError(lib.get(orgId, "x", null), 403, ApiErrors.FORBIDDEN);
        assertError(lib.create(orgId, CSRF_OK, Map.of("title", "T", "bodyHtml", BODY)), 403, ApiErrors.FORBIDDEN);
    }

    // ------------------------------------------------------------------ a trip's documents

    @Test
    @SuppressWarnings("unchecked")
    public void aManagerReadsSignsAndDownloadsForTheirChild() throws IOException {
        final String id = createdDocument();
        final Trip trip = newTrip();
        final Person.Id regAdmin = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.REGISTRATION_ADMIN, trip.getId(), "r")
                .withNewPerson(regAdmin)));
        signedInAs(regAdmin);
        final TripWaiversResource staff = resource(new TripWaiversResource());
        assertError(staff.replace(trip.getId(), null, Map.of()), 403, ApiErrors.CSRF);
        assertError(staff.replace(trip.getId(), CSRF_OK, Map.of("waivers", List.of(Map.of("waiverId", "nope",
                "version", 1)))), 400, ApiErrors.VALIDATION_FAILED);
        final Response pinned = staff.replace(trip.getId(), CSRF_OK, Map.of("waivers",
                List.of(Map.of("waiverId", id, "version", 1, "acceptOlder", false), "not a map")));
        assertOk(pinned);
        assertEquals(((List<Map<String, Object>>) body(pinned).get("waivers")).get(0).get("acceptOlder"),
                Boolean.FALSE);

        signedInAs(manager.getId());
        final TripWaiversResource ken = resource(new TripWaiversResource());
        final String lucy = child.getId().getValue();
        final Response before = ken.list(trip.getId(), lucy);
        assertOk(before);
        assertEquals(body(before).get("satisfied"), Boolean.FALSE);
        final Map<String, Object> row = ((List<Map<String, Object>>) body(before).get("waivers")).get(0);
        assertEquals(row.get("standing"), "MISSING");
        assertEquals(row.get("authorityText"),
                "I attest that I am Lucy Api, or that I am authorized to sign on Lucy Api's behalf.");
        assertError(ken.list(trip.getId(), stranger.getId().getValue()), 403, ApiErrors.FORBIDDEN);
        assertError(ken.replace(trip.getId(), CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);

        assertError(ken.accept(trip.getId(), id, null, Map.of()), 403, ApiErrors.CSRF);
        assertError(ken.accept(trip.getId(), id, CSRF_OK, Map.of("personId", lucy, "version", 1, "agreed", true)),
                422, "MISSING_ATTESTATION");
        assertError(ken.accept(trip.getId(), id, CSRF_OK, Map.of("personId", lucy, "version", 2, "agreed", true,
                "authorityAttested", true)), 409, "NOT_PINNED");
        assertError(ken.accept(trip.getId(), id, CSRF_OK, Map.of("personId", stranger.getId().getValue(),
                "version", 1, "agreed", true, "authorityAttested", true)), 403, ApiErrors.FORBIDDEN);
        assertError(ken.pdf(trip.getId(), id, lucy, 1), 404, ApiErrors.NOT_FOUND);
        assertError(ken.pdf(trip.getId(), id, lucy, null), 404, ApiErrors.NOT_FOUND);

        final Response signed = ken.accept(trip.getId(), id, CSRF_OK, Map.of("personId", lucy, "version", 1,
                "agreed", true, "authorityAttested", true, "client", "android"));
        assertOk(signed);
        assertEquals(body(signed).get("satisfied"), Boolean.TRUE);
        final Map<String, Object> acceptance = (Map<String, Object>) body(signed).get("acceptance");
        assertEquals(acceptance.get("onBehalf"), Boolean.TRUE);
        assertTrue(((String) acceptance.get("pdfUrl")).contains("/api/trips/" + trip.getId() + "/waivers/" + id
                + "/pdf?personId=" + lucy + "&version=1"));
        assertEquals(DAO.getInstance().getWaiverAcceptances(trip.getId(), child.getId()).get(0).getClient(),
                WaiverAcceptance.Client.ANDROID);
        assertEquals(((List<Map<String, Object>>) body(ken.list(trip.getId(), lucy)).get("waivers")).get(0)
                .get("standing"), "SIGNED");

        final Response pdf = ken.pdf(trip.getId(), id, lucy, 1);
        assertEquals(pdf.getStatus(), 200);
        assertTrue(pdf.getHeaderString("Content-Disposition").contains("Lucy Api"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aPartySubmitCarriesTheAnswersAndRefusesTheUnsigned() throws IOException {
        final String id = createdDocument();
        final Trip trip = newTrip();
        signedInAsSiteAdmin(Person.Id.newInstance());
        assertOk(resource(new TripWaiversResource()).replace(trip.getId(), CSRF_OK,
                Map.of("waivers", List.of(Map.of("waiverId", id, "version", 1)))));
        bind(org.paulsens.trip.action.AuditCommands.class, new org.paulsens.trip.action.AuditCommands());
        signedInAs(manager.getId());
        final RegistrationsResource party = resource(new RegistrationsResource());
        final org.paulsens.trip.api.dto.RegisterPartyRequest body = new org.paulsens.trip.api.dto.RegisterPartyRequest(
                List.of(new org.paulsens.trip.api.dto.RegisterPartyRequest.TravelerRequest(child.getId().getValue(),
                                null, null, List.of(new org.paulsens.trip.api.dto.RegisterPartyRequest.WaiverAnswer(
                                        id, 1, true, true))),
                        new org.paulsens.trip.api.dto.RegisterPartyRequest.TravelerRequest(manager.getId().getValue(),
                                null, null)),
                "ios");
        final Response response = party.registerParty(trip.getId(), CSRF_OK, body);
        assertOk(response);
        final org.paulsens.trip.api.dto.RegisterPartyResponse out =
                (org.paulsens.trip.api.dto.RegisterPartyResponse) response.getEntity();
        assertEquals(out.registered().size(), 1);
        assertEquals(out.refused(), Map.of(manager.getId().getValue(), "WAIVERS_REQUIRED"));
        assertEquals(DAO.getInstance().getWaiverAcceptances(trip.getId(), child.getId()).get(0).getClient(),
                WaiverAcceptance.Client.IOS);
        bind(org.paulsens.trip.action.RegistrationCommands.class,
                new org.paulsens.trip.action.RegistrationCommands(this::currentCaller));
        final Response reg = party.get(trip.getId(), manager.getId().getValue());
        assertEquals(((org.paulsens.trip.api.dto.RegistrationDto) reg.getEntity()).waiversSatisfied(), Boolean.FALSE);
    }

    @Test
    public void unknownDocumentsAndClientsDegradeCleanly() throws IOException {
        final Trip trip = newTrip();
        signedInAs(manager.getId());
        final TripWaiversResource ken = resource(new TripWaiversResource());
        final Response none = ken.list(trip.getId(), null);
        assertOk(none);
        assertEquals(body(none).get("satisfied"), Boolean.TRUE, "a trip with no documents is satisfied");
        assertError(ken.accept(trip.getId(), "nope", CSRF_OK, Map.of("version", 1, "agreed", true,
                "authorityAttested", true)), 409, "NOT_PINNED");
        assertThrown(() -> ken.list(UUID.randomUUID().toString(), null), 404, ApiErrors.NOT_FOUND);
        assertEquals(TripWaiversResource.clientOf(null), WaiverAcceptance.Client.API);
        assertEquals(TripWaiversResource.clientOf("ios"), WaiverAcceptance.Client.IOS);
        assertEquals(TripWaiversResource.clientOf("toaster"), WaiverAcceptance.Client.API);
        assertFalse(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getWaivers().iterator()
                .hasNext());
    }
}
