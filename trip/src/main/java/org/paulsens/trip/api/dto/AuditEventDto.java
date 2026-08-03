package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** One audit record. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEventDto(
        Instant timestamp,
        String action,
        String outcome,
        String actorEmail,
        String actorId,
        String targetType,
        String targetEmail,
        String targetId,
        String message) {
}
