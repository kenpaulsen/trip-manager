package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

/**
 * An organization: the tenancy boundary of the platform. Trips belong to an organization, people are members of
 * at least one, and org-owned configuration (payment processors first, theming and content later) is visible
 * only inside it.
 *
 * <p>Membership itself is NOT stored on this row: a roster can reach the whole user base, which would push a
 * single item toward the DynamoDB size cap and serialize every join on one optimistic-version row. Member rows
 * live in the {@code org_members} table (source of truth), with {@link Person#getOrgIds()} as the derived
 * reverse edge -- the same row-is-truth/back-pointer split {@link Family} uses. Only {@code adminIds} lives
 * here: admins are few, and every authorization check needs them in hand with the org.
 *
 * <p>Ids are canonical UUIDs on purpose: {@link Privilege} scope suffixes must round-trip through a UUID-anchored
 * parse, so a non-UUID org id would foreclose org-scoped privileges later.
 *
 * <p>{@code version} implements optimistic concurrency exactly like {@link Family}: saves are conditioned on the
 * stored version matching the loaded one, and a lost race surfaces to the caller rather than retrying silently.
 */
@Data
public final class Organization implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private Id id;
    private String name;
    private String abbreviation;
    private String contactEmail;
    private List<Person.Id> adminIds;
    private Person.Id createdBy;
    private LocalDateTime created;
    private long version;
    /**
     * Org-level payment defaults: the middle rung of the trip &rarr; org &rarr; site settings ladder, the
     * same {@link TripPaymentConfig} type the trip-level override uses. Like {@code Person.familyId},
     * deliberately NOT a constructor parameter (setter-populated) so the 8-arg creator's callers stay
     * untouched; never null via the lazy getter.
     */
    private TripPaymentConfig paymentDefaults;
    /**
     * The SES-verified sending domains this org's From addresses may use -- the site-admin allow-list
     * behind every From composer on the org's pages (mail merge, trip payment settings). {@code null} OR
     * empty means "never restricted": the composer offers every domain SES has verified. Unlike
     * {@link #grantablePrivileges}, empty is NOT "nothing allowed" -- an org that may send from no domain
     * cannot send at all, which is a footgun rather than a useful state, so restriction is expressed only
     * by listing the domains that ARE allowed. Setter-populated, outside the 8-arg creator like
     * {@link #paymentDefaults}.
     */
    private List<String> mailDomains;
    /**
     * The org admins' preferred domain among {@link #mailDomains} -- what a From composer preselects. Not
     * an authorization control (the allow-list is), just the default choice, which is why an ORG admin may
     * set it while only a site admin may edit the list itself. Ignored when it is not currently allowed.
     */
    private String defaultMailDomain;

    /**
     * The org's subdomain label on the org-site base domain: slug {@code acme} makes
     * {@code acme.unitetrip.com} serve this org's site. {@code null} (or blank) means the org has no
     * subdomain site (the shared-site service tier). Site-admin only -- a slug is a public namespace grant,
     * like {@link #mailDomains} -- and validated by {@code OrgCommands} (sole writer): lowercase DNS-label
     * grammar, unique across orgs, never a reserved platform name. Setter-populated, outside the 8-arg
     * creator like {@link #paymentDefaults}. Assigning or clearing it is an ONLINE tier change: the
     * {@code SiteIndex} refreshes on save, wildcard DNS and the wildcard certificate already cover every
     * label, and no deploy or restart is involved.
     */
    private String slug;

    /**
     * The subdomain label of the PLATFORM's own organization -- the one whose site is
     * {@code www.{base}} and the base domain's apex (the product's marketing site). Exactly one org may
     * hold it (slugs are unique), a site admin assigns it, and {@code SiteContext.isMarketing()} is true
     * on its site so the few marketing-only rules (no auto-join, no trip menus) still apply.
     */
    public static final String PLATFORM_SLUG = "www";

    /**
     * When the org's default home page was seeded (see {@code OrgPageBootstrap}); null until the org first
     * gets a subdomain. Recorded on the org row rather than inferred from the page's content so that seeding
     * happens exactly ONCE: an org that later deletes every section has an empty page by choice, and must
     * not find the starter sections back the next time an admin opens its settings. Setter-populated,
     * outside the 8-arg creator like {@link #paymentDefaults}.
     */
    private LocalDateTime homePageSeededAt;

    /**
     * The org side of the double gate on SHARED sites: whether this org's public content (trips, albums)
     * may appear on a shared site's sections at all. {@code null} = allow (the default; the site side's own
     * pick list is already opt-in), {@code false} = never, whatever a shared site's curation says. Org-admin
     * controlled -- it is the org's content. Has no effect on the org's OWN site. Setter-populated, outside
     * the 8-arg creator like {@link #paymentDefaults}.
     */
    private Boolean allowSharedSites;

    /**
     * The privilege base names this org may grant (site-admin controlled allow-list, bounding both the
     * trip-scoped roles on the trip editor and the org-scoped grants on the org People page). {@code null}
     * means "never restricted" == everything allowed, so existing rows need no migration and restriction is
     * always an explicit act; an EMPTY list means "nothing grantable". That distinction is why -- unlike
     * {@link #paymentDefaults} -- the getter must NOT lazy-init. Setter-populated, deliberately outside the
     * 8-arg creator for the same reason {@code paymentDefaults} is.
     */
    private List<String> grantablePrivileges;

    /**
     * The org's own values for the settings marked {@link SettingDef#isOrgOverridable() org-overridable}, keyed
     * by setting name -- the org rung of the org &rarr; site &rarr; default ladder ({@code ConfigCommands}
     * resolves it on the org's host; {@code OrgCommands.effectiveSetting} for background code). Only
     * org-overridable keys are ever stored ({@code OrgCommands.saveOrgSettings} refuses the rest), and a
     * blank or absent value means "inherit". Kept on the org row rather than re-keying the {@code config}
     * table: a few short strings per org, read on every request of its site alongside the org itself.
     * Setter-populated, outside the 8-arg creator like {@link #paymentDefaults}; never null via the getter.
     */
    private Map<String, String> settingsOverrides;

    @Builder
    @JsonCreator
    public Organization(
            @JsonProperty("id") final Id id,
            @JsonProperty("name") final String name,
            @JsonProperty("abbreviation") final String abbreviation,
            @JsonProperty("contactEmail") final String contactEmail,
            @JsonProperty("adminIds") final List<Person.Id> adminIds,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("version") final long version) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.name = trim(name);
        this.abbreviation = trim(abbreviation);
        this.contactEmail = trim(contactEmail);
        this.adminIds = (adminIds == null) ? new ArrayList<>() : new ArrayList<>(adminIds);
        this.createdBy = createdBy;
        this.created = created;
        this.version = version;
    }

    public Organization() {
        this(null, null, null, null, null, null, null, 0L);
    }

    public TripPaymentConfig getPaymentDefaults() {
        if (paymentDefaults == null) {
            paymentDefaults = new TripPaymentConfig();
        }
        return paymentDefaults;
    }

    public Map<String, String> getSettingsOverrides() {
        if (settingsOverrides == null) {
            settingsOverrides = new LinkedHashMap<>();
        }
        return settingsOverrides;
    }

    /** The org's non-blank override for a setting name, or null when it inherits (never a blank string). */
    @JsonIgnore
    public String settingOverride(final String name) {
        final String value = (settingsOverrides == null || name == null) ? null : settingsOverrides.get(name);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    @JsonIgnore
    public boolean isAdmin(final Person.Id personId) {
        return personId != null && adminIds.contains(personId);
    }

    /** True for the platform's own organization ({@link #PLATFORM_SLUG}). */
    @JsonIgnore
    public boolean isPlatform() {
        return PLATFORM_SLUG.equals(slug);
    }

    /** Whether shared sites may show this org's content -- see {@link #allowSharedSites}; null reads as allow. */
    @JsonIgnore
    public boolean allowsSharedSites() {
        return !Boolean.FALSE.equals(allowSharedSites);
    }

    /** True when this org may grant the given privilege base name -- see {@link #grantablePrivileges}. */
    @JsonIgnore
    public boolean mayGrant(final String baseName) {
        return grantablePrivileges == null || grantablePrivileges.contains(baseName);
    }

    /** The short label when one was set, otherwise the full name -- what compact UI (chips, notes) shows. */
    @JsonIgnore
    public String getShortName() {
        return (abbreviation == null || abbreviation.isBlank()) ? name : abbreviation;
    }

    private static String trim(final String str) {
        return (str == null) ? null : str.trim();
    }

    @Value
    public static class Id implements Serializable, Comparable<Id> {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        @JsonValue
        String value;

        public static Id from(final String id) {
            return new Id(id);
        }

        public static Id newInstance() {
            return new Id(UUID.randomUUID().toString());
        }

        @Override
        public int compareTo(final Id o) {
            return value.compareTo(o.getValue());
        }
    }
}
