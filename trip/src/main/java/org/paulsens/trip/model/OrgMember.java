package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * One person's membership in one {@link Organization} -- a row in the {@code org_members} table (PK orgId,
 * SK personId), which is the source of truth for org membership. The derived reverse edge is
 * {@link Person#getOrgIds()}, synced surgically by {@code OrgCommands} the way family membership syncs
 * {@code managedUsers}.
 *
 * <p>There is deliberately no role field: org admins live on {@link Organization#getAdminIds()} (few, and
 * needed alongside the org for every authorization check), so a membership row only answers "is this person in
 * this org, and since when".
 */
@Data
public final class OrgMember implements Serializable {
    private Organization.Id orgId;
    private Person.Id personId;
    private LocalDateTime joined;

    @Builder
    @JsonCreator
    public OrgMember(
            @JsonProperty("orgId") final Organization.Id orgId,
            @JsonProperty("personId") final Person.Id personId,
            @JsonProperty("joined") final LocalDateTime joined) {
        this.orgId = orgId;
        this.personId = personId;
        this.joined = joined;
    }

    public OrgMember() {
        this(null, null, null);
    }
}
