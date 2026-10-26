package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.action.RatePlanViews.AttachForm;
import org.paulsens.trip.action.RatePlanViews.PlanChoice;
import org.paulsens.trip.action.RatePlanViews.RatePlanForm;
import org.paulsens.trip.action.RatePlanViews.RatePlanRow;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.CostExtra;
import org.paulsens.trip.model.RatePlan;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.RoomType;
import org.paulsens.trip.model.Trip;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import static org.paulsens.trip.action.PageFeedback.info;
import static org.paulsens.trip.action.PageFeedback.refuse;
import static org.paulsens.trip.action.PageFeedback.warn;

/**
 * Hotel rate plans, exposed to pages as {@code #{ratePlans}}: the hotel page's Rate plans tab and the Offers
 * tab's "Add from hotel rate plan". Feature doc: {@code docs/lodging.md}, "Rate plans".
 *
 * <p>A {@link RatePlan} is the HOTEL's rate template -- its cost and extras for some room types over a date
 * range, and NOTHING of the participant price (owner decision 2026-10-26: the price differs per trip and an
 * operator must never see one). A trip takes it by {@link #attach}: a {@link ReservationOffer} is created whose
 * COST side is a COPY of the plan's and which remembers the plan's id and version, and the plan records the trip
 * in {@code linkedTripIds}; the option's PRICE is the trip's own, entered on the attach ({@link AttachForm}) or
 * on the Offers tab afterwards, and the same plan may be attached to a trip several times (two options at two
 * prices). The link is LIVE in one direction: {@link #savePlan} fans the new cost out to every linked offer
 * ({@link #syncLinked}) and NEVER recomputes a bill, since no bill reads the cost side; {@link #detach} leaves
 * the offer with its copy and forgets the trip once no other option of the trip still follows the plan.
 * Write-through, never a read-time join: {@code LodgingPricing}, the reports, the PDF and REST never learn that
 * plans exist.
 *
 * <p>One gate: {@link #canEditCost} = anyone who may edit the hotel (its Hotel Operators, global lodging
 * admins) writes its plans. Reading the table ({@link #rows}) takes whoever may open the hotel page.
 */
@Slf4j
@Named("ratePlans")
@ApplicationScoped
public class RatePlanCommands {

    private final Supplier<Caller> callerSource;
    private final Supplier<LodgingCommands> lodgingSource;
    private final Supplier<TripCommands> tripSource;
    private final Supplier<AuditCommands> auditSource;
    private final LodgingAccessCommands access;

    public RatePlanCommands() {
        this(Caller::current);
    }

    /** Test seam (the {@link HotelCommands} pattern): {@code Caller.current()} needs a FacesContext. */
    public RatePlanCommands(final Supplier<Caller> callerSource) {
        this(callerSource, () -> new LodgingCommands(callerSource),
                () -> new TripCommands(() -> new OrgCommands(callerSource)), AuditCommands::new);
    }

    RatePlanCommands(final Supplier<Caller> callerSource, final Supplier<LodgingCommands> lodgingSource,
            final Supplier<TripCommands> tripSource, final Supplier<AuditCommands> auditSource) {
        this.callerSource = callerSource;
        this.lodgingSource = lodgingSource;
        this.tripSource = tripSource;
        this.auditSource = auditSource;
        this.access = new LodgingAccessCommands(callerSource);
    }

    // ================================================================== gates

    /** May state the hotel's COST on its plans: whoever may edit the hotel (operators, global admins). */
    public boolean canEditCost(final String accId) {
        return lodging().canEditAccommodation(accId);
    }

    /** May read the plan table: whoever may open the hotel's page (its editors and the lodging admins). */
    boolean canRead(final String accId) {
        final LodgingCommands lodging = lodging();
        return lodging.canEditAccommodation(accId) || lodging.canOpenLodgingAdmin();
    }

    // ================================================================== the table

