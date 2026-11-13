package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.annotation.FacesConfig;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.security.PasswordPolicy;
import org.paulsens.trip.security.RememberMeService;
import org.paulsens.trip.util.Util;
import org.paulsens.trip.web.Sessions;

import static org.paulsens.trip.action.TripUtilCommands.addMessage;
import org.paulsens.trip.cache.Cached;

@Slf4j
@Named("pass")
@FacesConfig
@ApplicationScoped
public class PassCommands {
    private final Supplier<OrgCommands> orgSource;

    public PassCommands() {
        this(() -> org.paulsens.trip.api.Beans.get(OrgCommands.class));
    }

    /**
     * Test seam (the {@link PersonCommands} pattern): {@code Beans.get} needs a CDI container. The org
     * commands are only ever asked who may change SOMEBODY ELSE's sign-in ({@link OrgCommands#canChangeSignInOf}).
     */
    PassCommands(final Supplier<OrgCommands> orgSource) {
        this.orgSource = orgSource;
    }

    /**
     * This person's own stored login row, or null when they have none. Owner-checked, because the {@code pass}
     * table is keyed by a mutable email: a row found under the person's address but naming somebody else is
     * not theirs. Java-side only, like {@link #adminGetCreds} -- never hand the result to a page. A failed read
     * propagates, so a caller deciding something on "has no login" cannot mistake an outage for an answer.
     */
    static Creds storedLoginOf(final Person person) {
        return (person == null) ? null : storedLogin(person.getEmail(), person.getId());
    }

    /**
     * Whether this person signs in for themselves (has a stored login row of their own). A login that could
     * not be read counts as one: an outage must not be what lets an account be deleted or re-keyed. The ONE
     * fail-closed reader, for every rule that protects somebody's own account.
     */
    static boolean hasOwnLogin(final Person person) {
        try {
            return storedLoginOf(person) != null;
        } catch (final RuntimeException ex) {
            log.error("Could not read the login of {}; treating them as having one.", person.getId(), ex);
            return true;
        }
    }

    /** Whether this person's own login is a site administrator's. Unreadable counts as one, as above. */
    static boolean isSiteAdminLogin(final Person person) {
        try {
            final Creds login = storedLoginOf(person);
            return login != null && login.getPriv() != null && login.getPriv().contains("admin");
        } catch (final RuntimeException ex) {
            log.error("Could not read the login of {} to check its role; treating it as a site admin's.",
                    person.getId(), ex);
            return true;
        }
    }

    /** {@link #storedLoginOf} by the address the login is keyed under, for a caller that already holds it. */
    private static Creds storedLogin(final String email, final Person.Id owner) {
        if (Util.isBlank(email) || owner == null) {
            return null;
        }
        final Creds creds = DAO.getInstance().getCredsForCodeLogin(email.trim(), Cached.NO);
        return (creds != null && owner.equals(creds.getUserId())) ? creds : null;
    }

    public boolean userExistsWithEmail(final String email) {
        return DAO.getInstance().getPersonByEmail(email, Cached.NO) != null;
    }

    /**
     * {@link #login} plus session establishment, for the JSF login page.
     *
     * <p>The page used to assign the session attributes itself in inline JSFT, which meant the browser login
     * never rotated the session id the way the REST login always has. Funneling through
     * {@link Sessions#establish} closes that gap and keeps the attribute set defined in one place.
     */
    public Creds loginSession(final String email, final String pass) {
        final Creds creds = login(email, pass);
        if (creds != null) {
            Sessions.establish(currentRequest(), email.trim(), creds);
            RememberMeService.getInstance().issue(currentRequest(), currentResponse(), creds);
        }
        return creds;
    }

    /**
     * Signs the browser user out by invalidating the session, not by nulling attributes -- the old inline
     * logout left the session id and all server-side state alive and reusable.
     */
    public void logout() {
        final HttpServletRequest request = currentRequest();
        final HttpSession session = request.getSession(false);
        final Object email = session == null ? null : session.getAttribute(Sessions.LOGIN_EMAIL);
        // Read the actor BEFORE invalidating; afterwards the session that knows who this was is gone.
        Audit.builder(AuditAction.LOGIN, AuditOutcome.SUCCESS)
                .actor(email == null ? null : email.toString(), null)
                .message("Logged out")
                .log();
        // Logout means THIS browser stops being signed in -- the remember-me cookie must die with the
        // session, or the next request would quietly sign the person straight back in.
        RememberMeService.getInstance().revoke(request, currentResponse());
        Sessions.logout(request, currentResponse());
    }

