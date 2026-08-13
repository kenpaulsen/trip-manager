package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import org.paulsens.trip.api.AccessLevel;

/**
 * A person as the API presents them.
 *
 * <p>Separate from {@code Person} on purpose. The model is the persistence shape: adding a field to it is a
 * routine change that would, with direct serialization, immediately publish that field to every mobile client and
 * every co-traveller. Here the wire shape is something you have to opt into.
 *
 * <p>Null fields are omitted rather than sent as {@code null}, so a redacted response does not advertise the
 * existence of what it withheld.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonDto(
        String id,
        String nickname,
        String first,
        String middle,
        String last,
        String preferredName,
        String sex,
        LocalDate birthdate,
        String cell,
        String email,
        String tsa,
        AddressDto address,
        PassportDto passport,
        String notes,
        List<String> managedUsers,
        String emergencyContactName,
        String emergencyContactPhone,
        boolean deleted,
        PrivacyDto privacy) {

    /**
     * This record narrowed to what {@code level} is allowed to see.
     *
     * <p>Redaction happens here, on a built DTO, rather than in the MapStruct mapper. MapStruct decides shape at
     * compile time from types; this is a per-request authorization decision about a specific pair of people, and
     * pretending it is a mapping concern is how a passport number ends up in a roster response.
     *
     * <p>Written as an explicit whole-record rebuild, not a mutation. Every field is named exactly once, so a
     * field added to the record fails to compile here until somebody has decided who may see it -- which is the
     * only mechanism that reliably survives the next person adding a column.
     */
    public PersonDto redactedFor(final AccessLevel level) {
        // A DTO built without privacy (older clients, tests) must redact as if the subject kept the defaults.
        final PrivacyDto chosen = (privacy == null) ? PrivacyDto.DEFAULTS : privacy;
        return new PersonDto(
                id,
                nickname,
                first,
                middle,
                last,
                preferredName,
                sex,
                level.seesTravelDocuments() ? birthdate : null,
                (level.seesContactDetail() || chosen.cellVisible()) ? cell : null,
                (level.seesContactDetail() || chosen.emailVisible()) ? email : null,
                level.seesTravelDocuments() ? tsa : null,
                addressFor(level, chosen),
                level.seesTravelDocuments() ? passport : null,
                level.seesNotes() ? notes : null,
                level.seesAccountStructure() ? managedUsers : null,
                level.seesContactDetail() ? emergencyContactName : null,
                level.seesContactDetail() ? emergencyContactPhone : null,
                level.seesAccountStructure() && deleted,
                level.seesAccountStructure() ? privacy : null);
    }

    /**
     * The address a viewer without {@code seesContactDetail} gets: nothing when city is private, city+state when
     * only the city is shared, the full address when the subject opted the street in too.
     */
    private AddressDto addressFor(final AccessLevel level, final PrivacyDto chosen) {
        if (level.seesContactDetail()) {
            return address;
        }
        if (address == null || !chosen.cityVisible()) {
            return null;
        }
        return chosen.streetVisible() ? address : new AddressDto(null, address.city(), address.state(), null);
    }
}
