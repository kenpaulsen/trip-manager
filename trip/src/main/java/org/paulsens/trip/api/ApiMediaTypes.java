package org.paulsens.trip.api;

import org.paulsens.trip.action.ChatCommands;

/**
 * Versioned media types, one per API area.
 *
 * <p>Versioning is by {@code Accept} media type, never by a {@code /v1} path segment, so a client upgrades by
 * asking for a newer type rather than by rewriting every URL it holds.
 *
 * <p>Each constant must stay a compile-time constant: they are used in {@code @Produces} and {@code @Consumes},
 * which are annotations and cannot take a computed value.
 */
public final class ApiMediaTypes {

    /**
     * Chat's type predates this class and is defined next to the bean, because the shipped browser client in
     * chat.xhtml sends it verbatim. Aliased here rather than moved so there is still one list of every area.
     */
    public static final String CHAT_V1 = ChatCommands.MEDIA_TYPE_V1;

    public static final String AUTH_V1 = "application/vnd.trip.auth.v1+json";
    public static final String PEOPLE_V1 = "application/vnd.trip.people.v1+json";
    public static final String TRIPS_V1 = "application/vnd.trip.trips.v1+json";
    public static final String REGISTRATIONS_V1 = "application/vnd.trip.registrations.v1+json";
    public static final String TODOS_V1 = "application/vnd.trip.todos.v1+json";
    public static final String CHAT_ADMIN_V1 = "application/vnd.trip.chatadmin.v1+json";
    public static final String TRANSACTIONS_V1 = "application/vnd.trip.transactions.v1+json";
    public static final String PAYMENTS_V1 = "application/vnd.trip.payments.v1+json";
    public static final String MEDIA_V1 = "application/vnd.trip.media.v1+json";
    public static final String PHOTO_CHAT_V1 = "application/vnd.trip.photochat.v1+json";
    public static final String AUDIT_V1 = "application/vnd.trip.audit.v1+json";
    public static final String CONFIG_V1 = "application/vnd.trip.config.v1+json";
    public static final String PRIVILEGES_V1 = "application/vnd.trip.privileges.v1+json";
    public static final String MAIL_V1 = "application/vnd.trip.mail.v1+json";
    public static final String DEPLOY_V1 = "application/vnd.trip.deploy.v1+json";
    public static final String CACHE_V1 = "application/vnd.trip.cache.v1+json";

    private static final String PREFIX = "application/";
    private static final String SUFFIX = "+json";

    private ApiMediaTypes() {
    }

    /**
     * The part of a media type a client's {@code Accept} header is matched against -- {@code vnd.trip.chat.v1}
     * out of {@code application/vnd.trip.chat.v1+json}.
     *
     * <p>Derived rather than declared as a second constant per area: two hand-maintained strings that must agree
     * is exactly the pair that drifts, and the failure is silent -- negotiation quietly stops recognising the
     * type and every client falls back to the unversioned default.
     */
    public static String token(final String mediaType) {
        if (mediaType == null) {
            return "";
        }
        final String withoutPrefix = mediaType.startsWith(PREFIX) ? mediaType.substring(PREFIX.length()) : mediaType;
        return withoutPrefix.endsWith(SUFFIX)
                ? withoutPrefix.substring(0, withoutPrefix.length() - SUFFIX.length()) : withoutPrefix;
    }
}
