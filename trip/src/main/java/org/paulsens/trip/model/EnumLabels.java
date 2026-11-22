package org.paulsens.trip.model;

import java.util.Locale;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.i18n.Text;

/**
 * The display name of an enum constant, read from the {@code labels} bundle in the language of the request in
 * progress ({@code docs/i18n.md}). The key is derived from the constant rather than written beside it, so a
 * new constant needs only its line in {@code labels.properties}: {@code labels.enum.TripEvent.Type.FLIGHT}.
 *
 * <p>A constant's underscores are left out of its key ({@code IN_PROGRESS} reads
 * {@code labels.enum.Status.StatusValue.INPROGRESS}): the bundle policy admits only letters and digits in a
 * key segment. The keys are built here and not handed to {@code Text} as literals, so {@code LabelsBundleTest}
 * is what proves every constant has its line.
 *
 * <p>An interface of static methods rather than a utility class: it holds no state and is never an object, and
 * {@code ModelSerializationTest} requires every CLASS in this package to be {@code Serializable}.
 */
interface EnumLabels {

    String ENUMS = "labels.enum.";

    /** The constant's own name: {@code labels.enum.{Enum}.{CONSTANT}}, a nested enum under its outer class. */
    static String of(final Enum<?> constant) {
        return of(constant, RequestContext.currentLocale());
    }

    /** {@link #of(Enum)} in an explicit language: a mail or PDF whose reader is not the one making the request. */
    static String of(final Enum<?> constant, final Locale locale) {
        final Class<?> type = constant.getDeclaringClass();
        final String path = type.getCanonicalName().substring(type.getPackageName().length() + 1);
        return Text.forLocale(locale, ENUMS + path + "." + segment(constant));
    }

    /** Another text a constant carries (a chip, a call to action, a sentence about it): {@code {area}.{CONSTANT}}. */
    static String of(final String area, final Enum<?> constant, final Object... args) {
        return ofIn(RequestContext.currentLocale(), area, constant, args);
    }

    /** {@link #of(String, Enum, Object...)} in an explicit language. */
    static String ofIn(final Locale locale, final String area, final Enum<?> constant, final Object... args) {
        return Text.forLocale(locale, area + "." + segment(constant), args);
    }

    private static String segment(final Enum<?> constant) {
        return constant.name().replace("_", "");
    }
}
