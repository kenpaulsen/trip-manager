package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * The record that one traveler's document was agreed to: who signed ({@code signerId}, always the signed-in
 * person -- identity never changes, a family manager signs AS themselves), for whom ({@code personId}, the
 * traveler), when, and exactly which text ({@code waiverId}, {@code version}, {@code sha256}). Both attestations
 * are stored even though a row only exists when both were given, so the record says so by itself.
 *
 * <p>Write-once in {@code waiver_acceptances} (PK tripId, SK {@link #sortKey}): never updated, never deleted --
 * not by trip delete, not by account deletion. Names and the signer's email are captured at signing so the
 * record still reads correctly after a profile changes or a person is erased.
 */
@Value
public class WaiverAcceptance implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** How the agreement was given. */
    public enum Client {
        WEB, IOS, ANDROID, API
    }

    String tripId;
    Person.Id personId;
    String waiverId;
    int version;
    String title;
    String sha256;
    Person.Id signerId;
    String signerName;
    String signerEmail;
    String subjectName;
    boolean agreed;
    boolean authorityAttested;
    Instant acceptedAt;
    Client client;
    String ip;
    String userAgent;
    String orgId;

    @Builder
    @JsonCreator
    public WaiverAcceptance(
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("personId") final Person.Id personId,
            @JsonProperty("waiverId") final String waiverId,
            @JsonProperty("version") final int version,
            @JsonProperty("title") final String title,
            @JsonProperty("sha256") final String sha256,
            @JsonProperty("signerId") final Person.Id signerId,
            @JsonProperty("signerName") final String signerName,
            @JsonProperty("signerEmail") final String signerEmail,
            @JsonProperty("subjectName") final String subjectName,
            @JsonProperty("agreed") final boolean agreed,
            @JsonProperty("authorityAttested") final boolean authorityAttested,
            @JsonProperty("acceptedAt") final Instant acceptedAt,
            @JsonProperty("client") final Client client,
            @JsonProperty("ip") final String ip,
            @JsonProperty("userAgent") final String userAgent,
            @JsonProperty("orgId") final String orgId) {
        this.tripId = tripId;
        this.personId = personId;
        this.waiverId = waiverId;
        this.version = version;
        this.title = title;
        this.sha256 = sha256;
        this.signerId = signerId;
        this.signerName = signerName;
        this.signerEmail = signerEmail;
        this.subjectName = subjectName;
        this.agreed = agreed;
        this.authorityAttested = authorityAttested;
        this.acceptedAt = acceptedAt;
        this.client = (client == null) ? Client.WEB : client;
        this.ip = ip;
        this.userAgent = (userAgent == null || userAgent.length() <= 512) ? userAgent : userAgent.substring(0, 512);
        this.orgId = orgId;
    }

    /** The sort key: one row per traveler, document and version. */
    @JsonIgnore
    public String getSortKey() {
        return sortKey(personId, waiverId, version);
    }

    /** Whether someone other than the traveler signed (a family manager, or a site admin). */
    @JsonIgnore
    public boolean isOnBehalf() {
        return signerId != null && !signerId.equals(personId);
    }

    public static String sortKey(final Person.Id personId, final String waiverId, final int version) {
        // Zero-padded so a partition read sorts the versions numerically.
        return personId.getValue() + "#" + waiverId + "#" + String.format("%05d", version);
    }
}
