package org.paulsens.trip.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the http(s) URLs in a chat body into anchors for the HTML surfaces that show one (the mention, reply and
 * digest mails). The browser pane and the iOS app apply the same two rules in their own languages, so a body reads
 * the same everywhere: a link starts at {@code http://} or {@code https://} and runs to the next whitespace, and
 * the punctuation a sentence hangs on the end ({@code .,;:!?} and closing quotes/brackets) stays outside it.
 *
 * <p>Output is escape-safe HTML: every character outside an anchor is escaped, the href and the anchor text are
 * escaped, and nothing but a {@code https?://} token can become an anchor, so a body can never smuggle a
 * {@code javascript:} link or an attribute in. That is what lets the callers wrap the result in
 * {@link MailTemplates.Raw}.
 */
public final class ChatLinks {

    /** A URL token: scheme plus everything up to whitespace. Trailing punctuation is trimmed afterwards. */
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    /** Characters that end a sentence around a URL rather than belonging to it. */
    private static final String TRAILING = ".,;:!?'\")]}>…";

    private static final String ANCHOR_ATTRS = " target=\"_blank\" rel=\"noopener noreferrer\"";

    private ChatLinks() {
    }

    /**
     * Escaped HTML with each URL wrapped in an anchor that opens in a new window. A null body renders as "".
     */
    public static String html(final String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder(body.length() + 64);
        final Matcher links = URL.matcher(body);
        int copied = 0;
        while (links.find()) {
            final String url = trimTrailing(links.group());
            if (url.indexOf("//") + 2 >= url.length()) {
                // A bare scheme ("see https://.") is prose, not a link; leave it for the text run.
                continue;
            }
            out.append(MailTemplates.escape(body.substring(copied, links.start())));
            final String escaped = MailTemplates.escape(url);
            out.append("<a href=\"").append(escaped).append('"').append(ANCHOR_ATTRS).append('>')
                    .append(escaped).append("</a>");
            copied = links.start() + url.length();
        }
        out.append(MailTemplates.escape(body.substring(copied)));
        return out.toString();
    }

    /** The URL without the sentence punctuation that follows it; "" when nothing but punctuation is left. */
    static String trimTrailing(final String token) {
        int end = token.length();
        while (end > 0 && TRAILING.indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(0, end);
    }
}
