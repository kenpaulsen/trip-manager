package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import lombok.Value;

/**
 * A named hyperlink attached to a trip artifact (today: a chat channel). Placed in the shared model package
 * rather than {@code model.chat} so it can later move onto {@link Trip} without a package rewrite.
 *
 * <p>Renderers must emit {@code rel="noopener noreferrer"} whenever {@link #target} is {@code _blank}, and
 * {@link #validateUrlScheme(String)} must run on save so a stored {@code javascript:} URL cannot become XSS.
 */
@Value
public class TripLink implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    String name;
    String url;
    /** Default {@code _blank}. */
    String target;
    /** e.g. {@code application/pdf} → document glyph. Null when unknown. */
    String contentType;

    @JsonCreator
    public TripLink(
            @JsonProperty("name") final String name,
            @JsonProperty("url") final String url,
            @JsonProperty("target") final String target,
            @JsonProperty("contentType") final String contentType) {
        this.name = name == null ? "" : name;
        this.url = url;
        this.target = (target == null || target.isBlank()) ? "_blank" : target;
        this.contentType = contentType;
    }

    /**
     * Allows only {@code http}/{@code https}. Throws on blank, unparseable, or disallowed schemes
     * ({@code javascript:}, {@code data:}, etc.).
     */
    public static String validateUrlScheme(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }
        final String trimmed = raw.trim();
        final URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid URL: " + trimmed, ex);
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("URL scheme must be http or https, not: " + scheme);
        }
        return trimmed;
    }
}
