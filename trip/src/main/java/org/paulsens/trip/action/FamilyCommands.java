package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.EmailAddresses;
import org.paulsens.trip.util.Util;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import org.paulsens.trip.cache.Cached;

/**
 * Family accounts: one login owning several full Person profiles. This bean is the ONLY writer of family
 * structure, and that is a security boundary, not a convenience -- {@code Person.managedUsers} grants access to
 * passports, so self-service must never accept an arbitrary existing person id. Members are created here
 * (create-and-link, atomic from the caller's point of view) or linked by an admin; nothing else.
 *
 * <p>The {@link Family} row is the source of truth. Every manager's {@code managedUsers} list is derived from
 * it by SURGICAL deltas (add/remove specific ids, never wholesale replacement), so grants an admin made outside
 * any family survive every family operation. {@link #resyncFamily} is the idempotent repair if a multi-write
 * sequence dies partway: additive-only, because a leftover entry cannot be distinguished from a legitimate
 * admin grant (report, don't remove).
 *
 * <p>Write order everywhere: the person row that must exist first, then the versioned family put, then the
 * derived person writes computed from the IN-MEMORY objects just saved (a re-read can be stale in production),
 * then audit. A lost optimistic-version race surfaces as "family changed, retry" -- never retried silently,
 * because the caller's deltas were computed against the losing row.
 */
@Slf4j
@Named("family")
@ApplicationScoped
public class FamilyCommands {
    private final ConfigCommands config;
    private final AuditCommands audit;
    private final java.util.function.Supplier<Caller> callerSource;
    private final java.util.function.Supplier<OrgCommands> orgSource;

    public FamilyCommands() {
        this(new ConfigCommands(), new AuditCommands(), Caller::current);
    }

    /**
     * Test seam (the {@code ChatCommands} pattern): collaborators handed in, no container needed. The caller
     * supplier exists because {@code Caller.current()} needs a FacesContext, which unit tests do not have.
     * The org supplier shares the SAME caller source, so the admin-link reach check answers for the caller
     * this instance was built for.
     */
    public FamilyCommands(final ConfigCommands config, final AuditCommands audit,
            final java.util.function.Supplier<Caller> callerSource) {
        this.config = config;
        this.audit = audit;
        this.callerSource = callerSource;
        this.orgSource = () -> new OrgCommands(callerSource);
    }

    private Caller caller() {
        return callerSource.get();
    }

    // ------------------------------------------------------------------ reads

    /** The signed-in person's family, or null when they have none (the common case). */
    public Family getMyFamily() {
        return familyOf(currentPerson());
    }

    public Family familyOf(final Person person) {
        if (person == null || person.getFamilyId() == null) {
            return null;
        }
        final Family family = DAO.getInstance().getFamily(person.getFamilyId(), Cached.NO).orElse(null);
        if (family == null) {
            log.warn("Person {} points at family {} which does not exist.",
                    person.getId(), person.getFamilyId());
        }
        return family;
    }

    /** Whether the signed-in person manages a family (drives the family page's edit affordances). */
    public boolean isMyFamilyManager() {
        final Person me = currentPerson();
        final Family family = familyOf(me);
        return family != null && me != null && family.isManager(me.getId());
    }

    /**
     * Whether the signed-in user may manage THIS family's page affordances: one of its managers, or a site
     * admin (the write paths below already accept site admins; this lets the page render the same reach).
     */
    public boolean canManage(final Family family) {
        final Person me = currentPerson();
        if (family == null || me == null) {
            return false;
        }
        return managerOrAdmin(family, me);
    }

    /** The write gate on a family: one of its managers, or a site administrator. Neither argument is null. */
    private boolean managerOrAdmin(final Family family, final Person me) {
        return family.isManager(me.getId()) || caller().isSiteAdmin();
    }

    /** {@link #isAtLimit()} for an explicit family (the subject-aware family page). Distinct name for EL. */
    public boolean isFamilyAtLimit(final Family family) {
        return family != null && family.getSize() >= getMaxMembers();
    }

    /** Resolved members of this family, the signed-in person first, then the family row's order. */
    public List<Person> getMembers(final Family family) {
        final List<Person> result = new ArrayList<>();
        if (family == null) {
            return result;
        }
        final Person me = currentPerson();
        if (me != null && family.isMember(me.getId())) {
            result.add(me);
        }
        for (final Person.Id memberId : family.getMemberIds()) {
            if (me != null && memberId.equals(me.getId())) {
                continue;
            }
            final Person member = DAO.getInstance().getPerson(memberId, Cached.NO).orElse(null);
            if (member != null) {
                result.add(member);
            }
        }
        return result;
    }

    public int getMaxMembers() {
        return config.getInt(KnownSettings.FAMILY_MAX_MEMBERS, 1, 100);
    }

    public boolean isAtLimit() {
        final Family family = getMyFamily();
        return family != null && family.getSize() >= getMaxMembers();
    }

    /**
     * Profile fields a traveler still needs to fill in -- one definition driving the family page badges and the
     * registration confirmation checklist.
     */
    public List<String> missingProfileFields(final Person person) {
        return missingProfileFields(person, null);
    }

