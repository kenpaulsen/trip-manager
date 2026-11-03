package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.ContentRenderer;

/**
 * How a CONFERENCE's public page looks (docs/conference.md, "Look and feel"), on {@code Trip.page}. Every field
 * is nullable and null means "as the organization's Appearance page has it" (layout, background) or the page's
 * own default (no title over the hero, the section links shown, no stylesheet), so a trip stored before this
 * existed renders exactly as it did. The values are screened the way the org's settings are: the layout must be
 * one of {@link KnownSettings#SITE_LAYOUT}'s choices, the URLs {@code http(s)}, the color a hex value; whether a
 * stylesheet is a CSS file in the trip's org library needs the DAO and is {@code ConferenceCommands.pageProblems}'.
 */
@Data
public final class TripPageOptions implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** The dialog's layout and background radio values for "the organization's". */
    public static final String MODE_ORG = "org";
    public static final String BG_COLOR = "color";
    public static final String BG_IMAGE = "image";

    /** {@code classic} or {@code full-width} (the org setting's own values); null follows the org. */
    private String layout;
    /** An image behind the page; wins over the color, as on the Appearance page. Null: the org's background. */
    private String backgroundUrl;
    /** A hex color behind the page when there is no image. Null: the org's. */
    private String backgroundColor;
    /** Whether the trip's title renders over the hero; null reads as no (the hero band carries it). */
    private Boolean showTitle;
    /** Whether the strip of section links renders; null reads as yes. */
    private Boolean showNav;
    /** A stylesheet for this page alone: a CSS file in the trip's organization's media library. */
    private String stylesheetUrl;
    /**
     * The background radio's choice while its value is still blank. The mode is otherwise derived from the data,
     * and that alone made the dialog's Image and Color choices snap back to "the organization's" on the re-render
     * that was meant to show their fields (2026-11-02). Dialog state only: never stored, never compared.
     */
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private transient String backgroundChoice;

    @JsonCreator
    public TripPageOptions(
            @JsonProperty("layout") final String layout,
            @JsonProperty("backgroundUrl") final String backgroundUrl,
            @JsonProperty("backgroundColor") final String backgroundColor,
            @JsonProperty("showTitle") final Boolean showTitle,
            @JsonProperty("showNav") final Boolean showNav,
            @JsonProperty("stylesheetUrl") final String stylesheetUrl) {
        this.layout = trim(layout);
        this.backgroundUrl = trim(backgroundUrl);
        this.backgroundColor = trim(backgroundColor);
        this.showTitle = showTitle;
        this.showNav = showNav;
        this.stylesheetUrl = trim(stylesheetUrl);
    }

    public TripPageOptions() {
        this(null, null, null, null, null, null);
    }

    /** Whether every option is unset: the page is exactly what the org's Appearance page makes it. */
    @JsonIgnore
    public boolean isEmpty() {
        return layout == null && backgroundUrl == null && backgroundColor == null && showTitle == null
                && showNav == null && stylesheetUrl == null;
    }

    /**
     * Whether the page is full width: its own layout when set, else {@code orgFullWidth} (the org's
     * {@code site.layout}). An unknown stored value counts as classic, the org setting's own rule.
     */
    public boolean fullWidth(final boolean orgFullWidth) {
        return layout == null ? orgFullWidth : KnownSettings.LAYOUT_FULL_WIDTH.equals(layout);
    }

    @JsonIgnore
    public boolean isTitleShown() {
        return Boolean.TRUE.equals(showTitle);
    }

    @JsonIgnore
    public boolean isNavShown() {
        return showNav == null || showNav;
    }

    // --- the dialog's radios: a tri-state needs a value for "the organization's" ---

    /** The layout radio: {@value #MODE_ORG}, or the layout value itself. */
    @JsonIgnore
    public String getLayoutMode() {
        return layout == null ? MODE_ORG : layout;
    }

    public void setLayoutMode(final String mode) {
        layout = (mode == null || MODE_ORG.equals(mode)) ? null : trim(mode);
    }

    /**
     * The background radio: image when a URL is set, color when a color is, else the choice the editor made and
     * has yet to fill in, else {@value #MODE_ORG}.
     */
    @JsonIgnore
    public String getBackgroundMode() {
        if (backgroundUrl != null) {
            return BG_IMAGE;
        }
        if (backgroundColor != null) {
            return BG_COLOR;
        }
        return backgroundChoice == null ? MODE_ORG : backgroundChoice;
    }

    /** Choosing a mode clears what it does not use, as the Appearance page's Save does. */
    public void setBackgroundMode(final String mode) {
        backgroundChoice = (BG_IMAGE.equals(mode) || BG_COLOR.equals(mode)) ? mode : null;
        if (!BG_IMAGE.equals(mode)) {
            backgroundUrl = null;
        }
        if (!BG_COLOR.equals(mode)) {
            backgroundColor = null;
        }
    }

    /** Switch views for the dialog: null-safe booleans the toggles can bind to. */
    @JsonIgnore
    public boolean isTitleOn() {
        return isTitleShown();
    }

    public void setTitleOn(final boolean on) {
        showTitle = on ? Boolean.TRUE : null;
    }

    @JsonIgnore
    public boolean isNavOn() {
        return isNavShown();
    }

    public void setNavOn(final boolean on) {
        showNav = on ? null : Boolean.FALSE;
    }

    /** What is wrong with these options as the editor left them (the field-level rules); empty when fine. */
    public List<String> problems() {
        final List<String> problems = new ArrayList<>();
        if (layout != null && !KnownSettings.SITE_LAYOUT.allows(layout)) {
            problems.add("The page layout must be " + String.join(" or ", KnownSettings.SITE_LAYOUT.getChoices())
                    + ".");
        }
        // Link-shaped (http(s) or a site-relative path), not image-shaped: the media library's URLs are
        // site-relative wherever no media host is configured (locally), and both are fetched by the browser from
        // an attribute the renderer escapes.
        if (backgroundUrl != null && ContentRenderer.requireLinkUrl(backgroundUrl).isEmpty()) {
            problems.add("The background image must be an http(s) URL.");
        }
        if (backgroundColor != null && SettingDef.hexColor(backgroundColor) == null) {
            problems.add("The background color must be a hex color such as #336699.");
        }
        // The dialog's Done, not a stored row: a mode picked and left blank would silently save as the org's.
        if (BG_IMAGE.equals(backgroundChoice) && backgroundUrl == null) {
            problems.add("Choose a background image from the library, or paste its URL.");
        }
        if (BG_COLOR.equals(backgroundChoice) && backgroundColor == null) {
            problems.add("Choose a background color.");
        }
        if (stylesheetUrl != null && ContentRenderer.requireLinkUrl(stylesheetUrl).isEmpty()) {
            problems.add("The stylesheet must be an http(s) URL.");
        }
        return problems;
    }

    private static String trim(final String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
