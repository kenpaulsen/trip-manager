package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;

/**
 * Developer-facing privilege API. Every accessor takes the privilege's base {@code name} plus a {@code tripId}
 * ({@code null}/blank == a global, non-trip privilege). The combined DynamoDB key (base name + trip id) is an
 * implementation detail built internally via {@link Privilege#idFor(String, String)} -- callers never construct or
 * pass it.
 */
@Slf4j
@Named("priv")
@ApplicationScoped
public class PrivilegeCommands {
    private static final long TIMEOUT = 5_000;
    private final DAO dao = DAO.getInstance();

    /**
     * Creates a privilege from an explicit scope. {@code tripId} null/blank makes it global; otherwise the trip id
     * is appended to {@code name} to form the identity. The editPrivs page passes the scope chosen in its selector.
     */
    public Privilege createPrivilege(
            final String name, final String description, final String tripId, final List<Person.Id> people) {
        return new Privilege(Privilege.idFor(name, tripId), description, people);
    }

    /** Global (non-trip) privileges, name-sorted. */
    public List<Privilege> getGlobalPrivileges() {
        return sorted(dao.getGlobalPrivileges());
    }

    /** Privileges scoped to the given trip (blank/null == the global partition), name-sorted. */
    public List<Privilege> getTripPrivileges(final String tripId) {
        final String scope = blankToNull(tripId);
        return sorted(scope == null ? dao.getGlobalPrivileges() : dao.getTripPrivileges(scope));
    }

    /**
     * The named privilege, or {@link Privilege#NONE} if it does not exist. {@code tripId} null/blank looks up the
     * global privilege; otherwise the trip-scoped one.
     */
    public Privilege getPrivilege(final String name, final String tripId) {
        if (name == null) {
            return Privilege.NONE;
        }
        return getPrivilegeById(Privilege.idFor(name, blankToNull(tripId))).orElse(Privilege.NONE);
    }

    /** The named privilege if it exists, otherwise a new (unsaved) one with the given description. */
    public Privilege getOrCreate(final String name, final String tripId, final String description) {
        final String id = Privilege.idFor(name, blankToNull(tripId));
        return getPrivilegeById(id).orElseGet(() -> new Privilege(id, description, List.of()));
    }

