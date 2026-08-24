package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
import org.paulsens.trip.cache.Cached;

/**
 * Developer-facing privilege API. Every accessor takes the privilege's base {@code name} plus a {@code scopeId}
 * ({@code null}/blank == a global privilege; otherwise a Trip or Organization id -- the base name decides which
 * kind, see {@link #TRIP_SCOPED_BASES} / {@link #ORG_SCOPED_BASES}). The combined DynamoDB key (base name +
 * scope id) is an implementation detail built internally via {@link Privilege#idFor(String, String)} -- callers
 * never construct or pass it.
 */
@Slf4j
@Named("priv")
@ApplicationScoped
public class PrivilegeCommands {

    /**
     * The privilege names, spelled exactly as the XHTML pages spell them.
     *
     * <p>Here rather than at the REST edge because both edges need them and a typo is a silent open door -- a
     * misspelled name matches no stored row, {@code check} answers false, and the feature simply stops working
     * for everyone who is not a site administrator.
     *
     * <p>These are the replacement for the legacy all-or-nothing {@code showAll} flag, which is being phased
     * out; new code authorizes by name.
     */
    public static final String PEOPLE_ADMIN = "peopleAdmin";
    public static final String PRIVILEGE_ADMIN = "privilegeAdmin";
    public static final String CONFIG_ADMIN = "configAdmin";
    public static final String AUDIT_ADMIN = "auditAdmin";
    public static final String MEDIA_ADMIN = "mediaAdmin";
    /** Edits content templates and ALL template-driven page sections. Template bodies are raw HTML rendered
     *  unescaped on public pages, so grant this like you would grant script access. */
    public static final String CONTENT_ADMIN = "contentAdmin";
    /** Edits the home-page Events section (fills placeholder values only; cannot author templates). */
    public static final String EVENT_ADMIN = "eventAdmin";
    public static final String EMAIL_ADMIN = "emailAdmin";
    public static final String SITE_DEPLOYER = "siteDeployer";
    public static final String TRIP_MGR = "tripMgr";
    public static final String TRIP_VIEW = "tripView";
    public static final String TRIP_FIN_VIEW = "tripFinView";
    public static final String TRIP_FIN_ADMIN = "tripFinAdmin";
    /** Moderates a trip's chat (delete messages, manage members). Trip-scoped. */
    public static final String CHAT_MGR = "chatMgr";
    public static final String ADD_TRIP = "addTrip";
    /** May flip the payment page into SANDBOX mode (sandbox APIs, no real ledger writes). Org-scoped:
     *  sandbox mode exercises the trip's org's sandbox credentials, so the grant follows the org. */
    public static final String PAYMENTS_ADMIN = "paymentsAdmin";

    /**
     * The scope each base name is used with. A privilege row does not record what kind of thing its scope UUID
     * names ({@link Privilege}'s suffix parse is kind-blind), so these lists ARE the authority: org pages offer
     * {@link #ORG_SCOPED_BASES}, the trip-manager panel offers {@link #TRIP_SCOPED_BASES}, and an
     * {@code Organization}'s allow-list draws from both. {@link #GLOBAL_BASES} exists for the privilege editor's
     * name dropdown -- content containers may still reference arbitrary names beyond it.
     */
    public static final List<String> TRIP_SCOPED_BASES =
            List.of(TRIP_MGR, TRIP_FIN_ADMIN, TRIP_FIN_VIEW, TRIP_VIEW, CHAT_MGR);
    public static final List<String> ORG_SCOPED_BASES =
            List.of(PEOPLE_ADMIN, ADD_TRIP, EMAIL_ADMIN, PAYMENTS_ADMIN);
    public static final List<String> GLOBAL_BASES = List.of(PRIVILEGE_ADMIN, CONFIG_ADMIN, AUDIT_ADMIN,
            SITE_DEPLOYER, CONTENT_ADMIN, MEDIA_ADMIN, EVENT_ADMIN);

    /**
     * One-line description per canonical base, defaulted into a new row's description by the privilege
     * editors (a picked name says what the description would say; nobody should have to retype it). Stored
     * rows keep whatever description they were saved with -- this map only fills blanks at create time.
     */
    private static final java.util.Map<String, String> BASE_DESCRIPTIONS = java.util.Map.ofEntries(
            java.util.Map.entry(TRIP_MGR, "Edit the trip: details, itinerary, roster"),
            java.util.Map.entry(TRIP_FIN_ADMIN, "Record and edit the trip's financial transactions"),
            java.util.Map.entry(TRIP_FIN_VIEW, "View the trip's finances (read-only)"),
            java.util.Map.entry(TRIP_VIEW, "View the trip's admin pages (read-only)"),
            java.util.Map.entry(CHAT_MGR, "Moderate the trip's chat"),
            java.util.Map.entry(PEOPLE_ADMIN, "Manage the organization's people"),
            java.util.Map.entry(ADD_TRIP, "Create new trips for the organization"),
            java.util.Map.entry(EMAIL_ADMIN, "Send email to the organization's members"),
            java.util.Map.entry(PAYMENTS_ADMIN, "Use payment sandbox mode on the organization's trips"),
            java.util.Map.entry(PRIVILEGE_ADMIN, "See and edit privileges"),
            java.util.Map.entry(CONFIG_ADMIN, "See and manage site settings"),
            java.util.Map.entry(AUDIT_ADMIN, "See audit logs"),
            java.util.Map.entry(SITE_DEPLOYER, "Deploy committed changes to AWS"),
            java.util.Map.entry(CONTENT_ADMIN, "Edit templates and template-driven page content"),
            java.util.Map.entry(MEDIA_ADMIN, "Manage the media library"),
            java.util.Map.entry(EVENT_ADMIN, "Edit the home page's Events section"));