    /**
     * {@link #missingProfileFields(Person)} for a registration on {@code trip}: a conference is not travel, so its
     * checklist never asks for a passport (birthdate still matters, it prices the ticket). A null trip is the
     * travel checklist, which is what the family page shows.
     */
    public List<String> missingProfileFields(final Person person, final Trip trip) {
        final List<String> missing = new ArrayList<>();
        if (person == null) {
            return missing;
        }
        if (person.getBirthdate() == null) {
            missing.add(Text.get("common.birthdate"));
        }
        if (trip != null && trip.isConference()) {
            if (isBlank(person.getEmergencyContactName())) {
                missing.add(Text.get("feedback.familyCommands.fieldEmergencyContact"));
            }
            return missing;
        }
        if (isBlank(person.getPassport().getNumber())) {
            missing.add(Text.get("feedback.familyCommands.fieldPassportNumber"));
        } else if (person.getPassport().getExpires() == null) {
            missing.add(Text.get("feedback.familyCommands.fieldPassportExpiration"));
        }
        if (isBlank(person.getEmergencyContactName())) {
            missing.add(Text.get("feedback.familyCommands.fieldEmergencyContact"));
        }
        return missing;
    }

    /**
     * Why this member cannot be deleted by a manager, or null when deletion is allowed. Registrations have no
     * person-side index, so trip membership is the proxy (a registration is only created for a trip the person
     * is on); soft-deleted transactions still block -- financial history must survive.
     *
     * <p>Before either: the delete is for a member somebody created by mistake, so it never reaches anyone who
     * signs in for themselves ({@link #ownAccountBlock}). The family page and the REST
     * family resource both hide Delete on this answer, so one rule covers the button and the write.
     */
    public String deleteBlockReason(final Person.Id memberId) {
        final String ownAccount = ownAccountBlock(memberId);
        if (ownAccount != null) {
            return ownAccount;
        }
        return historyBlock(memberId);
    }

    /**
     * Why this member is somebody a manager may not delete whatever their history: they have a login of their
     * own. Deleting the profile ends that person's account, and one adult deleting another's -- a spouse who
     * signed up and was linked in by an admin, with no trip yet -- is not what "remove a family member" means.
     * Those go to an admin, who UNLINKS and deletes nothing. The LOGIN is the test, not the manager flag: a
     * co-manager added a moment ago with a mistyped address has never signed in, has no account to lose, and
     * the manager who added them must be able to take that back.
     */
    private String ownAccountBlock(final Person.Id memberId) {
        final Person member = memberId == null ? null : DAO.getInstance().getPerson(memberId, Cached.NO).orElse(null);
        if (member == null) {
            return null;
        }
        return PassCommands.hasOwnLogin(member)
                ? Text.get("feedback.familyCommands.hasOwnAccount") : null;
    }

    private String historyBlock(final Person.Id memberId) {
        if (!DAO.getInstance().getTripsForUser(memberId, Cached.NO).isEmpty()) {
            return Text.get("feedback.familyCommands.hasRegistrations");
        }
        if (!DAO.getInstance().getTransactions(memberId, Cached.NO).isEmpty()) {
            return Text.get("feedback.familyCommands.hasFinancialHistory");
        }
        return null;
    }

    // ------------------------------------------------------------------ self-service writes

    /**
     * The EL-friendly face of {@link #createFamilyMember}: pages bind the sex menu to a plain String (EL does
     * not coerce enums), and a DIFFERENT name avoids overload ambiguity in EL method resolution.
     */
    public Person addFamilyMember(final String first, final String last, final LocalDate birthdate,
            final String sex, final String email, final boolean manager) {
        final Person.Sex parsed = isBlank(sex) ? null : Person.Sex.valueOf(sex);
        return createFamilyMember(first, last, birthdate, parsed, email, manager);
    }

    /**
     * {@link #addFamilyMember} anchored on another person's family -- the subject-aware family page: admins
     * (and managers acting for a member) arrive with {@code ?id=} and must grow THAT family, not their own.
     * A blank or self subjectId behaves exactly like {@link #addFamilyMember}.
     */
    public Person addFamilyMemberFor(final String subjectId, final String first, final String last,
            final LocalDate birthdate, final String sex, final String email, final boolean manager) {
        final Person.Sex parsed = isBlank(sex) ? null : Person.Sex.valueOf(sex);
        return report(addFamilyMemberForOutcome(subjectId, first, last, birthdate, parsed, email, manager))
                .member();
    }

    /**
     * How a family write went. {@code code} is null on success; otherwise one of the constants below, with
     * the page's own headline and explanation so a refusal reads the same on every edge. The REST family
     * resource maps the codes onto statuses; the page growls {@code summary: detail}.
     */
    public record FamilyResult(String code, String summary, String detail, Person member, Family family) {

