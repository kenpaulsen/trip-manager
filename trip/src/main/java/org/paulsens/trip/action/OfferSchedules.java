package org.paulsens.trip.action;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.LodgingViews.CostExtraForm;
import org.paulsens.trip.action.LodgingViews.OfferCostForm;
import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.action.RatePlanViews.AttachForm;
import org.paulsens.trip.action.RatePlanViews.RatePlanForm;
import org.paulsens.trip.model.CostExtra;
import org.paulsens.trip.model.PricingModel;
import org.paulsens.trip.model.RatePlan;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.pay.MoneyMath;

/**
 * The money half of a lodging option as the dialogs carry it: the PARTICIPANT PRICE and the HOTEL COST of a
 * {@link ReservationOffer}, each a {@link RateSchedule} or absent, plus the hotel's {@link CostExtra}s. Pure
 * form-to-model and model-to-form code with its validation and labels; {@link LodgingCommands} decides who
 * may call it and what happens to the bills afterwards.
 *
 * <p>Two absences mean two different things, and the forms spell them out as flags rather than blank fields:
 * {@code priceKnown == false} saves a null price, "needs participant pricing" (no stay may be reserved on
 * the option until an internal admin sets one); {@code costKnown == false} saves a null cost, "not set", which
 * the Room Invoice REFUSES to invoice (no cost is ever assumed from a price, owner decision 2026-10-26). The
 * operator's {@link OfferCostForm} has no price fields at all, so the cost path can never touch what a
 * participant pays.
 *
 * <p>A hotel {@link RatePlan} carries the COST side only as a template; {@link #copyFromPlan} is the one place
 * an offer takes it (the live link is write-through: the offer keeps its own COPY and remembers the plan's id
 * and version) and leaves the offer's PRICE alone -- a linked option owns its participant price. The
 * {@code plan*} methods are the same form bridge for the plan dialog.
 */
final class OfferSchedules {
    /** What the Offers tab shows for an unpriced option. */
    static final String NEEDS_PRICING = "needs participant pricing";
    /** What every reader sees for an option whose hotel cost is not stated: never the price. */
    static final String COST_NOT_SET = "not set";
    /** The Offers tab's spelling of the same case (one constant per reader, so a page diff stays readable). */
    static final String COST_UNSET = COST_NOT_SET;

    private OfferSchedules() {
    }

    // ================================================================== form -> offer

    /** Writes both schedules and the extras from the offer dialog; every other field is the caller's. */
    static void apply(final ReservationOffer offer, final OfferForm form) {
        offer.setPrice(form.isPriceKnown() ? schedule(form.getPricingModel(), form.getNightlyPrice(),
                form.isPerNightPricing(), form.getPerNight(), form.getSingleSupplement()) : null);
        offer.setCost(form.isCostKnown() ? schedule(form.getCostPricingModel(), form.getCostNightlyPrice(),
                form.isCostPerNight(), form.getCostPerNightRates(), form.getCostSupplement()) : null);
        offer.setCostExtras(extrasOf(form.getCostExtras()));
    }

    /**
     * Writes the COST side alone (the operator's dialog). The dialog has no per-night table, so the overrides
     * an internal admin may have entered on the cost side are kept rather than silently dropped.
     */
    static void applyCost(final ReservationOffer offer, final OfferCostForm form) {
        final Map<String, Long> kept = (offer.getCost() == null) ? Map.of() : offer.getCost().getOverrides();
        offer.setCost(form.isCostKnown() ? new RateSchedule(modelOf(form.getCostPricingModel()),
                LodgingCommands.cents(form.getCostNightlyPrice()), kept,
                LodgingCommands.cents(form.getCostSupplement())) : null);
        offer.setCostExtras(extrasOf(form.getCostExtras()));
    }

    /**
     * Makes the offer's COST side a copy of the plan's: the cost schedule (or its absence), the extras, and the
     * link ({@code ratePlanId}, {@code ratePlanVersion}). The offer's PRICE and every other field are the trip's
     * and are left alone. Copies, never the plan's own objects: the plan is a DAO row the caller may go on
     * editing.
     */
    static void copyFromPlan(final ReservationOffer offer, final RatePlan plan) {
        offer.setCost(copyOf(plan.getCost()));
        offer.setCostExtras(copyExtras(plan.getCostExtras()));
        offer.setRatePlanId(plan.getId().getValue());
        offer.setRatePlanVersion(plan.getVersion());
    }

