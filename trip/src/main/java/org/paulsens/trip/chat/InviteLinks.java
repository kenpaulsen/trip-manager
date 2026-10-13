package org.paulsens.trip.chat;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * The shape of a chat invite link: {@code https://{site}/i/{trip}/{selector}.{validator}}.
 *
 * <p>Short on purpose. The link is mostly scanned as a QR code, and every character is more modules in the
 * code and a less forgiving scan. The 2026-09 shape, {@code /trip/chatInvite.jsf?trip={uuid}&token=...},
 * was about 155 characters; this one is about 85 on an org site. The savings, none of which cost entropy
 * where it matters:</p>
 * <ul>
 *   <li>the path is {@code /i/} and the two values are segments, not named query parameters;</li>
 *   <li>a UUID trip id travels as its 16 bytes in unpadded base64url (22 characters instead of 36); an id
 *       that is not a UUID (local-mode seeds) travels as itself, URL-encoded;</li>
 *   <li>the validator is 16 bytes (22 characters): 128 bits of CSPRNG entropy, the size every other secret
 *       of this kind uses, where 32 bytes was more than any online guess could ever need against a hashed,
 *       expiring, rate-limited token; the selector is 6 bytes (8 characters), only ever matched within one
 *       channel's rows.</li>
 * </ul>
 *
 * <p>Old links keep working: {@code chatInvite.jsf} still accepts its query parameters, and a stored
 * validator hash verifies whatever length the validator was. {@code /i/*} is a servlet that expands the
 * short form and redirects to that page, so the landing, login round-trip and redemption stay in one place.
 * The same two shapes are what the phones' universal / app links claim.</p>
 */
public final class InviteLinks {

    /** The path the short links live under, also the app-link path the phones claim. */
    public static final String PREFIX = "/i/";
    /** The landing page a short link expands to. */
    public static final String LANDING = "/trip/chatInvite.jsf";

    private static final int COMPACT_UUID_LENGTH = 22;

    private InviteLinks() {
    }

    /** The path part of a short link, from the trip and the two halves of its token. */
    public static String shortPath(final String tripId, final String selector, final String validator) {
        return PREFIX + compactTripId(tripId) + "/" + selector + "." + validator;
    }

    /** The landing page URL (path and query) a short link's pieces expand to. */
    public static String landingPath(final String tripId, final String token) {
        return LANDING + "?trip=" + URLEncoder.encode(tripId, StandardCharsets.UTF_8)
                + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /** A UUID as 22 base64url characters; any other id URL-encoded as it is. */
    public static String compactTripId(final String tripId) {
        final UUID uuid = asUuid(tripId);
        if (uuid == null) {
            return URLEncoder.encode(tripId, StandardCharsets.UTF_8);
        }
        final ByteBuffer bytes = ByteBuffer.allocate(16);
        bytes.putLong(uuid.getMostSignificantBits());
        bytes.putLong(uuid.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /**
     * The inverse of {@link #compactTripId}: 22 base64url characters are a packed UUID, anything else is a
     * URL-encoded id. A 36-character UUID string is also accepted as itself, so a client may send either.
     */
    public static String expandTripId(final String segment) {
        if (segment == null) {
            return null;
        }
        if (segment.length() == COMPACT_UUID_LENGTH) {
            try {
                final ByteBuffer bytes = ByteBuffer.wrap(Base64.getUrlDecoder().decode(segment));
                if (bytes.remaining() == 16) {
                    return new UUID(bytes.getLong(), bytes.getLong()).toString();
                }
            } catch (final IllegalArgumentException notBase64) {
                // Not a packed UUID after all: fall through to the plain reading.
            }
        }
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }

    /** The trip id and token of a short link's path info ({@code /{trip}/{selector}.{validator}}). */
    public static Optional<Parsed> parse(final String pathInfo) {
        if (pathInfo == null) {
            return Optional.empty();
        }
        final String path = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        final String[] parts = path.split("/", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return Optional.empty();
        }
        final String token = parts[1];
        final int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(expandTripId(parts[0]), token));
    }

    private static UUID asUuid(final String id) {
        if (id == null || id.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (final IllegalArgumentException notUuid) {
            return null;
        }
    }

    /** A short link taken apart: the full trip id and the {@code selector.validator} token. */
    public record Parsed(String tripId, String token) {
    }
}
