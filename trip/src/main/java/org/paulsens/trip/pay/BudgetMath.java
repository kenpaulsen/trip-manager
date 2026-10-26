package org.paulsens.trip.pay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.paulsens.trip.model.BudgetCategory;
import org.paulsens.trip.model.BudgetLine;
import org.paulsens.trip.model.BudgetLodgingLine;
import org.paulsens.trip.model.CostExtra;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.TripBudget;

/**
 * The arithmetic of a {@link TripBudget}, pure and deterministic like {@link LodgingPricing}: long cents
 * throughout, every division HALF_UP to the cent. The formulas and the two oracles they reproduce (the
 * Google Sheet the budget replaced, and one lodging line on the seeded rate plan's shape) are in
 * {@code docs/budget.md}.
 *
 * <p>A lodging line reads its option's schedules LIVE and uses the flat {@code nightlyCents} only: per-date
 * overrides are ignored, because a budget plans a stay in aggregate and has no nights to look them up by.
 */
public final class BudgetMath {
    private BudgetMath() {
    }

    /**
     * One lodging line's figures. {@code singleAddOn} is meaningful only when {@code singleAddOnApplies} (a
     * PER_PERSON price): under PER_ROOM a single occupant pays the whole room, so there is no add-on.
     */
    public record LodgingFigures(long revenue, long hotelCost, long margin, long perPersonPrice, long singleAddOn,
            boolean singleAddOnApplies, boolean priceMissing, boolean costMissing) {
        static final LodgingFigures NONE = new LodgingFigures(0L, 0L, 0L, 0L, 0L, false, true, true);
    }

    /** One payee's total: what is handed to them, from how many lines. {@code name} is the first spelling seen. */
    public record PayeeTotal(String name, int items, long cents) {
    }

    /** One line that is NOT paid on site, with what it costs the trip; {@code payee} may be null. */
    public record OtherItem(String name, String payee, long cents) {
    }

    /** How deep a chain of percent lines may go before it is treated as broken (a cycle, in practice). */
    static final int MAX_PERCENT_DEPTH = 20;

    // ================================================================== one lodging line

    /** The line's figures on its option; a null (deleted) option is all zeros with both "missing" flags set. */
    public static LodgingFigures lodging(final BudgetLodgingLine line, final ReservationOffer offer) {
        if (line == null || offer == null) {
            return LodgingFigures.NONE;
        }
        final RateSchedule price = offer.getPrice();
        final long revenue = scheduleTotal(price, line);
        long hotelCost = scheduleTotal(offer.getCost(), line);
        if (offer.getCost() != null) {
            final int guestNights = line.getPeople() * line.getNights();
            final int roomNights = line.getRooms() * line.getNights();
            for (final CostExtra extra : offer.getCostExtras()) {
                hotelCost += CostExtras.amountCents(extra, guestNights, roomNights, line.getRooms());
            }
        }
        final boolean perPerson = price != null && price.isPerPerson();
        final long perPersonPrice;
        if (price == null) {
            perPersonPrice = 0L;
        } else if (perPerson) {
            perPersonPrice = line.getNights() * price.getNightlyCents();
        } else {
            perPersonPrice = divide(revenue, line.getPeople());
        }
        final long singleAddOn = perPerson ? line.getNights() * price.getSingleSupplementCents() : 0L;
        return new LodgingFigures(revenue, hotelCost, revenue - hotelCost, perPersonPrice, singleAddOn, perPerson,
                price == null, offer.getCost() == null);
    }

    /** What one schedule charges for the line: per person (plus single supplements) or per room; 0 when null. */
    static long scheduleTotal(final RateSchedule schedule, final BudgetLodgingLine line) {
        if (schedule == null) {
            return 0L;
        }
        if (schedule.isPerPerson()) {
            return (long) line.getPeople() * line.getNights() * schedule.getNightlyCents()
                    + (long) line.getSingles() * line.getNights() * schedule.getSingleSupplementCents();
        }
        return (long) line.getRooms() * line.getNights() * schedule.getNightlyCents();
    }

    /** Whether planning on this option needs a room count: either side per room, or a per-room/per-stay extra. */
    public static boolean needsRooms(final ReservationOffer offer) {
        if (offer == null) {
            return false;
        }
        if (perRoom(offer.getPrice()) || perRoom(offer.getCost())) {
            return true;
        }
        if (offer.getCost() == null) {
            return false;
        }
        for (final CostExtra extra : offer.getCostExtras()) {
            if (extra.getBasis() == CostExtra.Basis.PER_ROOM_NIGHT || extra.getBasis() == CostExtra.Basis.PER_STAY) {
                return true;
            }
        }
        return false;
    }

