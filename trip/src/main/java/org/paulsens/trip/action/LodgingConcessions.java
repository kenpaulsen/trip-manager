package org.paulsens.trip.action;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.action.LodgingViews.ConcessionForm;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Concession;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.pay.LodgingPricing;
import org.paulsens.trip.pay.MoneyMath;

import static org.paulsens.trip.action.PageFeedback.refuse;

/**
 * Per-stay concessions, one side at a time: the PRICE side forgives the participants part of their lodging
 * bill (the Bill rows shrink in place, "complimentary" removes them), the COST side records what the hotel
 * forgave us (the Room Invoice shrinks, no bill moves). The gates differ by side -- the price is the trip's
 * business ({@code canManageTripLodging}), the cost is also the hotel operator's ({@code canSetCost}) -- and
 * the write funnels through {@link LodgingCommands#persistReservation}, so the audit trail and the automatic
 * bill recompute are exactly the reservation dialog's. Pages reach it through {@code #{lodging.*}} one-liners.
 */
final class LodgingConcessions {
    private static final String PRICE = "PRICE";
    private static final String COST = "COST";

    private final LodgingCommands lodging;
    private final TripCommands trips;
    private final AuditCommands audit;

    LodgingConcessions(final LodgingCommands lodging, final TripCommands trips, final AuditCommands audit) {
        this.lodging = lodging;
        this.trips = trips;
        this.audit = audit;
    }

    /** The dialog for one stay's side; a form with only its {@code problem} set when the caller may not. */
    ConcessionForm formFor(final String tripId, final String reservationId, final String side) {
        final ConcessionForm form = new ConcessionForm();
        form.setTripId(tripId);
        form.setReservationId(reservationId);
        form.setSide(sideOf(side));
        final Reservation res = lodging.findReservation(tripId, reservationId);
        if (res == null) {
            form.setProblem("This reservation no longer exists.");
            return form;
        }
        if (!maySet(form, res)) {
            form.setProblem(notAllowed(form));
            return form;
        }
        form.setOccupants(lodging.names(res.getOccupants()));
        final Concession existing = form.isPriceSide() ? res.getPriceConcession() : res.getCostConcession();
        form.setCurrent(existing == null ? "" : existing.label());
        if (existing != null) {
            form.setKind(existing.getKind().name());
            form.setAmount(amountOf(existing));
            form.setReason(existing.getReason());
        }
        form.setPreview(preview(form));
        return form;
    }

    /** Sets or clears ({@code kind == ""}) the side's concession; refusals land on the form AND in a growl. */
    boolean set(final ConcessionForm form) {
        if (form == null) {
            return refuse("Nothing to save.");
        }
        form.setSide(sideOf(form.getSide()));
        final Reservation res = lodging.findReservation(form.getTripId(), form.getReservationId());
        if (res == null || !res.isActive()) {
            return refuseInto(form, "This reservation no longer exists or is cancelled.");
        }
        if (!maySet(form, res)) {
            return refuseInto(form, notAllowed(form));
        }
        final String problem = problem(form);
        if (problem != null) {
            return refuseInto(form, problem);
        }
        final Concession concession = build(form);
        if (form.isPriceSide()) {
            res.setPriceConcession(concession);
        } else {
            res.setCostConcession(concession);
        }
        final Trip trip = trips.getTripForEdit(form.getTripId());
        final ReservationOffer offer = lodging.findOffer(form.getTripId(), LodgingCommands.idValue(res.getOfferId()));
        final Accommodation acc = lodging.findAccommodation(LodgingCommands.idValue(res.getAccommodationId()));
        if (!lodging.persistReservation(trip, offer, acc, res, res.getRoomId())) {
            return refuseInto(form, "The concession could not be saved.");
        }
        final String what = (concession == null) ? "CONCESSION cleared (" + form.getSide() + ") for "
                : "CONCESSION set (" + form.getSide() + "): " + concession.label() + " for ";
        final String why = (concession == null || concession.getReason() == null || concession.getReason().isBlank())
                ? "" : " -- " + concession.getReason();
        audit.lodging(AuditEventBuilder.TARGET_RESERVATION, res.getId().getValue(), trip.getOrgId(),
                what + lodging.names(res.getOccupants()) + why, lodging.caller().auditActor());
        form.setProblem(null);
        form.setCurrent(concession == null ? "" : concession.label());
        form.setPreview(preview(form));
        return true;
    }

    /** What the COST preview says when the option has no stated cost (nothing to discount from). */
    static final String NO_COST = "Hotel cost is not set for this option.";

