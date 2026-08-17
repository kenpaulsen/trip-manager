package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.EmailAddresses;
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

    public FamilyCommands() {
        this(new ConfigCommands(), new AuditCommands(), Caller::current);
    }

    /**
     * Test seam (the {@code ChatCommands} pattern): collaborators handed in, no container needed. The caller
     * supplier exists because {@code Caller.current()} needs a FacesContext, which unit tests do not have.
     */
    public FamilyCommands(final ConfigCommands config, final AuditCommands audit,
            final java.util.function.Supplier<Caller> callerSource) {
        this.config = config;
        this.audit = audit;
        this.callerSource = callerSource;
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
        final List<String> missing = new ArrayList<>();
        if (person == null) {
            return missing;
        }
        if (person.getBirthdate() == null) {
            missing.add("Birthdate");
        }
        if (isBlank(person.getPassport().getNumber())) {
            missing.add("Passport number");
        } else if (person.getPassport().getExpires() == null) {
            missing.add("Passport expiration");
        }
        if (isBlank(person.getEmergencyContactName())) {
            missing.add("Emergency contact");
        }
        return missing;
    }

    /**
     * Why this member cannot be deleted by a manager, or null when deletion is allowed. Registrations have no
     * person-side index, so trip membership is the proxy (a registration is only created for a trip the person
     * is on); soft-deleted transactions still block -- financial history must survive.
     */
    public String deleteBlockReason(final Person.Id memberId) {
        if (!DAO.getInstance().getTripsForUser(memberId, Cached.NO).isEmpty()) {
            return "This person has trip registrations. Ask an administrator to remove them instead.";
        }
        if (!DAO.getInstance().getTransactions(memberId, Cached.NO).isEmpty()) {
            return "This person has financial history. Ask an administrator to remove them instead.";
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
        final Person me = currentPerson();
        if (me == null) {
            return failPerson("Not signed in", "Sign in to manage your family.");
        }
        final Person anchor = resolveAnchor(me, subjectId);
        if (anchor == null) {
            return failPerson("Not allowed", "You cannot manage that person's family.");
        }
        final Person.Sex parsed = isBlank(sex) ? null : Person.Sex.valueOf(sex);
        return createFamilyMemberFor(anchor, first, last, birthdate, parsed, email, manager);
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
        return createFamilyMemberFor(currentPerson(), first, last, birthdate, sex, email, manager);
    }

    private Person createFamilyMemberFor(final Person anchor, final String first, final String last,
            final LocalDate birthdate, final Person.Sex sex, final String email, final boolean manager) {
        final Person me = currentPerson();
        if (me == null || anchor == null) {
            return failPerson("Not signed in", "Sign in to manage your family.");
        }
        if (isBlank(first) || isBlank(last)) {
            return failPerson("Name required", "A family member needs at least a first and last name.");
        }
        if (sex == null) {
            return failPerson("Sex required", "Choose Male or Female for " + first
                    + " -- passports and rooming assignments both need it.");
        }
        if (birthdate == null) {
            return failPerson("Birthdate required", "Enter " + first + "'s birthdate -- passports, "
                    + "insurance, and age-based pricing all need it.");
        }
        if (birthdate.isAfter(LocalDate.now())) {
            return failPerson("Birthdate is in the future", "Check " + first + "'s birthdate.");
        }
        if (manager && !EmailAddresses.isValid(email)) {
            // A manager signs in and acts for the family; without their own WORKING address they cannot.
            // Validity, not merely presence: "foo" is an email field with no mailbox behind it, and it
            // would satisfy a blank check while still leaving them unable to sign in or be mailed.
            // Checked before ensureFamilyFor so a refusal writes nothing, not even an empty family row.
            return failPerson("Manager needs an email", "A valid separate email address is required for "
                    + first + " to be a family manager. Add them with their own email, or add them now and"
                    + " grant manager once they have one.");
        }
        Family family = familyOf(anchor);
        if (family != null && !family.isManager(me.getId()) && !caller().isSiteAdmin()) {
            return failPerson("Not a family manager", "Only a family manager can add family members.");
        }
        if (family == null) {
            family = ensureFamilyFor(anchor);
            if (family == null) {
                return null;
            }
        }
        if (family.getSize() >= getMaxMembers()) {
            return failPerson("Family is full", "Your family has reached the size limit ("
                    + getMaxMembers() + "). You can request an increase from the family page.");
        }
        final String cleanEmail = isBlank(email) ? null : email.trim();
        if (cleanEmail != null && emailInUse(cleanEmail, null)) {
            return failPerson("Email already in use",
                    "Another person already uses '" + cleanEmail + "'. Leave the email blank, or use another.");
        }

        final Person member = Person.builder().first(first).last(last).birthdate(birthdate).sex(sex).build();
        member.setEmail(cleanEmail);
        member.setFamilyId(family.getId());
        if (!savePersonOrWarn(member)) {
            return null;
        }
        family.getMemberIds().add(member.getId());
        if (manager) {
            family.getManagerIds().add(member.getId());
        }
        if (!saveFamilyOrWarn(family)) {
            return null;
        }
        syncMemberAdded(family, member, manager);
        audit.family(member, AuditCommands.describe(me) + " added " + AuditCommands.describe(member)
                + " to their family" + (manager ? " as a family manager." : "."));
        return member;
    }

    /**
     * Delete (soft) a member a manager created by mistake -- allowed only while the member has no history at
     * all ({@link #deleteBlockReason}). Anything with history goes through an admin unlink instead.
     */
    public boolean deleteFamilyMember(final Person.Id memberId) {
        final Person me = currentPerson();
        final Person member = memberId == null ? null : DAO.getInstance().getPerson(memberId, Cached.NO).orElse(null);
        // The MEMBER's family, not the caller's: for a manager they are the same, and anchoring here is
        // what lets a site admin work the subject-aware family page (?id=) for someone else's household.
        final Family family = familyOf(member);
        if (me == null || family == null) {
            return fail("No family", "That person is not in a family you can manage.");
        }
        if (!family.isManager(me.getId()) && !caller().isSiteAdmin()) {
            return fail("Not a family manager", "Only a family manager can remove family members.");
        }
        if (me.getId().equals(memberId)) {
            return fail("Cannot delete yourself", "You cannot delete your own profile.");
        }
        final String blocked = deleteBlockReason(memberId);
        if (blocked != null) {
            return fail("Cannot delete", blocked);
        }
        final boolean wasManager = family.isManager(memberId);
        family.getMemberIds().remove(memberId);
        family.getManagerIds().remove(memberId);
        if (!saveFamilyOrWarn(family)) {
            return false;
        }
        syncMemberRemoved(family, member, memberId, wasManager, true);
        audit.family(member, AuditCommands.describe(me) + " deleted family member "
                + AuditCommands.describe(member) + " (no registrations or transactions).");
        return true;
    }

    /**
     * Grant or revoke the family-manager flag. A grant copies the current family into the member's
     * {@code managedUsers}; a revoke removes exactly the family-member ids -- an entry that was ALSO an admin
     * grant made before the person joined the family is lost with them (accepted, admin-repairable, and the
     * consistency script flags it).
     */
    public boolean setManager(final Person.Id memberId, final boolean grant) {
        final Person me = currentPerson();
        final Person member = memberId == null ? null : DAO.getInstance().getPerson(memberId, Cached.NO).orElse(null);
        // The MEMBER's family (same reasoning as deleteFamilyMember): identical for a manager, and it lets
        // a site admin manage another household from the subject-aware family page.
        final Family family = familyOf(member);
        if (me == null || family == null) {
            return fail("No family", "That person is not in a family you can manage.");
        }
        if (!family.isManager(me.getId()) && !caller().isSiteAdmin()) {
            return fail("Not a family manager", "Only a family manager can change who manages the family.");
        }
        if (grant == family.isManager(memberId)) {
            return true;
        }
        if (!grant && family.getManagerIds().size() == 1 && family.getSize() >= 2) {
            return fail("A manager must remain", "A family with more than one person needs at least one manager.");
        }
        if (grant && (member == null || !EmailAddresses.isValid(member.getEmail()))) {
            // A manager signs in and acts for the family; without their own WORKING address they cannot.
            return fail("Manager needs an email", "A valid separate email address is required for them to be"
                    + " a family manager. Add a real email address to their profile first.");
        }
        if (grant) {
            family.getManagerIds().add(memberId);
        } else {
            family.getManagerIds().remove(memberId);
        }
        if (!saveFamilyOrWarn(family)) {
            return false;
        }
        if (member != null) {
            applyManagerFlagToMember(family, member, grant);
        }
        audit.family(member, AuditCommands.describe(me) + (grant ? " granted " : " revoked ")
                + "family-manager for " + AuditCommands.describe(member) + ".");
        return true;
    }

    // ------------------------------------------------------------------ admin writes

    /**
     * Admin-only: link an EXISTING person into {@code anchorId}'s family (the spouse-with-their-own-login
     * case). Rejected when the person already belongs to a different family -- one person, one family, by
     * construction.
     */
    public boolean adminLink(final Person.Id anchorId, final Person.Id personId, final boolean manager) {
        if (!requirePeopleAdmin()) {
            return false;
        }
        final Person anchor = DAO.getInstance().getPerson(anchorId, Cached.NO).orElse(null);
        final Person person = DAO.getInstance().getPerson(personId, Cached.NO).orElse(null);
        if (anchor == null || person == null) {
            return fail("Unknown person", "Both people must exist to link a family.");
        }
        final Family anchorFamily = ensureFamilyFor(anchor);
        if (anchorFamily == null) {
            return false;
        }
        if (person.getFamilyId() != null) {
            if (person.getFamilyId().equals(anchorFamily.getId())) {
                return true;
            }
            return fail("Already in a family", AuditCommands.describe(person)
                    + " is already in another family. Unlink them there first.");
        }
        if (anchorFamily.getSize() >= getMaxMembers()) {
            return fail("Family is full", "This family has reached the size limit (" + getMaxMembers()
                    + "). Raise family.maxMembers in Settings first.");
        }
        if (manager && !EmailAddresses.isValid(person.getEmail())) {
            // Same rule as self-service: a manager must be able to sign in, which takes a real address.
            return fail("Manager needs an email", "A valid separate email address is required for "
                    + AuditCommands.describe(person) + " to be a family manager.");
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
            return "That person is not in a family.";
        }
        final List<Person.Id> remaining = new ArrayList<>(family.getMemberIds());
        remaining.remove(memberId);
        if (remaining.isEmpty()) {
            return null;    // last member out: the family row is deleted, nothing to protect
        }
        if (remaining.size() >= 2 && remainingManagers(family, memberId) == 0) {
            return "No family manager would remain. Make someone else a manager first.";
        }
        if (!anyoneHasEmail(remaining)) {
            return "No remaining family member would have an email address. Set one first.";
        }
        return null;
    }

    /**
     * Admin-only unlink: the member's profile, registrations and history all survive; they simply stop being
     * reachable from this family's logins. The last member's unlink deletes the family row itself.
     */
    public boolean adminUnlink(final Person.Id memberId) {
        if (!requirePeopleAdmin()) {
            return false;
        }
        final String blocked = unlinkBlockReason(memberId);
        if (blocked != null) {
            return fail("Cannot remove from family", blocked);
        }
        final Person member = DAO.getInstance().getPerson(memberId, Cached.NO).orElse(null);
        final Family family = familyOf(member);
        if (member == null || family == null) {
            return fail("Not in a family", "That person is not in a family.");
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
        if (!requirePeopleAdmin()) {
            return false;
        }
        final Family family = DAO.getInstance().getFamily(Family.Id.from(familyId), Cached.NO).orElse(null);
        if (family == null) {
            return fail("Unknown family", "No family with id " + familyId);
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
                return failFamily();
            }
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to create family for {}", owner.getId(), ex);
            return failFamily();
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

    private boolean requirePeopleAdmin() {
        if (caller().has(PrivilegeCommands.PEOPLE_ADMIN)) {
            return true;
        }
        return fail("Not authorized", "Managing family links requires the peopleAdmin privilege.");
    }

    private Person currentPerson() {
        // Not getCurrentPerson(): that answers a BLANK person with a fresh id when nobody is signed in, which
        // would satisfy every null check and then anchor a junk family. Resolve through the Caller instead.
        final Caller current = caller();
        return current.isAuthenticated() ? DAO.getInstance().getPerson(current.personId(),
                Cached.NO).orElse(null) : null;
    }

    private boolean saveFamilyOrWarn(final Family family) {
        try {
            return DAO.getInstance().saveFamily(family);
        } catch (final ConditionalCheckFailedException ex) {
            return fail("Family changed", "Someone else changed this family at the same time. "
                    + "Please reload the page and try again.");
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to save family {}", family.getId(), ex);
            return fail("Unable to save", "Unable to save the family: " + ex.getMessage());
        }
    }

    private boolean savePersonOrWarn(final Person person) {
        return PersonCommands.getPersonCommands().savePerson(person);
    }

    private boolean fail(final String summary, final String detail) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, summary, detail);
        return false;
    }

    private Person failPerson(final String summary, final String detail) {
        fail(summary, detail);
        return null;
    }

    private Family failFamily() {
        fail("Unable to save", "Unable to create the family.");
        return null;
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
