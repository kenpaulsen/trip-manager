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
import org.paulsens.trip.model.Reservation;
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
 * <p>A {@link RatePlan} is the HOTEL's pricing template (its cost and extras, and -- when an internal admin has
 * set it -- the participant price) for some room types over a date range. A trip takes it by {@link #attach}:
 * a {@link ReservationOffer} is created whose schedules are a COPY of the plan's and which remembers the plan's
 * id and version, and the plan records the trip in {@code linkedTripIds}. The link is LIVE in one direction:
 * {@link #savePlan} fans the new numbers out to every linked offer ({@link #syncLinked}), recomputing bills
 * only where the PRICE side moved; {@link #detach} leaves the offer with its copy and forgets the trip.
 * Write-through, never a read-time join: {@code LodgingPricing}, the reports, the PDF and REST never learn that
 * plans exist.
 *
 * <p>Two gates, two sides. The COST side is the hotel's: {@link #canEditCost} = anyone who may edit the hotel
 * (its Hotel Operators, global lodging admins). The PRICE side is internal: {@link #canEditPrice} = a global
 * lodging admin or a lodging admin of ANY organization, and the price fields of anyone else's form are
 * IGNORED on save, so an operator's dialog -- whatever it posts -- cannot move what a participant pays.
 * Reading the table ({@link #rows}) takes whoever may open the hotel page; the price column is null for a
 * reader who may not edit it.
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

    /**
     * May set the PARTICIPANT PRICE on a plan: internal only -- a global lodging admin or a lodging admin of
     * any organization. A Hotel Operator never is, whichever hotel the plan belongs to.
     */
    public boolean canEditPrice(final String accId) {
        final LodgingCommands lodging = lodging();
        return lodging.isGlobalLodgingAdmin() || lodging.canCreateAccommodation();
    }

    /** May read the plan table: whoever may open the hotel's page (its editors and the lodging admins). */
    boolean canRead(final String accId) {
        final LodgingCommands lodging = lodging();
        return lodging.canEditAccommodation(accId) || lodging.canOpenLodgingAdmin();
    }

    // ================================================================== the table

    /** The hotel's plans by name, as rows; the price column only for a caller who may edit prices. */
    public List<RatePlanRow> rows(final String accId) {
        final List<RatePlanRow> rows = new ArrayList<>();
        final Accommodation acc = lodging().findAccommodation(accId);
        if (acc == null || !canRead(accId)) {
            return rows;
        }
        final boolean prices = canEditPrice(accId);
        for (final RatePlan plan : sortedPlans(accId, Cached.YES)) {
            rows.add(rowOf(acc, plan, prices));
        }
        return rows;
    }

    private RatePlanRow rowOf(final Accommodation acc, final RatePlan plan, final boolean prices) {
        return new RatePlanRow(plan.getId().getValue(), plan.getName(), roomTypeNames(acc, plan.getRoomTypeIds()),
                datesLabel(plan), OfferSchedules.planCostLabel(plan),
                prices ? OfferSchedules.planPriceLabel(plan) : null, !plan.isPriced(),
                linkedTripsLabel(acc, plan), plan.getLinkedTripIds().size(), plan.isRetired(), plan.getNotes());
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
     * Creates or edits a plan, then pushes it to every linked offer. The cost side needs {@link #canEditCost};
     * the price side is applied only for a caller who {@link #canEditPrice} (anyone else's price fields are
     * ignored, and the plan keeps the price it had). Un-pricing a plan whose linked options have ACTIVE stays
     * is refused: those stays would price to nothing. Refusals land on {@code form.problem} and in a growl; a
     * fan-out that could not reach every trip is reported there too, but the plan itself is saved.
     */
    public boolean savePlan(final RatePlanForm form) {
        if (form == null) {
            return refuse("Nothing to save.");
        }
        final String accId = form.getAccId();
        // Two sides, two editors: the hotel's operators own the hotel side (name, room types, dates, cost,
        // notes, retirement); an internal lodging admin owns the participant price at ANY hotel, which is why a
        // price-only caller may save a plan they cannot otherwise touch -- with the hotel side re-read from the row.
        final boolean costCounts = canEditCost(accId);
        final boolean priceCounts = canEditPrice(accId);
        if (!costCounts && !priceCounts) {
            return refuseInto(form, "Not allowed: only this hotel's operators and lodging admins edit its rate plans.");
        }
        final Accommodation acc = lodging().findAccommodation(accId);
        if (acc == null || acc.isRetired()) {
            return refuseInto(form, "That accommodation is retired or gone.");
        }
        final boolean editing = form.getPlanId() != null && !form.getPlanId().isBlank();
        final RatePlan plan = editing ? findPlan(accId, form.getPlanId(), Cached.NO)
                : costCounts ? RatePlan.builder().accommodationId(acc.getId()).createdBy(caller().personId())
                        .created(LocalDateTime.now()).build() : null;
        if (plan == null) {
            return refuseInto(form, editing ? "This rate plan no longer exists."
                    : "Only the hotel's operators create its rate plans; a lodging admin prices an existing one.");
        }
        if (!costCounts) {
            keepHotelSide(form, plan);
        }
        final String problem = problem(acc, form, priceCounts);
        if (problem != null) {
            return refuseInto(form, problem);
        }
        if (priceCounts && plan.isPriced() && !form.isPriceKnown()) {
            final int stays = activeStaysFollowing(plan);
            if (stays > 0) {
                return refuseInto(form, "'" + plan.getName() + "' prices " + stays
                        + (stays == 1 ? " active stay" : " active stays") + " on linked options; it cannot lose its"
                        + " participant price.");
            }
        }
        final RateSchedule costBefore = plan.getCost();
        final List<CostExtra> extrasBefore = new ArrayList<>(plan.getCostExtras());
        final RateSchedule priceBefore = plan.getPrice();
        apply(plan, form, priceCounts);
        if (!store(plan, form)) {
            return false;
        }
        form.setPlanId(plan.getId().getValue());
        form.setProblem(null);
        final boolean costChanged = !Objects.equals(costBefore, plan.getCost())
                || !extrasBefore.equals(plan.getCostExtras());
        final boolean priceChanged = !Objects.equals(priceBefore, plan.getPrice());
        audit(plan, null, (editing ? "Saved rate plan '" : "Created rate plan '") + plan.getName() + "' at '"
                + acc.getName() + "'" + sides(costChanged, priceChanged));
        return report(plan, form, syncLinked(plan));
    }

    private static String sides(final boolean cost, final boolean price) {
        if (cost && price) {
            return " (cost and price changed)";
        }
        if (cost) {
            return " (cost changed)";
        }
        return price ? " (price changed)" : "";
    }

    /** The plan dialog's refusals; null when the plan can be saved as the form says. */
    private String problem(final Accommodation acc, final RatePlanForm form, final boolean priceCounts) {
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
        return OfferSchedules.planProblem(form, priceCounts);
    }

    /**
     * A price-only editor may not move the hotel's side, whatever the browser sent: every hotel field of the form
     * is re-read from the row and only the four participant-price fields survive from the submission.
     */
    private static void keepHotelSide(final RatePlanForm form, final RatePlan plan) {
        final boolean priceKnown = form.isPriceKnown();
        final String pricingModel = form.getPricingModel();
        final Double nightlyPrice = form.getNightlyPrice();
        final Double singleSupplement = form.getSingleSupplement();
        form.setName(plan.getName());
        form.setRoomTypeIds(new ArrayList<>(plan.getRoomTypeIds()));
        form.setValidFrom(plan.getValidFrom());
        form.setValidUntil(plan.getValidUntil());
        OfferSchedules.fillPlanForm(form, plan);
        form.setNotes(plan.getNotes());
        form.setRetired(plan.isRetired());
        form.setPriceKnown(priceKnown);
        form.setPricingModel(pricingModel);
        form.setNightlyPrice(nightlyPrice);
        form.setSingleSupplement(singleSupplement);
    }

    private static void apply(final RatePlan plan, final RatePlanForm form, final boolean priceCounts) {
        plan.setName(form.getName().trim());
        plan.setRoomTypeIds(new ArrayList<>(form.getRoomTypeIds()));
        plan.setValidFrom(form.getValidFrom());
        plan.setValidUntil(form.getValidUntil());
        OfferSchedules.applyPlanCost(plan, form);
        if (priceCounts) {
            OfferSchedules.applyPlanPrice(plan, form);
        }
        plan.setNotes(LodgingCommands.blankToNull(form.getNotes()));
        plan.setRetired(form.isRetired() ? Boolean.TRUE : null);
    }

    /** How many ACTIVE stays sit on offers that follow this plan, over every linked trip. */
    private int activeStaysFollowing(final RatePlan plan) {
        int stays = 0;
        for (final String tripId : plan.getLinkedTripIds()) {
            final Set<ReservationOffer.Id> following = new LinkedHashSet<>();
            for (final ReservationOffer offer : offersFollowing(tripId, plan)) {
                following.add(offer.getId());
            }
            for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.NO)) {
                if (res.isActive() && following.contains(res.getOfferId())) {
                    stays++;
                }
            }
        }
        return stays;
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
     * Pushes the plan to every linked trip: each offer following the plan re-copies it and is saved; its bills
     * recompute only when the PRICE side moved. A trip with no offer following the plan any more (or no trip
     * row) is PRUNED first and the plan saved once, so the offers then copy the plan's final version and read
     * as current. A lost conditional put on one offer is reported, not retried: that offer keeps its old
     * {@code ratePlanVersion}, which the Offers tab shows as drift for the admin to re-save or detach.
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

    /** Re-copies the plan onto the trip's following offers; true when every one of them saved. */
    private boolean pushTo(final Trip trip, final RatePlan plan, final List<String> failures) {
        boolean all = true;
        int recomputed = 0;
        final List<ReservationOffer> offers = offersFollowing(trip.getId(), plan);
        for (final ReservationOffer offer : offers) {
            final RateSchedule priceBefore = offer.getPrice();
            OfferSchedules.copyFromPlan(offer, plan);
            if (!saveOfferQuietly(offer)) {
                failures.add("trip '" + trip.getTitle() + "': saved by someone else");
                all = false;
                continue;
            }
            if (!Objects.equals(priceBefore, offer.getPrice())) {
                lodging().recomputeAfterOfferEdit(trip, offer);
                recomputed++;
            }
        }
        audit(plan, trip.getOrgId(), "Rate plan '" + plan.getName() + "' pushed to " + offers.size()
                + (offers.size() == 1 ? " lodging option" : " lodging options") + " on '" + trip.getTitle() + "'"
                + (recomputed > 0 ? " (price changed: bills recomputed)" : "") + (all ? "" : " (not all saved)"));
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
     * trip's dates and is not already on the trip, by hotel then plan. Empty for a caller who may not manage
     * the trip's lodging, and for a trip without dates (nothing can be said to cover them).
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
        final Set<String> onTrip = planIdsOn(tripId, Cached.YES);
        for (final Accommodation acc : lodging.getAccommodations()) {
            for (final RatePlan plan : sortedPlans(acc.getId().getValue(), Cached.YES)) {
                if (!plan.isRetired() && plan.covers(from, to) && !onTrip.contains(plan.getId().getValue())) {
                    choices.add(new PlanChoice(plan.getId().getValue(), acc.getId().getValue(),
                            acc.getName() + ": " + plan.getName() + " (" + datesLabel(plan) + ")"));
                }
            }
        }
        return choices;
    }

    /** {@link #attach(String, String, String)} from a menu value ({@link PlanChoice#getValue()}). */
    public boolean attach(final String tripId, final String choice) {
        final int at = (choice == null) ? -1 : choice.indexOf(PlanChoice.SEPARATOR);
        if (at <= 0 || at == choice.length() - 1) {
            return refuse("Choose a rate plan.");
        }
        return attach(tripId, choice.substring(0, at), choice.substring(at + 1));
    }

    /**
     * Puts a hotel's plan on the trip as a new lodging option: name, room types and dates from the plan, the
     * default stay from the trip, a NEW lodging event, and the schedules COPIED from the plan
     * ({@code OfferForm.ratePlanId} tells {@code saveOffer} to copy rather than read the form's money). The
     * trip joins the plan's {@code linkedTripIds} FIRST: that write bumps the plan's version, and the offer
     * must copy the version it will then be compared against, or it would read as drifted from birth. A
     * refused offer save unlinks again. Gate: {@code canManageTripLodging}.
     */
    public boolean attach(final String tripId, final String accId, final String planId) {
        final LodgingCommands lodging = lodging();
        if (!lodging.canManageTripLodging(tripId)) {
            return refuse("Not allowed: only the trip's managers and lodging admins add lodging options.");
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        if (!tripId.equals(trip.getId())) {
            return refuse("Unknown trip.");
        }
        final Accommodation acc = lodging.findAccommodation(accId);
        final RatePlan plan = findPlan(accId, planId, Cached.NO);
        if (acc == null || acc.isRetired() || plan == null) {
            return refuse("That rate plan no longer exists.");
        }
        if (plan.isRetired()) {
            return refuse("'" + plan.getName() + "' is retired.");
        }
        if (!plan.covers(dateOf(trip.getStartDate()), dateOf(trip.getEndDate()))) {
            return refuse("'" + plan.getName() + "' (" + datesLabel(plan) + ") does not cover the trip's dates.");
        }
        if (planIdsOn(tripId, Cached.NO).contains(plan.getId().getValue())) {
            return refuse("'" + plan.getName() + "' is already on this trip.");
        }
        final OfferForm form = lodging.offerFormFor(tripId, null);
        form.setName(plan.getName());
        form.setAccommodationId(accId);
        form.setRoomTypeIds(new ArrayList<>(plan.getRoomTypeIds()));
        form.setTripEventId(OfferForm.NEW_EVENT);
        form.setRatePlanId(plan.getId().getValue());
        if (plan.getValidFrom() != null) {
            form.setValidFrom(plan.getValidFrom().atStartOfDay());
        }
        if (plan.getValidUntil() != null) {
            form.setValidUntil(plan.getValidUntil().atTime(23, 59));
        }
        plan.link(tripId);
        if (!storeQuietly(plan)) {
            return refuse("Saved by someone else: reload the page and try again.");
        }
        if (!lodging.saveOffer(tripId, form)) {
            plan.unlink(tripId);
            storeQuietly(plan);
            return false;
        }
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

    private static Set<String> planIdsOn(final String tripId, final Cached cached) {
        final Set<String> ids = new LinkedHashSet<>();
        for (final ReservationOffer offer : DAO.getInstance().getReservationOffers(tripId, cached)) {
            if (offer.isLinked()) {
                ids.add(offer.getRatePlanId());
            }
        }
        return ids;
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
