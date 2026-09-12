package org.paulsens.trip.api.dto;

import java.time.LocalDate;

/**
 * A new family member, created and linked in one step (the only self-service way into a family). {@code sex}
 * is {@code Male} or {@code Female}; a manager needs a valid {@code email} of their own; {@code forPersonId}
 * grows THAT person's family instead of the caller's (a manager acting for a member, or a site admin).
 */
public record CreateFamilyMemberRequest(
        String first,
        String last,
        LocalDate birthdate,
        String sex,
        String email,
        Boolean manager,
        String forPersonId) {
}
