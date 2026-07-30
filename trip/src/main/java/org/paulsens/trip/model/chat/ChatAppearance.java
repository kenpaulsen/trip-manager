package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.Locale;
import lombok.Value;

/**
 * How one chat pane looks: a background colour and an optional background image.
 *
 * <p>Held twice — as the channel default in {@link ChatSettings} and as a per-member override on
 * {@link ChatMembership} — and merged per field, so someone can override the colour while keeping the trip's
 * image, or the reverse. A member override is per channel, so a person can style each trip differently.
 *
 * <p><b>Both values are validated here rather than at the point of use.</b> They travel from a text field
 * straight into a {@code style} attribute, so an unchecked value is CSS injection at best and, for the URL,
 * stored XSS with an administrator as its author — the same hazard {@code TripLink} guards. Anything that does
 * not pass is dropped to null, which falls back to the stylesheet.
 */
@Value
public class ChatAppearance implements Serializable {

    public static final ChatAppearance NONE = new ChatAppearance(null, null);

    String backgroundColor;
    String backgroundImageUrl;

    @JsonCreator
    public ChatAppearance(
            @JsonProperty("backgroundColor") final String backgroundColor,
            @JsonProperty("backgroundImageUrl") final String backgroundImageUrl) {
        this.backgroundColor = safeColor(backgroundColor);
        this.backgroundImageUrl = safeImageUrl(backgroundImageUrl);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return backgroundColor == null && backgroundImageUrl == null;
    }

    /** The member's choice per field, falling back to the channel's. Never null. */
    public static ChatAppearance effective(final ChatAppearance override, final ChatAppearance channelDefault) {
        final ChatAppearance mine = override == null ? NONE : override;
        final ChatAppearance theirs = channelDefault == null ? NONE : channelDefault;
        return new ChatAppearance(
                mine.backgroundColor == null ? theirs.backgroundColor : mine.backgroundColor,
                mine.backgroundImageUrl == null ? theirs.backgroundImageUrl : mine.backgroundImageUrl);
    }

    /**
     * A CSS colour, or null.
     *
     * <p>Deliberately narrow: {@code #rgb}, {@code #rrggbb} or a plain CSS colour keyword. Not a general CSS
     * parser — this value is interpolated into a style attribute, so anything containing a quote, a semicolon,
     * a brace or {@code url(} could close the declaration and start another one.
     */
    private static String safeColor(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        final String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.matches("#[0-9a-f]{3}") || value.matches("#[0-9a-f]{6}") || value.matches("[a-z]{3,20}")) {
            return value;
        }
        return null;
    }

    /** An {@code http}/{@code https} URL with nothing that could break out of {@code url('...')}, or null. */
    private static String safeImageUrl(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        final String value = raw.trim();
        final String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        // A quote, parenthesis, backslash, semicolon or whitespace would let the value escape url('...').
        for (final char c : value.toCharArray()) {
            if (c == '\'' || c == '"' || c == '(' || c == ')' || c == '\\' || c == ';' || Character.isWhitespace(c)) {
                return null;
            }
        }
        return value;
    }
}
