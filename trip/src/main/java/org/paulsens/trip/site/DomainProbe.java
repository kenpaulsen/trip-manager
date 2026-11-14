package org.paulsens.trip.site;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.StructuredTaskScope;
import javax.net.ssl.SSLException;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.LocalMode;

/**
 * The live proof a custom domain gives before an organization may claim it ({@code docs/org-admin.md}, "Custom
 * domains"). A custom domain becomes, the moment it is saved, the host of every link mailed for that org (some
 * carrying bearer tokens) and an accepted payment return host, so "well-formed and unclaimed" is not enough: a
 * typo, a domain saved before its certificate is attached, or one whose DNS points elsewhere would send those
 * links to a host this service does not answer on.
 *
 * <p>So the save asks {@code https://{domain}}{@value #PATH}{@code ?code={nonce}} with a one-time code only this
 * server knows, over a normally validated TLS connection with short timeouts and no redirects, and the claim
 * stands only when the answer is that code. Only this server echoes it ({@code web/SiteCheckServlet}), so the
 * echo proves at once that the domain's DNS reaches this service and that the load balancer holds a valid
 * certificate for it.
 *
 * <p>The codes live in this JVM: production runs ONE task by design. While a blue/green deploy has two tasks
 * serving, a probe can land on the other one and fail; saving again once the deploy settles succeeds.
 *
 * <p>Checked once, at the claim (a re-save of the same domain is not re-probed). A domain that lapses later is
 * NOT re-checked: taking an org off a dead domain is a site admin clearing the field.
 *
 * <p>Local mode skips the probe (no laptop has a certificate for a customer's domain, and FakeData seeds its
 * {@code beta-corp.test} directly); {@link #useCheck} is the seam that lets a test make it pass or fail.
 */
@Slf4j
public final class DomainProbe {

    /** Where the probe asks; mapped to {@code web/SiteCheckServlet} in the live web.xml. */
    public static final String PATH = "/.well-known/trip-site-check";
    /** How long an issued code is answered: one probe's round trip, with room for a slow load balancer. */
    static final Duration CODE_LIFETIME = Duration.ofSeconds(30);
    /** Connect and whole-exchange (body included) budgets: an admin is waiting on the Save button. */
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** A code is 22 characters; anything this long is not our answer, so the rest is never read. */
    private static final int MAX_ANSWER = 256;
    private static final int CODE_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Outstanding code -> its expiry (epoch millis). Removed after its probe; expired ones never answer. */
    private static final Map<String, Long> CODES = new ConcurrentHashMap<>();
    /** One client for the JVM (each owns a selector thread), on virtual threads like the other JDK clients. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    /** How a claim is checked: why {@code domain} did not echo {@code code}, or null when it did. */
    @FunctionalInterface
    public interface Check {
        String problem(String domain, String code);
    }

    /** The test seam's replacement, or null for the real check (the HTTPS probe; nothing in local mode). */
    private static volatile Check override;

    private DomainProbe() {
    }

    /**
     * Why an org may not newly claim {@code wanted} (it did not echo a fresh code over HTTPS), or null when it
     * may. Null without probing when {@code wanted} is already the stored domain: a re-save is not a new claim.
     *
     * @param wanted the {@link CustomDomains#normalize normalized}, already {@link CustomDomains#claimProblem
     *               validated} domain
     * @param stored the org's stored custom domain, in any spelling (null when it has none)
     */
    public static String newClaimProblem(final String wanted, final String stored) {
        if (wanted == null || wanted.equals(CustomDomains.normalize(stored))) {
            return null;
        }
        final String code = issue();
        try {
            return check().problem(wanted, code);
        } finally {
            CODES.remove(code);
        }
    }

    /**
     * The answer for a probe carrying {@code code}: the code as issued while it is outstanding, else null (the
     * servlet's 404). Read-only and session-free, since the probe arrives anonymous.
     */
    public static String answer(final String code) {
        if (code == null) {
            return null;
        }
        final Long expires = CODES.get(code);
        return (expires != null && System.currentTimeMillis() < expires) ? code : null;
    }

    /**
     * Test seam: replaces the check (null restores the real one) and answers the previous replacement, so a
     * test can put it back.
     */
    public static Check useCheck(final Check replacement) {
        final Check previous = override;
        override = replacement;
        return previous;
    }