    /** The hotel's plans by name, as rows; the same rows for every reader (no price is on a plan). */
    public List<RatePlanRow> rows(final String accId) {
        final List<RatePlanRow> rows = new ArrayList<>();
        final Accommodation acc = lodging().findAccommodation(accId);
        if (acc == null || !canRead(accId)) {
            return rows;
        }
        for (final RatePlan plan : sortedPlans(accId, Cached.YES)) {
            rows.add(rowOf(acc, plan));
        }
        return rows;
    }

    private RatePlanRow rowOf(final Accommodation acc, final RatePlan plan) {
        return new RatePlanRow(plan.getId().getValue(), plan.getName(), roomTypeNames(acc, plan.getRoomTypeIds()),
                datesLabel(plan), OfferSchedules.planCostLabel(plan), linkedTripsLabel(acc, plan),
                plan.getLinkedTripIds().size(), plan.isRetired(), plan.getNotes());
    }

    /** "Double, Triple": the plan's room types by name, in its order; a type no longer at the hotel reads "?". */
    static String roomTypeNames(final Accommodation acc, final List<String> typeIds) {
        final List<String> names = new ArrayList<>();
        for (final String typeId : typeIds) {
            final RoomType type = acc.roomType(typeId);
            names.add(type == null ? "?" : type.getName());
        }
        return String.join(", ", names);
    }

    /** "Sep 1 - Oct 31, 2026", "from Sep 1, 2026", "until Oct 31, 2026", or "any dates". */
    static String datesLabel(final RatePlan plan) {
        final LocalDate from = plan.getValidFrom();
        final LocalDate to = plan.getValidUntil();
        if (from == null && to == null) {
            return "any dates";
        }
        if (from == null) {
            return "until " + HotelCommands.dateSpan(null, to);
        }
        if (to == null) {
            return "from " + HotelCommands.dateSpan(from, null);
        }
        return HotelCommands.dateSpan(from, to);
    }

    /**
     * The linked trips as this caller may read them: titles for the trips the naming rule lets them name
     * (internal on the trip, or an operator of this hotel), a count for the rest.
     */
    private String linkedTripsLabel(final Accommodation acc, final RatePlan plan) {
        final List<String> named = new ArrayList<>();
        int unnamed = 0;
        for (final String tripId : plan.getLinkedTripIds()) {
            final Trip trip = DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
            if (trip != null && access.mayNameTrip(tripId, acc.getId().getValue())) {
                named.add(trip.getTitle());
            } else {
                unnamed++;
            }
        }
        if (unnamed == 0) {
            return String.join(", ", named);
        }
        final String rest = unnamed + (named.isEmpty() ? (unnamed == 1 ? " trip" : " trips")
                : (unnamed == 1 ? " other trip" : " other trips"));
        return named.isEmpty() ? rest : String.join(", ", named) + ", " + rest;
    }

    // ================================================================== the dialog

    /** The dialog's form: a blank new plan for the hotel, or the existing plan filled in. Never null. */
    public RatePlanForm planFormFor(final String accId, final String planId) {
        final RatePlanForm form = new RatePlanForm();
        form.setAccId(accId);
        final RatePlan plan = findPlan(accId, planId, Cached.YES);
        if (plan == null) {
            return form;
        }
        form.setPlanId(plan.getId().getValue());
        form.setName(plan.getName());
        form.setRoomTypeIds(plan.getRoomTypeIds());
        form.setValidFrom(plan.getValidFrom());
        form.setValidUntil(plan.getValidUntil());
        OfferSchedules.fillPlanForm(form, plan);
        form.setNotes(plan.getNotes());
        form.setRetired(plan.isRetired());
        return form;
    }