    static RateSchedule copyOf(final RateSchedule schedule) {
        return (schedule == null) ? null : new RateSchedule(schedule.getPricingModel(), schedule.getNightlyCents(),
                schedule.getOverrides(), schedule.getSingleSupplementCents());
    }

    static List<CostExtra> copyExtras(final List<CostExtra> extras) {
        final List<CostExtra> copies = new ArrayList<>();
        for (final CostExtra extra : extras) {
            copies.add(new CostExtra(extra.getLabel(), extra.getBasis(), extra.getAmountCents()));
        }
        return copies;
    }

    /** The plan dialog's HOTEL COST block (the plan's only money) onto the plan. */
    static void applyPlanCost(final RatePlan plan, final RatePlanForm form) {
        plan.setCost(form.isCostKnown() ? schedule(form.getCostPricingModel(), form.getCostNightlyPrice(), false,
                null, form.getCostSupplement()) : null);
        plan.setCostExtras(extrasOf(form.getCostExtras()));
    }

    /**
     * The attach dialog's PARTICIPANT PRICE onto the new option's form: the four price fields, no per-night
     * table (an attach has none). {@code priceKnown} off leaves the option "needs participant pricing".
     */
    static void applyAttachPrice(final OfferForm form, final AttachForm attach) {
        form.setPriceKnown(attach.isPriceKnown());
        form.setPricingModel(attach.getPricingModel() == null ? "PER_ROOM" : attach.getPricingModel());
        form.setNightlyPrice(attach.getNightlyPrice());
        form.setPerNightPricing(false);
        form.setSingleSupplement(attach.getSingleSupplement());
    }

    static RateSchedule schedule(final String model, final Double nightly, final boolean perNightOn,
            final Map<String, String> perNight, final Double supplement) {
        final Map<String, Long> overrides = new HashMap<>();
        if (perNightOn && perNight != null) {
            for (final Map.Entry<String, String> entry : perNight.entrySet()) {
                final Double dollars = parseDollars(entry.getValue());
                if (dollars != null) {
                    overrides.put(entry.getKey(), LodgingCommands.cents(dollars));
                }
            }
        }
        return new RateSchedule(modelOf(model), LodgingCommands.cents(nightly), overrides,
                LodgingCommands.cents(supplement));
    }

    static PricingModel modelOf(final String name) {
        return PricingModel.PER_PERSON.name().equals(name) ? PricingModel.PER_PERSON : PricingModel.PER_ROOM;
    }

    /** Rows with neither a label nor an amount are the dialog's blank spares and are dropped. */
    static List<CostExtra> extrasOf(final List<CostExtraForm> forms) {
        final List<CostExtra> extras = new ArrayList<>();
        if (forms == null) {
            return extras;
        }
        for (final CostExtraForm form : forms) {
            if (!isBlankRow(form)) {
                extras.add(new CostExtra(form.getLabel(), basisOf(form.getBasis()), signedCents(form.getAmount())));
            }
        }
        return extras;
    }

    private static boolean isBlankRow(final CostExtraForm form) {
        return (form.getLabel() == null || form.getLabel().isBlank()) && form.getAmount() == null;
    }

    /** A {@link CostExtra.Basis} by name; FIXED for anything else (validation refused the garbage already). */
    static CostExtra.Basis basisOf(final String name) {
        for (final CostExtra.Basis basis : CostExtra.Basis.values()) {
            if (basis.name().equals(name)) {
                return basis;
            }
        }
        return CostExtra.Basis.FIXED;
    }

    private static boolean knownBasis(final String name) {
        return basisOf(name).name().equals(name);
    }

    // ================================================================== offer -> form

    /** Fills the offer dialog's two money fieldsets from the row. */
    static void fill(final OfferForm form, final ReservationOffer offer) {
        final RateSchedule price = offer.getPrice();
        form.setPriceKnown(price != null);
        if (price != null) {
            form.setPricingModel(price.getPricingModel().name());
            form.setNightlyPrice(LodgingCommands.dollars(price.getNightlyCents()));
            form.setPerNightPricing(price.hasOverrides());
            form.setPerNight(perNightText(price));
            form.setSingleSupplement(LodgingCommands.dollars(price.getSingleSupplementCents()));
        }
        final RateSchedule cost = offer.getCost();
        form.setCostKnown(cost != null);
        if (cost != null) {
            form.setCostPricingModel(cost.getPricingModel().name());
            form.setCostNightlyPrice(LodgingCommands.dollars(cost.getNightlyCents()));
            form.setCostPerNight(cost.hasOverrides());
            form.setCostPerNightRates(perNightText(cost));
            form.setCostSupplement(LodgingCommands.dollars(cost.getSingleSupplementCents()));
        }
        form.setCostExtras(extraForms(offer.getCostExtras()));
    }