    /** {@link #createCreds} plus session establishment, for the create-account page (same gap as login). */
    public Creds createCredsSession(final String email, final String newPass) {
        final Creds creds = createCreds(email, newPass);
        if (creds != null) {
            Sessions.establish(currentRequest(), email.trim(), creds);
            RememberMeService.getInstance().issue(currentRequest(), currentResponse(), creds);
        }
        return creds;
    }

    private static HttpServletRequest currentRequest() {
        return (HttpServletRequest) FacesContext.getCurrentInstance().getExternalContext().getRequest();
    }

    private static HttpServletResponse currentResponse() {
        return (HttpServletResponse) FacesContext.getCurrentInstance().getExternalContext().getResponse();
    }

    public Creds login(final String email, final String pass) {
        if (Util.isBlank(email) || Util.isBlank(pass)) {
            throw new IllegalArgumentException("Email or password is blank.");
        }
        if (passwordThrottled(email)) {
            // Same audit shape and same null as a wrong password: the throttle must not be distinguishable
            // from failure at the form, or it becomes a signal of its own.
            Audit.builder(AuditAction.LOGIN, AuditOutcome.FAILURE)
                    .actor(email, null)
                    .message("Login refused: too many wrong passwords in the window")
                    .log();
            return null;
        }
        final Creds creds = getCreds(email, pass);
        if (creds != null) {
            // login successful
            final String prevUpdateTime = Audit.formatEpochSeconds(DAO.getInstance().updateLastLogin(creds));
            Audit.builder(AuditAction.LOGIN, AuditOutcome.SUCCESS)
                    .actor(email, idOf(creds))
                    .message("Logged in, previous login was: " + prevUpdateTime)
                    .log();
        } else {
            recordPasswordFailure(email);
            // No id to record: a failed login is exactly the case where we do not know who they are, and
            // guessing from the email typed at the form would attribute the attempt to whoever owns it.
            Audit.builder(AuditAction.LOGIN, AuditOutcome.FAILURE)
                    .actor(email, null)
                    .message("Login failed")
                    .log();
        }
        return creds;
    }

    /**
     * Whether this address has burned through its wrong-password budget. Checked BEFORE the hash: BCrypt work
     * is exactly what an online-guessing throttle exists to deny. Fail-open on a cache error -- a broken
     * cache must degrade to "no throttle", never to "nobody can sign in".
     */
    private boolean passwordThrottled(final String email) {
        final ConfigCommands config = new ConfigCommands();
        final int maxFails = config.getInt(KnownSettings.LOGIN_PASSWORD_MAX_FAILS, 0, 1000);
        if (maxFails <= 0) {
            return false;
        }
        final int windowSeconds = config.getInt(KnownSettings.LOGIN_PASSWORD_FAIL_WINDOW_SECONDS, 60, 86400);
        final String key = CacheKeys.passwordFailsKey(email.trim().toLowerCase(Locale.ROOT), windowSeconds,
                Instant.now().getEpochSecond());
        final long fails = DAO.getInstance().getCacheClient().getValue(key)
                .map(PassCommands::parseCount).orElse(0L);
        return fails >= maxFails;
    }

    private void recordPasswordFailure(final String email) {
        final ConfigCommands config = new ConfigCommands();
        if (config.getInt(KnownSettings.LOGIN_PASSWORD_MAX_FAILS, 0, 1000) <= 0) {
            return;
        }
        final int windowSeconds = config.getInt(KnownSettings.LOGIN_PASSWORD_FAIL_WINDOW_SECONDS, 60, 86400);
        final String key = CacheKeys.passwordFailsKey(email.trim().toLowerCase(Locale.ROOT), windowSeconds,
                Instant.now().getEpochSecond());
        DAO.getInstance().getCacheClient().increment(key, 1, Duration.ofSeconds(windowSeconds));
    }

