package org.paulsens.trip.api.dto;

import java.time.LocalDate;
import java.util.List;
import org.paulsens.trip.api.AccessLevel;
import org.paulsens.trip.api.mapper.PersonMapper;
import org.paulsens.trip.model.Address;
import org.paulsens.trip.model.Passport;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PrivacySettings;
import org.paulsens.trip.model.PrivacySettings.Visibility;
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
 *
 * <p>Since privacy settings, what a PEER or TRIP_VIEWER sees of email/cell/address is the SUBJECT's choice, so
 * the matrix is (level x privacy) for those fields. Fixed policy is unchanged by any knob: passport, TSA,
 * birthdate, emergency contacts and notes never reach a peer or viewer; names and sex always do.
 */
public class PersonDtoRedactionTest {

    @Test
    public void aCoTravellerWithDefaultsSeesNameSexAndSharedContactInfo() {
        final PersonDto dto = dtoFor(fullPerson(), AccessLevel.PEER);

        // The point of the roster: you can see who else is on the trip.
        Assert.assertEquals(dto.first(), "Ken");
        Assert.assertEquals(dto.last(), "Paulsen");
        Assert.assertEquals(dto.sex(), "Male", "Sex is always visible to signed-in users (user-locked 2026-08-12)");
        // Default knobs: email, cell, and city are shared with signed-in users; street stays private.
        Assert.assertEquals(dto.email(), "ken@example.com");
        Assert.assertEquals(dto.cell(), "555-1212");
        Assert.assertEquals(dto.address().city(), "Portland");
        Assert.assertEquals(dto.address().state(), "OR");
        Assert.assertNull(dto.address().street(), "Street defaults private.");
        Assert.assertNull(dto.address().zip(), "Zip follows street.");
        // Fixed policy -- no knob can share these.
        Assert.assertNull(dto.passport(), "A co-traveller must never receive passport details.");
        Assert.assertNull(dto.tsa(), "A co-traveller must never receive a TSA number.");
        Assert.assertNull(dto.birthdate(), "A birthdate is identity data, not roster data.");
        Assert.assertNull(dto.notes());
        Assert.assertNull(dto.emergencyContactName());
        Assert.assertNull(dto.emergencyContactPhone());
        Assert.assertNull(dto.managedUsers());
        Assert.assertNull(dto.privacy(), "The choices themselves are private too.");
    }

    @Test
    public void aCoTravellerSeesNothingButNameAndSexWhenEverythingIsPrivate() {
        final PersonDto dto = dtoFor(allPrivatePerson(), AccessLevel.PEER);

        Assert.assertEquals(dto.first(), "Ken");
        Assert.assertEquals(dto.sex(), "Male");
        Assert.assertNull(dto.email());
        Assert.assertNull(dto.cell());
        Assert.assertNull(dto.address(), "A private city removes the address entirely.");
    }

    @Test
    public void anOptedInStreetReachesACoTravellerButNeverWithoutTheCity() {
        final Person shared = fullPerson();
        shared.getPrivacy().setStreet(Visibility.LOGGED_IN);
        final PersonDto full = dtoFor(shared, AccessLevel.PEER);
        Assert.assertEquals(full.address().street(), "1 Main St");
        Assert.assertEquals(full.address().zip(), "97201");

        // The stored street choice must not survive a private city -- a street alone still locates the person.
        shared.getPrivacy().setCity(Visibility.PRIVATE);
        final PersonDto hidden = dtoFor(shared, AccessLevel.PEER);
        Assert.assertNull(hidden.address(), "Street visibility must be forced off by a private city.");
    }

    @Test
    public void aTripViewerFollowsTheSubjectsPrivacyChoicesLikeAnyPeer() {
        final PersonDto dto = dtoFor(fullPerson(), AccessLevel.TRIP_VIEWER);
        // Default knobs still open these to any signed-in user, viewer included.
        Assert.assertEquals(dto.cell(), "555-1212");
        Assert.assertEquals(dto.email(), "ken@example.com");
        // But roster access no longer overrides a privacy choice...
        Assert.assertNull(dtoFor(allPrivatePerson(), AccessLevel.TRIP_VIEWER).cell(),
                "tripView must not override a private phone number.");
        // ...and the always-private fields are gone regardless of knobs.
        Assert.assertNull(dto.emergencyContactName(), "Emergency contacts are always private now.");
        Assert.assertNull(dto.emergencyContactPhone());
        Assert.assertNull(dto.passport(), "tripView is roster access; it does not carry passport data.");
        Assert.assertNull(dto.tsa());
        Assert.assertNull(dto.notes(), "Notes are written ABOUT travellers, not for everyone with roster access.");
        Assert.assertNull(dto.privacy());
    }

