package org.paulsens.trip.pay;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.AdvanceEntry;
import org.paulsens.trip.model.AdvanceType;
import org.paulsens.trip.model.CurrencyRate;
import org.paulsens.trip.model.ExpenseCategory;
import org.paulsens.trip.model.ExpenseLine;
import org.paulsens.trip.model.TripExpenseReport;

/**
 * The "Expense Report" tab of the {@code 2026-09-HolyAngels} sheet, line for line, as the oracle the expense
 * report's arithmetic must reproduce: cash advance $45,765.00, USD / EUR 0.83, USD / KM (BAM) 1.66, Actual
 * total $55,560.95, due to the organization -$9,795.95. The sheet's one mixed row (bus driver tips: 40 EUR +
 * 200 KM + $20) is three lines here, since a line carries one currency.
 */
public final class ExpenseOracle {
    public static final long CASH_ADVANCE = 4_576_500L;
    public static final long TOTAL = 5_556_095L;
    public static final long DUE = -979_595L;

    private ExpenseOracle() {
    }

    /** The sheet's report; every line id is its description's slug so tests can find one. */
    public static TripExpenseReport report() {
        final List<ExpenseLine> lines = new ArrayList<>();
        lines.add(line("van-pdx", 9, 20, ExpenseCategory.TRANSPORTATION, "Rental Van from PDX to SEA", "USD", 25_583L));
        lines.add(line("audie", 9, 20, ExpenseCategory.TRANSPORTATION, "Audie flight change", "USD", 18_000L));
        lines.add(line("tips-eur", 0, 0, ExpenseCategory.TIPS, "Tips for bus drivers", "EUR", 4_000L));
        lines.add(line("tips-bam", 0, 0, ExpenseCategory.TIPS, "Tips for bus drivers", "BAM", 20_000L));
        lines.add(line("tips-usd", 0, 0, ExpenseCategory.TIPS, "Tips for bus drivers", "USD", 2_000L));
        lines.add(line("candles", 9, 25, ExpenseCategory.SUPPLIES, "Candles for consecration", "EUR", 2_300L));
        lines.add(line("party", 9, 26, ExpenseCategory.SUPPLIES, "Party supplies", "USD", 10_644L));
        lines.add(line("gradnici", 9, 29, ExpenseCategory.MEALS, "Gradnici donation", "EUR", 22_000L));
        lines.add(line("guide", 9, 29, ExpenseCategory.GUIDE, "Guide", "USD", 144_000L));
        lines.add(line("gift", 9, 29, ExpenseCategory.TIPS, "Donation to Fr. John's gift", "USD", 10_000L));
        lines.add(line("gas", 10, 3, ExpenseCategory.TRANSPORTATION, "Gas for Van", "USD", 11_398L));
        lines.add(line("tolls", 0, 0, ExpenseCategory.TRANSPORTATION, "Tolls", "USD", 3_000L));
        lines.add(line("van", 10, 4, ExpenseCategory.TRANSPORTATION, "Rental Van", "BAM", 80_000L));
        lines.add(line("brela", 10, 7, ExpenseCategory.LODGING, "Brela Pansion", "USD", 100_000L));
        lines.add(line("huanita", 9, 30, ExpenseCategory.LODGING, "Huanita and Dragan", "USD", 2_522_000L));
        lines.add(line("bus", 9, 30, ExpenseCategory.TRANSPORTATION, "Ground Transportation", "USD", 316_068L));
        lines.add(line("dragan-gas", 9, 30, ExpenseCategory.TRANSPORTATION, "Gas for Dragan's car", "USD", 4_770L));
        lines.add(line("left", 9, 30, ExpenseCategory.GENERAL, "Money left with Huanita", "USD", 2_294_295L));
        return TripExpenseReport.builder().tripId("t").submittedBy("Ken Paulsen")
                .advances(List.of(advance(CASH_ADVANCE)))
                .currencies(List.of(rate("EUR", 830_000L), rate("BAM", 1_660_000L))).lines(lines).build();
    }

    /** One Cash advance from CFPW of this many US cents: the sheet's single "Cash advance" header figure. */
    public static AdvanceEntry advance(final long usdCents) {
        return AdvanceEntry.builder().id("advance-" + usdCents).type(AdvanceType.CASH).amountMinor(usdCents)
                .fields(Map.of("from", "CFPW")).build();
    }

    static CurrencyRate rate(final String code, final long micros) {
        return CurrencyRate.builder().code(code).perUsdMicros(micros).build();
    }

    private static ExpenseLine line(final String id, final int month, final int day, final ExpenseCategory category,
            final String description, final String currency, final long amount) {
        return ExpenseLine.builder().id(id).date((month == 0) ? null : LocalDate.of(2026, month, day))
                .category(category).description(description).currency(currency).amountMinor(amount).build();
    }
}
