package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.Instant;
import lombok.Value;
import org.paulsens.trip.model.Person;

/**
 * One shareable invite link for a trip chat: anyone with an account who presents it becomes a guest member.
 *
 * <p>Selector/validator split, same shape as {@code RememberToken}: the row stores the selector and
 * SHA-256(validator), the URL carries {@code selector.validator}, so a read of the table (or a backup) cannot
 * mint working links. Unlike remember-me the validator never rotates — a link is multi-use by design, shared
 * in a group text or shown as a QR code, and dies by expiry or revocation instead.
 *
 * <p>{@code expires} is epoch seconds and doubles as the DynamoDB TTL attribute; redemption re-checks it
 * because TTL deletion can lag by up to 48 hours.
 */
@Value
public class ChatInvite implements Serializable {

    ChatChannel.Id channelId;
    String selector;
    String validatorHash;
    Person.Id createdBy;
    Instant created;
    long expires;
    /** Best-effort redemption count (ADD increment); informational only, never authorization. */
    long uses;

    @JsonCreator
    public ChatInvite(
            @JsonProperty("channelId") final ChatChannel.Id channelId,
            @JsonProperty("selector") final String selector,
            @JsonProperty("validatorHash") final String validatorHash,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final Instant created,
            @JsonProperty("expires") final long expires,
            @JsonProperty("uses") final long uses) {
        this.channelId = channelId;
        this.selector = selector;
        this.validatorHash = validatorHash;
        this.createdBy = createdBy;
        this.created = created;
        this.expires = expires;
        this.uses = uses;
    }

    @JsonIgnore
    public boolean isExpired(final Instant now) {
        return now != null && now.getEpochSecond() >= expires;
    }

    /** {@link #expires} as an Instant, for display (the admin table renders it via EL). */
    @JsonIgnore
    public Instant getExpiresAt() {
        return Instant.ofEpochSecond(expires);
    }
}
