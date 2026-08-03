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
        boolean deleted) {

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
        return new PersonDto(
                id,
                nickname,
                first,
                middle,
                last,
                preferredName,
                level.seesTravelDocuments() ? sex : null,
                level.seesTravelDocuments() ? birthdate : null,
                level.seesContactDetail() ? cell : null,
                level.seesContactDetail() ? email : null,
                level.seesTravelDocuments() ? tsa : null,
                level.seesContactDetail() ? address : null,
                level.seesTravelDocuments() ? passport : null,
                level.seesNotes() ? notes : null,
                level.seesAccountStructure() ? managedUsers : null,
                level.seesContactDetail() ? emergencyContactName : null,
                level.seesContactDetail() ? emergencyContactPhone : null,
                level.seesAccountStructure() && deleted);
    }
}
