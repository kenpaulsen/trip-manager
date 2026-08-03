package org.paulsens.trip.api;

/**
 * How much of another person's record the caller is allowed to see.
 *
 * <p>This exists because the API returns people to people. A JSF page could rely on nobody navigating to a URL
 * they were not linked to; a REST client enumerates ids. So visibility has to be decided in Java, per viewer, and
 * be visible in a test.
 *
 * <p>Deliberately NOT a ranked ladder with a {@code >=} comparison. The relationships genuinely do not form a
 * total order -- a trip manager needs passport details to file a manifest but has no business reading the private
 * notes an office admin left, while a person managing their spouse's booking sees everything about them and
 * nothing about anyone else. A rank forces one of those to be wrong. Capabilities are asked for by name instead.
 */
public enum AccessLevel {

    /** The subject is the caller. */
    SELF,
    /** The caller manages the subject's booking -- {@code Person.managedUsers} contains the subject. */
    MANAGER,
    /** Site administrator. */
    SITE_ADMIN,
    /** Holds {@code tripMgr} on a trip the subject is registered for. */
    TRIP_ADMIN,
    /** Holds {@code tripView} on a trip the subject is registered for: roster access, not dossier access. */
    TRIP_VIEWER,
    /** Registered on the same trip and nothing more. The default, and the one that must leak nothing. */
    PEER;

    /** Cell, email, address, emergency contacts. */
    public boolean seesContactDetail() {
        return this != PEER;
    }

    /** Passport, TSA number, birthdate, sex -- what is needed to book travel and file a manifest. */
    public boolean seesTravelDocuments() {
        return this == SELF || this == MANAGER || this == SITE_ADMIN || this == TRIP_ADMIN;
    }

    /**
     * Free-text notes staff keep on a traveller.
     *
     * <p>Never visible to {@link #TRIP_VIEWER} or {@link #PEER}: these are written about people, not for them,
     * and the whole point of the field is that the subject is not reading over your shoulder.
     */
    public boolean seesNotes() {
        return this == SELF || this == SITE_ADMIN || this == TRIP_ADMIN;
    }

    /** Structural fields -- managed users, the soft-delete marker. */
    public boolean seesAccountStructure() {
        return this == SELF || this == MANAGER || this == SITE_ADMIN;
    }
}
