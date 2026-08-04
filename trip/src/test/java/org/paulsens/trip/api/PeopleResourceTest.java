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
import org.paulsens.trip.action.ProfilePhotos;
import org.paulsens.trip.api.dto.PersonDataValueDto;
import org.paulsens.trip.api.dto.PersonDto;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
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

    /** A peer gets a name and nothing else; this is the leak the redaction layer exists to prevent. */
    @Test
    public void aPeerDoesNotReceiveContactDetailOrNotes() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        exists(person(OTHER, "Sam"));

        final Response response = resource.get(OTHER.getValue(), null);

        assertOk(response);
        final PersonDto dto = (PersonDto) response.getEntity();
        Assert.assertEquals(dto.first(), "Sam");
        Assert.assertNull(dto.cell(), "A peer must not receive contact detail");
        Assert.assertNull(dto.notes(), "A peer must not receive notes");
    }

    @Test
    public void searchIsRefusedWithoutPeopleAdmin() {
        signedInAs(ME);

        assertError(resource.search("sam", 25), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(people, Mockito.never()).searchPeople(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt());
    }

    @Test
    public void searchReturnsResultsForAnAdmin() {
        signedInAsSiteAdmin(ME);
        Mockito.when(people.searchPeople(ArgumentMatchers.eq("sam"), ArgumentMatchers.anyInt()))
                .thenReturn(List.of(person(OTHER, "Sam")));

        final Response response = resource.search("sam", 25);

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
    }

    /** A bulk-disclosure endpoint should not let the caller pick the page size. */
    @Test
    public void searchClampsTheLimitAtBothEnds() {
        signedInAsSiteAdmin(ME);
        Mockito.when(people.searchPeople(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        resource.search(null, 100_000);
        Mockito.verify(people).searchPeople("", 100);

        resource.search("x", -5);
        Mockito.verify(people).searchPeople("x", 1);
    }

    @Test
    public void creatingAPersonRequiresCsrfAndPeopleAdmin() {
        signedInAsSiteAdmin(ME);
        assertError(resource.create(null, null), 403, ApiErrors.CSRF);

        signedInAs(ME);
        final PeopleResource asOrdinaryUser = resource(new PeopleResource());
        assertError(asOrdinaryUser.create(CSRF_OK, null), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(people, Mockito.never()).savePerson(ArgumentMatchers.any());
    }

    @Test
    public void creatingAPersonAuditsIt() {
        signedInAsSiteAdmin(ME);
        final Person created = person(OTHER, "New");
        Mockito.when(people.createPerson()).thenReturn(created);
        Mockito.when(people.savePerson(created)).thenReturn(true);

        assertOk(resource.create(CSRF_OK, dto("Fresh")));

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

        assertError(resource.create(CSRF_OK, dto("Fresh")), 500, ApiErrors.STORE_FAILED);
        Mockito.verifyNoInteractions(audit);
    }

    @Test
    public void aPeerCannotEditSomebodyElse() {
        signedInAs(ME);
        exists(person(ME, "Ken"));
        exists(person(OTHER, "Sam"));

        assertError(resource.update(OTHER.getValue(), CSRF_OK, dto("Hacked")), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(people, Mockito.never()).savePerson(ArgumentMatchers.any());
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
        Mockito.when(people.savePerson(me)).thenReturn(true);

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
        Mockito.when(people.savePerson(me)).thenReturn(true);

        assertOk(resource.update(ME.getValue(), CSRF_OK, dto("Kenneth")));

        Assert.assertEquals(me.getLast(), "Traveller", "An absent field must survive the update");
        Assert.assertEquals(me.getCell(), "555-0100");
    }

    @Test
    public void aFailedUpdateIsReported() {
        signedInAs(ME);
        final Person me = person(ME, "Ken");
        exists(me);
        Mockito.when(people.savePerson(me)).thenReturn(false);

        assertError(resource.update(ME.getValue(), CSRF_OK, dto("Kenneth")), 500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void photoReportsPresenceAndUrl() {
        signedInAs(ME);
        final ProfilePhotos photos = bindMock(ProfilePhotos.class);
        Mockito.when(photos.hasPhoto(ME.getValue())).thenReturn(true);
        Mockito.when(photos.getUrl(ME.getValue())).thenReturn("/photo/me.jpg");

        final Response response = resource.photo(ME.getValue());

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("hasPhoto"), true);
        Assert.assertEquals(body.get("url"), "/photo/me.jpg");
    }

    @Test
    public void photoOmitsTheUrlWhenThereIsNone() {
        signedInAs(ME);
        final ProfilePhotos photos = bindMock(ProfilePhotos.class);
        Mockito.when(photos.hasPhoto(ME.getValue())).thenReturn(false);

        final Response response = resource.photo(ME.getValue());

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("hasPhoto"), false);
        Assert.assertNull(body.get("url"));
        Mockito.verify(photos, Mockito.never()).getUrl(ArgumentMatchers.anyString());
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

        assertError(resource.saveDataValue(ME.getValue(), "d1", CSRF_OK, null), 500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void theProducedTypeIsThePeopleMediaType() {
        Assert.assertEquals(new PeopleResource().versionedType(), ApiMediaTypes.PEOPLE_V1);
    }

    private static PersonDto dto(final String first) {
        return new PersonDto(null, null, first, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false);
    }

    private static PersonDataValue dataValue(final String dataId, final Object content) {
        return PersonDataValue.builder()
                .userId(ME)
                .dataId(DataId.from(dataId))
                .type("text")
                .content(content)
                .build();
    }
}
