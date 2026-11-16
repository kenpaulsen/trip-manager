package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Setter;
import org.paulsens.trip.model.BrandColor;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.theme.ThemeColorMath;
import org.paulsens.trip.theme.ThemeVars;

/**
 * An organization's own named colors, exposed to pages as {@code #{brandColors}}: the Appearance tab lists, adds,
 * edits and deletes them; the rich-text editors and the trip page style editor offer them beside the theme colors
 * ({@link EditorColors}); {@link BrandCommands} sets each on the org site's pages as {@code --brand-<id>}.
 *
 * <p>Tenancy: a color belongs to the ONE org whose row holds it. Every write re-reads that org uncached, checks
 * {@link OrgCommands#canManageOrg}, and saves through {@link OrgCommands#saveOrganization} (its optimistic version
 * check), the {@link CustomPalettes} pattern; reads take an org id the page already guarded.
 */
@Named("brandColors")
@ApplicationScoped
public class BrandColors {

    /** Colors one organization may keep: a short brand set, not a design library (and every editor lists them). */
    static final int MAX_COLORS = 12;
    static final int MAX_NAME = 30;
    /** The least contrast text needs (WCAG AA); the Appearance tab notes a color under it, and saves it anyway. */
    static final double READABLE = 4.5;
    /** A new color's starting value when the org's accent cannot be read: a warm gold. */
    static final String STARTER = "#a07a2c";

    private final Supplier<Caller> callerSource;
    private final OrgCommands orgs;

    /** Test seam: an org by id, cached (default: the DAO through {@link OrgCommands#findOrganization}). */
    @Setter
    private Function<String, Organization> orgSource;

    public BrandColors() {
        this(Caller::current);
    }

    /** Test seam: {@code Caller.current()} needs a FacesContext. */
    public BrandColors(final Supplier<Caller> callerSource) {
        this.callerSource = callerSource;
        this.orgs = new OrgCommands(callerSource);
        // A lambda, not orgs::findOrganization: a method reference null-checks its target, which SpotBugs reads as a
        // constructor that can throw (CT_CONSTRUCTOR_THROW).
        this.orgSource = id -> this.orgs.findOrganization(id);
    }

    // ------------------------------------------------------------------ reads

    /** The org's colors in stored order; empty for an unknown org or none. */
    public List<BrandColor> listFor(final String orgId) {
        return colorsOf(orgId == null || orgId.isBlank() ? null : orgSource.apply(orgId));
    }

    /** Whether the org may add another color: what the Appearance tab's Add button is shown on. */
    public boolean canAdd(final String orgId) {
        return listFor(orgId).size() < MAX_COLORS;
    }

