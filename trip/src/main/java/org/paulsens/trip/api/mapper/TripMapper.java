package org.paulsens.trip.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.paulsens.trip.api.dto.TripDto;
import org.paulsens.trip.api.dto.TripEventDto;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;

/**
 * {@code Trip} and {@code TripEvent} to their wire shapes.
 *
 * <p>The two viewer-relative fields on an event -- {@code participating} and {@code privNote} -- are NOT mapped
 * here. They depend on who is asking, which is a per-request fact and not something a mapper knows; the resource
 * fills them in. Leaving them to MapStruct would either produce nulls or, worse, tempt somebody into mapping the
 * whole {@code privNotes} map across.
 */
@Mapper(uses = ValueMappers.class)
public interface TripMapper {

    TripMapper INSTANCE = Mappers.getMapper(TripMapper.class);

    @Mapping(target = "people", source = "people")
    TripDto toDto(Trip trip);

    @Mapping(target = "participating", ignore = true)
    @Mapping(target = "privNote", ignore = true)
    TripEventDto toDto(TripEvent event);
}
