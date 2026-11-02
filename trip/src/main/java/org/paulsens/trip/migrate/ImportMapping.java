package org.paulsens.trip.migrate;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * The owner's decisions for one import (docs/migrations/conference-import.md), kept in the PRIVATE repo beside the
 * account's scripts: which organization receives everything, who is skipped or merged by hand, and how the legacy
 * pricing labels read. Every collection is optional and never null.
 *
 * @param targetOrgId            the organization that owns the imported people, trips and transactions
 * @param skipPeople             old person ids left out entirely, with their registrations and transactions
 * @param mergeIntoEmail         old person id -> the address of the EXISTING person they are (an account whose
 *                               address changed since; the name check is waived for these)
 * @param nameMatchOverrides     old person ids whose address matches an existing person although the name is
 *                               written differently ("FR. JOHN MARSHALL" for "John Marshall"): merged anyway
 * @param sameAs                 old person id -> the old person id it duplicates; both become one person
 * @param familyDecisions        old person id -> the old person id to link them under, or {@code none}: the
 *                               owner's answer for an address-less person with several candidate families
 * @param familyWindowHours      how close two same-surname registrations on a trip must be to suggest a family
 * @param registrationTypeLabels a legacy {@code _regType} value -> the registration type it is shown as
 * @param roleMap                a legacy trip-role base -> today's base ({@code tripCheckin} -> {@code checkinStaff})
 * @param admissionQuestion      the question a trip's legacy admission options become
 * @param transactionTrips       {@code oldUserId,txId} -> the trip a ledger row belongs to, for a row the export
 *                               holds no trip binding for (a repair the owner approved)
 */
public record ImportMapping(String targetOrgId, Set<String> skipPeople, Map<String, String> mergeIntoEmail,
        Set<String> nameMatchOverrides, Map<String, String> sameAs, Map<String, String> familyDecisions,
        int familyWindowHours, Map<String, String> registrationTypeLabels, Map<String, String> roleMap,
        String admissionQuestion, Map<String, String> transactionTrips) {

    /** A {@link #familyDecisions} value: leave this person out of every family. */
    public static final String NO_FAMILY = "none";

    /** Every collection optional and never null, however the mapping was built. */
    public ImportMapping {
        skipPeople = skipPeople == null ? Set.of() : Set.copyOf(skipPeople);
        mergeIntoEmail = mergeIntoEmail == null ? Map.of() : Map.copyOf(mergeIntoEmail);
        nameMatchOverrides = nameMatchOverrides == null ? Set.of() : Set.copyOf(nameMatchOverrides);
        sameAs = sameAs == null ? Map.of() : Map.copyOf(sameAs);
        familyDecisions = familyDecisions == null ? Map.of() : Map.copyOf(familyDecisions);
        registrationTypeLabels = registrationTypeLabels == null ? Map.of() : Map.copyOf(registrationTypeLabels);
        roleMap = roleMap == null ? Map.of() : Map.copyOf(roleMap);
        admissionQuestion = admissionQuestion == null || admissionQuestion.isBlank() ? "Admission" : admissionQuestion;
        transactionTrips = transactionTrips == null ? Map.of() : Map.copyOf(transactionTrips);
    }

    @JsonCreator
    public ImportMapping(
            @JsonProperty("targetOrgId") final String targetOrgId,
            @JsonProperty("skipPeople") final Set<String> skipPeople,
            @JsonProperty("mergeIntoEmail") final Map<String, String> mergeIntoEmail,
            @JsonProperty("nameMatchOverrides") final Set<String> nameMatchOverrides,
            @JsonProperty("sameAs") final Map<String, String> sameAs,
            @JsonProperty("familyDecisions") final Map<String, String> familyDecisions,
            @JsonProperty("familyWindowHours") final Integer familyWindowHours,
            @JsonProperty("registrationTypeLabels") final Map<String, String> registrationTypeLabels,
            @JsonProperty("roleMap") final Map<String, String> roleMap,
            @JsonProperty("admissionQuestion") final String admissionQuestion,
            @JsonProperty("transactionTrips") final Map<String, String> transactionTrips) {
        this(targetOrgId, skipPeople, mergeIntoEmail, nameMatchOverrides, sameAs, familyDecisions,
                familyWindowHours == null ? 2 : familyWindowHours.intValue(), registrationTypeLabels, roleMap,
                admissionQuestion, transactionTrips);
    }

    /** Reads the mapping file. */
    public static ImportMapping read(final Path file, final ObjectMapper mapper) throws IOException {
        return mapper.readValue(file.toFile(), ImportMapping.class);
    }

    /** The base a legacy role becomes: mapped, else unchanged. */
    public String roleFor(final String legacyBase) {
        return roleMap.getOrDefault(legacyBase, legacyBase);
    }
}