    /** One of the org's colors by id, or null. */
    public BrandColor find(final String orgId, final String id) {
        return listFor(orgId).stream().filter(color -> color.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * The Edit link's read: the color, with {@code found} in the ajax answer so the page opens its dialog, or null
     * after growling that it no longer exists (another admin deleted it meanwhile), so the dialog stays shut rather
     * than opening empty and saving what is typed as a NEW color.
     */
    public BrandColor findForEdit(final String orgId, final String id) {
        final BrandColor found = find(orgId, id);
        if (found == null) {
            PageFeedback.refuse("Unknown color", "That color no longer exists. Reload the page.");
            return null;
        }
        PageFeedback.callbackParam("found", true);
        return found;
    }

    /**
     * The org's colors as they may reach a page: each value re-screened to {@code #rrggbb} (the save path checks
     * them, but they land in style attributes and a row written by hand bypasses that path); a color whose light
     * value fails is left out, a dark value that fails reads as none.
     */
    public static List<BrandColor> colorsOf(final Organization org) {
        if (org == null || org.getBrandColors() == null) {
            return List.of();
        }
        final List<BrandColor> out = new ArrayList<>();
        for (final BrandColor color : org.getBrandColors()) {
            final String light = opaque(color.light());
            if (light != null && color.id() != null && color.id().matches("[a-z0-9]+")) {
                out.add(new BrandColor(color.id(), color.name(), light, opaque(color.dark())));
            }
        }
        return out;
    }

    /** The custom property declarations for an org's colors in one mode: {@code --brand-<id>:#rrggbb;} each. */
    public static String styleOf(final Organization org, final boolean dark) {
        final StringBuilder out = new StringBuilder();
        for (final BrandColor color : colorsOf(org)) {
            out.append(color.varName()).append(':').append(color.valueFor(dark)).append(';');
        }
        return out.toString();
    }

    /**
     * What the Appearance tab says under a color: how it reads as text on the org's cards in each mode, and which
     * mode is hard to read (saving such a color is allowed: a highlight may be meant for backgrounds).
     */
    public String readNote(final String orgId, final BrandColor color) {
        final Organization org = orgId == null ? null : orgSource.apply(orgId);
        final double light = ThemeColorMath.contrast(color.light(), EditorColors.cardOf(org, false));
        final double dark = ThemeColorMath.contrast(color.valueFor(true), EditorColors.cardOf(org, true));
        return "As text on cards: " + ratio(light, "light") + ", " + ratio(dark, "dark") + ".";
    }

    private static String ratio(final double value, final String mode) {
        return String.format(Locale.ROOT, "%.1f to 1 in %s", value, mode) + (value < READABLE ? " (hard to read)" : "");
    }

    /**
     * What a new color's picker starts on: the org's accent in light mode (a brand color is often a shade of it), or
     * {@link #STARTER} when the theme cannot be read.
     */
    public String starterColor(final String orgId) {
        final Organization org = orgId == null || orgId.isBlank() ? null : orgSource.apply(orgId);
        final String accent = opaque(ThemeVars.resolve(EditorColors.themeVars(org, false), "--primary-color"));
        return accent == null ? STARTER : accent;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Adds a color ({@code id} blank) or changes one: its name, light value and optional dark value (blank = the
     * light one in both modes). Refuses (growl, nothing written) a caller who cannot manage the org, an unknown id, a
     * blank, long or taken name, a value that is not an opaque {@code #rrggbb}, or a 13th color.
     */
    public boolean save(final String orgId, final String id, final String name, final String light,
            final String dark) {
        final Organization fresh = editable(orgId);
        if (fresh == null) {
            return false;
        }
        final List<BrandColor> colors = new ArrayList<>(colorsOf(fresh));
        final boolean adding = id == null || id.isBlank();
        final int index = adding ? -1 : indexOf(colors, id);
        if (!adding && index < 0) {
            return PageFeedback.refuse("Unknown color", "That color no longer exists. Reload the page.");
        }
        if (adding && colors.size() >= MAX_COLORS) {
            return PageFeedback.refuse("Too many colors", "An organization can keep up to " + MAX_COLORS
                    + " brand colors. Delete one first.");
        }
        final String cleanName = cleanName(name);
        if (cleanName == null) {
            return PageFeedback.refuse("Name needed", "Give the color a name of at most " + MAX_NAME + " characters.");
        }
        if (!nameFree(colors, cleanName, adding ? null : id)) {
            return PageFeedback.refuse("Name taken", "This organization already has a color called \"" + cleanName
                    + "\". Choose another name.");
        }
        final String lightValue = opaque(light);
        final String darkValue = dark == null || dark.isBlank() ? null : opaque(dark);
        if (lightValue == null || (dark != null && !dark.isBlank() && darkValue == null)) {
            return PageFeedback.refuse("Not saved", "Choose each color with the color picker (a value like #a07a2c).");
        }
        final String newId = adding ? UUID.randomUUID().toString().replace("-", "").substring(0, 8) : id;
        final BrandColor made = new BrandColor(newId, cleanName, lightValue, darkValue);
        if (adding) {
            colors.add(made);
        } else {
            colors.set(index, made);
        }
        fresh.setBrandColors(colors);
        if (!orgs.saveOrganization(fresh)) {
            return false;
        }
        orgs.audit(callerSource.get(), fresh, "Brand color '" + cleanName + "' (" + newId + ") "
                + (adding ? "added" : "saved"));
        PageFeedback.info("Saved \"" + cleanName + "\".");
        PageFeedback.callbackParam("saved", true);
        return true;
    }

    /**
     * Deletes a color. Text already colored with it keeps the light value it carries as its fallback, so nothing
     * stored needs rewriting; it just stops following dark mode.
     */
    public boolean delete(final String orgId, final String id) {
        final Organization fresh = editable(orgId);
        if (fresh == null) {
            return false;
        }
        final List<BrandColor> colors = new ArrayList<>(colorsOf(fresh));
        final int index = indexOf(colors, id);
        if (index < 0) {
            return PageFeedback.refuse("Unknown color", "That color no longer exists. Reload the page.");
        }
        final BrandColor removed = colors.remove(index);
        fresh.setBrandColors(colors.isEmpty() ? null : colors);
        if (!orgs.saveOrganization(fresh)) {
            return false;
        }
        orgs.audit(callerSource.get(), fresh, "Brand color '" + removed.name() + "' (" + id + ") deleted");
        PageFeedback.info("Deleted \"" + removed.name() + "\".");
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /** A fresh org the caller may manage, or null (growled). */
    private Organization editable(final String orgId) {
        return OrgOwnedLists.editable(orgs, orgId, "brand colors");
    }

    /** {@code #rrggbb}, or null for anything else (translucent colors are refused: a brand color is a solid ink). */
    static String opaque(final String value) {
        final String color = ThemeColorMath.normalize(value);
        return color == null || color.length() != 7 ? null : color;
    }

    static String cleanName(final String name) {
        return OrgOwnedLists.clean(name, MAX_NAME);
    }

    private static boolean nameFree(final List<BrandColor> colors, final String name, final String exceptId) {
        return OrgOwnedLists.free(colors, BrandColor::id, BrandColor::name, name, exceptId);
    }

    private static int indexOf(final List<BrandColor> colors, final String id) {
        for (int i = 0; i < colors.size(); i++) {
            if (colors.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
