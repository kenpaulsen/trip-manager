package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.Util;

/**
 * The audit API for XHTML pages.
 *
 * <p>Pages used to build their own records: a type string and a message assembled from a chain of
 * {@code .concat()} calls. Three problems followed from that, all of them visible in the five years of history
 * this replaces:
 *
 * <ul>
 *   <li>The type drifted -- {@code ADMIN}, {@code REG}, {@code saveTx}, {@code PWReset} -- because nothing
 *       constrained it. Here the page picks a METHOD, and the method picks the action.</li>
 *   <li>The actor was whatever variable was in scope, which was often the person being acted ON. A registration
 *       cancelled by an admin was recorded against the traveller, so the trail said they cancelled it
 *       themselves. Here the actor is always the signed-in session and pages cannot pass one.</li>
 *   <li>A {@code .concat()} on a null field throws and aborts the whole event block, so a person with no cell
 *       number silently lost the audit record AND everything after it. Message building moved here, where a
 *       null is just a null.</li>
 * </ul>
 *
 * <p>Every method RETURNS the message it recorded, because several pages send the same text as a notification
 * email -- that is why they built the string themselves in the first place.
 */
@Named("audit")
@ApplicationScoped
public class AuditCommands {

    /**
     * Records an arbitrary message with a free-text type.
     *
     * @deprecated Prefer a typed method below. This exists for pages not yet converted and for genuinely
     *         one-off events; an unrecognised type is recorded as {@code UNKNOWN} with the original text kept,
     *         so nothing is lost, but such records cannot be filtered on properly.
     * @param userEmail The user (email) this message pertains to.
     * @param auditType The TYPE of message (i.e. LOGIN, CREATE_CREDS, etc).
     * @param msg       The "message" to write to the audit log.
     */
    @Deprecated
    public void log(final String userEmail, final String auditType, final String msg) {
        Audit.log(Util.orDefault(userEmail, ""), Util.orDefault(auditType, ""),
                Util.orDefault(msg, "[no message]"));
    }

    /**
     * An admin created, edited or deleted a person record.
     *
     * @param target what was acted on.
     * @param what   the change, e.g. "EDITED" or "DELETED".
     */
    public String person(final Person target, final String what) {
        final String msg = what + " " + describe(target);
        return record(AuditAction.PERSON, AuditOutcome.SUCCESS, target, msg);
    }

    /** An account's login address changed, which is the event that makes older records look misattributed. */
    public String loginChanged(final Person target, final String oldEmail) {
        final String msg = "Login for " + describe(target) + " changed from " + oldEmail
                + " to " + emailOf(target);
        return record(AuditAction.PERSON, AuditOutcome.SUCCESS, target, msg);
    }

    /** Credentials were removed, so this person can no longer sign in. */
    public String credentialsRemoved(final Person target) {
        final String msg = "Credentials removed for " + describe(target);
        return record(AuditAction.DELETE_CREDS, AuditOutcome.SUCCESS, target, msg);
    }

    /** Someone registered for a trip. */
    public String registered(final Person target, final Trip trip) {
        final String msg = describe(target) + " just registered for the '" + titleOf(trip) + "' trip.";
        return record(AuditAction.REGISTRATION, AuditOutcome.SUCCESS, target, msg);
    }

    /** A registration was moved between trips. */
    public String registrationMoved(final Person target, final Trip from, final Trip to) {
        final String msg = actorEmail() + " moved " + describe(target)
                + " from '" + titleOf(from) + "' to '" + titleOf(to) + "'.";
        return record(AuditAction.REGISTRATION, AuditOutcome.SUCCESS, target, msg);
    }

    /** A registration was cancelled. */
    public String registrationRemoved(final Person target, final Trip trip) {
        final String msg = describe(target) + " was removed from '" + titleOf(trip) + "'.";
        return record(AuditAction.REGISTRATION, AuditOutcome.SUCCESS, target, msg);
    }

    /** A todo item's status changed for someone. */
    public String todoStatus(final Person target, final String description, final String status) {
        final String msg = "Set '" + description + "' to " + status + " for " + describe(target);
        return record(AuditAction.TODO, AuditOutcome.SUCCESS, target, msg);
    }

    /** A transaction was recorded or edited against someone's account. */
    public String transaction(final Person target, final Transaction tx) {
        final String msg = "Recorded $" + amountOf(tx) + " (" + noteOf(tx) + ") for " + describe(target);
        final AuditEventBuilder builder = Audit.builder(AuditAction.TRANSACTION, AuditOutcome.SUCCESS)
                .actor(AuditActor.current())
                .targetPerson(target)
                .message(msg);
        if (tx != null && tx.getTxId() != null) {
            builder.target(AuditEventBuilder.TARGET_TRANSACTION, tx.getTxId());
            // Keep the person too: "what happened to this traveller's account" is the usual question.
            builder.targetPerson(target);
        }
        builder.log();
        return msg;
    }

    /** A password reset was requested through the forgot-password flow. */
    public String passwordReset(final String email, final boolean succeeded, final String detail) {
        final String msg = succeeded ? "Password reset sent" : "Password reset failed: " + detail;
        Audit.builder(AuditAction.PASSWORD_RESET, AuditOutcome.of(succeeded))
                // Nobody is signed in during this flow, so the account holder is the truthful actor.
                .currentActor(email)
                .targetPerson(email, null)
                .message(msg)
                .log();
        return msg;
    }

    /**
     * An admin began acting as another user.
     *
     * <p>Also previously unaudited. Without it, every subsequent action in that session appears to have been
     * performed by the person being impersonated, and nothing anywhere records that a switch happened.
     */
    public String impersonation(final Person target) {
        final String msg = actorEmail() + " is now acting as " + describe(target);
        return record(AuditAction.IMPERSONATION, AuditOutcome.SUCCESS, target, msg);
    }

    private static String record(final AuditAction action, final AuditOutcome outcome, final Person target,
            final String msg) {
        Audit.builder(action, outcome)
                .actor(AuditActor.current())
                .targetPerson(target)
                .message(msg)
                .log();
        return msg;
    }

    /** "First Last [email]", tolerating any of them being null -- the old concat chains did not. */
    static String describe(final Person person) {
        if (person == null) {
            return "(unknown person)";
        }
        final StringBuilder text = new StringBuilder();
        // getPreferredName() already falls back to the first name; it is null only if both are.
        append(text, person.getPreferredName());
        append(text, person.getLast());
        if (person.getEmail() != null) {
            text.append(text.isEmpty() ? "" : " ").append('[').append(person.getEmail()).append(']');
        }
        return text.isEmpty() ? "(unnamed person)" : text.toString();
    }

    private static void append(final StringBuilder text, final String part) {
        if (part != null && !part.isBlank()) {
            text.append(text.isEmpty() ? "" : " ").append(part);
        }
    }

    private static String actorEmail() {
        final AuditActor actor = AuditActor.current();
        return (actor.email() == null) ? "Someone" : actor.email();
    }

    private static String emailOf(final Person person) {
        return (person == null || person.getEmail() == null) ? "(no email)" : person.getEmail();
    }

    private static String titleOf(final Trip trip) {
        return (trip == null || trip.getTitle() == null) ? "(unknown trip)" : trip.getTitle();
    }

    private static String amountOf(final Transaction tx) {
        return (tx == null || tx.getAmount() == null) ? "0" : String.valueOf(tx.getAmount());
    }

    private static String noteOf(final Transaction tx) {
        return (tx == null || tx.getNote() == null) ? "" : tx.getNote();
    }
}