    /**
     * Creates or edits a plan, then pushes its cost to every linked offer. Gate: {@link #canEditCost}. Refusals
     * land on {@code form.problem} and in a growl; a fan-out that could not reach every trip is reported there
     * too, but the plan itself is saved. No bill ever moves: a plan carries the hotel's side only.
     */
    public boolean savePlan(final RatePlanForm form) {
        if (form == null) {
            return refuse("Nothing to save.");
        }
        final String accId = form.getAccId();
        if (!canEditCost(accId)) {
            return refuseInto(form, "Not allowed: only this hotel's operators and lodging admins edit its rate plans.");
        }
        final Accommodation acc = lodging().findAccommodation(accId);
        if (acc == null || acc.isRetired()) {
            return refuseInto(form, "That accommodation is retired or gone.");
        }
        final boolean editing = form.getPlanId() != null && !form.getPlanId().isBlank();
        final RatePlan plan = editing ? findPlan(accId, form.getPlanId(), Cached.NO)
                : RatePlan.builder().accommodationId(acc.getId()).createdBy(caller().personId())
                        .created(LocalDateTime.now()).build();
        if (plan == null) {
            return refuseInto(form, "This rate plan no longer exists.");
        }
        final String problem = problem(acc, form);
        if (problem != null) {
            return refuseInto(form, problem);
        }
        final RateSchedule costBefore = plan.getCost();
        final List<CostExtra> extrasBefore = new ArrayList<>(plan.getCostExtras());
        apply(plan, form);
        if (!store(plan, form)) {
            return false;
        }
        form.setPlanId(plan.getId().getValue());
        form.setProblem(null);
        final boolean costChanged = !Objects.equals(costBefore, plan.getCost())
                || !extrasBefore.equals(plan.getCostExtras());
        audit(plan, null, (editing ? "Saved rate plan '" : "Created rate plan '") + plan.getName() + "' at '"
                + acc.getName() + "'" + (costChanged ? " (cost changed)" : ""));
        return report(plan, form, syncLinked(plan));
    }

    /** The plan dialog's refusals; null when the plan can be saved as the form says. */
    private String problem(final Accommodation acc, final RatePlanForm form) {
        if (form.getName() == null || form.getName().isBlank()) {
            return "A rate plan needs a name.";
        }
        final List<String> typeIds = new ArrayList<>(new LinkedHashSet<>(form.getRoomTypeIds()));
        typeIds.removeIf(Objects::isNull);
        typeIds.removeIf(String::isBlank);
        if (typeIds.isEmpty()) {
            return "Choose at least one room type.";
        }
        for (final String typeId : typeIds) {
            if (acc.roomType(typeId) == null) {
                return "That room type is not at " + acc.getName() + ".";
            }
        }
        form.setRoomTypeIds(typeIds);
        if (form.getValidFrom() != null && form.getValidUntil() != null
                && form.getValidUntil().isBefore(form.getValidFrom())) {
            return "The plan's last date cannot be before its first.";
        }
        return OfferSchedules.planProblem(form);
    }

    private static void apply(final RatePlan plan, final RatePlanForm form) {
        plan.setName(form.getName().trim());
        plan.setRoomTypeIds(new ArrayList<>(form.getRoomTypeIds()));
        plan.setValidFrom(form.getValidFrom());
        plan.setValidUntil(form.getValidUntil());
        OfferSchedules.applyPlanCost(plan, form);
        plan.setNotes(LodgingCommands.blankToNull(form.getNotes()));
        plan.setRetired(form.isRetired() ? Boolean.TRUE : null);
    }

    /** Retires (or revives) a plan: it leaves the attach menu; linked offers keep following it. */
    public boolean retirePlan(final String accId, final String planId, final boolean retired) {
        if (!canEditCost(accId)) {
            return refuse("Not allowed: only this hotel's operators and lodging admins edit its rate plans.");
        }
        final RatePlan plan = findPlan(accId, planId, Cached.NO);
        if (plan == null) {
            return refuse("That rate plan no longer exists.");
        }
        plan.setRetired(retired ? Boolean.TRUE : null);
        if (!store(plan, null)) {
            return false;
        }
        audit(plan, null, (retired ? "Retired rate plan '" : "Revived rate plan '") + plan.getName() + "'");
        // The version moved, so the linked offers re-copy (same numbers) rather than read as drifted.
        final SyncOutcome sync = syncLinked(plan);
        if (!sync.failures().isEmpty()) {
            warn("Plan saved; " + sync.updated() + " of " + sync.total() + " trips updated; "
                    + String.join("; ", sync.failures()));
        } else {
            info("Rate plan '" + plan.getName() + "' " + (retired ? "retired." : "revived."));
        }
        return true;
    }

