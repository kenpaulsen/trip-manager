package org.paulsens.trip.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.EmbedStyles;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BrandColor;
import org.paulsens.trip.model.CustomPalette;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.theme.ThemeResources;
import org.paulsens.trip.theme.ThemeVars;

/**
 * The colors the rich-text editors' text and highlight buttons offer (Quill's fixed grid of hex colors ignored the
 * org's palette and dark mode, issue #26), exposed to pages as {@code #{editorColors}} and read by
 * {@code WEB-INF/quillEditor.xhtml}: a few theme colors, then the organization's own {@link BrandColors}.
 *
 * <p>Each swatch carries its custom property AND the org's current light value for it, resolved here from the org's
 * theme ({@link ThemeVars}). A web editor inserts {@code var(--name, #rrggbb)}: the page follows the palette and dark
 * mode, and a place without the theme's variables (print, the shared site, and phone-app builds from before
 * 2026-11-16; current apps declare the REST look's {@link #modeValues} on their rich-text views) draws the fallback.
 * An email editor inserts the {@code #rrggbb} alone, since mail clients have no variables.
 *
 * <p>Tenancy: a brand color's NAME is its org's own configuration. The lists carry the brand swatches only for a
 * caller who belongs to that org (a member or one of its admins) or a site admin ({@link #mayName}); anyone else, on
 * any host, gets the theme colors alone. Labels come from {@link ThemeColors}, the ONE catalog.
 */
@Slf4j
@Named("editorColors")
@ApplicationScoped
public class EditorColors {

    /**
     * One editor swatch: what its tooltip says, the custom property it stands for, and that property's current light
     * value for the org as an opaque {@code #rrggbb} (composited over the org's card), or null when it does not
     * resolve to a plain color (then an email editor leaves the swatch out).
     */
    public record Swatch(String label, String name, String hex) {
    }

    /** The two lists, in toolbar order. */
    public record Lists(List<Swatch> text, List<Swatch> background) {
    }

    private static final String CARD = "--surface-card";
    private static final String CARD_FALLBACK = "#ffffff";
    private static final String CARD_FALLBACK_DARK = "#1c2023";
    /** The text color button's theme colors, by property; their labels are {@link ThemeColors}'. */
    private static final List<String> TEXT = List.of("--text-color", "--primary-color", "--status-danger",
            "--status-success", "--status-warning", "--status-info");
    /** The highlight button's theme colors: quiet panels Text stays readable on, in both modes. */
    private static final List<String> BACKGROUND = List.of("--primary-lighter-color", "--status-danger-bg",
            "--status-success-bg", "--status-warning-bg", "--status-info-bg", "--surface-100");
    /** Both lists, for {@link #modeValues}. */
    private static final List<String> ALL = Stream.concat(TEXT.stream(), BACKGROUND.stream())
            .toList();
    private static final ObjectMapper JSON = new ObjectMapper();
    /** A compiled theme's {@code :root} declarations, read once per theme name (only once read successfully). */
    private static final Map<String, Map<String, String>> THEME_VARS = new ConcurrentHashMap<>();

    /** Test seam: an org by id, cached (default: {@link OrgCommands#findOrganization}). */
    @Setter
    private Function<String, Organization> orgSource;

    /** Test seam: who is asking ({@code Caller.current()} needs a FacesContext). */
    @Setter
    private Supplier<Caller> callerSource = Caller::current;

    public EditorColors() {
        final OrgCommands orgs = new OrgCommands();
        this.orgSource = orgs::findOrganization;
    }

    /**
     * The editors' lists as JSON ({@code {"text":[...],"background":[...]}}) for {@code orgId}, or, when it is blank,
     * the org behind this request's host; with neither, the theme colors alone, resolved in the platform default.
     * The org's brand colors are in it only for a caller who may see their names ({@link #mayName}).
     */
    public String json(final String orgId) {
        final Organization org = orgFor(orgId);
        try {
            return JSON.writeValueAsString(lists(org, mayName(callerSource.get(), org)));
        } catch (final JsonProcessingException ex) {
            log.warn("Unable to write the editor colors", ex);
            return "{}";
        }
    }

