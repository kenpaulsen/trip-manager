package org.paulsens.trip.chat;

import com.sun.jsft.util.ELUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders {@code mailTemplates/*.tpl} for mail that is sent <b>off a request thread</b>.
 *
 * <p>Same files and same {@code #{}} syntax as the JSF-driven templates, but rendered through
 * {@link ELUtil#renderWithoutJsf} because there is no {@code FacesContext} on a scheduler thread — and
 * {@code ELUtil.eval} answers {@code null} there, which stringifies into a mail body reading "null".
 *
 * <p><b>Escaping is the reason this class exists rather than a bare call to the renderer.</b> EL has no output
 * encoding, and a chat body is arbitrary text typed by one pilgrim and rendered as HTML in another's inbox — the
 * same stored-XSS shape as the chat pane, in a place no administrator can reach to correct it. So:
 *
 * <ul>
 *   <li>every {@code String} value is HTML-escaped automatically, making safety the default;</li>
 *   <li>markup the caller built itself must be wrapped in {@link Raw}, so bypassing the escape is deliberate,
 *       greppable, and visible at the call site;</li>
 *   <li><b>domain objects are rejected outright.</b> Binding a {@code Person} and writing
 *       {@code #{person.first}} would resolve straight through the escape with no way to notice — the value never
 *       passes through this class. Templates for off-thread mail therefore take flat, pre-escaped scalars.</li>
 * </ul>
 */
@Slf4j
public final class MailTemplates {

    private static final String PREFIX = "mailTemplates/";
    private static final String SUFFIX = ".tpl";

    private MailTemplates() {
    }

    /** Markup the caller assembled and has already escaped the parts of. Rendered verbatim. */
    public record Raw(String html) {
        @Override
        public String toString() {
            return html == null ? "" : html;
        }
    }

    /**
     * Renders a named template.
     *
     * @param templateName bare name; {@code mailTemplates/} and {@code .tpl} are added
     * @param values       {@code String} (escaped), {@link Raw} (verbatim), {@code Number} or {@code Boolean}
     * @return the rendered body, or {@code null} when the template is missing or fails to render — callers must
     *     treat {@code null} as "do not send", because sending a half-rendered mail is worse than sending none
     */
    public static String render(final String templateName, final Map<String, Object> values) {
        final String template = ELUtil.getInstance().readFile(PREFIX + templateName + SUFFIX);
        if (template == null) {
            log.error("Mail template not found: {}{}{}", PREFIX, templateName, SUFFIX);
            return null;
        }
        // Validated OUTSIDE the try, so the two failure classes stay distinguishable. Passing a domain object is a
        // programming error and must be loud; a template that will not render is a content problem and is contained
        // below. Folding them together turned "you bound a Person" into a silent no-send.
        final Map<String, Object> safe = safeValues(values);
        try {
            return ELUtil.getInstance().renderWithoutJsf(template, safe);
        } catch (final RuntimeException ex) {
            // A missing property throws. One broken template must not take down a whole digest run, and it must not
            // silently produce a mail with a blank where a name belongs.
            log.error("Unable to render mail template {}", templateName, ex);
            return null;
        }
    }

    /** HTML-escapes text for inclusion in a mail body. */
    public static String escape(final String text) {
        if (text == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Escapes strings, unwraps {@link Raw}, and refuses anything else.
     *
     * <p>The refusal is the point: a rejected template render is a bug caught in a test, while a silently accepted
     * domain object is unescaped user text in someone's inbox.
     */
    private static Map<String, Object> safeValues(final Map<String, Object> values) {
        final Map<String, Object> out = new LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        values.forEach((name, value) -> out.put(name, safeValue(name, value)));
        return out;
    }

    private static Object safeValue(final String name, final Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Raw raw) {
            return raw.html() == null ? "" : raw.html();
        }
        if (value instanceof String text) {
            return escape(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("Mail template value '" + name + "' is a "
                + value.getClass().getName() + ". Off-thread mail templates take escaped scalars only: pass a "
                + "String (escaped for you), a Number/Boolean, or MailTemplates.Raw for markup you built. Binding a "
                + "domain object lets #{obj.field} emit unescaped user text.");
    }
}
