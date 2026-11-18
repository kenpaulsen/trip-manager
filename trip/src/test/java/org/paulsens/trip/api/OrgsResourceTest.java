package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link OrgsResource}: the smallest tenant-bootstrap surface. Runs against the REAL {@code OrgCommands}
 * and in-memory store (identity/authorization are never mocked in this harness), so what is pinned is the
 * whole rule: create is site-admin only, membership and admin changes need {@code canManageOrg}, and the
 * bean's refusals (duplicate name, last admin) surface as the documented statuses.
 */
public class OrgsResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("orgs-me");

    private OrgsResource resource;

    @BeforeMethod
    public void bindBeans() {
        // Real, not mocked: OrgCommands writes the derived Person.orgIds edge through PersonCommands, and
        // a mock returning false there would make every membership write report failure.
        bind(PersonCommands.class, new PersonCommands());
        bind(org.paulsens.trip.action.BrandCommands.class, new org.paulsens.trip.action.BrandCommands());
        resource = resource(new OrgsResource());
    }

    private static Person.Id somebody(final String first) {
        final PersonCommands people = new PersonCommands();
        final Person person = people.createPerson();
        person.setFirst(first);
        person.setLast("Orgtester");
        Assert.assertTrue(people.savePerson(person));
        return person.getId();
    }

    private String createOrg(final String name) {
        final Response response = resource.create(CSRF_OK, Map.of("name", name));
        assertOk(response);
        return (String) ((Map<?, ?>) response.getEntity()).get("id");
    }

    @Test
    public void directoryListsEveryOrganizationByNameForAnyMember() {
        signedInAsSiteAdmin(ME);
        final String stamp = String.valueOf(System.nanoTime());
        final String zeta = createOrg("Zeta Pilgrims " + stamp);
        final String alpha = createOrg("Alpha Tours " + stamp);
        signedInAs(somebody("Directory"));
        resource = resource(new OrgsResource());

        final List<?> all = (List<?>) resource.directory("").getEntity();
        final List<?> zetaOnly = (List<?>) resource.directory(" ZETA pilgrims " + stamp).getEntity();

        final List<String> ids = all.stream()
                .map(entry -> ((org.paulsens.trip.api.dto.OrgDirectoryEntryDto) entry).id()).toList();
        Assert.assertTrue(ids.contains(zeta) && ids.contains(alpha), "every org is listed");
        Assert.assertEquals(zetaOnly.size(), 1);
        final org.paulsens.trip.api.dto.OrgDirectoryEntryDto entry =
                (org.paulsens.trip.api.dto.OrgDirectoryEntryDto) zetaOnly.get(0);
        Assert.assertEquals(entry.id(), zeta);
        Assert.assertEquals(entry.name(), "Zeta Pilgrims " + stamp);
        Assert.assertNull(entry.siteUrl(), "no slug, no site");
    }

    @Test
    public void createIsSiteAdminOnlyAndAnswersTheNewId() {
        signedInAs(ME);
        assertError(resource.create(CSRF_OK, Map.of("name", "Nope Inc")), 403, ApiErrors.FORBIDDEN);

        // A resource memoizes its caller, so an identity change needs a fresh instance (harness rule).
        signedInAsSiteAdmin(ME);
        resource = resource(new OrgsResource());
        assertError(resource.create(null, Map.of("name", "Nope Inc")), 403, ApiErrors.CSRF);
        assertError(resource.create(CSRF_OK, Map.of()), 400, ApiErrors.VALIDATION_FAILED);

        final String name = "Org " + System.nanoTime();
        final String id = createOrg(name);
        Assert.assertNotNull(id);
        // The one expected refusal: the name is taken now.
        assertError(resource.create(CSRF_OK, Map.of("name", name)), 409, ApiErrors.CONFLICT);
    }

    /**
     * The profile's "Shared sites" checkbox over REST (the demo seed's way to stand up an organization Discover
     * must not list): the organization's admins and site admins only, stored as the page stores it.
     */
    @Test
    public void sharedSitesIsWrittenByTheOrgsAdminsOnly() {
        signedInAsSiteAdmin(ME);
        final String orgId = createOrg("Shared Sites Org " + System.nanoTime());
        final Organization.Id id = Organization.Id.from(orgId);
        final Person.Id orgAdmin = somebody("OrgAdmin");
        final Person.Id member = somebody("Member");
        assertOk(resource.setAdmin(orgId, orgAdmin.getValue(), CSRF_OK, Map.of("admin", true)));
        assertOk(resource.addMember(orgId, member.getValue(), CSRF_OK));

        Assert.assertNull(stored(id).getAllowSharedSites(), "never touched: it reads as allowed");

        Response response = resource.saveProfile(orgId, CSRF_OK, Map.of("allowsSharedSites", false));
        assertOk(response);
        Assert.assertEquals(response.getEntity(), Map.of("allowsSharedSites", false));
        Assert.assertEquals(stored(id).getAllowSharedSites(), Boolean.FALSE);
        Assert.assertFalse(stored(id).allowsSharedSites());

        assertOk(resource.saveProfile(orgId, CSRF_OK, Map.of("allowsSharedSites", true)));
        Assert.assertNull(stored(id).getAllowSharedSites(), "allowed is stored as the null default, as the page does");

        assertError(resource.saveProfile(orgId, null, Map.of("allowsSharedSites", false)), 403, ApiErrors.CSRF);
        assertError(resource.saveProfile(orgId, CSRF_OK, Map.of("allowsSharedSites", "no")), 400,
                ApiErrors.VALIDATION_FAILED);
        assertError(resource.saveProfile("no-such-org", CSRF_OK, Map.of("allowsSharedSites", false)), 404,
                ApiErrors.NOT_FOUND);
        // The field is the organization's consent to being listed across organizations, and the only one this
        // write takes: a body that does not carry it under the name every READ uses must fail, never answer 200
        // having stored nothing. "allowSharedSites" is the stored attribute's name, the likeliest misspelling.
        assertError(resource.saveProfile(orgId, CSRF_OK, Map.of("allowSharedSites", false)), 400,
                ApiErrors.VALIDATION_FAILED);
        assertError(resource.saveProfile(orgId, CSRF_OK, Map.of()), 400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.saveProfile(orgId, CSRF_OK, null), 400, ApiErrors.VALIDATION_FAILED);
        Assert.assertTrue(stored(id).allowsSharedSites(), "none of the refusals wrote anything");

        // The organization's own admin may; a plain member may not.
        signedInAs(orgAdmin);
        resource = resource(new OrgsResource());
        assertOk(resource.saveProfile(orgId, CSRF_OK, Map.of("allowsSharedSites", false)));
        Assert.assertFalse(stored(id).allowsSharedSites());
        signedInAs(member);
        resource = resource(new OrgsResource());
        assertError(resource.saveProfile(orgId, CSRF_OK, Map.of("allowsSharedSites", true)), 403,
                ApiErrors.FORBIDDEN);
        Assert.assertFalse(stored(id).allowsSharedSites(), "a member's write changed nothing");
    }

    private static Organization stored(final Organization.Id id) {
        return org.paulsens.trip.dynamo.DAO.getInstance()
                .getOrganization(id, org.paulsens.trip.cache.Cached.NO).orElseThrow();
    }

    @Test
    public void membershipAndAdminFollowTheOrgAdminRule() {
        signedInAsSiteAdmin(ME);
        final String orgId = createOrg("Org " + System.nanoTime());
        final Person.Id member = somebody("Member");
        final Person.Id admin = somebody("Admin");

        assertOk(resource.addMember(orgId, member.getValue(), CSRF_OK));
        assertError(resource.addMember(orgId, member.getValue(), null), 403, ApiErrors.CSRF);
        // A site admin passes canManageOrg by short-circuit, so an unknown org is the bean 400, not a 403.
        assertError(resource.addMember("no-such-org", member.getValue(), CSRF_OK), 400,
                ApiErrors.VALIDATION_FAILED);

        assertOk(resource.setAdmin(orgId, admin.getValue(), CSRF_OK, Map.of("admin", true)));
        assertError(resource.setAdmin(orgId, admin.getValue(), CSRF_OK, Map.of()), 400,
                ApiErrors.VALIDATION_FAILED);

        // The bean's last-admin protection surfaces as 400, not a silent success.
        assertError(resource.setAdmin(orgId, admin.getValue(), CSRF_OK, Map.of("admin", false)), 400,
                ApiErrors.VALIDATION_FAILED);

        // Removal follows the same rule and the same bean refusals: an admin cannot be removed before the role
        // is revoked, and a stranger was never a member.
        assertError(resource.removeMember(orgId, admin.getValue(), CSRF_OK), 400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.removeMember(orgId, member.getValue(), null), 403, ApiErrors.CSRF);
        assertError(resource.removeMember(orgId, somebody("Stranger").getValue(), CSRF_OK), 400,
                ApiErrors.VALIDATION_FAILED);
        // The happy path: a plain member with another organization to fall back on leaves cleanly (every person
        // must keep at least one, so a member of this org alone is refused above as "last organization").
        final String otherOrg = createOrg("Org " + System.nanoTime());
        assertOk(resource.addMember(otherOrg, member.getValue(), CSRF_OK));
        assertOk(resource.removeMember(orgId, member.getValue(), CSRF_OK));
        assertOk(resource.addMember(orgId, member.getValue(), CSRF_OK));

        // A plain signed-in user manages nothing.
        signedInAs(member);
        resource = resource(new OrgsResource());
        assertError(resource.addMember(orgId, member.getValue(), CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(resource.removeMember(orgId, member.getValue(), CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(resource.setAdmin(orgId, member.getValue(), CSRF_OK, Map.of("admin", true)), 403,
                ApiErrors.FORBIDDEN);
    }

    @Test
    public void theProducedTypeIsTheOrgsMediaType() {
        Assert.assertEquals(new OrgsResource().versionedType(), ApiMediaTypes.ORGS_V1);
    }


    @Test
    public void anOrgsOwnPaletteReachesTheClientWithItsColors() throws java.io.IOException {
        signedInAsSiteAdmin(ME);
        final String orgId = createOrg("Painted " + System.nanoTime());
        final Person.Id member = somebody("Member");
        assertOk(resource.addMember(orgId, member.getValue(), CSRF_OK));
        final org.paulsens.trip.model.Organization stored = org.paulsens.trip.dynamo.DAO.getInstance()
                .getOrganization(org.paulsens.trip.model.Organization.Id.from(orgId),
                        org.paulsens.trip.cache.Cached.NO).orElseThrow();
        stored.setCustomPalettes(List.of(new org.paulsens.trip.model.CustomPalette("p1", "Ours", "blue",
                Map.of("primary-color", "#8a2be2", "shade000", "#ffffff"), Map.of("shade000", "#101010"))));
        stored.getSettingsOverrides().put("site.theme.palette", "custom:p1");
        stored.setBrandColors(List.of(new org.paulsens.trip.model.BrandColor("gold1", "Parish gold", "#a07a2c",
                "#e0b85c")));
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(stored));

        signedInAs(member);
        final Response response = resource(new OrgsResource()).mine(false);
        assertOk(response);
        @SuppressWarnings("unchecked")
        final org.paulsens.trip.api.dto.OrgBrandingDto look =
                ((List<org.paulsens.trip.api.dto.OrgSummaryDto>) response.getEntity()).get(0).branding();
        Assert.assertEquals(look.palette(), "custom:p1");
        Assert.assertEquals(look.themeName(), "freya-custom-light");
        Assert.assertEquals(look.customColors().light().get("primary-color"), "#8a2be2");
        Assert.assertEquals(look.customColors().dark().get("shade000"), "#101010", "the dark override");
        Assert.assertEquals(look.customColors().dark().get("primary-dark-color"), "#8a2be2",
                "dark is derived: one step up from the light accent");
        Assert.assertEquals(look.brandColors(), List.of(new org.paulsens.trip.api.dto.OrgBrandingDto.BrandColorDto(
                "--brand-gold1", "#a07a2c", "#e0b85c")), "the brand colors, never their names");
        Assert.assertNotNull(look.editorColors(), "the editor colors per mode, for the apps' rich text");

        // On a stock palette the test class path can resolve (blue, light and dark stand-ins): each mode its own.
        final org.paulsens.trip.model.Organization blue = org.paulsens.trip.dynamo.DAO.getInstance()
                .getOrganization(org.paulsens.trip.model.Organization.Id.from(orgId),
                        org.paulsens.trip.cache.Cached.NO).orElseThrow();
        blue.getSettingsOverrides().put("site.theme.palette", "blue");
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(blue));
        @SuppressWarnings("unchecked")
        final org.paulsens.trip.api.dto.OrgBrandingDto blueLook = ((List<org.paulsens.trip.api.dto.OrgSummaryDto>)
                resource(new OrgsResource()).mine(false).getEntity()).get(0).branding();
        Assert.assertEquals(blueLook.editorColors().light().get("--text-color"), "#69707a");
        Assert.assertEquals(blueLook.editorColors().dark().get("--text-color"), "#dee2e6", "the dark map is DARK");
    }

    @Test
    public void mineListsMembershipsWithStandingSiteAndLook() throws java.io.IOException {
        signedInAsSiteAdmin(ME);
        final String withSite = createOrg("Acme " + System.nanoTime());
        final String withoutSite = createOrg("Beta " + System.nanoTime());
        final Person.Id member = somebody("Member");
        final Person.Id admin = somebody("Admin");
        assertOk(resource.addMember(withSite, member.getValue(), CSRF_OK));
        assertOk(resource.addMember(withoutSite, member.getValue(), CSRF_OK));
        assertOk(resource.setAdmin(withSite, admin.getValue(), CSRF_OK, Map.of("admin", true)));
        // Slug and branding are written straight through the DAO: a slug is a site-admin grant with its own
        // page flow, and the look is what the Appearance page stores -- neither is this resource's job.
        final org.paulsens.trip.model.Organization stored = org.paulsens.trip.dynamo.DAO.getInstance()
                .getOrganization(org.paulsens.trip.model.Organization.Id.from(withSite),
                        org.paulsens.trip.cache.Cached.NO).orElseThrow();
        stored.setSlug("acme" + System.nanoTime() % 1000);
        stored.getSettingsOverrides().put("site.theme.palette", "blue");
        stored.getSettingsOverrides().put("site.theme.dark", "true");
        stored.getSettingsOverrides().put("site.logo.url", "https://cdn.example/logo.png");
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(stored));
        Mockito.when(request.getServerName()).thenReturn("localhost");
        Mockito.when(request.getServerPort()).thenReturn(8080);

        signedInAs(member);
        final OrgsResource asMember = resource(new OrgsResource());
        final Response response = asMember.mine(false);
        assertOk(response);
        @SuppressWarnings("unchecked")
        final List<org.paulsens.trip.api.dto.OrgSummaryDto> mine =
                (List<org.paulsens.trip.api.dto.OrgSummaryDto>) response.getEntity();
        Assert.assertEquals(mine.size(), 2, "both memberships, and nothing else");
        final org.paulsens.trip.api.dto.OrgSummaryDto acme =
                mine.stream().filter(org -> org.id().equals(withSite)).findFirst().orElseThrow();
        Assert.assertTrue(acme.isMember());
        Assert.assertFalse(acme.isAdmin());
        Assert.assertEquals(acme.siteUrl(), "http://" + stored.getSlug() + ".localhost:8080",
                "the site URL follows the host the request came in on, without a trailing slash");
        Assert.assertEquals(acme.branding().palette(), "blue");
        Assert.assertTrue(acme.branding().dark());
        Assert.assertEquals(acme.branding().themeName(), "freya-blue-dark");
        Assert.assertEquals(acme.branding().logoUrl(), "https://cdn.example/logo.png");
        final org.paulsens.trip.api.dto.OrgSummaryDto beta =
                mine.stream().filter(org -> org.id().equals(withoutSite)).findFirst().orElseThrow();
        Assert.assertNull(beta.slug());
        Assert.assertNull(beta.siteUrl(), "no slug, no site");
        Assert.assertNull(beta.branding().palette(), "unbranded: the client draws its default");

        assertError(asMember.mine(true), 403, ApiErrors.FORBIDDEN);

        signedInAs(admin);
        final OrgsResource asAdmin = resource(new OrgsResource());
        final Response adminView = asAdmin.site(withSite);
        assertOk(adminView);
        Assert.assertTrue(((org.paulsens.trip.api.dto.OrgSummaryDto) adminView.getEntity()).isAdmin());
        assertError(asAdmin.site("no-such-org"), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void aSiteAdminMayListEveryOrganization() {
        signedInAsSiteAdmin(ME);
        final Person.Id admin = somebody("Site");
        signedInAsSiteAdmin(admin);
        final OrgsResource asSiteAdmin = resource(new OrgsResource());
        createOrg("Gamma " + System.nanoTime());
        final Response response = asSiteAdmin.mine(true);
        assertOk(response);
        Assert.assertFalse(((List<?>) response.getEntity()).isEmpty());
        // Not a member of anything: the plain listing is empty, the all listing is not.
        Assert.assertTrue(((List<?>) asSiteAdmin.mine(false).getEntity()).isEmpty());
    }
}