        public static final String NOT_SIGNED_IN = "NOT_SIGNED_IN";
        public static final String NOT_ALLOWED = "NOT_ALLOWED";
        public static final String NOT_MANAGER = "NOT_MANAGER";
        public static final String NAME_REQUIRED = "NAME_REQUIRED";
        public static final String SEX_REQUIRED = "SEX_REQUIRED";
        public static final String BIRTHDATE_REQUIRED = "BIRTHDATE_REQUIRED";
        public static final String BIRTHDATE_FUTURE = "BIRTHDATE_FUTURE";
        public static final String MANAGER_NEEDS_EMAIL = "MANAGER_NEEDS_EMAIL";
        public static final String FAMILY_FULL = "FAMILY_FULL";
        public static final String EMAIL_IN_USE = "EMAIL_IN_USE";
        public static final String NO_FAMILY = "NO_FAMILY";
        public static final String CANNOT_DELETE_SELF = "CANNOT_DELETE_SELF";
        public static final String HAS_HISTORY = "HAS_HISTORY";
        public static final String HAS_OWN_ACCOUNT = "HAS_OWN_ACCOUNT";
        public static final String LAST_MANAGER = "LAST_MANAGER";
        public static final String FAMILY_CHANGED = "FAMILY_CHANGED";
        public static final String STORE_FAILED = "STORE_FAILED";

        static FamilyResult ok(final Person member, final Family family) {
            return new FamilyResult(null, null, null, member, family);
        }

        static FamilyResult refused(final String code, final String summary, final String detail) {
            return new FamilyResult(code, summary, detail, null, null);
        }

        public boolean isOk() {
            return code == null;
        }

        /** The one-line form the page shows ({@code summary: detail}). */
        public String message() {
            return PageFeedback.compose(summary, detail);
        }
    }

    /**
     * {@link #addFamilyMemberFor} with the outcome returned rather than growled, and the sex already parsed
     * (the REST edge validates it before calling; the page's String form parses on its way here).
     */
    public FamilyResult addFamilyMemberForOutcome(final String subjectId, final String first, final String last,
            final LocalDate birthdate, final Person.Sex sex, final String email, final boolean manager) {
        final Person me = currentPerson();
        if (me == null) {
            return FamilyResult.refused(FamilyResult.NOT_SIGNED_IN, Text.get("feedback.shared.notSignedIn"),
                    Text.get("feedback.familyCommands.signInToManage"));
        }
        final Person anchor = resolveAnchor(me, subjectId);
        if (anchor == null) {
            return FamilyResult.refused(FamilyResult.NOT_ALLOWED, Text.get("feedback.shared.notAllowed"),
                    Text.get("feedback.familyCommands.cannotManageThatFamily"));
        }
        return createOutcome(anchor, first, last, birthdate, sex, email, manager);
    }

    /** Growls a refusal the way the page always has; the outcome passes through for the caller's use. */
    private FamilyResult report(final FamilyResult outcome) {
        if (!outcome.isOk()) {
            fail(outcome.summary(), outcome.detail());
        }
        return outcome;
    }

    /** The family anchor a subjectId resolves to: me for blank/self; otherwise access-checked, null if denied. */
    private Person resolveAnchor(final Person me, final String subjectId) {
        if (isBlank(subjectId) || me.getId().getValue().equals(subjectId)) {
            return me;
        }
        final Person subject = DAO.getInstance().getPerson(Person.Id.from(subjectId), Cached.NO).orElse(null);
        if (subject == null) {
            return null;
        }
        final boolean allowed = caller().isSiteAdmin()
                || PersonCommands.getPersonCommands().canAccessUserId(me, subject.getId());
        return allowed ? subject : null;
    }

    /**
     * Create-and-link: the one self-service way a person enters a family. The new Person is created HERE, so a
     * non-admin can never link an id they did not just create. Returns the new member, or null with a faces
     * message explaining the refusal.
     */
    public Person createFamilyMember(final String first, final String last, final LocalDate birthdate,
            final Person.Sex sex, final String email, final boolean manager) {
        return report(createOutcome(currentPerson(), first, last, birthdate, sex, email, manager)).member();
    }

