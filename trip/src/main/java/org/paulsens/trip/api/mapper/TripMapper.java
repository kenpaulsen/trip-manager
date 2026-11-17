package org.paulsens.trip.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.paulsens.trip.action.RichTextHtml;
import org.paulsens.trip.api.dto.TripDto;
import org.paulsens.trip.api.dto.TripEventDto;
import org.paulsens.trip.api.dto.TripPageOptionsDto;
import org.paulsens.trip.api.dto.TripPaymentConfigDto;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.model.TripPageOptions;
import org.paulsens.trip.model.TripPaymentConfig;

/**
 * {@code Trip} and {@code TripEvent} to their wire shapes.
 *
 * <p>The two viewer-relative fields on an event -- {@code participating} and {@code privNote} -- are NOT mapped
 * here. They depend on who is asking, which is a per-request fact and not something a mapper knows; the resource
 * fills them in. Leaving them to MapStruct would either produce nulls or, worse, tempt somebody into mapping the
 * whole {@code privNotes} map across.
 *
 * <p>An event's shared {@code notes} ARE mapped here, and always cleaned ({@code RichTextHtml.cleanEventNote},
 * medjugorje#156): every client renders them as HTML, a row stored before notes were cleaned may hold anything,
 * and cleaning in the one mapper means no resource can send an event without it.
 */
@Mapper(uses = ValueMappers.class, imports = RichTextHtml.class)
public interface TripMapper {

    TripMapper INSTANCE = Mappers.getMapper(TripMapper.class);

    @Mapping(target = "people", source = "people")
    @Mapping(target = "detailsUrl", ignore = true)
    @Mapping(target = "pictures", ignore = true)
    @Mapping(target = "tagLabels", ignore = true)
    @Mapping(target = "fallbackPicture", ignore = true)
    TripDto toDto(Trip trip);

    @Mapping(target = "participating", ignore = true)
    @Mapping(target = "privNote", ignore = true)
    @Mapping(target = "notes", expression = "java(RichTextHtml.cleanEventNote(event.getNotes()))")
    TripEventDto toDto(TripEvent event);

    /** The trip's own rung of the payment ladder; the enum crosses as its name. */
    TripPaymentConfigDto toDto(TripPaymentConfig config);

    /** A conference page's own look (docs/conference.md); null fields follow the organization. */
    TripPageOptionsDto toDto(TripPageOptions options);
}
