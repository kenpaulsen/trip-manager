package org.paulsens.trip.action;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.paulsens.trip.action.BudgetViews.Choice;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.pay.MoneyMath;

/**
 * The conversions and checks shared by the trip's money pages (the budget, the expense report, the lodging
 * forms): long cents in the model, {@code Double} dollars in a form, HALF_UP to the cent, a percentage as basis
 * points, and the refusal text the pages use.
 */
final class MoneyForms {
    /** The largest amount a money field takes: ten million dollars. */
    static final long MAX_CENTS = 10_000_000_00L;

    private MoneyForms() {
    }

    /** "$1,440.00", "-$1.50". */
    static String money(final long cents) {
        return MoneyMath.formatCents(cents);
    }

    /** Dollars typed into a form as cents, HALF_UP; 0 for null. */
    static long cents(final Double dollars) {
        return (dollars == null) ? 0L
                : BigDecimal.valueOf(dollars).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /** Cents as the dollars a form shows. */
    static Double dollars(final long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).doubleValue();
    }

    /**
     * A percentage typed into a form (12.5) as basis points (1250), HALF_UP; 0 for null. THE conversion for every
     * percent field: the lodging cancellation fee used {@code Math.round(percent * 100)}, which works in binary
     * and so read 12.345% as 1234 where the budget and the concessions read 1235.
     */
    static int bps(final Double percent) {
        return (percent == null) ? 0
                : BigDecimal.valueOf(percent).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /**
     * Why a typed amount cannot be saved, or null: not a number, negative, or over {@link #MAX_CENTS}. The
     * refusal is the WHOLE sentence {@code rangeKey} names, one per field, with the maximum as its argument: a
     * field's name glued in front of a shared tail cannot be translated (docs/i18n.md, "Whole sentences").
     */
    static String dollarsProblem(final String rangeKey, final Double dollars) {
        if (dollars == null) {
            return null;
        }
        if (dollars.isNaN() || dollars.isInfinite() || dollars < 0 || cents(dollars) > MAX_CENTS) {
            return Text.get(rangeKey, MoneyMath.formatCents(MAX_CENTS));
        }
        return null;
    }

    /**
     * Why a typed SIGNED amount cannot be saved, or null: missing, not a number, or more than {@link #MAX_CENTS}
     * either way (a payment can run either direction). {@code rangeKey} as in {@link #dollarsProblem}.
     */
    static String signedDollarsProblem(final String rangeKey, final Double dollars) {
        if (dollars == null || dollars.isNaN() || dollars.isInfinite() || Math.abs(dollars) * 100 > MAX_CENTS) {
            return Text.get(rangeKey, MoneyMath.formatCents(MAX_CENTS));
        }
        return null;
    }

    /**
     * Why a typed text cannot be saved, or null: longer than {@code max} once trimmed. {@code tooLongKey} is the
     * field's whole sentence, with {@code max} as its (plural) count.
     */
    static String tooLong(final String tooLongKey, final String text, final int max) {
        return (text != null && text.trim().length() > max) ? Text.get(tooLongKey, max) : null;
    }

    /**
     * A saved row into its list: appended when it is new ({@code existing} null), else in {@code existing}'s
     * place, so an edit never moves a line. The caller has already refused an edit of a row that is gone.
     */
    static <T> void putAt(final List<T> rows, final T existing, final T row) {
        if (existing == null) {
            rows.add(row);
        } else {
            rows.set(rows.indexOf(existing), row);
        }
    }

    /** One menu entry: the value a form stores and what the menu calls it. */
    static Choice choice(final String id, final String label) {
        final Choice choice = new Choice();
        choice.setId(id);
        choice.setLabel(label);
        return choice;
    }

    /** An enum's menu: every constant in declaration order, value = its name. A new list, the caller's to change. */
    static <E extends Enum<E>> List<Choice> choicesOf(final E[] values, final Function<E, String> label) {
        final List<Choice> choices = new ArrayList<>();
        for (final E value : values) {
            choices.add(choice(value.name(), label.apply(value)));
        }
        return choices;
    }

    /** The enum constant named {@code name}; null for blank or unknown. */
    static <E extends Enum<E>> E parse(final Class<E> type, final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.trim());
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }
}
