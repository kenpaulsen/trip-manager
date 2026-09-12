package org.paulsens.trip.api.dto;

import java.util.List;

/**
 * One member of a family as the family page shows them: the name, whether they manage the family, the
 * profile fields still missing, why a manager cannot delete them (null when they can, absent for callers who
 * cannot manage), and their record redacted for the caller like any person read.
 */
public record FamilyMemberDto(
        String id,
        String preferredName,
        String first,
        String last,
        boolean manager,
        List<String> missingProfileFields,
        String deleteBlockReason,
        PersonDto person) {
}
