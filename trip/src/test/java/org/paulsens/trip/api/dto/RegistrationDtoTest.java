package org.paulsens.trip.api.dto;

import java.time.LocalDateTime;
import java.util.Map;
import org.paulsens.trip.api.mapper.RegistrationMapper;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * What a registration puts on the wire.
 *
 * <p>{@code options} is where every free-text answer a trip asked for ends up -- dietary requirements, medical
 * notes, mobility needs. The roster endpoint strips it, and the reason is scale: one person's answers reaching
 * the wrong person is a mistake, whereas a list endpoint that forgets to strip them discloses the medical notes
 * of everyone on the trip in a single response.
 */
public class RegistrationDtoTest {

    @Test
    public void theRosterViewCarriesNoFreeTextAnswers() {
        final RegistrationDto dto = dto().withoutOptions();

        Assert.assertNull(dto.options(), "A roster listing must never carry travellers' answers.");
        // Everything a roster actually needs survives.
        Assert.assertEquals(dto.userId(), "person-1");
        Assert.assertEquals(dto.tripId(), "trip-1");
        Assert.assertEquals(dto.status(), "PENDING");
    }

    @Test
    public void theSinglePersonViewKeepsTheAnswers() {
        // Stripping here too would make the endpoint useless -- this is the view a traveller edits.
        Assert.assertEquals(dto().options(), Map.of("diet", "vegetarian"));
    }

    @Test
    public void statusGoesOutAsTheEnumNameNotTheHumanDescription() {
        // Registration.toString() is overridden to a display string for the JSF pages ("Pending approval").
        // A client keying off that breaks the day somebody rewords it, so the wire carries name().
        final RegistrationDto dto = dto();

        Assert.assertEquals(dto.status(), Registration.Status.PENDING.name());
        Assert.assertNotEquals(dto.status(), Registration.Status.PENDING.toString(),
                "If these ever match, the display string and the wire contract have been conflated.");
    }

    @Test
    public void whatTheApiEmitsIsNotWhatWithStatusStringParses() {
        // The trap this pins: Registration.withStatusString is fromDescription, which matches "Pending", while
        // the wire carries "PENDING". A client that GETs a registration and PUTs it back unchanged -- the most
        // ordinary request there is -- would parse its own status as null and wipe it. RegistrationsResource
        // therefore parses by enum NAME, and this asserts the mismatch that makes that necessary is real.
        final String onTheWire = dto().status();

        Assert.assertNull(Registration.Status.fromDescription(onTheWire),
                "If this ever returns non-null, re-check whether RegistrationsResource.parseStatus is still needed.");
        Assert.assertEquals(Registration.Status.valueOf(onTheWire), Registration.Status.PENDING);
    }

    private static RegistrationDto dto() {
        return RegistrationMapper.INSTANCE.toDto(new Registration(
                "trip-1",
                Person.Id.from("person-1"),
                LocalDateTime.of(2026, 1, 1, 9, 0),
                Registration.Status.PENDING,
                Map.of("diet", "vegetarian")));
    }
}