    /** Deletes a plan nothing follows; a linked plan must be detached from every trip first. */
    public boolean deletePlan(final String accId, final String planId) {
        if (!canEditCost(accId)) {
            return refuse("Not allowed: only this hotel's operators and lodging admins edit its rate plans.");
        }
        final RatePlan plan = findPlan(accId, planId, Cached.NO);
        if (plan == null) {
            return refuse("That rate plan is already gone.");
        }
        final int linked = plan.getLinkedTripIds().size();
        if (linked > 0) {
            return refuse("'" + plan.getName() + "' is on " + linked + (linked == 1 ? " trip" : " trips")
                    + ". Detach it from " + linked + (linked == 1 ? " trip" : " trips") + " first.");
        }
        if (!Boolean.TRUE.equals(DAO.getInstance().deleteRatePlan(accId, plan.getId()))) {
            return refuse("The rate plan could not be removed.");
        }
        audit(plan, null, "Deleted rate plan '" + plan.getName() + "'");
        info("Rate plan '" + plan.getName() + "' removed.");
        return true;
    }

    // ================================================================== the fan-out

    /** What {@link #syncLinked} achieved: trips it should have reached, trips fully updated, and the losses. */
    record SyncOutcome(int total, int updated, List<String> failures) {
    }

    /**
     * Pushes the plan to every linked trip: each offer following the plan re-copies its COST side and is saved;
     * no bill is recomputed, ever, because no bill reads the cost side and the offer's price is untouched. A
     * trip with no offer following the plan any more (or no trip row) is PRUNED first and the plan saved once,
     * so the offers then copy the plan's final version and read as current. A lost conditional put on one
     * offer is reported, not retried: that offer keeps its old {@code ratePlanVersion}, which the Offers tab
     * shows as drift for the admin to re-save or detach.
     */
    SyncOutcome syncLinked(final RatePlan plan) {
        final List<String> failures = new ArrayList<>();
        final List<Trip> trips = new ArrayList<>();
        final List<String> prune = new ArrayList<>();
        for (final String tripId : new ArrayList<>(plan.getLinkedTripIds())) {
            final Trip trip = DAO.getInstance().getTrip(tripId, Cached.NO).orElse(null);
            if (trip == null || offersFollowing(tripId, plan).isEmpty()) {
                prune.add(tripId);
            } else {
                trips.add(trip);
            }
        }
        for (final String tripId : prune) {
            plan.unlink(tripId);
        }
        if (!prune.isEmpty() && !storeQuietly(plan)) {
            failures.add("the plan's trip list could not be pruned (saved by someone else)");
        }
        int updated = 0;
        for (final Trip trip : trips) {
            if (pushTo(trip, plan, failures)) {
                updated++;
            }
        }
        return new SyncOutcome(trips.size(), updated, failures);
    }

    /** Re-copies the plan's cost onto the trip's following offers; true when every one of them saved. */
    private boolean pushTo(final Trip trip, final RatePlan plan, final List<String> failures) {
        boolean all = true;
        final List<ReservationOffer> offers = offersFollowing(trip.getId(), plan);
        for (final ReservationOffer offer : offers) {
            OfferSchedules.copyFromPlan(offer, plan);
            if (!saveOfferQuietly(offer)) {
                failures.add("trip '" + trip.getTitle() + "': saved by someone else");
                all = false;
            }
        }
        audit(plan, trip.getOrgId(), "Rate plan '" + plan.getName() + "' pushed to " + offers.size()
                + (offers.size() == 1 ? " lodging option" : " lodging options") + " on '" + trip.getTitle() + "'"
                + (all ? "" : " (not all saved)"));
        return all;
    }

