package org.paulsens.trip.action;

import java.time.LocalDate;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Two family rules that guard somebody ELSE's account: an admin link needs reach over every member of the
 * family it grows (a new manager gets all of them), and a manager's delete is only for a member who was created
 * by mistake -- never a co-manager, never someone who signs in for themselves.
 */
public class FamilyLinkAndDeleteTest {

    private static FamilyCommands familyAs(final Caller caller) {
        return new FamilyCommands(new ConfigCommands(), new AuditCommands(), () -> caller);
    }

    private static FamilyCommands familyFor(final Person person) {
        return familyAs(TenancyFixtures.callerFor(person));
    }

    /** The child is in another organization only, so that org's people are not this people admin's to manage. */
    @Test
    public void anAdminLinkNeedsReachOverEveryMemberOfTheAnchorsFamily() {
        final Person parent = TenancyFixtures.savedPerson("Parent");
        final Person child = TenancyFixtures.savedPerson("Child");
        final Person deputy = TenancyFixtures.savedPerson("Deputy");
        final Organization orgA = TenancyFixtures.org("LinkA", parent, deputy);
        TenancyFixtures.org("LinkB", child);
        TenancyFixtures.grantPeopleAdmin(orgA, deputy);
        assertTrue(familyAs(TenancyFixtures.siteAdminCaller()).adminLink(parent.getId(), child.getId(), false));

        final FamilyCommands asDeputy = familyFor(deputy);
        assertFalse(asDeputy.adminLink(parent.getId(), deputy.getId(), true),
                "both ends are in the deputy's org, but the family holds someone who is not");
        assertFalse(TenancyFixtures.reload(deputy).getManagedUsers().contains(child.getId()),
                "the refused link must not have made the deputy the child's manager");
        assertNull(TenancyFixtures.reload(deputy).getFamilyId(), "...nor a member of the family");

        // The refusal is about reach, not about the link: once the child is one of the org's people it goes through.
        TenancyFixtures.addMember(orgA, child);
        assertTrue(asDeputy.adminLink(parent.getId(), deputy.getId(), true));
        assertTrue(TenancyFixtures.reload(deputy).getManagedUsers().contains(child.getId()));
    }

    @Test
    public void aManagerCannotDeleteACoManager() {
        final Person owner = TenancyFixtures.savedPerson("Owner");
        final FamilyCommands asOwner = familyFor(owner);
        final Person spouse = asOwner.createFamilyMember("Pat", "Spouse", LocalDate.of(1980, 1, 1),
                Person.Sex.Female, "spouse." + TenancyFixtures.unique() + "@example.com", true);
        assertNotNull(spouse);

        assertNotNull(asOwner.deleteBlockReason(spouse.getId()), "the page hides Delete on this answer");
        final FamilyCommands.FamilyResult result = asOwner.deleteFamilyMemberOutcome(spouse.getId());
        assertFalse(result.isOk(), "a co-manager is not somebody a manager created by mistake");
        assertTrue(TenancyFixtures.reload(spouse).getFamilyId() != null, "the co-manager is untouched");
    }

    @Test
    public void aManagerCannotDeleteAMemberWhoSignsInForThemselves() {
        final Person owner = TenancyFixtures.savedPerson("Owner");
        final FamilyCommands asOwner = familyFor(owner);
        final Person teen = asOwner.createFamilyMember("Tee", "Teen", LocalDate.of(2009, 1, 1),
                Person.Sex.Male, "teen." + TenancyFixtures.unique() + "@example.com", false);
        assertNotNull(teen);
        TenancyFixtures.giveLogin(teen, "user");

        assertNotNull(asOwner.deleteBlockReason(teen.getId()));
        assertFalse(asOwner.deleteFamilyMemberOutcome(teen.getId()).isOk(),
                "deleting the profile would end an account its owner signs in to");
        assertNotNull(TenancyFixtures.reload(teen).getFamilyId());
        final Caller root = TenancyFixtures.siteAdminAs(TenancyFixtures.savedPerson("Root"));
        assertFalse(familyAs(root).deleteFamilyMemberOutcome(teen.getId()).isOk(),
                "a site admin uses the unlink path for them too");
        assertNotNull(TenancyFixtures.reload(teen).getFamilyId());
    }

    /** The case the delete exists for: an address was typed, but nobody ever signed in with it. */
    @Test
    public void aMemberWithAnAddressButNoLoginIsStillDeletable() {
        final Person owner = TenancyFixtures.savedPerson("Owner");
        final FamilyCommands asOwner = familyFor(owner);
        final Person typo = asOwner.createFamilyMember("Ty", "Po", LocalDate.of(2012, 1, 1),
                Person.Sex.Male, "typo." + TenancyFixtures.unique() + "@example.com", false);
        assertNotNull(typo);

        assertNull(asOwner.deleteBlockReason(typo.getId()));
        assertTrue(asOwner.deleteFamilyMemberOutcome(typo.getId()).isOk());
        assertNull(asOwner.deleteBlockReason(Person.Id.from("nobody-" + TenancyFixtures.unique())),
                "an id that names nobody has no account to protect");
    }
}
