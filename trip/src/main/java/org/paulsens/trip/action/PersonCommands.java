package org.paulsens.trip.action;

import com.sun.jsft.util.ELUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.EmailAddresses;
import org.paulsens.trip.util.ScopeUtil;
import org.paulsens.trip.web.Sessions;
import org.paulsens.trip.cache.Cached;

@Slf4j
@Named("people")
@ApplicationScoped
public class PersonCommands {
    // Session Scope variable of the active user (Person.Id); NOTE: When an admin user assumes someone else's
    // identity, this variable is set to the non-admin user. The admin user's id can be found in the "aUser" key.
    public static final String ACTIVE_USER_ID = "userId";
    public static final String ACTIVE_USER_ROLE = "userRole";
    /**
     * Session key: the family member the signed-in user is currently "viewing" (Person.Id, or absent). Sticky
     * so the selection survives menu navigation. This changes only which SUBJECT person-scoped pages default
     * to -- NEVER the session identity: {@link #ACTIVE_USER_ID}, the audit actor, and chat authorship all stay
     * the signed-in user, which is what separates this from the admin View As swap.
     */
    public static final String ACTING_FOR = "actingFor";
    /**
     * Session key: who a captured card payment is credited to (set by the payByCard pages, which lose their
     * query string across the PayPal round trip). Declared here so the acting-for switch can clear it.
     */
    public static final String PAY_FOR = "payFor";

