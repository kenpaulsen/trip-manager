package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PrivacySettings;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import org.paulsens.trip.cache.Cached;

/**
 * Email identity around family members: the profile page's on-blur conflict lookup, and the display-only
 * fallback that reaches a child through their family manager. Both exist because a family member created
 * under someone else's login can legitimately have NO address of their own -- a state that was impossible
 * before family accounts, and that a lot of display code silently rendered as a blank.
 */
public class ContactEmailAndConflictTest {
    private DAO dao;
    private PersonCommands people;

    @BeforeClass
    public void init() {
        dao = DAO.getInstance();
        people = new PersonCommands();
    }

    // ------------------------------------------------------------------ conflict lookup

    @Test
    public void anAddressHeldByAnotherPersonIsReportedWithARecognisableName() throws IOException {
        final String email = "taken." + unique() + "@example.com";
        final Person holder = savedPerson("Martha", "Washington", email);
        final Person asker = savedPerson("Someone", "Else", null);

        final String conflict = people.emailConflictName(email, asker.getId());
        assertNotNull(conflict, "An address in use must be reported");
        assertTrue(conflict.contains("Martha"), "Enough to recognise the account: " + conflict);
        assertFalse(conflict.contains("Washington"),
                "...but not the full surname -- this answers a stranger's typo, so it must not become a "
                        + "way to read other people's details: " + conflict);
        assertNull(people.emailConflictName(email, holder.getId()),
                "Their OWN address is never a conflict");
    }

    @Test
    public void afreeOrUnusableAddressIsNoConflict() {
        assertNull(people.emailConflictName("nobody." + unique() + "@example.com", null),
                "An unused address is free");
        assertNull(people.emailConflictName("foo", null), "Not an address at all: nothing to conflict with");
        assertNull(people.emailConflictName(null, null));
        assertNull(people.emailConflictName("  ", null));
    }

    // ------------------------------------------------------------------ display fallback

    @Test
    public void aMemberWithNoAddressShowsTheirFamilyManagersOne() throws IOException {
        final Person parent = savedPerson("Ken", "Paulsen", "parent." + unique() + "@example.com");
        final FamilyCommands family = familyFor(parent);
        final Person child = family.createFamilyMember("Lucy", "Paulsen", LocalDate.of(2016, 4, 12),
                Person.Sex.Female, null, false);
        assertNotNull(child);

        assertEquals(people.contactEmail(reload(child)), parent.getEmail(),
                "A child with no address is reachable through their family manager");
        assertEquals(people.contactEmailVia(reload(child)), parent.getPreferredName(),
                "...and the borrowed mailbox is attributed, so nobody reads it as the child's own");

        assertEquals(people.contactEmail(parent), parent.getEmail(), "Their own address wins");
        assertEquals(people.contactEmailVia(parent), "", "...with nothing to attribute");
    }

    @Test
    public void everyMailableManagerIsShownCreatorFirstAndTheDisplayMatchesTheSendPath() throws IOException {
        final Person creator = savedPerson("First", "Manager", "creator." + unique() + "@example.com");
        final FamilyCommands family = familyFor(creator);
        final Person spouse = family.createFamilyMember("Second",
                "Manager", LocalDate.of(2000, 1, 1), Person.Sex.Female,
                "spouse." + unique() + "@example.com", true);
        final Person child = family.createFamilyMember("Kid",
                "Manager", LocalDate.of(2000, 1, 1), Person.Sex.Male, null, false);
        assertNotNull(spouse);
        assertNotNull(child);

        assertEquals(people.contactEmail(reload(child)), creator.getEmail() + ", " + spouse.getEmail(),
                "ALL mailable managers show, the family's creator first");
        assertEquals(people.contactEmailVia(reload(child)), "First, Second",
                "...each attributed by name, in the same order");
        assertEquals(people.contactEmail(reload(child)).replace(", ", ","),
                new RegistrationCommands().approvalRecipients(reload(child)),
                "What the contact list shows and what the approval email sends must be the SAME set");

        // Creator loses the manager flag: only the remaining manager is shown (and mailed).
        assertTrue(family.setManager(creator.getId(), false));
        assertEquals(people.contactEmail(reload(child)), spouse.getEmail(),
                "With the creator no longer a manager, the remaining manager is the contact");
    }

