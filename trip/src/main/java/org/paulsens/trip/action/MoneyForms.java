package org.paulsens.trip.action;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.function.Consumer;
import org.paulsens.trip.pay.CostExtras;
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
        return CostExtras.formatSigned(cents);
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

    /** Why a typed amount cannot be saved, or null: not a number, negative, or over {@link #MAX_CENTS}. */
    static String dollarsProblem(final String what, final Double dollars) {
        if (dollars == null) {
            return null;
        }
        if (dollars.isNaN() || dollars.isInfinite() || dollars < 0 || cents(dollars) > MAX_CENTS) {
            return what + " must be between $0 and " + MoneyMath.formatCents(MAX_CENTS) + ".";
        }
        return null;
    }

    /**
     * Why a typed SIGNED amount cannot be saved, or null: missing, not a number, or more than {@link #MAX_CENTS}
     * either way (a payment can run either direction).
     */
    static String signedDollarsProblem(final String what, final Double dollars) {
        if (dollars == null || dollars.isNaN() || dollars.isInfinite() || Math.abs(dollars) * 100 > MAX_CENTS) {
            final String max = MoneyMath.formatCents(MAX_CENTS);
            return what + " must be between -" + max + " and " + max + ".";
        }
        return null;
    }

    /** Why a typed text cannot be saved, or null: longer than {@code max} once trimmed. */
    static String tooLong(final String what, final String text, final int max) {
        return (text != null && text.trim().length() > max) ? what + " is at most " + max + " characters." : null;
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
