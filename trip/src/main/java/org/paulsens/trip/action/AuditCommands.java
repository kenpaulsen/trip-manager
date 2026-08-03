package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <h2>The {@code AuditActor} overloads, and why they are not a loophole</h2>
 *
 * <p>Every method has a sibling taking an explicit {@link AuditActor}. That looks like it reopens the second
 * problem above -- callers choosing an actor, and choosing wrong -- so it is worth being precise about what
 * changed and what did not.
 *
 * <p>The invariant is still "the actor is the signed-in session". What varies is only HOW that session is
 * reached. {@link AuditActor#current()} finds it through {@code FacesContext}, a ThreadLocal that exists only on
 * a JSF request; on a JAX-RS thread it returns an empty actor, silently, and the write still succeeds. So a REST
 * caller that could not pass an actor would not get the safe old behaviour -- it would get NO attribution at
 * all, which is strictly worse than the bug this class was written to fix.
 *
 * <p>The overloads therefore take an actor resolved by {@link AuditActor#from(jakarta.servlet.http.HttpSession)}
 * -- the same session, the same two keys, read without {@code FacesContext}. Pages keep calling the no-arg forms
 * and still cannot pass one. Nothing may pass an actor it did not get from the caller's own session.
 */
@Slf4j
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
        return person(target, what, AuditActor.current());
    }

    /** @see #person(Person, String) */
    public String person(final Person target, final String what, final AuditActor who) {
        final String msg = what + " " + describe(target);
        return record(AuditAction.PERSON, AuditOutcome.SUCCESS, target, msg, who);
    }

    /** An account's login address changed, which is the event that makes older records look misattributed. */
    public String loginChanged(final Person target, final String oldEmail) {
        return loginChanged(target, oldEmail, AuditActor.current());
    }

    /** @see #loginChanged(Person, String) */
    public String loginChanged(final Person target, final String oldEmail, final AuditActor who) {
        final String msg = "Login for " + describe(target) + " changed from " + oldEmail
                + " to " + emailOf(target);
        return record(AuditAction.PERSON, AuditOutcome.SUCCESS, target, msg, who);
    }

    /** Credentials were removed, so this person can no longer sign in. */
    public String credentialsRemoved(final Person target) {
        return credentialsRemoved(target, AuditActor.current());
    }

    /** @see #credentialsRemoved(Person) */
    public String credentialsRemoved(final Person target, final AuditActor who) {
        final String msg = "Credentials removed for " + describe(target);
        return record(AuditAction.DELETE_CREDS, AuditOutcome.SUCCESS, target, msg, who);
    }

    /** Someone registered for a trip. */
    public String registered(final Person target, final Trip trip) {
        return registered(target, trip, AuditActor.current());
    }

    /** @see #registered(Person, Trip) */
    public String registered(final Person target, final Trip trip, final AuditActor who) {
        final String msg = describe(target) + " just registered for the '" + titleOf(trip) + "' trip.";
        return record(AuditAction.REGISTRATION, AuditOutcome.SUCCESS, target, msg, who);
    }

    /** A registration was moved between trips. */
    public String registrationMoved(final Person target, final Trip from, final Trip to) {
        return registrationMoved(target, from, to, AuditActor.current());
    }

    /** @see #registrationMoved(Person, Trip, Trip) */
    public String registrationMoved(
            final Person target, final Trip from, final Trip to, final AuditActor who) {
        final String msg = actorEmail(who) + " moved " + describe(target)
                + " from '" + titleOf(from) + "' to '" + titleOf(to) + "'.";
        return record(AuditAction.REGISTRATION, AuditOutcome.SUCCESS, target, msg, who);
    }

    /** A registration was cancelled. */
    public String registrationRemoved(final Person target, final Trip trip) {
        return registrationRemoved(target, trip, AuditActor.current());
    }

    /** @see #registrationRemoved(Person, Trip) */
    public String registrationRemoved(final Person target, final Trip trip, final AuditActor who) {
        final String msg = describe(target) + " was removed from '" + titleOf(trip) + "'.";
        return record(AuditAction.REGISTRATION, AuditOutcome.SUCCESS, target, msg, who);
    }

    /** A todo item's status changed for someone. */
    public String todoStatus(final Person target, final String description, final String status) {
        return todoStatus(target, description, status, AuditActor.current());
    }

    /** @see #todoStatus(Person, String, String) */
    public String todoStatus(
            final Person target, final String description, final String status, final AuditActor who) {
        final String msg = "Set '" + description + "' to " + status + " for " + describe(target);
        return record(AuditAction.TODO, AuditOutcome.SUCCESS, target, msg, who);
    }

    /**
     * A transaction was recorded or edited against someone's account.
     *
     * <p>The message names BOTH people on purpose. It is not only an audit record: {@code transaction.xhtml}
     * sends it verbatim as the body of the notification email, so whatever is left out of it is unavailable
     * to the person reading that mail. Phase 10 moved this text out of the XHTML and, in doing so, dropped the
     * actor -- leaving a notification that said what was recorded and for whom, but not who did it. The record
     * itself still carried actorEmail; the human-readable half is what regressed.
     */
    public String transaction(final Person target, final Transaction tx) {
        return transaction(target, tx, AuditActor.current());
    }

    /** @see #transaction(Person, Transaction) */
    public String transaction(final Person target, final Transaction tx, final AuditActor who) {
        final AuditActor actor = (who == null) ? AuditActor.current() : who;
        final String msg = describeActor(actor) + " recorded $" + amountOf(tx) + " (" + noteOf(tx) + ") for "
                + describe(target);
        final AuditEventBuilder builder = Audit.builder(AuditAction.TRANSACTION, AuditOutcome.SUCCESS)
                .actor(actor)
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
        return impersonation(target, AuditActor.current());
    }

    /** @see #impersonation(Person) */
    public String impersonation(final Person target, final AuditActor who) {
        final String msg = actorEmail(who) + " is now acting as " + describe(target);
        return record(AuditAction.IMPERSONATION, AuditOutcome.SUCCESS, target, msg, who);
    }

    private static String record(final AuditAction action, final AuditOutcome outcome, final Person target,
            final String msg, final AuditActor who) {
        Audit.builder(action, outcome)
                .actor(who == null ? AuditActor.current() : who)
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

    private static String actorEmail(final AuditActor who) {
        final AuditActor actor = (who == null) ? AuditActor.current() : who;
        return (actor.email() == null) ? "Someone" : actor.email();
    }

    /**
     * The signed-in user as "First Last [email]", for messages a human reads.
     *
     * <p>Resolved by EMAIL rather than by the session's userId, which matters during impersonation: acting as
     * someone else replaces userId with THEIR id but leaves loginEmail as the admin's, so the email is the one
     * that still identifies who is really doing this.
     *
     * <p>Falls back to the bare email, then to "Someone". A missing name must never cost us the record.
     */
    private static String describeActor(final AuditActor who) {
        final AuditActor actor = (who == null) ? AuditActor.current() : who;
        if (actor.email() == null) {
            return "Someone";
        }
        try {
            final Person person = PersonCommands.getPersonCommands().getPersonByEmail(actor.email());
            if (person != null) {
                return describe(person);
            }
        } catch (final RuntimeException ex) {
            log.debug("Could not resolve a name for actor {}; using the email", actor.email(), ex);
        }
        return actor.email();
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
