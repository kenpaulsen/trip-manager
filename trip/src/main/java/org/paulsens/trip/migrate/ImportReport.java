package org.paulsens.trip.migrate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * What an apply wrote (docs/migrations/conference-import.md), saved after every phase so a stopped run still says
 * exactly what exists: the rollback script is generated from it. Before-images are the rows as they were read just
 * before the write; {@code null} means the row did not exist.
 */
@Data
public class ImportReport {
    private String startedAt;
    private String finishedAt;
    private String targetOrgId;
    private List<String> createdPeople = new ArrayList<>();
    private List<Before> mergedPeople = new ArrayList<>();
    private List<String> memberships = new ArrayList<>();
    private List<Before> families = new ArrayList<>();
    private List<Before> familyMembers = new ArrayList<>();
    private List<String> trips = new ArrayList<>();
    private List<Key> registrations = new ArrayList<>();
    private List<Key> transactions = new ArrayList<>();
    private List<Before> privileges = new ArrayList<>();
    /** Trips already present that the legacy-pricing patch rewrote, with the row as it was (key: trip id). */
    private List<Before> patchedTrips = new ArrayList<>();
    /** Registrations the patch gave a breakdown, with the row as it was (key: {@code tripId/userId}). */
    private List<Before> patchedRegistrations = new ArrayList<>();
    private List<String> skipped = new ArrayList<>();
    private List<String> failures = new ArrayList<>();

    /** A row's key and its before-image ({@code null}: created by the import). */
    public record Before(String key, JsonNode before) {
    }

    /** A two-part key: (tripId, userId) for a registration; (userId, txId) for a transaction, with its trip. */
    public record Key(String hash, String range, String tripId) {
    }
}