    private FamilyResult createOutcome(final Person anchor, final String first, final String last,
            final LocalDate birthdate, final Person.Sex sex, final String email, final boolean manager) {
        final Person me = currentPerson();
        if (me == null || anchor == null) {
            return FamilyResult.refused(FamilyResult.NOT_SIGNED_IN, Text.get("feedback.shared.notSignedIn"),
                    Text.get("feedback.familyCommands.signInToManage"));
        }
        if (isBlank(first) || isBlank(last)) {
            return FamilyResult.refused(FamilyResult.NAME_REQUIRED, Text.get("feedback.familyCommands.nameRequired"),
                    Text.get("feedback.familyCommands.needsFirstAndLast"));
        }
        if (sex == null) {
            return FamilyResult.refused(FamilyResult.SEX_REQUIRED, Text.get("feedback.familyCommands.sexRequired"),
                    Text.get("feedback.familyCommands.chooseSex", first));
        }
        if (birthdate == null) {
            return FamilyResult.refused(FamilyResult.BIRTHDATE_REQUIRED,
                    Text.get("feedback.familyCommands.birthdateRequired"),
                    Text.get("feedback.familyCommands.enterBirthdate", first));
        }
        if (birthdate.isAfter(LocalDate.now())) {
            return FamilyResult.refused(FamilyResult.BIRTHDATE_FUTURE,
                    Text.get("feedback.familyCommands.birthdateInFuture"),
                    Text.get("feedback.familyCommands.checkBirthdate", first));
        }
        if (manager && !EmailAddresses.isValid(email)) {
            // A manager signs in and acts for the family; without their own WORKING address they cannot.
            // Validity, not merely presence: "foo" is an email field with no mailbox behind it, and it
            // would satisfy a blank check while still leaving them unable to sign in or be mailed.
            // Checked before ensureFamilyFor so a refusal writes nothing, not even an empty family row.
            return FamilyResult.refused(FamilyResult.MANAGER_NEEDS_EMAIL,
                    Text.get("feedback.familyCommands.managerNeedsEmail"),
                    Text.get("feedback.familyCommands.managerNeedsOwnEmail", first));
        }
        Family family = familyOf(anchor);
        if (family != null && !managerOrAdmin(family, me)) {
            return FamilyResult.refused(FamilyResult.NOT_MANAGER, Text.get("feedback.family.notAManager"),
                    Text.get("feedback.familyCommands.onlyManagerAdds"));
        }
        if (family == null) {
            family = createdFamilyFor(anchor);
            if (family == null) {
                return FamilyResult.refused(FamilyResult.STORE_FAILED, Text.get("feedback.shared.unableToSave"),
                        Text.get("feedback.familyCommands.familyNotCreated"));
            }
        }
        if (family.getSize() >= getMaxMembers()) {
            return FamilyResult.refused(FamilyResult.FAMILY_FULL, Text.get("feedback.familyCommands.familyFull"),
                    Text.get("feedback.familyCommands.yourFamilyAtLimit", getMaxMembers()));
        }
        final String cleanEmail = Util.trimToNull(email);
        if (cleanEmail != null && emailInUse(cleanEmail, null)) {
            return FamilyResult.refused(FamilyResult.EMAIL_IN_USE, Text.get("feedback.personCommands.emailInUse"),
                    Text.get("feedback.familyCommands.anotherPersonUses", cleanEmail));
        }

        final Person member = Person.builder().first(first).last(last).birthdate(birthdate).sex(sex).build();
        member.setEmail(cleanEmail);
        member.setFamilyId(family.getId());
        if (!savePersonOrWarn(member)) {
            return FamilyResult.refused(FamilyResult.STORE_FAILED, Text.get("feedback.shared.unableToSave"),
                    Text.get("feedback.familyCommands.memberNotSaved"));
        }
        family.getMemberIds().add(member.getId());
        if (manager) {
            family.getManagerIds().add(member.getId());
        }
        final FamilyResult notSaved = saveFamilyResult(family);
        if (notSaved != null) {
            return notSaved;
        }
        syncMemberAdded(family, member, manager);
        tenantIntoAnchorsOrgs(anchor, member);
        audit.family(member, AuditCommands.describe(me) + " added " + AuditCommands.describe(member)
                + " to their family" + (manager ? " as a family manager." : "."));
        return FamilyResult.ok(member, family);
    }

    /**
     * A brand-new member joins every organization the anchor belongs to. A person in no org is invisible to
     * org-scoped reads and {@code TripsResource.applyPeople} silently drops them from a roster, which is how a
     * family-created child could never be seated on a trip (found 2026-10-10). The anchor is re-read: the
     * object in hand may predate an org join made earlier in this request. A refused or failed write is
     * logged, not fatal: the member exists and is linked, and the org-people page can still add them.
     */
    private void tenantIntoAnchorsOrgs(final Person anchor, final Person member) {
        final Person fresh = DAO.getInstance().getPerson(anchor.getId(), Cached.NO).orElse(anchor);
        for (final Organization.Id orgId : fresh.getOrgIds()) {
            if (!orgSource.get().addCreatedFamilyMember(orgId.getValue(), member.getId(), fresh)) {
                log.warn("Family member {} was not added to organization {}", member.getId(), orgId.getValue());
            }
        }
    }

    /**
     * Delete (soft) a member a manager created by mistake -- allowed only while the member has no history at
     * all and no login of their own ({@link #deleteBlockReason}). Anyone else
     * goes through an admin unlink instead.
     */
    public boolean deleteFamilyMember(final Person.Id memberId) {
        return report(deleteFamilyMemberOutcome(memberId)).isOk();
    }

