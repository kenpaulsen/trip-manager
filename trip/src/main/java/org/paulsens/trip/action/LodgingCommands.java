package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.LodgingViews.AccommodationForm;
import org.paulsens.trip.action.LodgingViews.AccommodationRow;
import org.paulsens.trip.action.LodgingViews.AssignOutcome;
import org.paulsens.trip.action.LodgingViews.CancelPreview;
import org.paulsens.trip.action.LodgingViews.ContactHit;
import org.paulsens.trip.action.LodgingViews.ItineraryRow;
import org.paulsens.trip.action.LodgingViews.OccupantChip;
import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.action.LodgingViews.OfferRow;
import org.paulsens.trip.action.LodgingViews.PersonCard;
import org.paulsens.trip.action.LodgingViews.PlacementForm;
import org.paulsens.trip.action.LodgingViews.ReservationForm;
import org.paulsens.trip.action.LodgingViews.ReservationRow;
import org.paulsens.trip.action.LodgingViews.RoomBoard;
import org.paulsens.trip.action.LodgingViews.RoomCell;
import org.paulsens.trip.action.LodgingViews.RoomDetail;
import org.paulsens.trip.action.LodgingViews.RoomForm;
import org.paulsens.trip.action.LodgingViews.RoomRow;
import org.paulsens.trip.action.LodgingViews.RoomTypeForm;
import org.paulsens.trip.action.LodgingViews.RoomTypeRow;
import org.paulsens.trip.action.LodgingViews.RoomingRow;
import org.paulsens.trip.action.LodgingViews.StayWindowForm;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.HtmlFragmentValidator;
import org.paulsens.trip.content.RichTextRules;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Address;
import org.paulsens.trip.model.FloorMap;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PricingModel;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomType;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.pay.LodgingBiller;
import org.paulsens.trip.pay.LodgingPricing;
import org.paulsens.trip.pay.MoneyMath;
import org.paulsens.trip.util.EmailAddresses;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import static org.paulsens.trip.action.PageFeedback.callbackParam;
import static org.paulsens.trip.action.PageFeedback.error;
import static org.paulsens.trip.action.PageFeedback.info;
import static org.paulsens.trip.action.PageFeedback.refuse;
import static org.paulsens.trip.action.PageFeedback.refuseAction;
import static org.paulsens.trip.action.PageFeedback.warn;

/**
 * Lodging, exposed to pages as {@code #{lodging}}: accommodations (global hotels: inventory, floor plans,
 * photos, the contact person), per-trip offers, reservations with their ledger bills, the room-assignment
 * workspace, the rooming list, and the itinerary rows whose dates a reservation overrides. Feature doc:
 * {@code docs/lodging.md}.
 *
 * <p><b>Who may do what.</b> Site admins and GLOBAL {@code lodgingAdmin}s: everything. An org-scoped
 * {@code lodgingAdmin}: offers and reservations on that org's trips, and creating accommodations. A trip's
 * {@code tripMgr}: offers and reservations on that trip. {@code accommodationAdmin@{accId}} (the creator and
 * the hotel's contact): that one hotel's data. Every gate re-asks per call; nothing is inherited from a
 * dialog being open. Pages bind to the scalar forms and rows in {@link LodgingViews}; every save re-reads the
 * real row with {@code Cached.NO} and applies the form.
 *
 * <p><b>Money.</b> Bills follow every reservation write automatically (the {@code lodging.bills.autoRecompute}
 * setting, default on) through {@link LodgingBiller}; the explicit {@link #recomputeBills} stays for the
 * admin button and for the setting being off.
 */
@Slf4j
@Named("lodging")
@ApplicationScoped
public class LodgingCommands {
    static final int MAX_ROOMS = 500;
    private static final DateTimeFormatter STAY = DateTimeFormatter.ofPattern("MMM d, h:mm a");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private final Supplier<Caller> callerSource;
    private final Supplier<TripCommands> tripSource;
    private final Supplier<TransactionsCommands> txSource;
    private final Supplier<OrgCommands> orgSource;
    private final Supplier<PrivilegeCommands> privSource;
    private final Supplier<AuditCommands> auditSource;
    private final Supplier<MediaCommands> mediaSource;

    public LodgingCommands() {
        this(Caller::current);
    }

    /** Test seam (the FamilyCommands pattern): {@code Caller.current()} needs a FacesContext tests lack. */
    public LodgingCommands(final Supplier<Caller> callerSource) {
        // The org commands (and the trip commands' org seam) run as THIS bean's caller, so a contact's
        // membership write audits as the admin who created the hotel, never as an anonymous actor.
        this(callerSource, () -> new TripCommands(() -> new OrgCommands(callerSource)), TransactionsCommands::new,
                () -> new OrgCommands(callerSource), PrivilegeCommands::new, AuditCommands::new, MediaCommands::new);
    }

    LodgingCommands(final Supplier<Caller> callerSource, final Supplier<TripCommands> tripSource,
            final Supplier<TransactionsCommands> txSource, final Supplier<OrgCommands> orgSource,
            final Supplier<PrivilegeCommands> privSource, final Supplier<AuditCommands> auditSource,
            final Supplier<MediaCommands> mediaSource) {
        this.callerSource = callerSource;
        this.tripSource = tripSource;
        this.txSource = txSource;
        this.orgSource = orgSource;
        this.privSource = privSource;
        this.auditSource = auditSource;
        this.mediaSource = mediaSource;
    }

    // ================================================================== gates

    /** Site admin or GLOBAL lodging admin: every hotel, every trip. */
    public boolean isGlobalLodgingAdmin() {
        final Caller current = caller();
        return current.isAuthenticated() && (current.isSiteAdmin() || current.has(PrivilegeCommands.LODGING_ADMIN));
    }