    /** Fills the plan dialog's cost block from the plan. */
    static void fillPlanForm(final RatePlanForm form, final RatePlan plan) {
        final RateSchedule cost = plan.getCost();
        form.setCostKnown(cost != null);
        if (cost != null) {
            form.setCostPricingModel(cost.getPricingModel().name());
            form.setCostNightlyPrice(LodgingCommands.dollars(cost.getNightlyCents()));
            form.setCostSupplement(LodgingCommands.dollars(cost.getSingleSupplementCents()));
        }
        form.setCostExtras(extraForms(plan.getCostExtras()));
    }

    /** The operator's cost dialog for one option. */
    static OfferCostForm costFormFor(final String tripId, final ReservationOffer offer) {
        final OfferCostForm form = new OfferCostForm();
        form.setTripId(tripId);
        form.setOfferId(offer.getId().getValue());
        form.setOfferName(offer.getName());
        final RateSchedule cost = offer.getCost();
        form.setCostKnown(cost != null);
        if (cost != null) {
            form.setCostPricingModel(cost.getPricingModel().name());
            form.setCostNightlyPrice(LodgingCommands.dollars(cost.getNightlyCents()));
            form.setCostSupplement(LodgingCommands.dollars(cost.getSingleSupplementCents()));
        }
        form.setCostExtras(extraForms(offer.getCostExtras()));
        return form;
    }

    /** ISO date -> "150.00": the per-night table's text cells. */
    private static Map<String, String> perNightText(final RateSchedule schedule) {
        final Map<String, String> text = new LinkedHashMap<>();
        for (final Map.Entry<String, Long> entry : schedule.getOverrides().entrySet()) {
            text.put(entry.getKey(), MoneyMath.formatCents(entry.getValue()).replace("$", ""));
        }
        return text;
    }

    static List<CostExtraForm> extraForms(final List<CostExtra> extras) {
        final List<CostExtraForm> forms = new ArrayList<>();
        for (final CostExtra extra : extras) {
            forms.add(new CostExtraForm(extra.getLabel(), extra.getBasis().name(), extra.getAmountCents() / 100.0));
        }
        return forms;
    }

    // ================================================================== validation

    /** The money refusals of the offer dialog; null when every number is a number and none is negative. */
    static String problem(final OfferForm form) {
        if (form.isPriceKnown()) {
            final String price = scheduleProblem("price", form.getNightlyPrice(), form.getSingleSupplement(),
                    form.isPerNightPricing(), form.getPerNight());
            if (price != null) {
                return price;
            }
        }
        if (form.isCostKnown()) {
            final String cost = scheduleProblem("cost", form.getCostNightlyPrice(), form.getCostSupplement(),
                    form.isCostPerNight(), form.getCostPerNightRates());
            if (cost != null) {
                return cost;
            }
        }
        return extrasProblem(form.getCostExtras());
    }

    /** The operator dialog's refusals; null when fine. */
    static String costProblem(final OfferCostForm form) {
        if (form.isCostKnown()) {
            final String cost = scheduleProblem("cost", form.getCostNightlyPrice(), form.getCostSupplement(), false,
                    Map.of());
            if (cost != null) {
                return cost;
            }
        }
        return extrasProblem(form.getCostExtras());
    }

    /** The attach dialog's price refusals; null when fine (or when no price is being set). */
    static String attachProblem(final AttachForm form) {
        return form.isPriceKnown() ? scheduleProblem("price", form.getNightlyPrice(), form.getSingleSupplement(),
                false, Map.of()) : null;
    }

    /** The plan dialog's money refusals: the cost block and the extras; null when fine. */
    static String planProblem(final RatePlanForm form) {
        if (form.isCostKnown()) {
            final String cost = scheduleProblem("cost", form.getCostNightlyPrice(), form.getCostSupplement(), false,
                    Map.of());
            if (cost != null) {
                return cost;
            }
        }
        return extrasProblem(form.getCostExtras());
    }