    /** {@link #deleteFamilyMember} with the outcome returned rather than growled. */
    public FamilyResult deleteFamilyMemberOutcome(final Person.Id memberId) {
        final Person me = currentPerson();
        final Person member = memberId == null ? null : DAO.getInstance().getPerson(memberId, Cached.NO).orElse(null);
        // The MEMBER's family, not the caller's: for a manager they are the same, and anchoring here is
        // what lets a site admin work the subject-aware family page (?id=) for someone else's household.
        final Family family = familyOf(member);
        if (me == null || family == null) {
            return FamilyResult.refused(FamilyResult.NO_FAMILY, Text.get("feedback.familyCommands.noFamily"),
                    Text.get("feedback.familyCommands.notInYourFamily"));
        }
        if (!managerOrAdmin(family, me)) {
            return FamilyResult.refused(FamilyResult.NOT_MANAGER, Text.get("feedback.family.notAManager"),
                    Text.get("feedback.familyCommands.onlyManagerRemoves"));
        }
        if (me.getId().equals(memberId)) {
            return FamilyResult.refused(FamilyResult.CANNOT_DELETE_SELF,
                    Text.get("feedback.familyCommands.cannotDeleteYourself"),
                    Text.get("feedback.familyCommands.cannotDeleteOwnProfile"));
        }
        // Site admins included: for somebody with their own login the admin path is the UNLINK on Manage
        // People, which removes them from the family and deletes nothing.
        final String ownAccount = ownAccountBlock(memberId);
        if (ownAccount != null) {
            return FamilyResult.refused(FamilyResult.HAS_OWN_ACCOUNT, Text.get("feedback.familyCommands.cannotDelete"),
                    ownAccount);
        }
        final String blocked = historyBlock(memberId);
        if (blocked != null) {
            return FamilyResult.refused(FamilyResult.HAS_HISTORY, Text.get("feedback.familyCommands.cannotDelete"),
                    blocked);
        }
        final boolean wasManager = family.isManager(memberId);
        family.getMemberIds().remove(memberId);
        family.getManagerIds().remove(memberId);
        final FamilyResult notSaved = saveFamilyResult(family);
        if (notSaved != null) {
            return notSaved;
        }
        syncMemberRemoved(family, member, memberId, wasManager, true);
        audit.family(member, AuditCommands.describe(me) + " deleted family member "
                + AuditCommands.describe(member) + " (no registrations or transactions).");
        return FamilyResult.ok(member, family);
    }

    /**
     * Grant or revoke the family-manager flag. A grant copies the current family into the member's
     * {@code managedUsers}; a revoke removes exactly the family-member ids -- an entry that was ALSO an admin
     * grant made before the person joined the family is lost with them (accepted, admin-repairable, and the
     * consistency script flags it).
     */
    public boolean setManager(final Person.Id memberId, final boolean grant) {
        return report(setManagerOutcome(memberId, grant)).isOk();
    }

    /** {@link #setManager} with the outcome returned rather than growled. A no-op grant is a success. */
    public FamilyResult setManagerOutcome(final Person.Id memberId, final boolean grant) {
        final Person me = currentPerson();
        final Person member = memberId == null ? null : DAO.getInstance().getPerson(memberId, Cached.NO).orElse(null);
        // The MEMBER's family (same reasoning as deleteFamilyMember): identical for a manager, and it lets
        // a site admin manage another household from the subject-aware family page.
        final Family family = familyOf(member);
        if (me == null || family == null) {
            return FamilyResult.refused(FamilyResult.NO_FAMILY, Text.get("feedback.familyCommands.noFamily"),
                    Text.get("feedback.familyCommands.notInYourFamily"));
        }
        if (!managerOrAdmin(family, me)) {
            return FamilyResult.refused(FamilyResult.NOT_MANAGER, Text.get("feedback.family.notAManager"),
                    Text.get("feedback.familyCommands.onlyManagerChangesManagers"));
        }
        if (grant == family.isManager(memberId)) {
            return FamilyResult.ok(member, family);
        }
        if (!grant && family.getManagerIds().size() == 1 && family.getSize() >= 2) {
            return FamilyResult.refused(FamilyResult.LAST_MANAGER,
                    Text.get("feedback.familyCommands.managerMustRemain"),
                    Text.get("feedback.familyCommands.needsOneManager"));
        }
        if (grant && !EmailAddresses.isValid(member.getEmail())) {
            // A manager signs in and acts for the family; without their own WORKING address they cannot.
            return FamilyResult.refused(FamilyResult.MANAGER_NEEDS_EMAIL,
                    Text.get("feedback.familyCommands.managerNeedsEmail"),
                    Text.get("feedback.familyCommands.memberNeedsRealEmail"));
        }
        if (grant) {
            family.getManagerIds().add(memberId);
        } else {
            family.getManagerIds().remove(memberId);
        }
        final FamilyResult notSaved = saveFamilyResult(family);
        if (notSaved != null) {
            return notSaved;
        }
        applyManagerFlagToMember(family, member, grant);
        audit.family(member, AuditCommands.describe(me) + (grant ? " granted " : " revoked ")
                + "family-manager for " + AuditCommands.describe(member) + ".");
        return FamilyResult.ok(member, family);
    }

    // ------------------------------------------------------------------ admin writes

