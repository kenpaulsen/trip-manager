package org.paulsens.trip.chat;

import java.util.List;
import org.paulsens.trip.model.chat.ChatMarkup;

/**
 * A chat body as mail HTML: the {@link ChatMarkup} subset rendered through an allowlist that emits only
 * {@code b i u s ul ol li br}, every text run escaped and autolinked by {@link ChatLinks#html}. Nothing a body
 * contains is ever copied into the output as markup, which is what lets the callers wrap the result in
 * {@link MailTemplates.Raw}; the design doc's rule for formatting is exactly this shape (a restricted markup
 * rendered through an allowlist, never user HTML through a denylist).
 *
 * <p>Paragraph lines are separated by {@code <br />}, so a multi-line body keeps its lines in a mail.
 */
public final class ChatBodyHtml {

    private ChatBodyHtml() {
    }

    /** Escape-safe HTML for the whole body. A null body renders as "". */
    public static String html(final String body) {
        final StringBuilder out = new StringBuilder();
        for (final ChatMarkup.Block block : ChatMarkup.parse(body)) {
            appendBlock(out, block);
        }
        return out.toString();
    }

    private static void appendBlock(final StringBuilder out, final ChatMarkup.Block block) {
        if (block instanceof ChatMarkup.Paragraph paragraph) {
            appendParagraph(out, paragraph.lines());
        } else if (block instanceof ChatMarkup.ListBlock list) {
            appendList(out, list);
        }
    }

    private static void appendParagraph(final StringBuilder out, final List<List<ChatMarkup.Inline>> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append("<br />");
            }
            appendInlines(out, lines.get(i));
        }
    }

    private static void appendList(final StringBuilder out, final ChatMarkup.ListBlock list) {
        if (!list.ordered()) {
            out.append("<ul>");
        } else if (list.start() == 1) {
            out.append("<ol>");
        } else {
            out.append("<ol start=\"").append(list.start()).append("\">");
        }
        for (final List<ChatMarkup.Inline> item : list.items()) {
            out.append("<li>");
            appendInlines(out, item);
            out.append("</li>");
        }
        out.append(list.ordered() ? "</ol>" : "</ul>");
    }

    private static void appendInlines(final StringBuilder out, final List<ChatMarkup.Inline> inlines) {
        for (final ChatMarkup.Inline inline : inlines) {
            if (inline instanceof ChatMarkup.Text text) {
                out.append(ChatLinks.html(text.text()));
            } else if (inline instanceof ChatMarkup.Styled styled) {
                final String tag = tagFor(styled.style());
                out.append('<').append(tag).append('>');
                appendInlines(out, styled.children());
                out.append("</").append(tag).append('>');
            }
        }
    }

    private static String tagFor(final ChatMarkup.Style style) {
        return switch (style) {
            case BOLD -> "b";
            case ITALIC -> "i";
            case UNDERLINE -> "u";
            case STRIKETHROUGH -> "s";
        };
    }
}
