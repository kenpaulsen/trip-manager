package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.BrandCommands;
import org.paulsens.trip.model.BrandColor;

/**
 * An organization's stored look, host-independent: what {@code BrandCommands} renders on the org's own
 * site, handed to a client that builds its own chrome. Every URL was screened the way the page screens it;
 * a value the org never chose is absent, so the client draws its neutral default rather than a blank.
 *
 * <p>{@code palette} is a stock palette's name, or {@code custom:<id>} for one of the org's OWN palettes; then
 * {@code customColors} carries its colors, never its admin-only name (absent for a stock palette), keyed by the
 * generated theme's input names ({@code primary-color}, {@code primary-text-color}, {@code shade000}, ...).
 * {@code brandColors} are the org's own named colors, which rich-text content may be colored with, and
 * {@code editorColors} the theme colors it may be colored with, each per mode.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgBrandingDto(
        String palette,
        boolean dark,
        String layout,
        String themeName,
        String logoUrl,
        String faviconUrl,
        String ogImageUrl,
        String backgroundUrl,
        String backgroundColor,
        String footerTitle,
        String footerText,
        String contactName,
        String contactPhone,
        String donateUrl,
        CustomColors customColors,
        List<BrandColorDto> brandColors,
        ModeColors editorColors) {

    /**
     * An org's own palette: both modes' colors, input name to {@code #rrggbb(aa)}. Colors only: they are on every
     * page of the org's public site, while the palette's NAME is admin-only (the /site look is public face only).
     */
    public record CustomColors(Map<String, String> light, Map<String, String> dark) {
    }

    /**
     * The editor theme colors in both modes, CSS property ({@code --status-danger}) to opaque {@code #rrggbb}. Its own
     * type, not {@link CustomColors}: the keys differ, and two adjacent arguments of one type could be swapped.
     */
    public record ModeColors(Map<String, String> light, Map<String, String> dark) {
    }

    /**
     * One of the org's brand colors: the custom property its content reads ({@code --brand-<id>}) and its value in
     * each mode ({@code dark} the same as {@code light} when the org set none). Never the color's NAME, which is
     * admin configuration.
     */
    public record BrandColorDto(String var, String light, String dark) {
    }

    /**
     * {@code customColors} is null for a stock palette or none; {@code brandColors} null when the org has none.
     * {@code editorColors} are the theme colors the rich-text editors write ({@code var(--status-danger, #...)}), per
     * mode, property to {@code #rrggbb}: a client declares them on a fragment's root so that text draws in its mode.
     */
    public static OrgBrandingDto of(final BrandCommands.OrgLook look, final String themeName,
            final CustomColors customColors, final List<BrandColor> brandColors, final ModeColors editorColors) {
        return new OrgBrandingDto(look.palette(), look.dark(), look.layout(), themeName, look.logoUrl(),
                look.faviconUrl(), look.ogImageUrl(), look.backgroundUrl(), look.backgroundColor(),
                look.footerTitle(), look.footerText(), look.contactName(), look.contactPhone(), look.donateUrl(),
                customColors, brandColors == null || brandColors.isEmpty() ? null : brandColors.stream()
                        .map(color -> new BrandColorDto(color.varName(), color.light(), color.valueFor(true)))
                        .toList(), editorColors);
    }
}
