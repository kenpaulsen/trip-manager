package org.paulsens.trip.action;

import com.sun.jsft.util.ELUtil;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.PersonDAO;
import org.paulsens.trip.dynamo.PersonMatch;
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
    /** How many search hits a people picker offers ({@link #searchPeople(String)}, {@link #searchCandidates}). */
    private static final int PICKER_LIMIT = 25;

    private final Supplier<OrgCommands> orgSource;

    public PersonCommands() {
        this(() -> org.paulsens.trip.api.Beans.get(OrgCommands.class));
    }

    /** Test seam (the {@link TripDeleteCommands} pattern): {@code Beans.get} needs a CDI container. */
    PersonCommands(final Supplier<OrgCommands> orgSource) {
        this.orgSource = orgSource;
    }

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
        return savePerson(person, orgSource);
    }

    /**
     * {@link #savePerson(Person)} for an edge that resolves its own caller: a REST resource hands in org
     * commands built over the request's session or token, because the default ones find the caller through
     * {@code FacesContext} and would see nobody there. Only consulted when the save would change how the
     * person signs in ({@link #signInChangeRefusal}).
     */
    public boolean savePerson(final Person person, final Supplier<OrgCommands> reach) {
        final String refusal = signInChangeRefusal(person, reach);
        if (refusal != null) {
            return PageFeedback.refuse(refusal);
        }
        if (emailTakenByAnother(person)) {
            PageFeedback.error("Email already in use",
                    "'" + person.getEmail() + "' already belongs to another person. Use a different address, "
                            + "or clear the email field.");
            return false;
        }
        boolean result;
        try {
            result = DAO.getInstance().savePerson(person);
        } catch (final RuntimeException ex) {
            PageFeedback.failure("Error saving: " + person.getFirst()
                    + " " + person.getLast());
            log.error("Error while saving user: ", ex);
            result = false;
        } catch (final IOException ex) {
            PageFeedback.failure("Unable to save: " + person.getFirst()
                    + " " + person.getLast());
            log.error("Error while saving user: ", ex);
            result = false;
        }
        return result;
    }

    /** Said when the stored row could not be read to compare against: the save is refused, not waved through. */
    static final String SIGN_IN_CHECK_FAILED =
            "Not saved: this person's current record could not be read. Please try again.";

    /**
     * Why this save may not go through as it stands, or null when it may: the profile-save half of THE sign-in
     * rule ({@link OrgCommands#canChangeSignInOf}). Every person save funnels through here, and two things a
     * save can do change how somebody signs in, so each takes more than being able to edit the profile:
     *
     * <ul>
     *   <li><b>Changing the email.</b> Sign-in codes are mailed to the address on the person row, and a code
     *       redeemed at an address with no login creates one. Whoever sets the address can sign in as the
     *       person. A FIRST address may also come from the person's family manager
     *       ({@link OrgCommands#canAddEmailFor}); replacing or clearing one never.</li>
     *   <li><b>Deleting somebody who has their own login.</b> That ends an account its owner signs in to. A
     *       member a family manager created by mistake has no login and is not touched by this.</li>
     * </ul>
     *
     * <p>A person changing their own address is unaffected, and so is a brand-new person: creation has its own
     * five doors. The stored row is read fresh; a cached one could approve a stale form putting back an address
     * the person has since moved off.
     */
    public String signInChangeRefusal(final Person person, final Supplier<OrgCommands> reach) {
        if (person == null || person.getId() == null) {
            return null;
        }
        final Person stored;
        try {
            stored = DAO.getInstance().getPerson(person.getId(), Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Could not read person '{}' to check a sign-in change; refusing the save.", person.getId(), ex);
            return SIGN_IN_CHECK_FAILED;
        }
        if (stored == null) {
            return null;
        }
        final boolean allowed;
        if (person.getDeleted() != null) {
            allowed = mayEndAccount(stored, reach);
        } else if (sameAddress(stored.getEmail(), person.getEmail())) {
            allowed = true;
        } else if (isBlank(stored.getEmail())) {
            allowed = reach.get().canAddEmailFor(stored.getId());
        } else {
            allowed = reach.get().canChangeSignInOf(stored.getId());
        }
        return allowed ? null : OrgCommands.SIGN_IN_CHANGE_REFUSED;
    }

    /**
     * Whether {@link #savePerson} would accept the signed-in user DELETING this person, for a page to decide
     * before it offers Delete (or unlinks them from a family on the way to it): anyone without a login of
     * their own on the page's existing reach, somebody who signs in only under
     * {@link OrgCommands#canChangeSignInOf}. False for an id that names nobody.
     */
    public boolean canDeletePerson(final Person.Id id) {
        if (id == null) {
            return false;
        }
        try {
            return DAO.getInstance().getPerson(id, Cached.NO)
                    .map(stored -> mayEndAccount(stored, orgSource))
                    .orElse(false);
        } catch (final RuntimeException ex) {
            log.error("Could not read person '{}' to decide whether they may be deleted.", id, ex);
            return false;
        }
    }

    private static boolean mayEndAccount(final Person stored, final Supplier<OrgCommands> reach) {
        return !PassCommands.hasOwnLogin(stored) || reach.get().canChangeSignInOf(stored.getId());
    }

    /** Addresses compare the way the email index stores them: trimmed, case-folded, blank the same as none. */
    private static boolean sameAddress(final String stored, final String updated) {
        return normalized(stored).equals(normalized(updated));
    }

    private static String normalized(final String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    /**
     * {@link #savePerson} for the profile EDITOR pages: saves an EXISTING person only. A person comes into
     * being through sign-up (createAccount, where they see the privacy and legal text themselves), a family
     * manager's add-member flow, the REST API, or as a lodging contact auto-created by
     * {@code LodgingCommands.findOrCreateContact} (docs/lodging.md) -- never through an admin opening a
     * profile page. Without
     * this guard {@code /account/person.jsf?id=<unknown>} handed an admin a blank Person under a fresh id
     * (the never-null {@code getPersonForEdit} contract) whose Save created a junk row; the People page's
     * "New Person" button was exactly that path (removed 2026-09-01).
     */
    public boolean saveProfile(final Person person) {
        return saveProfile(person, getCurrentPerson(), hasRole("admin"));
    }

    /** {@link #saveProfile(Person)} with the editor named (the session lookup needs a FacesContext). */
    boolean saveProfile(final Person person, final Person editor, final boolean siteAdmin) {
        if (person == null || person.getId() == null || !personExists(person.getId())) {
            PageFeedback.error("Not saved: this profile does not exist. A person is created by signing up, not from "
                            + "a profile page.");
            return false;
        }
        if (!mayEditProfile(editor, siteAdmin, person.getId())) {
            return PageFeedback.refuse("Not saved: you can view this profile, but only the person, their family "
                    + "manager or an administrator can change it.");
        }
        return savePerson(person);
    }

    /**
     * Whether {@code editor} may CHANGE this profile: the person, whoever manages them, a site admin, or a
     * people admin of an organization they belong to ({@link OrgCommands#canAdminPerson}). A trip role is not
     * on the list on purpose: the profile page opens for a trip's managers and finance viewers so they can
     * READ a traveler's record, and until 2026-11 its Save checked nothing at all, so that read grant let any
     * trip manager overwrite anybody's profile (and fill in an empty email, handing the identity to an address
     * of their choosing).
     */
    boolean mayEditProfile(final Person editor, final boolean siteAdmin, final Person.Id subjectId) {
        return siteAdmin || canAccessUserId(editor, subjectId) || orgSource.get().canAdminPerson(subjectId);
    }

    /** {@link #mayEditProfile} for the signed-in user: the profile page shows its Save button under it. */
    public boolean canEditProfile(final Person.Id subjectId) {
        return subjectId != null && mayEditProfile(getCurrentPerson(), hasRole("admin"), subjectId);
    }

    /**
     * Whether a person row is stored under this id: a fresh read through {@link #getPersonForEdit}, whose
     * miss answer carries a FRESH id (the selectProfilePhoto idiom) -- so a failed read also reads as absent.
     */
    private boolean personExists(final Person.Id id) {
        return getPersonForEdit(id).getId().equals(id);
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
     * A person picker's label on Manage People, "Last, First - email / cell": the email and cell are added only
     * where the current viewer may see them ({@link PrivacyView} not {@code HIDE}). A list item cannot be masked,
     * so a value is either in the label or absent. {@code adminView} is true because the page is a people-admin
     * page and its search ({@link #adminSearchPeople}) returns only people the viewer administers.
     */
    public String adminPickerLabel(final Person person) {
        if (person == null) {
            return "";
        }
        final PrivacyView view = privacyView(person, true);
        final String email = (view.getEmail() == PrivacyView.State.HIDE) ? null : person.getEmail();
        final String cell = (view.getCell() == PrivacyView.State.HIDE) ? null : person.getCell();
        final String contact = Stream.of(email, cell).filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" / "));
        final String name = person.getLast() + ", " + person.getFirst();
        return contact.isEmpty() ? name : name + " - " + contact;
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
        return managersOf(person).stream().filter(manager -> EmailAddresses.isValid(manager.getEmail())).toList();
    }

    /**
     * This person's family managers, resolved and in the same order as {@link #mailableManagers} (creator
     * first while still a manager), WITHOUT the address filter: the push route reaches a manager's phone
     * whether or not they have a mailbox. Empty for someone in no family.
     */
    public List<Person> managersOf(final Person person) {
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
            DAO.getInstance().getPerson(id, Cached.NO).ifPresent(managers::add);
        }
        return managers;
    }

    /**
     * The one-line label a person picker shows, EMPTY for no selection.
     *
     * <p>An autocomplete evaluates its {@code itemLabel} for the current value too, not just for the
     * suggestion rows, and a composite expression there renders its punctuation against a null item: the
     * admin Organizations picker came up pre-filled with a literal "  ()", which every keystroke then
     * appended to, so the search could never match anything. A method that returns "" for null keeps the
     * informative label on the rows and an empty box when nothing is picked.
     */
    public String pickerLabel(final Person person) {
        if (person == null) {
            return "";
        }
        final String name = (person.getPreferredName() + " " + person.getLast()).trim();
        return EmailAddresses.isValid(person.getEmail()) ? name + " (" + person.getEmail() + ")" : name;
    }

    /**
     * {@link #pickerLabel} for an autocomplete whose VALUE is a person's id string rather than a Person (the
     * content dialog's PERSON prompt stores ids in a string map): PrimeFaces evaluates {@code itemLabel} with
     * {@code var} bound to the raw current value, so the label must accept either shape -- a Person for the
     * suggestion rows, the stored id for the box. An id that names nobody shows as itself, so a dangling
     * reference stays visible to the editor instead of reading as "nothing picked".
     */
    public String pickerLabelFor(final Object personOrId) {
        if (personOrId instanceof Person person) {
            return pickerLabel(person);
        }
        if (!(personOrId instanceof String id) || id.isBlank()) {
            return "";
        }
        try {
            return DAO.getInstance().getPerson(Person.Id.from(id.trim()), Cached.YES)
                    .map(this::pickerLabel).orElse(id);
        } catch (final RuntimeException ex) {
            log.error("Unable to label person '{}'", id, ex);
            return id;
        }
    }

    /** People search ({@link PersonMatch}); default result cap. */
    public List<Person> searchPeople(final String query) {
        return searchPeople(query, PICKER_LIMIT);
    }

    /**
     * The directory search behind the pickers. A site admin may search by every field (they see private ones
     * masked); anyone else only by the cell and email a subject shares with every signed-in user, so a picker
     * cannot be used to test digits against a private number ({@link PersonMatch.Reach}). The role is read from
     * the JSF session, so a caller with none (REST, a script) gets the shared reach: the safe side.
     */
    public List<Person> searchPeople(final String query, final int limit) {
        return searchPeople(query, limit, hasRole("admin") ? PersonMatch.EVERY_FIELD : PersonMatch.SHARED_FIELDS);
    }

    /** {@link #searchPeople(String, int)} with the reach decided by a caller that knows its viewer (REST). */
    public List<Person> searchPeople(final String query, final int limit, final PersonMatch.Reach reach) {
        try {
            return DAO.getInstance().searchPeople(query, limit, Cached.NO, reach).people();
        } catch (final RuntimeException ex) {
            log.error("Failed to search people for '{}'!", query, ex);
            return Collections.emptyList();
        }
    }

    /**
     * People search restricted to subjects the caller may administer ({@code OrgCommands.canAdminPerson}:
     * site admins see everyone; org-scoped {@code peopleAdmin} holders see their orgs' people only). The
     * admin people pages and their pickers use THIS, never {@link #searchPeople}, so tenancy is enforced in
     * the data source rather than per page. See {@link #adminSearch} for how each kind of caller is searched.
     */
    public List<Person> adminSearchPeople(final String query, final int limit) {
        return adminSearch(query, limit).rows();
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
     * The most rows any people list renders (trip Contacts, Org People, the admin People search): with no query
     * a list shows everyone only when its candidates fit; past this a page asks for a search instead of
     * rendering a few hundred rows, each with its own privacy and balance lookups.
     */
    public static final int PEOPLE_LIST_LIMIT = 60;

    /** {@link #PEOPLE_LIST_LIMIT}, for the pages' copy. */
    public int getPeopleListLimit() {
        return PEOPLE_LIST_LIMIT;
    }

    /**
     * THE people-list rule, shared by every page that lists a known set of people: a blank query lists every
     * candidate when they fit {@link #PEOPLE_LIST_LIMIT} and nobody ({@link PeopleResult#needsQuery}) when they
     * do not; otherwise the candidates matching every query word ({@link PersonMatch}), searching cell and email
     * only as far as {@code reach} lets this viewer see them. Rows are always in {@link Person#PEOPLE_SORTER}
     * order (last, first) and capped at the limit, with {@code truncated} saying more matched than shown.
     */
    public PeopleResult listOrSearch(
            final Collection<Person> candidates, final String query, final PersonMatch.Reach reach) {
        return listOrSearch(candidates, query, reach, PEOPLE_LIST_LIMIT);
    }

    static PeopleResult listOrSearch(final Collection<Person> candidates, final String query,
            final PersonMatch.Reach reach, final int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return PeopleResult.NONE;
        }
        final List<String> words = PersonMatch.queryWords(query);
        if (words.isEmpty() && candidates.size() > limit) {
            return PeopleResult.NEEDS_QUERY;
        }
        return PeopleResult.capped(candidates.stream()
                .filter(person -> words.isEmpty() || PersonMatch.matchesAll(person, words, reach))
                .sorted(Person.PEOPLE_SORTER)
                .toList(), limit);
    }

    /** The admin People page's search: {@link #adminSearch(String, int)} at {@link #PEOPLE_LIST_LIMIT}. */
    public PeopleResult adminSearch(final String query) {
        return adminSearch(query, PEOPLE_LIST_LIMIT);
    }

    /**
     * The admin People search. A blank query always asks for one (the directory never fits the limit).
     *
     * <p>A site admin searches the whole directory index, whose window is read in token order, so a short common
     * word can fill it: the result is then marked {@code truncated} even when fewer rows survive, and the page
     * asks for a narrower search instead of presenting a partial list as complete.</p>
     *
     * <p>Anyone else searches, in memory, exactly the members of the orgs where they hold {@code peopleAdmin}
     * (the people {@code canAdminPerson} admits). Filtering a site-wide window down to their orgs instead could
     * miss every one of their own matches (review finding, 2026-11-12). Measured locally at about 7 ms for a
     * 1,000-member org and 45 ms at 10,000, against 25 ms for the old window search that missed them.</p>
     */
    public PeopleResult adminSearch(final String query, final int limit) {
        return adminSearch(query, limit, orgSource);
    }

    /**
     * {@link #adminSearch(String, int)} for an edge that resolves its own caller (a REST resource hands in org
     * commands built over the request's session or token, as {@link #savePerson(Person, Supplier)} takes them),
     * so the website's People search and {@code GET /api/people} are one search.
     */
    public PeopleResult adminSearch(final String query, final int limit, final Supplier<OrgCommands> reach) {
        if (PersonMatch.queryWords(query).isEmpty()) {
            return PeopleResult.NEEDS_QUERY;
        }
        final OrgCommands orgs = reach.get();
        if (!orgs.isSiteAdmin()) {
            return listOrSearch(orgs.peopleAdminScope(), query, PersonMatch.EVERY_FIELD, limit);
        }
        try {
            final PersonDAO.Search found =
                    DAO.getInstance().searchPeople(query, limit, Cached.NO, PersonMatch.EVERY_FIELD);
            return new PeopleResult(
                    found.people(), false, found.cut());
        } catch (final RuntimeException ex) {
            log.error("Failed to search people for '{}'!", query, ex);
            return PeopleResult.NONE;
        }
    }

    /**
     * The trip Contacts page's rows, by {@link #listOrSearch} over the trip's roster. Cell and email are searched
     * only where THIS viewer may see them on the page ({@link PrivacyView} with the page's own {@code adminView}):
     * a co-traveler must not be able to find someone by digits of a number the page hides from them. A roster too
     * big to list is answered before resolving anyone.
     */
    public PeopleResult contactsSearch(final List<Person.Id> roster, final String query, final boolean adminView) {
        if (roster == null || roster.isEmpty()) {
            return PeopleResult.NONE;
        }
        if (PersonMatch.queryWords(query).isEmpty() && roster.size() > PEOPLE_LIST_LIMIT) {
            return PeopleResult.NEEDS_QUERY;
        }
        return listOrSearch(existingPeople(roster), query, PrivacyView.searchReach(getCurrentPerson(), adminView));
    }

    /**
     * The given ids as people in {@link Person#PEOPLE_SORTER} order, skipping ids that name nobody -- for the
     * uncapped roster reports (emails, emergency contacts, pilgrims, ages), which freeze the ids in the view.
     */
    public List<Person.Id> sortedIds(final Collection<Person.Id> ids) {
        if (ids == null) {
            return new ArrayList<>();
        }
        return toIds(existingPeople(ids).stream().sorted(Person.PEOPLE_SORTER).toList());
    }

    /** The people these ids name, skipping any that name nobody (unlike {@link #getPerson}'s blank person). */
    private List<Person> existingPeople(final Collection<Person.Id> ids) {
        return ids.stream()
                .map(id -> getPersonInternal(id, () -> null))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Candidate list for people pickers: the already-selected people first (so current selections always remain
     * valid options in the component), then search results for {@code query}. Either argument may be null;
     * {@code selected} may be a {@code List} or an array (PrimeFaces multi-selects submit arrays) of
     * {@link Person.Id} or their string values.
     */
    public List<Person> searchCandidates(final Object selected, final String query) {
        return searchCandidatesOnTrip(selected, query, null);
    }

    /**
     * {@link #searchCandidates} for a picker on a page that belongs to one trip (Batch Transactions, the trip's
     * to-do's): the search half stays inside that trip's organization ({@code OrgCommands.pickerScope}). A null
     * or unknown trip searches the caller's own organizations.
     */
    public List<Person> searchCandidatesOnTrip(final Object selected, final String query, final String tripId) {
        final List<Person> result = new ArrayList<>();
        selectedIds(selected).map(this::getPerson).filter(Objects::nonNull)
                .filter(p -> !result.contains(p)).forEach(result::add);
        pickerSearch(query, tripId).stream().filter(p -> !result.contains(p)).forEach(result::add);
        return result;
    }

    /**
     * The search half of {@link #searchCandidatesOnTrip}. The picker is on pages a non-site-admin reaches (a
     * trip's Finance Admin on Batch Transactions), and the site-wide search matches email and cell across every
     * account: unbounded, it mapped any tenant's address or phone number to a name and let that person be
     * selected. So anyone but a site admin searches, in memory, exactly the people the page may offer them
     * ({@code OrgCommands.pickerScope}), by name and by what each person shares with everyone -- the way
     * {@link #adminSearch} searches an org people admin's members, and for the same reason: filtering a window of
     * the site-wide index down to those people can miss every one of them.
     */
    private List<Person> pickerSearch(final String query, final String tripId) {
        if (PersonMatch.queryWords(query).isEmpty()) {
            return List.of();
        }
        final OrgCommands orgCommands = orgSource.get();
        if (orgCommands.isSiteAdmin()) {
            return searchPeople(query, PICKER_LIMIT);
        }
        return listOrSearch(orgCommands.pickerScope(tripId), query, PersonMatch.SHARED_FIELDS, PICKER_LIMIT).rows();
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

    /**
     * The person an EDIT form seeds its working copy from, always read fresh: the form's copy becomes the
     * save payload wholesale, so seeding it from the near-cache would let a stale copy overwrite fields
     * somebody else just changed. Display lookups stay on {@link #getPerson}.
     */
    public Person getPersonForEdit(final Person.Id id) {
        try {
            return DAO.getInstance().getPerson(id, Cached.NO).orElseGet(Person::new);
        } catch (final RuntimeException ex) {
            log.error("Failed to get person '" + id + "' for editing!", ex);
            return new Person();
        }
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
        return getPerson(subjectId(idParam));
    }

    /** {@link #getSubject} for the profile EDITOR: same subject resolution, fresh read (see getPersonForEdit). */
    public Person getSubjectForEdit(final String idParam) {
        return getPersonForEdit(subjectId(idParam));
    }

    private Person.Id subjectId(final String idParam) {
        if (idParam != null && !idParam.isBlank()) {
            return Person.Id.from(idParam);
        }
        final Person.Id actingFor = getActingFor();
        return actingFor != null ? actingFor : ScopeUtil.getInstance().getSessionMap(ACTIVE_USER_ID);
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
            PageFeedback.warn("Cannot view as user",
                    "Only a signed-in admin can do that.");
            return false;
        }
        PageFeedback.info("Viewing as user " + getPerson(targetId).getPreferredName());
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
        PageFeedback.info("Back to admin!",
                getCurrentPerson().getPreferredName());
        return true;
    }

    /**
     * Whether this session is an administrator viewing as somebody ({@link Sessions#isViewAs}): the one
     * spelling for pages, which hide the passkey and signed-in-devices fieldsets on it. Those manage the
     * session person's own sign-in, and during View As that person is the one being viewed.
     */
    public boolean isViewingAs() {
        return Sessions.isViewAs(currentSession());
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
            PageFeedback.warn("Cannot view as "
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
        final Person fresh = getPersonForEdit(viewPerson.getId());
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

    /**
     * {@link #selectProfilePhoto} for the profile page: only somebody who may EDIT the profile chooses its
     * picture ({@link #mayEditProfile}, the rule its Save follows). A trip's staff read a traveler's profile and
     * were still offered the thumbnails, whose click changed the traveler's picture everywhere it is shown. The
     * REST door authorizes its own subject and calls {@link #selectProfilePhoto} directly.
     */
    public boolean chooseProfilePhoto(final Person viewPerson, final int slot) {
        return chooseProfilePhoto(viewPerson, slot, getCurrentPerson(), hasRole("admin"));
    }

    boolean chooseProfilePhoto(final Person viewPerson, final int slot, final Person editor,
            final boolean siteAdmin) {
        if (viewPerson == null || viewPerson.getId() == null
                || !mayEditProfile(editor, siteAdmin, viewPerson.getId())) {
            return PageFeedback.refuse("Not changed: you can view this profile, but only the person, their family "
                    + "manager or an administrator can change its picture.");
        }
        return selectProfilePhoto(viewPerson, slot);
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
