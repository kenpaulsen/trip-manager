package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * People, organizations and callers for the tests of the cross-tenant rules (who may change a sign-in, link a
 * family, remove a member, search a picker). Everything is saved through the real DAO and commands, with REAL
 * privilege checks: these rules are about which rows exist, so a stubbed check would prove nothing.
 */
final class TenancyFixtures {

    private TenancyFixtures() {
    }

    static String unique() {
        return RandomData.genAlpha(10).toLowerCase(Locale.ROOT);
    }

    /**
     * A saved person whose address is NOT a local-mode persona: an {@code admin*} or {@code user*} address is
     * answered a login by {@code FakeData} whatever the store holds, which would make "has no login" and "is
     * not a site admin" untestable.
     */
    static Person savedPerson(final String tag) {
        final Person person = Person.builder().first(tag).last("T" + unique())
                .email("tf." + tag.toLowerCase(Locale.ROOT) + "." + unique() + "@example.com").build();
        return saved(person);
    }

    static Person saved(final Person person) {
        try {
            assertTrue(DAO.getInstance().savePerson(person));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return person;
    }

    static Person reload(final Person person) {
        return DAO.getInstance().getPerson(person.getId(), Cached.NO).orElseThrow();
    }

    /** A stored login for this person with the given role ("user" or "admin"). */
    static void giveLogin(final Person person, final String role) {
        assertTrue(DAO.getInstance().saveCreds(
                new Creds(person.getEmail(), person.getId(), role, "password01", null)));
    }

    static Caller siteAdminCaller() {
        return new Caller(Person.Id.from("tf-admin-" + unique()), true, new AuditActor("admin@test", "admin"),
                new PrivilegeCommands());
    }

    /** A stored person signed in as a site administrator, for beans that resolve the caller's own row. */
    static Caller siteAdminAs(final Person person) {
        return new Caller(person.getId(), true, new AuditActor(person.getEmail(), person.getId().getValue()),
                new PrivilegeCommands());
    }

    /** The person as an ordinary signed-in caller, holding whatever privilege rows the store has for them. */
    static Caller callerFor(final Person person) {
        return new Caller(person.getId(), false, new AuditActor(person.getEmail(), person.getId().getValue()),
                new PrivilegeCommands());
    }

    static OrgCommands asSiteAdmin() {
        return new OrgCommands(TenancyFixtures::siteAdminCaller);
    }

    static OrgCommands orgsFor(final Person person) {
        return new OrgCommands(() -> callerFor(person));
    }

    static Organization org(final String tag, final Person... members) {
        final Organization org = asSiteAdmin().createOrganization(tag + " " + unique(), null, null);
        assertNotNull(org);
        for (final Person member : members) {
            assertTrue(asSiteAdmin().addMember(org.getId().getValue(), member.getId()));
        }
        return org;
    }

    static void addMember(final Organization org, final Person person) {
        assertTrue(asSiteAdmin().addMember(org.getId().getValue(), person.getId()));
    }

    static void makeOrgAdmin(final Organization org, final Person person) {
        assertTrue(asSiteAdmin().setOrgAdmin(org.getId().getValue(), person.getId(), true));
    }

    static void grantPeopleAdmin(final Organization org, final Person person) {
        assertTrue(asSiteAdmin().grantOrgPrivilege(org.getId().getValue(), person.getId(),
                PrivilegeCommands.PEOPLE_ADMIN));
    }

    /** A saved trip owned by the org (a canonical UUID id: privilege scopes refuse anything else). */
    static Trip tripOwnedBy(final Organization owner, final Person... roster) {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Trip " + unique())
                .people(new java.util.ArrayList<>(List.of(roster).stream().map(Person::getId).toList())).build();
        trip.setOrgId(owner.getId().getValue());
        try {
            assertTrue(DAO.getInstance().saveTrip(trip));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return trip;
    }
}
