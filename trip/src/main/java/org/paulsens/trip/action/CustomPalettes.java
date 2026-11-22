package org.paulsens.trip.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.CustomPalette;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.theme.ThemeColorMath;
import org.paulsens.trip.theme.ThemeResources;

/**
 * An organization's own color palettes, exposed to pages as {@code #{customPalettes}}: the reads that draw them
 * (their inputs in either mode, as the {@code --ct-*} style the generated {@code freya-custom-*} theme reads) and
 * the org-admin writes that keep them (create from a stock palette or another custom one, save, delete).
 *
 * <p>Which colors exist is not decided here: the freya build's generator writes
 * {@code freya-layout/custom-theme-inputs.json} with every input of the custom theme and every stock palette's
 * values for them, and this class reads it ({@link #manifest}). A clone therefore starts from exactly the colors the
 * stock palette compiles to, and a save refuses any color name the theme does not have. Without the manifest
 * (the theme jar missing a generated file) custom palettes are unavailable and the Appearance page offers none.
 *
 * <p>A palette's dark set is DERIVED ({@link #darkInputs}): the stock dark values of the palette it was cloned
 * from, with the accent family re-derived from the light accent when the admin changed it
 * ({@link ThemeColorMath#darkAccentFamily}), and the admin's own dark overrides on top. One derivation, here; the
 * palette editor asks for it over ajax rather than repeating it in the browser.
 *
 * <p>Tenancy: a palette belongs to the ONE org whose row holds it. Every write re-reads that org uncached, checks
 * {@link OrgCommands#canManageOrg}, and saves through {@link OrgCommands#saveOrganization} (its optimistic version
 * check); reads take an org id the page already guarded, and never search across orgs.
 */
@Slf4j
@Named("customPalettes")
@ApplicationScoped
public class CustomPalettes {

    /** The generator's manifest, relative to the theme jar's {@code META-INF/resources}. */
    static final String MANIFEST = "freya-layout/custom-theme-inputs.json";
    /** Palettes one organization may keep: a choice of looks, not a design library. */
    static final int MAX_PALETTES = 20;
    static final int MAX_NAME = 40;
    /**
     * The stock palette whose inputs name the theme's color set ({@link #inputNames}), and what a stored palette
     * whose source has left the manifest is drawn from. No new palette starts from it: a blank choice is the
     * platform's own palette, and an unknown one is refused.
     */
    static final String DEFAULT_SOURCE = "blue";
    /** The platform default's stock name in the manifest (the palette setting stores it as blank). */
    static final String PLATFORM_SOURCE = "medj";
    /** The palette editor page; {@link BrandCommands} draws it in the palette being edited. */
    static final String EDITOR_VIEW_ID = "/admin/orgPalette.xhtml";
    static final String EDITOR_URL = "/admin/orgPalette.jsf";
    /**
     * The editor's palette parameter. Not {@code id}: the page re-supplies it in a hidden field, and a form field
     * named {@code id} shadows the form element's own {@code id} property in the browser.
     */
    static final String EDITOR_PALETTE_PARAM = "paletteId";
    /** The editor's create-mode parameter: what an unsaved palette is cloned from (a stock name or custom:id). */
    static final String EDITOR_FROM_PARAM = "from";
    /** The id an unsaved (draft) palette carries until its first Save gives it a real one. */
    static final String DRAFT_ID = "new";

    /**
     * The editor's groups, in page order. These are IDS (the page asks for a group's fields by them), not words:
     * a heading reads {@link #groupLabelKey}.
     */
    public static final String GROUP_ACCENT = "Accent";
    public static final String GROUP_TEXT = "Text";
    public static final String GROUP_SURFACES = "Surfaces";
    public static final String GROUP_MENU = "Menu and more";

    /**
     * One color the palette editor offers: the theme input it sets, what it is called on the page, and its group.
     * The labels say what each input DRAWS, from Freya's own notes on its variables
     * ({@code sass/variables/theme/_theme_{light,dark}.scss}, {@code layout/_layout_*.scss}); the same input name
     * draws different things in the two modes ({@code shade000} is the card in light and the text in dark).
     */
    public record PaletteField(String input, String label, String group) {
    }

    /**
     * One readability check of the editor: a text color over the color behind it, named per mode as the custom
     * theme maps them, and the WCAG ratio it needs (4.5 for text; 3 for secondary text, which Freya uses for hints,
     * placeholders and icons, where the non-text rule applies).
     */
    public record ReadPair(String label, String fgLight, String bgLight, String fgDark, String bgDark, double min) {
    }

