package org.paulsens.trip.content;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The string work behind the stylesheet editor's "Classes on this page" list and its completion
 * ({@code TripStylesheets.classReport}, docs/conference.md "Look and feel"): which class names a page's content
 * references, and which a stylesheet defines. Deliberately simple: an attribute scan and a selector scan, no CSS
 * parser, no specificity. Pure functions, unit-tested without a container.
 */
public final class CssClassScan {

    /** {@code class="a b"} and {@code class='a b'}, as the content editors' HTML and the templates write them. */
    private static final Pattern CLASS_ATTR = Pattern.compile("\\bclass\\s*=\\s*([\"'])(.*?)\\1",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** A class selector token inside a rule's prelude: {@code .name}, never inside a declaration block. */
    private static final Pattern CLASS_SELECTOR = Pattern.compile("\\.([A-Za-z_-][A-Za-z0-9_-]*)");
    /** A declaration block, removed before scanning selectors (a value such as {@code 1.5em} is no class). */
    private static final Pattern BLOCK = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * Class prefixes the theme, PrimeFaces, PrimeIcons and PrimeFlex own: never "custom", and never offered as
     * something a trip stylesheet should define.
     */
    static final List<String> FRAMEWORK_PREFIXES = List.of("ui-", "pi", "p-", "layout-", "grid", "col", "md:",
            "lg:", "sm:", "xl:", "flex", "text-", "mt-", "mb-", "ml-", "mr-", "p-", "m-", "w-", "h-", "justify-",
            "align-", "font-", "surface-", "bg-", "border-", "shadow-", "hidden", "block", "inline", "ql-");

    private CssClassScan() {
    }

    /** Every class name referenced by {@code html}'s {@code class} attributes, in first-seen order, no duplicates. */
    public static Set<String> classesIn(final String html) {
        final Set<String> out = new LinkedHashSet<>();
        if (html == null || html.isBlank()) {
            return out;
        }
        final Matcher attr = CLASS_ATTR.matcher(html);
        while (attr.find()) {
            for (final String name : attr.group(2).trim().split("\\s+")) {
                if (!name.isBlank() && !name.contains("#{")) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /** Every class name a stylesheet's selectors mention, in first-seen order, no duplicates. */
    public static Set<String> selectorsIn(final String css) {
        final Set<String> out = new LinkedHashSet<>();
        if (css == null || css.isBlank()) {
            return out;
        }
        // One pass removes the innermost blocks, the declarations (a value such as 1.5em is no class); a media
        // query's outer braces stay, and the selectors inside it with them.
        final String preludes = BLOCK.matcher(COMMENT.matcher(css).replaceAll(" ")).replaceAll(" ");
        final Matcher selector = CLASS_SELECTOR.matcher(preludes);
        while (selector.find()) {
            out.add(selector.group(1));
        }
        return out;
    }

    /** Whether a class name belongs to the theme or a framework rather than to the site's content vocabulary. */
    public static boolean isFrameworkClass(final String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        for (final String prefix : FRAMEWORK_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
