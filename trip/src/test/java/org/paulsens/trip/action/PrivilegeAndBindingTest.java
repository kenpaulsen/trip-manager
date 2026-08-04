package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link PrivilegeCommands} and {@link BindingCommands} against the fake store.
 *
 * <p>{@code isAuthorized} is the one every page's {@code defaultAuth} runs, so its ladder -- role, then
 * "acting for this person", then a named privilege, then "nothing was required" -- decides whether a page
 * renders at all. Off a Faces thread the role and current-person halves resolve to nothing, which is why the
 * REST edge has {@code Caller} instead.
 */
public class PrivilegeAndBindingTest {

    private final PrivilegeCommands privileges = new PrivilegeCommands();
    private final BindingCommands bindings = new BindingCommands();
    private final PersonCommands people = new PersonCommands();

    /**
     * Trip ids here are real UUIDs, and that is load-bearing.
     *
     * <p>A privilege's identity is {@code baseName + tripId} with no separator, and the split back into
     * (name, tripId) is a regex anchored on a CANONICAL UUID suffix. A trip id that is not a UUID does not
     * parse, so the privilege silently reads as GLOBAL -- it saves fine, checks fine by name, and simply
     * appears in the wrong listing. Real trip ids are UUIDs ({@code Trip.builder()} mints one), so a test using
     * "trip-1" would be testing a shape the application never produces.
     */
    private String tripId;

    private Person.Id alice;
    private Person.Id bob;

    @BeforeMethod
    public void createPeople() {
        tripId = java.util.UUID.randomUUID().toString();
        alice = person("Alice", "Privs");
        bob = person("Bob", "Privs");
    }

    /**
     * Grants after ensuring the privilege exists.
     *
     * <p>Two steps, because {@code getOrCreate} answers an UNSAVED privilege when there is none and {@code add}
     * resolves the STORED one and no-ops when it finds nothing. So granting into a name nobody has saved
     * silently does nothing and reports false. The admin page saves first; a test that skipped that step would
     * be asserting against a code path the application never takes.
     */
    private void grant(final String name, final String tripId, final Person.Id who) {
        Assert.assertTrue(privileges.savePrivilege(privileges.getOrCreate(name, tripId, name)),
                "Saving " + name);
        Assert.assertTrue(privileges.add(name, tripId, who), "Grant of " + name + " to " + who);
    }

    /** Cache invalidation after a write is asynchronous; poll rather than race it. */
    private static void awaitTrue(final java.util.function.BooleanSupplier condition, final String what) {
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assert.assertTrue(condition.getAsBoolean(), "Timed out waiting for " + what);
    }

    private Person.Id person(final String first, final String last) {
        final Person who = people.createPerson();
        who.setFirst(first);
        who.setLast(last);
        Assert.assertTrue(people.savePerson(who));
        return who.getId();
    }

    // --- PrivilegeCommands ---

    @Test
    public void grantingAndRevokingRoundTrips() {
        Assert.assertFalse(privileges.check("tripMgr", tripId, alice));
        // Granting into a name nobody created is a silent no-op, by design.
        Assert.assertFalse(privileges.add("neverCreated", tripId, alice));

        grant("tripMgr", tripId, alice);
        Assert.assertTrue(privileges.check("tripMgr", tripId, alice));

        // Re-granting is not a failure of intent, but it reports false: nothing changed.
        Assert.assertFalse(privileges.add("tripMgr", tripId, alice));

        Assert.assertTrue(privileges.remove("tripMgr", tripId, alice));
        Assert.assertFalse(privileges.check("tripMgr", tripId, alice));
        // NOT symmetric with add: remove reports whether the SAVE succeeded, not whether anything changed, so
        // revoking what nobody holds still answers true. Callers cannot read it as "something was revoked".
        Assert.assertTrue(privileges.remove("tripMgr", tripId, alice));
    }