    // The three tables below hold each label as a message KEY; worded() turns one into the reader's words.
    static final List<ReadPair> READ_PAIRS = List.of(
            new ReadPair("feedback.customPalettes.textOnCards", "shade700", "shade000", "shade000", "shade800", 4.5),
            new ReadPair("feedback.customPalettes.textOnPageGround", "shade700", "body-bg-color", "shade000",
                    "body-bg-color", 4.5),
            new ReadPair("feedback.customPalettes.secondaryTextOnCards", "shade600", "shade000", "shade100",
                    "shade800", 3),
            new ReadPair("feedback.customPalettes.textOnAccent", ThemeColorMath.ON_ACCENT, ThemeColorMath.ACCENT,
                    ThemeColorMath.ON_ACCENT, ThemeColorMath.ACCENT, 4.5),
            new ReadPair("feedback.customPalettes.accentLinkOnCards", ThemeColorMath.ACCENT, "shade000",
                    ThemeColorMath.ACCENT, "shade800", 4.5));

    private static final List<PaletteField> LIGHT_FIELDS = List.of(
            new PaletteField(ThemeColorMath.ACCENT, "feedback.colors.accent", GROUP_ACCENT),
            new PaletteField(ThemeColorMath.ON_ACCENT, "feedback.customPalettes.textOnAccent", GROUP_ACCENT),
            new PaletteField(ThemeColorMath.HOVER, "feedback.customPalettes.accentHover", GROUP_ACCENT),
            new PaletteField(ThemeColorMath.PRESSED, "feedback.customPalettes.accentPressed", GROUP_ACCENT),
            new PaletteField(ThemeColorMath.SOFT, "feedback.customPalettes.accentSoft", GROUP_ACCENT),
            new PaletteField("shade700", "feedback.colors.text", GROUP_TEXT),
            new PaletteField("shade600", "feedback.customPalettes.secondaryText", GROUP_TEXT),
            new PaletteField("shade500", "feedback.customPalettes.inputIcons", GROUP_TEXT),
            new PaletteField("text-shade100", "feedback.customPalettes.menuText", GROUP_TEXT),
            new PaletteField("text-shade200", "feedback.customPalettes.menuTextQuieter", GROUP_TEXT),
            new PaletteField("text-shade300", "feedback.customPalettes.menuTextFaint", GROUP_TEXT),
            new PaletteField("text-secondary-color", "feedback.customPalettes.layoutSecondaryText", GROUP_TEXT),
            new PaletteField("body-bg-color", "feedback.customPalettes.pageGround", GROUP_SURFACES),
            new PaletteField("shade000", "feedback.customPalettes.cardsAndPanels", GROUP_SURFACES),
            new PaletteField("shade100", "feedback.customPalettes.panelHeaders", GROUP_SURFACES),
            new PaletteField("shade300", "feedback.customPalettes.borders", GROUP_SURFACES),
            new PaletteField("shade400", "feedback.customPalettes.inputBorders", GROUP_SURFACES),
            new PaletteField("divider-color", "feedback.customPalettes.dividers", GROUP_SURFACES),
            new PaletteField("content-shade100", "feedback.customPalettes.layoutPanels", GROUP_SURFACES),
            new PaletteField("content-shade200", "feedback.customPalettes.layoutPanelsTinted", GROUP_SURFACES),
            new PaletteField("content-shade300", "feedback.customPalettes.layoutPanelsSelected", GROUP_SURFACES),
            new PaletteField("content-shade400", "feedback.customPalettes.layoutPanelsQuiet", GROUP_SURFACES),
            new PaletteField("menu-bgcolor", "feedback.customPalettes.menuBackground", GROUP_MENU),
            new PaletteField("sub-menu-bgcolor", "feedback.customPalettes.submenuBackground", GROUP_MENU),
            new PaletteField("menu-tooltip-bg-color", "feedback.customPalettes.menuTooltip", GROUP_MENU),
            new PaletteField("menu-tooltip-text-color", "feedback.customPalettes.menuTooltipText", GROUP_MENU),
            new PaletteField("right-sidebar-bg", "feedback.customPalettes.rightSidebar", GROUP_MENU));

