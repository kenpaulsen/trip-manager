package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.PasskeyCredential;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PrivacySettings;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.security.RememberMeService;
import org.paulsens.trip.security.TokenService;
import org.paulsens.trip.util.Util;

/**
 * Self-service account deletion (App Store guideline 5.1.1(v); {@code docs/moderation.md}).
 *
 * <p>What "delete" means here, decided 2026-09-13:
 * <ul>
 *   <li><b>Gone, immediately and permanently:</b> the login (password, passkeys, every signed-in device and
 *       remember-me cookie), the profile (name, contact details, birthdate, passport, emergency contact,
 *       privacy choices, profile pictures), every chat message and photo the person posted, every comment
 *       and reaction they left, their per-person data (block list, todo status, push devices), privileges,
 *       organization memberships and family links, and -- since 2026-09-16 -- every trip registration and
 *       every seat on a trip: roster, director and facilitator lists, event participation and private event
 *       notes. A registration is the person's own filing, not the organization's accounting; kept under an
 *       anonymized id it showed as a pending registration nobody could act on.</li>
 *   <li><b>Kept, under an anonymized record:</b> every transaction. Money rows are never destroyed (a
 *       user-locked rule of the payments system) and an organization's books must still add up after a
 *       traveler leaves; the row keeps the person id, but the person behind it now reads "Deleted Account".
 *       This is the "data legally required to maintain" carve-out the store rule allows, and the Terms of
 *       Use say so. Ledgers are per person, not per registration, so nothing there depends on the rows that
 *       go.</li>
 * </ul>
 *
 * <p><b>Settle first.</b> With {@code account.delete.requireSettled} on (the default) deletion is refused
 * while the person is registered for a trip that has not ended, or owes a balance -- the app tells them who
 * to contact. The rule can be switched off without a deploy, because a store review may insist deletion is
 * always available; the notice email then still says what is owed. Two refusals are NOT subject to the
 * switch, because they would leave the data model broken: the sole admin of an organization, and a family
 * manager whose dependents are themselves unsettled.
 *
 * <p><b>Notice first.</b> Before anything is erased, one email per organization the person belonged to goes
 * to that organization's contact (the {@code account.delete.notify.email} slot): who left, every trip they
 * were on, their complete transaction history, and the amount owed by or to them in bold. Composed before
 * the scrub because after it the name and email no longer exist. If the mail cannot be sent, the same
 * report goes to the audit trail as an ALARM and the deletion proceeds: a mail outage must not block a
 * person's right to leave, and the record must never be lost.
 */
@Slf4j
@Named("accountDeletion")
@ApplicationScoped
public class AccountDeletionCommands {

    /** The literal the client must echo, so a stray tap can never delete an account. */
    public static final String CONFIRMATION = "DELETE";

    public static final String BLOCK_UPCOMING_TRIP = "UPCOMING_TRIP";
    public static final String BLOCK_BALANCE_OWED = "BALANCE_OWED";
    public static final String BLOCK_SOLE_ORG_ADMIN = "SOLE_ORG_ADMIN";
    public static final String BLOCK_DEPENDENT_UNSETTLED = "DEPENDENT_UNSETTLED";

    public static final String REFUSED_BLOCKED = "blocked";
    public static final String REFUSED_NOT_FOUND = "not_found";
    public static final String REFUSED_CONFIRMATION = "confirmation";
    public static final String REFUSED_FAILED = "failed";