    @Test
    public void aTripScopedGrantDoesNotLeakToAnotherTripOrToGlobal() {
        grant("tripMgr", tripId, alice);

        Assert.assertFalse(privileges.check("tripMgr", java.util.UUID.randomUUID().toString(), alice), "Another trip");
        Assert.assertFalse(privileges.check("tripMgr", null, alice), "Global scope");
        Assert.assertFalse(privileges.check("tripMgr", tripId, bob), "Another person");
    }

    @Test
    public void savingAPrivilegeWithAnActorRecordsIt() {
        final Privilege privilege = privileges.createPrivilege("emailAdmin", "Send email", null, List.of(alice));

        Assert.assertTrue(privileges.savePrivilege(privilege, new AuditActor("admin@x", "admin-id")));

        Assert.assertEquals(privileges.getPrivilege("emailAdmin", null).getPeople(), List.of(alice));
    }

    @Test
    public void getOrCreateInventsAPrivilegeRatherThanFailing() {
        final Privilege created = privileges.getOrCreate("brandNew", null, "A description");

        Assert.assertNotNull(created);
        Assert.assertEquals(created.getDescription(), "A description");
    }

    @Test
    public void anUnknownPrivilegeReadsAsTheNoneSentinel() {
        final Privilege missing = privileges.getPrivilege("noSuchPrivilege", null);

        Assert.assertTrue(missing == null || missing == Privilege.NONE || missing.getPeople().isEmpty(),
                "A miss must not look like a grant");
    }

    @Test
    public void globalAndTripListingsAreSeparateAndNameSorted() {
        Assert.assertTrue(privileges.savePrivilege(
                privileges.createPrivilege("zzzGlobal", "z", null, List.of(alice))));
        Assert.assertTrue(privileges.savePrivilege(
                privileges.createPrivilege("aaaGlobal", "a", null, List.of(alice))));
        grant("scopedOne", tripId, alice);

        final List<Privilege> global = privileges.getGlobalPrivileges();
        final List<String> globalNames = global.stream().map(Privilege::getName).toList();
        Assert.assertTrue(globalNames.contains("aaaGlobal"));
        Assert.assertTrue(globalNames.contains("zzzGlobal"));
        Assert.assertFalse(globalNames.contains("scopedOne"), "A trip privilege is not global");
        Assert.assertEquals(globalNames, globalNames.stream().sorted(String::compareToIgnoreCase).toList());

        awaitTrue(() -> privileges.getTripPrivileges(tripId).stream()
                .anyMatch(p -> "scopedOne".equals(p.getName())), "the trip-scoped privilege listing");
    }

    /** The people picker: de-duplicated across privileges and sorted "last, preferred". */
    @Test
    public void peopleWithPrivAreDedupedAndSorted() {
        final Person.Id zed = person("Zed", "Zulu");
        final Person.Id ann = person("Ann", "Able");
        grant("privA", tripId, zed);
        grant("privA", tripId, ann);
        grant("privB", tripId, ann);

        final List<Person.Id> found = privileges.getPeopleWithPriv(List.of("privA", "privB"), tripId);

        Assert.assertEquals(found.size(), 2, "Ann holds both but appears once");
        Assert.assertEquals(found.get(0), ann, "Able sorts before Zulu");
    }

    /**
     * The suffix rule itself. If trip ids ever stop being canonical UUIDs, every trip-scoped privilege silently
     * becomes global -- saved, checkable by name, and in the wrong listing. This is the guard for that.
     */
    @Test
    public void aTripScopedPrivilegeOnlyParsesWhenTheTripIdIsAUuid() {
        final Privilege scoped = privileges.createPrivilege("scoped", "d", tripId, List.of(alice));
        Assert.assertEquals(scoped.getName(), "scoped");
        Assert.assertEquals(scoped.getTripId(), tripId);
        Assert.assertFalse(scoped.isGlobal());

        final Privilege notAUuid = privileges.createPrivilege("scoped", "d", "trip-1", List.of(alice));
        Assert.assertTrue(notAUuid.isGlobal(),
                "A non-UUID trip id does not parse as a suffix, so the privilege reads as global");
        Assert.assertEquals(notAUuid.getName(), "scopedtrip-1", "and the base name absorbs it");

        Assert.assertTrue(privileges.createPrivilege("global", "d", null, List.of()).isGlobal());
    }

