package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.List;
import org.paulsens.trip.i18n.Text;

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

    // The catalog below holds message KEYS for a color's label, group and hint, so each reader gets them in the
    // language of their own request; worded() is where a key becomes words.
    static final String TEXT = "feedback.themeColors.groupText";
    static final String ACCENT = "feedback.themeColors.groupAccent";
    static final String SURFACES = "feedback.themeColors.groupSurfaces";
    static final String STATUS = "common.status";

    private static final List<ThemeColor> CATALOG = List.of(
            // No "muted text" entry: --text-color-secondary is off limits for new work (owner, 2026-11-15; the
            // platform default's light value is about 2.1:1 on white), so muted text is Text, set smaller.
            new ThemeColor("--text-color", "feedback.colors.text", TEXT,
                    "feedback.themeColors.textHint", true),
            new ThemeColor("--primary-color", "feedback.colors.accent", ACCENT,
                    "feedback.themeColors.accentHint", true),
            new ThemeColor("--primary-color-text", "feedback.themeColors.onAccent", ACCENT,
                    "feedback.themeColors.onAccentHint", true),
            new ThemeColor("--primary-dark-color", "feedback.themeColors.accentDark", ACCENT,
                    "feedback.themeColors.accentDarkHint", false),
            new ThemeColor("--primary-light-color", "feedback.themeColors.accentLight", ACCENT,
                    "feedback.themeColors.accentLightHint", false),
            new ThemeColor("--primary-lighter-color", "feedback.themeColors.accentSoft", ACCENT,
                    "feedback.themeColors.accentSoftHint", false),
            new ThemeColor("--surface-card", "feedback.themeColors.card", SURFACES,
                    "feedback.themeColors.cardHint", true),
            new ThemeColor("--surface-ground", "feedback.themeColors.ground", SURFACES,
                    "feedback.themeColors.groundHint", true),
            new ThemeColor("--surface-section", "feedback.themeColors.section", SURFACES,
                    "feedback.themeColors.sectionHint", false),
            new ThemeColor("--surface-hover", "feedback.themeColors.hover", SURFACES,
                    "feedback.themeColors.hoverHint", false),
            new ThemeColor("--surface-100", "feedback.themeColors.oneStepOff", SURFACES,
                    "feedback.themeColors.oneStepOffHint", false),
            new ThemeColor("--site-bg-default", "feedback.themeColors.pageTint", SURFACES,
                    "feedback.themeColors.pageTintHint", false),
            new ThemeColor("--surface-border", "feedback.themeColors.border", SURFACES,
                    "feedback.themeColors.borderHint", true),
            new ThemeColor("--maskbg", "feedback.themeColors.scrim", SURFACES,
                    "feedback.themeColors.scrimHint", false),
            new ThemeColor("--status-danger", "feedback.themeColors.danger", STATUS,
                    "feedback.themeColors.dangerHint", true),
            new ThemeColor("--status-danger-bg", "feedback.themeColors.dangerPanel", STATUS,
                    "feedback.themeColors.dangerPanelHint", false),
            new ThemeColor("--status-danger-strong", "feedback.themeColors.dangerFill", STATUS,
                    "feedback.themeColors.dangerFillHint", false),
            new ThemeColor("--status-danger-strong-text", "feedback.themeColors.onDangerFill", STATUS,
                    "feedback.themeColors.onDangerFillHint", false),
            new ThemeColor("--status-success", "feedback.themeColors.success", STATUS,
                    "feedback.themeColors.successHint", false),
            new ThemeColor("--status-success-bg", "feedback.themeColors.successPanel", STATUS,
                    "feedback.themeColors.successPanelHint", false),
            new ThemeColor("--status-success-strong", "feedback.themeColors.successFill", STATUS,
                    "feedback.themeColors.successFillHint", false),
            new ThemeColor("--status-success-strong-text", "feedback.themeColors.onSuccessFill", STATUS,
                    "feedback.themeColors.onSuccessFillHint", false),
            new ThemeColor("--status-warning", "feedback.themeColors.warning", STATUS,
                    "feedback.themeColors.warningHint", false),
            new ThemeColor("--status-warning-bg", "feedback.themeColors.warningPanel", STATUS,
                    "feedback.themeColors.warningPanelHint", false),
            new ThemeColor("--status-warning-strong", "feedback.themeColors.warningFill", STATUS,
                    "feedback.themeColors.warningFillHint", false),
            new ThemeColor("--status-warning-strong-text", "feedback.themeColors.onWarningFill", STATUS,
                    "feedback.themeColors.onWarningFillHint", false),
            new ThemeColor("--status-info", "feedback.themeColors.note", STATUS,
                    "feedback.themeColors.noteHint", false),
            new ThemeColor("--status-info-bg", "feedback.themeColors.notePanel", STATUS,
                    "feedback.themeColors.notePanelHint", false),
            new ThemeColor("--status-info-strong", "feedback.themeColors.noteFill", STATUS,
                    "feedback.themeColors.noteFillHint", false),
            new ThemeColor("--status-info-strong-text", "feedback.themeColors.onNoteFill", STATUS,
                    "feedback.themeColors.onNoteFillHint", false));

    private static final List<String> GROUP_ORDER = List.of(TEXT, ACCENT, SURFACES, STATUS);

    /** Every color in the catalog, in display order (the guide renders it through {@link #getGroups()}). */
    public List<ThemeColor> getAll() {
        return CATALOG.stream().map(ThemeColors::worded).toList();
    }

    /** One color by its property, or null: the ONE place a color's label lives ({@link EditorColors} reads it). */
    public static ThemeColor named(final String name) {
        return CATALOG.stream().filter(color -> color.name().equals(name)).findFirst().map(ThemeColors::worded)
                .orElse(null);
    }

    /** The colors the editor's strip shows by default; the rest are one click away under "More". */
    public List<ThemeColor> getStrip() {
        return CATALOG.stream().filter(ThemeColor::strip).map(ThemeColors::worded).toList();
    }

    /** The whole catalog by group, in the order a page author reaches for them. */
    public List<Group> getGroups() {
        return GROUP_ORDER.stream().map(ThemeColors::group).toList();
    }

    private static Group group(final String key) {
        return new Group(Text.get(key), CATALOG.stream().filter(color -> color.group().equals(key))
                .map(ThemeColors::worded).toList());
    }

    /** A catalog entry with its label, group and hint in the reader's language. */
    private static ThemeColor worded(final ThemeColor color) {
        return new ThemeColor(color.name(), Text.get(color.label()), Text.get(color.group()),
                Text.get(color.hint()), color.strip());
    }
}
