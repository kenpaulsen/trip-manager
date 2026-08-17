package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.cache.Cached;

@Slf4j
@Named("reg")
@ApplicationScoped
public class RegistrationCommands {
    private static final String ROOM = "room";

    private final java.util.function.Supplier<Caller> callerSource;

    public RegistrationCommands() {
        this(Caller::current);
    }

    /** Test seam (the FamilyCommands pattern): {@code Caller.current()} needs a FacesContext tests lack. */
    public RegistrationCommands(final java.util.function.Supplier<Caller> callerSource) {
        this.callerSource = callerSource;
    }

    public Registration createRegistration(final String tripId, final Person.Id userId) {
        return new Registration(tripId, userId);
    }

    public boolean saveRegistration(final Registration reg) {
        boolean result;
        try {
            result = DAO.getInstance().saveRegistration(reg);
        } catch (final RuntimeException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Error saving registration for '" + reg.getUserId() + "': " + reg.getTripId(),
                    ex.getMessage());
            log.error("Error while saving registration: ", ex);
            result = false;
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Unable to save registration '" + reg.getUserId() + "': " + reg.getTripId(), ex.getMessage());
            log.warn("Error while saving registration: ", ex);
            result = false;
        }
        return result;
    }

    /**
     * Registers every SELECTED traveler in one family submit: one ordinary PENDING row per traveler (the
     * admin approve flow, rosters, and exports see nothing new), stamped with who registered them and a
     * shared party id so the admin page can group the family. Validation is per traveler and all-or-nothing
     * per traveler (a refused traveler is reported and skipped; the others still register).
     *
     * @param trip     the page's in-hand Trip -- never re-read here (the stale-cache rule)
     * @param selected personId value -> Boolean, the "Who's going?" checkboxes
     * @param regs     personId value -> that traveler's transient Registration carrying the page's answers
     * @return the travelers actually registered (empty, with faces messages, when none were)
     */
    public List<Person> registerParty(final org.paulsens.trip.model.Trip trip,
            final java.util.Map<String, Object> selected,
            final java.util.Map<String, Registration> regs) {
        return registerParty(trip, selected, regs, null);
    }

    /**
     * @param digests personId value -> Boolean daily-digest answers, parked on each registration BEFORE its
     *                save (approval is what turns the answer into a real chat preference); null skips.
     */
    public List<Person> registerParty(final org.paulsens.trip.model.Trip trip,
            final java.util.Map<String, Object> selected,
            final java.util.Map<String, Registration> regs,
            final java.util.Map<String, Object> digests) {
        final List<Person> registered = new java.util.ArrayList<>();
        final Person me = currentPerson();
        if (trip == null || me == null || selected == null || regs == null) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Nothing registered",
                    "Select at least one traveler.");
            return registered;
        }
        final String party = java.util.UUID.randomUUID().toString();
        final PersonCommands people = PersonCommands.getPersonCommands();
        for (final java.util.Map.Entry<String, Object> entry : selected.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            final Person.Id travelerId = Person.Id.from(entry.getKey());
            final Person traveler = people.getPerson(travelerId);
            if (!canRegisterFor(me, travelerId)) {
                TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not registered",
                        "You cannot register " + traveler.getPreferredName() + ".");
                continue;
            }
            final Registration reg = regs.get(entry.getKey());
            if (reg == null || reg.getStatus() != Registration.Status.NOT_REGISTERED) {
                continue;   // already pending/confirmed: the page shows their status instead of a form
            }
            // A traveler already ON the roster bypasses canJoin (which is false for them by definition):
            // people get added to trips by hand, and filing/maintaining their registration row afterwards
            // must keep working -- the pre-merge single-traveler page allowed exactly that.
            if (!trip.canJoin(travelerId) && !trip.getPeople().contains(travelerId)) {
                TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, "Not registered",
                        traveler.getPreferredName() + " cannot join this trip.");
                continue;
            }
            reg.getOptions().put(Registration.OPT_REGISTERED_BY, me.getId().getValue());
            reg.getOptions().put(Registration.OPT_PARTY, party);
            if (digests != null && trip.getChatEnabled()) {
                new ChatCommands().setDigestChoice(reg, Boolean.TRUE.equals(digests.get(entry.getKey())));
            }
            if (saveRegistration(reg.withStatusString("Pending"))) {
                registered.add(traveler);
            }
        }
        final List<String> updated = saveResponseEdits(trip, regs, digests, me, people);
        if (registered.isEmpty() && updated.isEmpty()) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, "Nothing registered",
                    "No travelers were registered.");
        } else if (!updated.isEmpty()) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO, "Changes saved",
                    "Updated responses for " + String.join(", ", updated) + ".");
        }
        return registered;
    }

    /**
     * The re-edit path for travelers who are ALREADY registered (pending or confirmed): gated on the
     * {@code reg.allowEdits} setting, and applied onto the freshly-read STORED row rather than saving the
     * page's draft object -- the draft's status can be behind the store (an admin may have approved
     * meanwhile), and saving the draft wholesale would silently revert that. Only the form's own fields
     * move: the visible option answers and (while chat is enabled) the parked digest choice; status and
     * reserved keys are untouched. No-op rows are detected and skipped, so an untouched submit writes
     * nothing and says nothing.
     *
     * @return the preferred names whose registrations were actually updated
     */
    private List<String> saveResponseEdits(final org.paulsens.trip.model.Trip trip,
            final java.util.Map<String, Registration> regs,
            final java.util.Map<String, Object> digests,
            final Person me, final PersonCommands people) {
        final List<String> updated = new java.util.ArrayList<>();
        if (!new ConfigCommands().getBoolean(org.paulsens.trip.config.KnownSettings.REG_ALLOW_EDITS)) {
            return updated;
        }
        final ChatCommands chat = new ChatCommands();
        for (final java.util.Map.Entry<String, Registration> entry : regs.entrySet()) {
            final Registration draftReg = entry.getValue();
            if (draftReg == null || draftReg.getStatus() == Registration.Status.NOT_REGISTERED) {
                continue;
            }
            final Person.Id who = Person.Id.from(entry.getKey());
            if (!canRegisterFor(me, who)) {
                continue;
            }
            final Registration stored = DAO.getInstance().getRegistration(trip.getId(), who, Cached.NO).orElse(null);
            if (stored == null || stored.getStatus() == Registration.Status.NOT_REGISTERED) {
                continue;   // the store no longer agrees they are registered; nothing to edit
            }
            boolean changed = applyOptionEdits(trip, stored, draftReg);
            if (digests != null && trip.getChatEnabled() && digests.containsKey(entry.getKey())) {
                final boolean wanted = Boolean.TRUE.equals(digests.get(entry.getKey()));
                if (chat.digestChoice(stored) != wanted) {
                    chat.setDigestChoice(stored, wanted);
                    changed = true;
                }
            }
            if (changed && saveRegistration(stored)) {
                updated.add(people.getPerson(who).getPreferredName());
                regs.put(entry.getKey(), stored);   // the page's (session-draft) copy follows the saved row
            }
        }
        return updated;
    }

    /** Copies the form's visible option answers onto the stored row; everything else is untouched. */
    private static boolean applyOptionEdits(final org.paulsens.trip.model.Trip trip, final Registration stored,
            final Registration draftReg) {
        boolean changed = false;
        if (trip.getRegOptions() == null) {
            return false;
        }
        for (final org.paulsens.trip.model.RegistrationOption opt : trip.getRegOptions()) {
            final String key = opt.getKey();
            final String value = draftReg.getOptions().get(key);
            if (!java.util.Objects.equals(value, stored.getOptions().get(key))) {
                if (value == null) {
                    stored.getOptions().remove(key);
                } else {
                    stored.getOptions().put(key, value);
                }
                changed = true;
            }
        }
        return changed;
    }

    /** Session key prefix for {@link #loadDraft}: one draft per trip. */
    public static final String DRAFT_KEY_PREFIX = "regDraft:";

    /** {@link #loadDraft} for the single-traveler page: the same drafting, a party of one. */
    public org.paulsens.trip.model.RegistrationDraft loadSingleDraft(final org.paulsens.trip.model.Trip trip,
            final Person person) {
        return loadDraft(trip, person == null ? List.of() : List.of(person));
    }

    /**
     * Loads (or starts) the SESSION-held draft of this trip's registration form and reconciles it with the
     * store. A member whose saved registration is still NOT_REGISTERED keeps their drafted answers, selection,
     * and digest choice from the prior visit; anyone the store says has moved on gets the stored row -- a
     * draft must never resurrect or overwrite real state. Members with no draft entry (the whole point: they
     * were just added on the add-a-family-member detour) get defaults: selected only when they are the caller,
     * daily digest ON. Draft entries for people outside {@code members} are carried, not dropped, so a
     * single-traveler visit (an {@code ?id=} link) cannot wipe the rest of the family's typed answers.
     */
    public org.paulsens.trip.model.RegistrationDraft loadDraft(final org.paulsens.trip.model.Trip trip,
            final List<Person> members) {
        final org.paulsens.trip.model.RegistrationDraft draft = new org.paulsens.trip.model.RegistrationDraft();
        if (trip == null || members == null) {
            return draft;
        }
        final Object prior = org.paulsens.trip.util.ScopeUtil.getInstance()
                .getSessionMap(DRAFT_KEY_PREFIX + trip.getId());
        final org.paulsens.trip.model.RegistrationDraft old =
                (prior instanceof org.paulsens.trip.model.RegistrationDraft d) ? d : null;
        final Caller caller = callerSource.get();
        final ChatCommands chat = new ChatCommands();
        for (final Person member : members) {
            if (member == null || member.getId() == null) {
                continue;
            }
            final String key = member.getId().getValue();
            final Registration fresh = getRegistration(trip.getId(), member.getId());
            final Registration oldReg = (old == null) ? null : old.getRegs().get(key);
            if (oldReg != null && fresh != null && oldReg.getStatus() == fresh.getStatus()) {
                // Same status in the draft and the store: keep the drafted answers (typed options,
                // selection, digest) -- this is what lets a detour, or an in-progress EDIT of an
                // already-registered row, survive navigation. On ANY status change the store wins
                // outright: a draft must never resurrect or overwrite a registration that moved on.
                draft.getRegs().put(key, oldReg);
                draft.getSel().put(key, Boolean.TRUE.equals(old.getSel().get(key)));
                draft.getDigest().put(key, Boolean.TRUE.equals(old.getDigest().get(key)));
            } else {
                draft.getRegs().put(key, fresh);
                draft.getSel().put(key, caller.isAuthenticated() && member.getId().equals(caller.personId()));
                draft.getDigest().put(key, chat.digestChoiceOrDefault(fresh));
            }
        }
        if (old != null) {
            for (final java.util.Map.Entry<String, Registration> entry : old.getRegs().entrySet()) {
                if (!draft.getRegs().containsKey(entry.getKey()) && stillDraftable(entry.getValue())) {
                    draft.getRegs().put(entry.getKey(), entry.getValue());
                    draft.getSel().put(entry.getKey(), Boolean.TRUE.equals(old.getSel().get(entry.getKey())));
                    draft.getDigest().put(entry.getKey(),
                            Boolean.TRUE.equals(old.getDigest().get(entry.getKey())));
                }
            }
        }
        final jakarta.faces.context.FacesContext ctx = jakarta.faces.context.FacesContext.getCurrentInstance();
        if (ctx != null) {
            ctx.getExternalContext().getSessionMap().put(DRAFT_KEY_PREFIX + trip.getId(), draft);
        }
        return draft;
    }

    private static boolean stillDraftable(final Registration reg) {
        return reg != null && reg.getStatus() == Registration.Status.NOT_REGISTERED;
    }

    /** Whether any page-held registration is still NOT_REGISTERED -- arms the continue-reminder banner. */
    public boolean anyUnregistered(final java.util.Map<String, Registration> regs) {
        if (regs != null) {
            for (final Registration reg : regs.values()) {
                if (reg != null && reg.getStatus() == Registration.Status.NOT_REGISTERED) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Plain-text summary of a party submit, for the ONE internal notification email. */
    public String partySummary(final List<Person> travelers, final org.paulsens.trip.model.Trip trip) {
        final StringBuilder text = new StringBuilder("New registration for '")
                .append(trip == null ? "?" : trip.getTitle()).append("':\n");
        for (final Person traveler : travelers) {
            text.append(" - ").append(AuditCommands.describe(traveler)).append('\n');
        }
        return text.toString();
    }

    /** Tokens for the registration-received MAIL template: one email to the submitting owner. */
    public java.util.Map<String, Object> receivedMailValues(final org.paulsens.trip.model.Trip trip,
            final List<Person> travelers) {
        final StringBuilder block = new StringBuilder("<ul>");
        for (final Person traveler : travelers) {
            block.append("<li>").append(org.paulsens.trip.chat.MailTemplates.escape(
                    traveler.getPreferredName() + " " + (traveler.getLast() == null ? "" : traveler.getLast())))
                    .append("</li>");
        }
        block.append("</ul>");
        return java.util.Map.of(
                "tripTitle", trip.getTitle() == null ? "" : trip.getTitle(),
                "tripUrl", regBaseUrl() + "/trip/tripDetails.jsf?trip=" + trip.getId(),
                "travelersBlock", new org.paulsens.trip.chat.MailTemplates.Raw(block.toString()));
    }

    /** Tokens for the registration-approved MAIL template. */
    public java.util.Map<String, Object> approvedMailValues(final org.paulsens.trip.model.Trip trip,
            final Person person) {
        return java.util.Map.of(
                "tripTitle", trip.getTitle() == null ? "" : trip.getTitle(),
                "firstName", person.getPreferredName() == null ? "" : person.getPreferredName(),
                "itineraryUrl", regBaseUrl() + "/trip/itinerary.jsf?trip=" + trip.getId()
                        + "&id=" + person.getId().getValue(),
                "profileUrl", regBaseUrl() + "/account/person.jsf?id=" + person.getId().getValue());
    }

    /**
     * Where the approval email goes: the approved person when they have a usable address, else ALL of their
     * family's managers with usable addresses (comma-joined, ready for {@code MailCommands.send}), else
     * nobody -- and "nobody" must never block the approval itself.
     *
     * <p>This replaced the registered-by fallback (2026-08-12): the family row, not the registration stamp,
     * is the source of truth for who answers for a traveler without a mailbox, and every mailable manager is
     * included rather than guessing which one reads mail.
     */
    public String approvalRecipients(final Person person) {
        if (person != null && org.paulsens.trip.util.EmailAddresses.isValid(person.getEmail())) {
            return person.getEmail();
        }
        final List<Person> managers = mailableManagers(person);
        if (managers.isEmpty()) {
            return null;
        }
        return managers.stream().map(Person::getEmail).collect(java.util.stream.Collectors.joining(","));
    }

    /** Whether approving this person can send an email at all -- drives the dialog checkbox's enablement. */
    public boolean canEmailApproval(final Person person) {
        return approvalRecipients(person) != null;
    }

    /**
     * The text beside the approval dialog's send-email checkbox. Three shapes, one per recipient outcome:
     * the person's own address, the family managers standing in for them, or a statement that no email can
     * go out (the disabled-checkbox case). Built here rather than in EL so the browser test, the unit test,
     * and the page render the exact same words.
     */
    public String approvalEmailLabel(final Person person) {
        if (person == null) {
            return "";
        }
        final String name = displayName(person);
        if (org.paulsens.trip.util.EmailAddresses.isValid(person.getEmail())) {
            return "Send approval email to " + name + " (" + person.getEmail() + ")";
        }
        final List<Person> managers = mailableManagers(person);
        if (managers.isEmpty()) {
            return name + " will not receive an email because they do not have a valid email address.";
        }
        final String managerList = managers.stream()
                .map(this::nameAndAddress)
                .collect(java.util.stream.Collectors.joining(", "));
        return "Send approval email for " + name + " to family manager"
                + (managers.size() > 1 ? "s " : " ") + managerList;
    }

    private String nameAndAddress(final Person manager) {
        return displayName(manager) + " (" + manager.getEmail() + ")";
    }

    private static String displayName(final Person person) {
        final String last = person.getLast();
        return person.getPreferredName() + ((last == null || last.isBlank()) ? "" : " " + last);
    }

    /** One source with the display fallback ({@code PersonCommands.contactEmail}) -- they must not drift. */
    private List<Person> mailableManagers(final Person person) {
        return PersonCommands.getPersonCommands().mailableManagers(person);
    }

    /** "Registered by" label for the admin table: the submitting owner's name, or empty pre-family rows. */
    public String registeredByLabel(final Registration reg) {
        final String ownerId = (reg == null) ? null : reg.getRegisteredBy();
        if (ownerId == null || ownerId.equals(reg.getUserId().getValue())) {
            return "";
        }
        return PersonCommands.getPersonCommands().getPerson(Person.Id.from(ownerId)).getPreferredName();
    }

    private String regBaseUrl() {
        return new ConfigCommands().getString(org.paulsens.trip.config.KnownSettings.REG_MAIL_BASE_URL);
    }

    /**
     * Whether the signed-in user may register (or edit answers for) this traveler on the party view: self,
     * a managed user, or a site admin. The page's checkbox gating and the write path share this method so
     * the UI can never enable what the save refuses.
     */
    public boolean canRegister(final Person traveler) {
        return traveler != null && canRegisterFor(currentPerson(), traveler.getId());
    }

    private boolean canRegisterFor(final Person me, final Person.Id travelerId) {
        if (me == null || travelerId == null) {
            return false;
        }
        return PersonCommands.getPersonCommands().canAccessUserId(me, travelerId)
                || callerSource.get().isSiteAdmin();
    }

    private Person currentPerson() {
        final Caller caller = callerSource.get();
        return caller.isAuthenticated()
                ? DAO.getInstance().getPerson(caller.personId(), Cached.NO).orElse(null) : null;
    }

    public List<Registration> getRegistrations(final String tripId) {
        try {
            return DAO.getInstance().getRegistrations(tripId, Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("Failed to get registrations for trip '" + tripId + "'!", ex);
            return Collections.emptyList();
        }
    }

    /**
     * The scalar row model the admin trip-registrations table keeps in the VIEW. PrimeFaces sorts the
     * value list IN PLACE and row buttons decode by row position against that same list, so the list
     * must live across postbacks in the exact order the admin last saw -- but the session-scope policy
     * bans domain objects from view/session scope (viewScope rides into the serialized session, where
     * the next change to Registration's shape would invalidate every live session). Strings and a date
     * only; the real {@link Registration} and Person resolve per request from {@link #getUserId()}.
     * Plain mutable POJO with a no-arg constructor on purpose: Kryo session serialization restores
     * fields directly and runs no constructor (see BadgeImage for the precedent).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String userId;
        private LocalDateTime created;
        private String status;
        private String party;
    }

    /** The view-held rows for the admin registrations table -- scalars only, see {@link RegRow}. */
    public List<RegRow> rowsForTrip(final String tripId) {
        final List<RegRow> rows = new ArrayList<>();
        for (final Registration reg : getRegistrations(tripId)) {
            rows.add(new RegRow(reg.getUserId().getValue(), reg.getCreated(),
                    reg.getStatus().getDescription(), reg.getParty()));
        }
        return rows;
    }

    /**
     * One row's registration out of the request's SINGLE {@link #getRegistrations} read -- registrations
     * are deliberately uncached, so per-row lookups must not each go back to the store. Null when the
     * row's user no longer has a registration (deleted between render and this request).
     */
    public Registration rowRegistration(final List<Registration> regs, final String userId) {
        if (regs == null || userId == null) {
            return null;
        }
        for (final Registration reg : regs) {
            if (userId.equals(reg.getUserId().getValue())) {
                return reg;
            }
        }
        return null;
    }

    /** Row-model variant of {@link #registeredByLabel(Registration)} for the converted admin table. */
    public String registeredByLabel(final List<Registration> regs, final String userId) {
        return registeredByLabel(rowRegistration(regs, userId));
    }

    /** One option's submitted value for a row, or null when unanswered (drives the rendered flags). */
    public String regOptionValue(final List<Registration> regs, final String userId, final Object optId) {
        final Registration reg = rowRegistration(regs, userId);
        return (reg == null || optId == null) ? null : reg.getOptions().get(String.valueOf(optId));
    }

    public int getNumPending(final String tripId) {
        int result = 0;
        for (final Registration reg : getRegistrations(tripId)) {
            if (reg.getStatus() == Registration.Status.PENDING) {
                result++;
            }
        }
        return result;
    }

    /**
     * The landing-page registration chip's label: "Register" for the anonymous or unregistered, otherwise
     * the viewer's own status. A bean method (not page EL) so the accordion row stays free of nested
     * ternaries and repeated lookups.
     */
    public String getChipLabel(final String tripId, final Person.Id userId) {
        if (userId == null || tripId == null) {
            return "Register";
        }
        final Registration registration = getRegistration(tripId, userId);
        if (registration == null || registration.getStatus() == Registration.Status.NOT_REGISTERED) {
            return "Register";
        }
        return registration.getStatus().getDescription();
    }

    /** Whether the viewer has any registration on this trip -- drives the chip's registered styling. */
    public boolean isChipRegistered(final String tripId, final Person.Id userId) {
        if (userId == null || tripId == null) {
            return false;
        }
        final Registration registration = getRegistration(tripId, userId);
        return registration != null && registration.getStatus() != Registration.Status.NOT_REGISTERED;
    }

    public Registration getRegistration(final String tripId, final Person.Id userId) {
        if (tripId == null) {
            log.error("getRegistration() called with null tripId");
            return null;
        }
        if (userId == null) {
            log.error("getRegistration() called with null userId");
            return null;
        }
        try {
            return DAO.getInstance().getRegistration(tripId, userId, Cached.NO)
                    .orElse(createRegistration(tripId, userId));
        } catch (final RuntimeException ex) {
            log.error("Failed to get registration for user '" + userId.getValue()
                    + "' on trip '" + tripId + "'!", ex);
            return createRegistration(tripId, userId);
        }
    }

    public PersonDataValue getRoomPDV(final String tripId, final Person.Id userId) {
        if (tripId == null) {
            log.error("getRoom() called with null tripId");
            return null;
        }
        if (userId == null) {
            log.error("getRoom() called with null userId");
            return null;
        }
        PersonDataValue pdv = PersonDataValueCommands.getPersonDataValue(userId, getTripRoomDataId(tripId));
        if (pdv == null) {
            pdv = PersonDataValueCommands.createPersonDataValue(userId, getTripRoomDataId(tripId), ROOM);
            pdv.setContent("");
            PersonDataValueCommands.savePersonDataValue(pdv);
        }
        return pdv;
    }

    /**
     * Stores one person's room for a trip.
     *
     * <p>The room value is passed in rather than read back out of a previously-returned object, and that is the
     * whole point of the signature. The page used to bind an input straight to
     * {@code getRoomPDV(...).content} and then call a no-value {@code saveRoom}, which looked up the record
     * <em>again</em> and saved that. Two lookups return two objects: since the persistence redesign a read
     * deserializes a fresh one rather than handing back a shared instance, so the typed value was set on an
     * object nobody stored. The save reported success and the room reverted on reload.
     *
     * <p>The page comment at the time -- "must bind this way for the save button to work, needs to bind to the
     * real value" -- records exactly the assumption that stopped holding.
     */
    public boolean saveRoom(final String tripId, final Person.Id userId, final String room) {
        if (tripId == null || userId == null) {
            log.error("saveRoom() requires both a tripId and a userId");
            return false;
        }
        final PersonDataValue pdv = getRoomPDV(tripId, userId);
        if (pdv == null) {
            return false;
        }
        pdv.setContent((room == null) ? "" : room);
        return PersonDataValueCommands.savePersonDataValue(pdv);
    }

    private DataId getTripRoomDataId(final String tripId) {
        return DataId.from(ROOM + tripId);
    }
}
