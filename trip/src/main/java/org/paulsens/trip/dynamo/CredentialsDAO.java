package org.paulsens.trip.dynamo;

import jakarta.faces.context.FacesContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.security.PasswordHasher;
import org.paulsens.trip.util.RandomData;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

@Slf4j
public class CredentialsDAO {
    public static final String IS_ADMIN = "showAll";
    /**
     * View flag a people-admin page sets AFTER its own reach check passes ({@code org.canAdminPerson} --
     * org-scoped {@code peopleAdmin} holders, who are not site admins and so never get {@code showAll}).
     * The org-migration analogue of the template-set {@link #IS_ADMIN} flag: both mark "this rendered view
     * was admitted by a server-side admin gate", which is what the credential read requires.
     */
    public static final String CREDS_ADMIN_VIEW = "credsAdminView";
    static final String PASS_TABLE = "pass";
    static final String EMAIL = "email";
    static final String PRIV = "priv";
    static final String PW = "pass";
    static final String USER_ID = "userId";
    static final String LAST_LOGIN = "lastLogin";

    private final Persistence persistence;
    private final PersonDAO personDao;
    private final PasswordHasher hasher;

    protected CredentialsDAO(final Persistence persistence, final PersonDAO personDAO) {
        this(persistence, personDAO, PasswordHasher.getInstance());
    }

    // Package-private: tests inject a PasswordHasher built over a fixed Pepper so they never touch AWS.
    CredentialsDAO(final Persistence persistence, final PersonDAO personDAO, final PasswordHasher hasher) {
        this.persistence = persistence;
        this.personDao = personDAO;
        this.hasher = hasher;
    }