    private static final List<PaletteField> DARK_FIELDS = List.of(
            new PaletteField(ThemeColorMath.ACCENT, "feedback.colors.accent", GROUP_ACCENT),
            new PaletteField(ThemeColorMath.ON_ACCENT, "feedback.customPalettes.textOnAccent", GROUP_ACCENT),
            new PaletteField(ThemeColorMath.HOVER, "feedback.customPalettes.accentHover", GROUP_ACCENT),
            new PaletteField(ThemeColorMath.PRESSED, "feedback.customPalettes.accentPressed", GROUP_ACCENT),
            new PaletteField(ThemeColorMath.SOFT, "feedback.customPalettes.accentSoft", GROUP_ACCENT),
            new PaletteField("shade000", "feedback.colors.text", GROUP_TEXT),
            new PaletteField("shade100", "feedback.customPalettes.secondaryText", GROUP_TEXT),
            new PaletteField("text-shade100", "feedback.customPalettes.menuText", GROUP_TEXT),
            new PaletteField("text-shade200", "feedback.customPalettes.menuTextQuieter", GROUP_TEXT),
            new PaletteField("text-shade300", "feedback.customPalettes.menuTextFaint", GROUP_TEXT),
            new PaletteField("text-secondary-color", "feedback.customPalettes.layoutSecondaryText", GROUP_TEXT),
            new PaletteField("body-bg-color", "feedback.customPalettes.pageGround", GROUP_SURFACES),
            new PaletteField("shade900", "feedback.customPalettes.groundSurface", GROUP_SURFACES),
            new PaletteField("shade800", "feedback.customPalettes.cardsAndPanels", GROUP_SURFACES),
            new PaletteField("shade700", "feedback.customPalettes.alternateTableRows", GROUP_SURFACES),
            new PaletteField("shade600", "feedback.customPalettes.inputsBordersDividers", GROUP_SURFACES),
            new PaletteField("divider-color", "feedback.customPalettes.dividers", GROUP_SURFACES),
            new PaletteField("content-shade100", "feedback.customPalettes.layoutPanels", GROUP_SURFACES),
            new PaletteField("content-shade200", "feedback.customPalettes.layoutPanelsTinted", GROUP_SURFACES),
            new PaletteField("content-shade300", "feedback.customPalettes.layoutPanelsSelected", GROUP_SURFACES),
            new PaletteField("content-shade400", "feedback.customPalettes.layoutPanelsQuiet", GROUP_SURFACES),
            new PaletteField("menu-bgcolor", "feedback.customPalettes.menuBackground", GROUP_MENU),
            new PaletteField("sub-menu-bgcolor", "feedback.customPalettes.submenuBackground", GROUP_MENU),
            new PaletteField("menu-tooltip-bg-color", "feedback.customPalettes.menuTooltip", GROUP_MENU),
            new PaletteField("menu-tooltip-text-color", "feedback.customPalettes.menuTooltipText", GROUP_MENU),
            new PaletteField("right-sidebar-bg", "feedback.customPalettes.rightSidebar", GROUP_MENU));
    private static final List<String> ACCENT_KEYS = List.of(ThemeColorMath.ACCENT, ThemeColorMath.HOVER,
            ThemeColorMath.PRESSED, ThemeColorMath.SOFT, ThemeColorMath.ON_ACCENT);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> COLOR_MAP = new TypeReference<>() { };

    /**
     * The theme jar's manifest, parsed once for EVERY instance (the bean, BrandCommands', ThemePalettes'): stock
     * palette -> mode ("light"/"dark") -> input -> color. Kept only once read, so a jar without it is asked again.
     */
    private static final AtomicReference<Map<String, Map<String, Map<String, String>>>> SHARED_MANIFEST =
            new AtomicReference<>();

    /** An instance's own manifest, used only when a test set {@link #manifestSource}. */
    private final AtomicReference<Map<String, Map<String, Map<String, String>>>> manifest = new AtomicReference<>();

    private final Supplier<Caller> callerSource;
    private final OrgCommands orgs;

    /** Test seam: the manifest's text. Unset (null), the theme jar's file, read once for every instance. */
    @Setter
    private Supplier<String> manifestSource;

    /** Test seam: the brand bean a delete clears this admin's Appearance preview through. */
    @Setter
    private Supplier<BrandCommands> brandSource = BrandCommands::new;

    /** Test seam: an org by id, cached (default: the DAO through {@link OrgCommands#findOrganization}). */
    @Setter
    private Function<String, Organization> orgSource;

    public CustomPalettes() {
        this(Caller::current);
    }

    /** Test seam: {@code Caller.current()} needs a FacesContext. */
    public CustomPalettes(final Supplier<Caller> callerSource) {
        this.callerSource = callerSource;
        this.orgs = new OrgCommands(callerSource);
        // A lambda, not orgs::findOrganization: a method reference null-checks its target, which SpotBugs reads as a
        // constructor that can throw (CT_CONSTRUCTOR_THROW).
        this.orgSource = id -> this.orgs.findOrganization(id);
    }

    // ------------------------------------------------------------------ reads

    /** Whether custom palettes can be drawn at all (the theme jar carries the generated manifest). */
    public boolean isAvailable() {
        return !manifest().isEmpty();
    }

    /** The org's palettes in stored order; empty for an unknown org or none. */
    public List<CustomPalette> listFor(final String orgId) {
        final Organization org = orgId == null || orgId.isBlank() ? null : orgSource.apply(orgId);
        return palettesOf(org);
    }

    /** Whether the org may add another palette: what the Appearance page's New palette card is shown on. */
    public boolean canAdd(final String orgId) {
        return isAvailable() && listFor(orgId).size() < MAX_PALETTES;
    }

    /** One of the org's palettes by id, or null. */
    public CustomPalette find(final String orgId, final String id) {
        return paletteOf(orgId == null ? null : orgSource.apply(orgId), id);
    }

    /** One of the org's palettes by id, or null. */
    public static CustomPalette paletteOf(final Organization org, final String id) {
        for (final CustomPalette palette : palettesOf(org)) {
            if (palette.id().equals(id)) {
                return palette;
            }
        }
        return null;
    }