    public boolean savePrivilege(final Privilege privilege) {
        if ((privilege == null) || (privilege.getId() == null) || privilege.getId().isBlank()) {
            throw new IllegalStateException("Cannot save a privilege without a name!");
        }
        // Compared against the saved state so the record says what actually CHANGED. "Saved a privilege with 12
        // people" does not answer the question anyone asks of an audit trail, which is who just gained access.
        final List<Person.Id> before = getPrivilegeById(privilege.getId())
                .map(Privilege::getPeople)
                .orElse(List.of());
        final boolean saved = dao.savePrivilege(privilege)
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, false))
                .join();
        // Audited here rather than at the pages, because there are three call sites and one of them (the trip
        // editor's manager checkboxes) grants privileges without ever mentioning the word.
        Audit.builder(AuditAction.PRIVILEGE, AuditOutcome.of(saved))
                .actor(AuditActor.current())
                .target(AuditEventBuilder.TARGET_PRIVILEGE, privilege.getId())
                .message(describeChange(privilege, before))
                .log();
        return saved;
    }

    /** "granted to X, revoked from Y" -- or a plain save when the membership did not move. */
    private static String describeChange(final Privilege privilege, final List<Person.Id> before) {
        final List<Person.Id> after = (privilege.getPeople() == null) ? List.of() : privilege.getPeople();
        final List<String> granted = after.stream().filter(id -> !before.contains(id))
                .map(Person.Id::getValue).toList();
        final List<String> revoked = before.stream().filter(id -> !after.contains(id))
                .map(Person.Id::getValue).toList();
        final StringBuilder msg = new StringBuilder("Saved privilege '").append(privilege.getId()).append('\'');
        if (!granted.isEmpty()) {
            msg.append("; granted to ").append(String.join(", ", granted));
        }
        if (!revoked.isEmpty()) {
            msg.append("; revoked from ").append(String.join(", ", revoked));
        }
        if (granted.isEmpty() && revoked.isEmpty()) {
            msg.append("; membership unchanged (").append(after.size()).append(" held)");
        }
        return msg.toString();
    }

    /** True if {@code personId} holds the named privilege ({@code tripId} null/blank == global). */
    public boolean check(final String name, final String tripId, final Person.Id personId) {
        if (name == null || personId == null) {
            return false;
        }
        return getPrivilegeById(Privilege.idFor(name, blankToNull(tripId)))
                .map(priv -> priv.getPeople().contains(personId))
                .orElse(false);
    }

    /** Grants the named privilege to {@code personId}. No-op if already held or the privilege is unknown. */
    public boolean add(final String name, final String tripId, final Person.Id personId) {
        if (check(name, tripId, personId)) {
            return false;
        }
        return getPrivilegeById(Privilege.idFor(name, blankToNull(tripId)))
                .map(priv -> priv.withNewPerson(personId))
                .map(this::savePrivilege)
                .orElse(false);
    }

    /** Revokes the named privilege from {@code personId}. */
    public boolean remove(final String name, final String tripId, final Person.Id personId) {
        return getPrivilegeById(Privilege.idFor(name, blankToNull(tripId)))
                .map(priv -> priv.withoutPerson(personId))
                .map(this::savePrivilege)
                .orElse(false);
    }

    /**
     * Rules for supporting role / user / priv according to this method:
     * <ol><li>If role is supplied and the active user has that role, they are authorized.</li>
     *     <li>If user is supplied and the active user is that user, or can access that user, they are authorized.</li>
     *     <li>If priv is supplied and the active user matches, they are authorized.</li>
     *     <li>If role, user, and priv are all null, they are authorized.</li>
     *     <li>Otherwise, they are <em>not</em> authorized.</li>
     * </ol>
     * @param role          The role... this can be a blank string b/c of how EL evaluates, we will treat this as null.
     * @param requiredUser  The user ID.
     * @param privName      The privilege base name... blank (from EL) is treated as null.
     * @param privTripId    The trip the privilege is scoped to, or null/blank for a global privilege.
     * @return  True if the user is authorized.
     */
    public boolean isAuthorized(
            final String role, final Person.Id requiredUser, final String privName, final String privTripId) {
        final PersonCommands personCommands = PersonCommands.getPersonCommands();
        final Person currUser = personCommands.getCurrentPerson();
        final boolean result;
        final String requiredRole = blankToNull(role);
        final String requiredPriv = blankToNull(privName);
        if (personCommands.hasRole(requiredRole)) {
            result = true;
        } else if (personCommands.canAccessUserId(currUser, requiredUser)) {
            result = true;
        } else if (requiredPriv != null) {
            result = check(requiredPriv, privTripId, currUser.getId());
        } else {
            result = requiredRole == null && requiredUser == null;
        }
        return result;
    }

    /**
     * All people who hold any of the named privileges within {@code tripId} (null/blank == global), de-duplicated
     * and sorted by "last, preferred name".
     */
    public List<Person.Id> getPeopleWithPriv(final List<String> names, final String tripId) {
        final PersonCommands people = PersonCommands.getPersonCommands();
        final String scope = blankToNull(tripId);
        return names.stream()
                .map(name -> Privilege.idFor(name, scope))
                .map(this::getPrivilegeById)
                .map(op -> op.map(Privilege::getPeople).orElse(List.of()))
                .flatMap(Collection::stream)
                .distinct()
                .sorted((a, b) -> lastCommaPreferredComparator(people, a, b))
                .toList();
    }

    private int lastCommaPreferredComparator(final PersonCommands people, final Person.Id a, final Person.Id b) {
        final Person aPerson = people.getPerson(a);
        final Person bPerson = people.getPerson(b);
        if (aPerson == null) {
            return -1;
        }
        if (bPerson == null) {
            return 1;
        }
        return CharSequence.compare(
                aPerson.getLast() + ',' + aPerson.getPreferredName(),
                bPerson.getLast() + ',' + bPerson.getPreferredName());
    }

    private List<Privilege> sorted(final CompletableFuture<List<Privilege>> future) {
        final List<Privilege> result = new ArrayList<>(future
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, List.of()))
                .join());
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    // Internal: lookup by the combined DynamoDB identity. The public API never exposes this id.
    private Optional<Privilege> getPrivilegeById(final String id) {
        final Optional<Privilege> priv = dao.getPrivilege(id)
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, Optional.empty()))
                .join();
        if (priv.isEmpty()) {
            log.debug("Unknown privilege '" + id + "'!");
        }
        return priv;
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private <T> T logAndReturn(final Throwable ex, final T result) {
        log.warn("Exception!", ex);
        return result;
    }
}
