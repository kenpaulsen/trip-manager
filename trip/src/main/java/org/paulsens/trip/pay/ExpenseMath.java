package org.paulsens.trip.pay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import org.paulsens.trip.model.BudgetLine;
import org.paulsens.trip.model.BudgetLodgingLine;
import org.paulsens.trip.model.CurrencyRate;
import org.paulsens.trip.model.ExpenseLine;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.TripBudget;
import org.paulsens.trip.model.TripExpenseReport;

/**
 * The arithmetic of a {@link TripExpenseReport}, pure and deterministic like {@link BudgetMath}: long cents,
 * every conversion HALF_UP to the cent. A rate is foreign units per dollar in millionths, so a foreign amount
 * in dollars is {@code amount × 1,000,000 / rate}. Dollar figures are derived on every read and never stored,
 * so a rate edited later re-prices every line in that currency. The formulas and the spreadsheet oracle they
 * reproduce are in {@code docs/expense-report.md}.
 */
public final class ExpenseMath {
    private ExpenseMath() {
    }

    /** {@code amountMinor} of a currency at {@code perUsdMicros} in US cents, HALF_UP; 0 without a usable rate. */
    public static long usdCents(final long amountMinor, final long perUsdMicros) {
        if (perUsdMicros <= 0L) {
            return 0L;
        }
        return BigDecimal.valueOf(amountMinor).multiply(BigDecimal.valueOf(CurrencyRate.MICROS))
                .divide(BigDecimal.valueOf(perUsdMicros), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** One line in US cents: a USD line as typed, a foreign line at the report's rate (0 when it has none). */
    public static long lineUsd(final TripExpenseReport report, final ExpenseLine line) {
        if (line.isUsd()) {
            return line.getAmountMinor();
        }
        final CurrencyRate rate = report.currency(line.getCurrency());
        return (rate == null) ? 0L : usdCents(line.getAmountMinor(), rate.getPerUsdMicros());
    }

    /** Whether a foreign line has no usable rate, so it counts $0 and the page warns. */
    public static boolean rateMissing(final TripExpenseReport report, final ExpenseLine line) {
        if (line.isUsd()) {
            return false;
        }
        final CurrencyRate rate = report.currency(line.getCurrency());
        return rate == null || rate.getPerUsdMicros() <= 0L;
    }

    /** Everything spent, in US cents. */
    public static long total(final TripExpenseReport report) {
        long sum = 0L;
        for (final ExpenseLine line : report.getLines()) {
            sum += lineUsd(report, line);
        }
        return sum;
    }

    /** The cash advance less everything spent: positive is owed back to the organization, negative to the submitter. */
    public static long due(final TripExpenseReport report) {
        return report.getCashAdvanceCents() - total(report);
    }

    /** What the lines matched to one budget line spent, in US cents. */
    public static long spentOn(final TripExpenseReport report, final String budgetLineId) {
        long sum = 0L;
        for (final ExpenseLine line : report.getLines()) {
            if (budgetLineId != null && budgetLineId.equals(line.getBudgetLineId())) {
                sum += lineUsd(report, line);
            }
        }
        return sum;
    }

    /**
     * What the lines matched to NO budget line spent: unmatched lines and lines whose budget line is gone (not
     * in {@code budgetLineIds}), so the budget table's spent column always adds up to the report's total.
     */
    public static long notInBudget(final TripExpenseReport report, final Set<String> budgetLineIds) {
        long sum = 0L;
        for (final ExpenseLine line : report.getLines()) {
            if (line.getBudgetLineId() == null || !budgetLineIds.contains(line.getBudgetLineId())) {
                sum += lineUsd(report, line);
            }
        }
        return sum;
    }

    /** The lines in one currency, summed in that currency's hundredths. */
    public static long currencyTotal(final TripExpenseReport report, final String code) {
        long sum = 0L;
        for (final ExpenseLine line : report.linesUsing(code)) {
            sum += line.getAmountMinor();
        }
        return sum;
    }

    /** The lines in one currency, summed in US cents line by line (so it matches the lines' own USD column). */
    public static long currencyUsd(final TripExpenseReport report, final String code) {
        long sum = 0L;
        for (final ExpenseLine line : report.linesUsing(code)) {
            sum += lineUsd(report, line);
        }
        return sum;
    }

    /** How many lines carry no receipt. */
    public static int linesWithoutReceipt(final TripExpenseReport report) {
        int count = 0;
        for (final ExpenseLine line : report.getLines()) {
            if (line.getReceiptIds().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** Dollars per ONE unit of the currency (the inverse of the stored rate), to four places; null without a rate. */
    public static BigDecimal usdPerUnit(final long perUsdMicros) {
        if (perUsdMicros <= 0L) {
            return null;
        }
        return BigDecimal.valueOf(CurrencyRate.MICROS).divide(BigDecimal.valueOf(perUsdMicros), 4,
                RoundingMode.HALF_UP);
    }

    /** A typed rate ("0.83") in millionths, HALF_UP; 0 for null. */
    public static long micros(final BigDecimal rate) {
        if (rate == null) {
            return 0L;
        }
        return rate.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /** A stored rate as typed: 830_000 reads 0.83, trailing zeros dropped. */
    public static BigDecimal rate(final long perUsdMicros) {
        return BigDecimal.valueOf(perUsdMicros).movePointLeft(6).stripTrailingZeros();
    }

    /** What the budget planned for a typed line, for the whole trip ({@link BudgetMath#tripCents}). */
    public static long budgeted(final TripBudget budget, final BudgetLine line) {
        return BudgetMath.tripCents(budget, line);
    }

    /** What the budget planned for a lodging line: its hotel COST, never the price (0 when the option is gone). */
    public static long budgeted(final BudgetLodgingLine line, final ReservationOffer offer) {
        return BudgetMath.lodging(line, offer).hotelCost();
    }
}
