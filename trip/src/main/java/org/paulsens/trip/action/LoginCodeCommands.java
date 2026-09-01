package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.security.Digests;
import org.paulsens.trip.security.RememberMeService;
import org.paulsens.trip.util.RandomData;
import org.paulsens.trip.util.Util;
import org.paulsens.trip.web.Sessions;
import org.paulsens.trip.cache.Cached;

/**
 * Sign-in with a short code emailed to the account address, which is also the forgot-password flow: once the
 * code proves control of the inbox, the session is established exactly like a password login, and the page
 * offers (but does not require) setting a new password.
 *
 * <p>This replaced {@code PassCommands.resetPass}, which emailed a newly-generated password in plaintext and
 * changed the account's password <em>before</em> the mail was known to be sent -- a lockout when delivery
 * failed, and a security liability when it succeeded. A code changes nothing until it is verified.
 *
 * <p>Codes live in the shared cache under {@code auth:v1:} keys as SHA-256 hashes with the configured TTL;
 * single-use, attempt-capped, send-rate-limited. Every knob is a {@link KnownSettings} entry on the admin
 * Settings page.
 */
@Slf4j
@Named("loginCode")
@ApplicationScoped
public class LoginCodeCommands {

    /**
     * The one code local mode ever issues, the same way local mode's passwords are fixed -- the webtests have
     * to be able to type it. Everything else (storage, TTL, attempts, single-use) behaves exactly as in
     * production, and {@code MailCommands} already refuses to send real mail locally.
     */
    public static final String LOCAL_MODE_CODE = "424242";

    private final CacheClient cacheClient;
    private final ConfigCommands config;
    private final MailCommands mail;

    public LoginCodeCommands() {
        this(DAO.getInstance().getCacheClient(), new ConfigCommands(), new MailCommands());
    }

    LoginCodeCommands(final CacheClient cacheClient, final ConfigCommands config, final MailCommands mail) {
        this.cacheClient = cacheClient;
        this.config = config;
        this.mail = mail;
    }

