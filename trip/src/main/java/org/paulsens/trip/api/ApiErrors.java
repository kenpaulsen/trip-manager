package org.paulsens.trip.api;

/**
 * The wire error codes shared by every API area, and the rule for choosing one.
 *
 * <p>{@link ChatErrors} came first and stays as it is: its strings are a pinned contract with a shipped browser
 * client, and several of its codes are chat-specific in a way nothing else needs. The codes here are the ones a
 * generic resource reaches for, spelled identically to their chat equivalents so a client that already handles
 * {@code NOT_AUTHENTICATED} from chat handles it everywhere.
 *
 * <h2>Why a closed table and not per-endpoint judgement</h2>
 *
 * <p>The command beans mostly answer {@code false} or {@code null} and post a {@code FacesMessage} describing what
 * actually went wrong. Off a JSF thread that message goes nowhere — {@code TripUtilCommands.addMessage} no-ops with
 * a log line when there is no {@code FacesContext} — so by the time a resource sees the result, the reason is gone.
 * Left to invent a code per endpoint, two resources will answer differently for the same underlying failure.
 *
 * <p>So the mapping is fixed:
 * <ul>
 *   <li>{@code null} from a getter → 404 {@link #NOT_FOUND}</li>
 *   <li>{@code false} from a {@code save*} → 500 {@link #STORE_FAILED}</li>
 *   <li>{@code false} from a permission-shaped call → 403 {@link #FORBIDDEN}</li>
 *   <li>{@code IllegalArgumentException} → 400 {@link #BAD_REQUEST}</li>
 *   <li>a validation message returned as a String → 422 {@link #VALIDATION_FAILED}</li>
 * </ul>
 *
 * <p>Where an area needs to say more than a boolean can, the bean grows a small result record rather than the
 * resource guessing: {@code ChatCommands.SendResult} and {@code ReactResult} are the in-repo precedent.
 */
public final class ApiErrors {

    /** No session, or a session with no signed-in user. Recoverable: the client should re-authenticate. */
    public static final String NOT_AUTHENTICATED = ChatErrors.NOT_AUTHENTICATED;
    /** Authenticated, but not permitted. Never used for "not signed in" — that ambiguity hides broken sessions. */
    public static final String FORBIDDEN = ChatErrors.FORBIDDEN;
    public static final String NOT_FOUND = ChatErrors.NOT_FOUND;
    /** The request was malformed. Distinct from {@link #VALIDATION_FAILED}, which means well-formed but refused. */
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String CONFLICT = "CONFLICT";
    /** The write was understood and attempted, and the store refused it. Retryable, unlike the 4xx codes. */
    public static final String STORE_FAILED = ChatErrors.STORE_FAILED;
    public static final String CSRF = ChatErrors.CSRF;
    public static final String NOT_ACCEPTABLE = ChatErrors.NOT_ACCEPTABLE;
    public static final String INTERNAL = ChatErrors.INTERNAL;

    private ApiErrors() {
    }
}