    /** Whether planning on this option needs a single-room count: a per-person side with a single supplement. */
    public static boolean needsSingles(final ReservationOffer offer) {
        return offer != null && (hasSupplement(offer.getPrice()) || hasSupplement(offer.getCost()));
    }

    private static boolean perRoom(final RateSchedule schedule) {
        return schedule != null && !schedule.isPerPerson();
    }

    private static boolean hasSupplement(final RateSchedule schedule) {
        return schedule != null && schedule.isPerPerson() && schedule.getSingleSupplementCents() > 0;
    }

    // ================================================================== percent lines

    /**
     * Recomputes every percent line's {@code amountCents} from the line it follows (recursively, so a percent
     * of a percent works), HALF_UP to the cent. A line whose base is gone, or that sits on a cycle, resolves to
     * 0; {@link #percentProblem} keeps a save from creating either.
     */
    public static void resolvePercents(final TripBudget budget) {
        for (final BudgetLine line : budget.getLines()) {
            if (line.isPercent()) {
                line.setAmountCents(effectiveCents(budget, line, 0));
            }
        }
    }

    private static long effectiveCents(final TripBudget budget, final BudgetLine line, final int depth) {
        if (!line.isPercent()) {
            return line.getAmountCents();
        }
        final BudgetLine base = budget.line(line.getPercentOf());
        if (base == null || depth >= MAX_PERCENT_DEPTH) {
            return 0L;
        }
        return BigDecimal.valueOf(effectiveCents(budget, base, depth + 1)).multiply(BigDecimal.valueOf(
                line.getPercentBps())).divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * Why {@code line} (already in the budget) may not follow the line it names, or null when it may: the base
     * must exist, be another line of the same kind, and not lead back to {@code line}.
     */
    public static String percentProblem(final TripBudget budget, final BudgetLine line) {
        if (!line.isPercent()) {
            return null;
        }
        final BudgetLine base = budget.line(line.getPercentOf());
        if (base == null || base.getId().equals(line.getId())) {
            return "Choose another line for the percentage to apply to.";
        }
        if (base.getKind() != line.getKind()) {
            return "A percentage must be of a line of the same kind (shared or per person).";
        }
        BudgetLine step = base;
        for (int depth = 0; step != null && step.isPercent(); depth++) {
            if (step.getPercentOf().equals(line.getId()) || depth >= MAX_PERCENT_DEPTH) {
                return "That would make the percentages go in a circle: '" + base.getName() + "' already depends "
                        + "on this line.";
            }
            step = budget.line(step.getPercentOf());
        }
        return null;
    }

    // ================================================================== the whole budget

    /** The SHARED lines of one category, summed. */
    public static long sharedTotal(final TripBudget budget, final BudgetCategory category) {
        long sum = 0L;
        for (final BudgetLine line : budget.sharedLines(category)) {
            sum += line.getAmountCents();
        }
        return sum;
    }

    /** Every SHARED line, summed: the sheet's "Ground Cost (excluding lodging)". */
    public static long sharedTotal(final TripBudget budget) {
        long sum = 0L;
        for (final BudgetLine line : budget.getLines()) {
            if (line.isShared()) {
                sum += line.getAmountCents();
            }
        }
        return sum;
    }

    /** The shared costs divided across the budget's head count; 0 when nobody is counted. */
    public static long estimatedPerPerson(final TripBudget budget) {
        return divide(sharedTotal(budget), budget.getPeople());
    }

    /** Every INDIVIDUAL line per person: per-night lines times the budget's trip nights. */
    public static long individualExtras(final TripBudget budget) {
        long sum = 0L;
        for (final BudgetLine line : budget.individualLines()) {
            sum += line.totalCents(budget.getTripNights());
        }
        return sum;
    }

    /** What one participant on this lodging line costs: shared share + lodging price + individual extras. */
    public static long totalPerPersonGround(final TripBudget budget, final LodgingFigures figures) {
        return estimatedPerPerson(budget) + figures.perPersonPrice() + individualExtras(budget);
    }

    /** The computed to-bill figure for this lodging line: per-person budget + lodging price + individual extras. */
    public static long toBillReference(final TripBudget budget, final LodgingFigures figures) {
        return budget.getPerPersonBudgetCents() + figures.perPersonPrice() + individualExtras(budget);
    }

    /** The sheet's "Budget left over": what the per-person budget raises minus the shared costs. Signed. */
    public static long budgetLeftOver(final TripBudget budget) {
        return budget.getPerPersonBudgetCents() * budget.getPeople() - sharedTotal(budget);
    }

    /** {@link #budgetLeftOver} plus the lodging margin across every lodging line. Signed. */
    public static long overall(final TripBudget budget, final long lodgingMargin) {
        return budgetLeftOver(budget) + lodgingMargin;
    }

    /**
     * What is handed to whom on site: every line marked on-site. A lodging line contributes its HOTEL COST under
     * its payee (default: the hotel's name, which {@code defaultPayee} supplies); a SHARED line its amount; an
     * INDIVIDUAL line its per-person total times the head count ({@link #tripCents}). A line with no payee is
     * listed under its own name in brackets ("[Badges]"), so nothing paid on site goes missing from the list.
     * Grouped case-insensitively on the trimmed payee, in first-seen order (lodging first, then the lines).
     */
    public static List<PayeeTotal> payeeTotals(final TripBudget budget,
            final Function<BudgetLodgingLine, LodgingFigures> figures,
            final Function<BudgetLodgingLine, String> defaultPayee) {
        final Map<String, PayeeTotal> totals = new LinkedHashMap<>();
        for (final BudgetLodgingLine line : budget.getLodging()) {
            if (line.isOnSite()) {
                add(totals, lodgingPayee(line, defaultPayee), figures.apply(line).hotelCost());
            }
        }
        for (final BudgetLine line : budget.getLines()) {
            if (line.isOnSite()) {
                add(totals, (line.getPayee() != null) ? line.getPayee() : bracketed(line.getName()),
                        tripCents(budget, line));
            }
        }
        return new ArrayList<>(totals.values());
    }

    /**
     * Every line NOT paid on site, in budget order (lodging first), each with what it costs the trip: the other
     * half of the estimated trip total.
     */
    public static List<OtherItem> otherItems(final TripBudget budget,
            final Function<BudgetLodgingLine, LodgingFigures> figures,
            final Function<BudgetLodgingLine, String> labeler,
            final Function<BudgetLodgingLine, String> defaultPayee) {
        final List<OtherItem> items = new ArrayList<>();
        for (final BudgetLodgingLine line : budget.getLodging()) {
            if (!line.isOnSite()) {
                items.add(new OtherItem(labeler.apply(line), lodgingPayee(line, defaultPayee),
                        figures.apply(line).hotelCost()));
            }
        }
        for (final BudgetLine line : budget.getLines()) {
            if (!line.isOnSite()) {
                items.add(new OtherItem(line.getName(), line.getPayee(), tripCents(budget, line)));
            }
        }
        return items;
    }

    /** What a typed line costs the whole trip: a SHARED amount as is, an INDIVIDUAL per-person total × people. */
    public static long tripCents(final TripBudget budget, final BudgetLine line) {
        return line.isShared() ? line.getAmountCents() : line.totalCents(budget.getTripNights()) * budget.getPeople();
    }

    /** The other items' total. */
    public static long otherTotal(final List<OtherItem> items) {
        long sum = 0L;
        for (final OtherItem item : items) {
            sum += item.cents();
        }
        return sum;
    }

    /** The largest single-room add-on across the lodging lines; 0 when none has one. */
    public static long maxSingleAddOn(final TripBudget budget,
            final Function<BudgetLodgingLine, LodgingFigures> figures) {
        long max = 0L;
        for (final BudgetLodgingLine line : budget.getLodging()) {
            max = Math.max(max, figures.apply(line).singleAddOn());
        }
        return max;
    }

    /** The estimated basic ground cost per person: (shared costs + lodging hotel cost) / people; 0 for nobody. */
    public static long estimatedGroundPerPerson(final TripBudget budget, final long lodgingCost) {
        return divide(sharedTotal(budget) + lodgingCost, budget.getPeople());
    }

    private static String lodgingPayee(final BudgetLodgingLine line,
            final Function<BudgetLodgingLine, String> defaultPayee) {
        if (line.getPayee() != null) {
            return line.getPayee();
        }
        final String hotel = defaultPayee.apply(line);
        return (hotel != null) ? hotel : bracketed(line.getLabel());
    }

    private static String bracketed(final String name) {
        return "[" + ((name == null) ? "unnamed" : name) + "]";
    }

    /** The grand "total to bring": every payee total. */
    public static long grandTotal(final List<PayeeTotal> payees) {
        long sum = 0L;
        for (final PayeeTotal payee : payees) {
            sum += payee.cents();
        }
        return sum;
    }

    private static void add(final Map<String, PayeeTotal> totals, final String payee, final long cents) {
        if (payee == null || payee.isBlank()) {
            return;
        }
        final String name = payee.trim();
        final String key = name.toLowerCase(Locale.ROOT);
        final PayeeTotal before = totals.get(key);
        totals.put(key, (before == null) ? new PayeeTotal(name, 1, cents)
                : new PayeeTotal(before.name(), before.items() + 1, before.cents() + cents));
    }

    /** {@code cents / ways} HALF_UP to the cent; 0 when {@code ways} is not positive. */
    static long divide(final long cents, final int ways) {
        if (ways <= 0) {
            return 0L;
        }
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(ways), 0, RoundingMode.HALF_UP).longValueExact();
    }
}