    /** A stock palette's inputs in one mode, as the generator read them; empty when unknown. */
    public Map<String, String> stockInputs(final String stock, final boolean dark) {
        final Map<String, Map<String, String>> modes = manifest().get(stock);
        final Map<String, String> inputs = modes == null ? null : modes.get(dark ? "dark" : "light");
        return inputs == null ? Map.of() : Collections.unmodifiableMap(inputs);
    }

    /** The input names one mode has (the light set and the dark set differ). */
    public Set<String> inputNames(final boolean dark) {
        return stockInputs(DEFAULT_SOURCE, dark).keySet();
    }

    /**
     * A palette's inputs in one mode: the stored light set, or the derived dark set with its overrides. Every value
     * is re-screened to {@code #rrggbb(aa)} and every name to a plain token (the save path checks both, but these
     * land in style attributes and a row written by hand bypasses that path); one that fails is left out, so the
     * theme's default shows for it.
     */
    public Map<String, String> inputs(final CustomPalette palette, final boolean dark) {
        final Map<String, String> out = new TreeMap<>();
        (dark ? darkInputs(palette) : palette.light()).forEach((input, value) -> screen(out, input, value));
        return out;
    }

    private static void screen(final Map<String, String> out, final String input, final String value) {
        final String color = ThemeColorMath.normalize(value);
        if (color != null && input.matches("[a-z0-9-]+")) {
            out.put(input, color);
        }
    }

    /**
     * The derived dark set: the source palette's stock dark values, the accent family re-derived when the light
     * accent family differs from the source's (a clone left alone keeps its source's dark accent exactly), then the
     * palette's own dark overrides.
     */
    public Map<String, String> darkInputs(final CustomPalette palette) {
        final String source = manifest().containsKey(palette.source()) ? palette.source() : DEFAULT_SOURCE;
        final Map<String, String> out = new TreeMap<>(stockInputs(source, true));
        final Map<String, String> sourceLight = stockInputs(source, false);
        if (ACCENT_KEYS.stream().anyMatch(key -> !same(palette.light().get(key), sourceLight.get(key)))) {
            out.putAll(ThemeColorMath.darkAccentFamily(palette.light()));
        }
        out.putAll(palette.darkOverrides());
        return out;
    }

    /**
     * The page style that draws a palette: one {@code --ct-<input>: <color>} per (screened) input.
     */
    public String styleOf(final CustomPalette palette, final boolean dark) {
        final StringBuilder out = new StringBuilder();
        inputs(palette, dark).forEach((input, color) -> out.append("--ct-").append(input).append(':').append(color)
                .append(';'));
        return out.toString();
    }

    /**
     * The derived dark set for an editor's UNSAVED light colors, as JSON: what the palette editor previews in dark
     * mode while the admin works in light, so the derivation exists once (here). Null for an unreadable request.
     */
    public String derivedDarkJson(final String source, final String lightJson, final String overridesJson) {
        final Map<String, String> light = parse(lightJson);
        final Map<String, String> overrides = parse(overridesJson);
        if (light == null || overrides == null) {
            return null;
        }
        final CustomPalette draft = new CustomPalette("draft", "draft", source, light, overrides);
        return write(darkInputs(draft));
    }

    /**
     * The colors the editor offers in one mode and group, in page order: the catalog above for every input the
     * generated theme has, then any input the catalog does not know yet (a later Freya) under its own name in
     * {@link #GROUP_MENU}, so a new input is editable the day the theme gains it.
     */
    public List<PaletteField> fields(final boolean dark, final String group) {
        final Set<String> inputs = inputNames(dark);
        final List<PaletteField> catalog = dark ? DARK_FIELDS : LIGHT_FIELDS;
        final List<PaletteField> out = new ArrayList<>();
        catalog.stream().filter(f -> f.group().equals(group) && inputs.contains(f.input()))
                .map(CustomPalettes::worded).forEach(out::add);
        if (GROUP_MENU.equals(group)) {
            final Set<String> named = new HashSet<>();
            catalog.forEach(f -> named.add(f.input()));
            inputs.stream().filter(input -> !named.contains(input))
                    .forEach(input -> out.add(new PaletteField(input, input, GROUP_MENU)));
        }
        return out;
    }

    /** The readability checks as JSON, for the editor's live checklist. */
    public String readabilityJson() {
        try {
            return JSON.writeValueAsString(READ_PAIRS.stream().map(CustomPalettes::worded).toList());
        } catch (final JsonProcessingException ex) {
            return "[]";
        }
    }

