package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
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

        // A plain signed-in user manages nothing.
        signedInAs(member);
        resource = resource(new OrgsResource());
        assertError(resource.addMember(orgId, member.getValue(), CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(resource.setAdmin(orgId, member.getValue(), CSRF_OK, Map.of("admin", true)), 403,
                ApiErrors.FORBIDDEN);
    }

    @Test
    public void theProducedTypeIsTheOrgsMediaType() {
        Assert.assertEquals(new OrgsResource().versionedType(), ApiMediaTypes.ORGS_V1);
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