    /**
     * Emails a fresh login code -- maybe. Always answers {@code true}: whether the address has an account,
     * whether the rate limit bit, whether the cache was reachable, the page shows the same "check your email"
     * message, because any distinction here is an account-enumeration oracle. The audit trail records what
     * actually happened.
     */
    public boolean requestCode(final String email) {
        if (!config.getBoolean(KnownSettings.LOGIN_CODE_ENABLED) || Util.isBlank(email)) {
            return true;
        }
        final String lowEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!underSendLimit(lowEmail)) {
            Audit.builder(AuditAction.ALARM, AuditOutcome.FAILURE)
                    .actor(lowEmail, null)
                    .message("login.rate: code send limit reached for " + lowEmail)
                    .log();
            return true;
        }
        final Person person = DAO.getInstance().getPersonByEmail(lowEmail, Cached.NO);
        if (person == null) {
            Audit.builder(AuditAction.CODE_REQUEST, AuditOutcome.FAILURE)
                    .actor(lowEmail, null)
                    .message("Login code requested for an unknown address; nothing sent")
                    .log();
            return true;
        }
        final String code = FakeData.isLocal() ? LOCAL_MODE_CODE
                : RandomData.genSecureDigits(config.getInt(KnownSettings.LOGIN_CODE_LENGTH, 4, 10));
        final Duration ttl = codeTtl();
        if (!cacheClient.putValue(CacheKeys.loginCodeKey(lowEmail), sha256(code), ttl)) {
            // No stored code means an emailed one could never work; better no mail than a dead code.
            Audit.builder(AuditAction.CODE_REQUEST, AuditOutcome.FAILURE)
                    .actor(lowEmail, person.getId().getValue())
                    .message("Could not store a login code (cache unavailable); nothing sent")
                    .log();
            return true;
        }
        // A fresh code gets a fresh guess budget.
        cacheClient.removeKey(CacheKeys.loginCodeAttemptsKey(lowEmail));
        if (FakeData.isLocal()) {
            log.info("LOCAL MODE: login code for {} is {}", lowEmail, code);
        }
        final MailAddressCommands addresses = new MailAddressCommands(config);
        mail.send(addresses.from(KnownSettings.LOGIN_MAIL_FROM), lowEmail, null,
                addresses.replyTo(KnownSettings.LOGIN_MAIL_REPLY_TO, null), "Your sign-in code",
                codeEmail(code, ttl), AuditActor.current());
        Audit.builder(AuditAction.CODE_REQUEST, AuditOutcome.SUCCESS)
                .actor(lowEmail, person.getId().getValue())
                .message("Login code sent")
                .log();
        return true;
    }

    /**
     * Verifies a code and, on success, establishes a rotated session -- the JSF entry point. Null on ANY
     * failure; the page shows one generic message, because "wrong" vs "expired" vs "too many tries" is more
     * useful to a guesser than to the owner.
     */
    public Creds verifyAndLogin(final String email, final String code) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return verifyAndLogin(email, code, currentRequest(),
                (HttpServletResponse) ctx.getExternalContext().getResponse());
    }

    /** {@link #verifyAndLogin(String, String)} for callers that carry their own request (the REST edge). */
    public Creds verifyAndLogin(final String email, final String code, final HttpServletRequest request,
            final HttpServletResponse response) {
        final Creds creds = verifiedCreds(email, code);
        if (creds == null) {
            return null;
        }
        final String lowEmail = email.trim().toLowerCase(Locale.ROOT);
        DAO.getInstance().updateLastLogin(creds);
        final HttpSession session = Sessions.establish(request, lowEmail, creds);
        session.setAttribute(Sessions.CODE_LOGIN, Boolean.TRUE);
        if (response != null) {
            RememberMeService.getInstance().issue(request, response, creds);
        }
        Audit.builder(AuditAction.LOGIN, AuditOutcome.SUCCESS)
                .actor(lowEmail, creds.getUserId() == null ? null : creds.getUserId().getValue())
                .message("Logged in via email code")
                .log();
        return creds;
    }

    /**
     * Verifies a code and answers the credentials WITHOUT establishing a session or issuing cookies -- the
     * bearer-token issuance path ({@code docs/api-tokens.md}), which must stay sessionless. Null on ANY
     * failure, indistinguishably, for the same enumeration reason the login page shows one generic message.
     */
    public Creds verifyForToken(final String email, final String code) {
        final Creds creds = verifiedCreds(email, code);
        if (creds != null) {
            DAO.getInstance().updateLastLogin(creds);
        }
        return creds;
    }

    /** The shared verification half of every code login: burns the code, resolves creds, audits failures. */
    private Creds verifiedCreds(final String email, final String code) {
        if (!config.getBoolean(KnownSettings.LOGIN_CODE_ENABLED) || Util.isBlank(email) || Util.isBlank(code)) {
            return null;
        }
        final String lowEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!verifyCode(lowEmail, code.trim())) {
            Audit.builder(AuditAction.LOGIN, AuditOutcome.FAILURE)
                    .actor(lowEmail, null)
                    .message("Email-code login failed")
                    .log();
            return null;
        }
        final Creds creds = credsFor(lowEmail);
        if (creds == null) {
            Audit.builder(AuditAction.LOGIN, AuditOutcome.FAILURE)
                    .actor(lowEmail, null)
                    .message("Email-code verified but no account credentials could be found or created")
                    .log();
        }
        return creds;
    }

    /** Burns the code on success (single-use) and on the attempt cap; constant-time hash comparison. */
    private boolean verifyCode(final String lowEmail, final String code) {
        final String codeKey = CacheKeys.loginCodeKey(lowEmail);
        final String attemptsKey = CacheKeys.loginCodeAttemptsKey(lowEmail);
        final Optional<Long> attempts = cacheClient.increment(attemptsKey, 1, codeTtl());
        final int maxAttempts = config.getInt(KnownSettings.LOGIN_CODE_MAX_ATTEMPTS, 3, 10);
        if (attempts.isPresent() && attempts.get() > maxAttempts) {
            // Someone is guessing: the code itself is now suspect, so it dies with the budget.
            cacheClient.removeKey(codeKey);
            return false;
        }
        final String stored = cacheClient.getValue(codeKey).orElse(null);
        if (!Digests.matches(stored, sha256(code))) {
            return false;
        }
        cacheClient.removeKey(codeKey);
        cacheClient.removeKey(attemptsKey);
        return true;
    }

    /**
     * The credentials to sign in with -- created on the spot when the person exists but has never had any.
     * That case is real: people imported by an admin have a Person row and no creds, and before this their
     * only way in was asking for help. The seeded password is random and unknown; the signed-in person can set
     * a real one from the code-login page or the change-password page.
     */
    private Creds credsFor(final String lowEmail) {
        final Creds existing = DAO.getInstance().getCredsForCodeLogin(lowEmail, Cached.NO);
        if (existing != null) {
            return existing;
        }
        return DAO.getInstance().createCreds(lowEmail).orElse(null);
    }

    private boolean underSendLimit(final String lowEmail) {
        final int windowSeconds = config.getInt(KnownSettings.LOGIN_CODE_SEND_WINDOW_SECONDS, 60, 3600);
        final String key = CacheKeys.loginCodeSendsKey(lowEmail, windowSeconds, Instant.now().getEpochSecond());
        final Optional<Long> count = cacheClient.increment(key, 1, Duration.ofSeconds(windowSeconds));
        // Fail-open on a cache error (chat's convention): a broken cache should degrade to "no limit", not
        // "nobody can sign in".
        return count.isEmpty() || count.get() <= config.getInt(KnownSettings.LOGIN_CODE_MAX_SENDS, 1, 10);
    }

    private Duration codeTtl() {
        return Duration.ofSeconds(config.getInt(KnownSettings.LOGIN_CODE_TTL_SECONDS, 60, 1800));
    }

    private String codeEmail(final String code, final Duration ttl) {
        return "<p>Your sign-in code is:</p>"
                + "<p style=\"font-size:2em;font-weight:bold;letter-spacing:0.2em;\">" + code + "</p>"
                + "<p>It works for the next " + ttl.toMinutes() + " minutes, once.</p>"
                + "<p>If you didn't request this, you can ignore this email; nothing has changed on your "
                + "account.</p>";
    }

    static String sha256(final String value) {
        return Digests.sha256Base64(value);
    }

    private static HttpServletRequest currentRequest() {
        return (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
    }
}
