package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.BrandColors;
import org.paulsens.trip.action.BrandCommands;
import org.paulsens.trip.action.EditorColors;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.OrgSharedSites;
import org.paulsens.trip.action.SiteCommands;
import org.paulsens.trip.api.dto.OrgBrandingDto;
import org.paulsens.trip.api.dto.OrgDirectoryEntryDto;
import org.paulsens.trip.api.dto.OrgSummaryDto;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;

/**
 * Organizations — the tenancy boundary, so this resource is deliberately the SMALLEST surface that lets an
 * administrator stand a tenant up: create, add a member, set an org admin. Everything else about an org
 * (profile edits, privileges, processors, removal) stays on the org-admin pages, whose gates and confirm
 * flows are the product surface for those decisions. Every mutation delegates its authorization to
 * {@code OrgCommands} — create is site-admin only, the rest require {@code canManageOrg} — so the page
 * rule and the REST rule are one rule. The one profile field with a write here is "Shared sites"
 * ({@link #saveProfile}), because the demo seed has no browser to tick it with.
 *
 * <p>The two reads exist for the native clients, whose tenancy is a HOST: the server scopes trips, money
 * and chat by the request host, so a client has to learn which org subdomains its person belongs to
 * before it can fan out to them. {@code mine} answers that (memberships, or every org for a site admin
 * asking with {@code all=true}); {@code {orgId}/site} answers the same shape for one org, for a trip
 * whose org the caller is not a member of (site admins, trip managers).
 */
