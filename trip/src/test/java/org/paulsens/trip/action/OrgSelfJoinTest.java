package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * The two self-join paths (user-locked 2026-09-01): a NEW account created on an organization's own site
 * joins that org, and an existing account joins an org when one of its trips accepts their registration.
 * Everything else -- browsing, a shared host, an org-less trip, an unknown org -- joins nobody, silently.
 */
public class OrgSelfJoinTest {
    private DAO dao;

    @BeforeClass
    public void init() {
        dao = DAO.getInstance();
    }

    // ------------------------------------------------------------------ sign-up on an org site

    @Test
    public void signupOnAnOrgHostJoinsThatOrg() throws Exception {
        final Organization org = newOrg();
        final Person newcomer = savedPerson();

        final boolean joined = onSite(SiteContext.org(org.getId(), "slug", "slug.unitetrip.com"),
                () -> commandsFor(newcomer).joinSiteOrgOnSignup());

        assertTrue(joined);
        assertTrue(commandsFor(newcomer).isMember(org.getId().getValue(), newcomer.getId()));
        assertTrue(reload(newcomer).getOrgIds().contains(org.getId()), "the derived back-pointer follows");
    }

    @Test
    public void signupOnASharedOrMarketingHostJoinsNothing() throws Exception {
        final Person newcomer = savedPerson();
        assertFalse(onSite(SiteContext.shared("visitqueenofpeace.com"),
                () -> commandsFor(newcomer).joinSiteOrgOnSignup()));
        assertFalse(onSite(SiteContext.marketing("unitetrip.com"),
                () -> commandsFor(newcomer).joinSiteOrgOnSignup()));
        assertFalse(commandsFor(newcomer).joinSiteOrgOnSignup(), "off a bound request: the SHARED default");
        assertTrue(reload(newcomer).getOrgIds().isEmpty());
    }

    @Test
    public void anAnonymousCallerCannotSelfJoin() throws Exception {
        final Organization org = newOrg();
        final OrgCommands nobody = new OrgCommands(() -> new Caller(null, false, AuditActor.from(null),
                grantsNothing()));
        assertFalse(onSite(SiteContext.org(org.getId(), "slug", "slug.unitetrip.com"),
                nobody::joinSiteOrgOnSignup));
        assertEquals(nobody.getMemberCount(org), 0);
    }

    @Test
    public void anOrgHostNamingAnUnknownOrgJoinsNothing() throws Exception {
        final Person newcomer = savedPerson();
        assertFalse(onSite(SiteContext.org(Organization.Id.from("no-such-org"), "gone", "gone.unitetrip.com"),
                () -> commandsFor(newcomer).joinSiteOrgOnSignup()));
    }

    // ------------------------------------------------------------------ join on registration

    @Test
    public void registeringForAnOrgsTripJoinsTheOrg() throws IOException {
        final Organization org = newOrg();
        final Person traveler = savedPerson();
        final Trip trip = savedTrip(org.getId().getValue());

        assertTrue(commandsFor(traveler).joinOnRegistration(trip, traveler.getId()));
        assertTrue(commandsFor(traveler).isMember(org.getId().getValue(), traveler.getId()));
        assertTrue(commandsFor(traveler).joinOnRegistration(trip, traveler.getId()),
                "re-registering is an idempotent no-op");
        assertEquals(reload(traveler).getOrgIds().stream().filter(org.getId()::equals).count(), 1L);
    }

    @Test
    public void anOrgLessTripOrAMissingTravelerJoinsNothing() throws IOException {
        final Person traveler = savedPerson();
        final Trip orphan = savedTrip(null);
        assertFalse(commandsFor(traveler).joinOnRegistration(orphan, traveler.getId()));
        assertFalse(commandsFor(traveler).joinOnRegistration(null, traveler.getId()));
        final Trip owned = savedTrip(newOrg().getId().getValue());
        assertFalse(commandsFor(traveler).joinOnRegistration(owned, null));
        assertFalse(commandsFor(traveler).joinOnRegistration(savedTrip("no-such-org"), traveler.getId()),
                "a trip naming an org that does not exist joins nobody");
        assertTrue(reload(traveler).getOrgIds().isEmpty());
    }

