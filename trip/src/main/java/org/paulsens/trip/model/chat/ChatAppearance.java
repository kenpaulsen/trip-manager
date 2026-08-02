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
 * <p><b>A colour and an image are mutually exclusive.</b> An image covers the pane, so a colour chosen
 * underneath one is invisible — a person picking a colour and seeing no change has no way to tell that the
 * image is what is overriding them. One value, one visible result. When both arrive anyway (a row written
 * before this rule, or by hand) the colour wins, because the colour is picked from a closed list in the dialog
 * while the image is the field that also carries a site-wide default.
 *
 * <p>Held twice — as the channel default in {@link ChatSettings} and as a per-member override on
 * {@link ChatMembership} — and, because of the exclusivity above, chosen whole rather than merged per field:
 * see {@link #effective}. A member override is per channel, so a person can style each trip differently.
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
        // Exclusive, and resolved here so no caller can construct a combination the renderer would have to
        // arbitrate: a colour hidden under an image is a setting that silently does nothing.
        this.backgroundImageUrl = this.backgroundColor == null ? safeImageUrl(backgroundImageUrl) : null;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return backgroundColor == null && backgroundImageUrl == null;
    }

    /**
     * The member's choice, or the channel's when they have made none. Never null.
     *
     * <p>Whole, not merged per field. Under the exclusivity rule a per-field merge inverts the person's
     * intent in exactly the case that matters: someone choosing an image over a channel whose default is a
     * colour would merge to colour-plus-image, and the colour would win — so their pick would do nothing.
     */
    public static ChatAppearance effective(final ChatAppearance override, final ChatAppearance channelDefault) {
        final ChatAppearance mine = override == null ? NONE : override;
        return mine.isEmpty() ? (channelDefault == null ? NONE : channelDefault) : mine;
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