    private static long parseCount(final String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (final NumberFormatException ex) {
            return 0L;
        }
    }

    public Creds getCreds(final String email, final String pass) {
        if (Util.isBlank(email) || Util.isBlank(pass)) {
            throw new IllegalArgumentException("Email or password is blank.");
        }
        try {
            return DAO.getInstance().getCredsByEmailAndPass(email, pass, Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("Failed to get creds for: " + email, ex);
            return null;
        }
    }

    /**
     * INTERNAL credential lookup. Java-side flows only ({@link #setEmail}'s move logic and the like) --
     * pages must never call this: a {@code Creds} object in EL is one expression away from echoing a stored
     * secret, which for a legacy un-rehashed row is a plaintext password. The display-safe accessor for
     * pages is {@link #userTypeOf}. (The old admin set-password UI that consumed this is gone -- password
     * changes go through the user's own reset flow.)
     */
    public Creds adminGetCreds(final String email) {
        if (Util.isBlank(email)) {
            throw new IllegalArgumentException("Email is blank.");
        }
        return DAO.getInstance().adminGetCredsByEmail(email, Cached.NO);
    }

    /** The stored account type ("user"/"admin") for display, or null when no login exists. */
    public String userTypeOf(final String email) {
        if (Util.isBlank(email)) {
            return null;
        }
        final Creds creds = adminGetCreds(email);
        return (creds == null) ? null : creds.getPriv();
    }

    public Creds getCredsByAdmin(final String email, final Person.Id id) {
        if (Util.isBlank(email) || id == null) {
            throw new IllegalArgumentException("Email or password is blank.");
        }
        try {
            return DAO.getInstance().getCredsByEmailAdminOnly(email, id, Cached.NO);
        } catch (final RuntimeException ex) {
            log.error("Failed to get creds for: " + email, ex);
            return null;
        }
    }

    /**
     * This should only be called for a Person that exists, but does NOT have creds in the db yet. See
     * createAccount.xhtml.
     *
     * @param email     The user's unique email address.
     * @param newPass   The new password to force-set.
     * @return The newly created Creds (which are also persisted to the db) or null if it fails.
     */
    public Creds createCreds(final String email, final String newPass) {
        if (!acceptable(newPass)) {
            return null;
        }
        final DAO dao = DAO.getInstance();
        final Creds creds = dao.createCreds(email).orElse(null);
        if (creds != null) {
            creds.setPass(newPass);
            dao.saveCreds(creds);
            Audit.builder(AuditAction.CREATE_CREDS, AuditOutcome.SUCCESS)
                    .actor(email, idOf(creds))
                    .message("Created credentials")
                    .log();
        } else {
            Audit.builder(AuditAction.CREATE_CREDS, AuditOutcome.FAILURE)
                    .actor(email, null)
                    .message("Failed to create credentials")
                    .log();
        }
        return creds;
    }

    /**
     * This method allows the user to change their password.
     * @param email     The email address (login) to receive a new password.
     * @param currPass  The current password.
     * @param pass      The new password.
     * @param pass2     The new password again (in case these 2 values come from a set password form).
     * @return  True if the password was set, False otherwise.
     */
    public Boolean setPass(final String email, final String currPass, final String pass, final String pass2) {
        if (!pass.equals(pass2)) {
            PageFeedback.error("Passwords do not match!");
            return false;
        }
        if (!acceptable(pass)) {
            return false;
        }
        final DAO dao = DAO.getInstance();
        final Person person = dao.getPersonByEmail(email, Cached.NO);
        final Creds currCreds = getCreds(email, currPass);
        if ((currCreds == null) || (person == null)) {
            PageFeedback.error("Person or credentials missing!");
            return false;
        }
        if (!currCreds.getUserId().equals(person.getId())) {
            PageFeedback.error("You do not have permission to change this person's password!");
            return false;
        }
        return setPass(email, pass);
    }

    public Boolean deleteCreds(final String email) {
        // The plain (no-password, no-viewMap) read: whose login this is, for the sign-in rule below and for
        // the remember-me tokens that die if the removal succeeds. removeCreds carries its own gate as well.
        final Creds doomed = DAO.getInstance().getCredsForCodeLogin(email, Cached.NO);
        if (doomed != null && doomed.getUserId() != null
                && !orgSource.get().canChangeSignInOf(doomed.getUserId())) {
            // Removing a login ends how its owner signs in, so it takes the same reach as changing it.
            Audit.builder(AuditAction.DELETE_CREDS, AuditOutcome.FAILURE)
                    .currentActor(email)
                    .targetPerson(email, doomed.getUserId().getValue())
                    .message("Refused to remove credentials for " + email + ": not permitted to change this"
                            + " person's sign-in")
                    .log();
            return PageFeedback.refuse(OrgCommands.SIGN_IN_CHANGE_REFUSED);
        }
        final Boolean result = DAO.getInstance().removeCreds(email);
        if (Boolean.TRUE.equals(result) && doomed != null && doomed.getUserId() != null) {
            RememberMeService.getInstance().revokeAllFor(doomed.getUserId());
        }
        if (!result) {
            log.warn("Unable to remove Creds for ({}). Perhaps no Creds exist or this user is an admin?", email);
        }
        // Locking someone out of their account is worth a record whether or not it succeeded: a failed attempt
        // says as much about what someone tried to do as a successful one.
        Audit.builder(AuditAction.DELETE_CREDS, AuditOutcome.of(Boolean.TRUE.equals(result)))
                .currentActor(email)
                .targetPerson(email, null)
                .message("Removed credentials for " + email)
                .log();
        return result;
    }

    /**
     * Moves a login to a new address: the second half of an email change on the Manage People page, after
     * the person row was saved. Whoever does this for somebody else decides where that person's sign-in codes
     * are mailed, so it takes {@link OrgCommands#canChangeSignInOf}: being able to edit the profile is not
     * enough (an organization can add any account as a member, and its people admin then edits that profile).
     */
    public Boolean setEmail(final Person person, final String oldEmail, final String newEmail) {
        if (newEmail == null || newEmail.equals(oldEmail)) {
            return false;
        }
        // A blank old address has no login to move (see below), so there is nothing here to authorize.
        if (!Util.isBlank(oldEmail) && !orgSource.get().canChangeSignInOf(person.getId())) {
            return PageFeedback.refuse(OrgCommands.SIGN_IN_CHANGE_REFUSED);
        }
        final Boolean result;
        // Update their old Creds to use their new email
        final Creds newCreds = adminGetCreds(newEmail);
        if (newCreds != null && !newCreds.getUserId().equals(person.getId())) {
            // Trying to use an email address that is not theirs and already in use!
            final String msg = person.getEmail() + " is already in use! Reverting.";
            addMessage(null, new FacesMessage(FacesMessage.SEVERITY_WARN, msg, ""));
            person.setEmail(oldEmail);
            try {
                if (!DAO.getInstance().savePerson(person)

                        ) {
                    throw new IOException("Failed to revert email!");
                }
            } catch (final IOException ex) {
                addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, "Unable to revert email!", ""));
            }
            result = false;
        } else {
            // A person who never had a login (a family member gaining an email) has no Creds row to re-key;
            // adminGetCreds throws on a blank email, so it must not even be asked.
            final Creds oldCreds = Util.isBlank(oldEmail) ? null : adminGetCreds(oldEmail);
            if (oldCreds == null) {
                result = false;
            } else {
                oldCreds.setEmail(newEmail);
                result = DAO.getInstance().saveCreds(oldCreds);
                if (Boolean.TRUE.equals(result)) {
                    completeEmailMove(person, oldEmail, newEmail);
                }
            }
        }
        return result;
    }

    /**
     * Finishes what re-keying the credentials could only half-do. The {@code pass} table is KEYED by email, so
     * moving a login writes a NEW row and leaves the old one behind, still naming this person. That orphan is
     * not cosmetic: the next account created under the old address takes the row over, and every credential
     * that reaches an account through an email -- passkeys, remember-me cookies, API tokens -- then follows
     * the address to a stranger. (That is not hypothetical; it is how an admin's passkey once signed into
     * somebody else's account.) So the abandoned row goes, and everything holding the old address is
     * re-stamped with the new one.
     */
    private void completeEmailMove(final Person person, final String oldEmail, final String newEmail) {
        final DAO dao = DAO.getInstance();
        dao.removeCredsAfterRekey(oldEmail, person.getId());
        dao.updatePasskeyEmailForUser(person.getId(), newEmail);
        dao.updateAuthTokenEmailForUser(person.getId(), newEmail);
    }

    /**
     * Warning this is a dangerous command that allows a password to be directly set. Do not allow the user to
     * set the email parameter directly. Do not call this method directly from the UI (private for a reason). See
     * the setPass() which requires the old password, or the resetPass instead.
     * @param email     The email address (login) to receive a new password.
     * @param pass      The new password.
     * @return  True if the password was set, False otherwise.
     */
    private Boolean setPass(final String email, final String pass) {
        final DAO dao = DAO.getInstance();
        final Person person = dao.getPersonByEmail(email, Cached.NO);
        if (person == null) {
            PageFeedback.error("Check email address, and make sure you have registered.");
            return false;
        }
        // The stored row with a new password, NOT a fresh row: the row also carries the account's role and
        // its last sign-in, and a fresh one is an ordinary user who never signed in. That is how an
        // administrator who changed their password stopped being one. A row under this address that names
        // somebody else is not this person's to inherit from, so only then is a new one written.
        final Creds stored = storedLogin(email, person.getId());
        final Creds creds = (stored == null) ? new Creds(email, person.getId(), pass) : stored;
        creds.setPass(pass);
        final Boolean saved = dao.saveCreds(creds);
        if (Boolean.TRUE.equals(saved)) {
            // A changed password is the "I no longer trust the old state" signal: every browser holding a
            // remember-me token stops being signed in. Covers self-change, the post-code reset, and an admin
            // setting someone else's, because every change funnels through here.
            RememberMeService.getInstance().revokeAllFor(person.getId());
        }
        // Every password change funnels through here -- the owner changing their own, the forgot-password
        // reset, and an admin setting someone else's -- so this one record covers all of them. The actor is
        // whoever is signed in, which is what distinguishes "she changed her password" from "an admin did".
        Audit.builder(AuditAction.PASSWORD_CHANGE, AuditOutcome.of(Boolean.TRUE.equals(saved)))
                .currentActor(email)
                .targetPerson(person)
                .message("Password set for " + email)
                .log();
        return saved;
    }

    /**
     * Sets a new password for the SIGNED-IN person, authorized by the one-shot {@link Sessions#CODE_LOGIN}
     * flag rather than by knowing the current password -- somebody who just proved control of their inbox via
     * a login code is exactly somebody who has forgotten it. The email comes off the session, never from a
     * parameter, so the flag can only ever change the password of the account the code verified.
     */
    public Boolean setPassAfterCodeLogin(final String pass, final String pass2) {
        if (Util.isBlank(pass) || !pass.equals(pass2)) {
            PageFeedback.error("Passwords do not match!");
            return false;
        }
        if (!acceptable(pass)) {
            return false;
        }
        final HttpSession session = currentRequest().getSession(false);
        final Object flag = session == null ? null : session.getAttribute(Sessions.CODE_LOGIN);
        final Object email = session == null ? null : session.getAttribute(Sessions.LOGIN_EMAIL);
        if (!Boolean.TRUE.equals(flag) || email == null) {
            PageFeedback.error("You do not have permission to change this password!");
            return false;
        }
        // One shot: consumed before the attempt, so a failed save cannot leave the authorization behind.
        session.setAttribute(Sessions.CODE_LOGIN, null);
        return setPass(email.toString(), pass);
    }

    /**
     * Why a proposed password would be refused, or {@code null} when it is fine -- for pages that want to say
     * so before submitting the rest of a form. The same rule every setter below applies.
     */
    public String passwordProblem(final String pass) {
        return PasswordPolicy.problem(pass);
    }

    /** The policy gate every password setter shares; the refusal is reported to the page, never thrown. */
    private static boolean acceptable(final String pass) {
        final String problem = PasswordPolicy.problem(pass);
        if (problem == null) {
            return true;
        }
        PageFeedback.error(problem);
        return false;
    }

    /** The person id behind a set of credentials, when there is one. */
    private static String idOf(final Creds creds) {
        return (creds == null || creds.getUserId() == null) ? null : creds.getUserId().getValue();
    }
}