    /** The canonical description for a base name, or "" for custom names -- see {@link #BASE_DESCRIPTIONS}. */
    public String baseDescription(final String baseName) {
        return baseName == null ? "" : BASE_DESCRIPTIONS.getOrDefault(baseName.trim(), "");
    }

    private static final long TIMEOUT = 5_000;
    private final DAO dao = DAO.getInstance();

    /**
     * Creates a privilege from an explicit scope. {@code scopeId} null/blank makes it global; otherwise the trip
     * or org id is appended to {@code name} to form the identity. The privilege-editor pages pass the scope
     * chosen in their selector.
     */
    public Privilege createPrivilege(
            final String name, final String description, final String scopeId, final List<Person.Id> people) {
        Privilege.requireStorableScope(scopeId);
        return new Privilege(Privilege.idFor(name, scopeId), description, people);
    }

    /** Global (non-trip) privileges, name-sorted. */
    public List<Privilege> getGlobalPrivileges() {
        return sorted(dao.getGlobalPrivileges(Cached.NO));
    }

    /**
     * Privileges in the given scope's partition (blank/null == the global partition), name-sorted. The name
     * says "trip" for EL-compatibility reasons, but any scope id works -- an org id lists that org's partition.
     */
    public List<Privilege> getTripPrivileges(final String scopeId) {
        final String scope = blankToNull(scopeId);
        return sorted(scope == null ? dao.getGlobalPrivileges(Cached.NO) : dao.getTripPrivileges(scope, Cached.NO));
    }

    /**
     * The named privilege, or {@link Privilege#NONE} if it does not exist. {@code scopeId} null/blank looks up
     * the global privilege; otherwise the trip- or org-scoped one.
     */
    public Privilege getPrivilege(final String name, final String scopeId) {
        if (name == null) {
            return Privilege.NONE;
        }
        return getPrivilegeById(Privilege.idFor(name, blankToNull(scopeId))).orElse(Privilege.NONE);
    }

    /** The named privilege if it exists, otherwise a new (unsaved) one with the given description. */
    public Privilege getOrCreate(final String name, final String scopeId, final String description) {
        Privilege.requireStorableScope(blankToNull(scopeId));
        final String id = Privilege.idFor(name, blankToNull(scopeId));
        return getPrivilegeById(id).orElseGet(() -> new Privilege(id, description, List.of()));
    }

    public boolean savePrivilege(final Privilege privilege) {
        return savePrivilege(privilege, AuditActor.current());
    }

