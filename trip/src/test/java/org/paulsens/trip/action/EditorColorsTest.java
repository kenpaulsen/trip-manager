package org.paulsens.trip.action;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BrandColor;
import org.paulsens.trip.model.CustomPalette;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.site.SiteContext;
import org.testng.annotations.Test;

/**
 * The rich-text editors' color lists: the theme colors with the org's current light value as each one's fallback
 * (read from the test class path's stand-in for the compiled blue theme), then the org's brand colors, and the org a
 * page without one of its own falls back to (its host's).
 */
public class EditorColorsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void theThemeColorsComeFirstWithTheOrgsValues() {
        final Organization org = orgIn("blue");
        org.setBrandColors(List.of(new BrandColor("gold1", "Parish gold", "#a07a2c", "#e0b85c")));
        final EditorColors.Lists lists = EditorColors.lists(org, true);
        assertEquals(lists.text().get(0), new EditorColors.Swatch("Text", "--text-color", "#69707a"));
        assertEquals(swatch(lists.text(), "--status-danger").hex(), "#d42f2f", "followed through the ramp");
        assertNull(swatch(lists.text(), "--status-warning").hex(), "not in the stand-in theme: no fallback");
        assertEquals(swatch(lists.background(), "--primary-lighter-color").hex(), "#f0f8ff",
                "a translucent color is composited over the card, so email can use it");
        final EditorColors.Swatch gold = new EditorColors.Swatch("Parish gold", "--brand-gold1", "#a07a2c");
        assertEquals(lists.text().get(lists.text().size() - 1), gold, "the brand colors come last, in both lists");
        assertEquals(lists.background().get(lists.background().size() - 1), gold);
        assertEquals(lists.text().size(), 7);
        assertEquals(lists.background().size(), 7);
        assertEquals(swatch(lists.background(), "--primary-lighter-color").label(), "Accent, soft",
                "labels come from the ThemeColors catalog");
        final EditorColors.Lists unnamed = EditorColors.lists(org, false);
        assertEquals(unnamed.text().size(), 6, "without the right to see names: the theme colors alone");
        assertTrue(unnamed.background().stream().noneMatch(s -> s.name().startsWith("--brand-")));
    }

    @Test
    public void modeValuesGiveTheAppsBothModes() {
        final Organization org = orgIn("blue");
        final Map<String, String> light = EditorColors.modeValues(org, false);
        assertEquals(light.get("--text-color"), "#69707a");
        assertEquals(light.get("--primary-lighter-color"), "#f0f8ff", "translucent, laid over the card");
        assertTrue(light.keySet().stream().noneMatch(name -> name.startsWith("--brand-")), "brand colors are apart");
        final Map<String, String> dark = EditorColors.modeValues(org, true);
        assertEquals(dark.get("--text-color"), "#dee2e6", "the DARK theme's values, for the apps' dark mode");
        assertEquals(dark.get("--status-warning-bg"), "#3a2f1e", "a highlight dark text stays readable on");
        assertEquals(dark.get("--primary-lighter-color"), "#283846", "translucent, laid over the DARK card");
        assertEquals(EditorColors.modeValues(null, true), Map.of(), "no theme on the class path: nothing resolves");
    }

    @Test
    public void onlyTheOrgsOwnPeopleSeeItsColorNames() throws Exception {
        final DAO dao = DAO.getInstance();
        final Person member = person(dao);
        final Person stranger = person(dao);
        final Person admin = person(dao);
        final Organization org = orgIn("blue");
        org.setAdminIds(List.of(admin.getId()));
        member.setOrgIds(List.of(org.getId()));
        assertTrue(dao.savePerson(member));
        assertTrue(EditorColors.mayName(caller(member, false), org), "a member");
        assertTrue(EditorColors.mayName(caller(admin, false), org), "an admin");
        assertTrue(EditorColors.mayName(caller(stranger, true), org), "a site admin");
        assertFalse(EditorColors.mayName(caller(stranger, false), org), "someone from another org");
        assertFalse(EditorColors.mayName(new Caller(null, false, null, null), org), "nobody signed in");
        assertFalse(EditorColors.mayName(caller(member, false), null));

        org.setBrandColors(List.of(new BrandColor("navy1", "Navy", "#123456", null)));
        final EditorColors colors = new EditorColors();
        colors.setOrgSource(id -> org);
        colors.setCallerSource(() -> caller(stranger, false));
        assertFalse(colors.json(org.getId().getValue()).contains("Navy"), "a stranger gets no names");
        colors.setCallerSource(() -> caller(member, false));
        assertTrue(colors.json(org.getId().getValue()).contains("Navy"));
    }

    @Test
    public void anOrgsOwnPaletteResolvesThroughItsInputs() {
        final CustomPalette ours = new CustomPalette("p1", "Ours", "blue", Map.of("primary-color", "#8a2be2",
                "shade000", "#fefefe"), Map.of());
        final Organization org = orgIn(ours.ref());
        org.setCustomPalettes(List.of(ours));
        final Map<String, String> vars = EditorColors.themeVars(org, false);
        assertEquals(vars.get("--ct-primary-color"), "#8a2be2", "the palette's colors are declared");
        assertEquals(EditorColors.cardOf(null, false), "#ffffff", "no theme on the class path: a white card");
        assertEquals(EditorColors.cardOf(null, true), "#1c2023");
    }

    @Test
    public void jsonUsesThePagesOrgElseTheHosts() throws Exception {
        final Organization org = orgIn("blue");
        org.setBrandColors(List.of(new BrandColor("navy1", "Navy", "#123456", null)));
        final EditorColors colors = new EditorColors();
        colors.setOrgSource(id -> id.equals(org.getId().getValue()) ? org : null);
        colors.setCallerSource(() -> new Caller(Person.Id.from("site-admin"), true, null, null));

        final JsonNode named = JSON.readTree(colors.json(org.getId().getValue()));
        assertEquals(named.path("text").get(0).path("hex").asText(), "#69707a");
        assertTrue(named.toString().contains("\"--brand-navy1\""), named.toString());

        final SiteContext host = SiteContext.org(org.getId(), "painted", "painted.unitetrip.com");
        final String onHost = ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, host))
                .call(() -> colors.json(""));
        assertTrue(onHost.contains("\"--brand-navy1\""), onHost);
        final String shared = ScopedValue.where(RequestContext.SCOPE,
                RequestContext.of(null, null, SiteContext.shared("www.visitqueenofpeace.com")))
                .call(() -> colors.json(null));
        assertTrue(shared.contains("\"--text-color\"") && !shared.contains("--brand-"), shared);
    }

    private static Person person(final DAO dao) throws java.io.IOException {
        final Person person = Person.builder().first("Ed").last("Colors")
                .email("editor.colors." + System.nanoTime() + "@example.com").build();
        assertTrue(dao.savePerson(person));
        return person;
    }

    private static Caller caller(final Person person, final boolean siteAdmin) {
        return new Caller(person.getId(), siteAdmin, null, null);
    }

    private static Organization orgIn(final String palette) {
        final Organization org = Organization.builder().id(Organization.Id.newInstance()).name("Painted").build();
        org.getSettingsOverrides().put("site.theme.palette", palette);
        return org;
    }

    private static EditorColors.Swatch swatch(final List<EditorColors.Swatch> list, final String name) {
        return list.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }
}