    @Test
    public void peopleWithPrivIgnoresUnknownNames() {
        Assert.assertEquals(privileges.getPeopleWithPriv(List.of("noSuchPriv"), null), List.of());
    }

    /**
     * Off a Faces thread there is no session, so the role and current-person halves of the ladder resolve to
     * nothing. What must NOT happen is authorizing anyway: with a requirement stated, the answer is false.
     */
    @Test
    public void isAuthorizedFailsClosedWithoutASession() {
        Assert.assertFalse(privileges.isAuthorized("admin", null, null, null));
        Assert.assertFalse(privileges.isAuthorized(null, alice, null, null));
        Assert.assertFalse(privileges.isAuthorized(null, null, "tripMgr", tripId));
    }

    /** Nothing required means nothing to refuse -- how a page with no reqRole/reqPriv renders for anyone. */
    @Test
    public void isAuthorizedAllowsWhenNothingIsRequired() {
        Assert.assertTrue(privileges.isAuthorized(null, null, null, null));
        Assert.assertTrue(privileges.isAuthorized("  ", null, "  ", null),
                "Blank comes from EL for an unset value and means the same as null");
    }

    // --- BindingCommands ---

    /**
     * TRANSACTION is a COMPOSITE binding type, so its id must be a "user,tx" composite key -- transactions are
     * keyed by person as well as id. Passing a bare id throws "Invalid composite key".
     */
    @Test
    public void bindingsRoundTripInBothDirections() {
        final String txKey = bindings.key("user-1", "tx-1");
        bindings.setBindings(txKey, BindingType.TRANSACTION, BindingType.TRIP, List.of("trip-a"), true);

        Assert.assertEquals(bindings.getBindings(txKey, BindingType.TRANSACTION, BindingType.TRIP),
                List.of("trip-a"));
        Assert.assertTrue(bindings.getBindings("trip-a", BindingType.TRIP, BindingType.TRANSACTION)
                .contains(txKey), "The reverse binding is written too");
    }

    @Test
    public void aCompositeBindingTypeRefusesABareId() {
        Assert.assertThrows(IllegalArgumentException.class, () ->
                bindings.getBindings("bare-id", BindingType.TRANSACTION, BindingType.TRIP));
    }

    /** setBindings is a REPLACE: ids that disappear from the list are unbound, and reported. */
    @Test
    public void setBindingsReplacesAndReportsWhatItRemoved() {
        final String txKey = bindings.key("user-2", "tx-2");
        bindings.setBindings(txKey, BindingType.TRANSACTION, BindingType.TRIP,
                List.of("trip-x", "trip-y"), true);

        final List<String> removed = bindings.setBindings(txKey, BindingType.TRANSACTION, BindingType.TRIP,
                List.of("trip-y"), true);

        Assert.assertEquals(removed, List.of("trip-x"));
        Assert.assertEquals(bindings.getBindings(txKey, BindingType.TRANSACTION, BindingType.TRIP),
                List.of("trip-y"));
    }

    @Test
    public void duplicateDestinationsAreCollapsed() {
        final String txKey = bindings.key("user-3", "tx-3");
        bindings.setBindings(txKey, BindingType.TRANSACTION, BindingType.TRIP,
                List.of("trip-z", "trip-z"), false);

        Assert.assertEquals(bindings.getBindings(txKey, BindingType.TRANSACTION, BindingType.TRIP),
                List.of("trip-z"));
    }

    @Test
    public void compositeKeysRoundTrip() {
        final String key = bindings.key("user-1", "tx-9");

        Assert.assertEquals(bindings.splitKey(key), List.of("user-1", "tx-9"));
    }

    @Test
    public void anUnboundThingHasNoBindings() {
        Assert.assertEquals(bindings.getBindings("nothing-here", BindingType.TRIP, BindingType.TRANSACTION),
                List.of());
    }
}
