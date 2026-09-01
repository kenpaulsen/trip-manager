package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.Map;
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
}