    /** A fresh code, outstanding for {@link #CODE_LIFETIME}. */
    static String issue() {
        final byte[] bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);
        final String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        CODES.put(code, System.currentTimeMillis() + CODE_LIFETIME.toMillis());
        return code;
    }

    private static Check check() {
        final Check replaced = override;
        if (replaced != null) {
            return replaced;
        }
        return LocalMode.isLocal() ? DomainProbe::skipped : DomainProbe::overHttps;
    }

    private static String skipped(final String domain, final String code) {
        log.info("Local mode: custom domain {} accepted without the HTTPS probe", domain);
        return null;
    }

    private static String overHttps(final String domain, final String code) {
        return probe(HTTP, "https://" + domain, domain, code);
    }

    /**
     * One probe of {@code origin} (the testable half: a test points it at a local server): null when it answered
     * 200 with exactly {@code code}, else why not, worded for the admin who pressed Save.
     */
    static String probe(final HttpClient http, final String origin, final String domain, final String code) {
        return probe(http, origin, domain, code, TIMEOUT);
    }

    /**
     * {@link #probe(HttpClient, String, String, String)} with the whole exchange, body included, under {@code
     * deadline}. {@link HttpRequest#timeout} alone is not enough: this JDK stops that timer once the headers
     * arrive, so a host that sends headers and then stalls would hold the Save request (and its code) forever.
     * The exchange runs in a scope with a timeout instead; on expiry the scope interrupts it, which unblocks the
     * body read, and the admin hears that the domain did not answer.
     */
    static String probe(final HttpClient http, final String origin, final String domain, final String code,
            final Duration deadline) {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(origin + PATH + "?code=" + code))
                .timeout(deadline)
                .GET()
                .build();
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<String>awaitAllSuccessfulOrThrow(),
                config -> config.withTimeout(deadline))) {
            final StructuredTaskScope.Subtask<String> answered = scope.fork(() -> exchange(http, request, domain,
                    code));
            scope.join();
            return answered.get();
        } catch (final StructuredTaskScope.TimeoutException ex) {
            return notAnswered(domain);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return interrupted(domain);
        }
    }

    /** The request and the bounded body read, run inside {@link #probe}'s deadline. */
    private static String exchange(final HttpClient http, final HttpRequest request, final String domain,
            final String code) {
        try {
            final HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            final String answer = readAnswer(response.body());
            if (response.statusCode() == HttpURLConnection.HTTP_OK && code.equals(answer)) {
                return null;
            }
            return domain + " answered, but not as this site (HTTP " + response.statusCode()
                    + "). Check that its DNS points here.";
        } catch (final IOException ex) {
            // The deadline's interrupt surfaces from the body read as IOException(InterruptedException). The scope
            // has already answered "did not answer", so this is no failure to log with a stack trace.
            return Thread.currentThread().isInterrupted() || causedBy(ex, InterruptedException.class)
                    ? interrupted(domain) : problemOf(domain, ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return interrupted(domain);
        }
    }

    private static String notAnswered(final String domain) {
        return domain + " did not answer over HTTPS. Check that its DNS points here.";
    }

    private static String interrupted(final String domain) {
        return "Interrupted while checking " + domain + ".";
    }

    /**
     * Why a probe that threw did not reach this site. The JDK client wraps its causes (an unresolved name arrives
     * as a ConnectException around an UnresolvedAddressException), so the whole cause chain is consulted.
     */
    private static String problemOf(final String domain, final IOException ex) {
        if (causedBy(ex, SSLException.class)) {
            log.info("Custom domain probe of {}: TLS refused", domain, ex);
            return domain + " has no valid certificate here yet. Attach its certificate first.";
        }
        if (causedBy(ex, UnknownHostException.class) || causedBy(ex, UnresolvedAddressException.class)) {
            return domain + " does not resolve. Add its DNS records first.";
        }
        if (causedBy(ex, HttpTimeoutException.class) || causedBy(ex, ConnectException.class)) {
            return notAnswered(domain);
        }
        log.info("Custom domain probe of {} failed", domain, ex);
        return domain + " could not be reached over HTTPS (" + ex.getClass().getSimpleName() + ").";
    }

    private static boolean causedBy(final Throwable thrown, final Class<? extends Throwable> type) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /** At most {@link #MAX_ANSWER} bytes of the body, trimmed: a stranger's server may send anything. */
    private static String readAnswer(final InputStream body) throws IOException {
        try (InputStream in = body) {
            return new String(in.readNBytes(MAX_ANSWER), StandardCharsets.UTF_8).trim();
        }
    }
}
