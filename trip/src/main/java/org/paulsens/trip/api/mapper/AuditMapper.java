package org.paulsens.trip.api.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.paulsens.trip.api.dto.AuditEventDto;
import org.paulsens.trip.model.AuditEvent;

/**
 * {@code AuditEvent} to its wire shape.
 *
 * <p>Every field crosses. An audit record is already the redacted view of whatever it describes -- the message
 * is composed for a human reading the trail -- so there is nothing here to withhold from a caller who is
 * entitled to read the trail at all. Entitlement is the whole control, and it is the resource's job.
 */
@Mapper
public interface AuditMapper {

    AuditMapper INSTANCE = Mappers.getMapper(AuditMapper.class);

    AuditEventDto toDto(AuditEvent event);
}