    @Test
    public void registerPartyJoinsEachAcceptedTravelerToTheTripsOrg() throws IOException {
        final Organization org = newOrg();
        final Person traveler = savedPerson();
        final Trip trip = savedTrip(org.getId().getValue());
        final java.util.Map<String, org.paulsens.trip.model.Registration> regs = new java.util.HashMap<>();
        regs.put(traveler.getId().getValue(),
                new org.paulsens.trip.model.Registration(trip.getId(), traveler.getId()));
        final java.util.Map<String, Object> selected = java.util.Map.of(traveler.getId().getValue(), Boolean.TRUE);

        final RegistrationCommands reg = new RegistrationCommands(() -> callerFor(traveler));
        assertEquals(reg.registerParty(trip, selected, regs).size(), 1, "the registration itself succeeds");

        assertTrue(commandsFor(traveler).isMember(org.getId().getValue(), traveler.getId()),
                "an accepted registration is what makes the traveler a member");
    }

    @Test
    public void aRefusedRegistrationJoinsNobody() throws IOException {
        final Organization org = newOrg();
        final Person traveler = savedPerson();
        final Trip trip = savedTrip(org.getId().getValue());
        trip.setStartDate(LocalDateTime.now().minusDays(1));   // already started: canJoin refuses
        assertTrue(dao.saveTrip(trip));
        final java.util.Map<String, org.paulsens.trip.model.Registration> regs = new java.util.HashMap<>();
        regs.put(traveler.getId().getValue(),
                new org.paulsens.trip.model.Registration(trip.getId(), traveler.getId()));
        final java.util.Map<String, Object> selected = java.util.Map.of(traveler.getId().getValue(), Boolean.TRUE);

        final RegistrationCommands reg = new RegistrationCommands(() -> callerFor(traveler));
        assertTrue(reg.registerParty(trip, selected, regs).isEmpty());

        assertFalse(commandsFor(traveler).isMember(org.getId().getValue(), traveler.getId()));
    }

    // ------------------------------------------------------------------ helpers

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(AuditActor.system(), null, site))
                .call(body);
    }

    private Organization newOrg() {
        final OrgCommands admin = new OrgCommands(() -> new Caller(Person.Id.from("admin-" + unique()), true,
                new AuditActor("admin@test", "admin"), grantsNothing()));
        final Organization org = admin.createOrganization("Join " + unique(), null, null);
        assertNotNull(org);
        return org;
    }

    private Person savedPerson() throws IOException {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("join." + unique() + "@example.com")
                .build();
        assertTrue(dao.savePerson(person));
        return person;
    }

    private Trip savedTrip(final String orgId) throws IOException {
        final Trip trip = Trip.builder().id("join-" + RandomData.genAlpha(8)).title("Join Trip")
                .startDate(LocalDateTime.now().plusDays(30)).endDate(LocalDateTime.now().plusDays(40))
                .build();
        trip.setOrgId(orgId);
        assertTrue(dao.saveTrip(trip));
        return trip;
    }

    private Person reload(final Person person) {
        return dao.getPerson(person.getId(), Cached.NO).orElseThrow();
    }

    private static OrgCommands commandsFor(final Person person) {
        return new OrgCommands(() -> callerFor(person));
    }

    private static Caller callerFor(final Person person) {
        return new Caller(person.getId(), false,
                new AuditActor(person.getEmail(), person.getId().getValue()), grantsNothing());
    }

    private static PrivilegeCommands grantsNothing() {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(none.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return none;
    }

    private static String unique() {
        return RandomData.genAlpha(10);
    }
}
