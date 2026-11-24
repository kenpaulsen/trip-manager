package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.TestPeople;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * People, organizations and callers for the tests of the cross-tenant rules (who may change a sign-in, link a
 * family, remove a member, search a picker). Everything is saved through the real DAO and commands, with REAL
 * privilege checks: these rules are about which rows exist, so a stubbed check would prove nothing.
 */
public final class TenancyFixtures {

    private TenancyFixtures() {
    }

    public static String unique() {
        return TestPeople.unique();
    }

    /**
     * A saved person whose address is NOT a local-mode persona: an {@code admin*} or {@code user*} address is
     * answered a login by {@code FakeData} whatever the store holds, which would make "has no login" and "is
     * not a site admin" untestable.
     */
    public static Person savedPerson(final String tag) {
        final Person person = Person.builder().first(tag).last("T" + unique())
                .email("tf." + tag.toLowerCase(Locale.ROOT) + "." + unique() + "@example.com").build();
        return saved(person);
    }

    public static Person saved(final Person person) {
        return TestPeople.save(person);
    }

    public static Person reload(final Person person) {
        return DAO.getInstance().getPerson(person.getId(), Cached.NO).orElseThrow();
    }

    /** A stored login for this person with the given role ("user" or "admin"). */
    public static void giveLogin(final Person person, final String role) {
        assertTrue(DAO.getInstance().saveCreds(
                new Creds(person.getEmail(), person.getId(), role, "password01", null)));
    }

    public static Caller siteAdminCaller() {
        return new Caller(Person.Id.from("tf-admin-" + unique()), true, new AuditActor("admin@test", "admin"),
                new PrivilegeCommands());
    }

    /** A stored person signed in as a site administrator, for beans that resolve the caller's own row. */
    public static Caller siteAdminAs(final Person person) {
        return new Caller(person.getId(), true, new AuditActor(person.getEmail(), person.getId().getValue()),
                new PrivilegeCommands());
    }

    /** The person as an ordinary signed-in caller, holding whatever privilege rows the store has for them. */
    public static Caller callerFor(final Person person) {
        return new Caller(person.getId(), false, new AuditActor(person.getEmail(), person.getId().getValue()),
                new PrivilegeCommands());
    }

    public static OrgCommands asSiteAdmin() {
        return new OrgCommands(TenancyFixtures::siteAdminCaller);
    }

    public static OrgCommands orgsFor(final Person person) {
        return new OrgCommands(() -> callerFor(person));
    }

    public static Organization org(final String tag, final Person... members) {
        final Organization org = asSiteAdmin().createOrganization(tag + " " + unique(), null, null);
        assertNotNull(org);
        for (final Person member : members) {
            assertTrue(asSiteAdmin().addMember(org.getId().getValue(), member.getId()));
        }
        return org;
    }

    /**
     * A new organization whose one admin is this person, re-read so the answer carries the admin row. No
     * {@code addMember} first: {@code setOrgAdmin} alone is the sequence the org-admin tests are written against.
     */
    public static Organization orgWithAdmin(final Person orgAdmin) {
        final Organization org = asSiteAdmin().createOrganization("Org " + unique(), null, null);
        assertNotNull(org);
        assertTrue(asSiteAdmin().setOrgAdmin(org.getId().getValue(), orgAdmin.getId(), true));
        return reread(org);
    }

    /** The organization as the store holds it now, past the cache. */
    public static Organization reread(final Organization org) {
        return DAO.getInstance().getOrganization(org.getId(), Cached.NO).orElseThrow();
    }

    public static void addMember(final Organization org, final Person person) {
        assertTrue(asSiteAdmin().addMember(org.getId().getValue(), person.getId()));
    }

    public static void makeOrgAdmin(final Organization org, final Person person) {
        assertTrue(asSiteAdmin().setOrgAdmin(org.getId().getValue(), person.getId(), true));
    }

    public static void grantPeopleAdmin(final Organization org, final Person person) {
        assertTrue(asSiteAdmin().grantOrgPrivilege(org.getId().getValue(), person.getId(),
                PrivilegeCommands.PEOPLE_ADMIN));
    }

    /**
     * The id of a saved trip that can carry ledger rows (it exists and has an organization, the seeded CFPW id),
     * for a test whose subject is the ROW: every new row must name such a trip
     * ({@code TransactionsCommands.tripProblem}). One per call, so tests never share books.
     */
    public static String ledgerTripId() {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Ledger " + unique()).build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        try {
            assertTrue(DAO.getInstance().saveTrip(trip));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return trip.getId();
    }

    /** A saved trip owned by the org (a canonical UUID id: privilege scopes refuse anything else). */
    public static Trip tripOwnedBy(final Organization owner, final Person... roster) {
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
