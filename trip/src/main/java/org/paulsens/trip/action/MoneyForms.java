package org.paulsens.trip.action;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Consumer;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.pay.MoneyMath;

/**
 * The conversions and checks shared by the trip's money pages (the budget and the expense report): long cents
 * in the model, {@code Double} dollars in a form, HALF_UP to the cent, and the refusal text both pages use.
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

    /** Hands {@code message} to the refusal and answers false, so a refusal can be a one-line return. */
    static boolean refused(final Consumer<String> refusal, final String message) {
        refusal.accept(message);
        return false;
    }
}