    /**
     * The readability checks a palette misses, in both modes, as "Text on the accent, light mode (2.9 to 1)": what a
     * save repeats after its confirmation. Empty when every pair reads.
     */
    public List<String> hardToRead(final CustomPalette palette) {
        final List<String> out = new ArrayList<>();
        for (final boolean dark : new boolean[] {false, true}) {
            final Map<String, String> in = inputs(palette, dark);
            for (final ReadPair pair : READ_PAIRS) {
                final double ratio = ThemeColorMath.contrast(in.get(dark ? pair.fgDark() : pair.fgLight()),
                        in.get(dark ? pair.bgDark() : pair.bgLight()));
                if (ratio > 0 && ratio < pair.min()) {
                    final String shown = String.format(Locale.ROOT, "%.1f", ratio);
                    out.add(dark ? Text.get("feedback.customPalettes.hardToReadDark", Text.get(pair.label()), shown)
                            : Text.get("feedback.customPalettes.hardToReadLight", Text.get(pair.label()), shown));
                }
            }
        }
        return out;
    }

    private static PaletteField worded(final PaletteField field) {
        return new PaletteField(field.input(), Text.get(field.label()), field.group());
    }

    private static ReadPair worded(final ReadPair pair) {
        return new ReadPair(Text.get(pair.label()), pair.fgLight(), pair.bgLight(), pair.fgDark(), pair.bgDark(),
                pair.min());
    }

    /** The editor's groups in page order. */
    public List<String> getGroups() {
        return List.of(GROUP_ACCENT, GROUP_TEXT, GROUP_SURFACES, GROUP_MENU);
    }

    /**
     * The message key of a group's heading, for {@code msg[...]} on the page (the light panel's fieldsets name the
     * same keys); the menu group's is page copy, read from the pages' bundle like any other key.
     */
    public String groupLabelKey(final String group) {
        return switch (group == null ? "" : group) {
            case GROUP_ACCENT -> "feedback.themeColors.groupAccent";
            case GROUP_TEXT -> "feedback.themeColors.groupText";
            case GROUP_SURFACES -> "feedback.themeColors.groupSurfaces";
            default -> "content.palette.menu";
        };
    }

    /** A palette's colors in one mode as JSON, for the editor's script ({@code {}} for none). */
    public String inputsJson(final CustomPalette palette, final boolean dark) {
        return palette == null ? "{}" : write(inputs(palette, dark));
    }

    /** A palette's dark overrides as JSON ({@code {}} for none). */
    public String overridesJson(final CustomPalette palette) {
        if (palette == null) {
            return "{}";
        }
        final Map<String, String> out = new TreeMap<>();
        palette.darkOverrides().forEach((input, value) -> screen(out, input, value));
        return write(out);
    }

    /** The editor's address for one of an org's palettes. */
    public static String editorUrl(final String orgId, final String id) {
        return EDITOR_URL + "?orgId=" + URLEncoder.encode(orgId, StandardCharsets.UTF_8)
                + "&" + EDITOR_PALETTE_PARAM + "=" + URLEncoder.encode(id, StandardCharsets.UTF_8);
    }

    /**
     * The editor's address for a NEW palette cloned from {@code from} (a stock card's param, the platform default's
     * {@link BrandCommands#PALETTE_CARD_NONE} or blank, or {@code custom:<id>} to duplicate one of the org's own):
     * what Customize, Duplicate and New palette redirect to. Nothing is stored here: the editor draws a draft, and
     * only its first Save creates the palette, so looking costs nothing and Cancel leaves nothing behind. Null
     * (growled) when the caller may not manage the org, it is at {@link #MAX_PALETTES}, or {@code from} names a
     * palette that no longer exists.
     */
    public String newEditorUrl(final String orgId, final String from) {
        final Organization fresh = editable(orgId);
        if (fresh == null || !roomFor(palettesOf(fresh)) || draftOrRefuse(fresh, from) == null) {
            return null;
        }
        return EDITOR_URL + "?orgId=" + URLEncoder.encode(orgId, StandardCharsets.UTF_8) + "&" + EDITOR_FROM_PARAM
                + "=" + URLEncoder.encode(sourceKey(from), StandardCharsets.UTF_8);
    }

    /** The unsaved palette the editor's create mode draws, or null (an unknown org or source). */
    public CustomPalette draft(final String orgId, final String from) {
        return orgId == null || orgId.isBlank() ? null : draftFor(orgSource.apply(orgId), from);
    }

    /**
     * A draft cloned from {@code from} for {@code org}: a stock palette's exact compiled colors, or a copy of one of
     * the org's own, with a name no palette of the org's has yet. Null when {@code from} names nothing that exists
     * (a palette deleted meanwhile is never silently swapped for another).
     */
    CustomPalette draftFor(final Organization org, final String from) {
        if (org == null) {
            return null;
        }
        final String key = sourceKey(from);
        final List<CustomPalette> palettes = palettesOf(org);
        final String customId = CustomPalette.idOf(key);
        if (customId != null) {
            final CustomPalette original = paletteOf(org, customId);
            return original == null ? null : new CustomPalette(DRAFT_ID, uniqueName(palettes, original.name()
                    + " copy"), original.source(), original.light(), original.darkOverrides());
        }
        if (!manifest().containsKey(key)) {
            return null;
        }
        final String base = ThemePalettes.label(PLATFORM_SOURCE.equals(key) ? ThemePalettes.PLATFORM : key);
        return new CustomPalette(DRAFT_ID, uniqueName(palettes, base + " (custom)"), key, stockInputs(key, false),
                Map.of());
    }