    /**
     * What the form's concession would do, on its side: the stay's new PRICE total as "$300.00", or the hotel's
     * charge for the stay before and after as "Hotel cost for this stay: $240.00 -> $216.00" ({@link #NO_COST}
     * when the option has no cost). "" for an unknown stay or a form that would be refused (whose reason lands
     * on {@code problem}). Priced on a COPY: the DAO hands out copies, so setting the proposed concession on it
     * changes nothing stored.
     */
    String preview(final ConcessionForm form) {
        if (form == null) {
            return "";
        }
        final Reservation copy = lodging.findReservation(form.getTripId(), form.getReservationId());
        final ReservationOffer offer = (copy == null) ? null
                : lodging.findOffer(form.getTripId(), LodgingCommands.idValue(copy.getOfferId()));
        if (copy == null || offer == null) {
            return "";
        }
        final String problem = problem(form);
        if (problem != null) {
            form.setProblem(problem);
            return "";
        }
        final Accommodation acc = lodging.findAccommodation(LodgingCommands.idValue(copy.getAccommodationId()));
        final List<Reservation> onRoom = lodging.activeOnRoom(form.getTripId(), copy.getRoomId());
        if (form.isPriceSide()) {
            copy.setPriceConcession(build(form));
            return MoneyMath.formatCents(total(LodgingPricing.price(copy, offer, acc, onRoom, lodging.nameResolver())));
        }
        if (offer.getCost() == null) {
            return NO_COST;
        }
        final long before = total(LodgingPricing.cost(copy, offer, acc, onRoom, lodging.nameResolver()));
        copy.setCostConcession(build(form));
        final long after = total(LodgingPricing.cost(copy, offer, acc, onRoom, lodging.nameResolver()));
        return "Hotel cost for this stay: " + MoneyMath.formatCents(before) + " -> " + MoneyMath.formatCents(after);
    }

    private static long total(final List<LodgingPricing.Line> lines) {
        long total = 0L;
        for (final LodgingPricing.Line line : lines) {
            total += line.amountCents();
        }
        return total;
    }

    /** The concession the form describes, stamped with who and when; null for "none". */
    Concession build(final ConcessionForm form) {
        if (form.getKind() == null || form.getKind().isBlank()) {
            return null;
        }
        final Concession.Kind kind = Concession.Kind.valueOf(form.getKind().trim());
        final int bps = (kind == Concession.Kind.PERCENT) ? toBps(form.getAmount()) : 0;
        final long cents = (kind == Concession.Kind.PER_NIGHT || kind == Concession.Kind.TOTAL)
                ? LodgingCommands.cents(form.getAmount()) : 0L;
        return new Concession(kind, bps, cents, form.getReason(), lodging.caller().personId(), LocalDateTime.now());
    }

    /** Why the form cannot be saved; null when it can. */
    static String problem(final ConcessionForm form) {
        if (form.getKind() == null || form.getKind().isBlank()) {
            return null;
        }
        final Concession.Kind kind = kindOf(form.getKind());
        if (kind == null) {
            return "Choose a concession kind.";
        }
        final Double amount = form.getAmount();
        if (kind == Concession.Kind.PERCENT && (amount == null || amount < 0 || amount > 100)) {
            return "A percentage must be between 0 and 100.";
        }
        if ((kind == Concession.Kind.PER_NIGHT || kind == Concession.Kind.TOTAL) && amount == null) {
            return "A " + (kind == Concession.Kind.PER_NIGHT ? "per-night" : "total") + " discount needs an amount.";
        }
        if (amount != null && amount < 0) {
            return "The amount cannot be negative.";
        }
        return null;
    }

    /** The side's concession as the tables show it; null when none. */
    static String label(final Concession concession) {
        return (concession == null) ? null : concession.label();
    }

    private boolean maySet(final ConcessionForm form, final Reservation res) {
        return form.isPriceSide() ? lodging.canManageTripLodging(form.getTripId())
                : lodging.canSetCost(form.getTripId(), LodgingCommands.idValue(res.getAccommodationId()));
    }

    private static String notAllowed(final ConcessionForm form) {
        return form.isPriceSide()
                ? "Not allowed: only the trip's managers and lodging admins grant a price concession."
                : "Not allowed: only the trip's lodging admins and the hotel's operators record a cost concession.";
    }

    /** The dialog shows the form's problem; a growl too would render twice under the dialog's p:messages. */
    private static boolean refuseInto(final ConcessionForm form, final String message) {
        form.setProblem(message);
        return false;
    }

    private static String sideOf(final String side) {
        return COST.equalsIgnoreCase(side) ? COST : PRICE;
    }

    private static Concession.Kind kindOf(final String name) {
        for (final Concession.Kind kind : Concession.Kind.values()) {
            if (kind.name().equals(name.trim())) {
                return kind;
            }
        }
        return null;
    }

    /** The form's number for an existing concession: the percent for PERCENT, dollars otherwise, none for free. */
    private static Double amountOf(final Concession concession) {
        switch (concession.getKind()) {
            case PERCENT:
                return concession.getPercentBps() / 100.0;
            case PER_NIGHT:
            case TOTAL:
                return concession.getAmountCents() / 100.0;
            case COMPLIMENTARY:
            default:
                return null;
        }
    }

    /** 12.5 (percent) -> 1250 basis points, half-up. */
    private static int toBps(final Double percent) {
        return (percent == null) ? 0
                : BigDecimal.valueOf(percent).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