    /**
     * Admin-only: link an EXISTING person into {@code anchorId}'s family (the spouse-with-their-own-login
     * case). Rejected when the person already belongs to a different family -- one person, one family, by
     * construction.
     */
    public boolean adminLink(final Person.Id anchorId, final Person.Id personId, final boolean manager) {
        // Authority is required over BOTH ends, and it is the sign-in rule, not profile reach: a link hands
        // people to managers (requireLinkAuthorityOver).
        if (!requireLinkAuthorityOver(anchorId) || !requireLinkAuthorityOver(personId)) {
            return false;
        }
        final Person anchor = DAO.getInstance().getPerson(anchorId, Cached.NO).orElse(null);
        final Person person = DAO.getInstance().getPerson(personId, Cached.NO).orElse(null);
        if (anchor == null || person == null) {
            return fail(Text.get("feedback.familyCommands.unknownPerson"),
                    Text.get("feedback.familyCommands.bothMustExist"));
        }
        // ...and over everyone ALREADY in the anchor's family: the link changes who manages whom across the
        // whole household (a new manager gains every member, every manager gains the newcomer), and a member
        // may belong to another organization as well. Checked before ensureFamilyFor so a refusal writes nothing.
        if (!hasLinkAuthorityOverFamilyOf(anchor)) {
            return false;
        }
        final Family anchorFamily = ensureFamilyFor(anchor);
        if (anchorFamily == null) {
            return false;
        }
        if (person.getFamilyId() != null) {
            if (person.getFamilyId().equals(anchorFamily.getId())) {
                return true;
            }
            return fail(Text.get("feedback.familyCommands.alreadyInFamily"),
                    Text.get("feedback.familyCommands.inAnotherFamily", AuditCommands.describe(person)));
        }
        if (anchorFamily.getSize() >= getMaxMembers()) {
            return fail(Text.get("feedback.familyCommands.familyFull"),
                    Text.get("feedback.familyCommands.thisFamilyAtLimit", getMaxMembers()));
        }
        if (manager && !EmailAddresses.isValid(person.getEmail())) {
            // Same rule as self-service: a manager must be able to sign in, which takes a real address.
            return fail(Text.get("feedback.familyCommands.managerNeedsEmail"),
                    Text.get("feedback.familyCommands.personNeedsEmailToManage", AuditCommands.describe(person)));
        }
        person.setFamilyId(anchorFamily.getId());
        if (!savePersonOrWarn(person)) {
            return false;
        }
        anchorFamily.getMemberIds().add(person.getId());
        if (manager) {
            anchorFamily.getManagerIds().add(person.getId());
        }
        if (!saveFamilyOrWarn(anchorFamily)) {
            return false;
        }
        syncMemberAdded(anchorFamily, person, manager);
        audit.family(person, "Admin linked " + AuditCommands.describe(person) + " into the family of "
                + AuditCommands.describe(anchor) + (manager ? " as a family manager." : "."));
        return true;
    }

    /**
     * Why this member cannot be unlinked, or null when the unlink may proceed. Rule (a): a remaining family of
     * two or more must keep a manager. Rule (b): SOME remaining member must have a usable email -- applied even
     * to a remaining family of one, because a login-less, email-less solo member is unreachable by anyone.
     */
    public String unlinkBlockReason(final Person.Id memberId) {
        final Person member = DAO.getInstance().getPerson(memberId, Cached.NO).orElse(null);
        final Family family = familyOf(member);
        if (family == null || !family.isMember(memberId)) {
            return Text.get("feedback.familyCommands.notInAFamily");
        }
        final List<Person.Id> remaining = new ArrayList<>(family.getMemberIds());
        remaining.remove(memberId);
        if (remaining.isEmpty()) {
            return null;    // last member out: the family row is deleted, nothing to protect
        }
        if (remaining.size() >= 2 && remainingManagers(family, memberId) == 0) {
            return Text.get("feedback.familyCommands.noManagerWouldRemain");
        }
        if (!anyoneHasEmail(remaining)) {
            return Text.get("feedback.familyCommands.noEmailWouldRemain");
        }
        return null;
    }

    /**
     * Admin-only unlink: the member's profile, registrations and history all survive; they simply stop being
     * reachable from this family's logins. The last member's unlink deletes the family row itself.
     */
    public boolean adminUnlink(final Person.Id memberId) {
        if (!requirePeopleAdminFor(memberId)) {
            return false;
        }
        final String blocked = unlinkBlockReason(memberId);
        if (blocked != null) {
            return fail(Text.get("feedback.familyCommands.cannotRemoveFromFamily"), blocked);
        }
        final Person member = DAO.getInstance().getPerson(memberId, Cached.NO).orElse(null);
        final Family family = familyOf(member);
        if (member == null || family == null) {
            return fail(Text.get("feedback.familyCommands.notInFamilyTitle"),
                    Text.get("feedback.familyCommands.notInAFamily"));
        }
        final boolean wasManager = family.isManager(memberId);
        family.getMemberIds().remove(memberId);
        family.getManagerIds().remove(memberId);
        if (family.getMemberIds().isEmpty()) {
            DAO.getInstance().deleteFamily(family.getId());
        } else if (!saveFamilyOrWarn(family)) {
            return false;
        }
        syncMemberRemoved(family, member, memberId, wasManager, false);
        audit.family(member, "Admin removed " + AuditCommands.describe(member) + " from their family.");
        return true;
    }