    // Only available to admin-gated views: site admins (showAll) or a people-admin page's own flag.
    protected Creds adminGetCredsByEmail(final String email) {
        final FacesContext facesContext = FacesContext.getCurrentInstance();
        if ((email == null) || email.isEmpty() || facesContext == null) {
            return null;
        }
        final Map<String, Object> viewMap = facesContext.getViewRoot().getViewMap(false);
        if (viewMap == null
                || (!Boolean.parseBoolean(viewMap.getOrDefault(IS_ADMIN, false).toString())
                        && !Boolean.parseBoolean(viewMap.getOrDefault(CREDS_ADMIN_VIEW, false).toString()))) {
            return null;
        }
        try {
            final GetItemResponse item =
                    persistence.getItem(b -> b.key(getCredQueryKey(email)).tableName(PASS_TABLE).build());
            return item.hasItem() ? credsFromResponse(item) : null;
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    protected Creds getCredsByEmailAndPass(final String email, final String pass) {
        if ((email == null) || email.isEmpty() || (pass == null) || pass.isEmpty()) {
            return null;
        }
        try {
            final GetItemResponse item =
                    persistence.getItem(b -> b.key(getCredQueryKey(email)).tableName(PASS_TABLE).build());
            return validateCreds(item, email, pass);
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    private Map<String, AttributeValue> getCredQueryKey(final String email) {
        return Map.of(EMAIL, AttributeValue.builder().s(email.toLowerCase(Locale.ROOT)).build());
    }

    /**
     * The credentials row WITHOUT any password check -- only for a caller that has already proven the user's
     * identity another way (a verified email one-time code). Kept separate from the admin form above because
     * that one requires a Faces view map and an expected owner id, neither of which a code login has.
     */
    protected Creds getCredsForCodeLogin(final String email) {
        if ((email == null) || email.isEmpty()) {
            return null;
        }
        final GetItemResponse item =
                persistence.getItem(b -> b.key(getCredQueryKey(email)).tableName(PASS_TABLE).build());
        return item.hasItem() ? credsFromResponse(item) : null;
    }

    /**
     * Only use by Admin. Getting Creds is not cached, so be careful about overusing this!
     * @param email The email address for the {@link Creds} to retrieve.
     * @param id    The {@link Person.Id} that is expected to own the {@link Creds} -- null returned if does not match.
     * @return The Creds for the email if found and validated to match the given {@code id}, or null.
     */
    protected Creds getCredsByEmailAdminOnly(final String email, final Person.Id id) {
        if ((email == null) || email.isEmpty()) {
            return null;
        }
        try {
            final GetItemResponse item =
                    persistence.getItem(b -> b.key(getCredQueryKey(email)).tableName(PASS_TABLE).build());
            final Creds creds = item.hasItem() ? credsFromResponse(item) : null;
            return
                    (creds == null || creds.getUserId().equals(id)) ? creds : null;
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    /**
     * This method sets the last login timestamp for the given user (via {@link Creds}). It returns the previous last
     * login, or {@code null} if the user hasn't logged in before.
     *
     * @param creds     The {@link Creds} to update.
     * @return The previous last login in Epoch seconds.
     */
    protected Long updateLastLogin(final Creds creds) {
        if (creds == null) {
            return null;
        }
        final Long prevLast = creds.getLastLogin();
        creds.setLastLogin(Instant.now().getEpochSecond());
        // If the previous login was more than 2 seconds ago, save the new login time.
        if ((prevLast == null) || (prevLast < creds.getLastLogin() - 2)) {
            saveCreds(creds);
        }
        return prevLast;
    }

    protected Optional<Creds> createCreds(final String email) {
        final Person user = personDao.getPersonByEmail(email);
        if (user == null) {
            log.warn("Email '{}' not found.", email);
            return Optional.empty();
        }
        // Fail closed: seed with a random, unguessable password rather than a derivable value (the last name used
        // to double as the initial password). The only caller, PassCommands.createCreds, immediately overwrites
        // this with the user's chosen password; if that second save were ever to fail, the account is left with a
        // password nobody knows -- recoverable only via reset -- instead of one anyone could guess.
        final Creds creds = new Creds(
                email.toLowerCase(Locale.ROOT),
                user.getId(),
                Creds.USER_PRIV,
                RandomData.genSecurePassChars(24),
                Instant.now().getEpochSecond());
        // Conditional, because this table is KEYED by email and a plain put silently TAKES OVER an address
        // that already has a login -- which is how a second account claiming a former address inherited the
        // first account's passkeys. Creating a login is never a legitimate overwrite; the callers all check
        // for an existing row first, and this closes the race between that check and this write.
        return Optional.ofNullable(putCreds(creds, true) ? creds : null);
    }

    protected Boolean saveCreds(final Creds creds) {
        return putCreds(creds, false);
    }

    /** Writes the row; {@code onlyIfAbsent} refuses to take over an address that already has a login. */
    private Boolean putCreds(final Creds creds, final boolean onlyIfAbsent) {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(EMAIL, AttributeValue.builder().s(creds.getEmail().toLowerCase(Locale.ROOT)).build());
        map.put(USER_ID, AttributeValue.builder().s(creds.getUserId().getValue()).build());
        map.put(PW, AttributeValue.builder().s(hashIfNeeded(creds.getPass())).build());
        map.put(PRIV, AttributeValue.builder().s(creds.getPriv()).build());
        if (creds.getLastLogin() != null) {
            map.put(LAST_LOGIN, AttributeValue.builder().n("" + creds.getLastLogin()).build());
        }
        try {
            return persistence.putItem(b -> {
                b.tableName(PASS_TABLE).item(map);
                if (onlyIfAbsent) {
                    b.conditionExpression("attribute_not_exists(" + EMAIL + ")");
                }
            }).sdkHttpResponse().isSuccessful();
        } catch (final ConditionalCheckFailedException ex) {
            log.warn("Refusing to create credentials for '{}': that address already has a login.",
                    creds.getEmail());
            return false;
        } catch (final RuntimeException ex) {
            log.error("Failed to save credentials!", ex);
            return false;
        }
    }

    /**
     * Deletes the row a login just MOVED off, owner-checked -- the second half of an email change, since this
     * table is keyed by email and re-keying can only write the NEW row. Deliberately distinct from
     * {@link #removeCreds}, which is account deletion and refuses admins: an admin changing their own address
     * is exactly the case where the abandoned row must not survive to be claimed by somebody else.
     */
    protected Boolean removeCredsAfterRekey(final String email, final Person.Id owner) {
        final Creds existing = getCredsForCodeLogin(email);
        // Re-read rather than trust the caller's copy: if something else already claimed the address, the row
        // is not ours to remove.
        if (existing == null || owner == null || !owner.equals(existing.getUserId())) {
            return false;
        }
        return removeCredsInternal(email);
    }

    protected Boolean removeCreds(final String email) {
        return validateCanRemoveCreds(adminGetCredsByEmail(email)) && removeCredsInternal(email);
    }

    private Boolean validateCanRemoveCreds(final Creds creds) {
        // Only delete normal users, admins have to be downgraded first or edited via the db
        return creds != null && Creds.USER_PRIV.equals(creds.getPriv());
    }

    private Boolean removeCredsInternal(final String email) {
        log.info("Removing Credentials for user ({}).", email);
        final Map<String, AttributeValue> primaryKey = getCredQueryKey(email);
        try {
            return
                    persistence.deleteItem(b -> b.tableName(PASS_TABLE).key(primaryKey))
                            .sdkHttpResponse().isSuccessful();
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    // Turns a password into what should be written: an existing hash envelope passes through untouched (so
    // re-saving a loaded Creds -- e.g. on a lastLogin or email change -- never double-hashes), while a plaintext
    // value (a newly set password, or a legacy row being migrated) is hashed. Blank passwords are left as-is; the
    // callers that build Creds guarantee a value, and a blank one is a broken credential either way.
    private String hashIfNeeded(final String pass) {
        if (pass == null || pass.isBlank() || hasher.isHashed(pass)) {
            return pass;
        }
        return hasher.hash(pass);
    }

    private Creds validateCreds(final GetItemResponse resp, final String email, final String pass) {
        final String lowEmail = email.toLowerCase(Locale.ROOT);
        if (!resp.hasItem()) {
            // No credentials on file. We used to auto-create them here with the last name as the initial password;
            // that made every registered person's account guessable, so it is gone. A person without credentials
            // must establish them explicitly via create-account or reset-password.
            log.warn("No credentials on file for ({}). Login denied; user must create an account or reset.", lowEmail);
            return null;
        }
        return validateCreds(lowEmail, pass, credsFromResponse(resp));
    }

    private Creds credsFromResponse(final GetItemResponse resp) {
        final Map<String, AttributeValue> at = resp.item();
        final AttributeValue last = at.get(LAST_LOGIN);
        return new Creds(
                at.get(EMAIL).s(),
                Person.Id.from(at.get(USER_ID).s()),
                at.get(PRIV).s(),
                at.get(PW).s(),
                last == null ? null : Long.parseLong(last.n()));
    }

    private Creds validateCreds(final String email, final String pass, final Creds creds) {
        final String stored = creds.getPass();
        if (!hasher.verify(pass, stored)) {
            log.warn("Invalid password for user: {}", email);
            return null;
        }
        // Lazy upgrade: if the stored value is legacy plaintext or was made under an older pepper, re-hash it now
        // that we hold the plaintext and know it is correct. Stamp the returned Creds with the new hash so the
        // login's subsequent lastLogin save (and any later save) stores it as-is rather than hashing again.
        if (hasher.needsRehash(stored)) {
            creds.setPass(hasher.hash(pass));
            try {
                saveCreds(creds);
            } catch (final RuntimeException ex) {
                log.error("Failed to upgrade stored password hash for {}", email, ex);
            }
        }
        return creds;
    }
}
