package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The family invariants. Every test drives the REAL command + DAO + in-memory store; only the caller (no
 * FacesContext in tests) and, where a test needs a specific size limit, the config are stood in for.
 */
public class FamilyCommandsTest {
    private DAO dao;

    @BeforeClass
    public void init() {
        dao = DAO.getInstance();
    }

    // ------------------------------------------------------------------ create-and-link

    @Test
    public void creatingTheFirstMemberFormsAFamilyWithTheOwnerAsManager() throws IOException {
        final Person owner = savedOwner();
        final Person member = commandsFor(owner).createFamilyMember(
                "Lucy", "Paulsen", LocalDate.of(2015, 5, 4), Person.Sex.Female, null, false);

        assertNotNull(member, "Create should succeed");
        final Family family = dao.getFamily(reload(owner).getFamilyId()).orElseThrow();
        assertTrue(family.isManager(owner.getId()), "The owner becomes the first manager");
        assertTrue(family.isMember(member.getId()));
        assertFalse(family.isManager(member.getId()), "A plain member is not a manager");
        assertEquals(reload(member).getFamilyId(), family.getId(), "Back-pointer set on the member");
        assertTrue(reload(owner).getManagedUsers().contains(member.getId()),
                "The manager's managedUsers gains the new member");
        assertTrue(reload(member).getManagedUsers().isEmpty(), "A plain member manages nobody");
    }

    @Test
    public void aManagerMemberGainsEveryOtherMemberAndLaterAddsStayInSync() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        final Person spouse = commands.createFamilyMember(
                "Pat", "Paulsen", LocalDate.of(1980, 1, 1), Person.Sex.Female,
                "pat." + unique() + "@example.com", true);
        assertNotNull(spouse);
        assertTrue(reload(spouse).getManagedUsers().contains(owner.getId()),
                "A manager member manages every other member, including the owner");