    /**
     * Idempotent repair: re-point member back-pointers and re-add missing manager entries from the family row.
     * Deliberately ADDITIVE-only on {@code managedUsers} -- a suspected leftover cannot be told apart from a
     * legitimate admin grant, so removal is a human decision (the consistency script reports candidates).
     */
    public boolean resyncFamily(final String familyId) {
        final Family family = DAO.getInstance().getFamily(Family.Id.from(familyId), Cached.NO).orElse(null);
        if (family == null) {
            return fail(Text.get("feedback.familyCommands.unknownFamily"),
                    Text.get("feedback.familyCommands.noFamilyWithId", familyId));
        }
        // Repair touches every member's managedUsers, so it needs people-admin reach over each of them
        // (trivially true for the site admins and ops flows this tool serves).
        for (final Person.Id memberId : family.getMemberIds()) {
            if (!requirePeopleAdminFor(memberId)) {
                return false;
            }
        }
        for (final Person.Id memberId : family.getMemberIds()) {
            final Person member = DAO.getInstance().getPerson(memberId, Cached.NO).orElse(null);
            if (member == null) {
                log.warn("Family {} references missing/deleted person {}", familyId, memberId);
                continue;
            }
            if (!family.getId().equals(member.getFamilyId())) {
                member.setFamilyId(family.getId());
                savePersonOrWarn(member);
            }
        }
        for (final Person.Id managerId : family.getManagerIds()) {
            final Person managerPerson = DAO.getInstance().getPerson(managerId, Cached.NO).orElse(null);
            if (managerPerson != null) {
                applyManagerFlagToMember(family, managerPerson, true);
            }
        }
        audit.family(null, "Admin resynced family " + familyId + ".");
        return true;
    }

    // ------------------------------------------------------------------ internals

    /** The caller's family, created on first use. Package-private so page flows in this package can reuse it. */
    Family ensureFamilyFor(final Person owner) {
        final Family family = createdFamilyFor(owner);
        return family == null ? failFamily() : family;
    }

    /** {@link #ensureFamilyFor} without the growl: null when the family row could not be created. */
    private Family createdFamilyFor(final Person owner) {
        final Family existing = familyOf(owner);
        if (existing != null) {
            return existing;
        }
        if (owner.getFamilyId() != null) {
            // Dangling pointer (row deleted out-of-band): fall through and give them a fresh family.
            log.warn("Replacing dangling familyId {} on {}", owner.getFamilyId(), owner.getId());
        }
        final Family family = Family.builder()
                .memberIds(List.of(owner.getId()))
                .managerIds(List.of(owner.getId()))
                .createdBy(owner.getId())
                .created(java.time.LocalDateTime.now())
                .build();
        try {
            if (!DAO.getInstance().saveFamily(family)) {
                return null;
            }
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to create family for {}", owner.getId(), ex);
            return null;
        }
        owner.setFamilyId(family.getId());
        if (!savePersonOrWarn(owner)) {
            return null;    // orphan family row: invisible, harmless, reported by the consistency script
        }
        return family;
    }

    /** Every manager (except the new member) gains the new member; a manager-member gains everyone else. */
    private void syncMemberAdded(final Family family, final Person newMember, final boolean isManager) {
        for (final Person.Id managerId : family.getManagerIds()) {
            if (managerId.equals(newMember.getId())) {
                continue;
            }
            final Person managerPerson = DAO.getInstance().getPerson(managerId, Cached.NO).orElse(null);
            if (managerPerson != null && !managerPerson.getManagedUsers().contains(newMember.getId())) {
                managerPerson.getManagedUsers().add(newMember.getId());
                savePersonOrWarn(managerPerson);
            }
        }
        if (isManager) {
            // Thread the SAME object through: it was saved above (familyId) and must accumulate, not fork.
            applyManagerFlagToMember(family, newMember, true);
        }
    }

    /**
     * Remove the departed member from every remaining manager's list, strip the member's own family-derived
     * entries if they were a manager, then detach (and optionally soft-delete) the member themselves. Takes
     * the already-loaded member object -- after a soft delete the DAO stops answering for this id, so a
     * re-load inside here would come back empty.
     */
    private void syncMemberRemoved(final Family family, final Person member, final Person.Id memberId,
            final boolean wasManager, final boolean delete) {
        for (final Person.Id managerId : family.getManagerIds()) {
            final Person managerPerson = DAO.getInstance().getPerson(managerId, Cached.NO).orElse(null);
            if (managerPerson != null && managerPerson.getManagedUsers().remove(memberId)) {
                savePersonOrWarn(managerPerson);
            }
        }
        if (member == null) {
            return;
        }
        if (wasManager) {
            member.getManagedUsers().removeAll(family.getMemberIds());
        }
        member.setFamilyId(null);
        if (delete) {
            member.delete();
        }
        savePersonOrWarn(member);
    }

