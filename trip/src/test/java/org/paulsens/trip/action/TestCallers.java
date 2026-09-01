package org.paulsens.trip.action;

import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;

/** Callers for tests of beans whose writes re-check the caller (the {@code callerSource} seam). */
final class TestCallers {

    static final Person.Id SITE_ADMIN_ID = Person.Id.from("test-site-admin");

    private TestCallers() {
    }

    /** A site administrator: holds everything, so a test of the write itself is not a test of authorization. */
    static Caller siteAdmin() {
        return new Caller(SITE_ADMIN_ID, true, new AuditActor("site-admin@example.com", SITE_ADMIN_ID.getValue()),
                new PrivilegeCommands());
    }

    /** An ordinary signed-in person with whatever privilege rows the store holds for them. */
    static Caller person(final Person.Id id) {
        return new Caller(id, false, new AuditActor(id.getValue() + "@example.com", id.getValue()),
                new PrivilegeCommands());
    }

    /** The media bean as a site administrator drives it. */
    static MediaCommands mediaAsSiteAdmin() {
        final MediaCommands media = new MediaCommands();
        media.setCallerSource(TestCallers::siteAdmin);
        return media;
    }
}