    /**
     * Whether {@code caller} may see {@code org}'s brand color names: a site admin, one of the org's admins, or a
     * member of it. Cached reads only (the caller's person row holds the org ids): this runs on page renders.
     */
    static boolean mayName(final Caller caller, final Organization org) {
        if (org == null || caller == null || !caller.isAuthenticated()) {
            return false;
        }
        if (caller.isSiteAdmin() || org.isAdmin(caller.personId())) {
            return true;
        }
        final Person person = DAO.getInstance().getPerson(caller.personId(), Cached.YES).orElse(null);
        return person != null && person.getOrgIds().contains(org.getId());
    }

    /** The two lists for an org (null: the platform default theme), with its brand colors when {@code named}. */
    public static Lists lists(final Organization org, final boolean named) {
        final Map<String, String> vars = themeVars(org, false);
        final String card = card(vars, false);
        final List<Swatch> text = new ArrayList<>();
        final List<Swatch> background = new ArrayList<>();
        TEXT.forEach(name -> text.add(swatch(vars, card, name)));
        BACKGROUND.forEach(name -> background.add(swatch(vars, card, name)));
        if (named) {
            // The tenancy gate: brand color names only for the org's own people (json checks mayName).
            for (final BrandColor color : BrandColors.colorsOf(org)) {
                final Swatch own = new Swatch(color.name(), color.varName(), color.light());
                text.add(own);
                background.add(own);
            }
        }
        return new Lists(text, background);
    }

    /**
     * The editors' theme colors as plain values in one mode, property to opaque {@code #rrggbb}: what the phone apps
     * declare on the root of a rich-text fragment so editor colors draw in the right mode there too (they carry no
     * theme stylesheet). Translucent values are laid over the org's card in that mode; one that does not resolve is
     * left out. The brand colors are the REST look's {@code brandColors}, not repeated here.
     */
    public static Map<String, String> modeValues(final Organization org, final boolean dark) {
        final Map<String, String> vars = themeVars(org, dark);
        final String card = card(vars, dark);
        final Map<String, String> out = new LinkedHashMap<>();
        for (final String name : ALL) {
            final String value = ThemeVars.resolve(vars, name);
            if (value != null) {
                out.put(name, ThemeVars.over(value, card));
            }
        }
        return out;
    }

    /**
     * Every custom property the org's site declares in one mode, name to raw value: its theme's {@code :root}
     * blocks, its own palette's {@code --ct-*} colors when it uses one, and its brand colors.
     */
    public static Map<String, String> themeVars(final Organization org, final boolean dark) {
        final BrandCommands.OrgLook look = org == null ? null : new BrandCommands().lookOf(org);
        final String theme = BrandCommands.themeName(look == null ? null : look.palette(), dark);
        final Map<String, String> vars = new LinkedHashMap<>(THEME_VARS.computeIfAbsent(theme,
                EditorColors::readTheme));
        if (vars.isEmpty()) {
            THEME_VARS.remove(theme);   // a missing jar is asked again next time, not remembered
        }
        final CustomPalette custom = look == null ? null : look.customPalette();
        if (custom != null) {
            CustomPalettes.getInstance().inputs(custom, dark).forEach((input, color) -> vars.put("--ct-" + input,
                    color));
        }
        BrandColors.colorsOf(org).forEach(color -> vars.put(color.varName(), color.valueFor(dark)));
        return vars;
    }

    /** The org's card color in one mode, opaque: what the Appearance tab measures a brand color against. */
    static String cardOf(final Organization org, final boolean dark) {
        return card(themeVars(org, dark), dark);
    }

    private static String card(final Map<String, String> vars, final boolean dark) {
        final String card = ThemeVars.resolve(vars, CARD);
        final String fallback = dark ? CARD_FALLBACK_DARK : CARD_FALLBACK;
        return card == null ? fallback : ThemeVars.over(card, fallback);
    }

    private static Swatch swatch(final Map<String, String> vars, final String card, final String name) {
        final ThemeColors.ThemeColor color = ThemeColors.named(name);
        final String value = ThemeVars.resolve(vars, name);
        return new Swatch(color == null ? name : color.label(), name, value == null ? null
                : ThemeVars.over(value, card));
    }

    private static Map<String, String> readTheme(final String theme) {
        return ThemeVars.declarations(EmbedStyles.rootBlockOf(ThemeResources.themeCss(theme)));
    }

    private Organization orgFor(final String orgId) {
        if (orgId != null && !orgId.isBlank()) {
            return orgSource.apply(orgId);
        }
        final SiteContext site = SiteContext.current();
        return site.isOrg() ? orgSource.apply(site.orgId().getValue()) : null;
    }
}