    private boolean report(final RatePlan plan, final RatePlanForm form, final SyncOutcome sync) {
        if (sync.failures().isEmpty()) {
            final String trips = (sync.updated() == 1) ? " linked trip updated." : " linked trips updated.";
            final String tail = (sync.total() == 0) ? "." : "; " + sync.updated() + trips;
            info("Rate plan '" + plan.getName() + "' saved" + tail);
            return true;
        }
        final String message = "Plan saved; " + sync.updated() + " of " + sync.total() + " trips updated; "
                + String.join("; ", sync.failures());
        form.setProblem(message);
        warn(message);
        return true;
    }

    // ================================================================== attach / detach

    /**
     * The "Add from hotel rate plan" menu for a trip: every live plan of every live hotel that covers the
     * trip's dates, by hotel then plan -- a plan already on the trip included, since a trip may take the same
     * plan twice at two participant prices. Empty for a caller who may not manage the trip's lodging, and for
     * a trip without dates (nothing can be said to cover them).
     */
    public List<PlanChoice> attachable(final String tripId) {
        final List<PlanChoice> choices = new ArrayList<>();
        final LodgingCommands lodging = lodging();
        if (!lodging.canManageTripLodging(tripId)) {
            return choices;
        }
        final Trip trip = tripSource.get().getTrip(tripId);
        final LocalDate from = dateOf(trip.getStartDate());
        final LocalDate to = dateOf(trip.getEndDate());
        for (final Accommodation acc : lodging.getAccommodations()) {
            for (final RatePlan plan : sortedPlans(acc.getId().getValue(), Cached.YES)) {
                if (!plan.isRetired() && plan.covers(from, to)) {
                    choices.add(new PlanChoice(plan.getId().getValue(), acc.getId().getValue(),
                            acc.getName() + ": " + plan.getName() + " (" + datesLabel(plan) + ")"));
                }
            }
        }
        return choices;
    }

    /** The attach dialog's blank form for a trip: no plan chosen yet, the price fieldset on. */
    public AttachForm attachFormFor(final String tripId) {
        final AttachForm form = new AttachForm();
        form.setTripId(tripId);
        return form;
    }

    /** {@link #attach(String, String, String)} from a menu value ({@link PlanChoice#getValue()}), unpriced. */
    public boolean attach(final String tripId, final String choice) {
        final AttachForm form = attachFormFor(tripId);
        form.setChoice(choice);
        form.setPriceKnown(false);
        return attach(form);
    }

    /**
     * The attach dialog: the trip, the chosen plan ({@code form.choice}) and, when {@code form.priceKnown}, the
     * new option's participant price. A refusal lands on {@code form.problem} as well as in a growl, so the
     * dialog can show it under a modal.
     */
    public boolean attach(final AttachForm form) {
        if (form == null) {
            return refuse("Choose a rate plan.");
        }
        final String choice = form.getChoice();
        final int at = (choice == null) ? -1 : choice.indexOf(PlanChoice.SEPARATOR);
        if (at <= 0 || at == choice.length() - 1) {
            return refuseInto(form, "Choose a rate plan.");
        }
        return attach(form.getTripId(), choice.substring(0, at), choice.substring(at + 1), form);
    }

    /** Puts a plan on the trip as a new, UNPRICED option ("needs participant pricing" until the tab sets one). */
    public boolean attach(final String tripId, final String accId, final String planId) {
        final AttachForm unpriced = attachFormFor(tripId);
        unpriced.setPriceKnown(false);
        return attach(tripId, accId, planId, unpriced);
    }

