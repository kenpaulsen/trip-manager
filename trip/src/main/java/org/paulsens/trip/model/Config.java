package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Value;

/**
 * One runtime-adjustable setting, stored in the {@code config} DynamoDB table and edited from the admin
 * Settings page. Values take effect without a redeploy -- that is the whole point of the table existing.
 *
 * <p>Deployment identity (account, VPC, endpoints, DNS) does NOT belong here: that is resolved at CDK synth
 * time from {@code deploy-config.json}, long before this table can be read. This table is for settings a human
 * changes while the site is running -- a banner message, a feature toggle, a tunable limit.
 *
 * <p>Every value is stored as a string and interpreted on read, with the caller always supplying a default (see
 * {@code ConfigCommands}). The {@link #type} is a UI and validation hint, not a storage format: it tells the
 * admin page whether to render a checkbox or a text field, and lets a bad edit be rejected at save time rather
 * than surfacing as a silently-ignored value at read time.
 */
@Value
public class Config implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** How the value should be interpreted and edited. Storage is always a string. */
    public enum Type {
        STRING, BOOLEAN, INT, LONG
    }

    /** The setting key == the DynamoDB partition key. Stable; renaming one is a new setting. */
    String name;
    String value;
    Type type;
    /** What this setting does, shown in the admin UI so a setting is never a mystery string. */
    String description;
    LocalDateTime modified;
    /** Who last changed it, for the audit trail. */
    String modifiedBy;

    @JsonCreator
    public Config(
            @JsonProperty("name") final String name,
            @JsonProperty("value") final String value,
            @JsonProperty("type") final Type type,
            @JsonProperty("description") final String description,
            @JsonProperty("modified") final LocalDateTime modified,
            @JsonProperty("modifiedBy") final String modifiedBy) {
        this.name = name;
        this.value = value;
        // Tolerate a missing type on rows written before the field existed, or by hand in the console.
        this.type = (type == null) ? Type.STRING : type;
        this.description = description;
        this.modified = modified;
        this.modifiedBy = modifiedBy;
    }
}