@Path("orgs")
@TripApi
public class OrgsResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.ORGS_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /** The caller's organizations, each with its site and look; {@code all=true} is site-admin only. */
    @GET
    @Path("mine")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response mine(@QueryParam("all") @DefaultValue("false") final boolean all) {
        final Person me = findPerson(personId());
        if (me == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.shared.signedInPersonNotFound"));
        }
        final OrgCommands orgs = new OrgCommands(this::caller);
        if (all && !privileges().isSiteAdmin()) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.orgs.listNeedsSiteAdmin"));
        }
        final List<Organization> found = all ? orgs.getManageableOrgs() : orgs.membershipsOf(me);
        return ok(found.stream()
                .sorted(Comparator.comparing(Organization::getName, String.CASE_INSENSITIVE_ORDER))
                .map(org -> summary(org, me))
                .toList());
    }

    /**
     * Every organization by name, for the native app's "choose your organization" list. Any signed-in
     * caller: an organization's name, abbreviation and site are its public face (each has a public site),
     * and nothing about its people or trips travels with them. {@code q} narrows by name or abbreviation.
     */
    @GET
    @Path("directory")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response directory(@QueryParam("q") @DefaultValue("") final String query) {
        final String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        return ok(new OrgCommands(this::caller).getOrganizations().stream()
                .filter(org -> needle.isEmpty() || matches(org, needle))
                .map(this::directoryEntry)
                .toList());
    }

    private static boolean matches(final Organization org, final String needle) {
        return (org.getName() != null && org.getName().toLowerCase(java.util.Locale.ROOT).contains(needle))
                || (org.getAbbreviation() != null
                        && org.getAbbreviation().toLowerCase(java.util.Locale.ROOT).contains(needle));
    }

    private OrgDirectoryEntryDto directoryEntry(final Organization org) {
        final String slug = org.getSlug();
        return new OrgDirectoryEntryDto(org.getId().getValue(), org.getName(), org.getAbbreviation(), slug,
                slug == null ? null : SiteCommands.orgSiteRoot(org, request));
    }

    /**
     * One organization's site and look, for any signed-in caller: everything here is what the org's own
     * public site already shows to anonymous visitors (the name, the slug, the contact card, the logo).
     */
    @GET
    @Path("{orgId}/site")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response site(@PathParam("orgId") final String orgId) {
        final Organization org = new OrgCommands(this::caller).findOrganization(orgId);
        if (org == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.shared.noSuchOrganization"));
        }
        return ok(summary(org, findPerson(personId())));
    }

    private OrgSummaryDto summary(final Organization org, final Person me) {
        final String slug = org.getSlug();
        final String siteUrl = slug == null ? null : SiteCommands.orgSiteRoot(org, request);
        final boolean member = me != null && me.getOrgIds().contains(org.getId());
        final boolean admin = (me != null && org.isAdmin(me.getId())) || privileges().isSiteAdmin();
        final BrandCommands brand = Beans.get(BrandCommands.class);
        final BrandCommands.OrgLook look = brand.lookOf(org);
        return new OrgSummaryDto(org.getId().getValue(), org.getName(), org.getAbbreviation(), slug, siteUrl,
                member, admin, org.allowsSharedSites(), org.registerTarget().name(), org.getContactEmail(),
                OrgBrandingDto.of(look, brand.themeNameOf(look), customColors(brand, look),
                        BrandColors.colorsOf(org), editorColors(org)));
    }

    /** The editor theme colors in both modes, for a client that renders rich text (docs/api-contract.md). */
    private static OrgBrandingDto.ModeColors editorColors(final Organization org) {
        return new OrgBrandingDto.ModeColors(EditorColors.modeValues(org, false), EditorColors.modeValues(org, true));
    }

    /** The look's own palette colors in both modes, or null for a stock palette or none. */
    private static OrgBrandingDto.CustomColors customColors(final BrandCommands brand,
            final BrandCommands.OrgLook look) {
        return look.customPalette() == null ? null
                : new OrgBrandingDto.CustomColors(brand.customColorsOf(look, false), brand.customColorsOf(look, true));
    }

    /** Creates an organization (site admin only). 409 covers a duplicate name, the one expected refusal. */
    @POST
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response create(
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        if (!privileges().isSiteAdmin()) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.orgs.createNeedsSiteAdmin"));
        }
        final String name = body == null ? null : string(body.get("name"));
        if (name == null || name.isBlank()) {
            return error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.orgs.nameRequired"));
        }
        final Organization org = new OrgCommands(this::caller).createOrganization(name,
                body == null ? null : string(body.get("abbreviation")),
                body == null ? null : string(body.get("contactEmail")));
        if (org == null) {
            return error(409, ApiErrors.CONFLICT,
                    Text.get("api.orgs.nameTaken"));
        }
        return ok(Map.of("id", org.getId().getValue(), "name", org.getName()));
    }

    /**
     * The one profile field a script needs to write: {@code {"allowsSharedSites": true|false}}, the profile
     * page's "Shared sites" checkbox, behind the page's own gate (this organization's admins, and site admins;
     * {@code OrgCommands.saveOrganization} enforces it again). Added 2026-11 so the demo seed can stand up an
     * organization that Discover must NOT list (medjugorje#155); every other profile field stays on the page.
     * The field is spelled as every read spells it ({@code OrgSummaryDto.allowsSharedSites}), and it is REQUIRED:
     * it is the organization's consent to being listed across organizations, so a body that names it wrongly
     * must fail loudly rather than answer 200 and store nothing. Anything but a boolean is refused.
     */
    @PUT
    @Path("{orgId}/profile")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response saveProfile(
            @PathParam("orgId") final String orgId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final OrgCommands orgs = new OrgCommands(this::caller);
        final Organization org = orgs.findOrganization(orgId);
        if (org == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.shared.noSuchOrganization"));
        }
        if (!orgs.canManageOrg(orgId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.orgs.profileNeedsOrgAdmin"));
        }
        final Object flag = body == null ? null : body.get("allowsSharedSites");
        if (!(flag instanceof Boolean allow)) {
            return error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.orgs.allowsSharedSitesRequired"));
        }
        final OrgSharedSites.Outcome outcome = OrgSharedSites.save(orgs, orgId, allow);
        if (outcome == OrgSharedSites.Outcome.NOT_FOUND) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.shared.noSuchOrganization"));
        }
        if (outcome == OrgSharedSites.Outcome.FAILED) {
            return error(500, ApiErrors.STORE_FAILED, Text.get("api.orgs.notSaved"));
        }
        // The value just written, not a re-read: a read straight after a write can still see the old row.
        return ok(Map.of("allowsSharedSites", allow));
    }

    /** Adds an EXISTING person to the org — an access change, so org admins only (the bean enforces). */
    @POST
    @Path("{orgId}/members/{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response addMember(
            @PathParam("orgId") final String orgId,
            @PathParam("personId") final String personId,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final OrgCommands orgs = new OrgCommands(this::caller);
        if (!orgs.canManageOrg(orgId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.orgs.addMembersNeedsOrgAdmin"));
        }
        if (!orgs.addMember(orgId, Person.Id.from(personId))) {
            return error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.orgs.unknownOrgOrPerson"));
        }
        return ok(Map.of("added", true));
    }

    /**
     * Removes a member. The bean refuses an org admin (revoke first), a person on one of the org's trips, a
     * person's last organization, and a non-member; every refusal maps to 400 here. Added 2026-10-16 for the
     * App Store review seed: a reviewer who answered the app's first-run organization chooser joined a client
     * organization, and nothing but the admin page could undo it.
     */
    @DELETE
    @Path("{orgId}/members/{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response removeMember(
            @PathParam("orgId") final String orgId,
            @PathParam("personId") final String personId,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final OrgCommands orgs = new OrgCommands(this::caller);
        if (!orgs.canManageOrg(orgId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.orgs.removeMembersNeedsOrgAdmin"));
        }
        if (!orgs.removeMember(orgId, Person.Id.from(personId))) {
            return error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.orgs.memberNotRemoved"));
        }
        return ok(Map.of("removed", true));
    }

    /**
     * Grants or revokes org-admin ({@code {"admin": true|false}}). Granting auto-adds membership; the bean
     * refuses to revoke the org's last admin, which maps to 400 here rather than being re-decided.
     */
    @PUT
    @Path("{orgId}/admins/{personId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response setAdmin(
            @PathParam("orgId") final String orgId,
            @PathParam("personId") final String personId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        final Object flag = body == null ? null : body.get("admin");
        if (!(flag instanceof Boolean admin)) {
            return error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.orgs.adminFlagRequired"));
        }
        final OrgCommands orgs = new OrgCommands(this::caller);
        if (!orgs.canManageOrg(orgId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.orgs.adminsNeedOrgAdmin"));
        }
        if (!orgs.setOrgAdmin(orgId, Person.Id.from(personId), admin)) {
            return error(400, ApiErrors.VALIDATION_FAILED,
                    Text.get("api.orgs.adminChangeRefused"));
        }
        return ok(Map.of("admin", admin));
    }

    private static String string(final Object value) {
        return value == null ? null : value.toString();
    }
}