    /**
     * Admin masking is a UI affordance (user-locked: UI-only, no server enforcement), so the wire carries the
     * real values to a trip admin even when the subject chose private.
     */
    @Test
    public void aTripAdminGetsTheFullDossierEvenWhenTheSubjectChosePrivate() {
        final PersonDto dto = dtoFor(allPrivatePerson(), AccessLevel.TRIP_ADMIN);

        Assert.assertNotNull(dto.passport(), "A trip manager cannot book travel without this.");
        Assert.assertEquals(dto.passport().number(), "X1234567");
        Assert.assertEquals(dto.tsa(), "TSA-9");
        Assert.assertEquals(dto.birthdate(), LocalDate.of(1970, 3, 4));
        Assert.assertEquals(dto.cell(), "555-1212");
        Assert.assertEquals(dto.email(), "ken@example.com");
        Assert.assertEquals(dto.address().street(), "1 Main St");
        Assert.assertNotNull(dto.notes());
        Assert.assertNotNull(dto.emergencyContactName());
        // Still not an account administrator: who manages whom is structural, not operational.
        Assert.assertNull(dto.managedUsers());
        Assert.assertNull(dto.privacy(), "The knobs are account structure; a trip role does not see them.");
    }

    @Test
    public void theSubjectThemselvesSeesEverythingIncludingTheirChoices() {
        final PersonDto dto = dtoFor(fullPerson(), AccessLevel.SELF);

        Assert.assertNotNull(dto.passport());
        Assert.assertNotNull(dto.address());
        Assert.assertNotNull(dto.notes());
        Assert.assertEquals(dto.managedUsers(), List.of("managed-1"));
        Assert.assertNotNull(dto.privacy());
        Assert.assertEquals(dto.privacy().street(), "PRIVATE");
        Assert.assertEquals(dto.privacy().email(), "LOGGED_IN");
    }

    @Test
    public void aManagerSeesTheirTravellersDocumentsButNotTheNotesWrittenAboutThem() {
        final PersonDto dto = dtoFor(allPrivatePerson(), AccessLevel.MANAGER);

        // Someone booking their spouse's trip needs the passport -- and privacy never hides from family.
        Assert.assertNotNull(dto.passport());
        Assert.assertNotNull(dto.cell());
        Assert.assertNotNull(dto.privacy(), "A manager maintains the settings for members without a login.");
        // Staff notes are a different thing from "I manage this person's booking".
        Assert.assertNull(dto.notes(), "Managing a booking does not grant access to staff notes.");
    }

    @Test
    public void theSoftDeleteMarkerIsNotBroadcastToOrdinaryViewers() {
        // deleted is a boolean on the wire, so an un-redacted false is indistinguishable from "not shown".
        // Pin it anyway: leaking that an account was deleted is leaking that it existed.
        final Person deleted = fullPerson();
        deleted.delete();

        Assert.assertTrue(dtoFor(deleted, AccessLevel.SELF).deleted());
        Assert.assertFalse(dtoFor(deleted, AccessLevel.PEER).deleted());
    }

    @Test
    public void theMapperPassesANullPrivacyThroughAsNull() {
        Assert.assertNull(PersonMapper.INSTANCE.toDto((PrivacySettings) null));
    }

    /** Hand-built DTOs (older tests, external callers) carry no privacy object -- they must fail closed to defaults. */
    @Test
    public void aDtoWithoutPrivacyRedactsAsTheDefaults() {
        final PersonDto bare = new PersonDto(null, null, "Ken", null, "Paulsen", null, "Male", null, "555-1212",
                "ken@example.com", null, new AddressDto("1 Main St", "Portland", "OR", "97201"), null, null,
                null, null, null, false, null);
        final PersonDto dto = bare.redactedFor(AccessLevel.PEER);
        Assert.assertEquals(dto.email(), "ken@example.com");
        Assert.assertNull(dto.address().street(), "No privacy object must mean the DEFAULTS, not everything.");
    }

    private static PersonDto dtoFor(final Person person, final AccessLevel level) {
        return PersonMapper.INSTANCE.toDto(person).redactedFor(level);
    }

    private static Person allPrivatePerson() {
        final Person person = fullPerson();
        person.setPrivacy(new PrivacySettings(
                Visibility.PRIVATE, Visibility.PRIVATE, Visibility.PRIVATE, Visibility.PRIVATE));
        return person;
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
                .emergencyName("Jane Paulsen")
                .emergencyPhone("555-3434")
                .build();
    }
}