    private static final String DELETED_FIRST = "Deleted";
    private static final String DELETED_LAST = "Account";
    private static final int PAGE = 200;
    private static final int MAX_PAGES = 500;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);

    /** One reason deletion is refused; {@code personName} names the dependent when it is not the caller. */
    public record BlockReason(String code, String tripId, String tripTitle, long amountOwedCents, String personName) {
    }

    /** A trip the person is or was on, as the preview and the notice show it. */
    public record TripLine(String tripId, String title, String orgId, LocalDateTime start, LocalDateTime end,
            String status, boolean ended) {
    }

    /** Everything the app needs to show BEFORE the person confirms. */
    public record Preview(boolean blocked, boolean settleRequired, List<BlockReason> reasons, long amountOwedCents,
            long amountCreditCents, String contactEmail, List<TripLine> trips, List<String> erased,
            List<String> retained) {
    }

    /** The result of an attempt; {@code preview} rides along on a refusal so the app can explain it. */
    public record Outcome(boolean ok, String code, String message, Preview preview) {
        static Outcome success() {
            return new Outcome(true, null, null, null);
        }

        static Outcome refused(final String code, final String message, final Preview preview) {
            return new Outcome(false, code, message, preview);
        }
    }

    public static final List<String> ERASED = List.of(
            "Your sign-in: password, passkeys and every signed-in device",
            "Your profile: name, email, phone, address, birthdate, passport and emergency contact",
            "Your profile pictures",
            "Every chat message and photo you posted, and every comment and reaction you left",
            "Your trip registrations and your place on every trip",
            "Your saved preferences and blocked people");
    public static final List<String> RETAINED = List.of(
            "Records of your payments, kept by the organization for its accounting, with your name and "
                    + "contact details removed");

    private final ConfigCommands config;
    private final Supplier<MailCommands> mailSource;
    private final Supplier<MailAddressCommands> addressSource;
    private final Supplier<ChatCommands> chatSource;
    private final Supplier<PhotoChatCommands> photoChatSource;
    private final Supplier<ProfilePhotoCommands> profilePhotoSource;
    private final Supplier<TokenService> tokenSource;

    public AccountDeletionCommands() {
        this(new ConfigCommands(), () -> org.paulsens.trip.api.Beans.get(MailCommands.class),
                MailAddressCommands::new, ChatCommands::getChatCommands, PhotoChatCommands::getPhotoChatCommands,
                () -> org.paulsens.trip.api.Beans.get(ProfilePhotoCommands.class), TokenService::getInstance);
    }

    /** Test seam: every collaborator handed in, no container needed. */
    public AccountDeletionCommands(final ConfigCommands config, final Supplier<MailCommands> mailSource,
            final Supplier<MailAddressCommands> addressSource, final Supplier<ChatCommands> chatSource,
            final Supplier<PhotoChatCommands> photoChatSource,
            final Supplier<ProfilePhotoCommands> profilePhotoSource, final Supplier<TokenService> tokenSource) {
        this.config = config;
        this.mailSource = mailSource;
        this.addressSource = addressSource;
        this.chatSource = chatSource;
        this.photoChatSource = photoChatSource;
        this.profilePhotoSource = profilePhotoSource;
        this.tokenSource = tokenSource;
    }

    // ------------------------------------------------------------------ preview

    /** What deleting {@code personId} would do and whether it is allowed right now; null for nobody. */
    public Preview preview(final Person.Id personId) {
        final Person person = load(personId);
        return person == null ? null : preview(person);
    }

    private Preview preview(final Person person) {
        final boolean settleRequired = config.getBoolean(KnownSettings.ACCOUNT_DELETE_REQUIRE_SETTLED);
        final List<BlockReason> reasons = new ArrayList<>();
        final List<TripLine> trips = tripLines(person.getId());
        long owed = 0L;
        long credit = 0L;
        for (final TripLine trip : trips) {
            if (!trip.ended() && !Registration.Status.NOT_REGISTERED.getDescription().equals(trip.status())) {
                reasons.add(new BlockReason(BLOCK_UPCOMING_TRIP, trip.tripId(), trip.title(), 0L, null));
            }
        }
        for (final Map.Entry<String, Long> balance : balancesByOrg(person.getId()).entrySet()) {
            if (balance.getValue() < 0) {
                owed += -balance.getValue();
                reasons.add(new BlockReason(BLOCK_BALANCE_OWED, null, orgName(balance.getKey()),
                        -balance.getValue(), null));
            } else {
                credit += balance.getValue();
            }
        }
        for (final Person dependent : dependents(person)) {
            final String name = displayName(dependent);
            for (final TripLine trip : tripLines(dependent.getId())) {
                if (!trip.ended() && !Registration.Status.NOT_REGISTERED.getDescription().equals(trip.status())) {
                    reasons.add(new BlockReason(BLOCK_DEPENDENT_UNSETTLED, trip.tripId(), trip.title(), 0L, name));
                }
            }
            for (final Long balance : balancesByOrg(dependent.getId()).values()) {
                if (balance < 0) {
                    reasons.add(new BlockReason(BLOCK_DEPENDENT_UNSETTLED, null, null, -balance, name));
                }
            }
        }
        for (final Organization org : organizationsOf(person)) {
            if (org.isAdmin(person.getId()) && org.getAdminIds().size() <= 1) {
                reasons.add(new BlockReason(BLOCK_SOLE_ORG_ADMIN, null, org.getName(), 0L, null));
            }
        }
        final boolean blocked = reasons.stream().anyMatch(reason -> settleRequired
                || BLOCK_SOLE_ORG_ADMIN.equals(reason.code()) || BLOCK_DEPENDENT_UNSETTLED.equals(reason.code()));
        return new Preview(blocked, settleRequired, List.copyOf(reasons), owed, credit,
                contactEmail(person, trips), trips, ERASED, RETAINED);
    }

    // ------------------------------------------------------------------ the deletion

    /**
     * Deletes {@code personId}'s own account. {@code confirmation} must be {@link #CONFIRMATION}. The
     * refusal codes are {@code REFUSED_*}; a blocked refusal carries the preview so the app can explain.
     */
    public Outcome deleteOwnAccount(final Person.Id personId, final String confirmation, final AuditActor actor) {
        if (!CONFIRMATION.equals(confirmation == null ? null : confirmation.trim())) {
            return Outcome.refused(REFUSED_CONFIRMATION, "Type " + CONFIRMATION + " to confirm.", null);
        }
        final Person person = load(personId);
        if (person == null) {
            return Outcome.refused(REFUSED_NOT_FOUND, "No such account.", null);
        }
        final Preview preview = preview(person);
        if (preview.blocked()) {
            return Outcome.refused(REFUSED_BLOCKED, "Your account can't be deleted yet.", preview);
        }
        final AuditActor who = actor != null && actor.isKnown() ? actor
                : new AuditActor(Util.orDefault(person.getEmail(), ""), person.getId().getValue());
        final String identity = displayName(person) + " <" + Util.orDefault(person.getEmail(), "") + "> ("
                + person.getId().getValue() + ")";
        final List<Person> dependents = dependents(person);
        notifyOrganizations(person, dependents, preview, who);
        try {
            revokeAccess(person);
            for (final Person dependent : dependents) {
                erasePresence(dependent, who);
            }
            erasePresence(person, who);
            for (final Person dependent : dependents) {
                leaveTrips(dependent);
            }
            leaveTrips(person);
            detachFamily(person, dependents);
            for (final Person dependent : dependents) {
                scrubAndSoftDelete(dependent);
            }
            leaveOrganizations(person);
            scrubAndSoftDelete(person);
        } catch (final RuntimeException | IOException ex) {
            log.error("Account deletion did not complete for {}", identity, ex);
            Audit.builder(AuditAction.ALARM, AuditOutcome.FAILURE).actor(who)
                    .targetPerson(person.getEmail(), person.getId().getValue())
                    .message("account.delete: INCOMPLETE for " + identity + ": " + ex).log();
            return Outcome.refused(REFUSED_FAILED,
                    "Something went wrong while deleting your account. Please contact support.", preview);
        }
        Audit.builder(AuditAction.PERSON, AuditOutcome.SUCCESS).actor(who)
                .targetPerson(person.getEmail(), person.getId().getValue())
                .message("ACCOUNT DELETED (self-service): " + identity + "; dependents="
                        + dependents.size() + "; trips=" + preview.trips().size()
                        + "; owed=" + money(preview.amountOwedCents())
                        + "; credit=" + money(preview.amountCreditCents())).log();
        return Outcome.success();
    }

    /**
     * Password, passkeys, bearer tokens, remember-me: nothing signs in as this person after this. The login
     * row is removed straight through the DAO, owner-checked: {@code PassCommands.deleteCreds} reads the row
     * through a JSF-admin-view gate that no REST call satisfies, so from the app it refused every time and the
     * password row outlived the account (2026-09-16). A row that will not go is fatal -- an account that is
     * "deleted" but still signs in is the one outcome worse than a failed deletion.
     */
    private void revokeAccess(final Person person) throws IOException {
        tokenSource.get().revokeAllFor(person.getId());
        for (final PasskeyCredential passkey : dao().getPasskeysForUser(person.getId(), Cached.NO)) {
            dao().deletePasskey(passkey.getCredentialId(), person.getId());
        }
        final String email = person.getEmail();
        if (email == null || email.isBlank()) {
            return;   // a created family member: never had a login
        }
        final Creds login = dao().getCredsForCodeLogin(email, Cached.NO);
        if (login == null || !person.getId().equals(login.getUserId())) {
            return;   // no login, or the address already belongs to somebody else's login: not ours to remove
        }
        final boolean removed = Boolean.TRUE.equals(dao().removeCredsForAccountDeletion(email, person.getId()));
        Audit.builder(AuditAction.DELETE_CREDS, AuditOutcome.of(removed))
                .actor(email, person.getId().getValue())
                .targetPerson(email, person.getId().getValue())
                .message("Removed credentials for " + email + " (account deletion)")
                .log();
        if (!removed) {
            throw new IOException("The login row for " + email + " was not removed.");
        }
        RememberMeService.getInstance().revokeAllFor(person.getId());
    }

    /** Chat messages and photos, photo comments, drafts, chat memberships, profile pictures, person data. */
    private void erasePresence(final Person person, final AuditActor who) {
        final Person.Id me = person.getId();
        final Caller self = Caller.forActor(new AuditActor(Util.orDefault(person.getEmail(), ""), me.getValue()));
        final ChatCommands chat = chatSource.get();
        final PhotoChatCommands photoChat = photoChatSource.get();
        for (final ChatCommands.ChatSummary summary : chat.myChats(me)) {
            final String tripId = summary.channel() == null ? null : summary.channel().getTripId();
            if (tripId == null) {
                continue;
            }
            final Set<String> photoKeys = new LinkedHashSet<>();
            ChatMessage.Id before = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                final ChatPage history = chat.history(tripId, me, before, PAGE);
                for (final ChatMessage message : history.getMessages()) {
                    for (final ChatAttachment attachment : message.getAttachments()) {
                        if (attachment.getS3Key() != null) {
                            photoKeys.add(attachment.getS3Key());
                        }
                    }
                    if (me.equals(message.getAuthorId()) && !message.isDeleted()) {
                        chat.deleteMessage(tripId, message.getId(), self);
                    }
                }
                if (!history.isHasMore() || history.getCursor() == null) {
                    break;
                }
                before = history.getCursor();
            }
            for (final String key : photoKeys) {
                eraseComments(photoChat, key, me, self);
                dao().deleteChatReactionsBy(ChatChannel.Id.forPhoto(key), me);
            }
            dao().deleteChatReactionsBy(summary.channel().getId(), me);
            dao().deleteChatDraft(summary.channel().getId(), me);
            chat.leave(tripId, me, who);
        }
        final ProfilePhotoCommands profilePhotos = profilePhotoSource.get();
        for (int slot = 1; slot <= ProfilePhotos.MAX_SLOTS; slot++) {
            profilePhotos.deleteSlotFor(self, person, slot);
        }
        for (final DataId dataId : new ArrayList<>(dao().getPersonDataValues(me, Cached.NO).keySet())) {
            dao().deletePersonDataValue(me, dataId);
        }
        revokePrivileges(person);
    }

    private static void eraseComments(final PhotoChatCommands photoChat, final String key, final Person.Id me,
            final Caller self) {
        ChatMessage.Id before = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            final ChatPage thread = photoChat.thread(key, before, PAGE);
            for (final ChatMessage comment : thread.getMessages()) {
                if (me.equals(comment.getAuthorId()) && !comment.isDeleted()) {
                    photoChat.deleteComment(key, comment.getId(), self);
                }
            }
            if (!thread.isHasMore() || thread.getCursor() == null) {
                break;
            }
            before = thread.getCursor();
        }
    }

    /**
     * Registrations and seats, on every trip. Every trip is walked rather than the membership index, because a
     * PENDING registration is not membership: that is exactly the row that used to survive.
     */
    private void leaveTrips(final Person person) throws IOException {
        final Person.Id me = person.getId();
        for (final Trip trip : dao().getRecentTrips(0, Cached.NO)) {
            if (dao().getRegistration(trip.getId(), me, Cached.NO).isPresent()
                    && !Boolean.TRUE.equals(dao().deleteRegistration(trip.getId(), me))) {
                throw new IOException("The registration on trip " + trip.getId() + " was not deleted.");
            }
            for (final TripEvent event : trip.getTripEvents()) {
                leaveEvent(event, me);
            }
            if (leaveRoster(trip, me) && !Boolean.TRUE.equals(dao().saveTrip(trip))) {
                throw new IOException("Trip " + trip.getId() + " was not saved after removing the person.");
            }
        }
    }

    /** Roster, directors, facilitators; true when the trip changed and needs saving. */
    private static boolean leaveRoster(final Trip trip, final Person.Id me) {
        final boolean member = trip.getPeople() != null && trip.getPeople().contains(me);
        final boolean staff = (trip.getDirectorIds() != null && trip.getDirectorIds().contains(me))
                || (trip.getFacilitatorIds() != null && trip.getFacilitatorIds().contains(me));
        if (member) {
            final List<Person.Id> people = new ArrayList<>(trip.getPeople());
            people.remove(me);
            trip.setPeople(people);
        }
        trip.removeDirectorId(me);
        trip.removeFacilitatorId(me);
        return member || staff;
    }

    /** Participation and the person's private note on an event; the event is only saved when it changed. */
    private void leaveEvent(final TripEvent event, final Person.Id me) throws IOException {
        final boolean participant = event.getParticipants() != null && event.getParticipants().contains(me);
        final boolean noted = event.getPrivNotes().containsKey(me);
        if (!participant && !noted) {
            return;
        }
        if (participant) {
            final List<Person.Id> participants = new ArrayList<>(event.getParticipants());
            participants.remove(me);
            event.setParticipants(participants);
        }
        event.getPrivNotes().remove(me);
        if (!Boolean.TRUE.equals(dao().saveTripEvent(event))) {
            throw new IOException("Trip event " + event.getId() + " was not saved after removing the person.");
        }
    }

    /** Global, per-trip and per-organization grants alike: the row loses this person, nothing else changes. */
    private void revokePrivileges(final Person person) {
        final Person.Id me = person.getId();
        final List<Privilege> rows = new ArrayList<>(dao().getGlobalPrivileges(Cached.NO));
        for (final Trip trip : dao().getTripsForUser(me, Cached.NO)) {
            rows.addAll(dao().getTripPrivileges(trip.getId(), Cached.NO));
        }
        for (final Organization.Id orgId : person.getOrgIds()) {
            rows.addAll(dao().getTripPrivileges(orgId.getValue(), Cached.NO));
        }
        for (final Privilege row : rows) {
            if (row.getPeople() != null && row.getPeople().contains(me)) {
                dao().savePrivilege(row.withoutPerson(me));
            }
        }
    }

    /** Every membership row and admin seat goes; the last-org rule does not apply to an account that is leaving. */
    private void leaveOrganizations(final Person person) throws IOException {
        for (final Organization org : organizationsOf(person)) {
            if (org.getAdminIds().remove(person.getId())) {
                dao().saveOrganization(org);
            }
            dao().deleteOrgMember(org.getId(), person.getId());
        }
        person.setOrgIds(new ArrayList<>());
    }

    /**
     * Leaves the family. Dependents (created members with no login and no other manager) are deleted with
     * the account -- they exist only as this person's household. Everyone else stays, minus this person.
     */
    private void detachFamily(final Person person, final List<Person> dependents) throws IOException {
        final Family family = familyOf(person);
        if (family == null) {
            person.getManagedUsers().clear();
            person.setFamilyId(null);
            return;
        }
        final Set<Person.Id> leaving = new HashSet<>();
        leaving.add(person.getId());
        for (final Person dependent : dependents) {
            leaving.add(dependent.getId());
        }
        family.getMemberIds().removeAll(leaving);
        family.getManagerIds().removeAll(leaving);
        for (final Person.Id remainingId : family.getMemberIds()) {
            final Person remaining = dao().getPerson(remainingId, Cached.NO).orElse(null);
            if (remaining != null && remaining.getManagedUsers().removeAll(leaving)) {
                dao().savePerson(remaining);
            }
        }
        if (family.getMemberIds().isEmpty()) {
            dao().deleteFamily(family.getId());
        } else {
            dao().saveFamily(family);
        }
        person.getManagedUsers().clear();
        person.setFamilyId(null);
    }

    /** The PII scrub, then the soft delete the DAO already honours (lookups, email index, search tokens). */
    private void scrubAndSoftDelete(final Person person) throws IOException {
        person.setFirst(DELETED_FIRST);
        person.setLast(DELETED_LAST);
        person.setNickname(null);
        person.setMiddle(null);
        person.setEmail(null);
        person.setCell(null);
        person.setTsa(null);
        person.setAddress(null);
        person.setPassport(null);
        person.setNotes(null);
        person.setBirthdate(null);
        person.setEmergencyContactName(null);
        person.setEmergencyContactPhone(null);
        person.setProfilePhotoSlot(null);
        person.setPrivacy(new PrivacySettings());
        person.getManagedUsers().clear();
        person.setFamilyId(null);
        person.setOrgIds(new ArrayList<>());
        person.delete();
        if (!Boolean.TRUE.equals(dao().savePerson(person))) {
            throw new IOException("The person row was not saved.");
        }
    }

    // ------------------------------------------------------------------ the notice

    /**
     * One notice per organization the person (or a dependent) had trips or money with, each limited to that
     * organization's rows; a person with neither gets one notice to the Site email. Best effort with a
     * fallback: a failed send is written to the trail as an ALARM carrying the whole report.
     */
    private void notifyOrganizations(final Person person, final List<Person> dependents, final Preview preview,
            final AuditActor who) {
        final List<Person> everyone = new ArrayList<>();
        everyone.add(person);
        everyone.addAll(dependents);
        final Map<String, List<TripLine>> tripsByOrg = new LinkedHashMap<>();
        final Map<String, List<Transaction>> txByOrg = new LinkedHashMap<>();
        for (final Person each : everyone) {
            for (final TripLine trip : tripLines(each.getId())) {
                tripsByOrg.computeIfAbsent(Util.orDefault(trip.orgId(), ""), key -> new ArrayList<>()).add(trip);
            }
            for (final Transaction tx : transactionsOf(each.getId())) {
                txByOrg.computeIfAbsent(Util.orDefault(tx.getOrgId(), ""), key -> new ArrayList<>()).add(tx);
            }
        }
        final Set<String> orgIds = new LinkedHashSet<>();
        for (final Organization.Id id : person.getOrgIds()) {
            orgIds.add(id.getValue());
        }
        orgIds.addAll(tripsByOrg.keySet());
        orgIds.addAll(txByOrg.keySet());
        orgIds.remove("");
        if (orgIds.isEmpty()) {
            orgIds.add("");
        }
        final List<TripLine> orglessTrips = tripsByOrg.getOrDefault("", List.of());
        final List<Transaction> orglessTx = txByOrg.getOrDefault("", List.of());
        boolean first = true;
        for (final String orgId : orgIds) {
            final Organization org = orgId.isEmpty() ? null
                    : dao().getOrganization(Organization.Id.from(orgId), Cached.NO).orElse(null);
            final List<TripLine> trips = new ArrayList<>(tripsByOrg.getOrDefault(orgId, List.of()));
            final List<Transaction> txs = new ArrayList<>(txByOrg.getOrDefault(orgId, List.of()));
            if (first) {
                // Rows stamped with no organization (legacy) ride with the first notice rather than vanish.
                if (!orgId.isEmpty()) {
                    trips.addAll(orglessTrips);
                    txs.addAll(orglessTx);
                }
                first = false;
            }
            final String subject = "Account deleted: " + displayName(person) + " (" + Util.orDefault(person.getEmail(),
                    "no email") + ")";
            final String body = noticeBody(person, dependents, org, trips, txs, preview);
            sendNotice(org, subject, body, person, who);
        }
    }

    private void sendNotice(final Organization org, final String subject, final String body, final Person person,
            final AuditActor who) {
        try {
            final MailAddressCommands addresses = addressSource.get();
            final String to = addresses.orgRecipient(KnownSettings.ACCOUNT_DELETE_NOTIFY_EMAIL, org);
            final String from = addresses.from(KnownSettings.ACCOUNT_DELETE_NOTIFY_FROM);
            final String bcc = config.getString(KnownSettings.MODERATION_PLATFORM_EMAIL);
            if (to == null || to.isBlank()) {
                throw new IllegalStateException("no recipient for the account-deletion notice");
            }
            mailSource.get().send(from, to, bcc == null || bcc.isBlank() ? null : bcc, null, subject, body, who);
        } catch (final RuntimeException ex) {
            log.warn("Account-deletion notice not sent ({}); recording it in the audit trail instead", subject, ex);
            Audit.builder(AuditAction.ALARM, AuditOutcome.FAILURE).actor(who)
                    .targetPerson(person.getEmail(), person.getId().getValue())
                    .org(org == null || org.getId() == null ? null : org.getId().getValue())
                    .message("account.delete: notice NOT mailed (" + ex.getMessage() + "). " + subject + " -- "
                            + stripTags(body)).log();
        }
    }

    private String noticeBody(final Person person, final List<Person> dependents, final Organization org,
            final List<TripLine> trips, final List<Transaction> txs, final Preview preview) {
        final StringBuilder html = new StringBuilder(4096);
        html.append("<p><b>").append(esc(displayName(person))).append("</b> (")
                .append(esc(Util.orDefault(person.getEmail(), "no email"))).append(", id ")
                .append(esc(person.getId().getValue())).append(") deleted their account on ")
                .append(LocalDateTime.now().format(DATE)).append(" from the UniteTrip app.");
        if (org != null) {
            html.append(" This notice covers their history with <b>").append(esc(org.getName())).append("</b>.");
        }
        html.append("</p>");
        if (!dependents.isEmpty()) {
            html.append("<p>Deleted with them, as family members they managed with no login of their own: ");
            html.append(esc(String.join(", ", dependents.stream().map(AccountDeletionCommands::displayName).toList())));
            html.append(".</p>");
        }
        html.append("<h3>Trips</h3>");
        if (trips.isEmpty()) {
            html.append("<p>None.</p>");
        } else {
            html.append("<table cellpadding=\"4\" border=\"1\" cellspacing=\"0\">"
                    + "<tr><th align=\"left\">Trip</th><th>Dates</th><th>Registration</th><th>When</th></tr>");
            for (final TripLine trip : trips) {
                html.append("<tr><td>").append(esc(trip.title())).append("</td><td>")
                        .append(trip.start() == null ? "" : trip.start().format(DATE)).append(" – ")
                        .append(trip.end() == null ? "" : trip.end().format(DATE)).append("</td><td>")
                        .append(esc(trip.status())).append("</td><td>")
                        .append(trip.ended() ? "Past" : "<b>Upcoming / in progress</b>").append("</td></tr>");
            }
            html.append("</table>");
        }
        html.append("<h3>Transactions</h3>");
        long balance = 0L;
        if (txs.isEmpty()) {
            html.append("<p>None.</p>");
        } else {
            txs.sort(Comparator.comparing(AccountDeletionCommands::dateOrMin));
            html.append("<table cellpadding=\"4\" border=\"1\" cellspacing=\"0\">"
                    + "<tr><th>Date</th><th>Type</th><th align=\"left\">Description</th>"
                    + "<th align=\"right\">Amount</th></tr>");
            for (final Transaction tx : txs) {
                final long cents = shareCents(tx);
                balance += cents;
                html.append("<tr><td>").append(tx.getTxDate() == null ? "" : tx.getTxDate().format(DATE))
                        .append("</td><td>").append(tx.getTxType() == null ? "" : tx.getTxType().name())
                        .append("</td><td>").append(esc(Util.orDefault(tx.getCategory(), "")))
                        .append(tx.getNote() == null || tx.getNote().isBlank() ? "" : " – " + esc(tx.getNote()))
                        .append("</td><td align=\"right\">").append(money(cents)).append("</td></tr>");
            }
            html.append("</table>");
        }
        html.append("<p style=\"font-size:1.1em\">");
        if (balance < 0) {
            html.append("<b>AMOUNT OWED BY TRAVELER: ").append(money(-balance)).append("</b>");
        } else if (balance > 0) {
            html.append("<b>AMOUNT OWED TO TRAVELER: ").append(money(balance)).append("</b>");
        } else {
            html.append("<b>Balance: settled</b>");
        }
        html.append("</p>");
        html.append("<p>Their profile is now anonymized (\"Deleted Account\"); the transaction rows above stay "
                + "in the system under the same id for the organization's accounting. Their registrations, "
                + "login, messages, photos and comments are gone and cannot be restored.</p>");
        return html.toString();
    }

    // ------------------------------------------------------------------ lookups

    private Person load(final Person.Id personId) {
        return personId == null ? null : dao().getPerson(personId, Cached.NO).orElse(null);
    }

    /**
     * Every trip the person is on OR has a live registration for. The membership index alone missed a
     * PENDING registration (not membership), so an upcoming trip the person was merely waiting on neither
     * blocked the deletion nor reached the notice.
     */
    private List<TripLine> tripLines(final Person.Id personId) {
        final LocalDateTime now = LocalDateTime.now();
        final List<TripLine> lines = new ArrayList<>();
        for (final Trip trip : dao().getRecentTrips(0, Cached.NO)) {
            final boolean member = trip.getPeople() != null && trip.getPeople().contains(personId);
            final Optional<Registration> registration = dao().getRegistration(trip.getId(), personId, Cached.NO);
            final boolean filed = registration.isPresent()
                    && registration.get().getStatus() != Registration.Status.NOT_REGISTERED;
            if (!member && !filed) {
                continue;
            }
            final String status = registration.map(Registration::getStatus)
                    .map(Registration.Status::getDescription)
                    .orElse("On the trip");
            final boolean ended = trip.getEndDate() != null && trip.getEndDate().isBefore(now);
            lines.add(new TripLine(trip.getId(), Util.orDefault(trip.getTitle(), trip.getId()), trip.getOrgId(),
                    trip.getStartDate(), trip.getEndDate(), status, ended));
        }
        return lines;
    }

    private List<Transaction> transactionsOf(final Person.Id personId) {
        return dao().getTransactions(personId, Cached.NO).stream()
                .filter(tx -> tx != null && tx.getDeleted() == null)
                .toList();
    }

    /** Signed balance per organization id ("" for org-less rows), in cents: negative means they owe. */
    private Map<String, Long> balancesByOrg(final Person.Id personId) {
        final Map<String, Long> balances = new LinkedHashMap<>();
        for (final Transaction tx : transactionsOf(personId)) {
            balances.merge(Util.orDefault(tx.getOrgId(), ""), shareCents(tx), Long::sum);
        }
        return balances;
    }

    /**
     * This person's share of a row in cents: a shared row is split evenly across its group, the same rule
     * {@code TransactionsCommands.getUserAmount} applies (re-stated here because that bean needs CDI injection).
     */
    static long shareCents(final Transaction tx) {
        if (tx.getAmount() == null) {
            return 0L;
        }
        final double amount = tx.getAmount();
        final int people = tx.getGroupPeople() == null ? 0 : tx.getGroupPeople().size();
        final double share = tx.isShared() && people > 1 ? amount / people : amount;
        return Math.round(share * 100d);
    }

    /**
     * Family members this person alone is responsible for: created members with no login of their own and
     * no other manager. They cannot outlive the account, so they are deleted with it (and block it while
     * they are unsettled).
     */
    private List<Person> dependents(final Person person) {
        final Family family = familyOf(person);
        if (family == null || !family.isManager(person.getId())) {
            return List.of();
        }
        final boolean otherManagers = family.getManagerIds().stream()
                .anyMatch(id -> !id.equals(person.getId()));
        if (otherManagers) {
            return List.of();
        }
        final List<Person> out = new ArrayList<>();
        for (final Person.Id memberId : family.getMemberIds()) {
            if (memberId.equals(person.getId())) {
                continue;
            }
            final Person member = dao().getPerson(memberId, Cached.NO).orElse(null);
            if (member == null || family.isManager(memberId)) {
                continue;
            }
            final boolean hasLogin = member.getEmail() != null && !member.getEmail().isBlank()
                    && dao().getCredsForCodeLogin(member.getEmail(), Cached.NO) != null;
            if (!hasLogin) {
                out.add(member);
            }
        }
        return out;
    }

    private Family familyOf(final Person person) {
        return person.getFamilyId() == null ? null : dao().getFamily(person.getFamilyId(), Cached.NO).orElse(null);
    }

    private List<Organization> organizationsOf(final Person person) {
        final List<Organization> out = new ArrayList<>();
        for (final Organization.Id orgId : person.getOrgIds()) {
            dao().getOrganization(orgId, Cached.NO).ifPresent(out::add);
        }
        return out;
    }

    private String orgName(final String orgId) {
        if (orgId == null || orgId.isEmpty()) {
            return "";
        }
        return dao().getOrganization(Organization.Id.from(orgId), Cached.YES)
                .map(Organization::getName).orElse(orgId);
    }

    /** Whom to contact about a blocked deletion: the first blocking trip's organization, else the Site. */
    private String contactEmail(final Person person, final List<TripLine> trips) {
        final MailAddressCommands addresses = addressSource.get();
        for (final TripLine trip : trips) {
            if (!trip.ended() && trip.orgId() != null) {
                final Organization org = dao().getOrganization(Organization.Id.from(trip.orgId()), Cached.YES)
                        .orElse(null);
                final String email = addresses.contactEmailOf(org);
                if (email != null) {
                    return email;
                }
            }
        }
        for (final Organization org : organizationsOf(person)) {
            final String email = addresses.contactEmailOf(org);
            if (email != null) {
                return email;
            }
        }
        return addresses.siteBare();
    }

    // ------------------------------------------------------------------ helpers

    /** Seam for tests that swap the store; production is the singleton. */
    protected DAO dao() {
        return DAO.getInstance();
    }

    /** Undated rows sort first: a legacy row without a date belongs at the top of a history, not at the end. */
    private static LocalDateTime dateOrMin(final Transaction tx) {
        return tx.getTxDate() == null ? LocalDateTime.MIN : tx.getTxDate();
    }

    static String displayName(final Person person) {
        return (Util.orDefault(person.getFirst(), "") + " " + Util.orDefault(person.getLast(), "")).strip();
    }

    static String money(final long cents) {
        return String.format(Locale.US, "$%,.2f", cents / 100d);
    }

    private static String esc(final String value) {
        return ModerationCommands.escape(value);
    }

    private static String stripTags(final String html) {
        return html == null ? "" : html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    /** For the preview endpoint: the block reasons as wire maps, without leaking anything but names. */
    public static List<Map<String, Object>> reasonsBody(final Preview preview) {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final BlockReason reason : preview.reasons()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", reason.code());
            row.put("tripId", reason.tripId());
            row.put("title", reason.tripTitle());
            row.put("amountOwedCents", reason.amountOwedCents());
            row.put("personName", reason.personName());
            out.add(row);
        }
        return out;
    }

    /** The map the resource answers for a preview; kept here so the refusal and the GET agree byte for byte. */
    public static Map<String, Object> previewBody(final Preview preview) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("blocked", preview.blocked());
        body.put("settleRequired", preview.settleRequired());
        body.put("reasons", reasonsBody(preview));
        body.put("amountOwedCents", preview.amountOwedCents());
        body.put("amountCreditCents", preview.amountCreditCents());
        body.put("contactEmail", preview.contactEmail());
        final List<Map<String, Object>> trips = new ArrayList<>();
        for (final TripLine trip : preview.trips()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("tripId", trip.tripId());
            row.put("title", trip.title());
            row.put("status", trip.status());
            row.put("ended", trip.ended());
            trips.add(row);
        }
        body.put("trips", trips);
        body.put("erased", preview.erased());
        body.put("retained", preview.retained());
        return body;
    }
}
