package org.paulsens.trip.action;

import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.TestPeople;
import org.testng.Assert;

/** Callers for tests of beans whose writes re-check the caller (the {@code callerSource} seam). */
public final class TestCallers {

    public static final Person.Id SITE_ADMIN_ID = Person.Id.from("test-site-admin");

    private TestCallers() {
    }

    /** A site administrator: holds everything, so a test of the write itself is not a test of authorization. */
    public static Caller siteAdmin() {
        return new Caller(SITE_ADMIN_ID, true, new AuditActor("site-admin@example.com", SITE_ADMIN_ID.getValue()),
                new PrivilegeCommands());
    }

    /** An ordinary signed-in person with whatever privilege rows the store holds for them. */
    public static Caller person(final Person.Id id) {
        return new Caller(id, false, new AuditActor(id.getValue() + "@example.com", id.getValue()),
                new PrivilegeCommands());
    }

    /** The media bean as a site administrator drives it. */
    public static MediaCommands mediaAsSiteAdmin() {
        final MediaCommands media = new MediaCommands();
        media.setCallerSource(TestCallers::siteAdmin);
        return media;
    }

    /**
     * A privilege store that answers no to every check, so a test of the org rules (membership, org admin) is
     * not decided by whatever privilege rows the shared store happens to hold. A fresh mock per call.
     */
    public static PrivilegeCommands grantsNothing() {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(none.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return none;
    }

    /** The stored person as an ordinary caller over {@link #grantsNothing()}: only their org rows count. */
    public static Caller holdingNothing(final Person person) {
        return new Caller(person.getId(), false, new AuditActor(person.getEmail(), person.getId().getValue()),
                grantsNothing());
    }

    /** A site administrator who is no stored person, over {@link #grantsNothing()}; a new id per call. */
    public static Caller siteAdminHoldingNothing() {
        return new Caller(Person.Id.from("admin-" + TestPeople.unique()), true, new AuditActor("admin@test", "admin"),
                grantsNothing());
    }

    /**
     * A new person id holding the named privilege in that scope, granted through the real privilege store:
     * for a test of what a trip- or org-scoped role may do.
     */
    public static Person.Id grant(final String name, final String scope) {
        final Person.Id who = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(name, scope, name).withNewPerson(who)));
        return who;
    }
}
