package org.paulsens.trip.migrate;

import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;

/**
 * Everything an import would write, decided before anything is written (docs/migrations/conference-import.md). The
 * planner builds it from the export, the mapping and READS of the target; the applier writes it; the verifier checks
 * the target against it. {@code blockers} non-empty means the owner must decide something first: the applier refuses.
 *
 * @param idMap         old person id -> the person they are in the target (created with the same id, or merged)
 * @param people        one step per old person, in export order
 * @param families      family links to make, each a member joining the anchor's family (never as a manager)
 * @param trips         the imported trips, ready to save
 * @param registrations the imported registrations, ready to save
 * @param transactions  the imported ledger rows, each with the trip it is bound to (null: unbound in the export)
 * @param roles         trip roles to grant
 * @param review        the people whose family is ambiguous, one line each with every candidate and its evidence
 * @param notes         what the report should say that needs no decision (skips, dropped legacy fields)
 * @param blockers      what needs the owner's decision before an apply
 */
public record ImportPlan(Map<String, Person.Id> idMap, List<PersonStep> people, List<FamilyLink> families,
        List<Trip> trips, List<Registration> registrations, List<TxStep> transactions, List<RoleStep> roles,
        List<String> review, List<String> notes, List<String> blockers) {

    /** What happens to one old person. */
    public enum Action {
        /** A new person, with the old id. */
        CREATE,
        /** An existing person (same address): blanks filled from the old row, nothing overwritten. */
        MERGE,
        /** A duplicate of another old person (mapping {@code sameAs}): nothing of its own is written. */
        SAME_AS,
        /** Left out (mapping {@code skipPeople}), with everything that references them. */
        SKIP
    }

    /**
     * @param oldId    the person's id in the export
     * @param targetId who they are in the target; null when skipped
     * @param person   the row to write: the new person, or the existing one with the blanks filled; null otherwise
     * @param before   for a MERGE, the existing row as read (the rollback's before-image)
     * @param filled   the fields a MERGE fills from the old row
     * @param kept     the fields where both rows have a value and they differ: the existing value is kept
     * @param note     why this action (the matching rule or mapping entry that decided it)
     */
    public record PersonStep(String oldId, Person.Id targetId, Action action, Person person, Person before,
            List<String> filled, List<String> kept, String note) {
    }

    /** A member joining the anchor's family (the anchor's own family is created if they have none). */
    public record FamilyLink(Person.Id anchor, Person.Id member, String evidence) {
    }

    /** One ledger row and the trip it is bound to. */
    public record TxStep(Transaction tx, String tripId) {
    }

    /** A trip role ({@code base} scoped to {@code tripId}) and the people who hold it. */
    public record RoleStep(String base, String tripId, List<Person.Id> people) {
    }

    /** The step for an old person id, or null. */
    public PersonStep stepFor(final String oldId) {
        return people.stream().filter(step -> step.oldId().equals(oldId)).findFirst().orElse(null);
    }

    /** How many steps took {@code action}. */
    public long count(final Action action) {
        return people.stream().filter(step -> step.action() == action).count();
    }
}