    /** Grant: add every other member (missing only). Revoke: remove exactly the other members. Then save. */
    private void applyManagerFlagToMember(final Family family, final Person member, final boolean grant) {
        boolean changed = false;
        for (final Person.Id otherId : family.getMemberIds()) {
            if (otherId.equals(member.getId())) {
                continue;
            }
            if (grant && !member.getManagedUsers().contains(otherId)) {
                member.getManagedUsers().add(otherId);
                changed = true;
            } else if (!grant && member.getManagedUsers().remove(otherId)) {
                changed = true;
            }
        }
        if (changed) {
            savePersonOrWarn(member);
        }
    }

    private int remainingManagers(final Family family, final Person.Id withoutId) {
        int count = 0;
        for (final Person.Id managerId : family.getManagerIds()) {
            if (!managerId.equals(withoutId)) {
                count++;
            }
        }
        return count;
    }

    private boolean anyoneHasEmail(final List<Person.Id> ids) {
        for (final Person.Id id : ids) {
            final Person person = DAO.getInstance().getPerson(id, Cached.NO).orElse(null);
            if (person != null && EmailAddresses.isValid(person.getEmail())) {
                return true;
            }
        }
        return false;
    }

    private boolean emailInUse(final String email, final Person.Id selfId) {
        final Person existing = DAO.getInstance().getPersonByEmail(email, Cached.NO);
        return existing != null && !existing.getId().equals(selfId);
    }

    /**
     * People-admin reach over ONE subject: site admin, or {@code peopleAdmin} in an org the subject belongs
     * to ({@code OrgCommands.canAdminPerson} -- the global peopleAdmin variant is retired, org migration
     * 2026-08). Subject-scoped on purpose: a people admin for one org must not relink another org's people.
     */
    private boolean requirePeopleAdminFor(final Person.Id subjectId) {
        if (orgSource.get().canAdminPerson(subjectId)) {
            return true;
        }
        return fail(Text.get("feedback.shared.notAuthorized"), Text.get("feedback.familyCommands.needsPeopleAdmin"));
    }

    /**
     * What an admin link takes over ONE person: {@code OrgCommands.canChangeSignInOf}, not the profile reach of
     * {@link #requirePeopleAdminFor}. A link makes somebody a manager of somebody else, and a manager acts for
     * their members everywhere and may set a member's first email address, which decides who signs in as them.
     * Profile reach follows membership, and an organization can impose membership (an address added by its
     * admin, a registration for one of its trips), so on that footing one tenant's people admin could have
     * linked themselves in as the manager of another tenant's family.
     */
    private boolean requireLinkAuthorityOver(final Person.Id subjectId) {
        if (orgSource.get().canChangeSignInOf(subjectId)) {
            return true;
        }
        return fail(Text.get("feedback.shared.notAuthorized"),
                Text.get("feedback.familyCommands.linkNeedsSiteAdmin"));
    }

    /** {@link #requireLinkAuthorityOver} each member of this person's family; true when they have none yet. */
    private boolean hasLinkAuthorityOverFamilyOf(final Person anchor) {
        final Family family = familyOf(anchor);
        if (family == null) {
            return true;
        }
        for (final Person.Id memberId : family.getMemberIds()) {
            if (!requireLinkAuthorityOver(memberId)) {
                return false;
            }
        }
        return true;
    }

    private Person currentPerson() {
        // Not getCurrentPerson(): that answers a BLANK person with a fresh id when nobody is signed in, which
        // would satisfy every null check and then anchor a junk family. Resolve through the Caller instead.
        final Caller current = caller();
        return current.isAuthenticated() ? DAO.getInstance().getPerson(current.personId(),
                Cached.NO).orElse(null) : null;
    }

    private boolean saveFamilyOrWarn(final Family family) {
        final FamilyResult notSaved = saveFamilyResult(family);
        return notSaved == null || fail(notSaved.summary(), notSaved.detail());
    }

    /** The versioned family put; null when it went through, else the refusal (lost race, or store failure). */
    private FamilyResult saveFamilyResult(final Family family) {
        try {
            if (DAO.getInstance().saveFamily(family)) {
                return null;
            }
            return FamilyResult.refused(FamilyResult.STORE_FAILED, Text.get("feedback.shared.unableToSave"),
                    Text.get("feedback.familyCommands.familyNotSaved"));
        } catch (final ConditionalCheckFailedException ex) {
            return FamilyResult.refused(FamilyResult.FAMILY_CHANGED, Text.get("feedback.familyCommands.familyChanged"),
                    Text.get("feedback.familyCommands.changedBySomeoneElse"));
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to save family {}", family.getId(), ex);
            return FamilyResult.refused(FamilyResult.STORE_FAILED, Text.get("feedback.shared.unableToSave"),
                    Text.get("feedback.familyCommands.familyNotSavedBecause", ex.getMessage()));
        }
    }

    private boolean savePersonOrWarn(final Person person) {
        return PersonCommands.getPersonCommands().savePerson(person);
    }

    private boolean fail(final String summary, final String detail) {
        return PageFeedback.refuse(summary, detail);
    }

    private Family failFamily() {
        fail(Text.get("feedback.shared.unableToSave"), Text.get("feedback.familyCommands.familyNotCreated"));
        return null;
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