    public static PersonCommands getPersonCommands() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final PersonCommands result;
        if (ctx != null) {
            final Map<String, Object> appMap = ctx.getExternalContext().getApplicationMap();
            result = (PersonCommands) appMap.computeIfAbsent("people", key -> new PersonCommands());
        } else {
            result = new PersonCommands();
        }
        return result;
    }

    public Person createPerson() {
        return new Person();
    }

    public boolean savePerson(final Person person) {
        if (emailTakenByAnother(person)) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Email already in use",
                    "'" + person.getEmail() + "' already belongs to another person. Use a different address, "
                            + "or clear the email field.");
            return false;
        }
        boolean result;
        try {
            result = DAO.getInstance().savePerson(person);
        } catch (final RuntimeException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Error saving: " + person.getFirst()
                    + " " + person.getLast(), ex.getMessage());
            log.error("Error while saving user: ", ex);
            result = false;
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Unable to save: " + person.getFirst()
                    + " " + person.getLast(), ex.getMessage());
            log.error("Error while saving user: ", ex);
            result = false;
        }
        return result;
    }

    /**
     * The email-uniqueness funnel: a non-blank email may belong to at most one (non-deleted) person. Family
     * members made null emails legitimate, and duplicate addresses were ONLY ever prevented by the pass
     * table's primary key -- which cannot see people who never had a login. This check covers every writer
     * that goes through this bean. It is check-then-act (DynamoDB cannot enforce uniqueness), so it is a
     * guard against mistakes, not against a determined race -- and a failed LOOKUP never blocks the save,
     * because refusing all profile edits during a cache hiccup would be worse than tolerating a duplicate.
     */
    public boolean emailTakenByAnother(final Person person) {
        if (person == null || person.getEmail() == null || person.getEmail().isBlank()) {
            return false;
        }
        try {
            final Person existing = DAO.getInstance().getPersonByEmail(person.getEmail(), Cached.NO);
            return existing != null && !existing.getId().equals(person.getId());
        } catch (final RuntimeException ex) {
            log.error("Email-uniqueness lookup failed for '{}'; allowing the save.", person.getEmail(), ex);
            return false;
        }
    }

    /**
     * The display name of the person already using {@code email}, or null when it is free. Drives the
     * profile page's on-blur conflict dialog -- the same check {@link #emailTakenByAnother} makes at save
     * time, asked early so nobody types a whole profile before learning the address is taken.
     */
    public String emailConflictName(final String email, final Person.Id selfId) {
        if (!EmailAddresses.isValid(email)) {
            return null;
        }
        try {
            final Person existing = DAO.getInstance().getPersonByEmail(email.trim(), Cached.NO);
            return (existing == null || existing.getId().equals(selfId)) ? null : describeOwner(existing);
        } catch (final RuntimeException ex) {
            // Same rule as the save-time check: a failed lookup must not block the edit.
            log.error("Email-conflict lookup failed for '{}'", email, ex);
            return null;
        }
    }

    /** Enough to recognise the account without publishing a stranger's full profile to the asker. */
    private String describeOwner(final Person owner) {
        final String last = owner.getLast();
        return owner.getPreferredName() + ((last == null || last.isBlank()) ? "" : " " + last.charAt(0) + ".");
    }

    /** Whether this person can actually be mailed -- "has an email" is not the same as "has a usable one". */
    public boolean hasValidEmail(final Person person) {
        return person != null && EmailAddresses.isValid(person.getEmail());
    }

    /**
     * The per-field render states for the current viewer looking at {@code subject} -- see {@link PrivacyView}.
     * {@code adminView} is the page's own privilege verdict (site admin / tripMgr / tripFinView), because which
     * privilege grants the admin view differs per page and the page already computed it.
     */
    public PrivacyView privacyView(final Person subject, final boolean adminView) {
        return PrivacyView.of(getCurrentPerson(), subject, adminView);
    }

    /**
     * Where to reach this person for DISPLAY: their own address when it works, otherwise their family's
     * mailable managers ({@link #mailableManagers}, the same list the approval email is sent to), comma-joined
     * -- MINUS any manager who keeps their email private. Privacy hides an address from viewers; it does not
     * unsubscribe the manager from operational mail, so a private manager still receives the approval email
     * while contact lists show nothing for them. That is the one sanctioned way display and send may differ.
     *
     * @see #contactEmailVia the names to render beside it, so nobody reads a parent's address as the child's
     */
    public String contactEmail(final Person person) {
        if (person == null) {
            return "";
        }
        if (EmailAddresses.isValid(person.getEmail())) {
            return person.getEmail();
        }
        return displayableManagers(person).stream()
                .map(Person::getEmail)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** The managers whose mailboxes {@link #contactEmail} borrowed, or "" when the address is the person's own. */
    public String contactEmailVia(final Person person) {
        if (person == null || EmailAddresses.isValid(person.getEmail())) {
            return "";
        }
        return displayableManagers(person).stream()
                .map(Person::getPreferredName)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * {@link #mailableManagers} minus anyone who keeps their own email private: the borrowed-address
     * fallback is a courtesy display, never worth overriding a privacy choice for.
     */
    private List<Person> displayableManagers(final Person person) {
        return mailableManagers(person).stream()
                .filter(this::emailVisible)
                .toList();
    }

    private boolean emailVisible(final Person manager) {
        return manager.getPrivacy().isEmailVisible();
    }

    /**
     * This person's family managers that can actually be mailed: the creator first while they are still a
     * manager ({@code managerIds} keeps insertion order for the rest), skipping any address that is not
     * valid. The ONE source for "who answers for a traveler without a mailbox" -- the sending paths
     * ({@code RegistrationCommands.approvalRecipients}) and the display fallback above both read it, so
     * they cannot drift apart. Deliberately privacy-blind: privacy is a display concern, applied by
     * {@link #displayableManagers} on top.
     */
    public List<Person> mailableManagers(final Person person) {
        if (person == null || person.getFamilyId() == null) {
            return List.of();
        }
        final Family family = DAO.getInstance().getFamily(person.getFamilyId(), Cached.NO).orElse(null);
        if (family == null) {
            return List.of();
        }
        final List<Person.Id> candidates = new ArrayList<>();
        if (family.getManagerIds().contains(family.getCreatedBy())) {
            candidates.add(family.getCreatedBy());
        }
        for (final Person.Id id : family.getManagerIds()) {
            if (!candidates.contains(id)) {
                candidates.add(id);
            }
        }
        final List<Person> managers = new ArrayList<>();
        for (final Person.Id id : candidates) {
            final Person manager = DAO.getInstance().getPerson(id, Cached.NO).orElse(null);
            if (manager != null && EmailAddresses.isValid(manager.getEmail())) {
                managers.add(manager);
            }
        }
        return managers;
    }

    /** Prefix search over name/nickname/email/cell; default result cap. */
    public List<Person> searchPeople(final String query) {
        return searchPeople(query, 25);
    }

    public List<Person> searchPeople(final String query, final int limit) {
        try {
            return DAO.getInstance().searchPeople(query, limit, Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("Failed to search people for '{}'!", query, ex);
            return Collections.emptyList();
        }
    }

    /**
     * Creates a new list that is sorted according to the given EL expression. {@code loopItem} in the EL represents an
     * object in the Person list that is being sorted. The EL expression should <em>NOT</em> contain the wrapping
     * {@code #{}} around it. The comparison of the expression will be a String comparison, perhaps something to
     * enhance in the future.
     * @return A new sorted list.
     */
    public <T> List<T> toSortedList(final List<T> before, final String expression) {
        final Map<String, Object> reqMap = FacesContext.getCurrentInstance().getExternalContext().getRequestMap();
        final List<T> after = new ArrayList<>(before);
        after.sort((a, b) -> stringCompareWithExpression(reqMap, a, b, expression));
        return after;
    }

    private <T> int stringCompareWithExpression(
            final Map<String, Object> reqMap, final T a, final T b, final String exp) {
        reqMap.put("loopItem", a);
        final String aStrVal = "" + ELUtil.getInstance().eval("#{" + exp + "}");
        reqMap.put("loopItem", b);
        final String bStrVal = "" + ELUtil.getInstance().eval("#{" + exp + "}");
        return aStrVal.compareTo(bStrVal);
    }

    // Mutable on purpose: a PrimeFaces dataTable SORTS its value list in place, so an immutable
    // Stream.toList() here 500s the whole page the first time a sortable column renders.
    public List<Person> getPeopleByIds(final List<Person.Id> ids) {
        return ids == null ? new ArrayList<>()
                : ids.stream().map(this::getPerson).collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * The ids of {@code people} — for views that keep a person-list result across postbacks. viewScope is
     * serialized into the HTTP session, so a page stores the IDS of a search result and re-resolves the
     * objects each request via {@link #getPeopleByIds} (point-cached, so the resolve is a map lookup): other
     * users' Person rows never ride in someone's session blob, and a Person shape change cannot invalidate
     * live sessions. Mutable for the same reason as {@link #getPeopleByIds}.
     */
    public List<Person.Id> toIds(final List<Person> people) {
        return people == null ? new ArrayList<>()
                : people.stream().map(Person::getId).collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Candidate list for people pickers: the already-selected people first (so current selections always remain
     * valid options in the component), then search results for {@code query}. Either argument may be null;
     * {@code selected} may be a {@code List} or an array (PrimeFaces multi-selects submit arrays) of
     * {@link Person.Id} or their string values.
     */
    public List<Person> searchCandidates(final Object selected, final String query) {
        final List<Person> result = new ArrayList<>();
        selectedIds(selected).map(this::getPerson).filter(Objects::nonNull)
                .filter(p -> !result.contains(p)).forEach(result::add);
        searchPeople(query).stream().filter(p -> !result.contains(p)).forEach(result::add);
        return result;
    }

    private Stream<Person.Id> selectedIds(final Object selected) {
        final Collection<?> items;
        if (selected instanceof Collection<?> col) {
            items = col;
        } else if (selected instanceof Object[] arr) {
            items = Arrays.asList(arr);
        } else {
            items = List.of();
        }
        return items.stream().filter(Objects::nonNull)
                .map(item -> (item instanceof Person.Id pid) ? pid : Person.Id.from(String.valueOf(item)));
    }

    public Person getPerson(final Person.Id id) {
        return getPersonInternal(id, Person::new);
    }

    public Person getCurrentPerson() {
        return getPerson(ScopeUtil.getInstance().getSessionMap(ACTIVE_USER_ID));
    }

    /**
     * The subject a person-scoped page should show: an explicit {@code ?id=} always wins (page auth still
     * gates it), else the sticky acting-for selection (validated here and cleared when stale -- an unlinked
     * member must not keep haunting the session), else the signed-in user themselves.
     */
    public Person getSubject(final String idParam) {
        if (idParam != null && !idParam.isBlank()) {
            return getPerson(Person.Id.from(idParam));
        }
        final Person.Id actingFor = getActingFor();
        final Person.Id self = ScopeUtil.getInstance().getSessionMap(ACTIVE_USER_ID);
        return getPerson(actingFor != null ? actingFor : self);
    }

    /** The validated acting-for selection, or null. A selection the user may no longer access is cleared. */
    public Person.Id getActingFor() {
        final Object raw = ScopeUtil.getInstance().getSessionMap(ACTING_FOR);
        if (raw == null) {
            return null;
        }
        final Person.Id selected = (raw instanceof Person.Id pid) ? pid : Person.Id.from(raw.toString());
        if (canAccessUserId(getCurrentPerson(), selected)
                && !selected.equals(ScopeUtil.getInstance().getSessionMap(ACTIVE_USER_ID))) {
            return selected;
        }
        setSessionValue(ACTING_FOR, null);
        return null;
    }

    /**
     * Admin "View As": audit happens in the page BEFORE this call (the actor must still be the admin);
     * this snapshots-and-clears the session via {@link Sessions#pushViewAs} and reports what happened.
     */
    public boolean viewAs(final Person.Id targetId) {
        return viewAs(currentSession(), targetId);
    }

    /** Test seam: the servlet session is not reachable without a FacesContext. */
    boolean viewAs(final HttpSession session, final Person.Id targetId) {
        if (!Sessions.pushViewAs(session, targetId)) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, "Cannot view as user",
                    "Only a signed-in admin can do that.");
            return false;
        }
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO,
                "Viewing as user " + getPerson(targetId).getPreferredName(), "");
        return true;
    }

    /** Back to admin: clears the viewed-as user's accumulated state and restores the pushed snapshot. */
    public boolean endViewAs() {
        return endViewAs(currentSession());
    }

    /** Test seam: the servlet session is not reachable without a FacesContext. */
    boolean endViewAs(final HttpSession session) {
        if (!Sessions.popViewAs(session)) {
            return false;
        }
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO, "Back to admin!",
                getCurrentPerson().getPreferredName());
        return true;
    }

    private static HttpSession currentSession() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return ctx == null ? null : (HttpSession) ctx.getExternalContext().getSession(false);
    }

    /** Sets (or, for self/invalid ids, clears) the sticky selection. Refuses anyone outside the caller's reach. */
    public void actFor(final String idStr) {
        // Flow state must not leap between subjects: an abandoned payment target or a staged upload
        // belongs to the moment it was created, not to whichever member is selected next.
        setSessionValue(PAY_FOR, null);
        setSessionValue(PendingUploads.SESSION_TOKEN_KEY, null);
        setSessionValue(ProfilePhotoCommands.BG_TOKEN_KEY, null);
        if (idStr == null || idStr.isBlank()) {
            setSessionValue(ACTING_FOR, null);
            return;
        }
        final Person.Id target = Person.Id.from(idStr);
        final Object self = ScopeUtil.getInstance().getSessionMap(ACTIVE_USER_ID);
        if (target.equals(self)) {
            setSessionValue(ACTING_FOR, null);
        } else if (!canAccessUserId(getCurrentPerson(), target)) {
            // Never refuse silently: the pre-fix topbar offered every family member, and a refused
            // click navigated away still "Viewing: self" with no explanation.
            setSessionValue(ACTING_FOR, null);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, "Cannot view as "
                    + getPerson(target).getPreferredName(),
                    "Only a family manager can act for another family member.");
        } else {
            setSessionValue(ACTING_FOR, target);
        }
    }

    private static void setSessionValue(final String key, final Object value) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null) {
            return;
        }
        final Map<String, Object> session = ctx.getExternalContext().getSessionMap();
        if (value == null) {
            session.remove(key);
        } else {
            session.put(key, value);
        }
    }

    public Person getPersonByEmail(final String email) {
        try {
            return DAO.getInstance().getPersonByEmail(email, Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("Exception while trying to find person with email: " + email);
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Tests to see if {@code userId} has access to {@code reqId}.
     * @param person    The user whom is requesting access.
     * @param reqId     The id to test for access.
     * @return  {@code true} if {@code userId} can access {@code reqId}.
     */
    public boolean canAccessUserId(final Person person, final Person.Id reqId) {
        if (person == null || reqId == null) {
            return false;
        }
        return person.getId().equals(reqId) || person.getManagedUsers().contains(reqId);
    }

    /**
     * Persists which slot is THE profile picture. The write goes to a FRESH copy loaded by id -- the view's
     * copy may hold half-edited fields the person never chose to save -- and the value is then mirrored onto
     * the view's copy so its later full Save writes the same choice rather than clobbering it with whatever
     * the view loaded (the read-after-write rule: pass along what was saved, never re-read).
     */
    public boolean selectProfilePhoto(final Person viewPerson, final int slot) {
        if (viewPerson == null || viewPerson.getId() == null || slot < 1 || slot > ProfilePhotos.MAX_SLOTS) {
            return false;
        }
        final Person fresh = getPerson(viewPerson.getId());
        if (!fresh.getId().equals(viewPerson.getId())) {
            // getPerson never returns null; a blank answer carries a FRESH id, which is how "not found" shows.
            return false;
        }
        fresh.setProfilePhotoSlot(slot);
        if (!savePerson(fresh)) {
            return false;
        }
        viewPerson.setProfilePhotoSlot(slot);
        return true;
    }

    public boolean hasRole(final String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        return role.equalsIgnoreCase(ScopeUtil.getInstance().getSessionMap(ACTIVE_USER_ROLE));
    }

    /**
     * The same role check for edges that have no {@code FacesContext} -- JAX-RS resources, and any future socket.
     *
     * <p>{@link #hasRole(String)} resolves the session through {@code FacesContext}, which is a ThreadLocal that
     * only exists on a JSF request. Called from a servlet it does not merely fail, it returns {@code false}
     * silently, so a site administrator looks like an ordinary member and every privileged action is refused. Same
     * shape as the audit-actor bug, and the same remedy: read the one session key from the session itself.
     */
    public static boolean hasRole(final jakarta.servlet.http.HttpSession session, final String role) {
        if (session == null || role == null || role.isBlank()) {
            return false;
        }
        final Object actual = session.getAttribute(ACTIVE_USER_ROLE);
        return actual != null && role.equalsIgnoreCase(actual.toString());
    }

    public Person.Id id(final String id) {
        return Person.Id.from(id);
    }

    private Person getPersonInternal(final Person.Id id, final Supplier<Person> defaultPersonSupplier) {
        try {
            return DAO.getInstance().getPerson(id, Cached.YES).orElse(defaultPersonSupplier.get());
        } catch (final RuntimeException ex) {
            log.error("Failed to get person '" + id + "'!", ex);
            return defaultPersonSupplier.get();
        }
    }
}
