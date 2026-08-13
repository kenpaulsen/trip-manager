package org.paulsens.trip.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.paulsens.trip.api.dto.AddressDto;
import org.paulsens.trip.api.dto.PassportDto;
import org.paulsens.trip.api.dto.PersonDto;
import org.paulsens.trip.api.dto.PrivacyDto;
import org.paulsens.trip.model.Address;
import org.paulsens.trip.model.Passport;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PrivacySettings;

/**
 * {@code Person} to its wire shape. Produces the FULL record; narrowing is
 * {@link PersonDto#redactedFor(org.paulsens.trip.api.AccessLevel)}'s job, never this mapper's.
 *
 * <p>{@code componentModel} is left at the default because resources are HK2-managed JAX-RS classes rather than
 * CDI beans, so there is nothing to inject a mapper into -- they reach it through {@link #INSTANCE}.
 */
@Mapper(uses = ValueMappers.class)
public interface PersonMapper {

    PersonMapper INSTANCE = Mappers.getMapper(PersonMapper.class);

    /**
     * {@code deleted} is a {@code LocalDateTime} on the model -- when it was soft-deleted -- and a boolean on the
     * wire, because a client only ever asks whether the record is live.
     */
    @Mapping(target = "deleted", expression = "java(person.getDeleted() != null)")
    PersonDto toDto(Person person);

    AddressDto toDto(Address address);

    PassportDto toDto(Passport passport);

    /**
     * Hand-written rather than generated so the wire form stays the enum NAME by construction -- MapStruct would
     * also use {@code name()}, but an implicit enum-to-string mapping is exactly the kind of thing a later
     * {@code toString()} "improvement" on the enum silently changes.
     */
    default PrivacyDto toDto(final PrivacySettings settings) {
        if (settings == null) {
            return null;
        }
        return new PrivacyDto(settings.getEmail().name(), settings.getCell().name(),
                settings.getCity().name(), settings.getStreet().name());
    }
}