    /**
     * Puts a hotel's plan on the trip as a new lodging option: named after the plan, its room types the plan's,
     * its DATE RANGE the plan's dates where set (each open end falling back to the trip's dates), its DEFAULT
     * STAY the trip's dates, a NEW lodging event, the COST side COPIED from the plan ({@code OfferForm.ratePlanId}
     * tells {@code saveOffer} to copy it) and the PRICE side from {@code priceFields} -- unpriced unless
     * {@code priceKnown}. The same plan may be attached to a trip again (another option, another price). The
     * trip joins the plan's {@code linkedTripIds} FIRST when it is not on it yet: that write bumps the plan's
     * version, and the offer must copy the version it will then be compared against, or it would read as
     * drifted from birth. A refused offer save undoes a link made here. Gate: {@code canManageTripLodging}.
     */
    public boolean attach(final String tripId, final String accId, final String planId,
            final AttachForm priceFields) {
        final LodgingCommands lodging = lodging();
        if (tripId == null || tripId.isBlank()) {
            return refuseInto(priceFields, "Unknown trip.");
        }
        if (!lodging.canManageTripLodging(tripId)) {
            return refuseInto(priceFields,
                    "Not allowed: only the trip's managers and lodging admins add lodging options.");
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        if (!tripId.equals(trip.getId())) {
            return refuseInto(priceFields, "Unknown trip.");
        }
        final Accommodation acc = lodging.findAccommodation(accId);
        final RatePlan plan = findPlan(accId, planId, Cached.NO);
        if (acc == null || acc.isRetired() || plan == null) {
            return refuseInto(priceFields, "That rate plan no longer exists.");
        }
        if (plan.isRetired()) {
            return refuseInto(priceFields, "'" + plan.getName() + "' is retired.");
        }
        if (!plan.covers(dateOf(trip.getStartDate()), dateOf(trip.getEndDate()))) {
            return refuseInto(priceFields,
                    "'" + plan.getName() + "' (" + datesLabel(plan) + ") does not cover the trip's dates.");
        }
        final String money = OfferSchedules.attachProblem(priceFields);
        if (money != null) {
            return refuseInto(priceFields, money);
        }
        // The blank form carries the trip's dates as range AND default stay; the plan's dates replace the range.
        final OfferForm form = lodging.offerFormFor(tripId, null);
        form.setName(plan.getName());
        form.setAccommodationId(accId);
        form.setRoomTypeIds(new ArrayList<>(plan.getRoomTypeIds()));
        form.setTripEventId(OfferForm.NEW_EVENT);
        form.setRatePlanId(plan.getId().getValue());
        OfferSchedules.applyAttachPrice(form, priceFields);
        if (plan.getValidFrom() != null) {
            form.setValidFrom(plan.getValidFrom().atStartOfDay());
        }
        if (plan.getValidUntil() != null) {
            form.setValidUntil(plan.getValidUntil().atTime(23, 59));
        }
        final boolean newlyLinked = plan.link(tripId);
        if (newlyLinked && !storeQuietly(plan)) {
            return refuseInto(priceFields, "Saved by someone else: reload the page and try again.");
        }
        if (!lodging.saveOffer(tripId, form)) {
            if (newlyLinked) {
                // Only the link THIS attach made: another option of the trip may still follow the plan.
                plan.unlink(tripId);
                storeQuietly(plan);
            }
            priceFields.setProblem(form.getProblem());
            return false;
        }
        priceFields.setProblem(null);
        audit(plan, trip.getOrgId(), "Attached rate plan '" + plan.getName() + "' (" + acc.getName() + ") to '"
                + trip.getTitle() + "' as lodging option '" + form.getName() + "'");
        info("Added '" + plan.getName() + "' from " + acc.getName() + " as a lodging option.");
        return true;
    }

    /**
     * Cuts an option loose from its plan: the schedules stay as they are (they were always the option's own
     * copy), the link fields clear, and the trip leaves the plan's list when no other option of the trip still
     * follows it. No bill moves. Gate: {@code canManageTripLodging}.
     */
    public boolean detach(final String tripId, final String offerId) {
        if (!lodging().canManageTripLodging(tripId)) {
            return refuse("Not allowed: only the trip's managers and lodging admins edit lodging options.");
        }
        final ReservationOffer offer = (offerId == null || offerId.isBlank()) ? null
                : DAO.getInstance().getReservationOffer(tripId, ReservationOffer.Id.from(offerId), Cached.NO)
                        .orElse(null);
        if (offer == null) {
            return refuse("Unknown offer.");
        }
        if (!offer.isLinked()) {
            return refuse("'" + offer.getName() + "' does not follow a rate plan.");
        }
        final String planId = offer.getRatePlanId();
        final String accId = LodgingCommands.idValue(offer.getAccommodationId());
        offer.setRatePlanId(null);
        offer.setRatePlanVersion(0L);
        if (!saveOfferQuietly(offer)) {
            return refuse("Saved by someone else: reload the page and try again.");
        }
        boolean stillFollowed = false;
        for (final ReservationOffer other : DAO.getInstance().getReservationOffers(tripId, Cached.NO)) {
            stillFollowed |= planId.equals(other.getRatePlanId());
        }
        final RatePlan plan = findPlan(accId, planId, Cached.NO);
        if (!stillFollowed && plan != null && plan.unlink(tripId)) {
            storeQuietly(plan);
        }
        auditSource.get().lodging(AuditEventBuilder.TARGET_RATE_PLAN, planId, offer.getOrgId(),
                "Detached lodging option '" + offer.getName() + "' from rate plan '"
                        + (plan == null ? planId : plan.getName()) + "'; its pricing is now its own",
                caller().auditActor());
        info("'" + offer.getName() + "' no longer follows the rate plan; its pricing is now its own.");
        return true;
    }

    /**
     * Forgets a trip on a plan (the trip-delete cascade, after the offers are gone). Tolerates a missing plan.
     * Audited under no organization: the hotel's row is global.
     */
    public boolean unlink(final String accId, final String planId, final String tripId) {
        final RatePlan plan = findPlan(accId, planId, Cached.NO);
        if (plan == null || !plan.unlink(tripId)) {
            return false;
        }
        if (!storeQuietly(plan)) {
            return false;
        }
        audit(plan, null, "Unlinked trip " + tripId + " from rate plan '" + plan.getName() + "'");
        return true;
    }

    /** {@link #unlink} for every (hotel, plan) the trip's offers named; the count of plans updated. */
    public int unlinkAll(final String tripId, final List<ReservationOffer> offers) {
        final Set<String> seen = new LinkedHashSet<>();
        int unlinked = 0;
        for (final ReservationOffer offer : offers) {
            final String accId = LodgingCommands.idValue(offer.getAccommodationId());
            if (offer.isLinked() && accId != null && seen.add(accId + PlanChoice.SEPARATOR + offer.getRatePlanId())
                    && unlink(accId, offer.getRatePlanId(), tripId)) {
                unlinked++;
            }
        }
        return unlinked;
    }

    // ================================================================== hooks for LodgingCommands

    /** The plan an offer form says to copy from, fresh; null for a standalone option or a plan that is gone. */
    static RatePlan planBehind(final OfferForm form) {
        if (form.getRatePlanId() == null || form.getRatePlanId().isBlank()) {
            return null;
        }
        return DAO.getInstance().getRatePlan(form.getAccommodationId(), RatePlan.Id.from(form.getRatePlanId()),
                Cached.NO).orElse(null);
    }

    /** The plan a linked offer follows (cached read, for rows); null for a standalone offer or a gone plan. */
    static RatePlan planOf(final ReservationOffer offer) {
        if (!offer.isLinked()) {
            return null;
        }
        return DAO.getInstance().getRatePlan(LodgingCommands.idValue(offer.getAccommodationId()),
                RatePlan.Id.from(offer.getRatePlanId()), Cached.YES).orElse(null);
    }

    // ================================================================== plumbing

    private static List<RatePlan> sortedPlans(final String accId, final Cached cached) {
        final List<RatePlan> plans = new ArrayList<>(DAO.getInstance().getRatePlans(accId, cached));
        plans.sort(RatePlanCommands::byName);
        return plans;
    }

    private static int byName(final RatePlan a, final RatePlan b) {
        return LodgingCommands.nullSafe(a.getName()).compareToIgnoreCase(LodgingCommands.nullSafe(b.getName()));
    }

    private static RatePlan findPlan(final String accId, final String planId, final Cached cached) {
        if (accId == null || accId.isBlank() || planId == null || planId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getRatePlan(accId.trim(), RatePlan.Id.from(planId.trim()), cached).orElse(null);
    }

    /** The trip's offers that follow this plan, fresh (the fan-out and the un-pricing check read them). */
    private static List<ReservationOffer> offersFollowing(final String tripId, final RatePlan plan) {
        final List<ReservationOffer> mine = new ArrayList<>();
        for (final ReservationOffer offer : DAO.getInstance().getReservationOffers(tripId, Cached.NO)) {
            if (plan.getId().getValue().equals(offer.getRatePlanId())) {
                mine.add(offer);
            }
        }
        return mine;
    }

    private static LocalDate dateOf(final LocalDateTime when) {
        return (when == null) ? null : when.toLocalDate();
    }

    /** The conditional put of a plan; the refusal lands on the form (when there is one) and in a growl. */
    private boolean store(final RatePlan plan, final RatePlanForm form) {
        try {
            return Boolean.TRUE.equals(DAO.getInstance().saveRatePlan(plan))
                    || refuseInto(form, "The rate plan could not be saved.");
        } catch (final ConditionalCheckFailedException ex) {
            return refuseInto(form, "Saved by someone else: reload the page and try again.");
        } catch (final IOException ex) {
            log.error("Unable to save rate plan {}", plan.getId(), ex);
            return refuseInto(form, "The rate plan could not be saved: " + ex.getMessage());
        }
    }

    /** A plan write whose loss is the caller's to report (link bookkeeping): no growl, a log line. */
    private static boolean storeQuietly(final RatePlan plan) {
        try {
            return Boolean.TRUE.equals(DAO.getInstance().saveRatePlan(plan));
        } catch (final ConditionalCheckFailedException ex) {
            log.warn("Rate plan {} was saved by someone else while recording a link", plan.getId());
            return false;
        } catch (final IOException ex) {
            log.error("Unable to save rate plan {}", plan.getId(), ex);
            return false;
        }
    }

    private static boolean saveOfferQuietly(final ReservationOffer offer) {
        try {
            return Boolean.TRUE.equals(DAO.getInstance().saveReservationOffer(offer));
        } catch (final ConditionalCheckFailedException ex) {
            log.warn("Offer {} on trip {} was saved by someone else during a rate plan write", offer.getId(),
                    offer.getTripId());
            return false;
        } catch (final IOException ex) {
            log.error("Unable to save offer {}", offer.getId(), ex);
            return false;
        }
    }

    private static boolean refuseInto(final RatePlanForm form, final String message) {
        if (form != null) {
            form.setProblem(message);
        }
        return refuse(message);
    }

    private static boolean refuseInto(final AttachForm form, final String message) {
        form.setProblem(message);
        return refuse(message);
    }

    private void audit(final RatePlan plan, final String orgId, final String what) {
        auditSource.get().lodging(AuditEventBuilder.TARGET_RATE_PLAN, plan.getId().getValue(), orgId, what,
                caller().auditActor());
    }

    private LodgingCommands lodging() {
        return lodgingSource.get();
    }

    private Caller caller() {
        return callerSource.get();
    }
}
