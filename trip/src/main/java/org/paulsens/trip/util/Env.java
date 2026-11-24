package org.paulsens.trip.util;

import java.util.Locale;
import software.amazon.awssdk.regions.Region;

/**
 * Deployment settings read from the process: a system property, else an environment variable.
 *
 * <p>One precedence for every reader: a non-blank system property wins, then a non-blank environment
 * variable, and the answer is trimmed; blank counts as unset. Settings that deliberately read differently
 * stay where they are ({@code LocalMode}, {@code trip.audit.dynamo}, {@code trip.dynamo.endpoint}, the
 * sysprop-only push secret file): each documents why at its own site.
 */
public final class Env {
    /** The region every AWS client of a deployment lands in, unless a setting of its own says otherwise. */
    static final String REGION_PROP = "trip.dynamo.region";
    static final String REGION_VAR = "TRIP_DYNAMO_REGION";

    private Env() {
    }

    /** The system property when it is set and not blank, else the environment variable; trimmed, or null. */
    public static String setting(final String sysProp, final String envVar) {
        final String fromProp = System.getProperty(sysProp);
        final String value = (fromProp == null || fromProp.isBlank()) ? System.getenv(envVar) : fromProp;
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** {@link #setting(String, String)} for a variable whose property is its name in dots: {@code A_B}, {@code a.b}. */
    public static String setting(final String envVar) {
        return setting(envVar.toLowerCase(Locale.ROOT).replace('_', '.'), envVar);
    }

    /**
     * The deployment's AWS region: {@code trip.dynamo.region} / {@code TRIP_DYNAMO_REGION}, else us-west-2 (the
     * active deployment). Overridable so a one-off tool can target another deployment without a rebuild.
     */
    public static Region awsRegion() {
        final String region = setting(REGION_PROP, REGION_VAR);
        return (region == null) ? Region.US_WEST_2 : Region.of(region);
    }
}