    /**
     * Saves a privilege, recording {@code who} as the actor.
     *
     * <p>The no-arg form resolves the actor with {@link AuditActor#current()}, which reads {@code FacesContext}.
     * On a JAX-RS thread there is no {@code FacesContext} and that returns an empty actor -- silently, with a
     * successful save. A privilege change is exactly the record you go looking for months later, and "somebody
     * granted site-admin to this person" is not an answer, so the REST edge passes the session-derived actor.
     */
    public boolean savePrivilege(final Privilege privilege, final AuditActor who) {
        if ((privilege == null) || (privilege.getId() == null) || privilege.getId().isBlank()) {
            throw new IllegalStateException("Cannot save a privilege without a name!");
        }
        // Compared against the saved state so the record says what actually CHANGED. "Saved a privilege with 12
        // people" does not answer the question anyone asks of an audit trail, which is who just gained access.
        final List<Person.Id> before = getPrivilegeById(privilege.getId())
                .map(Privilege::getPeople)
                .orElse(List.of());
        boolean saved;
        try {
            saved = dao.savePrivilege(privilege);
        } catch (final RuntimeException ex) {
            saved = logAndReturn(ex, false);
        }
        // Audited here rather than at the pages, because there are three call sites and one of them (the trip
        // editor's manager checkboxes) grants privileges without ever mentioning the word.
        Audit.builder(AuditAction.PRIVILEGE, AuditOutcome.of(saved))
                .actor(who == null ? AuditActor.current() : who)
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

    /** The canonical base names for one scope kind -- the privilege editor's name dropdown. */
    public List<String> knownBases(final String scopeKind) {
        if ("TRIP".equalsIgnoreCase(scopeKind)) {
            return TRIP_SCOPED_BASES;
        }
        if ("ORG".equalsIgnoreCase(scopeKind)) {
            return ORG_SCOPED_BASES;
        }
        return GLOBAL_BASES;
    }

    /**
     * Hard-deletes one privilege row, holders and all -- how the editor retires an obsolete or mistyped row
     * (and, post-org-migration, the inert global rows of the migrated names). The audit record names every
     * holder at deletion time, because "who LOST access" is exactly what the trail gets asked later.
     */
    public boolean deletePrivilege(final String name, final String scopeId) {
        if (name == null || name.isBlank()) {
            return false;
        }
        final String id = Privilege.idFor(name, blankToNull(scopeId));
        final Privilege stored = getPrivilegeById(id).orElse(null);
        if (stored == null) {
            return false;
        }
        boolean deleted;
        try {
            deleted = Boolean.TRUE.equals(dao.deletePrivilege(id));
        } catch (final RuntimeException ex) {
            deleted = logAndReturn(ex, false);
        }
        final List<String> holders = stored.getPeople().stream().map(Person.Id::getValue).toList();
        Audit.builder(AuditAction.PRIVILEGE, AuditOutcome.of(deleted))
                .actor(AuditActor.current())
                .target(AuditEventBuilder.TARGET_PRIVILEGE, id)
                .message("DELETED privilege '" + id + "'"
                        + (holders.isEmpty() ? " (no holders)" : "; held by " + String.join(", ", holders)))
                .log();
        return deleted;
    }

    /** True if {@code personId} holds the named privilege ({@code scopeId} null/blank == global). */
    public boolean check(final String name, final String scopeId, final Person.Id personId) {
        if (name == null || personId == null) {
            return false;
        }
        return getPrivilegeById(Privilege.idFor(name, blankToNull(scopeId)))
                .map(priv -> priv.getPeople().contains(personId))
                .orElse(false);
    }

    /**
     * Grants the named privilege to {@code personId}.
     *
     * @return true when the grant was stored; false when they already held it. A grant against a privilege
     *         nobody ever SAVED cannot succeed ({@code getOrCreate} answers an unsaved object; this resolves
     *         only stored rows) -- that case is a failed grant, so it is logged as an error and audited as a
     *         failure rather than returned as a quiet false a caller would read as "already held".
     */
    public boolean add(final String name, final String scopeId, final Person.Id personId) {
        Privilege.requireStorableScope(blankToNull(scopeId));
        if (check(name, scopeId, personId)) {
            return false;
        }
        final String id = Privilege.idFor(name, blankToNull(scopeId));
        final Privilege stored = getPrivilegeById(id).orElse(null);
        if (stored == null) {
            log.error("Grant of '{}' to {} did NOT happen: no privilege named '{}' has been saved.",
                    id, personId, id);
            Audit.builder(AuditAction.PRIVILEGE, AuditOutcome.FAILURE)
                    .target(AuditEventBuilder.TARGET_PRIVILEGE, id)
                    .message("Grant to " + personId.getValue() + " failed: privilege was never created")
                    .log();
            return false;
        }
        return savePrivilege(stored.withNewPerson(personId));
    }

    /**
     * Revokes the named privilege from {@code personId}. Idempotent by design: revoking from someone who never
     * held it re-saves the unchanged row and reports the SAVE -- {@code true} means "they do not hold it now",
     * not "they held it a moment ago". False means the privilege itself does not exist.
     */
    public boolean remove(final String name, final String scopeId, final Person.Id personId) {
        return getPrivilegeById(Privilege.idFor(name, blankToNull(scopeId)))
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
     * @param privScopeId   The trip or org the privilege is scoped to, or null/blank for a global privilege.
     * @return  True if the user is authorized.
     */
    public boolean isAuthorized(
            final String role, final Person.Id requiredUser, final String privName, final String privScopeId) {
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
            result = check(requiredPriv, privScopeId, currUser.getId());
        } else {
            result = requiredRole == null && requiredUser == null;
        }
        return result;
    }

    /**
     * All people who hold any of the named privileges within {@code scopeId} (null/blank == global),
     * de-duplicated and sorted by "last, preferred name".
     */
    public List<Person.Id> getPeopleWithPriv(final List<String> names, final String scopeId) {
        final PersonCommands people = PersonCommands.getPersonCommands();
        final String scope = blankToNull(scopeId);
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

    private List<Privilege> sorted(final List<Privilege> privileges) {
        final List<Privilege> result = new ArrayList<>(privileges);
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    // Internal: lookup by the combined DynamoDB identity. The public API never exposes this id.
    private Optional<Privilege> getPrivilegeById(final String id) {
        Optional<Privilege> priv;
        try {
            priv = dao.getPrivilege(id, Cached.NO);
        } catch (final RuntimeException ex) {
            priv = logAndReturn(ex, Optional.empty());
        }
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
