package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.List;

/**
 * The ONE catalog of the theme colors a page author should write, exposed to pages as {@code #{themeColors}}:
 * each a CSS custom property every palette declares, with a short human label, its group and how it pairs.
 *
 * <p>The trip page's stylesheet editor draws its "Theme colors" strip from {@link #getStrip()} and its "More"
 * list from {@link #getGroups()}, and the stylesheet guide ({@code admin/help/stylesheet.xhtml}) renders the
 * whole catalog as a table. All three paint their swatches as {@code background:var(--name)}, so each shows the
 * palette the page is drawn in, with no script. A color written as a literal ignores the organization's palette
 * and dark mode (issue #26); writing one of these instead follows both.
 *
 * <p>The names are the Freya theme's own (the sibling repo's {@code freya/}), plus two the build adds:
 * {@code --site-bg-default} and the {@code --status-*} family. The webtest {@code ThemeContrastPwIT} checks that
 * every compiled palette, light and dark, declares every name listed here.
 */
@Named("themeColors")
@ApplicationScoped
public class ThemeColors {

    /**
     * One author-facing color: its property, label, group, pairing hint and whether the strip shows it. Pages write
     * {@code var(#{tc.name})}: a record exposes only its components to EL (Tomcat 11's record resolver), so a
     * computed getter here would fail there.
     */
    public record ThemeColor(String name, String label, String group, String hint, boolean strip) {
    }

    /** A group of colors for the "More" list and the guide's table. */
    public record Group(String name, List<ThemeColor> colors) {
    }

    static final String TEXT = "Text";
    static final String ACCENT = "Accent";
    static final String SURFACES = "Surfaces";
    static final String STATUS = "Status";

    private static final List<ThemeColor> CATALOG = List.of(
            // No "muted text" entry: --text-color-secondary is off limits for new work (owner, 2026-11-15; the
            // platform default's light value is about 2.1:1 on white), so muted text is Text, set smaller.
            new ThemeColor("--text-color", "Text", TEXT,
                    "Body text, on a card or the page ground. For quieter captions and dates, use Text set smaller.",
                    true),
            new ThemeColor("--primary-color", "Accent", ACCENT,
                    "The palette's main color. Text on it: On accent.", true),
            new ThemeColor("--primary-color-text", "On accent", ACCENT,
                    "Text and icons drawn ON the Accent color; never pair Accent with another text color.", true),
            new ThemeColor("--primary-dark-color", "Accent, dark", ACCENT, "A deeper Accent, for hover.", false),
            new ThemeColor("--primary-light-color", "Accent, light", ACCENT, "A paler Accent.", false),
            new ThemeColor("--primary-lighter-color", "Accent, soft", ACCENT,
                    "A faint wash of the Accent, for a quiet highlight behind Text.", false),
            new ThemeColor("--surface-card", "Card", SURFACES, "The background of a card or panel.", true),
            new ThemeColor("--surface-ground", "Ground", SURFACES, "The page's own background.", true),
            new ThemeColor("--surface-section", "Section", SURFACES, "A band set apart from the ground.", false),
            new ThemeColor("--surface-hover", "Hover", SURFACES, "A row or item under the pointer.", false),
            new ThemeColor("--surface-100", "One step off", SURFACES,
                    "One step off the card, for chips and table heads; stays one step off in dark mode.", false),
            new ThemeColor("--site-bg-default", "Page tint", SURFACES,
                    "The palette's tinted page background on an organization's site.", false),
            new ThemeColor("--surface-border", "Border", SURFACES, "Hairlines and dividers.", true),
            new ThemeColor("--maskbg", "Scrim", SURFACES, "The dimmed layer behind a dialog or over a photo.", false),
            new ThemeColor("--status-danger", "Danger", STATUS,
                    "Errors and problems that must stop the reader; readable on Card and Ground.", true),
            new ThemeColor("--status-danger-bg", "Danger panel", STATUS,
                    "A quiet panel behind an error note; Danger and Text both read on it.", false),
            new ThemeColor("--status-danger-strong", "Danger fill", STATUS,
                    "A solid chip or badge. Text on it: On danger fill.", false),
            new ThemeColor("--status-danger-strong-text", "On danger fill", STATUS,
                    "Text drawn on Danger fill.", false),
            new ThemeColor("--status-success", "Success", STATUS, "Done, paid, confirmed.", false),
            new ThemeColor("--status-success-bg", "Success panel", STATUS, "A quiet panel behind a success note.",
                    false),
            new ThemeColor("--status-success-strong", "Success fill", STATUS,
                    "A solid chip or badge. Text on it: On success fill.", false),
            new ThemeColor("--status-success-strong-text", "On success fill", STATUS,
                    "Text drawn on Success fill.", false),
            new ThemeColor("--status-warning", "Warning", STATUS, "Needs attention soon.", false),
            new ThemeColor("--status-warning-bg", "Warning panel", STATUS, "A quiet panel behind a warning.", false),
            new ThemeColor("--status-warning-strong", "Warning fill", STATUS,
                    "A solid chip or badge. Text on it: On warning fill.", false),
            new ThemeColor("--status-warning-strong-text", "On warning fill", STATUS,
                    "Text drawn on Warning fill.", false),
            new ThemeColor("--status-info", "Note", STATUS, "Neutral information.", false),
            new ThemeColor("--status-info-bg", "Note panel", STATUS, "A quiet panel behind a note.", false),
            new ThemeColor("--status-info-strong", "Note fill", STATUS,
                    "A solid chip or badge. Text on it: On note fill.", false),
            new ThemeColor("--status-info-strong-text", "On note fill", STATUS, "Text drawn on Note fill.", false));

    private static final List<String> GROUP_ORDER = List.of(TEXT, ACCENT, SURFACES, STATUS);

    /** Every color in the catalog, in display order (the guide renders it through {@link #getGroups()}). */
    public List<ThemeColor> getAll() {
        return CATALOG;
    }

    /** One color by its property, or null: the ONE place a color's label lives ({@link EditorColors} reads it). */
    public static ThemeColor named(final String name) {
        return CATALOG.stream().filter(color -> color.name().equals(name)).findFirst().orElse(null);
    }

    /** The colors the editor's strip shows by default; the rest are one click away under "More". */
    public List<ThemeColor> getStrip() {
        return CATALOG.stream().filter(ThemeColor::strip).toList();
    }

    /** The whole catalog by group, in the order a page author reaches for them. */
    public List<Group> getGroups() {
        return GROUP_ORDER.stream().map(ThemeColors::group).toList();
    }

    private static Group group(final String name) {
        return new Group(name, CATALOG.stream().filter(color -> color.group().equals(name)).toList());
    }
}
