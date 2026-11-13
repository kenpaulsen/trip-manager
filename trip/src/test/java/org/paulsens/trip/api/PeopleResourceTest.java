package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PersonDataValueCommands;
import org.paulsens.trip.action.ProfilePhotoCommands;
import org.paulsens.trip.action.ProfilePhotos;
import org.paulsens.trip.api.dto.PersonDataValueDto;
import org.paulsens.trip.api.dto.PersonDto;
import org.paulsens.trip.api.dto.PrivacyDto;
import org.paulsens.trip.api.dto.ProfilePhotoDto;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.dynamo.PersonMatch;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.PrivacySettings;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link PeopleResource}, where the redaction rules and the blank-person trap both live.
 *
 * <p>Redaction here IS authorization, not formatting: a response is narrowed by the asker's relationship to the
 * subject, so a test that only checked status codes would pass while a passport leaked to a co-traveller.
 */
public class PeopleResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("people-me");
    private static final Person.Id OTHER = Person.Id.from("people-other");

    private PersonCommands people;
    private AuditCommands audit;
    private PeopleResource resource;
    private MockedStatic<PersonDataValueCommands> dataValues;

    @BeforeMethod
    public void bindBeans() {
        people = bindMock(PersonCommands.class);
        audit = bindMock(AuditCommands.class);
        dataValues = Mockito.mockStatic(PersonDataValueCommands.class);
        resource = resource(new PeopleResource());
    }

    @AfterMethod(alwaysRun = true)
    public void closeDataValues() {
        if (dataValues != null) {
            dataValues.close();
            dataValues = null;
        }
    }

    private static Person person(final Person.Id id, final String first) {
        final Person person = new Person();
        person.setId(id);
        person.setFirst(first);
        person.setLast("Traveller");
        person.setCell("555-0100");
        person.setNotes("staff note");
        return person;
    }

    private void exists(final Person person) {
        Mockito.when(people.getPerson(person.getId())).thenReturn(person);
    }

    @Test
    public void defaultOrgIsSelfOnlyAndJoinsTheOrganization() {
        // Real beans: the membership write goes through PersonCommands, and a mock there reports failure.
        bind(PersonCommands.class, new PersonCommands());
        final PersonCommands real = new PersonCommands();
        final Person me = real.createPerson();
        me.setFirst("Default");
        me.setLast("Orgtester");
        Assert.assertTrue(real.savePerson(me));
        signedInAsSiteAdmin(me.getId());
        final Response created = resource(new OrgsResource()).create(CSRF_OK,
                Map.of("name", "Org " + System.nanoTime()));
        assertOk(created);
        final String orgId = (String) ((Map<?, ?>) created.getEntity()).get("id");
        signedInAs(me.getId());
        resource = resource(new PeopleResource());

        assertError(resource.setDefaultOrg(OTHER.getValue(), CSRF_OK, Map.of("orgId", orgId)), 403,
                ApiErrors.FORBIDDEN);
        assertError(resource.setDefaultOrg(me.getId().getValue(), null, Map.of("orgId", orgId)), 403, ApiErrors.CSRF);
        assertError(resource.setDefaultOrg(me.getId().getValue(), CSRF_OK, Map.of()), 400,
                ApiErrors.VALIDATION_FAILED);
        assertError(resource.setDefaultOrg(me.getId().getValue(), CSRF_OK, Map.of("orgId", "nope")), 404,
                ApiErrors.NOT_FOUND);

        final Response response = resource.setDefaultOrg(me.getId().getValue(), CSRF_OK, Map.of("orgId", orgId));

        assertOk(response);
        Assert.assertEquals(((Map<?, ?>) response.getEntity()).get("defaultOrgId"), orgId);
        final Person saved = real.getPerson(me.getId());
        Assert.assertEquals(saved.getDefaultOrgId().getValue(), orgId);
        Assert.assertTrue(saved.getOrgIds().contains(org.paulsens.trip.model.Organization.Id.from(orgId)),
                "choosing a default organization joins it");
        Mockito.verify(audit).person(ArgumentMatchers.any(), ArgumentMatchers.eq("EDITED"), ArgumentMatchers.any());
    }

    /**
     * The trap that motivated {@code BaseResource.findPerson}.
     *
     * <p>{@code getPerson} answers a miss with {@code new Person()}, whose constructor mints a fresh random id,
     * so a plain null check never fires. Unguarded, this GET returns a blank stranger with a made-up id and a
     * 200 instead of a 404 -- and the PUT below would populate that blank object and save it as a junk row.
     */
    @Test
    public void aMissAnswers404EvenThoughTheLookupReturnsABlankPerson() {
        signedInAsSiteAdmin(ME);
        Mockito.when(people.getPerson(ArgumentMatchers.any())).thenReturn(new Person());

        assertError(resource.get(OTHER.getValue(), null), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void updatingAnUnknownPersonSavesNothing() {
        signedInAsSiteAdmin(ME);
        Mockito.when(people.getPerson(ArgumentMatchers.any())).thenReturn(new Person());

        assertError(resource.update(OTHER.getValue(), CSRF_OK, null), 404, ApiErrors.NOT_FOUND);
        Mockito.verify(people, Mockito.never()).savePerson(ArgumentMatchers.any());
        Mockito.verify(people, Mockito.never()).savePerson(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void aPersonCanReadTheirOwnFullRecord() {
        signedInAs(ME);
        exists(person(ME, "Ken"));

        final Response response = resource.get(ME.getValue(), null);

        assertOk(response);
        final PersonDto dto = (PersonDto) response.getEntity();
        Assert.assertEquals(dto.first(), "Ken");
        Assert.assertEquals(dto.cell(), "555-0100", "Self sees their own contact detail");
    }

    /** A peer gets what the subject chose to share and nothing else -- the leak the redaction layer prevents. */
    @Test
    public void aPeerDoesNotReceiveWhatTheSubjectKeptPrivate() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        final Person sam = person(OTHER, "Sam");
        sam.getPrivacy().setCell(PrivacySettings.Visibility.PRIVATE);
        exists(sam);

        final Response response = resource.get(OTHER.getValue(), null);

        assertOk(response);
        final PersonDto dto = (PersonDto) response.getEntity();
        Assert.assertEquals(dto.first(), "Sam");
        Assert.assertNull(dto.cell(), "A peer must not receive a private cell number");
        Assert.assertNull(dto.notes(), "A peer must not receive notes");
        Assert.assertNull(dto.privacy(), "A peer must not receive the privacy choices themselves");
    }

    /**
     * Audit 2026-11-11, F5. {@code ?trip=} is the CALLER's choice, so a manager of any trip at all used to get
     * the trip-admin view (notes, private contact detail, travel documents) of anybody in any organization by
     * naming their own trip. The role now raises the view only of someone who is ON that trip.
     */
    @Test
    public void aTripRoleRaisesTheViewOnlyOfSomeoneOnThatTrip() throws java.io.IOException {
        final org.paulsens.trip.action.TripCommands trips = bindMock(org.paulsens.trip.action.TripCommands.class);
        final org.paulsens.trip.action.PrivilegeCommands priv = new org.paulsens.trip.action.PrivilegeCommands();
        final String myTrip = tripOf(trips);
        final String theirTrip = tripOf(trips, OTHER);
        final String pendingTrip = tripOf(trips);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveRegistration(
                new org.paulsens.trip.model.Registration(pendingTrip, OTHER, null,
                        org.paulsens.trip.model.Registration.Status.PENDING, new java.util.HashMap<>())));
        for (final String tripId : List.of(myTrip, theirTrip, pendingTrip)) {
            Assert.assertTrue(priv.savePrivilege(priv.getOrCreate("tripMgr", tripId, "m").withNewPerson(ME), null));
        }
        signedInAs(ME);
        exists(person(ME, "Ken"));
        final Person sam = person(OTHER, "Sam");
        sam.getPrivacy().setCell(PrivacySettings.Visibility.PRIVATE);
        exists(sam);

        final PersonDto stranger = (PersonDto) resource.get(OTHER.getValue(), myTrip).getEntity();
        Assert.assertNull(stranger.notes(), "my trip says nothing about someone who is not on it");
        Assert.assertNull(stranger.cell(), "and their privacy choices still hold");
        final PersonDto unknownTrip = (PersonDto) resource.get(OTHER.getValue(), "no-such-trip").getEntity();
        Assert.assertNull(unknownTrip.notes());

        final PersonDto onRoster = (PersonDto) resource.get(OTHER.getValue(), theirTrip).getEntity();
        Assert.assertEquals(onRoster.notes(), "staff note", "the manager of a trip they ARE on reads the dossier");
        Assert.assertEquals(onRoster.cell(), "555-0100");
        final PersonDto registrant = (PersonDto) resource.get(OTHER.getValue(), pendingTrip).getEntity();
        Assert.assertEquals(registrant.notes(), "staff note", "a Pending registrant is on the trip for its staff");
    }

    /** A stored-nowhere trip the mocked bean answers by id, with this roster. */
    private static String tripOf(final org.paulsens.trip.action.TripCommands trips, final Person.Id... roster) {
        final org.paulsens.trip.model.Trip trip = org.paulsens.trip.model.Trip.builder()
                .id(java.util.UUID.randomUUID().toString()).title("Trip").people(List.of(roster)).build();
        Mockito.when(trips.getTrip(trip.getId())).thenReturn(trip);
        return trip.getId();
    }

    /** The default knobs share email/cell/city with signed-in users -- the audience the subject opted into. */
    @Test
    public void aPeerReceivesWhatTheDefaultKnobsShare() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        exists(person(OTHER, "Sam"));

        final Response response = resource.get(OTHER.getValue(), null);

        assertOk(response);
        final PersonDto dto = (PersonDto) response.getEntity();
        Assert.assertEquals(dto.cell(), "555-0100", "Default cell knob is LOGGED_IN");
    }

    /** PUT merges provided privacy knobs, leaves absent ones alone, and ignores garbage values. */
    @Test
    public void updateMergesPrivacyKnobsAndIgnoresGarbage() {
        signedInAs(ME);
        final Person me = person(ME, "Ken");
        exists(me);
        Mockito.when(people.savePerson(ArgumentMatchers.eq(me), ArgumentMatchers.any())).thenReturn(Boolean.TRUE);

        final PersonDto body = new PersonDto(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, false,
                new PrivacyDto("PRIVATE", null, null, "not-a-visibility"));
        assertOk(resource.update(ME.getValue(), CSRF_OK, body));

        Assert.assertEquals(me.getPrivacy().getEmail(), PrivacySettings.Visibility.PRIVATE, "Provided knob applies");
        Assert.assertEquals(me.getPrivacy().getCell(), PrivacySettings.Visibility.LOGGED_IN, "Absent knob untouched");
        Assert.assertEquals(me.getPrivacy().getStreet(), PrivacySettings.Visibility.PRIVATE, "Garbage is ignored");
    }

    /** The itinerary choice merges like the knobs: provided applies, absent leaves it alone. */
    @Test
    public void updateMergesTheItineraryChoice() {
        signedInAs(ME);
        final Person me = person(ME, "Ken");
        exists(me);
        Mockito.when(people.savePerson(ArgumentMatchers.eq(me), ArgumentMatchers.any())).thenReturn(Boolean.TRUE);
        final PersonDto off = new PersonDto(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, false, new PrivacyDto(null, null, null, null, false));
        assertOk(resource.update(ME.getValue(), CSRF_OK, off));
        Assert.assertFalse(me.getPrivacy().isItineraryShared(), "Provided choice applies");
        final PersonDto absent = new PersonDto(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, false, new PrivacyDto("PRIVATE", null, null, null));
        assertOk(resource.update(ME.getValue(), CSRF_OK, absent));
        Assert.assertFalse(me.getPrivacy().isItineraryShared(), "Absent choice untouched");
    }

    @Test
    public void searchIsRefusedWithoutPeopleAdmin() {
        signedInAs(ME);

        assertError(resource.search("sam", 25), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(people, Mockito.never()).searchPeople(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any());
    }

    @Test
    public void searchReturnsResultsForAnAdmin() {
        signedInAsSiteAdmin(ME);
        Mockito.when(people.searchPeople(ArgumentMatchers.eq("sam"), ArgumentMatchers.anyInt(),
                ArgumentMatchers.eq(PersonMatch.EVERY_FIELD)))
                .thenReturn(List.of(person(OTHER, "Sam")));

        final Response response = resource.search("sam", 25);

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
    }

    /** A bulk-disclosure endpoint should not let the caller pick the page size. */
    @Test
    public void searchClampsTheLimitAtBothEnds() {
        signedInAsSiteAdmin(ME);
        Mockito.when(people.searchPeople(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any()))
                .thenReturn(List.of());

        // The raw fetch is 4x the clamped page: the org filter runs after it (see the endpoint comment).
        resource.search(null, 100_000);
        Mockito.verify(people).searchPeople("", 400, PersonMatch.EVERY_FIELD);

        resource.search("x", -5);
        Mockito.verify(people).searchPeople("x", 4, PersonMatch.EVERY_FIELD);
    }

    @Test
    public void creatingAPersonRequiresCsrfAnOrgAndPeopleAdminReach() {
        signedInAsSiteAdmin(ME);
        assertError(resource.create(null, null, null), 403, ApiErrors.CSRF);

        signedInAs(ME);
        final PeopleResource asOrdinaryUser = resource(new PeopleResource());
        assertError(asOrdinaryUser.create(CSRF_OK, null, null), 400, ApiErrors.VALIDATION_FAILED);
        signedInAs(ME);
        assertError(resource(new PeopleResource())
                        .create(CSRF_OK, java.util.UUID.randomUUID().toString(), null),
                403, ApiErrors.FORBIDDEN);
        Mockito.verify(people, Mockito.never()).savePerson(ArgumentMatchers.any());
    }

    @Test
    public void creatingAPersonAuditsIt() {
        signedInAsSiteAdmin(ME);
        final Person created = person(OTHER, "New");
        Mockito.when(people.createPerson()).thenReturn(created);
        Mockito.when(people.savePerson(created)).thenReturn(true);

        assertOk(resource.create(CSRF_OK, null, dto("Fresh")));

        Assert.assertEquals(created.getFirst(), "Fresh");
        // savePerson does not audit; the edge has to, or a REST-created person leaves no trace.
        Mockito.verify(audit).person(ArgumentMatchers.eq(created), ArgumentMatchers.eq("CREATED"),
                ArgumentMatchers.any());
    }

    @Test
    public void aFailedCreateIsReportedAndNotAudited() {
        signedInAsSiteAdmin(ME);
        final Person created = person(OTHER, "New");
        Mockito.when(people.createPerson()).thenReturn(created);
        Mockito.when(people.savePerson(created)).thenReturn(false);

        assertError(resource.create(CSRF_OK, null, dto("Fresh")), 500, ApiErrors.STORE_FAILED);
        Mockito.verifyNoInteractions(audit);
    }

    @Test
    public void aPeerCannotEditSomebodyElse() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        exists(person(OTHER, "Sam"));

        assertError(resource.update(OTHER.getValue(), CSRF_OK, dto("Hacked")), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(people, Mockito.never()).savePerson(ArgumentMatchers.any());
        Mockito.verify(people, Mockito.never()).savePerson(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void updatingRequiresTheCsrfHeader() {
        signedInAsSiteAdmin(ME);

        assertError(resource.update(ME.getValue(), null, dto("x")), 403, ApiErrors.CSRF);
    }

    @Test
    public void aPersonCanEditThemselvesAndTheEditIsAudited() {
        signedInAs(ME);
        final Person me = person(ME, "Ken");
        exists(me);
        Mockito.when(people.savePerson(ArgumentMatchers.eq(me), ArgumentMatchers.any())).thenReturn(true);

        assertOk(resource.update(ME.getValue(), CSRF_OK, dto("Kenneth")));

        Assert.assertEquals(me.getFirst(), "Kenneth");
        Mockito.verify(audit).person(ArgumentMatchers.eq(me), ArgumentMatchers.eq("EDITED"), ArgumentMatchers.any());
    }

    /**
     * Absent fields are left alone rather than cleared.
     *
     * <p>Not a convenience: responses are redacted, so a client that reads a person, edits one field and posts
     * the object back would send nulls for everything it could not see. Clearing on null would erase a passport
     * on every round-trip through a co-traveller's view.
     */
    @Test
    public void absentFieldsAreLeftAloneRatherThanCleared() {
        signedInAs(ME);
        final Person me = person(ME, "Ken");
        exists(me);
        Mockito.when(people.savePerson(ArgumentMatchers.eq(me), ArgumentMatchers.any())).thenReturn(true);

        assertOk(resource.update(ME.getValue(), CSRF_OK, dto("Kenneth")));

        Assert.assertEquals(me.getLast(), "Traveller", "An absent field must survive the update");
        Assert.assertEquals(me.getCell(), "555-0100");
    }

    /**
     * Being able to edit a profile is not being able to change its sign-in address: the bean's refusal becomes
     * a 403 that says why, and nothing in the body is saved.
     */
    @Test
    public void anEmailChangeTheCallerMayNotMakeIs403AndSavesNothing() {
        signedInAsSiteAdmin(ME);
        final Person other = person(OTHER, "Sam");
        exists(person(ME, "Ken"));
        exists(other);
        Mockito.when(people.signInChangeRefusal(ArgumentMatchers.eq(other), ArgumentMatchers.any()))
                .thenReturn(org.paulsens.trip.action.OrgCommands.SIGN_IN_CHANGE_REFUSED);

        final Response refused = resource.update(OTHER.getValue(), CSRF_OK, dto("Sam"));

        assertError(refused, 403, ApiErrors.FORBIDDEN);
        @SuppressWarnings("unchecked")
        final java.util.Map<String, Object> body = (java.util.Map<String, Object>) refused.getEntity();
        Assert.assertEquals(body.get("message"), "Changing how this person signs in takes a site administrator.");
        Mockito.verify(people, Mockito.never()).savePerson(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(audit, Mockito.never()).person(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    /**
     * The same refusal with nothing stood in: an organization's people admin edits a member who also belongs to
     * another organization. The name saves; the address does not, and the reach is this REQUEST's caller (the
     * bean's own would look for a FacesContext and see nobody).
     */
    @Test
    public void aPeopleAdminEditsTheProfileButNotTheSignInAddressOfAnotherTenantsMember() throws Exception {
        bind(PersonCommands.class, new PersonCommands());
        final org.paulsens.trip.dynamo.DAO dao = org.paulsens.trip.dynamo.DAO.getInstance();
        final Person deputy = stored("Deputy");
        final Person victim = stored("Victim");
        final org.paulsens.trip.action.OrgCommands root = new org.paulsens.trip.action.OrgCommands(
                () -> new org.paulsens.trip.action.Caller(Person.Id.from("root"), true,
                        new org.paulsens.trip.audit.AuditActor("root@test", "root"),
                        new org.paulsens.trip.action.PrivilegeCommands()));
        final String orgA = root.createOrganization("PplA " + System.nanoTime(), null, null).getId().getValue();
        final String orgB = root.createOrganization("PplB " + System.nanoTime(), null, null).getId().getValue();
        Assert.assertTrue(root.addMember(orgA, deputy.getId()));
        Assert.assertTrue(root.addMember(orgA, victim.getId()));
        Assert.assertTrue(root.addMember(orgB, victim.getId()));
        Assert.assertTrue(root.grantOrgPrivilege(orgA, deputy.getId(), ApiPrivileges.PEOPLE_ADMIN));
        final String original = victim.getEmail();

        signedInAs(deputy.getId());
        final PersonDto rename = new PersonDto(null, "Vic", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, false, null);
        assertOk(resource(new PeopleResource()).update(victim.getId().getValue(), CSRF_OK, rename));
        final PersonDto rekey = new PersonDto(null, null, null, null, null, null, null, null, null,
                "attacker-" + System.nanoTime() + "@example.com", null,
                null, null, null, null, null, null, false, null);
        assertError(resource(new PeopleResource()).update(victim.getId().getValue(), CSRF_OK, rekey),
                403, ApiErrors.FORBIDDEN);

        final Person after = dao.getPerson(victim.getId(), org.paulsens.trip.cache.Cached.NO).orElseThrow();
        Assert.assertEquals(after.getNickname(), "Vic", "the profile edit went through");
        Assert.assertEquals(after.getEmail(), original, "the sign-in address did not move");
    }

    private static Person stored(final String first) throws java.io.IOException {
        final Person person = Person.builder().first(first).last("Rest")
                .email("rest." + first.toLowerCase(java.util.Locale.ROOT) + "." + System.nanoTime() + "@example.com")
                .build();
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().savePerson(person));
        return person;
    }

    @Test
    public void aFailedUpdateIsReported() {
        signedInAs(ME);
        final Person me = person(ME, "Ken");
        exists(me);
        Mockito.when(people.savePerson(ArgumentMatchers.eq(me), ArgumentMatchers.any())).thenReturn(false);

        assertError(resource.update(ME.getValue(), CSRF_OK, dto("Kenneth")), 500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void photoReportsPresenceAndUrl() {
        signedInAs(ME);
        final ProfilePhotos photos = bindMock(ProfilePhotos.class);
        Mockito.when(photos.hasPhoto(ME.getValue())).thenReturn(true);
        Mockito.when(photos.getUrl(ME.getValue())).thenReturn("/photo/me.jpg");

        Mockito.when(photos.getSelectedSlot(ME.getValue())).thenReturn(2);
        Mockito.when(photos.getSlots(ME.getValue())).thenReturn(List.of(
                new ProfilePhotos.Slot(1, "profilePics/me/1-1.jpg", "/photo/one.jpg"),
                new ProfilePhotos.Slot(2, "profilePics/me/2-1.jpg", "/photo/me.jpg")));
        Mockito.when(request.getScheme()).thenReturn("https");
        Mockito.when(request.getServerName()).thenReturn("visitqueenofpeace.com");
        Mockito.when(request.getServerPort()).thenReturn(443);

        final Response response = resource.photo(ME.getValue());

        assertOk(response);
        final ProfilePhotoDto body = (ProfilePhotoDto) response.getEntity();
        Assert.assertTrue(body.hasPhoto());
        Assert.assertEquals(body.url(), "https://visitqueenofpeace.com/photo/me.jpg",
                "a context-relative photo path leaves the API absolute");
        Assert.assertEquals(body.selectedSlot(), 2);
        Assert.assertEquals(body.slots().size(), 2);
        Assert.assertEquals(body.slots().get(0).url(), "https://visitqueenofpeace.com/photo/one.jpg");
        Assert.assertNull(body.storedSlot(), "only an upload names a stored slot");
    }

    @Test
    public void photoOmitsTheUrlWhenThereIsNone() {
        signedInAs(ME);
        final ProfilePhotos photos = bindMock(ProfilePhotos.class);
        Mockito.when(photos.hasPhoto(ME.getValue())).thenReturn(false);

        final Response response = resource.photo(ME.getValue());

        assertOk(response);
        final ProfilePhotoDto body = (ProfilePhotoDto) response.getEntity();
        Assert.assertFalse(body.hasPhoto());
        Assert.assertNull(body.url());
        Assert.assertEquals(body.selectedSlot(), 0);
        Assert.assertTrue(body.slots().isEmpty());
        Mockito.verify(photos, Mockito.never()).getUrl(ArgumentMatchers.anyString());
    }

    private ProfilePhotoCommands photoCommandsAllowing(final boolean allowed) {
        final ProfilePhotoCommands commands = bindMock(ProfilePhotoCommands.class);
        Mockito.when(commands.mayEdit(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(allowed);
        return commands;
    }

    private static java.io.InputStream bodyOf(final byte[] bytes) {
        return new java.io.ByteArrayInputStream(bytes);
    }

    @Test
    public void uploadPhotoStoresThroughTheBeanAndAnswersTheNewState() {
        signedInAs(ME);
        exists(person(ME, "Me"));
        final ProfilePhotoCommands commands = photoCommandsAllowing(true);
        final byte[] bytes = org.paulsens.trip.media.PhotoFixtures.jpeg(600, 400);
        Mockito.when(commands.storeFor(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq(bytes), ArgumentMatchers.eq(new PhotoProcessor.CropRect(10, 20, 300, 300)),
                ArgumentMatchers.eq(2))).thenReturn(new ProfilePhotoCommands.PhotoResult(null, null, 2));
        final ProfilePhotos photos = bindMock(ProfilePhotos.class);
        Mockito.when(photos.hasPhoto(ME.getValue())).thenReturn(true);
        Mockito.when(photos.getUrl(ME.getValue())).thenReturn("/profile-photos/x.jpg");
        Mockito.when(photos.getSelectedSlot(ME.getValue())).thenReturn(2);
        Mockito.when(photos.getSlots(ME.getValue()))
                .thenReturn(List.of(new ProfilePhotos.Slot(2, "x.jpg", "/profile-photos/x.jpg")));

        final Response response = resource.uploadPhoto(ME.getValue(), CSRF_OK, (long) bytes.length, 2,
                10, 20, 300, 300, bodyOf(bytes));

        assertOk(response);
        final ProfilePhotoDto body = (ProfilePhotoDto) response.getEntity();
        Assert.assertEquals(body.storedSlot(), Integer.valueOf(2));
        Assert.assertTrue(body.hasPhoto());
        Assert.assertEquals(body.slots().size(), 1);
    }

    @Test
    public void uploadPhotoRefusalsMapOntoTheStatuses() {
        signedInAs(ME);
        exists(person(ME, "Me"));
        final byte[] bytes = org.paulsens.trip.media.PhotoFixtures.jpeg(60, 40);
        assertError(resource.uploadPhoto(ME.getValue(), null, null, null, null, null, null, null, bodyOf(bytes)),
                403, ApiErrors.CSRF);
        assertError(resource.uploadPhoto(OTHER.getValue(), CSRF_OK, null, null, null, null, null, null,
                bodyOf(bytes)), 404, ApiErrors.NOT_FOUND);

        final ProfilePhotoCommands commands = photoCommandsAllowing(false);
        assertError(resource.uploadPhoto(ME.getValue(), CSRF_OK, null, null, null, null, null, null,
                bodyOf(bytes)), 403, ApiErrors.FORBIDDEN);

        Mockito.when(commands.mayEdit(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        assertError(resource.uploadPhoto(ME.getValue(), CSRF_OK, null, 5, null, null, null, null, bodyOf(bytes)),
                400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.uploadPhoto(ME.getValue(), CSRF_OK, null, null, 1, 2, null, null, bodyOf(bytes)),
                400, ApiErrors.BAD_REQUEST);
        // Declared too large: refused before a byte is read.
        assertError(resource.uploadPhoto(ME.getValue(), CSRF_OK, 17L * 1024 * 1024, null, null, null, null, null,
                bodyOf(bytes)), 413, ApiErrors.PAYLOAD_TOO_LARGE);
        assertError(resource.uploadPhoto(ME.getValue(), CSRF_OK, null, null, null, null, null, null,
                bodyOf(new byte[0])), 400, ApiErrors.VALIDATION_FAILED);

        final Person.Id[] ignored = {};
        Mockito.when(commands.storeFor(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(
                        ProfilePhotoCommands.PhotoResult.NO_FREE_SLOT, "No free slot", 0))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(
                        ProfilePhotoCommands.PhotoResult.REJECTED, "Photo rejected: garbage", 0))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(
                        ProfilePhotoCommands.PhotoResult.STORE_FAILED, "Not stored", 0))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(
                        ProfilePhotoCommands.PhotoResult.NOT_ALLOWED, "Not allowed", 0));
        assertError(resource.uploadPhoto(ME.getValue(), CSRF_OK, null, null, null, null, null, null,
                bodyOf(bytes)), 409, ApiErrors.CONFLICT);
        assertError(resource.uploadPhoto(ME.getValue(), CSRF_OK, null, null, null, null, null, null,
                bodyOf(bytes)), 422, ApiErrors.VALIDATION_FAILED);
        assertError(resource.uploadPhoto(ME.getValue(), CSRF_OK, null, null, null, null, null, null,
                bodyOf(bytes)), 500, ApiErrors.STORE_FAILED);
        assertError(resource.uploadPhoto(ME.getValue(), CSRF_OK, null, null, null, null, null, null,
                bodyOf(bytes)), 403, ApiErrors.FORBIDDEN);
        Assert.assertEquals(ignored.length, 0);
    }

    @Test
    public void rotatePhotoAnswersTheNewStateWithTheRotatedSlot() {
        signedInAs(ME);
        exists(person(ME, "Me"));
        final ProfilePhotoCommands commands = photoCommandsAllowing(true);
        Mockito.when(commands.rotateFor(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq(2),
                ArgumentMatchers.eq(org.paulsens.trip.media.QuarterTurn.RIGHT)))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(null, null, 2));
        final ProfilePhotos photos = bindMock(ProfilePhotos.class);
        Mockito.when(photos.hasPhoto(ME.getValue())).thenReturn(true);
        Mockito.when(photos.getUrl(ME.getValue())).thenReturn("/profile-photos/x-2.jpg");
        Mockito.when(photos.getSelectedSlot(ME.getValue())).thenReturn(2);
        Mockito.when(photos.getSlots(ME.getValue()))
                .thenReturn(List.of(new ProfilePhotos.Slot(2, "x-2.jpg", "/profile-photos/x-2.jpg")));

        final Response response = resource.rotatePhoto(ME.getValue(), 2, CSRF_OK,
                new org.paulsens.trip.api.dto.RotateRequest("Right"));

        assertOk(response);
        final ProfilePhotoDto body = (ProfilePhotoDto) response.getEntity();
        Assert.assertEquals(body.storedSlot(), Integer.valueOf(2));
        Assert.assertTrue(body.slots().get(0).url().endsWith("/profile-photos/x-2.jpg"));
    }

    @Test
    public void rotatePhotoRefusalsMapOntoTheStatuses() {
        signedInAs(ME);
        exists(person(ME, "Me"));
        final org.paulsens.trip.api.dto.RotateRequest left = new org.paulsens.trip.api.dto.RotateRequest("left");
        assertError(resource.rotatePhoto(ME.getValue(), 1, null, left), 403, ApiErrors.CSRF);
        assertError(resource.rotatePhoto(OTHER.getValue(), 1, CSRF_OK, left), 404, ApiErrors.NOT_FOUND);
        assertError(resource.rotatePhoto(ME.getValue(), 0, CSRF_OK, left), 400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.rotatePhoto(ME.getValue(), 1, CSRF_OK, null), 400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.rotatePhoto(ME.getValue(), 1, CSRF_OK,
                new org.paulsens.trip.api.dto.RotateRequest("up")), 400, ApiErrors.VALIDATION_FAILED);

        final ProfilePhotoCommands commands = photoCommandsAllowing(true);
        Mockito.when(commands.rotateFor(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any()))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(
                        ProfilePhotoCommands.PhotoResult.NOT_ALLOWED, "Not allowed", 0))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(
                        ProfilePhotoCommands.PhotoResult.EMPTY_SLOT, "Gone", 0))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(
                        ProfilePhotoCommands.PhotoResult.BUSY, "Busy", 0))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(
                        ProfilePhotoCommands.PhotoResult.REJECTED, "Photo rejected: garbage", 0))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(
                        ProfilePhotoCommands.PhotoResult.STORE_FAILED, "Not stored", 0))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(
                        ProfilePhotoCommands.PhotoResult.BAD_DIRECTION, "Say left or right", 0));
        assertError(resource.rotatePhoto(ME.getValue(), 1, CSRF_OK, left), 403, ApiErrors.FORBIDDEN);
        assertError(resource.rotatePhoto(ME.getValue(), 1, CSRF_OK, left), 404, ApiErrors.NOT_FOUND);
        assertError(resource.rotatePhoto(ME.getValue(), 1, CSRF_OK, left), 409, ApiErrors.CONFLICT);
        assertError(resource.rotatePhoto(ME.getValue(), 1, CSRF_OK, left), 422, ApiErrors.VALIDATION_FAILED);
        assertError(resource.rotatePhoto(ME.getValue(), 1, CSRF_OK, left), 500, ApiErrors.STORE_FAILED);
        assertError(resource.rotatePhoto(ME.getValue(), 1, CSRF_OK, left), 400, ApiErrors.VALIDATION_FAILED);
    }

    @Test
    public void deletePhotoAnswersTheRemainingStateOr404ForAnEmptySlot() {
        signedInAs(ME);
        exists(person(ME, "Me"));
        final ProfilePhotoCommands commands = photoCommandsAllowing(true);
        Mockito.when(commands.deleteSlotFor(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq(1)))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(null, null, 1));
        Mockito.when(commands.deleteSlotFor(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq(3)))
                .thenReturn(new ProfilePhotoCommands.PhotoResult(ProfilePhotoCommands.PhotoResult.EMPTY_SLOT, null, 0));
        final ProfilePhotos photos = bindMock(ProfilePhotos.class);
        Mockito.when(photos.hasPhoto(ME.getValue())).thenReturn(false);

        assertError(resource.deletePhoto(ME.getValue(), 1, null), 403, ApiErrors.CSRF);
        assertError(resource.deletePhoto(OTHER.getValue(), 1, CSRF_OK), 404, ApiErrors.NOT_FOUND);
        assertError(resource.deletePhoto(ME.getValue(), 0, CSRF_OK), 400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.deletePhoto(ME.getValue(), 3, CSRF_OK), 404, ApiErrors.NOT_FOUND);

        final Response response = resource.deletePhoto(ME.getValue(), 1, CSRF_OK);
        assertOk(response);
        Assert.assertFalse(((ProfilePhotoDto) response.getEntity()).hasPhoto());
    }

    @Test
    public void selectPhotoNeedsAnOccupiedSlotAndSavesThroughPersonCommands() {
        signedInAs(ME);
        final Person me = person(ME, "Me");
        exists(me);
        photoCommandsAllowing(true);
        final ProfilePhotos photos = bindMock(ProfilePhotos.class);
        Mockito.when(photos.hasPhoto(ME.getValue())).thenReturn(true);
        Mockito.when(photos.getUrl(ME.getValue())).thenReturn("/p/2.jpg");
        Mockito.when(photos.getSelectedSlot(ME.getValue())).thenReturn(2);
        Mockito.when(photos.getSlots(ME.getValue())).thenReturn(List.of(
                new ProfilePhotos.Slot(1, "k1", "/p/1.jpg"), new ProfilePhotos.Slot(2, "k2", "/p/2.jpg")));
        Mockito.when(people.selectProfilePhoto(me, 2)).thenReturn(true);

        assertError(resource.selectPhoto(ME.getValue(), null, Map.of("slot", 2)), 403, ApiErrors.CSRF);
        assertError(resource.selectPhoto(OTHER.getValue(), CSRF_OK, Map.of("slot", 2)), 404, ApiErrors.NOT_FOUND);
        assertError(resource.selectPhoto(ME.getValue(), CSRF_OK, Map.of()), 400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.selectPhoto(ME.getValue(), CSRF_OK, Map.of("slot", "two")), 400,
                ApiErrors.VALIDATION_FAILED);
        assertError(resource.selectPhoto(ME.getValue(), CSRF_OK, Map.of("slot", 3)), 404, ApiErrors.NOT_FOUND);

        final Response response = resource.selectPhoto(ME.getValue(), CSRF_OK, Map.of("slot", 2));
        assertOk(response);
        Assert.assertEquals(((ProfilePhotoDto) response.getEntity()).selectedSlot(), 2);
        Mockito.verify(people).selectProfilePhoto(me, 2);

        Mockito.when(people.selectProfilePhoto(me, 1)).thenReturn(false);
        assertError(resource.selectPhoto(ME.getValue(), CSRF_OK, Map.of("slot", 1)), 500, ApiErrors.STORE_FAILED);

        photoCommandsAllowing(false);
        assertError(resource.selectPhoto(ME.getValue(), CSRF_OK, Map.of("slot", 2)), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void personDataIsRefusedToAPeer() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        exists(person(OTHER, "Sam"));

        assertError(resource.data(OTHER.getValue()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.dataValue(OTHER.getValue(), "d1"), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void personDataIsReturnedToTheSubject() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        dataValues.when(() -> PersonDataValueCommands.getPersonDataValues(ME))
                .thenReturn(Map.of(DataId.from("d1"), dataValue("d1", "passport")));

        final Response response = resource.data(ME.getValue());

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
    }

    @Test
    public void anUnknownDataValueIs404() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        dataValues.when(() -> PersonDataValueCommands.getPersonDataValue(ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(null);

        assertError(resource.dataValue(ME.getValue(), "nope"), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void aDataValueIsReturnedToTheSubject() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        dataValues.when(() -> PersonDataValueCommands.getPersonDataValue(ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(dataValue("d1", "passport"));

        final Response response = resource.dataValue(ME.getValue(), "d1");

        assertOk(response);
        Assert.assertEquals(((PersonDataValueDto) response.getEntity()).content(), "passport");
    }

    @Test
    public void savingADataValueRequiresCsrfAndPermission() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        exists(person(OTHER, "Sam"));

        assertError(resource.saveDataValue(ME.getValue(), "d1", null, null), 403, ApiErrors.CSRF);
        assertError(resource.saveDataValue(OTHER.getValue(), "d1", CSRF_OK, null), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void savingADataValueStoresTheSubmittedContent() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        final PersonDataValue value = dataValue("d1", null);
        dataValues.when(() -> PersonDataValueCommands.createPersonDataValue(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(value);
        dataValues.when(() -> PersonDataValueCommands.savePersonDataValue(value)).thenReturn(true);

        assertOk(resource.saveDataValue(ME.getValue(), "d1",
                CSRF_OK, new PersonDataValueDto(null, null, "text", "a new value")));

        Assert.assertEquals(value.getContent(), "a new value");
    }

    @Test
    public void aFailedDataValueStoreIsReported() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        final PersonDataValue value = dataValue("d1", null);
        dataValues.when(() -> PersonDataValueCommands.createPersonDataValue(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(value);
        dataValues.when(() -> PersonDataValueCommands.savePersonDataValue(value)).thenReturn(false);

        assertError(resource.saveDataValue(ME.getValue(), "d1", CSRF_OK,
                new PersonDataValueDto(null, null, "text", "a new value")), 500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void aDataValueWithNoBodyOrNoTypeIsABadRequest() {
        // The model's type is @NonNull: these used to reach the builder and come back as a 500.
        signedInAs(ME);
        exists(person(ME, "Ken"));

        assertError(resource.saveDataValue(ME.getValue(), "d1", CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.saveDataValue(ME.getValue(), "d1", CSRF_OK,
                new PersonDataValueDto(null, null, null, "orphan")), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.saveDataValue(ME.getValue(), "d1", CSRF_OK,
                new PersonDataValueDto(null, null, " ", "orphan")), 400, ApiErrors.BAD_REQUEST);
    }

    @Test
    public void theProducedTypeIsThePeopleMediaType() {
        Assert.assertEquals(new PeopleResource().versionedType(), ApiMediaTypes.PEOPLE_V1);
    }

    private static PersonDto dto(final String first) {
        return new PersonDto(null, null, first, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, null);
    }

    private static PersonDataValue dataValue(final String dataId, final Object content) {
        return PersonDataValue.builder()
                .userId(ME)
                .dataId(DataId.from(dataId))
                .type("text")
                .content(content)
                .build();
    }


    /** Address, passport and sex are writable by the same people who may edit the rest; strings follow the
     *  absent-unchanged / empty-clears rule and dates cannot be cleared. */
    @Test
    public void selfCanWriteAddressPassportAndSex() {
        signedInAs(ME);
        final Person me = person(ME, "Me");
        me.getAddress().setStreet("1 Old Street");
        me.getAddress().setCity("Portland");
        me.getPassport().setNumber("OLD-1");
        me.getPassport().setExpires(java.time.LocalDate.of(2030, 1, 1));
        exists(me);
        Mockito.when(people.savePerson(ArgumentMatchers.eq(me), ArgumentMatchers.any())).thenReturn(true);

        final PersonDto body = new PersonDto(null, null, null, null, null, null, "female", null, null, null, null,
                new org.paulsens.trip.api.dto.AddressDto("", null, "Seattle", "WA", null, null),
                new org.paulsens.trip.api.dto.PassportDto("NEW-9", "US", null, null, "Portland"),
                null, null, null, null, false, null);
        assertOk(resource.update(ME.getValue(), CSRF_OK, body));

        Assert.assertEquals(me.getSex(), Person.Sex.Female, "sex is parsed case-insensitively");
        Assert.assertEquals(me.getAddress().getStreet(), "", "an empty string clears a line");
        Assert.assertEquals(me.getAddress().getCity(), "Seattle");
        Assert.assertEquals(me.getAddress().getState(), "WA");
        Assert.assertEquals(me.getPassport().getNumber(), "NEW-9");
        Assert.assertEquals(me.getPassport().getExpires(), java.time.LocalDate.of(2030, 1, 1),
                "an absent date leaves the stored one alone");
        Assert.assertEquals(me.getPassport().getPlaceOfBirth(), "Portland");
    }

    @Test
    public void anUnknownSexIsAValidationFailureNotABadRequest() {
        signedInAs(ME);
        final Person me = person(ME, "Me");
        exists(me);

        final PersonDto body = new PersonDto(null, null, null, null, null, null, "Other", null, null, null, null,
                null, null, null, null, null, null, false, null);
        assertError(resource.update(ME.getValue(), CSRF_OK, body), 400, ApiErrors.VALIDATION_FAILED);
        Mockito.verify(people, Mockito.never()).savePerson(ArgumentMatchers.any());
        Mockito.verify(people, Mockito.never()).savePerson(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    /** The redacted round-trip hazard: a body without a passport must not touch the stored one. */
    @Test
    public void anAbsentPassportAndAddressLeaveTheStoredOnesAlone() {
        signedInAs(ME);
        final Person me = person(ME, "Me");
        me.getAddress().setStreet("1 Old Street");
        me.getPassport().setNumber("OLD-1");
        exists(me);
        Mockito.when(people.savePerson(ArgumentMatchers.eq(me), ArgumentMatchers.any())).thenReturn(true);

        final PersonDto body = new PersonDto(null, "Kenny", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, false, null);
        assertOk(resource.update(ME.getValue(), CSRF_OK, body));

        Assert.assertEquals(me.getAddress().getStreet(), "1 Old Street");
        Assert.assertEquals(me.getPassport().getNumber(), "OLD-1");
        Assert.assertEquals(me.getNickname(), "Kenny");
    }
}
