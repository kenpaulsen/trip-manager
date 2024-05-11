package org.paulsens.trip.dynamo;

import jakarta.faces.context.FacesContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

@Slf4j
public class CredentialsDAO {
    public static final String IS_ADMIN = "showAll";
    static final String PASS_TABLE = "pass";
    static final String EMAIL = "email";
    static final String PRIV = "priv";
    static final String PW = "pass";
    static final String USER_ID = "userId";
    static final String LAST_LOGIN = "lastLogin";

    private final Persistence persistence;
    private final PersonDAO personDao;

    protected CredentialsDAO(final Persistence persistence, final PersonDAO personDAO) {
        this.persistence = persistence;
        this.personDao = personDAO;
    }

    // Only available to admins
    protected CompletableFuture<Creds> adminGetCredsByEmail(final String email) {
        final FacesContext facesContext = FacesContext.getCurrentInstance();
        if ((email == null) || email.isEmpty() || facesContext == null) {
            return CompletableFuture.completedFuture(null);
        }
        final Map<String, Object> viewMap = facesContext.getViewRoot().getViewMap(false);
        if (viewMap == null || !Boolean.parseBoolean(viewMap.getOrDefault(IS_ADMIN, false).toString())) {
            return CompletableFuture.completedFuture(null);
        }
        return persistence.getItem(b -> b
                        .key(getCredQueryKey(email))
                        .tableName(PASS_TABLE)
                        .build())
                .thenApply(this::credsFromResponse);
    }

    protected CompletableFuture<Creds> getCredsByEmailAndPass(final String email, final String pass) {
        if ((email == null) || email.isEmpty() || (pass == null) || pass.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return persistence.getItem(b -> b.key(getCredQueryKey(email)).tableName(PASS_TABLE).build())
                .thenApply(item -> validateCreds(item, email, pass));
    }

    private Map<String, AttributeValue> getCredQueryKey(final String email) {
        final String lowEmail = email.toLowerCase();
        return Map.of(EMAIL, AttributeValue.builder().s(lowEmail).build());
    }

    /**
     * Only use by Admin. Getting Creds is not cached, so be careful about overusing this!
     * @param email The email address for the {@link Creds} to retrieve.
     * @param id    The {@link Person.Id} that is expected to own the {@link Creds} -- null returned if does not match.
     * @return The Creds for the email if found and validated to match the given {@code id}, or null.
     */
    protected CompletableFuture<Creds> getCredsByEmailAdminOnly(final String email, final Person.Id id) {
        if ((email == null) || email.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final Map<String, AttributeValue> key =
                Map.of(EMAIL, AttributeValue.builder().s(email.toLowerCase()).build());
        return persistence.getItem(b -> b.key(key).tableName(PASS_TABLE).build())
                .thenApply(item -> (item.hasItem()) ? credsFromResponse(item) : null)
                .thenApply(creds -> (creds == null || creds.getUserId().equals(id)) ? creds : null);
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
        final Person user = personDao.getPersonByEmail(email).join();
        if (user == null) {
            log.warn("Email '{}' not found.", email);
            return Optional.empty();
        }
        final Creds creds = new Creds(
                email.toLowerCase(Locale.getDefault()),
                user.getId(),
                Creds.USER_PRIV,
                user.getLast(),
                Instant.now().getEpochSecond());
        return Optional.ofNullable(saveCreds(creds).join() ? creds : null);
    }

    protected CompletableFuture<Boolean> saveCreds(final Creds creds) {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(EMAIL, AttributeValue.builder().s(creds.getEmail().toLowerCase(Locale.getDefault())).build());
        map.put(USER_ID, AttributeValue.builder().s(creds.getUserId().getValue()).build());
        map.put(PW, AttributeValue.builder().s(creds.getPass()).build());
        map.put(PRIV, AttributeValue.builder().s(creds.getPriv()).build());
        if (creds.getLastLogin() != null) {
            map.put(LAST_LOGIN, AttributeValue.builder().n("" + creds.getLastLogin()).build());
        }
        return persistence.putItem(b -> b.tableName(PASS_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .exceptionally(ex -> {
                    log.error("Failed to save credentials!", ex);
                    return false;
                });
    }

    private Creds validateCreds(final GetItemResponse resp, final String email, final String pass) {
        final String lowEmail = email.toLowerCase();
        if (!resp.hasItem()) {
            log.warn("User with email (" + lowEmail + ") has not logged in before! Checking if user exists...");
            return createCreds(lowEmail).map(creds -> validateCreds(lowEmail, pass, creds)).orElse(null);
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
        if (!pass.equals(creds.getPass())) {
            log.warn("Invalid password for user: {}", email);
            return null;
        }
        return creds;
    }
}