    @Test
    public void anUnusableManagerAddressIsSkippedRatherThanShown() throws IOException {
        final Person parent = savedPerson("Ken", "Paulsen", "parent." + unique() + "@example.com");
        final FamilyCommands family = familyFor(parent);
        final Person child = family.createFamilyMember("Kid",
                "P", LocalDate.of(2000, 1, 1), Person.Sex.Male, null, false);
        assertNotNull(child);

        // The legacy shape: a manager row whose "email" is a login name, not an address.
        parent.setEmail("little.joey");
        assertTrue(dao.savePerson(parent));
        assertEquals(people.contactEmail(reload(child)), "",
                "An address that cannot be mailed is worse than no address -- it looks reachable");
        assertEquals(people.contactEmailVia(reload(child)), "");
    }

    /** A manager who set their email private has opted their mailbox out of contact lists too. */
    @Test
    public void aPrivateManagerAddressIsNeverBorrowed() throws IOException {
        final Person parent = savedPerson("Ken", "Paulsen", "parent." + unique() + "@example.com");
        final FamilyCommands family = familyFor(parent);
        final Person child = family.createFamilyMember("Kid", "P", LocalDate.of(2010, 1, 1), Person.Sex.Male,
                null, false);
        assertNotNull(child);

        parent.getPrivacy().setEmail(PrivacySettings.Visibility.PRIVATE);
        assertTrue(dao.savePerson(parent));
        assertEquals(people.contactEmail(reload(child)), "",
                "A privacy choice beats the display fallback");
        assertEquals(people.contactEmailVia(reload(child)), "");
        assertEquals(new RegistrationCommands().approvalRecipients(reload(child)), parent.getEmail(),
                "...but privacy hides the address from VIEWERS only: the manager still receives the "
                        + "approval email -- the one sanctioned display/send difference");
    }

    @Test
    public void noFamilyAndNoAddressSimplyHasNoContact() throws IOException {
        final Person loner = savedPerson("No", "Family", null);
        assertEquals(people.contactEmail(loner), "");
        assertEquals(people.contactEmailVia(loner), "");
        assertEquals(people.contactEmail(null), "");
        assertEquals(people.contactEmailVia(null), "");
        assertFalse(people.hasValidEmail(loner));
        assertFalse(people.hasValidEmail(null));
    }

    @Test
    public void hasValidEmailRejectsTheLegacyLoginNames() throws IOException {
        assertTrue(people.hasValidEmail(savedPerson("Real", "Address", "real." + unique() + "@example.com")));
        assertFalse(people.hasValidEmail(savedPerson("Login", "Name", "little.joey")),
                "A login name in the email field is not an address");
    }

    /** A dangling familyId (the family row deleted underneath) must answer blank, never blow up. */
    @Test
    public void aDanglingFamilyPointerIsSurvivable() throws IOException {
        final Person orphan = savedPerson("Dangling", "Pointer", null);
        orphan.setFamilyId(Family.Id.from("no-such-family-" + unique()));
        assertTrue(dao.savePerson(orphan));
        assertEquals(people.contactEmail(reload(orphan)), "");
    }

    // ------------------------------------------------------------------ helpers

    private Person savedPerson(final String first, final String last, final String email) throws IOException {
        final Person person = Person.builder().first(first).last(last).build();
        person.setEmail(email);
        assertTrue(dao.savePerson(person));
        return person;
    }

    private Person reload(final Person person) {
        return dao.getPerson(person.getId(), Cached.NO).orElseThrow();
    }

    private FamilyCommands familyFor(final Person person) {
        return new FamilyCommands(new ConfigCommands(), new AuditCommands(),
                () -> new Caller(person.getId(), false,
                        new AuditActor(person.getEmail(), person.getId().getValue()), grantsNothing()));
    }

    private static PrivilegeCommands grantsNothing() {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(none.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return none;
    }

    private String unique() {
        return RandomData.genAlpha(8);
    }
}