    /**
     * May work the Assignments board: everyone who manages the trip's lodging, plus a trip-scoped
     * {@code lodgingManager}. That is the hotel's own staff: they room people and nothing else -- no lodging
     * options, no reservations, no prices, no bills, and no other trip. Creating a reservation stays with
     * the managers, because choosing the option that pays for a stay is choosing what the pilgrim is
     * charged.
     */
    public boolean canAssignRooms(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return false;
        }
        return canManageTripLodging(tripId) || caller().has(PrivilegeCommands.LODGING_MANAGER, tripId);
    }

    /** May edit THIS accommodation's data: global admins, or a holder of {@code accommodationAdmin@acc}. */
    public boolean canEditAccommodation(final String accId) {
        if (accId == null || accId.isBlank()) {
            return false;
        }
        return isGlobalLodgingAdmin() || caller().has(PrivilegeCommands.ACCOMMODATION_ADMIN, accId);
    }

    /** May create an accommodation: global admins, or a lodging admin of ANY organization. */
    public boolean canCreateAccommodation() {
        if (isGlobalLodgingAdmin()) {
            return true;
        }
        final Caller current = caller();
        if (!current.isAuthenticated()) {
            return false;
        }
        for (final Organization org : DAO.getInstance().getOrganizations(Cached.YES)) {
            if (current.has(PrivilegeCommands.LODGING_ADMIN, org.getId().getValue())) {
                return true;
            }
        }
        return false;
    }

    /** May manage offers and reservations on this trip: global, the trip's org's lodging admin, or its tripMgr. */
    public boolean canManageTripLodging(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return false;
        }
        final Caller current = caller();
        if (!current.isAuthenticated()) {
            return false;
        }
        if (isGlobalLodgingAdmin() || current.has(PrivilegeCommands.TRIP_MGR, tripId)) {
            return true;
        }
        final Trip trip = DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        return trip != null && trip.getOrgId() != null
                && current.has(PrivilegeCommands.LODGING_ADMIN, trip.getOrgId());
    }

    /** Whether the Lodging admin page (and its menu entry) opens for this caller at all. */
    public boolean canOpenLodgingAdmin() {
        return canCreateAccommodation() || !managedAccommodations().isEmpty();
    }

    /** The accommodations this caller may edit -- the "Accommodations you manage" list. */
    public List<Accommodation> managedAccommodations() {
        final List<Accommodation> mine = new ArrayList<>();
        if (!caller().isAuthenticated()) {
            return mine;
        }
        for (final Accommodation acc : DAO.getInstance().getAccommodations(Cached.YES)) {
            if (canEditAccommodation(acc.getId().getValue())) {
                mine.add(acc);
            }
        }
        return mine;
    }

    // ================================================================== accommodations

    /** Every non-retired accommodation, by name -- pickers and the "All accommodations" list. */
    public List<Accommodation> getAccommodations() {
        final List<Accommodation> all = new ArrayList<>();
        for (final Accommodation acc : DAO.getInstance().getAccommodations(Cached.YES)) {
            if (!acc.isRetired()) {
                all.add(acc);
            }
        }
        all.sort((a, b) -> nullSafe(a.getName()).compareToIgnoreCase(nullSafe(b.getName())));
        return all;
    }

    public List<AccommodationRow> accommodationRows() {
        final List<AccommodationRow> rows = new ArrayList<>();
        for (final Accommodation acc : getAccommodations()) {
            rows.add(rowOf(acc));
        }
        return rows;
    }

    public List<AccommodationRow> managedAccommodationRows() {
        final List<AccommodationRow> rows = new ArrayList<>();
        for (final Accommodation acc : managedAccommodations()) {
            rows.add(rowOf(acc));
        }
        return rows;
    }

    private AccommodationRow rowOf(final Accommodation acc) {
        return new AccommodationRow(acc.getId().getValue(), acc.getName(), place(acc.getAddress()),
                acc.getRooms().size(), acc.getRoomTypes().size(), acc.getOrgIds().size(),
                canEditAccommodation(acc.getId().getValue()), acc.isRetired());
    }

    /** Never-null bean convention: a blank accommodation under a fresh id on a miss (prove with id.equals). */
    public Accommodation getAccommodation(final String accId) {
        final Accommodation found = findAccommodation(accId);
        return (found == null) ? Accommodation.builder().build() : found;
    }

    /** Whether a pinned id names a real accommodation -- the page's "detail mode" switch. */
    public boolean accommodationExists(final String accId) {
        return findAccommodation(accId) != null;
    }

    /** Nullable, for Java. */
    public Accommodation findAccommodation(final String accId) {
        if (accId == null || accId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getAccommodation(Accommodation.Id.from(accId.trim()), Cached.YES).orElse(null);
    }

    /** Accommodations that look like this one already (normalized name/email/phone/website/address). */
    public List<AccommodationRow> findDuplicates(final AccommodationForm form) {
        final List<AccommodationRow> rows = new ArrayList<>();
        if (form == null) {
            return rows;
        }
        for (final Accommodation acc : getAccommodations()) {
            if (!Objects.equals(acc.getId().getValue(), form.getId()) && acc.matchesDiscovery(form.getName(),
                    form.getEmail(), form.getPhone(), form.getWebsite(), addressOf(form))) {
                rows.add(rowOf(acc));
            }
        }
        return rows;
    }

    public AccommodationForm accommodationFormFor(final String accId) {
        final AccommodationForm form = new AccommodationForm();
        final Accommodation acc = findAccommodation(accId);
        if (acc == null) {
            return form;
        }
        form.setId(acc.getId().getValue());
        form.setName(acc.getName());
        form.setDescription(acc.getDescription());
        form.setEmail(acc.getEmail());
        form.setPhone(acc.getPhone());
        form.setWebsite(acc.getWebsite());
        final Address address = acc.getAddress();
        form.setStreet(address.getStreet());
        form.setStreet2(address.getStreet2());
        form.setCity(address.getCity());
        form.setState(address.getState());
        form.setZip(address.getZip());
        form.setCountry(address.getCountry());
        if (acc.getContactId() != null) {
            final Person contact = DAO.getInstance().getPerson(acc.getContactId(), Cached.YES).orElse(null);
            if (contact != null) {
                form.setContactEmail(contact.getEmail());
                form.setContactFirst(contact.getFirst());
                form.setContactLast(contact.getLast());
            }
        }
        return form;
    }

    /**
     * Creates or edits an accommodation from the dialog's form. Create: the caller may create, the duplicate
     * check passes (or {@code force}), the contact is found-or-created into {@code orgIdContext}, and the
     * creator plus the contact are granted {@code accommodationAdmin} for it. Edit: the caller may edit it.
     *
     * @return the accommodation id, or "" when refused (a growl says why).
     */
    public String saveAccommodation(final AccommodationForm form, final String orgIdContext) {
        if (form == null || form.getName() == null || form.getName().isBlank()) {
            return refuseAction("A name is required.");
        }
        final boolean creating = (form.getId() == null || form.getId().isBlank());
        if (creating && !canCreateAccommodation()) {
            return refuseAction("Not allowed: only lodging admins can add an accommodation.");
        }
        if (!creating && !canEditAccommodation(form.getId())) {
            return refuseAction("Not allowed: you do not manage this accommodation.");
        }
        if (creating && !form.isForce() && !findDuplicates(form).isEmpty()) {
            return refuseAction("This accommodation may already exist. Choose it from the matches, or create anyway.");
        }
        final String shapeProblem = shapeProblem(form);
        if (shapeProblem != null) {
            return refuseAction(shapeProblem);
        }
        final Accommodation acc = creating ? Accommodation.builder().build()
                : DAO.getInstance().getAccommodation(Accommodation.Id.from(form.getId()), Cached.NO).orElse(null);
        if (acc == null) {
            return refuseAction("This accommodation no longer exists.");
        }
        applyForm(acc, form);
        if (form.getContactEmail() != null && !form.getContactEmail().isBlank()) {
            final Person.Id contact = findOrCreateContact(form.getContactEmail(), contactName(form), orgIdContext);
            if (contact == null) {
                return "";
            }
            acc.setContactId(contact);
        }
        if (creating) {
            acc.setCreatedBy(caller().personId());
            acc.setCreated(LocalDateTime.now());
            if (orgIdContext != null && !orgIdContext.isBlank()) {
                acc.getOrgIds().add(Organization.Id.from(orgIdContext));
            }
        }
        if (!store(acc)) {
            return "";
        }
        if (creating) {
            grantAccommodationAdmin(acc, caller().personId());
            grantAccommodationAdmin(acc, acc.getContactId());
        }
        auditSource.get().lodging(AuditEventBuilder.TARGET_ACCOMMODATION, acc.getId().getValue(), null,
                (creating ? "Created" : "Edited") + " accommodation '" + acc.getName() + "'", caller().auditActor());
        return acc.getId().getValue();
    }

    /** The email / phone / website shape checks the dialog cannot trust the browser for. */
    private static String shapeProblem(final AccommodationForm form) {
        if (form.getEmail() != null && !form.getEmail().isBlank() && !EmailAddresses.isValid(form.getEmail())) {
            return "'" + form.getEmail() + "' is not an email address.";
        }
        if (form.getContactEmail() != null && !form.getContactEmail().isBlank()
                && !EmailAddresses.isValid(form.getContactEmail())) {
            return "'" + form.getContactEmail() + "' is not an email address.";
        }
        if (form.getWebsite() != null && form.getWebsite().contains(" ")) {
            return "'" + form.getWebsite() + "' is not a web address.";
        }
        return null;
    }

    private static void applyForm(final Accommodation acc, final AccommodationForm form) {
        acc.setName(form.getName().trim());
        acc.setDescription(form.getDescription());
        acc.setEmail(blankToNull(form.getEmail()) == null ? null : form.getEmail().trim().toLowerCase(Locale.ROOT));
        acc.setPhone(blankToNull(form.getPhone()));
        acc.setWebsite(blankToNull(form.getWebsite()));
        acc.setAddress(addressOf(form));
    }

    private static Address addressOf(final AccommodationForm form) {
        final Address address = new Address(blankToNull(form.getStreet()), blankToNull(form.getCity()),
                blankToNull(form.getState()), blankToNull(form.getZip()));
        address.setStreet2(blankToNull(form.getStreet2()));
        address.setCountry(blankToNull(form.getCountry()));
        return address;
    }

    private static String contactName(final AccommodationForm form) {
        return (nullSafe(form.getContactFirst()) + " " + nullSafe(form.getContactLast())).trim();
    }

    /** Retires (or un-retires) an accommodation; there is deliberately no delete. */
    public boolean retireAccommodation(final String accId, final boolean retired) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        final Accommodation acc = freshAccommodation(accId);
        if (acc == null) {
            return refuse("This accommodation no longer exists.");
        }
        acc.setRetired(retired ? Boolean.TRUE : null);
        if (!store(acc)) {
            return false;
        }
        auditSource.get().lodging(AuditEventBuilder.TARGET_ACCOMMODATION, accId, null,
                (retired ? "Retired" : "Reinstated") + " accommodation '" + acc.getName() + "'", caller().auditActor());
        return true;
    }

    // ------------------------------------------------------------------ contact

    /** Who owns that email, for the dialog's Find button: found (with a name) or not. */
    public ContactHit findContact(final String email) {
        if (!EmailAddresses.isValid(email)) {
            return new ContactHit(false, null, null);
        }
        final Person existing = DAO.getInstance().getPersonByEmail(email.trim(), Cached.NO);
        return (existing == null) ? new ContactHit(false, null, null)
                : new ContactHit(true, existing.getId().getValue(), displayName(existing));
    }

    /**
     * The accommodation's contact as a Person: found by email, else CREATED -- the fourth sanctioned
     * creation path (sign-up, family add-member, REST, and this). The person joins {@code orgId} (the org of
     * whoever is creating the hotel, and later each org whose trip uses it). It creates NO credentials and
     * sends NO mail: the existing email-code activation signs them in the first time they try. Refused
     * without a lodging privilege, so a future self-service reservation path can never reach it.
     *
     * @return the person id, or null when refused (a growl says why).
     */
    public Person.Id findOrCreateContact(final String email, final String name, final String orgId) {
        if (!canCreateAccommodation() && !isGlobalLodgingAdmin() && !hasAnyAccommodationAdmin()) {
            error("Not allowed: only lodging admins can set an accommodation's contact.");
            return null;
        }
        if (!EmailAddresses.isValid(email)) {
            error("A valid contact email is required.");
            return null;
        }
        final String addr = email.trim().toLowerCase(Locale.ROOT);
        Person person = DAO.getInstance().getPersonByEmail(addr, Cached.NO);
        if (person == null) {
            if (name == null || name.isBlank()) {
                error("The contact's name is required to create them.");
                return null;
            }
            person = Person.builder().first(firstOf(name)).last(lastOf(name)).email(addr).build();
            if (!PersonCommands.getPersonCommands().savePerson(person)) {
                return null;
            }
            auditSource.get().person(person, "CREATED as lodging contact", caller().auditActor());
        }
        final String home = contactOrg(orgId);
        if (home == null) {
            error("Choose which organization the contact joins (open this page from an organization's dashboard).");
            return null;
        }
        if (!orgSource.get().addLodgingContact(home, person.getId())) {
            return null;
        }
        return person.getId();
    }

    /**
     * The organization a new contact joins: the page's org context when it has one, else the creating
     * admin's OWN first organization (every person belongs to at least one, so a contact minted from the
     * site-level page still lands somewhere). Null only for a caller with no organization at all.
     */
    private String contactOrg(final String orgIdContext) {
        if (orgIdContext != null && !orgIdContext.isBlank()) {
            return orgIdContext.trim();
        }
        final Person.Id me = caller().personId();
        final Person person = (me == null) ? null : DAO.getInstance().getPerson(me, Cached.YES).orElse(null);
        if (person == null || person.getOrgIds().isEmpty()) {
            return null;
        }
        return person.getOrgIds().get(0).getValue();
    }

    private boolean hasAnyAccommodationAdmin() {
        return !managedAccommodations().isEmpty();
    }

    static String firstOf(final String name) {
        final String trimmed = name.trim();
        final int space = trimmed.lastIndexOf(' ');
        return (space < 0) ? trimmed : trimmed.substring(0, space).trim();
    }

    static String lastOf(final String name) {
        final String trimmed = name.trim();
        final int space = trimmed.lastIndexOf(' ');
        return (space < 0) ? "" : trimmed.substring(space + 1).trim();
    }

    // ------------------------------------------------------------------ managers (the privilege roster)

    /** The people holding {@code accommodationAdmin} for this hotel. */
    public List<Person> managers(final String accId) {
        final List<Person> people = new ArrayList<>();
        if (accId == null) {
            return people;
        }
        final Privilege row = privSource.get().getPrivilege(PrivilegeCommands.ACCOMMODATION_ADMIN, accId);
        for (final Person.Id id : row.getPeople()) {
            DAO.getInstance().getPerson(id, Cached.YES).ifPresent(people::add);
        }
        return people;
    }

    public boolean addManager(final String accId, final Person.Id personId) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        final Accommodation acc = findAccommodation(accId);
        if (acc == null || personId == null) {
            return refuse("Unknown accommodation or person.");
        }
        grantAccommodationAdmin(acc, personId);
        return true;
    }

    /** {@link #addManager} from a page, by the person's id string (pages carry ids, not {@code Person.Id}s). */
    public boolean addManagerById(final String accId, final String personId) {
        return personId != null && !personId.isBlank() && addManager(accId, Person.Id.from(personId));
    }

    public boolean removeManagerById(final String accId, final String personId) {
        return personId != null && !personId.isBlank() && removeManager(accId, Person.Id.from(personId));
    }

    public boolean removeManager(final String accId, final Person.Id personId) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        return privSource.get().remove(PrivilegeCommands.ACCOMMODATION_ADMIN, accId, personId);
    }

    private void grantAccommodationAdmin(final Accommodation acc, final Person.Id personId) {
        if (personId == null) {
            return;
        }
        final PrivilegeCommands priv = privSource.get();
        final Privilege row = priv.getOrCreate(PrivilegeCommands.ACCOMMODATION_ADMIN, acc.getId().getValue(),
                priv.baseDescription(PrivilegeCommands.ACCOMMODATION_ADMIN) + " (" + acc.getName() + ")");
        priv.savePrivilege(row.withNewPerson(personId), caller().auditActor());
    }

    // ------------------------------------------------------------------ room types

    public List<RoomTypeRow> roomTypeRows(final String accId) {
        final List<RoomTypeRow> rows = new ArrayList<>();
        final Accommodation acc = findAccommodation(accId);
        if (acc == null) {
            return rows;
        }
        for (final RoomType type : acc.getRoomTypes()) {
            rows.add(new RoomTypeRow(type.getId(), type.getName(), type.getDescription(), type.getMinPeople(),
                    type.getMaxPeople(), acc.getRooms().stream().filter(r -> type.getId().equals(r.getRoomTypeId()))
                    .toList().size(), type.getPhotoIds().size()));
        }
        return rows;
    }

    public RoomTypeForm roomTypeFormFor(final String accId, final String roomTypeId) {
        final RoomTypeForm form = new RoomTypeForm();
        final Accommodation acc = findAccommodation(accId);
        final RoomType type = (acc == null) ? null : acc.roomType(roomTypeId);
        if (type != null) {
            form.setId(type.getId());
            form.setName(type.getName());
            form.setDescription(type.getDescription());
            form.setMinPeople(type.getMinPeople());
            form.setMaxPeople(type.getMaxPeople());
        }
        return form;
    }

    public boolean saveRoomType(final String accId, final RoomTypeForm form) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        if (form == null || form.getName() == null || form.getName().isBlank()) {
            return refuse("A room type needs a name.");
        }
        if (form.getMaxPeople() < form.getMinPeople()) {
            return refuse("The maximum must be at least the minimum.");
        }
        final Accommodation acc = freshAccommodation(accId);
        if (acc == null) {
            return refuse("This accommodation no longer exists.");
        }
        RoomType type = acc.roomType(form.getId());
        if (type == null) {
            type = RoomType.builder().build();
            acc.getRoomTypes().add(type);
        }
        type.setName(form.getName().trim());
        type.setDescription(form.getDescription());
        type.setMinPeople(Math.max(1, form.getMinPeople()));
        type.setMaxPeople(Math.max(type.getMinPeople(), form.getMaxPeople()));
        return store(acc) && audited(acc, "Saved room type '" + type.getName() + "'");
    }

    public boolean deleteRoomType(final String accId, final String roomTypeId) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        final Accommodation acc = freshAccommodation(accId);
        final RoomType type = (acc == null) ? null : acc.roomType(roomTypeId);
        if (type == null) {
            return refuse("Unknown room type.");
        }
        if (acc.getRooms().stream().anyMatch(room -> roomTypeId.equals(room.getRoomTypeId()))) {
            return refuse("Rooms still use '" + type.getName() + "'. Change their type first.");
        }
        acc.getRoomTypes().remove(type);
        return store(acc) && audited(acc, "Deleted room type '" + type.getName() + "'");
    }

    // ------------------------------------------------------------------ rooms

    /** Every room, floor then natural room-number order. */
    public List<RoomRow> roomRows(final String accId) {
        final List<RoomRow> rows = new ArrayList<>();
        final Accommodation acc = findAccommodation(accId);
        if (acc == null) {
            return rows;
        }
        for (final Room room : sortedRooms(acc)) {
            rows.add(roomRowOf(acc, room));
        }
        return rows;
    }

    public List<RoomRow> roomsOnFloor(final String accId, final String floor) {
        final List<RoomRow> rows = new ArrayList<>();
        for (final RoomRow row : roomRows(accId)) {
            if (Objects.equals(floor, row.getFloor())) {
                rows.add(row);
            }
        }
        return rows;
    }

    public List<RoomRow> mappedRoomsOnFloor(final String accId, final String floor) {
        final List<RoomRow> rows = new ArrayList<>();
        for (final RoomRow row : roomsOnFloor(accId, floor)) {
            if (row.isMapped()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static RoomRow roomRowOf(final Accommodation acc, final Room room) {
        final RoomType type = acc.roomType(room.getRoomTypeId());
        final Room.MapRegion region = room.getMapRegion();
        return new RoomRow(room.getId(), room.getRoomNumber(), room.getFloor(), room.getRoomTypeId(),
                type == null ? "" : type.getName(), type == null ? 0 : type.getMinPeople(),
                type == null ? 0 : type.getMaxPeople(), room.getNotes(), room.getAdminNotes(), region != null,
                region == null ? 0 : region.getX(), region == null ? 0 : region.getY(),
                region == null ? 0 : region.getW(), region == null ? 0 : region.getH());
    }

    static List<Room> sortedRooms(final Accommodation acc) {
        final List<Room> rooms = new ArrayList<>(acc.getRooms());
        rooms.sort((a, b) -> {
            final int byFloor = naturalCompare(a.getFloor(), b.getFloor());
            return (byFloor != 0) ? byFloor : naturalCompare(a.getRoomNumber(), b.getRoomNumber());
        });
        return rooms;
    }

    /** "9" before "114", "1A" before "1B", blanks last -- the order a rooming list is read in. */
    static int naturalCompare(final String a, final String b) {
        if (a == null || a.isBlank()) {
            return (b == null || b.isBlank()) ? 0 : 1;
        }
        if (b == null || b.isBlank()) {
            return -1;
        }
        final String da = a.replaceAll("[^0-9]", "");
        final String db = b.replaceAll("[^0-9]", "");
        if (!da.isEmpty() && !db.isEmpty() && da.length() <= 9 && db.length() <= 9) {
            final int byNumber = Long.compare(Long.parseLong(da), Long.parseLong(db));
            if (byNumber != 0) {
                return byNumber;
            }
        }
        return a.compareToIgnoreCase(b);
    }

    public RoomForm roomFormFor(final String accId, final String roomId) {
        final RoomForm form = new RoomForm();
        final Accommodation acc = findAccommodation(accId);
        final Room room = (acc == null) ? null : acc.room(roomId);
        if (room != null) {
            form.setId(room.getId());
            form.setRoomTypeId(room.getRoomTypeId());
            form.setRoomNumber(room.getRoomNumber());
            form.setFloor(room.getFloor());
            form.setNotes(room.getNotes());
            form.setAdminNotes(room.getAdminNotes());
        }
        return form;
    }

    public boolean saveRoom(final String accId, final RoomForm form) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        if (form == null || form.getRoomNumber() == null || form.getRoomNumber().isBlank()) {
            return refuse("A room needs a number.");
        }
        final Accommodation acc = freshAccommodation(accId);
        if (acc == null) {
            return refuse("This accommodation no longer exists.");
        }
        if (acc.roomType(form.getRoomTypeId()) == null) {
            return refuse("Choose a room type.");
        }
        final String number = form.getRoomNumber().trim();
        for (final Room other : acc.getRooms()) {
            if (number.equalsIgnoreCase(other.getRoomNumber()) && !other.getId().equals(form.getId())) {
                return refuse("Room " + number + " already exists.");
            }
        }
        Room room = acc.room(form.getId());
        if (room == null) {
            if (acc.getRooms().size() >= MAX_ROOMS) {
                return refuse("An accommodation holds at most " + MAX_ROOMS + " rooms.");
            }
            room = Room.builder().build();
            acc.getRooms().add(room);
        }
        room.setRoomTypeId(form.getRoomTypeId());
        room.setRoomNumber(number);
        room.setFloor(blankToNull(form.getFloor()));
        room.setNotes(form.getNotes());
        room.setAdminNotes(form.getAdminNotes());
        return store(acc) && audited(acc, "Saved room " + number);
    }

    public boolean deleteRoom(final String accId, final String roomId) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        final Accommodation acc = freshAccommodation(accId);
        final Room room = (acc == null) ? null : acc.room(roomId);
        if (room == null) {
            return refuse("Unknown room.");
        }
        if (roomInUse(acc, roomId)) {
            return refuse("Room " + room.getRoomNumber() + " has an active reservation. Move them first.");
        }
        acc.getRooms().remove(room);
        return store(acc) && audited(acc, "Deleted room " + room.getRoomNumber());
    }

    /** Whether any ACTIVE reservation, on any trip that uses this hotel, sits in the room. */
    private boolean roomInUse(final Accommodation acc, final String roomId) {
        for (final String tripId : tripsUsing(acc)) {
            for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.NO)) {
                if (res.isActive() && roomId.equals(res.getRoomId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The trips with an offer on this hotel -- found through the orgs that use it (recent trips only). */
    private Set<String> tripsUsing(final Accommodation acc) {
        final Set<String> tripIds = new LinkedHashSet<>();
        for (final Organization.Id orgId : acc.getOrgIds()) {
            for (final Trip trip : tripSource.get().getTripsForOrg(orgId.getValue(), 200)) {
                for (final ReservationOffer offer : DAO.getInstance().getReservationOffers(trip.getId(), Cached.NO)) {
                    if (acc.getId().equals(offer.getAccommodationId())) {
                        tripIds.add(trip.getId());
                    }
                }
            }
        }
        return tripIds;
    }

    /** Adds rooms {@code prefix}{from}..{prefix}{to} on a floor with a type; existing numbers are skipped. */
    public int bulkAddRooms(final String accId, final String prefix, final int from, final int to, final String floor,
            final String roomTypeId) {
        if (!canEditAccommodation(accId)) {
            refuse("Not allowed: you do not manage this accommodation.");
            return 0;
        }
        final Accommodation acc = freshAccommodation(accId);
        if (acc == null || acc.roomType(roomTypeId) == null) {
            refuse("Choose a room type.");
            return 0;
        }
        if (to < from || to - from >= MAX_ROOMS) {
            refuse("Give a range of at most " + MAX_ROOMS + " rooms.");
            return 0;
        }
        final Set<String> existing = new HashSet<>();
        for (final Room room : acc.getRooms()) {
            existing.add(nullSafe(room.getRoomNumber()).toLowerCase(Locale.ROOT));
        }
        int added = 0;
        for (int n = from; n <= to && acc.getRooms().size() < MAX_ROOMS; n++) {
            final String number = nullSafe(prefix).trim() + n;
            if (existing.add(number.toLowerCase(Locale.ROOT))) {
                acc.getRooms().add(Room.builder().roomNumber(number).floor(blankToNull(floor))
                        .roomTypeId(roomTypeId).build());
                added++;
            }
        }
        if (added == 0) {
            info("Every room in that range already exists.");
            return 0;
        }
        if (!store(acc)) {
            return 0;
        }
        audited(acc, "Added " + added + " rooms (" + nullSafe(prefix) + from + " to " + nullSafe(prefix) + to + ")");
        info(added + " rooms added.");
        return added;
    }

    // ------------------------------------------------------------------ floors and maps

    public List<String> floorsOf(final String accId) {
        final Accommodation acc = findAccommodation(accId);
        return (acc == null) ? new ArrayList<>() : sortedFloors(acc);
    }

    private static List<String> sortedFloors(final Accommodation acc) {
        final List<String> floors = acc.floors();
        floors.sort(LodgingCommands::naturalCompare);
        return floors;
    }

    /** The media id of this floor's plan image, or "" when none. */
    public String floorMapMediaId(final String accId, final String floor) {
        final Accommodation acc = findAccommodation(accId);
        final FloorMap map = (acc == null) ? null : acc.floorMap(floor);
        return (map == null || map.getMediaId() == null) ? "" : map.getMediaId();
    }

    /**
     * The URL of a media-library image, or "" when the id no longer resolves: the CDN when a bucket is
     * configured, else this app's own {@code /lodging-photos/*} GET (local mode, every webtest).
     */
    public String mediaUrl(final String mediaId) {
        final MediaItem item = mediaSource.get().get(mediaId);
        if (item == null) {
            return "";
        }
        return mediaSource.get().isUploadEnabled() ? nullSafe(mediaSource.get().getUrl(item))
                : contextPath() + "/lodging-photos/" + item.getS3Key();
    }

    private static String contextPath() {
        final jakarta.faces.context.FacesContext ctx = jakarta.faces.context.FacesContext.getCurrentInstance();
        return (ctx == null) ? "" : ctx.getExternalContext().getRequestContextPath();
    }

    public boolean setFloorMap(final String accId, final String floor, final String mediaId) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        if (floor == null || floor.isBlank()) {
            return refuse("Which floor?");
        }
        final Accommodation acc = freshAccommodation(accId);
        if (acc == null) {
            return refuse("This accommodation no longer exists.");
        }
        final FloorMap existing = acc.floorMap(floor.trim());
        if (existing == null) {
            acc.getFloorMaps().add(new FloorMap(floor.trim(), mediaId));
        } else {
            existing.setMediaId(mediaId);
        }
        if (acc.roomsOnFloor(floor.trim()).isEmpty()) {
            // Silently attaching a plan to a floor no room is on is how a mismatch hides: the plan looks
            // uploaded, nothing can be mapped on it, and the floor list grows an entry with no rooms.
            warn("No rooms are on floor '" + floor.trim() + "', so this plan has nothing to map yet.");
        }
        return store(acc) && audited(acc, "Floor plan set for floor " + floor.trim());
    }

    /**
     * Takes the plan off a floor. The image stays in the media library, exactly as removing a gallery photo
     * does, and a floor that existed only because a plan named it leaves the floor list with it.
     */
    public boolean removeFloorMap(final String accId, final String floor) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        if (floor == null || floor.isBlank()) {
            return refuse("Which floor?");
        }
        final Accommodation acc = freshAccommodation(accId);
        if (acc == null) {
            return refuse("This accommodation no longer exists.");
        }
        final String wanted = floor.trim();
        if (!acc.getFloorMaps().removeIf(map -> wanted.equals(map.getFloor()))) {
            return refuse("Floor " + wanted + " has no plan to remove.");
        }
        return store(acc) && audited(acc, "Floor plan removed for floor " + wanted);
    }

    /**
     * The floors a plan on {@code from} could be moved to: every floor of the hotel except the one it is on
     * and any floor that already has its own plan.
     */
    public List<String> moveTargets(final String accId, final String from) {
        final Accommodation acc = findAccommodation(accId);
        if (acc == null || from == null) {
            return List.of();
        }
        final List<String> targets = new ArrayList<>();
        for (final String floor : acc.floors()) {
            if (!floor.equals(from.trim()) && acc.floorMap(floor) == null) {
                targets.add(floor);
            }
        }
        return targets;
    }

    /**
     * Re-points a plan at another floor, keeping the image. This is the repair for a plan and its rooms
     * ending up on differently named floors -- renaming the rooms' floor orphans the plan on the old name,
     * leaving a floor with a picture nothing can be mapped on and rooms with no picture (2026-09-07).
     * Both floors' regions are cleared: boxes on the source floor were drawn against an image it no longer
     * has, and boxes on the target floor were drawn against the plan this one replaces.
     */
    public boolean moveFloorMap(final String accId, final String from, final String to) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return refuse("Which floor should this plan move to?");
        }
        final String source = from.trim();
        final String target = to.trim();
        if (source.equals(target)) {
            return refuse("That plan is already on floor " + target + ".");
        }
        final Accommodation acc = freshAccommodation(accId);
        if (acc == null) {
            return refuse("This accommodation no longer exists.");
        }
        final FloorMap moving = acc.floorMap(source);
        if (moving == null) {
            return refuse("Floor " + source + " has no plan to move.");
        }
        if (acc.floorMap(target) != null) {
            return refuse("Floor " + target + " already has a plan. Remove that one first.");
        }
        moving.setFloor(target);
        for (final Room room : acc.getRooms()) {
            if (source.equals(room.getFloor()) || target.equals(room.getFloor())) {
                room.setMapRegion(null);
            }
        }
        return store(acc) && audited(acc, "Floor plan moved from floor " + source + " to floor " + target);
    }

    /**
     * Saves the annotator's regions for one floor: {@code [{roomId, kind, x, y, w, h}, ...]} in percent.
     * Every listed room must be on that floor, every box inside the image; rooms on the floor that are not
     * listed lose their region (the annotator deleted them). Answers the {@code saved} callback param.
     */
    public boolean saveFloorRegions(final String accId, final String floor, final String json) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        final Accommodation acc = freshAccommodation(accId);
        if (acc == null || floor == null) {
            return refuse("This accommodation no longer exists.");
        }
        final Map<String, Room.MapRegion> regions;
        try {
            regions = parseRegions(json);
        } catch (final IllegalArgumentException | IOException ex) {
            return refuse("The floor plan could not be read: " + ex.getMessage());
        }
        for (final String roomId : regions.keySet()) {
            final Room room = acc.room(roomId);
            if (room == null || !Objects.equals(floor, room.getFloor())) {
                return refuse("A box points at a room that is not on floor " + floor + ".");
            }
        }
        for (final Room room : acc.roomsOnFloor(floor)) {
            room.setMapRegion(regions.get(room.getId()));
        }
        final boolean saved = store(acc)
                && audited(acc, "Floor " + floor + " plan: " + regions.size() + " rooms mapped");
        callbackParam("saved", saved);
        return saved;
    }

    static Map<String, Room.MapRegion> parseRegions(final String json) throws IOException {
        final Map<String, Room.MapRegion> regions = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return regions;
        }
        final List<Map<String, Object>> raw = DAO.getInstance().getMapper().readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
        for (final Map<String, Object> entry : raw) {
            final Object roomId = entry.get("roomId");
            if (roomId == null || roomId.toString().isBlank()) {
                continue;
            }
            final Room.MapRegion region = Room.MapRegion.rect(number(entry.get("x")), number(entry.get("y")),
                    number(entry.get("w")), number(entry.get("h")));
            if (!region.isValid()) {
                throw new IllegalArgumentException("a box is outside the image or has no size");
            }
            if (regions.put(roomId.toString(), region) != null) {
                throw new IllegalArgumentException("room " + roomId + " is mapped twice");
            }
        }
        return regions;
    }

    private static double number(final Object value) {
        if (value instanceof Number n) {
            return Math.round(n.doubleValue() * 100.0) / 100.0;
        }
        throw new IllegalArgumentException("a coordinate is missing");
    }

    // ------------------------------------------------------------------ photos (media-library rows)

    /** The gallery: this hotel's media rows in stored order, hidden and vanished ones skipped. */
    public List<MediaItem> galleryOf(final String accId) {
        final Accommodation acc = findAccommodation(accId);
        return (acc == null) ? new ArrayList<>() : resolveMedia(acc.getPhotoIds());
    }

    public List<MediaItem> roomTypeGallery(final String accId, final String roomTypeId) {
        final Accommodation acc = findAccommodation(accId);
        final RoomType type = (acc == null) ? null : acc.roomType(roomTypeId);
        return (type == null) ? new ArrayList<>() : resolveMedia(type.getPhotoIds());
    }

    private List<MediaItem> resolveMedia(final List<String> ids) {
        final List<MediaItem> items = new ArrayList<>();
        for (final String id : ids) {
            final MediaItem item = mediaSource.get().get(id);
            if (item != null && !Boolean.TRUE.equals(item.getHidden())) {
                items.add(item);
            }
        }
        return items;
    }

    /** Records an uploaded media row as a property photo (or a room type's, when {@code roomTypeId} is given). */
    public boolean addPhoto(final String accId, final String roomTypeId, final String mediaId) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        final Accommodation acc = freshAccommodation(accId);
        if (acc == null || mediaId == null || mediaId.isBlank()) {
            return refuse("Nothing to add.");
        }
        final List<String> target = photoListOf(acc, roomTypeId);
        if (target == null) {
            return refuse("Unknown room type.");
        }
        if (!target.contains(mediaId)) {
            target.add(mediaId);
        }
        return store(acc);
    }

    /** Moves a photo one step up or down in its gallery; returns false with no change at the ends. */
    public boolean movePhoto(final String accId, final String roomTypeId, final String mediaId, final int delta) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        final Accommodation acc = freshAccommodation(accId);
        final List<String> target = (acc == null) ? null : photoListOf(acc, roomTypeId);
        final int at = (target == null) ? -1 : target.indexOf(mediaId);
        final int to = at + delta;
        if (at < 0 || to < 0 || to >= target.size()) {
            return false;
        }
        target.remove(at);
        target.add(to, mediaId);
        return store(acc);
    }

    /** Removes the reference from the gallery; the media-library row stays (the library owns deletion). */
    public boolean removePhoto(final String accId, final String roomTypeId, final String mediaId) {
        if (!canEditAccommodation(accId)) {
            return refuse("Not allowed: you do not manage this accommodation.");
        }
        final Accommodation acc = freshAccommodation(accId);
        final List<String> target = (acc == null) ? null : photoListOf(acc, roomTypeId);
        return target != null && target.remove(mediaId) && store(acc);
    }

    private static List<String> photoListOf(final Accommodation acc, final String roomTypeId) {
        if (roomTypeId == null || roomTypeId.isBlank()) {
            return acc.getPhotoIds();
        }
        final RoomType type = acc.roomType(roomTypeId);
        return (type == null) ? null : type.getPhotoIds();
    }

    /** Drops references to media rows the library has since deleted, so galleries never point at nothing. */
    private void pruneDanglingMedia(final Accommodation acc) {
        acc.getPhotoIds().removeIf(id -> mediaSource.get().get(id) == null);
        for (final RoomType type : acc.getRoomTypes()) {
            type.getPhotoIds().removeIf(id -> mediaSource.get().get(id) == null);
        }
    }

    // ------------------------------------------------------------------ countries (a static list)

    static final List<String> COUNTRIES = List.of("Argentina", "Australia", "Austria", "Belgium",
            "Bosnia and Herzegovina", "Brazil", "Canada", "Chile", "Colombia", "Croatia", "Czechia", "Denmark",
            "Egypt", "Fiji", "Finland", "France", "Germany", "Greece", "Guatemala", "Hungary", "India", "Indonesia",
            "Ireland", "Israel", "Italy", "Japan", "Jordan", "Kenya", "Lebanon", "Lithuania", "Malta", "Mexico",
            "Montenegro", "Morocco", "Netherlands", "New Zealand", "Nicaragua", "Norway", "Palestine", "Peru",
            "Philippines", "Poland", "Portugal", "Serbia", "Slovakia", "Slovenia", "South Africa", "South Korea",
            "Spain", "Sweden", "Switzerland", "Tanzania", "Thailand", "Turkey", "Uganda", "Ukraine",
            "United Arab Emirates", "United Kingdom", "United States", "Vatican City", "Vietnam");

    /** The country autocomplete: any country containing the query (case-insensitive); free text still saves. */
    public List<String> countrySuggestions(final String query) {
        final String q = nullSafe(query).trim().toLowerCase(Locale.ROOT);
        final List<String> matches = new ArrayList<>();
        for (final String country : COUNTRIES) {
            if (country.toLowerCase(Locale.ROOT).contains(q)) {
                matches.add(country);
            }
        }
        return matches;
    }

    // ================================================================== offers

    /** The trip's offers, by name; readable by anyone who may see the trip's lodging admin or is on the trip. */
    public List<ReservationOffer> getOffers(final String tripId) {
        final List<ReservationOffer> offers = new ArrayList<>(DAO.getInstance().getReservationOffers(tripId,
                Cached.YES));
        offers.sort((a, b) -> nullSafe(a.getName()).compareToIgnoreCase(nullSafe(b.getName())));
        return offers;
    }

    public ReservationOffer findOffer(final String tripId, final String offerId) {
        if (tripId == null || offerId == null || offerId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getReservationOffer(tripId, ReservationOffer.Id.from(offerId), Cached.YES)
                .orElse(null);
    }

    public List<OfferRow> offerRows(final String tripId) {
        final List<OfferRow> rows = new ArrayList<>();
        final Trip trip = tripSource.get().getTrip(tripId);
        final List<Reservation> reservations = DAO.getInstance().getReservations(tripId, Cached.YES);
        for (final ReservationOffer offer : getOffers(tripId)) {
            final Accommodation acc = findAccommodation(idValue(offer.getAccommodationId()));
            final TripEvent event = trip.getTripEvent(offer.getTripEventId());
            final int count = (int) reservations.stream()
                    .filter(res -> res.isActive() && offer.getId().equals(res.getOfferId())).count();
            rows.add(new OfferRow(offer.getId().getValue(), offer.getName(), acc == null ? "?" : acc.getName(),
                    roomTypeNames(acc, offer), event == null ? "" : event.getTitle(), pricingLabel(offer),
                    range(offer.getValidFrom(), offer.getValidUntil()),
                    range(offer.getDefaultStart(), offer.getDefaultEnd()), count, offer.isEnabled()));
        }
        return rows;
    }

    /** "Double / Triple": the option's room types by name, in its order. */
    static String roomTypeNames(final Accommodation acc, final ReservationOffer offer) {
        final List<String> names = new ArrayList<>();
        for (final String typeId : offer.getRoomTypeIds()) {
            final RoomType type = (acc == null) ? null : acc.roomType(typeId);
            names.add(type == null ? "?" : type.getName());
        }
        return names.isEmpty() ? "?" : String.join(" / ", names);
    }

    static String pricingLabel(final ReservationOffer offer) {
        final String rate = offer.getNightlyPriceOverrides().isEmpty()
                ? MoneyMath.formatCents(offer.getNightlyPriceCents()) + "/night" : "varies by night";
        final String model = offer.isPerPerson() ? " per person" : " per room";
        final String supplement = (offer.isPerPerson() && offer.getSingleSupplementCents() > 0)
                ? ", +" + MoneyMath.formatCents(offer.getSingleSupplementCents()) + " single" : "";
        return rate + model + supplement;
    }

    private static String range(final LocalDateTime from, final LocalDateTime to) {
        if (from == null && to == null) {
            return "";
        }
        return (from == null ? "…" : from.format(STAY)) + " – " + (to == null ? "…" : to.format(STAY));
    }

    public OfferForm offerFormFor(final String tripId, final String offerId) {
        final OfferForm form = new OfferForm();
        final ReservationOffer offer = findOffer(tripId, offerId);
        if (offer == null) {
            // A new option covers the trip's dates and defaults to the whole stay at the usual hotel hours.
            final Trip trip = tripSource.get().getTrip(tripId);
            form.setValidFrom(startOfDay(trip.getStartDate()));
            form.setValidUntil(endOfDay(trip.getEndDate()));
            form.setDefaultStart(trip.getStartDate() == null ? null
                    : trip.getStartDate().toLocalDate().atTime(OfferForm.DEFAULT_ARRIVAL));
            form.setDefaultEnd(trip.getEndDate() == null ? null
                    : trip.getEndDate().toLocalDate().atTime(OfferForm.DEFAULT_DEPARTURE));
            form.fillRanges();
            return form;
        }
        form.setId(offer.getId().getValue());
        form.setName(offer.getName());
        form.setAccommodationId(idValue(offer.getAccommodationId()));
        form.setRoomTypeIds(new ArrayList<>(offer.getRoomTypeIds()));
        form.setTripEventId(offer.getTripEventId());
        form.setPricingModel(offer.getPricingModel().name());
        form.setNightlyPrice(dollars(offer.getNightlyPriceCents()));
        form.setPerNightPricing(!offer.getNightlyPriceOverrides().isEmpty());
        for (final Map.Entry<String, Long> entry : offer.getNightlyPriceOverrides().entrySet()) {
            form.getPerNight().put(entry.getKey(), MoneyMath.formatCents(entry.getValue()).replace("$", ""));
        }
        form.setSingleSupplement(dollars(offer.getSingleSupplementCents()));
        form.setMinNights(offer.getMinNights());
        form.setValidFrom(offer.getValidFrom());
        form.setValidUntil(offer.getValidUntil());
        form.setDefaultStart(offer.getDefaultStart());
        form.setDefaultEnd(offer.getDefaultEnd());
        form.setPolicyHtml(offer.getPolicyHtml());
        if (offer.getCancelFeeBps() != null && offer.getCancelFeeBps() > 0) {
            form.setCancelFeeKind("PERCENT");
            form.setCancelFeeAmount(offer.getCancelFeeBps() / 100.0);
        } else {
            form.setCancelFeeKind("FLAT");
            form.setCancelFeeAmount(offer.getCancelFeeFixedCents() == null ? null
                    : dollars(offer.getCancelFeeFixedCents()));
        }
        form.setDisabled(!offer.isEnabled());
        form.fillRanges();
        return form;
    }

    private static LocalDateTime startOfDay(final LocalDateTime when) {
        return (when == null) ? null : when.toLocalDate().atStartOfDay();
    }

    private static LocalDateTime endOfDay(final LocalDateTime when) {
        return (when == null) ? null : when.toLocalDate().atTime(23, 59);
    }

    /**
     * Names the option after its room types ("Double / Triple room") when the name is still blank: the dialog
     * calls it as room types are picked, and {@link #saveOffer} falls back to it.
     */
    public void suggestOfferName(final OfferForm form) {
        if (form == null || (form.getName() != null && !form.getName().isBlank())) {
            return;
        }
        final Accommodation acc = findAccommodation(form.getAccommodationId());
        final List<String> names = new ArrayList<>();
        for (final String typeId : form.getRoomTypeIds()) {
            final RoomType type = (acc == null) ? null : acc.roomType(typeId);
            if (type != null && type.getName() != null && !names.contains(type.getName())) {
                names.add(type.getName());
            }
        }
        if (!names.isEmpty()) {
            form.setName(String.join(" / ", names) + " room");
        }
    }

    /** The nights of the form's default stay, for the per-night price table. */
    public List<String> perNightDates(final OfferForm form) {
        final List<String> dates = new ArrayList<>();
        if (form == null) {
            return dates;
        }
        form.resolveDates();
        for (final LocalDate night : LodgingPricing.nightsOf(form.getDefaultStart(), form.getDefaultEnd())) {
            dates.add(night.toString());
        }
        return dates;
    }

    /**
     * Creates or edits an offer. The accommodation must exist; the first time THIS trip's org uses the hotel
     * the org is recorded on it and the hotel's contact joins the org. {@code tripEventId == "NEW"} creates a
     * LODGING event on the trip from the default stay; an existing id must be one of the trip's events.
     */
    public boolean saveOffer(final String tripId, final OfferForm form) {
        if (!canManageTripLodging(tripId)) {
            return refuse("Not allowed: only the trip's managers and lodging admins can edit lodging options.");
        }
        if (form == null) {
            return refuse("Nothing to save.");
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        if (!tripId.equals(trip.getId())) {
            return refuse("Unknown trip.");
        }
        final Accommodation acc = findAccommodation(form.getAccommodationId());
        if (acc == null || acc.isRetired()) {
            return refuse("Choose an accommodation.");
        }
        final List<String> typeIds = new ArrayList<>(new LinkedHashSet<>(form.getRoomTypeIds()));
        typeIds.removeIf(id -> id == null || id.isBlank());
        if (typeIds.isEmpty()) {
            return refuse("Choose at least one room type.");
        }
        for (final String typeId : typeIds) {
            if (acc.roomType(typeId) == null) {
                return refuse("That room type is not at " + acc.getName() + ".");
            }
        }
        form.setRoomTypeIds(typeIds);
        suggestOfferName(form);
        if (form.getName() == null || form.getName().isBlank()) {
            return refuse("A lodging option needs a name.");
        }
        form.resolveDates();
        final String problem = offerProblem(form);
        if (problem != null) {
            return refuse(problem);
        }
        final ReservationOffer offer = (form.getId() == null || form.getId().isBlank())
                ? ReservationOffer.builder().tripId(tripId).build()
                : DAO.getInstance().getReservationOffer(tripId, ReservationOffer.Id.from(form.getId()), Cached.NO)
                        .orElse(null);
        if (offer == null) {
            return refuse("This lodging option no longer exists.");
        }
        final String eventId = resolveEvent(trip, form, acc);
        if (eventId == null) {
            return false;
        }
        final boolean editing = offer.getVersion() > 0L;
        applyOfferForm(offer, form, acc, eventId);
        if (offer.getVersion() == 0L) {
            offer.setOrgId(trip.getOrgId());
            offer.setCreatedBy(caller().personId());
            offer.setCreated(LocalDateTime.now());
        }
        try {
            if (!DAO.getInstance().saveReservationOffer(offer)) {
                return refuse("The lodging option could not be saved.");
            }
        } catch (final ConditionalCheckFailedException ex) {
            return refuse("Saved by someone else: reload the page and try again.");
        } catch (final IOException ex) {
            log.error("Unable to save offer {}", offer.getId(), ex);
            return refuse("The lodging option could not be saved: " + ex.getMessage());
        }
        recordOrgUse(acc, trip);
        auditSource.get().lodging(AuditEventBuilder.TARGET_OFFER, offer.getId().getValue(), trip.getOrgId(),
                "Saved lodging option '" + offer.getName() + "' (" + acc.getName() + ") on '" + trip.getTitle() + "'",
                caller().auditActor());
        if (editing) {
            recomputeAfterOfferEdit(trip, offer);
        }
        return true;
    }

    /**
     * An edited option (price, supplement, per-night overrides) changes what its reservations cost: recompute
     * every ACTIVE reservation on it, by room where placed, when automatic recompute is on.
     */
    private void recomputeAfterOfferEdit(final Trip trip, final ReservationOffer offer) {
        if (!autoRecompute(trip.getOrgId())) {
            warn("Lodging bills were not recomputed (automatic recompute is off): press Recompute.");
            return;
        }
        final Set<String> rooms = new LinkedHashSet<>();
        LodgingBiller.Result total = LodgingBiller.Result.none();
        for (final Reservation res : DAO.getInstance().getReservations(trip.getId(), Cached.NO)) {
            if (!res.isActive() || !offer.getId().equals(res.getOfferId())) {
                continue;
            }
            if (res.getRoomId() == null) {
                total = total.plus(recomputeOne(trip.getId(), res));
            } else if (rooms.add(res.getRoomId())) {
                total = total.plus(recomputeRoom(trip.getId(), res.getRoomId()));
            }
        }
        if (total.written() > 0 || total.removed() > 0) {
            auditSource.get().lodging(AuditEventBuilder.TARGET_OFFER, offer.getId().getValue(), trip.getOrgId(),
                    "Lodging bills recomputed after the option changed: " + total.summary(), caller().auditActor());
        }
    }

    private static String offerProblem(final OfferForm form) {
        if (form.getNightlyPrice() != null && form.getNightlyPrice() < 0) {
            return "The nightly price cannot be negative.";
        }
        if (form.getSingleSupplement() != null && form.getSingleSupplement() < 0) {
            return "The single supplement cannot be negative.";
        }
        if (form.getValidFrom() != null && form.getValidUntil() != null
                && !form.getValidUntil().isAfter(form.getValidFrom())) {
            return "The option's last date must be after its first.";
        }
        if (form.getDefaultStart() == null || form.getDefaultEnd() == null
                || !form.getDefaultEnd().isAfter(form.getDefaultStart())) {
            return "The default departure must be after the default arrival.";
        }
        if ((form.getValidFrom() != null && form.getDefaultStart().isBefore(form.getValidFrom()))
                || (form.getValidUntil() != null && form.getDefaultEnd().isAfter(form.getValidUntil()))) {
            return "The default stay must lie within the option's date range.";
        }
        if (form.getCancelFeeAmount() != null && form.getCancelFeeAmount() < 0) {
            return "The cancellation fee cannot be negative.";
        }
        if ("PERCENT".equals(form.getCancelFeeKind()) && form.getCancelFeeAmount() != null
                && form.getCancelFeeAmount() > 100) {
            return "A percentage fee cannot exceed 100%.";
        }
        if (form.isPerNightPricing()) {
            for (final Map.Entry<String, String> entry : form.getPerNight().entrySet()) {
                final Double price = parseDollars(entry.getValue());
                if (entry.getValue() != null && !entry.getValue().isBlank() && price == null) {
                    return "'" + entry.getValue() + "' is not a price (for " + entry.getKey() + ").";
                }
                if (price != null && price < 0) {
                    return "The price for " + entry.getKey() + " cannot be negative.";
                }
            }
        }
        final String policy = HtmlFragmentValidator.validate(nullSafe(form.getPolicyHtml()));
        return (policy == null) ? null : "Policy: " + policy;
    }

    /**
     * The LODGING event the offer tracks people on: one of the trip's own, or a new one from the form. A
     * lodging event with the same title and start already on the trip is REUSED rather than duplicated --
     * that is the normal case for a hotel's second offer (single and double rooms share one event), and
     * {@code Trip.addTripEvent} refuses a duplicate anyway.
     */
    private String resolveEvent(final Trip trip, final OfferForm form, final Accommodation acc) {
        if (OfferForm.NEW_EVENT.equals(form.getTripEventId()) || form.getTripEventId() == null
                || form.getTripEventId().isBlank()) {
            final String title = (form.getNewEventTitle() == null || form.getNewEventTitle().isBlank())
                    ? acc.getName() : form.getNewEventTitle().trim();
            for (final TripEvent existing : trip.getTripEvents()) {
                if (existing.getType() == TripEvent.Type.LODGING && title.equals(existing.getTitle())
                        && Objects.equals(existing.getStart(), form.getDefaultStart())) {
                    return existing.getId();
                }
            }
            final String id = trip.addTripEvent(TripEvent.Type.LODGING, title, lodgingEventNotes(acc),
                    form.getDefaultStart(), form.getDefaultEnd());
            if (!tripSource.get().saveTrip(trip)) {
                refuse("The lodging event could not be added to the trip.");
                return null;
            }
            return id;
        }
        final TripEvent event = trip.getTripEvent(form.getTripEventId());
        if (event == null) {
            refuse("That event is not on this trip.");
            return null;
        }
        if (event.getType() != TripEvent.Type.LODGING) {
            warn("'" + event.getTitle() + "' is not a lodging event; reservations will still join it.");
        }
        return event.getId();
    }

    private static void applyOfferForm(final ReservationOffer offer, final OfferForm form, final Accommodation acc,
            final String eventId) {
        offer.setName(form.getName().trim());
        offer.setAccommodationId(acc.getId());
        offer.setRoomTypeIds(new ArrayList<>(form.getRoomTypeIds()));
        offer.setTripEventId(eventId);
        offer.setPricingModel("PER_PERSON".equals(form.getPricingModel()) ? PricingModel.PER_PERSON
                : PricingModel.PER_ROOM);
        offer.setNightlyPriceCents(cents(form.getNightlyPrice()));
        final Map<String, Long> overrides = new HashMap<>();
        if (form.isPerNightPricing()) {
            for (final Map.Entry<String, String> entry : form.getPerNight().entrySet()) {
                final Double price = parseDollars(entry.getValue());
                if (price != null) {
                    overrides.put(entry.getKey(), cents(price));
                }
            }
        }
        offer.setNightlyPriceOverrides(overrides);
        offer.setSingleSupplementCents(cents(form.getSingleSupplement()));
        offer.setMinNights(Math.max(1, form.getMinNights()));
        offer.setValidFrom(form.getValidFrom());
        offer.setValidUntil(form.getValidUntil());
        offer.setDefaultStart(form.getDefaultStart());
        offer.setDefaultEnd(form.getDefaultEnd());
        offer.setPolicyHtml(RichTextRules.normalize(nullSafe(form.getPolicyHtml())));
        if ("PERCENT".equals(form.getCancelFeeKind())) {
            offer.setCancelFeeFixedCents(null);
            offer.setCancelFeeBps(form.getCancelFeeAmount() == null ? null
                    : (int) Math.round(form.getCancelFeeAmount() * 100));
        } else {
            offer.setCancelFeeBps(null);
            offer.setCancelFeeFixedCents(form.getCancelFeeAmount() == null ? null : cents(form.getCancelFeeAmount()));
        }
        offer.setDisabled(form.isDisabled() ? Boolean.TRUE : null);
    }

    /** First use of a hotel by an org: the org goes on the hotel and the contact joins the org (best effort). */
    private void recordOrgUse(final Accommodation acc, final Trip trip) {
        if (trip.getOrgId() == null || trip.getOrgId().isBlank()) {
            return;
        }
        final Organization.Id orgId = Organization.Id.from(trip.getOrgId());
        if (acc.usedBy(orgId)) {
            return;
        }
        final Accommodation fresh = freshAccommodation(acc.getId().getValue());
        if (fresh == null || fresh.usedBy(orgId)) {
            return;
        }
        fresh.getOrgIds().add(orgId);
        store(fresh);
        if (fresh.getContactId() != null) {
            orgSource.get().addLodgingContact(orgId.getValue(), fresh.getContactId());
        }
    }

    public boolean deleteOffer(final String tripId, final String offerId) {
        if (!canManageTripLodging(tripId)) {
            return refuse("Not allowed: only the trip's managers and lodging admins can delete offers.");
        }
        final ReservationOffer offer = findOffer(tripId, offerId);
        if (offer == null) {
            return refuse("Unknown offer.");
        }
        for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.NO)) {
            if (offer.getId().equals(res.getOfferId())) {
                return refuse("'" + offer.getName() + "' has reservations (cancelled ones included) and stays.");
            }
        }
        final boolean deleted = DAO.getInstance().deleteReservationOffer(tripId, offer.getId());
        if (deleted) {
            auditSource.get().lodging(AuditEventBuilder.TARGET_OFFER, offerId, offer.getOrgId(),
                    "Deleted offer '" + offer.getName() + "'", caller().auditActor());
        }
        return deleted;
    }

    /** The default offer for the workspace: the first by name, or "" when the trip has none. */
    public String defaultOfferId(final String tripId) {
        final List<ReservationOffer> offers = getOffers(tripId);
        return offers.isEmpty() ? "" : offers.get(0).getId().getValue();
    }

    /** The trip's LODGING events as id -> title, for the offer dialog's event menu. */
    public Map<String, String> lodgingEventChoices(final String tripId) {
        final Map<String, String> choices = new LinkedHashMap<>();
        for (final TripEvent event : tripSource.get().getTrip(tripId).getTripEvents()) {
            if (event.getType() == TripEvent.Type.LODGING) {
                choices.put(event.getId(), event.getTitle());
            }
        }
        return choices;
    }

    /** id -> label for the accommodation's room types. */
    public Map<String, String> roomTypeChoices(final String accId) {
        final Map<String, String> choices = new LinkedHashMap<>();
        final Accommodation acc = findAccommodation(accId);
        if (acc != null) {
            for (final RoomType type : acc.getRoomTypes()) {
                choices.put(type.getId(), type.getDisplayLabel());
            }
        }
        return choices;
    }

    /** Room id -> "114 (Double, floor 1)" for the reservation dialogs. */
    public Map<String, String> roomChoices(final String accId) {
        final Map<String, String> choices = new LinkedHashMap<>();
        final Accommodation acc = findAccommodation(accId);
        if (acc != null) {
            for (final Room room : sortedRooms(acc)) {
                final RoomType type = acc.roomType(room.getRoomTypeId());
                choices.put(room.getId(), room.getRoomNumber() + " (" + (type == null ? "?" : type.getName())
                        + (room.getFloor() == null ? "" : ", floor " + room.getFloor()) + ")");
            }
        }
        return choices;
    }

    // ================================================================== reservations

    public Reservation findReservation(final String tripId, final String reservationId) {
        if (tripId == null || reservationId == null || reservationId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getReservation(tripId, Reservation.Id.from(reservationId), Cached.NO).orElse(null);
    }

    /** The trip's reservations for one person (any status), for the itinerary and the rooming list. */
    public List<Reservation> reservationsFor(final String tripId, final Person.Id personId) {
        final List<Reservation> mine = new ArrayList<>();
        for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.YES)) {
            if (res.occupies(personId)) {
                mine.add(res);
            }
        }
        return mine;
    }

    public List<Reservation> activeReservationsFor(final String tripId, final Person.Id personId) {
        final List<Reservation> active = new ArrayList<>();
        for (final Reservation res : reservationsFor(tripId, personId)) {
            if (res.isActive()) {
                active.add(res);
            }
        }
        return active;
    }

    /** Every ACTIVE reservation on a room, read fresh (pricing input). */
    public List<Reservation> activeOnRoom(final String tripId, final String roomId) {
        final List<Reservation> active = new ArrayList<>();
        if (roomId == null) {
            return active;
        }
        for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.NO)) {
            if (res.isActive() && roomId.equals(res.getRoomId())) {
                active.add(res);
            }
        }
        return active;
    }

    public List<ReservationRow> reservationRows(final String tripId, final boolean showCancelled) {
        final List<ReservationRow> rows = new ArrayList<>();
        if (!canManageTripLodging(tripId)) {
            return rows;
        }
        final Map<ReservationOffer.Id, ReservationOffer> offers = new HashMap<>();
        for (final ReservationOffer offer : getOffers(tripId)) {
            offers.put(offer.getId(), offer);
        }
        final LodgingBiller biller = new LodgingBiller(txSource.get());
        for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.NO)) {
            if (!showCancelled && !res.isActive()) {
                continue;
            }
            final ReservationOffer offer = offers.get(res.getOfferId());
            final Accommodation acc = findAccommodation(idValue(res.getAccommodationId()));
            final long billed = biller.billedByPerson(res).values().stream().mapToLong(Long::longValue).sum();
            rows.add(new ReservationRow(res.getId().getValue(), names(res.getOccupants()),
                    offer == null ? "?" : offer.getName(),
                    acc == null ? "" : nullSafe(acc.roomLabel(res.getRoomId())), res.getStart(), res.getEnd(),
                    res.nights(), res.getStatus().name(), MoneyMath.formatCents(billed), res.isActive(),
                    res.isSupplementWaived(), res.getNotes()));
        }
        rows.sort((a, b) -> nullSafe(a.getOccupants()).compareToIgnoreCase(nullSafe(b.getOccupants())));
        return rows;
    }

    public ReservationForm reservationFormFor(final String tripId, final String reservationId) {
        final ReservationForm form = new ReservationForm();
        final Reservation res = findReservation(tripId, reservationId);
        if (res == null) {
            final String offerId = defaultOfferId(tripId);
            form.setOfferId(offerId);
            applyOfferDefaults(form, findOffer(tripId, offerId));
            return form;
        }
        form.setId(res.getId().getValue());
        form.setOfferId(idValue(res.getOfferId()));
        for (final Person.Id id : res.getOccupants()) {
            form.getPersonIds().add(id.getValue());
        }
        form.setStart(res.getStart());
        form.setEnd(res.getEnd());
        form.setRoomId(res.getRoomId());
        form.setNotes(res.getNotes());
        form.setWaiveSingleSupplement(res.isSupplementWaived());
        form.fillRanges();
        return form;
    }

    /** Puts the offer's default stay on the form -- the dialog's "offer changed" ajax. */
    public void applyOfferDefaults(final ReservationForm form, final ReservationOffer offer) {
        if (form == null || offer == null) {
            return;
        }
        form.setStart(offer.getDefaultStart());
        form.setEnd(offer.getDefaultEnd());
        form.fillRanges();
    }

    /**
     * Creates reservations from the dialog: one per person, or one shared reservation for everyone. Every
     * person must be on the roster; the stay must meet the offer's minimum; a disabled offer is refused; a
     * stay outside the offer's validity is only warned about (an admin is placing people, not a customer
     * buying). Occupants join the offer's event; bills follow.
     *
     * @return how many reservations were created.
     */
    public int createReservations(final String tripId, final ReservationForm form) {
        if (!canManageTripLodging(tripId)) {
            refuse("Not allowed: only the trip's managers and lodging admins can reserve.");
            return 0;
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        final ReservationOffer offer = (form == null) ? null : findOffer(tripId, form.getOfferId());
        if (!tripId.equals(trip.getId()) || offer == null) {
            refuse("Choose a lodging option.");
            return 0;
        }
        form.resolveDates();
        final List<Person.Id> people = new ArrayList<>();
        for (final String id : new LinkedHashSet<>(form.getPersonIds())) {
            people.add(Person.Id.from(id));
        }
        if (people.isEmpty()) {
            refuse("Choose at least one person.");
            return 0;
        }
        final String problem = stayProblem(trip, offer, people, form.getStart(), form.getEnd());
        if (problem != null) {
            refuse(problem);
            return 0;
        }
        final Accommodation acc = findAccommodation(idValue(offer.getAccommodationId()));
        if (form.getRoomId() != null && !form.getRoomId().isBlank() && (acc == null
                || acc.room(form.getRoomId()) == null)) {
            refuse("That room is not at " + (acc == null ? "the accommodation" : acc.getName()) + ".");
            return 0;
        }
        final List<List<Person.Id>> groups = new ArrayList<>();
        if (form.isShareOneRoom()) {
            groups.add(people);
        } else {
            for (final Person.Id person : people) {
                groups.add(List.of(person));
            }
        }
        int created = 0;
        for (final List<Person.Id> group : groups) {
            final Reservation res = Reservation.builder().tripId(tripId).orgId(trip.getOrgId())
                    .offerId(offer.getId()).accommodationId(offer.getAccommodationId()).occupants(group)
                    .start(form.getStart()).end(form.getEnd()).roomId(blankToNull(form.getRoomId()))
                    .notes(form.getNotes()).waiveSingleSupplement(form.isWaiveSingleSupplement() ? Boolean.TRUE : null)
                    .createdBy(caller().personId()).created(LocalDateTime.now()).build();
            if (persistReservation(trip, offer, acc, res, null)) {
                created++;
            }
        }
        if (created > 0) {
            info(created + (created == 1 ? " reservation" : " reservations") + " created.");
        }
        return created;
    }

    /** The refusals shared by create and edit; null when the stay is acceptable. */
    private String stayProblem(final Trip trip, final ReservationOffer offer, final List<Person.Id> people,
            final LocalDateTime start, final LocalDateTime end) {
        if (!offer.isEnabled()) {
            return "'" + offer.getName() + "' is not being offered.";
        }
        for (final Person.Id person : people) {
            if (!trip.getPeople().contains(person)) {
                return displayName(person) + " is not on this trip's roster.";
            }
        }
        if (start == null || end == null || !end.isAfter(start)) {
            return "Departure must be after arrival.";
        }
        if (Reservation.nightsBetween(start, end) < offer.getMinNights()) {
            return "'" + offer.getName() + "' requires at least " + offer.getMinNights()
                    + (offer.getMinNights() == 1 ? " night." : " nights.");
        }
        if (!offer.coversStay(start, end)) {
            return "The stay must fall within the dates of '" + offer.getName() + "' ("
                    + range(offer.getValidFrom(), offer.getValidUntil()) + "); widen the option's dates first.";
        }
        return null;
    }

    /** Edits dates, room, occupants, notes and the supplement waiver; recomputes both rooms. */
    public boolean updateReservation(final String tripId, final ReservationForm form) {
        if (!canManageTripLodging(tripId)) {
            return refuse("Not allowed: only the trip's managers and lodging admins can edit reservations.");
        }
        final Reservation res = (form == null) ? null : findReservation(tripId, form.getId());
        if (res == null || !res.isActive()) {
            return refuse("This reservation no longer exists or is cancelled.");
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        final ReservationOffer offer = findOffer(tripId, idValue(res.getOfferId()));
        if (offer == null) {
            return refuse("The reservation's offer no longer exists.");
        }
        final List<Person.Id> people = new ArrayList<>();
        for (final String id : new LinkedHashSet<>(form.getPersonIds())) {
            people.add(Person.Id.from(id));
        }
        if (people.isEmpty()) {
            return refuse("A reservation needs at least one person.");
        }
        form.resolveDates();
        final String problem = stayProblem(trip, offer, people, form.getStart(), form.getEnd());
        if (problem != null) {
            return refuse(problem);
        }
        final Accommodation acc = findAccommodation(idValue(offer.getAccommodationId()));
        final String roomId = blankToNull(form.getRoomId());
        if (roomId != null && (acc == null || acc.room(roomId) == null)) {
            return refuse("That room is not at the accommodation.");
        }
        final String previousRoom = res.getRoomId();
        final List<Person.Id> leaving = new ArrayList<>(res.getOccupants());
        leaving.removeAll(people);
        final boolean waiverChanged = res.isSupplementWaived() != form.isWaiveSingleSupplement();
        res.setOccupants(people);
        res.setStart(form.getStart());
        res.setEnd(form.getEnd());
        res.setRoomId(roomId);
        res.setNotes(form.getNotes());
        res.setWaiveSingleSupplement(form.isWaiveSingleSupplement() ? Boolean.TRUE : null);
        if (!persistReservation(trip, offer, acc, res, previousRoom)) {
            return false;
        }
        leaveEventIfLast(trip, offer, leaving, res.getId());
        if (waiverChanged) {
            auditSource.get().lodging(AuditEventBuilder.TARGET_RESERVATION, res.getId().getValue(), trip.getOrgId(),
                    (form.isWaiveSingleSupplement() ? "Waived" : "Reinstated") + " the single supplement for "
                            + names(people), caller().auditActor());
        }
        return true;
    }

    /**
     * The workspace's click: puts {@code personId} into {@code roomId}. With no reservation yet, one is
     * created from the offer's defaults. Over the room type's maximum (for the window) it refuses unless
     * {@code force}, answering an {@link AssignOutcome} the page pins for its confirmation dialog and the
     * {@code overCapacity} / {@code overCapacityMsg} callback params for the JS.
     */
    public AssignOutcome assignRoom(final String tripId, final String reservationId, final String roomId,
            final boolean force, final LocalDateTime winStart, final LocalDateTime winEnd) {
        if (!canAssignRooms(tripId)) {
            return outcome(false, false, "Not allowed: you do not room this trip's people.",
                    null, reservationId, roomId);
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        final Reservation res = findReservation(tripId, reservationId);
        if (res == null || !tripId.equals(trip.getId())) {
            // Creating one is the placement dialog's job: it asks which lodging option pays for the stay.
            return outcome(false, false, "That reservation no longer exists.", null, reservationId, roomId);
        }
        if (!res.isActive()) {
            return outcome(false, false, "That reservation is cancelled.", null, reservationId, roomId);
        }
        final ReservationOffer offer = findOffer(tripId, idValue(res.getOfferId()));
        final Accommodation acc = findAccommodation(idValue(res.getAccommodationId()));
        final Room room = (acc == null || roomId == null) ? null : acc.room(roomId);
        if (room == null) {
            return outcome(false, false, "That room is not at the accommodation.", null, reservationId, roomId);
        }
        final String personId = res.getOccupants().isEmpty() ? null : res.getOccupants().get(0).getValue();
        final String tooFull = capacityProblem(tripId, acc, room, res, windowStart(winStart, res),
                windowEnd(winEnd, res));
        if (tooFull != null && !force) {
            callbackParam("overCapacity", true);
            callbackParam("overCapacityMsg", tooFull);
            return outcome(false, true, tooFull, personId, res.getId().getValue(), roomId);
        }
        final RoomType type = acc.roomType(room.getRoomTypeId());
        if (type != null && type.getName() != null && offer != null && !offer.covers(room.getRoomTypeId())) {
            warn("Room " + room.getRoomNumber() + " is a " + type.getName() + ", not one of the option's room types.");
        }
        final String previousRoom = res.getRoomId();
        if (roomId.equals(previousRoom)) {
            info(names(res.getOccupants()) + " already in room " + room.getRoomNumber() + ".");
            return outcome(true, false, "already there", personId, res.getId().getValue(), roomId);
        }
        res.setRoomId(roomId);
        if (!persistReservation(trip, offer, acc, res, previousRoom)) {
            return outcome(false, false, "The assignment could not be saved.", personId, reservationId, roomId);
        }
        // A card from the board's Assigned list is a MOVE: say where from, so a mis-click reads as one.
        final String msg = names(res.getOccupants()) + (previousRoom == null ? " assigned to room "
                : " moved from room " + nullSafe(acc.roomLabel(previousRoom)) + " to room ") + room.getRoomNumber()
                + (tooFull == null ? "" : " (over capacity)") + ".";
        info(msg);
        return outcome(true, tooFull != null, msg, personId, res.getId().getValue(), roomId);
    }

    /**
     * Whether the room would hold more people than its type sleeps over the window, as a sentence; null when
     * it fits. The stay's own dates when no window is pinned, so a placement is judged on the nights it
     * actually occupies.
     */
    private String capacityProblem(final String tripId, final Accommodation acc, final Room room,
            final Reservation res, final LocalDateTime from, final LocalDateTime to) {
        final RoomType type = acc.roomType(room.getRoomTypeId());
        final int max = (type == null) ? Integer.MAX_VALUE : type.getMaxPeople();
        final int would = occupancyOver(tripId, room.getId(), res, from, to);
        return (would <= max) ? null
                : "Room " + room.getRoomNumber() + (type == null ? "" : " (" + type.getName() + ")")
                        + " sleeps " + max + ". Assigning " + names(res.getOccupants()) + " makes it " + would + ".";
    }

    // ---------------------------------------------------------------- placing someone who has no reservation

    /**
     * The dialog behind "click a person, click a room" for somebody with no reservation yet: which lodging
     * option pays for the stay, and the stay itself. The option was implicit in a toolbar menu before, which
     * made it easy to bill the wrong price to the wrong person.
     */
    public PlacementForm placementFormFor(final String tripId, final String accId, final String personId,
            final String roomId) {
        final PlacementForm form = new PlacementForm();
        form.setPersonId(personId);
        form.setAccommodationId(accId);
        form.setRoomId(roomId);
        final Accommodation acc = findAccommodation(accId);
        final Room room = (acc == null) ? null : acc.room(roomId);
        form.setRoomLabel(room == null ? "" : nullSafe(acc.roomLabel(roomId)));
        form.setPersonName(personId == null ? "" : displayName(Person.Id.from(personId)));
        final Map<String, String> options = optionChoices(tripId, accId);
        if (options.size() == 1) {
            form.setOfferId(options.keySet().iterator().next());
        }
        applyPlacementOption(tripId, form);
        return form;
    }

    /** The stay follows the chosen option's default; called again whenever the dialog's option changes. */
    public void applyPlacementOption(final String tripId, final PlacementForm form) {
        if (form == null) {
            return;
        }
        final ReservationOffer offer = findOffer(tripId, form.getOfferId());
        if (offer != null) {
            form.applyDefaults(offer.getDefaultStart(), offer.getDefaultEnd());
        }
    }

    /** The lodging options at one accommodation on this trip, as id -> "name (pricing)". */
    public Map<String, String> optionChoices(final String tripId, final String accId) {
        final Map<String, String> choices = new LinkedHashMap<>();
        for (final ReservationOffer offer : getOffers(tripId)) {
            if (accId != null && accId.equals(idValue(offer.getAccommodationId())) && offer.isEnabled()) {
                choices.put(offer.getId().getValue(), offer.getName() + " (" + pricingLabel(offer) + ")");
            }
        }
        return choices;
    }

    /**
     * Creates the reservation the placement dialog describes and puts it in the room. Refusals (no option, a
     * stay outside the option's dates, under the minimum, not on the roster) come back on the form so the
     * dialog can show them; a room that cannot hold them is the warning that {@code force} overrides.
     */
    public AssignOutcome place(final String tripId, final PlacementForm form) {
        if (!canManageTripLodging(tripId)) {
            return outcome(false, false, "Not allowed: you do not room this trip's people.",
                    null, null, null);
        }
        if (form == null || form.getPersonId() == null || form.getPersonId().isBlank()) {
            return outcome(false, false, "Who is being placed?", null, null, null);
        }
        final String personId = form.getPersonId();
        final String roomId = form.getRoomId();
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        final ReservationOffer offer = findOffer(tripId, form.getOfferId());
        if (offer == null || !tripId.equals(trip.getId())) {
            return placementProblem(form, "Choose a lodging option.", personId, roomId);
        }
        final Accommodation acc = findAccommodation(idValue(offer.getAccommodationId()));
        final Room room = (acc == null || roomId == null) ? null : acc.room(roomId);
        if (room == null) {
            return placementProblem(form, "That room is not at the accommodation.", personId, roomId);
        }
        final Person.Id person = Person.Id.from(personId);
        final String problem = stayProblem(trip, offer, List.of(person), form.start(), form.end());
        if (problem != null) {
            return placementProblem(form, problem, personId, roomId);
        }
        final Reservation res = Reservation.builder().tripId(tripId).orgId(trip.getOrgId()).offerId(offer.getId())
                .accommodationId(offer.getAccommodationId()).occupants(List.of(person))
                .start(form.start()).end(form.end()).createdBy(caller().personId())
                .waiveSingleSupplement(form.isWaiveSingleSupplement() ? Boolean.TRUE : null)
                .created(LocalDateTime.now()).build();
        final String tooFull = capacityProblem(tripId, acc, room, res, form.start(), form.end());
        if (tooFull != null && !form.isForce()) {
            form.setProblem(tooFull);
            callbackParam("overCapacity", true);
            return outcome(false, true, tooFull, personId, null, roomId);
        }
        if (!offer.covers(room.getRoomTypeId())) {
            final RoomType type = acc.roomType(room.getRoomTypeId());
            warn("Room " + room.getRoomNumber() + (type == null ? "" : " is a " + type.getName())
                    + ", not one of the option's room types.");
        }
        res.setRoomId(roomId);
        if (!persistReservation(trip, offer, acc, res, null)) {
            return placementProblem(form, "The reservation could not be saved.", personId, roomId);
        }
        if (res.isSupplementWaived()) {
            auditSource.get().lodging(AuditEventBuilder.TARGET_RESERVATION, res.getId().getValue(), trip.getOrgId(),
                    "Waived the single supplement for " + names(res.getOccupants()), caller().auditActor());
        }
        form.setProblem(null);
        final String msg = names(res.getOccupants()) + " placed in room " + room.getRoomNumber()
                + " on '" + offer.getName() + "'" + (res.isSupplementWaived() ? ", single supplement waived" : "")
                + (tooFull == null ? "" : " (over capacity)") + ".";
        info(msg);
        return outcome(true, tooFull != null, msg, personId, res.getId().getValue(), roomId);
    }

    /** A refusal the dialog shows in place, rather than a growl behind a modal. */
    private static AssignOutcome placementProblem(final PlacementForm form, final String message,
            final String personId, final String roomId) {
        form.setProblem(message);
        return new AssignOutcome(false, false, message, personId, null, roomId);
    }

    private static LocalDateTime windowStart(final LocalDateTime winStart, final Reservation res) {
        return (winStart == null) ? res.getStart() : winStart;
    }

    private static LocalDateTime windowEnd(final LocalDateTime winEnd, final Reservation res) {
        return (winEnd == null) ? res.getEnd() : winEnd;
    }

    /** The most people in the room on any night of the window, counting {@code candidate} as in it. */
    private int occupancyOver(final String tripId, final String roomId, final Reservation candidate,
            final LocalDateTime from, final LocalDateTime to) {
        final List<Reservation> onRoom = new ArrayList<>();
        for (final Reservation res : activeOnRoom(tripId, roomId)) {
            if (!res.getId().equals(candidate.getId())) {
                onRoom.add(res);
            }
        }
        onRoom.add(candidate);
        int peak = 0;
        final Map<LocalDate, List<Person.Id>> byNight = LodgingPricing.occupancyByNight(onRoom);
        for (final LocalDate night : LodgingPricing.nightsOf(from, to)) {
            peak = Math.max(peak, byNight.getOrDefault(night, List.of()).size());
        }
        return Math.max(peak, candidate.getOccupants().size());
    }

    private static AssignOutcome outcome(final boolean assigned, final boolean over, final String message,
            final String personId, final String reservationId, final String roomId) {
        if (!assigned && !over) {
            error(message);
        }
        return new AssignOutcome(assigned, over, message, personId, reservationId, roomId);
    }

    public boolean unassignRoom(final String tripId, final String reservationId) {
        if (!canAssignRooms(tripId)) {
            return refuse("Not allowed: you do not room this trip's people.");
        }
        final Reservation res = findReservation(tripId, reservationId);
        if (res == null || !res.isActive()) {
            return refuse("This reservation no longer exists or is cancelled.");
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        final ReservationOffer offer = findOffer(tripId, idValue(res.getOfferId()));
        final Accommodation acc = (offer == null) ? null : findAccommodation(idValue(offer.getAccommodationId()));
        final String previousRoom = res.getRoomId();
        res.setRoomId(null);
        return persistReservation(trip, offer, acc, res, previousRoom);
    }

    /** Save + event participation + bills, the write every reservation change funnels through. */
    private boolean persistReservation(final Trip trip, final ReservationOffer offer, final Accommodation acc,
            final Reservation res, final String previousRoom) {
        final boolean creating = res.getVersion() == 0L;
        try {
            if (!DAO.getInstance().saveReservation(res)) {
                return refuse("The reservation could not be saved.");
            }
        } catch (final ConditionalCheckFailedException ex) {
            return refuse("Saved by someone else: reload the page and try again.");
        } catch (final IOException ex) {
            log.error("Unable to save reservation {}", res.getId(), ex);
            return refuse("The reservation could not be saved: " + ex.getMessage());
        }
        if (offer != null && offer.getTripEventId() != null) {
            tripSource.get().updateEventParticipants(trip, offer.getTripEventId(), res.getOccupants(), List.of());
        }
        auditSource.get().lodging(AuditEventBuilder.TARGET_RESERVATION, res.getId().getValue(), trip.getOrgId(),
                (creating ? "Reserved " : "Updated reservation: ") + describe(res, offer, acc), caller().auditActor());
        afterWrite(trip, res, previousRoom);
        return true;
    }

    private String describe(final Reservation res, final ReservationOffer offer, final Accommodation acc) {
        final StringBuilder sb = new StringBuilder(names(res.getOccupants()));
        if (offer != null) {
            sb.append(", ").append(offer.getName());
        }
        if (acc != null && res.getRoomId() != null) {
            sb.append(", room ").append(nullSafe(acc.roomLabel(res.getRoomId())));
        }
        sb.append(", ").append(res.nights()).append(res.nights() == 1 ? " night " : " nights ");
        if (res.getStart() != null && res.getEnd() != null) {
            sb.append(res.getStart().format(STAY)).append(" – ").append(res.getEnd().format(STAY));
        }
        return sb.toString();
    }

    /** Takes {@code leavers} off the offer's event unless another ACTIVE reservation of theirs still uses it. */
    private void leaveEventIfLast(final Trip trip, final ReservationOffer offer, final List<Person.Id> leavers,
            final Reservation.Id except) {
        if (offer == null || offer.getTripEventId() == null || leavers.isEmpty()) {
            return;
        }
        final Set<String> eventOffers = new HashSet<>();
        for (final ReservationOffer other : getOffers(trip.getId())) {
            if (offer.getTripEventId().equals(other.getTripEventId())) {
                eventOffers.add(other.getId().getValue());
            }
        }
        final List<Person.Id> reallyLeaving = new ArrayList<>();
        for (final Person.Id person : leavers) {
            final boolean stillThere = DAO.getInstance().getReservations(trip.getId(), Cached.NO).stream()
                    .anyMatch(res -> res.isActive() && !res.getId().equals(except) && res.occupies(person)
                            && eventOffers.contains(idValue(res.getOfferId())));
            if (!stillThere) {
                reallyLeaving.add(person);
            }
        }
        tripSource.get().updateEventParticipants(trip, offer.getTripEventId(), List.of(), reallyLeaving);
    }

    // ------------------------------------------------------------------ cancel

    /** The cancel dialog's numbers: what the ledger says was billed, the offer's fee, and the difference. */
    public CancelPreview cancelPreview(final String tripId, final String reservationId) {
        if (!canManageTripLodging(tripId)) {
            return new CancelPreview();     // what was billed and what comes back is the trip's business
        }
        final Reservation res = findReservation(tripId, reservationId);
        if (res == null) {
            return new CancelPreview();
        }
        final ReservationOffer offer = findOffer(tripId, idValue(res.getOfferId()));
        final long billed = new LodgingBiller(txSource.get()).billedByPerson(res).values().stream()
                .mapToLong(Long::longValue).sum();
        final long fee = LodgingPricing.cancellationFee(offer, billed);
        return new CancelPreview(reservationId, names(res.getOccupants()), billed, fee, Math.max(0L, billed - fee),
                feeRule(offer), MoneyMath.formatCents(billed), MoneyMath.formatCents(fee),
                MoneyMath.formatCents(Math.max(0L, billed - fee)));
    }

    static String feeRule(final ReservationOffer offer) {
        if (offer == null || !offer.hasCancellationFee()) {
            return "no cancellation fee";
        }
        final List<String> parts = new ArrayList<>();
        if (offer.getCancelFeeFixedCents() != null && offer.getCancelFeeFixedCents() > 0) {
            parts.add(MoneyMath.formatCents(offer.getCancelFeeFixedCents()));
        }
        if (offer.getCancelFeeBps() != null && offer.getCancelFeeBps() > 0) {
            parts.add((offer.getCancelFeeBps() / 100.0) + "% of the bill");
        }
        return String.join(" + ", parts);
    }

    /**
     * Cancels: the row flips to CANCELLED (never deleted), occupants leave the event unless another active
     * reservation keeps them on it, an optional credit (billed minus the fee, admin-overridable) is written,
     * and the room's remaining occupants are re-billed (their shares and supplements changed).
     */
    public boolean cancelReservation(final String tripId, final String reservationId, final boolean credit,
            final Long feeOverrideCents, final String reason) {
        if (!canManageTripLodging(tripId)) {
            return refuse("Not allowed: only the trip's managers and lodging admins can cancel.");
        }
        final Reservation res = findReservation(tripId, reservationId);
        if (res == null || !res.isActive()) {
            return refuse("This reservation no longer exists or is already cancelled.");
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        final ReservationOffer offer = findOffer(tripId, idValue(res.getOfferId()));
        final LodgingBiller biller = new LodgingBiller(txSource.get());
        final Map<Person.Id, Long> billed = biller.billedByPerson(res);
        final long total = billed.values().stream().mapToLong(Long::longValue).sum();
        final long fee = (feeOverrideCents == null) ? LodgingPricing.cancellationFee(offer, total)
                : Math.max(0L, Math.min(total, feeOverrideCents));
        res.setStatus(Reservation.Status.CANCELLED);
        res.setCancelledAt(LocalDateTime.now());
        res.setCancelledBy(caller().personId());
        res.setCancelFeeCents(fee);
        res.setCredited(credit);
        res.setCancelReason(blankToNull(reason));
        try {
            if (!DAO.getInstance().saveReservation(res)) {
                return refuse("The cancellation could not be saved.");
            }
        } catch (final ConditionalCheckFailedException ex) {
            return refuse("Saved by someone else: reload the page and try again.");
        } catch (final IOException ex) {
            log.error("Unable to cancel reservation {}", res.getId(), ex);
            return refuse("The cancellation could not be saved: " + ex.getMessage());
        }
        leaveEventIfLast(trip, offer, res.getOccupants(), res.getId());
        String creditNote = "no credit";
        if (credit && total > 0) {
            final LodgingBiller.Result result = biller.applyCancelCredits(res, offer, billed, fee,
                    "Lodging cancellation credit: " + (offer == null ? "" : offer.getName() + ", ")
                            + res.nights() + (res.nights() == 1 ? " night" : " nights")
                            + (fee > 0 ? " (minus " + MoneyMath.formatCents(fee) + " cancellation fee)" : ""));
            creditNote = MoneyMath.formatCents(Math.max(0L, total - fee)) + " credited (" + result.summary() + ")";
        }
        auditSource.get().lodging(AuditEventBuilder.TARGET_RESERVATION, res.getId().getValue(), trip.getOrgId(),
                "Cancelled reservation for " + names(res.getOccupants()) + ": billed " + MoneyMath.formatCents(total)
                        + ", fee " + MoneyMath.formatCents(fee) + ", " + creditNote
                        + (res.getCancelReason() == null ? "" : " (" + res.getCancelReason() + ")"),
                caller().auditActor());
        afterWrite(trip, res, res.getRoomId());
        info("Reservation cancelled: " + creditNote + ".");
        return true;
    }

    /** {@link #cancelReservation} for the dialog, whose fee input is dollars (blank = the offer's own fee). */
    public boolean cancelReservationWithFee(final String tripId, final String reservationId, final boolean credit,
            final Double feeDollars, final String reason) {
        return cancelReservation(tripId, reservationId, credit, feeDollars == null ? null : cents(feeDollars), reason);
    }

    // ------------------------------------------------------------------ bills

    /** Automatic recompute after a write (the setting), else a reminder to press Recompute. */
    private void afterWrite(final Trip trip, final Reservation res, final String previousRoom) {
        if (!autoRecompute(trip.getOrgId())) {
            warn("Lodging bills were not recomputed (automatic recompute is off): press Recompute.");
            return;
        }
        final Set<String> rooms = new LinkedHashSet<>();
        if (res.getRoomId() != null) {
            rooms.add(res.getRoomId());
        }
        if (previousRoom != null) {
            rooms.add(previousRoom);
        }
        LodgingBiller.Result total = LodgingBiller.Result.none();
        for (final String roomId : rooms) {
            total = total.plus(recomputeRoom(trip.getId(), roomId));
        }
        if (res.isActive() && res.getRoomId() == null) {
            total = total.plus(recomputeOne(trip.getId(), res));
        }
        if (total.written() > 0 || total.removed() > 0) {
            auditSource.get().lodging(AuditEventBuilder.TARGET_RESERVATION, res.getId().getValue(), trip.getOrgId(),
                    "Lodging bills recomputed after a reservation change: " + total.summary(), caller().auditActor());
        }
    }

    boolean autoRecompute(final String orgId) {
        return Boolean.parseBoolean(orgSource.get().effectiveSetting(KnownSettings.LODGING_AUTO_RECOMPUTE, orgId));
    }

    /** The admin's explicit recompute for one room, or for the whole trip when {@code roomId} is blank. */
    public boolean recomputeBills(final String tripId, final String roomId) {
        if (!canManageTripLodging(tripId)) {
            return refuse("Not allowed: only the trip's managers and lodging admins recompute bills.");
        }
        final Trip trip = tripSource.get().getTrip(tripId);
        final LodgingBiller.Result result = (roomId == null || roomId.isBlank()) ? recomputeTrip(tripId)
                : recomputeRoom(tripId, roomId);
        auditSource.get().lodging(AuditEventBuilder.TARGET_RESERVATION, tripId, trip.getOrgId(),
                "Lodging bills recomputed" + (roomId == null || roomId.isBlank() ? " for the trip: "
                        : " for room " + roomId + ": ") + result.summary(), caller().auditActor());
        info("Lodging bills recomputed: " + result.summary() + ".");
        return true;
    }

    /** Every ACTIVE reservation on the room, priced against each other. */
    LodgingBiller.Result recomputeRoom(final String tripId, final String roomId) {
        LodgingBiller.Result total = LodgingBiller.Result.none();
        final List<Reservation> onRoom = activeOnRoom(tripId, roomId);
        for (final Reservation res : onRoom) {
            total = total.plus(applyBills(tripId, res, onRoom));
        }
        return total;
    }

    LodgingBiller.Result recomputeOne(final String tripId, final Reservation res) {
        final List<Reservation> onRoom = (res.getRoomId() == null) ? List.of(res)
                : activeOnRoom(tripId, res.getRoomId());
        return applyBills(tripId, res, onRoom);
    }

    LodgingBiller.Result recomputeTrip(final String tripId) {
        LodgingBiller.Result total = LodgingBiller.Result.none();
        for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.NO)) {
            if (res.isActive()) {
                total = total.plus(recomputeOne(tripId, res));
            }
        }
        return total;
    }

    private LodgingBiller.Result applyBills(final String tripId, final Reservation res,
            final List<Reservation> onRoom) {
        final ReservationOffer offer = findOffer(tripId, idValue(res.getOfferId()));
        if (offer == null) {
            return LodgingBiller.Result.none();
        }
        final Accommodation acc = findAccommodation(idValue(offer.getAccommodationId()));
        final List<LodgingPricing.Line> lines = LodgingPricing.price(res, offer, acc, onRoom, this::preferredName);
        return new LodgingBiller(txSource.get()).applyBills(res, offer, lines);
    }

    // ================================================================== the assignment workspace

    /**
     * The workspace for one offer: rooms with their occupancy over the window (any ACTIVE reservation on the
     * offer's accommodation whose stay overlaps it), people with a reservation on this offer but no room, and
     * roster members with no active reservation at this accommodation yet.
     */
    public RoomBoard roomBoard(final String tripId, final String accId, final LocalDateTime winStart,
            final LocalDateTime winEnd) {
        final RoomBoard board = new RoomBoard();
        if (!canAssignRooms(tripId)) {
            return board;
        }
        final Trip trip = tripSource.get().getTrip(tripId);
        final Accommodation acc = findAccommodation(accId);
        if (acc == null) {
            return board;
        }
        board.setAccommodationId(acc.getId().getValue());
        board.setAccommodationName(acc.getName());
        board.setFloors(sortedFloors(acc));
        // The board is the HOTEL's, not one lodging option's: which option pays for a stay is asked when the
        // person is placed. The window spans every option at this hotel, so a stay on any of them is visible.
        final LocalDateTime from = (winStart != null) ? winStart : spanStart(tripId, acc, trip);
        final LocalDateTime to = (winEnd != null) ? winEnd : spanEnd(tripId, acc, trip);
        final List<Reservation> all = DAO.getInstance().getReservations(tripId, Cached.NO);
        final Map<String, List<Reservation>> byRoom = new HashMap<>();
        final Set<Person.Id> housed = new HashSet<>();
        for (final Reservation res : all) {
            if (!res.isActive() || !acc.getId().equals(res.getAccommodationId())) {
                continue;
            }
            housed.addAll(res.getOccupants());
            if (res.getRoomId() != null && overlaps(res, from, to)) {
                byRoom.computeIfAbsent(res.getRoomId(), k -> new ArrayList<>()).add(res);
            } else if (res.getRoomId() == null) {
                board.getUnassigned().add(cardFor(trip, res.getOccupants().get(0), res));
            }
            if (res.getRoomId() != null) {
                final PersonCard placed = cardFor(trip, res.getOccupants().get(0), res);
                placed.setRoomLabel(nullSafe(acc.roomLabel(res.getRoomId())));
                board.getAssigned().add(placed);
            }
        }
        board.getAssigned().sort(Comparator.comparing(PersonCard::getRoomLabel).thenComparing(PersonCard::getName));
        for (final Room room : sortedRooms(acc)) {
            board.getRooms().add(cellFor(acc, room, byRoom.getOrDefault(room.getId(), List.of()), from, to));
        }
        // Placing one of these mints a reservation on a lodging OPTION, which is a price: that is the
        // trip's call, so a lodging manager is not shown a column whose clicks would all be refused.
        if (canManageTripLodging(tripId)) {
            for (final Person.Id person : trip.getPeople()) {
                if (!housed.contains(person)) {
                    board.getNoReservation().add(cardFor(trip, person, null));
                }
            }
        }
        return board;
    }

    /**
     * One room's occupancy over the board's window: the room's own details plus a full person card per
     * occupant. The board carries only names in its chips, so "who is in 104, and what did they ask for?"
     * had no answer without leaving the workspace.
     *
     * @return a detail whose roomId is blank when the caller may not see it, or the room is gone.
     */
    public RoomDetail roomDetail(final String tripId, final String accId, final String roomId,
            final LocalDateTime winStart, final LocalDateTime winEnd) {
        final RoomDetail detail = new RoomDetail();
        if (!canAssignRooms(tripId) || roomId == null || roomId.isBlank()) {
            return detail;
        }
        final Accommodation acc = findAccommodation(accId);
        final Room room = (acc == null) ? null : acc.room(roomId);
        if (room == null) {
            return detail;
        }
        final Trip trip = tripSource.get().getTrip(tripId);
        final LocalDateTime from = (winStart != null) ? winStart : spanStart(tripId, acc, trip);
        final LocalDateTime to = (winEnd != null) ? winEnd : spanEnd(tripId, acc, trip);
        final List<Reservation> onRoom = new ArrayList<>();
        for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.NO)) {
            if (res.isActive() && acc.getId().equals(res.getAccommodationId()) && roomId.equals(res.getRoomId())
                    && overlaps(res, from, to)) {
                onRoom.add(res);
            }
        }
        final RoomCell cell = cellFor(acc, room, onRoom, from, to);
        detail.setRoomId(cell.getRoomId());
        detail.setRoomNumber(cell.getRoomNumber());
        detail.setFloor(cell.getFloor());
        detail.setTypeName(cell.getTypeName());
        detail.setMinPeople(cell.getMinPeople());
        detail.setMaxPeople(cell.getMaxPeople());
        detail.setCount(cell.getCount());
        detail.setState(cell.getState());
        detail.setNotes(cell.getNotes());
        detail.setAdminNotes(cell.getAdminNotes());
        // Every occupant of every stay on the room, not one card per reservation: two people sharing one
        // reservation are two people in the room, and a second stay on other dates is a third card.
        final Set<String> seen = new LinkedHashSet<>();
        for (final Reservation res : onRoom) {
            for (final Person.Id person : res.getOccupants()) {
                if (seen.add(res.getId().getValue() + "/" + person.getValue())) {
                    detail.getOccupants().add(cardFor(trip, person, res));
                }
            }
        }
        return detail;
    }

    /** The earliest date any of this hotel's options covers on the trip; the trip's start when none say. */
    private LocalDateTime spanStart(final String tripId, final Accommodation acc, final Trip trip) {
        LocalDateTime earliest = null;
        for (final ReservationOffer offer : offersAt(tripId, acc)) {
            final LocalDateTime start = (offer.getValidFrom() != null) ? offer.getValidFrom()
                    : offer.getDefaultStart();
            if (start != null && (earliest == null || start.isBefore(earliest))) {
                earliest = start;
            }
        }
        return (earliest == null) ? trip.getStartDate() : earliest;
    }

    /** The latest date any of this hotel's options covers on the trip; the trip's end when none say. */
    private LocalDateTime spanEnd(final String tripId, final Accommodation acc, final Trip trip) {
        LocalDateTime latest = null;
        for (final ReservationOffer offer : offersAt(tripId, acc)) {
            final LocalDateTime end = (offer.getValidUntil() != null) ? offer.getValidUntil()
                    : offer.getDefaultEnd();
            if (end != null && (latest == null || end.isAfter(latest))) {
                latest = end;
            }
        }
        return (latest == null) ? trip.getEndDate() : latest;
    }

    private List<ReservationOffer> offersAt(final String tripId, final Accommodation acc) {
        final List<ReservationOffer> offers = new ArrayList<>();
        for (final ReservationOffer offer : getOffers(tripId)) {
            if (acc.getId().equals(offer.getAccommodationId())) {
                offers.add(offer);
            }
        }
        return offers;
    }

    /** A blank occupancy window for the board's dialog. */
    public StayWindowForm newStayWindow() {
        return new StayWindowForm();
    }

    /** The accommodations this trip has lodging options at, as id -> name; the board picks among these. */
    public Map<String, String> accommodationChoices(final String tripId) {
        final Map<String, String> choices = new LinkedHashMap<>();
        for (final ReservationOffer offer : getOffers(tripId)) {
            final String accId = idValue(offer.getAccommodationId());
            if (accId != null && !choices.containsKey(accId)) {
                final Accommodation acc = findAccommodation(accId);
                choices.put(accId, acc == null ? accId : acc.getName());
            }
        }
        return choices;
    }

    /** The accommodation the board opens on: the first one this trip has an option at. */
    public String defaultAccommodationId(final String tripId) {
        final Map<String, String> choices = accommodationChoices(tripId);
        return choices.isEmpty() ? "" : choices.keySet().iterator().next();
    }

    /**
     * The floor to show for an accommodation: the one already chosen when that hotel has it (switching
     * hotels used to drop you back to the first floor, losing your place), else its first.
     */
    public String floorFor(final String accId, final String current) {
        final Accommodation acc = findAccommodation(accId);
        final List<String> floors = (acc == null) ? List.of() : sortedFloors(acc);
        if (current != null && !current.isBlank() && floors.contains(current)) {
            return current;
        }
        return floors.isEmpty() ? "" : floors.get(0);
    }

    private static boolean overlaps(final Reservation res, final LocalDateTime from, final LocalDateTime to) {
        if (from == null || to == null || res.getStart() == null || res.getEnd() == null) {
            return true;
        }
        return res.getStart().isBefore(to) && res.getEnd().isAfter(from);
    }

    private RoomCell cellFor(final Accommodation acc, final Room room, final List<Reservation> onRoom,
            final LocalDateTime from, final LocalDateTime to) {
        final RoomType type = acc.roomType(room.getRoomTypeId());
        final Set<Person.Id> everyone = new LinkedHashSet<>();
        final List<OccupantChip> chips = new ArrayList<>();
        for (final Reservation res : onRoom) {
            for (final Person.Id person : res.getOccupants()) {
                if (everyone.add(person)) {
                    chips.add(new OccupantChip(res.getId().getValue(), person.getValue(), displayName(person)));
                }
            }
        }
        int peak = 0;
        final Map<LocalDate, List<Person.Id>> byNight = LodgingPricing.occupancyByNight(onRoom);
        for (final LocalDate night : LodgingPricing.nightsOf(from, to)) {
            peak = Math.max(peak, byNight.getOrDefault(night, List.of()).size());
        }
        final int count = Math.max(peak, everyone.isEmpty() ? 0 : 1);
        final int max = (type == null) ? 0 : type.getMaxPeople();
        final Room.MapRegion region = room.getMapRegion();
        return new RoomCell(room.getId(), room.getRoomNumber(), room.getFloor(), type == null ? "" : type.getName(),
                type == null ? 0 : type.getMinPeople(), max, count, stateOf(count, max), chips, room.getNotes(),
                room.getAdminNotes(), region != null, region == null ? 0 : region.getX(),
                region == null ? 0 : region.getY(), region == null ? 0 : region.getW(),
                region == null ? 0 : region.getH());
    }

    static String stateOf(final int count, final int max) {
        if (count == 0) {
            return "rs-empty";
        }
        if (max > 0 && count > max) {
            return "rs-over";
        }
        return (max > 0 && count >= max) ? "rs-full" : "rs-partial";
    }

    /** A person card: name, age at the trip's start, sex, party size, dates, registration answers, notes. */
    private PersonCard cardFor(final Trip trip, final Person.Id personId, final Reservation res) {
        final Person person = DAO.getInstance().getPerson(personId, Cached.YES).orElse(null);
        final PersonCard card = new PersonCard();
        card.setPersonId(personId.getValue());
        card.setReservationId(res == null ? null : res.getId().getValue());
        card.setName(person == null ? personId.getValue() : displayName(person));
        card.setAge(person == null ? "" : ageAt(person.getBirthdate(), trip.getStartDate()));
        card.setSex(person == null || person.getSex() == null ? "" : person.getSex().name().substring(0, 1));
        card.setPartySize(res == null ? 1 : res.getOccupants().size());
        card.setStart(res == null ? null : res.getStart());
        card.setEnd(res == null ? null : res.getEnd());
        card.setNotes(res == null ? null : res.getNotes());
        card.setAnswers(answersFor(trip, personId));
        return card;
    }

    static String ageAt(final LocalDate birthdate, final LocalDateTime when) {
        if (birthdate == null) {
            return "";
        }
        final LocalDate at = (when == null) ? LocalDate.now() : when.toLocalDate();
        return Integer.toString(Math.max(0, Period.between(birthdate, at).getYears()));
    }

    /** The registration answers worth seeing when placing someone: roommate request first, "Join…" skipped. */
    Map<String, String> answersFor(final Trip trip, final Person.Id personId) {
        final Map<String, String> answers = new LinkedHashMap<>();
        final Registration reg = DAO.getInstance().getRegistration(trip.getId(), personId, Cached.YES).orElse(null);
        if (reg == null || reg.getOptions() == null) {
            return answers;
        }
        final Map<String, String> rest = new LinkedHashMap<>();
        for (final RegistrationOption opt : trip.getRegOptions()) {
            final String label = nullSafe(opt.getShortDesc());
            final String value = reg.getOptions().get(opt.getKey());
            if (label.startsWith("Join") || value == null || value.isBlank()) {
                continue;
            }
            if (label.toLowerCase(Locale.ROOT).contains("room")) {
                answers.put(label, value);
            } else {
                rest.put(label, value);
            }
        }
        answers.putAll(rest);
        return answers;
    }

    // ================================================================== rooming list and compatibility

    /** The rooming list: everyone with a reservation, room then name; the unreserved at the bottom. */
    public List<RoomingRow> roomingList(final String tripId) {
        final List<RoomingRow> rows = new ArrayList<>();
        final Trip trip = tripSource.get().getTrip(tripId);
        if (!tripId.equals(trip.getId())) {
            return rows;
        }
        final Set<Person.Id> reserved = new HashSet<>();
        for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.YES)) {
            if (!res.isActive()) {
                continue;
            }
            final Accommodation acc = findAccommodation(idValue(res.getAccommodationId()));
            final ReservationOffer offer = findOffer(tripId, idValue(res.getOfferId()));
            final Room room = (acc == null) ? null : acc.room(res.getRoomId());
            // The ASSIGNED room's type when there is one (a Triple stays a Triple), else the offer's.
            final String typeId = (room != null) ? room.getRoomTypeId()
                    : (offer == null ? null : offer.firstRoomTypeId());
            final RoomType type = (acc == null) ? null : acc.roomType(typeId);
            for (final Person.Id person : res.getOccupants()) {
                reserved.add(person);
                rows.add(new RoomingRow(person.getValue(), displayName(person), cellOf(person),
                        acc == null ? "" : acc.getName(), room == null ? "" : nullSafe(room.getFloor()),
                        room == null ? "" : room.getRoomNumber(), type == null ? "" : type.getName(),
                        res.getStart(), res.getEnd(), res.getNotes(), answersLine(trip, person), true));
            }
        }
        rows.sort((a, b) -> {
            final int byRoom = naturalCompare(a.getRoom(), b.getRoom());
            return (byRoom != 0) ? byRoom : nullSafe(a.getName()).compareToIgnoreCase(nullSafe(b.getName()));
        });
        // The unreserved: their room, if any, is the LEGACY free-text one (the person_data row the old
        // rooms page wrote), so past trips keep printing what they always did.
        final List<RoomingRow> rest = new ArrayList<>();
        for (final Person.Id person : trip.getPeople()) {
            if (!reserved.contains(person)) {
                rest.add(new RoomingRow(person.getValue(), displayName(person), cellOf(person), "", "",
                        legacyRoom(tripId, person), "", null, null, null, answersLine(trip, person), false));
            }
        }
        rest.sort((a, b) -> nullSafe(a.getName()).compareToIgnoreCase(nullSafe(b.getName())));
        rows.addAll(rest);
        return rows;
    }

    /** The old rooms page's free-text room for someone with no reservation, or "". */
    static String legacyRoom(final String tripId, final Person.Id person) {
        final org.paulsens.trip.model.PersonDataValue pdv = PersonDataValueCommands.getPersonDataValue(person,
                RegistrationCommands.tripRoomDataId(tripId));
        return (pdv == null || pdv.getContent() == null) ? "" : pdv.getContent().toString();
    }

    private String cellOf(final Person.Id person) {
        return DAO.getInstance().getPerson(person, Cached.YES).map(Person::getCell).map(LodgingCommands::nullSafe)
                .orElse("");
    }

    private String answersLine(final Trip trip, final Person.Id person) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, String> entry : answersFor(trip, person).entrySet()) {
            sb.append(entry.getKey()).append(" <b>").append(escape(entry.getValue())).append("</b><br />");
        }
        return sb.toString();
    }

    /**
     * The person's room label on this trip from their ACTIVE reservations ("114", or "114 / 201" across two
     * stays), or null when no reservation names a room -- what {@code RegistrationCommands.getRoomPDV} asks
     * before falling back to the legacy free-text room.
     */
    public String roomLabelFor(final String tripId, final Person.Id personId) {
        if (tripId == null || personId == null) {
            return null;
        }
        final List<Reservation> active = activeReservationsFor(tripId, personId);
        active.sort((a, b) -> compareNullable(a.getStart(), b.getStart()));
        final Set<String> labels = new LinkedHashSet<>();
        for (final Reservation res : active) {
            final Accommodation acc = findAccommodation(idValue(res.getAccommodationId()));
            final String label = (acc == null) ? null : acc.roomLabel(res.getRoomId());
            if (label != null && !label.isBlank()) {
                labels.add(label);
            }
        }
        return labels.isEmpty() ? null : String.join(" / ", labels);
    }

    /** {@link #roomLabelFor} for pages, never null: "" when nothing is reserved. */
    public String roomLabel(final String tripId, final Person.Id personId) {
        return nullSafe(roomLabelFor(tripId, personId));
    }

    // ================================================================== itinerary

    /** Whether this trip's itineraries show room numbers (the Assignments tab's toggle); unknown trips: yes. */
    public boolean roomNumbersShown(final String tripId) {
        final Trip trip = (tripId == null || tripId.isBlank()) ? null
                : DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        return trip == null || trip.getRoomNumbersShown();
    }

    /**
     * The Assignments tab's "Show room numbers on itineraries" switch. Off while assignments are still being
     * worked out, so a temporary placement never reads as a decision; on when the plan is done. Read-modify-write
     * on a FRESH trip, never a cached one, and saved at once -- this is a setting, not part of any edit draft.
     */
    public boolean setRoomNumbersShown(final String tripId, final boolean shown) {
        if (!canAssignRooms(tripId)) {
            return refuse("Not allowed: you do not assign rooms on this trip.");
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        // getTripForEdit answers a blank trip with a fresh id for an unknown one; the same-id probe is the test.
        if (trip == null || !tripId.equals(trip.getId())) {
            return refuse("This trip no longer exists.");
        }
        trip.setRoomNumbersShown(shown);
        if (!tripSource.get().saveTrip(trip)) {
            return refuse("The setting could not be saved.");
        }
        auditSource.get().lodging(AuditEventBuilder.TARGET_TRIP, tripId, trip.getOrgId(),
                (shown ? "Room numbers shown" : "Room numbers hidden") + " on itineraries for '" + trip.getTitle()
                        + "'", caller().auditActor());
        info(shown ? "Room numbers are shown on itineraries again."
                : "Room numbers are hidden from itineraries until you turn this back on.");
        return true;
    }

    /**
     * The itinerary's rows in the page's FROZEN event order: every event as it is, except a LODGING event for
     * which the person holds an ACTIVE reservation on an offer that tracks it -- that row carries the
     * reservation's dates (earliest start, latest end across their reservations), room and notes.
     */
    public List<ItineraryRow> itineraryRows(final Trip trip, final List<String> frozenEventIds,
            final Person.Id personId) {
        final List<ItineraryRow> rows = new ArrayList<>();
        if (trip == null || personId == null) {
            return rows;
        }
        final Map<String, List<Reservation>> byEvent = reservationsByEvent(trip.getId(), personId);
        final boolean showRooms = trip.getRoomNumbersShown();
        for (final TripEvent event : tripSource.get().eventsForFrozenIds(trip, frozenEventIds)) {
            rows.add(rowFor(event, personId, byEvent.get(event.getId()), showRooms));
        }
        // By the date the row SHOWS, not the event's own: a late arriver's reservation starts days after the
        // group's hotel event, so ordering by the event put her hotel above the flights that got her there.
        // A stable sort, so events sharing a moment keep the order the trip gave them; undated rows sink.
        rows.sort(Comparator.comparing(ItineraryRow::getEffectiveStart,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /** The GET-only print pages: every event of the person, unfrozen, same rows. */
    public List<ItineraryRow> itineraryRowsFor(final Trip trip, final Person.Id personId) {
        if (trip == null || personId == null) {
            return new ArrayList<>();
        }
        return itineraryRows(trip, tripSource.get().eventIdsOf(trip.getTripEventsForUser(personId)), personId);
    }

    private Map<String, List<Reservation>> reservationsByEvent(final String tripId, final Person.Id personId) {
        final Map<String, List<Reservation>> byEvent = new HashMap<>();
        final Map<ReservationOffer.Id, ReservationOffer> offers = new HashMap<>();
        for (final ReservationOffer offer : DAO.getInstance().getReservationOffers(tripId, Cached.YES)) {
            offers.put(offer.getId(), offer);
        }
        for (final Reservation res : activeReservationsFor(tripId, personId)) {
            final ReservationOffer offer = offers.get(res.getOfferId());
            if (offer != null && offer.getTripEventId() != null) {
                byEvent.computeIfAbsent(offer.getTripEventId(), k -> new ArrayList<>()).add(res);
            }
        }
        return byEvent;
    }

    private ItineraryRow rowFor(final TripEvent event, final Person.Id personId, final List<Reservation> mine,
            final boolean showRooms) {
        final ItineraryRow row = new ItineraryRow();
        row.setId(event.getId());
        row.setType(event.getType() == null ? "EVENT" : event.getType().name());
        row.setTitle(event.getTitle());
        row.setNotes(event.getNotes());
        row.setStart(event.getStart());
        row.setEnd(event.getEnd());
        row.setEffectiveStart(event.getStart());
        row.setEffectiveEnd(event.getEnd());
        row.setParticipantCount(event.getParticipants().size());
        row.getParticipantIds().addAll(event.getParticipants());
        row.setPrivNote(nullSafe(event.getPrivNotes().get(personId)));
        row.setLodging(event.getType() == TripEvent.Type.LODGING);
        if (mine == null || mine.isEmpty()) {
            return row;
        }
        LocalDateTime earliest = null;
        LocalDateTime latest = null;
        final Set<String> labels = new LinkedHashSet<>();
        final List<String> notes = new ArrayList<>();
        for (final Reservation res : mine) {
            earliest = (earliest == null || compareNullable(res.getStart(), earliest) < 0) ? res.getStart() : earliest;
            latest = (latest == null || compareNullable(res.getEnd(), latest) > 0) ? res.getEnd() : latest;
            final Accommodation acc = findAccommodation(idValue(res.getAccommodationId()));
            final ReservationOffer offer = findOffer(res.getTripId(), idValue(res.getOfferId()));
            if (acc != null) {
                row.setAccommodationName(acc.getName());
                final String label = acc.roomLabel(res.getRoomId());
                if (label != null) {
                    labels.add(label);
                }
                final Room room = acc.room(res.getRoomId());
                final RoomType type = acc.roomType(room != null ? room.getRoomTypeId()
                        : (offer == null ? null : offer.firstRoomTypeId()));
                if (type != null) {
                    row.setRoomTypeName(type.getName());
                }
            }
            if (res.getNotes() != null && !res.getNotes().isBlank()) {
                notes.add(res.getNotes());
            }
        }
        row.setReservationId(mine.get(0).getId().getValue());
        row.setEffectiveStart(earliest == null ? event.getStart() : earliest);
        row.setEffectiveEnd(latest == null ? event.getEnd() : latest);
        // "Overridden" means the DATES differ from the group's; a reservation on the group dates still gets
        // its room line, but no "dates from your reservation" hint.
        row.setOverridden(!Objects.equals(row.getEffectiveStart(), event.getStart())
                || !Objects.equals(row.getEffectiveEnd(), event.getEnd()));
        // The number is withheld while the trip's admin is still planning (the Assignments tab's switch); the
        // type and the nights are still theirs to see.
        row.setRoomLabel(labels.isEmpty() || !showRooms ? null : String.join(" / ", labels));
        row.setReservationNotes(notes.isEmpty() ? null : String.join(" ", notes));
        row.setNights(Reservation.nightsBetween(row.getEffectiveStart(), row.getEffectiveEnd()));
        return row;
    }

    // ================================================================== plumbing

    private Caller caller() {
        return callerSource.get();
    }

    private Accommodation freshAccommodation(final String accId) {
        if (accId == null || accId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getAccommodation(Accommodation.Id.from(accId.trim()), Cached.NO).orElse(null);
    }

    /** The one write path for an accommodation: prune dead media refs, conditional put, growl on a race. */
    private boolean store(final Accommodation acc) {
        pruneDanglingMedia(acc);
        try {
            return DAO.getInstance().saveAccommodation(acc) || refuse("The accommodation could not be saved.");
        } catch (final ConditionalCheckFailedException ex) {
            return refuse("Saved by someone else: reload the page and try again.");
        } catch (final IllegalArgumentException ex) {
            return refuse(ex.getMessage());
        } catch (final IOException ex) {
            log.error("Unable to save accommodation {}", acc.getId(), ex);
            return refuse("The accommodation could not be saved: " + ex.getMessage());
        }
    }

    private boolean audited(final Accommodation acc, final String what) {
        auditSource.get().lodging(AuditEventBuilder.TARGET_ACCOMMODATION, acc.getId().getValue(), null,
                what + " at '" + acc.getName() + "'", caller().auditActor());
        return true;
    }

    private String names(final Collection<Person.Id> ids) {
        final List<String> names = new ArrayList<>();
        for (final Person.Id id : ids) {
            names.add(displayName(id));
        }
        return String.join(", ", names);
    }

    private String displayName(final Person.Id id) {
        return DAO.getInstance().getPerson(id, Cached.YES).map(LodgingCommands::displayName).orElse(id.getValue());
    }

    private static String displayName(final Person person) {
        final String first = nullSafe(person.getPreferredName()).trim();
        final String last = nullSafe(person.getLast()).trim();
        final String name = (first + " " + last).trim();
        return name.isEmpty() ? person.getId().getValue() : name;
    }

    private String preferredName(final Person.Id id) {
        return DAO.getInstance().getPerson(id, Cached.YES).map(Person::getPreferredName).orElse(id.getValue());
    }

    /**
     * The notes a NEW lodging event starts with: the hotel's address, so the itinerary shows where the stay is
     * under its name. Escaped here because event notes render as HTML everywhere.
     */
    static String lodgingEventNotes(final Accommodation acc) {
        return acc == null || acc.getAddress() == null ? "" : escape(acc.getAddress().oneLine());
    }

    private static String place(final Address address) {
        final List<String> parts = new ArrayList<>();
        for (final String part : List.of(nullSafe(address.getCity()), nullSafe(address.getCountry()))) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return String.join(", ", parts);
    }

    private static <T extends Comparable<T>> int compareNullable(final T a, final T b) {
        if (a == null) {
            return (b == null) ? 0 : 1;
        }
        return (b == null) ? -1 : a.compareTo(b);
    }

    private static String idValue(final Object id) {
        if (id instanceof ReservationOffer.Id offer) {
            return offer.getValue();
        }
        if (id instanceof Accommodation.Id acc) {
            return acc.getValue();
        }
        return (id == null) ? null : id.toString();
    }

    /** "1,234.50" or "$55" to a Double; null for blank or unparseable text. */
    static Double parseDollars(final String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.replace("$", "").replace(",", "").trim());
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    static long cents(final Double dollars) {
        return (dollars == null) ? 0L : MoneyMath.toCents(BigDecimal.valueOf(Math.max(0.0, dollars)));
    }

    static Double dollars(final long cents) {
        return cents / 100.0;
    }

    static String nullSafe(final String value) {
        return (value == null) ? "" : value;
    }

    static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String escape(final String value) {
        return nullSafe(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Whether this bean's name resolver can be handed to pricing (a seam for tests). */
    Function<Person.Id, String> nameResolver() {
        return this::preferredName;
    }
}
