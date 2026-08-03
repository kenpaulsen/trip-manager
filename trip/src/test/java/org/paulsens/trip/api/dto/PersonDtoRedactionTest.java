package org.paulsens.trip.api.dto;

import java.time.LocalDate;
import java.util.List;
import org.paulsens.trip.api.AccessLevel;
import org.paulsens.trip.api.mapper.PersonMapper;
import org.paulsens.trip.model.Address;
import org.paulsens.trip.model.Passport;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * What each kind of viewer may see of another person.
 *
 * <p>These assert ABSENCE, which is unusual and deliberate. A test that checks the fields a response contains
 * passes just as happily when the response contains six more it should not -- and over-sharing is the failure
 * mode that matters here, because the API hands people's records to other people. A passport number reaching a
 * co-traveller is not a rendering bug that someone notices; it is a disclosure that nobody notices.
 *
 * <p>Anything added to {@code PersonDto} lands in one of these buckets by definition, since
 * {@code redactedFor} names every field explicitly and will not compile until it does.
 */
public class PersonDtoRedactionTest {

    private static final Person SUBJECT = fullPerson();

    @Test
    public void aCoTravellerSeesAName_andNothingElseThatIdentifiesOrLocatesThem() {
        final PersonDto dto = dtoFor(AccessLevel.PEER);

        // The point of the roster: you can see who else is on the trip.
        Assert.assertEquals(dto.first(), "Ken");
        Assert.assertEquals(dto.last(), "Paulsen");
        // Everything else is somebody's private business.
        Assert.assertNull(dto.passport(), "A co-traveller must never receive passport details.");
        Assert.assertNull(dto.tsa(), "A co-traveller must never receive a TSA number.");
        Assert.assertNull(dto.birthdate(), "A birthdate is identity data, not roster data.");
        Assert.assertNull(dto.cell());
        Assert.assertNull(dto.email());
        Assert.assertNull(dto.address());
        Assert.assertNull(dto.notes());
        Assert.assertNull(dto.emergencyContactName());
        Assert.assertNull(dto.emergencyContactPhone());
        Assert.assertNull(dto.managedUsers());
    }

    @Test
    public void aTripViewerGetsContactDetailsButNoTravelDocumentsAndNoNotes() {
        final PersonDto dto = dtoFor(AccessLevel.TRIP_VIEWER);

        // Trip staff need to be able to phone someone.
        Assert.assertEquals(dto.cell(), "555-1212");
        Assert.assertEquals(dto.email(), "ken@example.com");
        Assert.assertNotNull(dto.emergencyContactPhone());
        // Read-only roster access is not dossier access.
        Assert.assertNull(dto.passport(), "tripView is roster access; it does not carry passport data.");
        Assert.assertNull(dto.tsa());
        Assert.assertNull(dto.notes(), "Notes are written ABOUT travellers, not for everyone with roster access.");
    }

    @Test
    public void aTripAdminGetsTravelDocumentsBecauseTheyFileTheManifest() {
        final PersonDto dto = dtoFor(AccessLevel.TRIP_ADMIN);

        Assert.assertNotNull(dto.passport(), "A trip manager cannot book travel without this.");
        Assert.assertEquals(dto.passport().number(), "X1234567");
        Assert.assertEquals(dto.tsa(), "TSA-9");
        Assert.assertEquals(dto.birthdate(), LocalDate.of(1970, 3, 4));
        Assert.assertNotNull(dto.notes());
        // Still not an account administrator: who manages whom is structural, not operational.
        Assert.assertNull(dto.managedUsers());
    }

    @Test
    public void theSubjectThemselvesSeesEverything() {
        final PersonDto dto = dtoFor(AccessLevel.SELF);

        Assert.assertNotNull(dto.passport());
        Assert.assertNotNull(dto.address());
        Assert.assertNotNull(dto.notes());
        Assert.assertEquals(dto.managedUsers(), List.of("managed-1"));
    }

    @Test
    public void aManagerSeesTheirTravellersDocumentsButNotTheNotesWrittenAboutThem() {
        final PersonDto dto = dtoFor(AccessLevel.MANAGER);

        // Someone booking their spouse's trip needs the passport.
        Assert.assertNotNull(dto.passport());
        Assert.assertNotNull(dto.cell());
        // Staff notes are a different thing from "I manage this person's booking".
        Assert.assertNull(dto.notes(), "Managing a booking does not grant access to staff notes.");
    }

    @Test
    public void theSoftDeleteMarkerIsNotBroadcastToOrdinaryViewers() {
        // deleted is a boolean on the wire, so an un-redacted false is indistinguishable from "not shown".
        // Pin it anyway: leaking that an account was deleted is leaking that it existed.
        final Person deleted = fullPerson();
        deleted.delete();

        Assert.assertTrue(PersonMapper.INSTANCE.toDto(deleted).redactedFor(AccessLevel.SELF).deleted());
        Assert.assertFalse(PersonMapper.INSTANCE.toDto(deleted).redactedFor(AccessLevel.PEER).deleted());
    }

    private static PersonDto dtoFor(final AccessLevel level) {
        return PersonMapper.INSTANCE.toDto(SUBJECT).redactedFor(level);
    }

    private static Person fullPerson() {
        return Person.builder()
                .id(Person.Id.newInstance())
                .first("Ken")
                .last("Paulsen")
                .sex(Person.Sex.Male)
                .birthdate(LocalDate.of(1970, 3, 4))
                .cell("555-1212")
                .email("ken@example.com")
                .tsa("TSA-9")
                .address(new Address("1 Main St", "Portland", "OR", "97201"))
                .passport(new Passport("X1234567", "USA", LocalDate.of(2030, 1, 1),
                        LocalDate.of(2020, 1, 1), "Portland"))
                .notes("Prefers an aisle seat.")
                .managedUsers(List.of(Person.Id.from("managed-1")))
                .emergencyContactName("Jane Paulsen")
                .emergencyContactPhone("555-3434")
                .build();
    }
}
