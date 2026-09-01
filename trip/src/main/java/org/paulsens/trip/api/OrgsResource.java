package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;

/**
 * Organizations — the tenancy boundary, so this resource is deliberately the SMALLEST surface that lets an
 * administrator stand a tenant up: create, add a member, set an org admin. Everything else about an org
 * (profile edits, privileges, processors, removal) stays on the org-admin pages, whose gates and confirm
 * flows are the product surface for those decisions. Every mutation delegates its authorization to
 * {@code OrgCommands} — create is site-admin only, the rest require {@code canManageOrg} — so the page
 * rule and the REST rule are one rule.
 */
@Path("orgs")
@TripApi
public class OrgsResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.ORGS_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /** Creates an organization (site admin only). 409 covers a duplicate name, the one expected refusal. */
    @POST
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response create(
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!privileges().isSiteAdmin()) {
            return error(403, ApiErrors.FORBIDDEN, "Only a site administrator can create an organization.");
        }
        final String name = body == null ? null : string(body.get("name"));
        if (name == null || name.isBlank()) {
            return error(400, ApiErrors.VALIDATION_FAILED, "name is required.");
        }
        final Organization org = new OrgCommands(this::caller).createOrganization(name,
                body == null ? null : string(body.get("abbreviation")),
                body == null ? null : string(body.get("contactEmail")));
        if (org == null) {
            return error(409, ApiErrors.CONFLICT,
                    "An organization with that name already exists (or the save failed).");
        }
        return ok(Map.of("id", org.getId().getValue(), "name", org.getName()));
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
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final OrgCommands orgs = new OrgCommands(this::caller);
        if (!orgs.canManageOrg(orgId)) {
            return error(403, ApiErrors.FORBIDDEN, "Only this organization's admins can add members.");
        }
        if (!orgs.addMember(orgId, Person.Id.from(personId))) {
            return error(400, ApiErrors.VALIDATION_FAILED, "Unknown organization or person.");
        }
        return ok(Map.of("added", true));
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
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Object flag = body == null ? null : body.get("admin");
        if (!(flag instanceof Boolean admin)) {
            return error(400, ApiErrors.VALIDATION_FAILED, "admin (true/false) is required.");
        }
        final OrgCommands orgs = new OrgCommands(this::caller);
        if (!orgs.canManageOrg(orgId)) {
            return error(403, ApiErrors.FORBIDDEN, "Only this organization's admins can change its admins.");
        }
        if (!orgs.setOrgAdmin(orgId, Person.Id.from(personId), admin)) {
            return error(400, ApiErrors.VALIDATION_FAILED,
                    "Refused (unknown person, or this is the organization's last admin).");
        }
        return ok(Map.of("admin", admin));
    }

    private static String string(final Object value) {
        return value == null ? null : value.toString();
    }
}
