package org.paulsens.trip.dynamo;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import lombok.extern.slf4j.Slf4j;

/**
 * Whether this JVM talks to fake data or to the real datastore. Resolved once, explicitly, and never guessed.
 *
 * <p><b>Local mode is opt-in and defaults to off.</b> That direction is the whole point of this class. It used to
 * be inferred as "no {@code FacesContext} means local", which is true of a JSF request thread and false of
 * everything else in a running container -- a {@code ServletContextListener}, a JAX-RS resource, a scheduled
 * task. The first such caller to reach {@code DAO.getInstance()} resolved the singleton against
 * {@link FakeData}, and because the DAO is a per-JVM singleton, the <em>entire deployment</em> then served fake
 * data: no real people, no real trips, no real logins, and writes going nowhere. It shipped that way on
 * 2026-07-30 when the chat digest scheduler became the first thing to touch the DAO at startup.
 *
 * <p>The failure is silent, total, and invisible to the test suite -- every unit test, webtest and integration
 * test deliberately runs in local mode, so nothing in CI can distinguish "correctly local" from "catastrophically
 * local". The only defence is that the fallback direction is production, so a bug like this can at worst break
 * a laptop, never the deployment. Do not add environment sniffing here, in any form, for any reason.
 *
 * <p>Exactly two things can turn local mode on:
 * <ol>
 *   <li>the {@code local} init-param on the {@code Faces Servlet} in {@code web.xml}, read once at context
 *       startup by {@code TripBootstrapListener} via {@link #resolveFrom(ServletContext)} -- this is what
 *       {@code TRIP_LOCAL_MODE=true} flips through the container entrypoint, and it is never set on the
 *       deployed task definition;</li>
 *   <li>the {@value #LOCAL_PROP} system property (or {@value #LOCAL_VAR} environment variable) for a JVM with no
 *       servlet container at all, which means {@code mvn test}. Set in the surefire configuration, so it cannot
 *       be forgotten and cannot leak into a deployment.</li>
 * </ol>
 *
 * <p>Resolution from the container always wins over the property: inside a deployment the descriptor is the
 * authority, so an ambient variable in the shell that happened to launch it cannot change the answer.
 *
 * @see org.paulsens.trip.audit.Audit for the same trap caught earlier, in the audit sink selection
 */
@Slf4j
public final class LocalMode {
    /** Opts a JVM with no servlet container into local mode. Surefire sets this; nothing else should. */
    static final String LOCAL_PROP = "trip.local.mode";
    /** Environment equivalent of {@value #LOCAL_PROP}, matching the container entrypoint's variable name. */
    static final String LOCAL_VAR = "TRIP_LOCAL_MODE";

    private static final String FACES_SERVLET = "Faces Servlet";
    private static final String INIT_PARAM = "local";

    /** {@code null} until a servlet container resolves it. Deliberately not defaulted to a guess. */
    private static volatile Boolean resolved;

    private LocalMode() {
    }

    /**
     * Resolves the mode from the deployment descriptor. Called once, at context startup, before anything can
     * touch the {@code DAO} -- which is why it is a listener declared ahead of every other listener and servlet
     * rather than something computed on demand.
     *
     * @param context the starting web application's context; a null or Faces-less context resolves to production.
     */
    public static void resolveFrom(final ServletContext context) {
        final boolean local = readInitParam(context);
        resolved = local;
        // Logged unconditionally, at startup, because "which datastore am I on?" is the first question worth
        // answering when a deployment looks wrong -- and the symptom of getting it wrong is data that is simply
        // absent rather than an error anyone can see.
        log.info("Trip datastore mode: {} (resolved from the Faces Servlet '{}' init-param)",
                local ? "LOCAL -- fake data, no AWS" : "PRODUCTION", INIT_PARAM);
    }

    /**
     * Whether this JVM is in local mode.
     *
     * @return {@code true} only if local mode was explicitly asked for; never inferred from the calling thread.
     */
    public static boolean isLocal() {
        final Boolean answer = resolved;
        return (answer == null) ? propertyOptIn() : answer;
    }

    /** Clears the resolved mode. For tests only -- production resolves once and never unresolves. */
    static void reset() {
        resolved = null;
    }

    private static boolean readInitParam(final ServletContext context) {
        if (context == null) {
            return false;
        }
        final ServletRegistration faces = context.getServletRegistration(FACES_SERVLET);
        return (faces != null) && "true".equals(faces.getInitParameter(INIT_PARAM));
    }

    private static boolean propertyOptIn() {
        final String explicit = System.getProperty(LOCAL_PROP, System.getenv(LOCAL_VAR));
        return (explicit != null) && Boolean.parseBoolean(explicit.trim());
    }
}