    /** The platform default's card sends {@link BrandCommands#PALETTE_CARD_NONE} (or nothing): its own palette. */
    private static String sourceKey(final String from) {
        return from == null || from.isBlank() || BrandCommands.PALETTE_CARD_NONE.equals(from) ? PLATFORM_SOURCE
                : from.trim();
    }

    private CustomPalette draftOrRefuse(final Organization org, final String from) {
        final CustomPalette draft = draftFor(org, from);
        if (draft == null && !isAvailable()) {
            PageFeedback.refuse(Text.get("feedback.orgCommands.unavailable"),
                    Text.get("feedback.customPalettes.notAvailableYet"));
        } else if (draft == null) {
            PageFeedback.refuse(Text.get("feedback.customPalettes.unknownPalette"),
                    Text.get("feedback.customPalettes.noLongerExists"));
        }
        return draft;
    }

    private static boolean roomFor(final List<CustomPalette> palettes) {
        return palettes.size() < MAX_PALETTES || PageFeedback.refuse(Text.get("feedback.customPalettes.tooMany"),
                Text.get("feedback.customPalettes.tooManyDetail", MAX_PALETTES));
    }

    /**
     * The palette editor's derivations over ajax: a {@code p:remoteCommand} posts the editor's unsaved
     * {@code source}, {@code light} and {@code overrides} (JSON), and the derived dark set comes back as the
     * callback parameter {@code dark}. With an {@code accent} too ("Match the accent shades"), the accent family
     * ({@link ThemeColorMath#accentFamily}) comes back as {@code family}, already folded into the light set the dark
     * one is derived from. Pure arithmetic over what was posted: nothing is read or written.
     */
    public void answerDerivedDark() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            derived(ctx.getExternalContext().getRequestParameterMap()).forEach(PageFeedback::callbackParam);
        }
    }

    /** {@link #answerDerivedDark}'s answer for its posted parameters: {@code dark}, and {@code family} when asked. */
    Map<String, String> derived(final Map<String, String> params) {
        final Map<String, String> out = new LinkedHashMap<>();
        final Map<String, String> family = ThemeColorMath.accentFamily(params.get("accent"));
        final Map<String, String> light = parse(params.get("light"));
        if (light == null) {
            return out;
        }
        light.putAll(family);
        final String dark = derivedDarkJson(params.get("source"), write(light), params.get("overrides"));
        if (dark != null) {
            out.put("dark", dark);
        }
        if (!family.isEmpty()) {
            out.put("family", write(family));
        }
        return out;
    }

    // ------------------------------------------------------------------ writes

    /**
     * The editor's first Save in create mode: stores a new palette with the editor's name and colors (the checks
     * {@link #save} applies), drawn from {@code source}, the stock palette the draft was settled on when the editor
     * opened. The source is settled THEN, not re-derived from {@code from} here: the draft's own colors are all in
     * the submission, so a palette duplicated and deleted meanwhile must not cost the admin their work. {@code from}
     * only names, in the audit record, what was cloned. Answers the editor's address for the new palette, carrying
     * the outcome as its {@code info} message; the ajax answer carries it too, as {@code goto} with {@code saved}:
     * the page's script lifts its leave-page guard and REPLACES the draft's address with it (a server redirect would
     * meet that guard first, and Back must not reopen a draft already stored). Null (growled, nothing stored) on any
     * refusal.
     */
    public String saveNew(final String orgId, final String from, final String source, final String name,
            final String lightJson, final String darkJson) {
        final Organization fresh = editable(orgId);
        if (fresh == null) {
            return null;
        }
        final List<CustomPalette> palettes = new ArrayList<>(palettesOf(fresh));
        if (!roomFor(palettes)) {
            return null;
        }
        if (source == null || !manifest().containsKey(source)) {
            PageFeedback.refuse(Text.get("feedback.shared.notSaved"),
                    Text.get("feedback.customPalettes.colorsNotRead"));
            return null;
        }
        final String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        final CustomPalette made = written(palettes, new CustomPalette(id, "", source, Map.of(), Map.of()), name,
                lightJson, darkJson);
        if (made == null) {
            return null;
        }
        palettes.add(made);
        fresh.setCustomPalettes(palettes);
        if (!orgs.saveOrganization(fresh)) {
            return null;
        }
        orgs.audit(callerSource.get(), fresh, "Color palette '" + made.name() + "' (" + id + ") created from "
                + sourceKey(from));
        final String next = editorUrl(orgId, id) + "&info=" + URLEncoder.encode(savedMessage(made),
                StandardCharsets.UTF_8);
        PageFeedback.callbackParam("saved", true);
        PageFeedback.callbackParam("goto", next);
        return next;
    }

    /**
     * Saves a palette's name and colors: {@code lightJson} every light input, {@code darkJson} the dark overrides
     * (empty for an automatic dark mode), each a JSON object of input name to color. Refuses (growl, nothing
     * written) an unknown palette, a blank or taken name, an unknown input or a value that is not a color. On
     * success the ajax answer carries {@code saved}, which is what clears the editor's unsaved-changes guard.
     */
    public boolean save(final String orgId, final String id, final String name, final String lightJson,
            final String darkJson) {
        final Organization fresh = editable(orgId);
        if (fresh == null) {
            return false;
        }
        final List<CustomPalette> palettes = new ArrayList<>(palettesOf(fresh));
        final int index = indexOf(palettes, id);
        if (index < 0) {
            return PageFeedback.refuse(Text.get("feedback.customPalettes.unknownPalette"),
                    Text.get("feedback.customPalettes.noLongerExists"));
        }
        final CustomPalette made = written(palettes, palettes.get(index), name, lightJson, darkJson);
        if (made == null) {
            return false;
        }
        palettes.set(index, made);
        fresh.setCustomPalettes(palettes);
        if (!orgs.saveOrganization(fresh)) {
            return false;
        }
        orgs.audit(callerSource.get(), fresh, "Color palette '" + made.name() + "' (" + id + ") saved");
        PageFeedback.info(savedMessage(made));
        PageFeedback.callbackParam("saved", true);
        return true;
    }

    /** {@code palette} with the submitted name and colors, checked; null (growled) when anything is refused. */
    private CustomPalette written(final List<CustomPalette> palettes, final CustomPalette palette, final String name,
            final String lightJson, final String darkJson) {
        final String cleanName = cleanName(name);
        if (cleanName == null) {
            PageFeedback.refuse(Text.get("feedback.customPalettes.nameNeeded"),
                    Text.get("feedback.customPalettes.nameNeededDetail", MAX_NAME));
            return null;
        }
        if (!nameFree(palettes, cleanName, palette.id())) {
            PageFeedback.refuse(Text.get("feedback.customPalettes.nameTaken"),
                    Text.get("feedback.customPalettes.nameTakenDetail", cleanName));
            return null;
        }
        final Map<String, String> light = cleanColors(parse(lightJson), inputNames(false), true);
        final Map<String, String> dark = cleanColors(parse(darkJson), inputNames(true), false);
        return light == null || dark == null ? null : palette.withName(cleanName).withColors(light, dark);
    }

    /** What a save says: the name, and every pair that is hard to read (saving one is allowed). */
    private String savedMessage(final CustomPalette palette) {
        final List<String> hard = hardToRead(palette);
        return hard.isEmpty() ? Text.get("feedback.customPalettes.saved", palette.name())
                : Text.get("feedback.customPalettes.savedHardToRead", palette.name(), String.join("; ", hard));
    }

    /**
     * Deletes a palette, from a page that must RELOAD to redraw (the Appearance page, the editor): answers the
     * Appearance tab's address carrying the outcome as its {@code info} message, or null (growled, nothing
     * deleted). {@code ref} is the palette's {@code custom:<id>} or its bare id. When the site uses it, its palette
     * setting is cleared in the same save; when this admin's unsaved Appearance preview chose it, the preview drops
     * it too, whichever page the delete came from, so that page's Save is not refused over a palette just deleted.
     */
    public String deleteAndReturn(final String orgId, final String ref) {
        final String id = CustomPalette.idOf(ref) == null ? ref : CustomPalette.idOf(ref);
        final String done = deleted(orgId, id);
        if (done == null) {
            return null;
        }
        final BrandCommands brand = brandSource.get();
        brand.forgetDeletedPalette(orgId, CustomPalette.REF_PREFIX + id);
        return brand.appearanceUrl(orgId) + "&info=" + URLEncoder.encode(done, StandardCharsets.UTF_8);
    }

    /** The delete itself: what to tell the admin, or null (growled) when nothing was deleted. */
    private String deleted(final String orgId, final String id) {
        final Organization fresh = editable(orgId);
        if (fresh == null) {
            return null;
        }
        final List<CustomPalette> palettes = new ArrayList<>(palettesOf(fresh));
        final int index = indexOf(palettes, id);
        if (index < 0) {
            PageFeedback.refuse(Text.get("feedback.customPalettes.unknownPalette"),
                    Text.get("feedback.customPalettes.noLongerExists"));
            return null;
        }
        final CustomPalette removed = palettes.remove(index);
        fresh.setCustomPalettes(palettes.isEmpty() ? null : palettes);
        final String setting = KnownSettings.SITE_THEME_PALETTE.getName();
        final boolean inUse = removed.ref().equals(fresh.getSettingsOverrides().get(setting));
        if (inUse) {
            fresh.getSettingsOverrides().remove(setting);
        }
        if (!orgs.saveOrganization(fresh)) {
            return null;
        }
        orgs.audit(callerSource.get(), fresh, "Color palette '" + removed.name() + "' (" + id + ") deleted"
                + (inUse ? "; the site was using it and now uses the platform default" : ""));
        return inUse ? Text.get("feedback.customPalettes.deletedWasInUse", removed.name())
                : Text.get("feedback.customPalettes.deleted", removed.name());
    }

    /** The editor's address for a palette card's {@code custom:<id>}, or null for a stock card. */
    public String editUrl(final String orgId, final String ref) {
        final String id = CustomPalette.idOf(ref);
        return id == null || orgId == null ? null : editorUrl(orgId, id);
    }

    // ------------------------------------------------------------------ helpers

    static List<CustomPalette> palettesOf(final Organization org) {
        return org == null || org.getCustomPalettes() == null ? List.of() : org.getCustomPalettes();
    }

    private Map<String, Map<String, Map<String, String>>> manifest() {
        final AtomicReference<Map<String, Map<String, Map<String, String>>>> cache =
                manifestSource == null ? SHARED_MANIFEST : manifest;
        final Map<String, Map<String, Map<String, String>>> known = cache.get();
        if (known != null) {
            return known;
        }
        final Map<String, Map<String, Map<String, String>>> loaded =
                readManifest(manifestSource == null ? ThemeResources.read(MANIFEST) : manifestSource.get());
        if (!loaded.isEmpty()) {
            cache.compareAndSet(null, loaded);
        }
        return loaded;
    }

    static Map<String, Map<String, Map<String, String>>> readManifest(final String text) {
        final Map<String, Map<String, Map<String, String>>> out = new TreeMap<>();
        if (text == null) {
            return out;
        }
        try {
            final JsonNode palettes = JSON.readTree(text).path("palettes");
            for (final Map.Entry<String, JsonNode> palette : palettes.properties()) {
                final Map<String, Map<String, String>> modes = new TreeMap<>();
                for (final Map.Entry<String, JsonNode> mode : palette.getValue().properties()) {
                    modes.put(mode.getKey(), JSON.convertValue(mode.getValue(), COLOR_MAP));
                }
                out.put(palette.getKey(), modes);
            }
        } catch (final JsonProcessingException | IllegalArgumentException ex) {
            log.warn("Unable to read the custom theme manifest", ex);
            out.clear();
        }
        return out;
    }

    /** A fresh org the caller may manage, or null (growled). */
    private Organization editable(final String orgId) {
        return OrgOwnedLists.editable(orgs, orgId, Text.get("feedback.customPalettes.adminsOnly"));
    }

    /**
     * The submitted colors, normalized, or null (growled) when one names an input this mode does not have or is
     * not a color. {@code complete}: every input must be present (the light set is stored whole).
     */
    static Map<String, String> cleanColors(final Map<String, String> colors, final Set<String> known,
            final boolean complete) {
        if (colors == null) {
            PageFeedback.refuse(Text.get("feedback.shared.notSaved"),
                    Text.get("feedback.customPalettes.colorsNotRead"));
            return null;
        }
        final Map<String, String> out = new TreeMap<>();
        for (final Map.Entry<String, String> entry : colors.entrySet()) {
            final String color = ThemeColorMath.normalize(entry.getValue());
            if (!known.contains(entry.getKey()) || color == null) {
                PageFeedback.refuse(Text.get("feedback.shared.notSaved"),
                        Text.get("feedback.customPalettes.notAColor", entry.getValue(), entry.getKey()));
                return null;
            }
            out.put(entry.getKey(), color);
        }
        if (complete && !out.keySet().containsAll(known)) {
            PageFeedback.refuse(Text.get("feedback.shared.notSaved"),
                    Text.get("feedback.customPalettes.colorsMissing"));
            return null;
        }
        return out;
    }

    private static Map<String, String> parse(final String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, COLOR_MAP);
        } catch (final JsonProcessingException ex) {
            return null;
        }
    }

    private static String write(final Map<String, String> map) {
        try {
            return JSON.writeValueAsString(map);
        } catch (final JsonProcessingException ex) {
            return null;
        }
    }

    static String cleanName(final String name) {
        return OrgOwnedLists.clean(name, MAX_NAME);
    }

    private static boolean nameFree(final List<CustomPalette> palettes, final String name, final String exceptId) {
        return OrgOwnedLists.free(palettes, CustomPalette::id, CustomPalette::name, name, exceptId);
    }

    private static String uniqueName(final List<CustomPalette> palettes, final String base) {
        final String trimmed = base.length() > MAX_NAME - 3 ? base.substring(0, MAX_NAME - 3) : base;
        String name = trimmed;
        for (int n = 2; !nameFree(palettes, name, null); n++) {
            name = trimmed + " " + n;
        }
        return name;
    }

    private static int indexOf(final List<CustomPalette> palettes, final String id) {
        for (int i = 0; i < palettes.size(); i++) {
            if (palettes.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean same(final String a, final String b) {
        final String na = ThemeColorMath.normalize(a);
        return na != null && na.equals(ThemeColorMath.normalize(b));
    }
}
