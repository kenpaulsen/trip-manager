package org.paulsens.trip.action;

import jakarta.faces.context.FacesContext;
import java.util.Map;
import java.util.function.Supplier;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.CredentialsDAO;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * THE rule for changing how somebody ELSE signs in ({@link OrgCommands#canChangeSignInOf}), and the paths that
 * ask it: a profile save that changes the address, the login re-key, removing a login, deleting an account.
 *
 * <p>The attack it exists for: an organization can add ANY account as a member by its address, and its admin can
 * grant themselves {@code peopleAdmin}. That reach is enough to edit the profile (accepted), and it used to be
 * enough to move the person's sign-in to an address the admin controls and sign in as them.
 */
public class SignInChangeRuleTest {

    /** The caller: a people admin of organization A, and nothing else anywhere. */
    private static final class Scene {
        private final Person deputy = TenancyFixtures.savedPerson("Deputy");
        private final Organization orgA = TenancyFixtures.org("SignA", deputy);
        private final Organization orgB = TenancyFixtures.org("SignB");

        Scene() {
            TenancyFixtures.grantPeopleAdmin(orgA, deputy);
        }

        OrgCommands asDeputy() {
            return TenancyFixtures.orgsFor(deputy);
        }

        Supplier<OrgCommands> deputyReach() {
            return this::asDeputy;
        }

        Person memberOfA(final String tag) {
            final Person member = TenancyFixtures.savedPerson(tag);
            TenancyFixtures.addMember(orgA, member);
            return member;
        }
    }

    // ------------------------------------------------------------------ the rule

    @Test
    public void aPeopleAdminMayChangeTheSignInOfSomeoneWhoIsOnlyInTheirOrg() {
        final Scene scene = new Scene();
        final Person onlyInA = scene.memberOfA("OnlyA");
        TenancyFixtures.giveLogin(onlyInA, "user");
        assertTrue(scene.asDeputy().canChangeSignInOf(onlyInA.getId()));
    }

    @Test
    public void notWhenThePersonAlsoBelongsToAnotherOrganization() {
        final Scene scene = new Scene();
        final Person inBoth = scene.memberOfA("Both");
        TenancyFixtures.addMember(scene.orgB, inBoth);
        assertTrue(scene.asDeputy().canAdminPerson(inBoth.getId()), "the profile is still theirs to edit");
        assertFalse(scene.asDeputy().canChangeSignInOf(inBoth.getId()),
                "another tenant's member is never one organization's to re-key");
    }

    /** The takeover itself: an org admin adds the site admin by address, then holds people admin over them. */
    @Test
    public void notASiteAdminWhoWasAddedToTheOrgByEmail() {
        final Scene scene = new Scene();
        final Person root = TenancyFixtures.savedPerson("Root");
        TenancyFixtures.giveLogin(root, "admin");
        TenancyFixtures.makeOrgAdmin(scene.orgA, scene.deputy);
        assertNull(scene.asDeputy().addMemberByEmail(scene.orgA.getId().getValue(), root.getEmail()));
        assertTrue(scene.asDeputy().canAdminPerson(root.getId()), "imposed membership gives profile reach");
        assertFalse(scene.asDeputy().canChangeSignInOf(root.getId()),
                "...but never the site admin's sign-in, even for the org's own admin");
    }

    /**
     * A site-wide privilege sits on an ordinary "user" login, so the site-admin check does not see it; yet whoever
     * signs in as a privilege admin can grant themselves anything on the platform.
     */
    @Test
    public void notAHolderOfASiteWidePrivilege() {
        final Scene scene = new Scene();
        final Person helper = scene.memberOfA("Helper");
        TenancyFixtures.giveLogin(helper, "user");
        assertTrue(scene.asDeputy().canChangeSignInOf(helper.getId()), "an ordinary member, before the grant");

        final PrivilegeCommands privileges = new PrivilegeCommands();
        assertTrue(privileges.savePrivilege(privileges.getOrCreate(PrivilegeCommands.PRIVILEGE_ADMIN, null,
                "See and edit privileges").withNewPerson(helper.getId())));
        assertTrue(scene.asDeputy().canAdminPerson(helper.getId()), "the profile is still theirs to edit");
        assertFalse(scene.asDeputy().canChangeSignInOf(helper.getId()),
                "re-keying this login would hand over every privilege on the site");
    }

    @Test
    public void notAnAdminOfTheOrgWhenTheCallerIsOnlyAPeopleAdminThere() {
        final Scene scene = new Scene();
        final Person boss = scene.memberOfA("Boss");
        TenancyFixtures.makeOrgAdmin(scene.orgA, boss);
        assertFalse(scene.asDeputy().canChangeSignInOf(boss.getId()),
                "a people admin must not be able to take over their own org's admin");

        // An admin of that org CAN manage it, so the same subject is theirs to change.
        final Person coAdmin = scene.memberOfA("CoAdmin");
        TenancyFixtures.makeOrgAdmin(scene.orgA, coAdmin);
        TenancyFixtures.grantPeopleAdmin(scene.orgA, coAdmin);
        assertTrue(TenancyFixtures.orgsFor(coAdmin).canChangeSignInOf(boss.getId()));
    }

    @Test
    public void yourselfAndASiteAdminAlwaysAndNobodyOtherwise() {
        final Scene scene = new Scene();
        final Person inBoth = scene.memberOfA("Both");
        TenancyFixtures.addMember(scene.orgB, inBoth);
        assertTrue(TenancyFixtures.orgsFor(inBoth).canChangeSignInOf(inBoth.getId()), "your own sign-in");
        assertTrue(TenancyFixtures.asSiteAdmin().canChangeSignInOf(inBoth.getId()));

        final Person stranger = TenancyFixtures.savedPerson("Stranger");
        assertFalse(TenancyFixtures.orgsFor(stranger).canChangeSignInOf(inBoth.getId()), "no reach at all");
        assertFalse(scene.asDeputy().canChangeSignInOf(null));
        assertFalse(scene.asDeputy().canChangeSignInOf(Person.Id.from("nobody-" + TenancyFixtures.unique())));
        final OrgCommands anonymous = new OrgCommands(() -> Caller.of((jakarta.servlet.http.HttpSession) null));
        assertFalse(anonymous.canChangeSignInOf(inBoth.getId()));
        assertFalse(anonymous.canAddEmailFor(inBoth.getId()));
    }

    // ------------------------------------------------------------------ the profile save

    @Test
    public void aProfileSaveThatChangesSomeoneElsesAddressNeedsTheRule() {
        final Scene scene = new Scene();
        final Person victim = scene.memberOfA("Victim");
        TenancyFixtures.addMember(scene.orgB, victim);
        final String original = victim.getEmail();
        final PersonCommands people = new PersonCommands(scene.deputyReach());

        final Person edited = TenancyFixtures.reload(victim);
        edited.setEmail("attacker." + TenancyFixtures.unique() + "@example.com");
        assertEquals(people.signInChangeRefusal(edited, scene.deputyReach()), OrgCommands.SIGN_IN_CHANGE_REFUSED);
        assertFalse(people.savePerson(edited), "a refusal is a refused save, never an exception");
        assertEquals(TenancyFixtures.reload(victim).getEmail(), original, "nothing was written");

        edited.setEmail(null);
        assertFalse(people.savePerson(edited), "clearing the address is a sign-in change too");

        // Everything else on the profile is still theirs to edit (the owner's decision: reach is unchanged).
        final Person renamed = TenancyFixtures.reload(victim);
        renamed.setNickname("Vic");
        renamed.setEmail("  " + original.toUpperCase(java.util.Locale.ROOT) + " ");
        assertTrue(people.savePerson(renamed), "the same address in another spelling is not a change");
    }

    @Test
    public void thePersonAndASiteAdminAndAnInOrgPeopleAdminMayChangeTheAddress() {
        final Scene scene = new Scene();
        final Person member = scene.memberOfA("Member");
        final Person mine = TenancyFixtures.reload(member);
        mine.setEmail("mine." + TenancyFixtures.unique() + "@example.com");
        assertTrue(new PersonCommands(() -> TenancyFixtures.orgsFor(member)).savePerson(mine));

        final Person byDeputy = TenancyFixtures.reload(member);
        byDeputy.setEmail("deputy-set." + TenancyFixtures.unique() + "@example.com");
        assertTrue(new PersonCommands(scene.deputyReach()).savePerson(byDeputy), "only in the deputy's org");

        final Person byRoot = TenancyFixtures.reload(member);
        byRoot.setEmail("root-set." + TenancyFixtures.unique() + "@example.com");
        assertTrue(new PersonCommands(TenancyFixtures::asSiteAdmin).savePerson(byRoot));
    }

    /** docs/family-accounts.md, "Adding a missing address": the member's own manager, and nobody passing by. */
    @Test
    public void aFirstAddressComesFromTheFamilyManagerOrTheRuleNotFromAnyoneWhoCanOpenTheProfile() {
        final Person parent = TenancyFixtures.savedPerson("Parent");
        final FamilyCommands family = new FamilyCommands(new ConfigCommands(), new AuditCommands(),
                () -> TenancyFixtures.callerFor(parent));
        final Person child = family.createFamilyMember("Kid", "NoMail", java.time.LocalDate.of(2012, 2, 3),
                Person.Sex.Female, null, false);
        assertNotNull(child);

        final Person tripStaff = TenancyFixtures.savedPerson("TripStaff");
        final Person filled = TenancyFixtures.reload(child);
        filled.setEmail("kid." + TenancyFixtures.unique() + "@example.com");
        assertFalse(new PersonCommands(() -> TenancyFixtures.orgsFor(tripStaff)).savePerson(filled),
                "whoever sets the first address can sign in as the person");
        assertNull(TenancyFixtures.reload(child).getEmail());

        assertTrue(TenancyFixtures.orgsFor(parent).canAddEmailFor(child.getId()));
        assertFalse(TenancyFixtures.orgsFor(parent).canChangeSignInOf(child.getId()),
                "a manager adds a missing address; changing one stays with an admin");
        assertTrue(new PersonCommands(() -> TenancyFixtures.orgsFor(parent)).savePerson(filled));

        final Person changed = TenancyFixtures.reload(child);
        changed.setEmail("other." + TenancyFixtures.unique() + "@example.com");
        assertFalse(new PersonCommands(() -> TenancyFixtures.orgsFor(parent)).savePerson(changed),
                "once set, the manager cannot move it");
    }

    /**
     * An organization's people admin filling in a missing address goes by the full rule, not the family
     * manager's allowance: fine for a member who is only theirs, refused for one another tenant shares.
     */
    @Test
    public void aPeopleAdminAddsAFirstAddressOnlyUnderTheRule() {
        final Scene scene = new Scene();
        final Person onlyInA = TenancyFixtures.saved(Person.builder().first("No").last("Mail").build());
        TenancyFixtures.addMember(scene.orgA, onlyInA);
        final Person shared = TenancyFixtures.saved(Person.builder().first("No").last("MailShared").build());
        TenancyFixtures.addMember(scene.orgA, shared);
        TenancyFixtures.addMember(scene.orgB, shared);
        final PersonCommands people = new PersonCommands(scene.deputyReach());

        assertTrue(scene.asDeputy().canAddEmailFor(onlyInA.getId()));
        final Person mine = TenancyFixtures.reload(onlyInA);
        mine.setEmail("first." + TenancyFixtures.unique() + "@example.com");
        assertTrue(people.savePerson(mine));

        assertFalse(scene.asDeputy().canAddEmailFor(shared.getId()));
        final Person theirs = TenancyFixtures.reload(shared);
        theirs.setEmail("first." + TenancyFixtures.unique() + "@example.com");
        assertFalse(people.savePerson(theirs));
        assertNull(TenancyFixtures.reload(shared).getEmail());
    }

    @Test
    public void deletingAnAccountSomeoneSignsInToNeedsTheRule() {
        final Scene scene = new Scene();
        final Person victim = scene.memberOfA("Victim");
        TenancyFixtures.addMember(scene.orgB, victim);
        TenancyFixtures.giveLogin(victim, "user");
        final PersonCommands people = new PersonCommands(scene.deputyReach());

        final Person doomed = TenancyFixtures.reload(victim);
        doomed.delete();
        assertFalse(people.savePerson(doomed), "ending the account ends how its owner signs in");
        assertTrue(DAO.getInstance().getPerson(victim.getId(), Cached.NO).isPresent());

        // Somebody with no login (a contact, a created child) is deleted on today's reach.
        final Person noLogin = scene.memberOfA("NoLogin");
        final Person gone = TenancyFixtures.reload(noLogin);
        gone.delete();
        assertTrue(people.savePerson(gone));

        assertFalse(people.canDeletePerson(victim.getId()), "what the page asks before it offers Delete");
        assertTrue(people.canDeletePerson(scene.memberOfA("AlsoNoLogin").getId()));
        assertFalse(people.canDeletePerson(null));
        assertFalse(people.canDeletePerson(Person.Id.from("nobody-" + TenancyFixtures.unique())));
        final PersonCommands asRoot = new PersonCommands(TenancyFixtures::asSiteAdmin);
        assertTrue(asRoot.canDeletePerson(victim.getId()));
        assertTrue(asRoot.savePerson(doomed), "a site admin may");
    }

    /** A new person has nothing stored to compare with: creation is its own set of doors. */
    @Test
    public void aNewPersonAndANullOneAreNotSignInChanges() {
        final PersonCommands people = new PersonCommands(SignInChangeRuleTest::neverAsked);
        final Person fresh = Person.builder().first("New").last("Person")
                .email("new." + TenancyFixtures.unique() + "@example.com").build();
        assertNull(people.signInChangeRefusal(fresh, SignInChangeRuleTest::neverAsked));
        assertNull(people.signInChangeRefusal(null, SignInChangeRuleTest::neverAsked));
        assertTrue(people.savePerson(fresh));
    }

    private static OrgCommands neverAsked() {
        throw new AssertionError("the reach must not be consulted when the sign-in does not change");
    }

    // ------------------------------------------------------------------ when the store cannot answer

    /**
     * Every "may I" here turns on a read (does this person have a login, is it an administrator's, what is
     * stored now), and a read that fails must count AGAINST the change: an outage is not a permission.
     */
    @Test
    public void aLoginThatCannotBeReadCountsAgainstTheChange() {
        final Scene scene = new Scene();
        final Person member = scene.memberOfA("Member");
        assertTrue(scene.asDeputy().canChangeSignInOf(member.getId()), "allowed while the store answers");
        final Person parent = TenancyFixtures.savedPerson("Parent");
        final FamilyCommands family = new FamilyCommands(new ConfigCommands(), new AuditCommands(),
                () -> TenancyFixtures.callerFor(parent));
        final Person child = family.createFamilyMember("Kid", "Mailed", java.time.LocalDate.of(2012, 2, 3),
                Person.Sex.Female, "kid." + TenancyFixtures.unique() + "@example.com", false);
        assertNull(family.deleteBlockReason(child.getId()), "deletable while the store answers");
        final Person doomed = TenancyFixtures.reload(member);
        doomed.delete();

        final DAO failing = Mockito.spy(DAO.getInstance());
        Mockito.doThrow(new IllegalStateException("throttled")).when(failing)
                .getCredsForCodeLogin(Mockito.anyString(), Mockito.eq(Cached.NO));
        try (MockedStatic<DAO> dao = Mockito.mockStatic(DAO.class, Mockito.CALLS_REAL_METHODS)) {
            dao.when(DAO::getInstance).thenReturn(failing);
            assertFalse(scene.asDeputy().canChangeSignInOf(member.getId()),
                    "a role that cannot be read is treated as a site admin's");
            assertNotNull(family.deleteBlockReason(child.getId()),
                    "a login that cannot be read is treated as one");
            final Person stranger = TenancyFixtures.savedPerson("Stranger");
            assertFalse(new PersonCommands(() -> TenancyFixtures.orgsFor(stranger)).savePerson(doomed));
        }
    }

    @Test
    public void aSaveIsRefusedWhenTheStoredRowCannotBeRead() {
        final Person member = TenancyFixtures.savedPerson("Member");
        final PersonCommands people = new PersonCommands(SignInChangeRuleTest::neverAsked);
        final DAO failing = Mockito.spy(DAO.getInstance());
        Mockito.doThrow(new IllegalStateException("throttled")).when(failing)
                .getPerson(member.getId(), Cached.NO);
        try (MockedStatic<DAO> dao = Mockito.mockStatic(DAO.class, Mockito.CALLS_REAL_METHODS)) {
            dao.when(DAO::getInstance).thenReturn(failing);
            assertEquals(people.signInChangeRefusal(member, SignInChangeRuleTest::neverAsked),
                    PersonCommands.SIGN_IN_CHECK_FAILED);
            assertFalse(people.savePerson(member), "not waved through");
            assertFalse(people.canDeletePerson(member.getId()));
        }
    }

    // ------------------------------------------------------------------ the login row

    @Test
    public void movingOrRemovingALoginNeedsTheRule() {
        final Scene scene = new Scene();
        final Person victim = scene.memberOfA("Victim");
        TenancyFixtures.addMember(scene.orgB, victim);
        TenancyFixtures.giveLogin(victim, "user");
        final String original = victim.getEmail();
        final String attacker = "attacker." + TenancyFixtures.unique() + "@example.com";
        final PassCommands asDeputy = new PassCommands(scene.deputyReach());

        try (MockedStatic<FacesContext> ignored = peopleAdminView()) {
            assertFalse(asDeputy.setEmail(victim, original, attacker), "the re-key is refused");
            assertFalse(asDeputy.deleteCreds(original), "and so is removing the login outright");
            final PassCommands asRoot = new PassCommands(TenancyFixtures::asSiteAdmin);
            assertNotNull(DAO.getInstance().getCredsForCodeLogin(original, Cached.NO), "the login is untouched");
            assertNull(DAO.getInstance().getCredsForCodeLogin(attacker, Cached.NO));

            assertTrue(asRoot.setEmail(victim, original, attacker), "a site admin still moves a login");
            assertTrue(asRoot.deleteCreds(attacker));
        }
    }

    /** The Manage People page's own flag, which is what lets a people admin's view read a login by address. */
    private static MockedStatic<FacesContext> peopleAdminView() {
        // Deep stubs (the PassCommandsTailsTest idiom): the audit and growl paths walk the context too.
        final FacesContext ctx = Mockito.mock(FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(ctx.getViewRoot().getViewMap(false))
                .thenReturn(Map.of(CredentialsDAO.CREDS_ADMIN_VIEW, "true"));
        final MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class);
        faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
        return faces;
    }
}
