package org.paulsens.trip.audit;

import java.time.Instant;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Person;

/**
 * Assembles an {@link AuditEvent} at a call site.
 *
 * <p>Most audited actions have both an actor and something they acted upon, and both are usually people. The
 * shape that matters is {@code who did what to whom}: an admin editing someone else's record is a different
 * event from a person editing their own, and a trail that records only the actor cannot tell them apart.
 *
 * <p>Both actor and target carry an email and an id. Emails are what a human searches by; ids are what stay
 * correct when someone changes their login address -- which this app supports, so an email-only record quietly
 * misattributes history after a rename.
 */
public final class AuditEventBuilder {

    /** Target type constants, so the strings do not drift the way the old free-text action strings did. */
    public static final String TARGET_PERSON = "person";
    public static final String TARGET_TRIP = "trip";
    public static final String TARGET_REGISTRATION = "registration";
    public static final String TARGET_TRANSACTION = "transaction";
    public static final String TARGET_TODO = "todo";
    public static final String TARGET_MEDIA = "media";
    public static final String TARGET_TEMPLATE = "template";
    public static final String TARGET_CONTENT = "content";
    public static final String TARGET_CONFIG = "config";
    public static final String TARGET_PRIVILEGE = "privilege";
    public static final String TARGET_CHAT_CHANNEL = "chatChannel";
    public static final String TARGET_CHAT_MESSAGE = "chatMessage";
    public static final String TARGET_PIPELINE = "pipeline";

    private final AuditAction action;
    private final AuditOutcome outcome;
    private Instant timestamp;
    private String actorEmail;
    private String actorId;
    private String targetType;
    private String targetEmail;
    private String targetId;
    private String message;
    private int schemaVersion = AuditEvent.CURRENT_SCHEMA;

    AuditEventBuilder(final AuditAction action, final AuditOutcome outcome) {
        this.action = action;
        this.outcome = outcome;
    }

    /** Who performed the action. */
    public AuditEventBuilder actor(final String email, final String id) {
        this.actorEmail = email;
        this.actorId = id;
        return this;
    }

    /** Who performed the action, when the {@link Person} is already loaded. */
    public AuditEventBuilder actor(final Person person) {
        return (person == null) ? this : actor(person.getEmail(), idOf(person));
    }

    /** The signed-in user. The right default: the actor is who DID it, not who it was about. */
    public AuditEventBuilder actor(final AuditActor auditActor) {
        return actor(auditActor.email(), auditActor.id());
    }

    /** The signed-in user, or the given email when nobody is signed in (self-service flows). */
    public AuditEventBuilder currentActor(final String fallbackEmail) {
        return actor(AuditActor.currentOr(fallbackEmail));
    }

    /** What was acted upon, when the target is a person. */
    public AuditEventBuilder targetPerson(final String email, final String id) {
        this.targetType = TARGET_PERSON;
        this.targetEmail = email;
        this.targetId = id;
        return this;
    }

    /** What was acted upon, when the {@link Person} is already loaded. */
    public AuditEventBuilder targetPerson(final Person person) {
        return (person == null) ? this : targetPerson(person.getEmail(), idOf(person));
    }

    /** What was acted upon, when the target is not a person (a trip, a media file, a setting). */
    public AuditEventBuilder target(final String type, final String id) {
        this.targetType = type;
        this.targetId = id;
        return this;
    }

    public AuditEventBuilder message(final String message) {
        this.message = message;
        return this;
    }

    /** Overrides the time; used by the importer replaying historical records, not by live callers. */
    public AuditEventBuilder at(final Instant when) {
        this.timestamp = when;
        return this;
    }

    /** Marks this as an imported legacy record, whose outcome and target were never written down. */
    public AuditEventBuilder legacy() {
        this.schemaVersion = AuditEvent.LEGACY_SCHEMA;
        return this;
    }

    public AuditEvent build() {
        return new AuditEvent(timestamp, action, outcome, actorEmail, actorId,
                targetType, targetEmail, targetId, message, schemaVersion);
    }

    /** Records the built event. The usual terminal call: {@code Audit.builder(...).actor(...).log();} */
    public void log() {
        Audit.log(build());
    }

    private static String idOf(final Person person) {
        return (person.getId() == null) ? null : person.getId().getValue();
    }
}