    private static String scheduleProblem(final String side, final Double nightly, final Double supplement,
            final boolean perNightOn, final Map<String, String> perNight) {
        if (nightly != null && nightly < 0) {
            return "The nightly " + side + " cannot be negative.";
        }
        if (supplement != null && supplement < 0) {
            return "price".equals(side) ? "The single supplement cannot be negative."
                    : "The cost single supplement cannot be negative.";
        }
        if (!perNightOn || perNight == null) {
            return null;
        }
        for (final Map.Entry<String, String> entry : perNight.entrySet()) {
            final Double dollars = parseDollars(entry.getValue());
            if (entry.getValue() != null && !entry.getValue().isBlank() && dollars == null) {
                return "'" + entry.getValue() + "' is not a " + side + " (for " + entry.getKey() + ").";
            }
            if (dollars != null && dollars < 0) {
                return "The " + side + " for " + entry.getKey() + " cannot be negative.";
            }
        }
        return null;
    }

    private static String extrasProblem(final List<CostExtraForm> forms) {
        if (forms == null) {
            return null;
        }
        for (final CostExtraForm form : forms) {
            if (isBlankRow(form)) {
                continue;
            }
            if (form.getLabel() == null || form.getLabel().isBlank()) {
                return "Each cost extra needs a label.";
            }
            if (form.getAmount() == null) {
                return "'" + form.getLabel() + "' needs an amount.";
            }
            if (form.getBasis() != null && !form.getBasis().isBlank() && !knownBasis(form.getBasis())) {
                return "'" + form.getLabel() + "' has an unknown basis.";
            }
        }
        return null;
    }

    // ================================================================== labels

    /** "$120.00/night per room", "varies by night per person, +$20.00 single", or {@link #NEEDS_PRICING}. */
    static String pricingLabel(final ReservationOffer offer) {
        return offer.isPriced() ? scheduleLabel(offer.getPrice()) : NEEDS_PRICING;
    }

    /** The Offers tab's cost column: the stated cost (extras counted), else {@link #COST_UNSET}. */
    static String costLabel(final ReservationOffer offer) {
        return (offer.getCost() == null) ? COST_UNSET : scheduleLabel(offer.getCost()) + extrasSuffix(offer);
    }

    /** The cost as a Hotel Operator reads it: the stated cost, else {@link #COST_NOT_SET}; never the price. */
    static String operatorCostLabel(final ReservationOffer offer) {
        return (offer.getCost() == null) ? COST_NOT_SET : scheduleLabel(offer.getCost()) + extrasSuffix(offer);
    }

    static String scheduleLabel(final RateSchedule schedule) {
        final String rate = schedule.hasOverrides() ? "varies by night"
                : MoneyMath.formatCents(schedule.getNightlyCents()) + "/night";
        final String model = schedule.isPerPerson() ? " per person" : " per room";
        final String supplement = (schedule.isPerPerson() && schedule.getSingleSupplementCents() > 0)
                ? ", +" + MoneyMath.formatCents(schedule.getSingleSupplementCents()) + " single" : "";
        return rate + model + supplement;
    }

    private static String extrasSuffix(final ReservationOffer offer) {
        return extrasSuffix(offer.getCostExtras().size());
    }

    static String extrasSuffix(final int count) {
        return (count == 0) ? "" : ", +" + count + (count == 1 ? " extra" : " extras");
    }

    /** The plan's cost as any reader of the plan table sees it: the stated cost, else {@link #COST_NOT_SET}. */
    static String planCostLabel(final RatePlan plan) {
        return (plan.getCost() == null) ? COST_NOT_SET
                : scheduleLabel(plan.getCost()) + extrasSuffix(plan.getCostExtras().size());
    }

    // ================================================================== the link, as the Offers tab reads it

    /** What the Offers tab says about an option's rate plan: nothing for a standalone option. */
    record PlanLink(String planName, boolean drift) {
        static final PlanLink NONE = new PlanLink(null, false);
    }

    /**
     * The plan behind a linked offer, by name, and whether the offer's copy LAGS it (a fan-out write was lost,
     * or the plan was saved while this trip's row was busy): {@code ratePlanVersion < plan.version}. A linked
     * offer whose plan is gone reads as drifted too -- there is nothing left to follow.
     */
    static PlanLink linkOf(final ReservationOffer offer, final RatePlan plan) {
        if (!offer.isLinked()) {
            return PlanLink.NONE;
        }
        if (plan == null) {
            return new PlanLink("(deleted plan)", true);
        }
        return new PlanLink(plan.getName(), offer.getRatePlanVersion() < plan.getVersion());
    }

    // ================================================================== money text

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

    /** Dollars to cents keeping the sign (an extra may be a coupon); 0 for null. */
    static long signedCents(final Double dollars) {
        return (dollars == null) ? 0L
                : BigDecimal.valueOf(dollars).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