        final Person child = commands.createFamilyMember(
                "Kid", "Paulsen", LocalDate.of(2018, 3, 2), Person.Sex.Male, null, false);
        assertNotNull(child);
        assertTrue(reload(owner).getManagedUsers().contains(child.getId()),
                "Every manager gains a later-added member");
        assertTrue(reload(spouse).getManagedUsers().contains(child.getId()),
                "EVERY manager, not just the one who clicked Add");
        assertFalse(reload(child).getManagedUsers().contains(owner.getId()),
                "A plain member gains nothing");
    }

    @Test
    public void aPlainMemberCannotAddToTheFamily() throws IOException {
        final Person owner = savedOwner();
        final Person member = commandsFor(owner).createFamilyMember(
                "Teen", "Paulsen", LocalDate.of(2010, 6, 1), Person.Sex.Male, "teen." + unique() + "@x.com", false);
        assertNotNull(member);

        final Person refused = commandsFor(member).createFamilyMember(
                "Friend", "Smith", LocalDate.of(2010, 7, 1), Person.Sex.Male, null, false);
        assertNull(refused, "A non-manager member must be refused");
        final Family family = dao.getFamily(reload(owner).getFamilyId()).orElseThrow();
        assertEquals(family.getSize(), 2, "The family must be unchanged");
    }

    @Test
    public void theSizeLimitIsEnforced() throws IOException {
        final Person owner = savedOwner();
        final ConfigCommands tinyLimit = Mockito.mock(ConfigCommands.class);
        Mockito.when(tinyLimit.getInt(KnownSettings.FAMILY_MAX_MEMBERS, 1, 100)).thenReturn(2);
        final FamilyCommands commands = new FamilyCommands(tinyLimit, new AuditCommands(), callerOf(owner, false));

        assertNotNull(commands.createFamilyMember("A", "One", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false));
        assertNull(commands.createFamilyMember("B", "Two", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false),
                "The member that would exceed family.maxMembers must be refused");
        assertTrue(commands.isAtLimit());
    }

    @Test
    public void aDuplicateEmailIsRefusedAtCreation() throws IOException {
        final String email = "taken." + unique() + "@example.com";
        final Person existing = savedOwner();
        existing.setEmail(email);
        assertTrue(dao.savePerson(existing));

        final Person owner = savedOwner();
        assertNull(commandsFor(owner).createFamilyMember("Dup", "Email", LocalDate.of(2000, 1, 1), Person.Sex.Female, email, false),
                "An email already belonging to another person must be refused");
    }

    @Test
    public void requiredNamesAreEnforcedAndNobodySignedInIsRefused() {
        final Person owner = savedOwner();
        assertNull(commandsFor(owner).createFamilyMember(" ", "Last", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false));
        final FamilyCommands anonymous = new FamilyCommands(
                new ConfigCommands(), new AuditCommands(), () -> new Caller(null, false, null, grantsNothing()));
        assertNull(anonymous.createFamilyMember("First", "Last", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false));
    }

    // ------------------------------------------------------------------ the admin-grant survival property

    @Test
    public void adminGrantsOutsideTheFamilySurviveEveryFamilyOperation() throws IOException {
        final Person.Id extraneous = Person.Id.from("admin-grant-" + unique());
        final Person owner = savedOwner();
        owner.getManagedUsers().add(extraneous);        // an admin visibility grant predating the family
        assertTrue(dao.savePerson(owner));

        final FamilyCommands commands = commandsFor(owner);
        final Person spouse = commands.createFamilyMember("S", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female,
                "s." + unique() + "@example.com", true);
        final Person child = commands.createFamilyMember("C", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female,
                "c." + unique() + "@example.com", false);
        assertNotNull(spouse);
        assertNotNull(child);
        assertTrue(reload(owner).getManagedUsers().contains(extraneous), "...survives member adds");

        assertTrue(commands.setManager(child.getId(), true));
        assertTrue(commands.setManager(child.getId(), false));
        assertTrue(reload(owner).getManagedUsers().contains(extraneous), "...survives manager flips");

        assertTrue(commands.deleteFamilyMember(child.getId()));
        assertTrue(reload(owner).getManagedUsers().contains(extraneous), "...survives member delete");

        assertTrue(commandsAdmin().adminUnlink(spouse.getId()));
        assertTrue(reload(owner).getManagedUsers().contains(extraneous), "...survives admin unlink");
    }

    // ------------------------------------------------------------------ delete

    @Test
    public void deleteIsBlockedByTripMembershipOrTransactions() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        final Person onTrip = commands.createFamilyMember("On", "Trip", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        final Person hasTx = commands.createFamilyMember("Has", "Tx", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        assertNotNull(onTrip);
        assertNotNull(hasTx);

        final Trip trip = Trip.builder().id("fam-trip-" + unique()).title("Fam Trip")
                .startDate(LocalDateTime.now().plusDays(30)).endDate(LocalDateTime.now().plusDays(40))
                .people(List.of(onTrip.getId())).build();
        assertTrue(dao.saveTrip(trip));
        assertTrue(dao.saveTransaction(new Transaction(hasTx.getId(), "grp-" + unique(),
                Transaction.Type.Shared)));

        assertFalse(commands.deleteFamilyMember(onTrip.getId()), "Trip membership must block delete");
        assertFalse(commands.deleteFamilyMember(hasTx.getId()), "Any transaction must block delete");
        assertNotNull(commands.deleteBlockReason(onTrip.getId()));
        assertNotNull(commands.deleteBlockReason(hasTx.getId()));
    }

    @Test
    public void deletingACleanMemberDetachesEverythingAndSoftDeletes() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        final Person spouse = commands.createFamilyMember("S", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female,
                "s." + unique() + "@example.com", true);
        final Person typo = commands.createFamilyMember("Typo", "Oops", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        assertNotNull(spouse);
        assertNotNull(typo);

        assertTrue(commands.deleteFamilyMember(typo.getId()));
        assertTrue(dao.getPerson(typo.getId()).isEmpty(), "Soft-deleted people stop resolving");
        final Family family = dao.getFamily(reload(owner).getFamilyId()).orElseThrow();
        assertFalse(family.isMember(typo.getId()));
        assertFalse(reload(owner).getManagedUsers().contains(typo.getId()),
                "Every manager forgets the deleted member");
        assertFalse(reload(spouse).getManagedUsers().contains(typo.getId()));

        assertFalse(commands.deleteFamilyMember(owner.getId()), "Deleting yourself is refused");
    }

    // ------------------------------------------------------------------ manager flag

    @Test
    public void managerGrantAndRevokeKeepManagedUsersInSync() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        final Person spouse = commands.createFamilyMember("S", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female,
                "s." + unique() + "@example.com", false);
        final Person child = commands.createFamilyMember("C", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        assertNotNull(spouse);
        assertNotNull(child);

        assertTrue(commands.setManager(spouse.getId(), true));
        assertTrue(reload(spouse).getManagedUsers().containsAll(List.of(owner.getId(), child.getId())),
                "A grant copies the whole current family");

        assertTrue(commands.setManager(spouse.getId(), false));
        assertTrue(reload(spouse).getManagedUsers().isEmpty(), "A revoke removes exactly the family ids");
    }

    /**
     * "Has an email" is not "can be mailed": {@code foo} in the email field satisfied a blank check while
     * leaving the person unable to sign in or receive anything. Every manager gate asks for a VALID address.
     */
    @Test
    public void aManagerNeedsAValidAddressNotJustANonEmptyOne() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);

        assertNull(commands.createFamilyMember("Bogus", "Manager", LocalDate.of(2000, 1, 1), Person.Sex.Male, "foo", true),
                "'foo' is not an address; it cannot buy a manager grant");

        final Person member = commands.createFamilyMember("Half", "Baked", LocalDate.of(2000, 1, 1), Person.Sex.Male, "foo", false);
        assertNotNull(member, "A non-manager may still hold whatever the legacy data holds");
        assertFalse(commands.setManager(member.getId(), true),
                "Granting manager to a member whose 'email' is not an address must be refused");

        member.setEmail("real." + unique() + "@example.com");
        assertTrue(dao.savePerson(member));
        assertTrue(commands.setManager(member.getId(), true), "A real address makes the grant possible");
    }

    /** Sex is required at creation: passports and rooming both need it, so a blank one is a future chore. */
    @Test
    public void creatingAMemberWithoutASexIsRefused() {
        final Person owner = savedOwner();
        assertNull(commandsFor(owner).createFamilyMember("No", "Sex", LocalDate.of(2000, 1, 1), null, null, false),
                "A family member must be created with a sex");
        assertNull(reload(owner).getFamilyId(), "The refusal wrote nothing");
    }

    /** The EL-facing overload parses the page's String; blank must reach the same refusal, not an NPE. */
    @Test
    public void theStringOverloadRefusesABlankSex() {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        assertNull(commands.addFamilyMember("No", "Sex", LocalDate.of(2000, 1, 1), "", null, false), "Blank sex is refused");
        assertNull(commands.addFamilyMember("No", "Sex", LocalDate.of(2000, 1, 1), null, null, false), "Null sex is refused");
        assertNotNull(commands.addFamilyMember("Has", "Sex", LocalDate.of(2000, 1, 1), "Male", null, false));
    }

    /** Birthdate is required at creation -- passports, insurance, and pricing all need it. */
    @Test
    public void creatingAMemberWithoutABirthdateIsRefused() {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        assertNull(commands.createFamilyMember("No", "Bday", null, Person.Sex.Male, null, false),
                "A missing birthdate must be refused");
        assertNull(reload(owner).getFamilyId(), "The refusal wrote nothing");
        assertNull(commands.createFamilyMember("Time", "Traveler",
                        LocalDate.now().plusDays(2), Person.Sex.Male, null, false),
                "A birthdate in the future is a typo, not a person");
        assertNotNull(commands.createFamilyMember("Has", "Bday",
                LocalDate.of(2001, 2, 3), Person.Sex.Male, null, false));
    }

    @Test
    public void aManagerMustHaveTheirOwnEmailAddress() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);

        assertNull(commands.createFamilyMember("No", "Email", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, true),
                "Creating a manager without an email must be refused");
        assertNull(reload(owner).getFamilyId(),
                "The refused create must not have created anything (checked before any write)");

        final Person child = commands.createFamilyMember("C", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        assertNotNull(child, "A NON-manager without an email is fine");
        assertFalse(commands.setManager(child.getId(), true),
                "Granting manager to an email-less member must be refused");
        assertFalse(dao.getFamily(reload(owner).getFamilyId()).orElseThrow().isManager(child.getId()));

        // A standalone (no-family) email-less person: adminLink must refuse the as-manager link too.
        final Person standalone = Person.builder().first("E").last("L").build();
        assertTrue(dao.savePerson(standalone));
        assertFalse(commandsAdmin().adminLink(savedOwner().getId(), standalone.getId(), true),
                "adminLink-as-manager must also require an email");
        assertNull(reload(standalone).getFamilyId(), "The refused link must not have linked");
    }

    @Test
    public void theElOverloadParsesSexAndARedundantGrantIsANoOp() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        final Person member = commands.addFamilyMember("El", "Overload", LocalDate.of(2001, 2, 3),
                "Female", "el." + unique() + "@example.com", false);
        assertNotNull(member, "The EL overload parses the sex string and delegates");
        assertEquals(member.getSex(), Person.Sex.Female);
        assertTrue(commands.setManager(owner.getId(), true),
                "Granting manager to an existing manager is a no-op success");
    }

    @Test
    public void theLastManagerOfAMultiPersonFamilyCannotBeRevoked() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        assertNotNull(commands.createFamilyMember("Kid", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false));
        assertFalse(commands.setManager(owner.getId(), false),
                "Revoking the only manager of a 2+ person family must be refused");
    }

    // ------------------------------------------------------------------ admin link / unlink

    @Test
    public void adminLinksAnExistingPersonAndRejectsCrossFamilyLinks() throws IOException {
        final Person anchor = savedOwner();
        final Person spouse = savedOwner();     // existing person with their own login story
        assertTrue(commandsAdmin().adminLink(anchor.getId(), spouse.getId(), true));

        final Family family = dao.getFamily(reload(anchor).getFamilyId()).orElseThrow();
        assertTrue(family.isMember(spouse.getId()));
        assertTrue(family.isManager(spouse.getId()));
        assertTrue(reload(spouse).getManagedUsers().contains(anchor.getId()));
        assertTrue(commandsAdmin().adminLink(anchor.getId(), spouse.getId(), true),
                "Re-linking into the same family is a no-op, not an error");

        final Person otherAnchor = savedOwner();
        assertFalse(commandsAdmin().adminLink(otherAnchor.getId(), spouse.getId(), false),
                "A person already in another family must be rejected");
    }

    @Test
    public void adminLinkIsRefusedWithoutThePrivilege() throws IOException {
        final Person anchor = savedOwner();
        final Person other = savedOwner();
        final FamilyCommands notAdmin = commandsFor(anchor);
        assertFalse(notAdmin.adminLink(anchor.getId(), other.getId(), false),
                "adminLink must require peopleAdmin (or site admin)");
    }

    @Test
    public void unlinkRulesProtectTheRemainingFamily() throws IOException {
        // Family: owner (email), spouse-manager (no email), child (no email).
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        final Person spouse = commands.createFamilyMember("S", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        final Person child = commands.createFamilyMember("C", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        assertNotNull(spouse);
        assertNotNull(child);
        // A no-email manager can no longer be CREATED through the commands (the email gate), but legacy
        // data can still hold one -- an email can be removed after the grant. Build that state directly;
        // the unlink rules must keep protecting against it.
        final Family withNoEmailManager = dao.getFamily(reload(owner).getFamilyId()).orElseThrow();
        withNoEmailManager.getManagerIds().add(spouse.getId());
        assertTrue(dao.saveFamily(withNoEmailManager));

        // Rule (b): removing the owner would leave no remaining member with an email address.
        assertNotNull(commandsAdmin().unlinkBlockReason(owner.getId()));
        assertFalse(commandsAdmin().adminUnlink(owner.getId()));

        // Give the spouse an email; now removing the owner trips rule... nothing -- spouse manages + has email.
        final Person spouseReloaded = reload(spouse);
        spouseReloaded.setEmail("spouse." + unique() + "@example.com");
        assertTrue(dao.savePerson(spouseReloaded));
        assertNull(commandsAdmin().unlinkBlockReason(owner.getId()));

        // Rule (a): removing the spouse-manager instead would leave owner+child managerless? No -- the owner
        // IS a manager. Remove the owner first, then the spouse is the only manager of spouse+child.
        assertTrue(commandsAdmin().adminUnlink(owner.getId()));
        assertNotNull(commandsAdmin().unlinkBlockReason(spouse.getId()),
                "Removing the last manager of a 2-person family must be blocked");

        assertNull(reload(owner).getFamilyId(), "Unlinked people lose the back-pointer");
        assertFalse(reload(owner).getManagedUsers().contains(child.getId()),
                "An unlinked manager loses the family-derived entries");
    }

    @Test
    public void unlinkingTheLastMemberDeletesTheFamilyRow() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        final Person child = commands.createFamilyMember("C", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        assertNotNull(child);
        final Family.Id familyId = reload(owner).getFamilyId();

        assertTrue(commandsAdmin().adminUnlink(child.getId()));
        assertTrue(commandsAdmin().adminUnlink(owner.getId()));
        assertTrue(dao.getFamily(familyId).isEmpty(), "An emptied family row is deleted");
    }

    // ------------------------------------------------------------------ resync

    @Test
    public void resyncRepairsCorruptionWithoutRemovingAnything() throws IOException {
        final Person owner = savedOwner();
        final Person.Id extraneous = Person.Id.from("keep-me-" + unique());
        final FamilyCommands commands = commandsFor(owner);
        final Person spouse = commands.createFamilyMember("S", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female,
                "s." + unique() + "@example.com", true);
        assertNotNull(spouse);
        final Family.Id familyId = reload(owner).getFamilyId();

        // Corrupt: strip the spouse from the owner's list, clear the spouse's back-pointer, add an extraneous
        // grant that a resync must NOT remove.
        final Person corruptOwner = reload(owner);
        corruptOwner.getManagedUsers().remove(spouse.getId());
        corruptOwner.getManagedUsers().add(extraneous);
        assertTrue(dao.savePerson(corruptOwner));
        final Person corruptSpouse = reload(spouse);
        corruptSpouse.setFamilyId(null);
        assertTrue(dao.savePerson(corruptSpouse));

        assertTrue(commandsAdmin().resyncFamily(familyId.getValue()));
        assertTrue(reload(owner).getManagedUsers().contains(spouse.getId()), "Missing entries re-added");
        assertTrue(reload(owner).getManagedUsers().contains(extraneous), "Additive-only: nothing removed");
        assertEquals(reload(spouse).getFamilyId(), familyId, "Back-pointers repaired");
    }

    @Test
    public void ensureFamilyIsIdempotent() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        assertNotNull(commands.createFamilyMember("A", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false));
        final Family.Id first = reload(owner).getFamilyId();
        assertNotNull(commands.createFamilyMember("B", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false));
        assertEquals(reload(owner).getFamilyId(), first, "A second add reuses the same family");
        assertEquals(dao.getFamily(first).orElseThrow().getSize(), 3);
    }

    // ------------------------------------------------------------------ profile completeness

    @Test
    public void missingProfileFieldsNamesExactlyTheGaps() {
        final FamilyCommands commands = commandsFor(savedOwner());
        final Person blank = new Person();
        assertEquals(commands.missingProfileFields(blank),
                List.of("Birthdate", "Passport number", "Emergency contact"));
        blank.setBirthdate(LocalDate.of(2000, 1, 1));
        blank.getPassport().setNumber("X123");
        blank.setEmergencyContactName("Mom");
        assertEquals(commands.missingProfileFields(blank), List.of("Passport expiration"),
                "A passport number without an expiration is called out");
        assertTrue(commands.missingProfileFields(null).isEmpty());
    }

    // ------------------------------------------------------------------ EL-facing reads

    @Test
    public void pageHelpersAnswerForTheSignedInPerson() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        assertNull(commands.getMyFamily(), "No family yet");
        assertFalse(commands.isMyFamilyManager());
        assertTrue(commands.getMembers(null).isEmpty());

        final Person child = commands.createFamilyMember("Kid", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        assertNotNull(child);
        final Family family = commands.getMyFamily();
        assertNotNull(family);
        assertTrue(commands.isMyFamilyManager());
        final List<Person> members = commands.getMembers(family);
        assertEquals(members.size(), 2);
        assertEquals(members.get(0).getId(), owner.getId(), "The signed-in person lists first");
        assertEquals(members.get(1).getId(), child.getId());
        assertFalse(commands.isAtLimit(), "Two members is nowhere near the default limit");
        assertEquals(commands.getMaxMembers(), 10, "The KnownSettings default");
    }

    @Test
    public void aDanglingFamilyPointerIsToleratedAndReplacedOnNextUse() throws IOException {
        final Person owner = savedOwner();
        owner.setFamilyId(Family.Id.from("deleted-out-of-band-" + unique()));
        assertTrue(dao.savePerson(owner));

        final FamilyCommands commands = commandsFor(owner);
        assertNull(commands.getMyFamily(), "A pointer at a missing row answers null, never throws");
        final Person child = commands.createFamilyMember("Kid", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false);
        assertNotNull(child, "The next member-add replaces the dangling family");
        assertNotNull(commands.getMyFamily());
        assertTrue(commands.getMyFamily().isMember(child.getId()));
    }

    // ------------------------------------------------------------------ guard edges

    @Test
    public void operationsWithoutAFamilyOrOutsideItAreRefused() throws IOException {
        final Person loner = savedOwner();
        final FamilyCommands commands = commandsFor(loner);
        assertFalse(commands.deleteFamilyMember(Person.Id.from("nobody")), "No family to manage");
        assertFalse(commands.setManager(Person.Id.from("nobody"), true), "No family to manage");

        final Person stranger = savedOwner();
        assertNotNull(commands.createFamilyMember("Kid", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false));
        assertFalse(commands.deleteFamilyMember(stranger.getId()), "Not in your family");
        assertFalse(commands.setManager(stranger.getId(), true), "Not in your family");
        assertTrue(commands.setManager(loner.getId(), true), "Granting an existing manager is a no-op");
        assertNotNull(commandsAdmin().unlinkBlockReason(stranger.getId()), "Not in a family");
        assertFalse(commandsAdmin().adminUnlink(stranger.getId()));
        assertFalse(commandsAdmin().adminLink(Person.Id.from("ghost-" + unique()), stranger.getId(), false),
                "A missing anchor person is refused");
        assertFalse(commandsAdmin().resyncFamily("no-such-family-" + unique()));
        assertFalse(commandsFor(loner).resyncFamily("whatever"), "resync requires peopleAdmin");
    }

    @Test
    public void resyncToleratesAFamilyReferencingAMissingPerson() throws IOException {
        final Person owner = savedOwner();
        final FamilyCommands commands = commandsFor(owner);
        assertNotNull(commands.createFamilyMember("Kid", "P", LocalDate.of(2000, 1, 1), Person.Sex.Female, null, false));
        final Family family = dao.getFamily(reload(owner).getFamilyId()).orElseThrow();
        family.getMemberIds().add(Person.Id.from("ghost-" + unique()));
        assertTrue(dao.saveFamily(family));

        assertTrue(commandsAdmin().resyncFamily(family.getId().getValue()),
                "A ghost member is reported, not fatal");
    }

    // ------------------------------------------------------------------ helpers

    /** A fresh saved person with a unique email, acting as a family owner. */
    private Person savedOwner() {
        final Person owner = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("owner." + unique() + "@example.com")
                .build();
        try {
            assertTrue(dao.savePerson(owner));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return owner;
    }

    private Person reload(final Person person) {
        return dao.getPerson(person.getId()).orElseThrow();
    }

    private FamilyCommands commandsFor(final Person person) {
        return new FamilyCommands(new ConfigCommands(), new AuditCommands(), callerOf(person, false));
    }

    /** A site-admin caller; {@code Caller.has} short-circuits, so no privilege rows are needed. */
    private FamilyCommands commandsAdmin() {
        return new FamilyCommands(new ConfigCommands(), new AuditCommands(),
                () -> new Caller(Person.Id.from("admin-" + unique()), true,
                        new AuditActor("admin@test", "admin"), grantsNothing()));
    }

    private java.util.function.Supplier<Caller> callerOf(final Person person, final boolean siteAdmin) {
        return () -> new Caller(person.getId(), siteAdmin,
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
