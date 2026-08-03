package org.paulsens.trip.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.paulsens.trip.api.dto.RegistrationDto;
import org.paulsens.trip.model.Registration;

/**
 * {@code Registration} to its wire shape.
 *
 * <p>{@code status} maps to the enum's {@code name()}, not its {@code toString()}. The model overrides
 * {@code toString()} to return a human description ("Pending approval" and the like) for the JSF pages, and a
 * client keying behaviour off a display string breaks the day somebody rewords it.
 */
@Mapper(uses = ValueMappers.class)
public interface RegistrationMapper {

    RegistrationMapper INSTANCE = Mappers.getMapper(RegistrationMapper.class);

    RegistrationDto toDto(Registration registration);
}
