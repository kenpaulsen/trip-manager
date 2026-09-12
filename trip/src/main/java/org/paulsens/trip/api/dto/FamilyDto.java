package org.paulsens.trip.api.dto;

import java.util.List;

/**
 * A household as the family page sees it. {@code id} is null when the subject has no family yet -- the
 * common case -- and then {@code members} is just the subject; creating the first member forms the family.
 * {@code canManage} is the caller's reach over THIS family (one of its managers, or a site admin), which is
 * what gates the add/remove/manager affordances.
 */
public record FamilyDto(
        String id,
        long version,
        List<String> managerIds,
        List<String> memberIds,
        int maxMembers,
        boolean atLimit,
        boolean canManage,
        List<FamilyMemberDto> members) {
}
