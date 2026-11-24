package org.paulsens.trip.action;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.CustomPalette;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.theme.ThemeColorMath;
import org.paulsens.trip.util.RandomData;
import org.paulsens.trip.web.FakeSessions;
import org.testng.annotations.Test;

/**
 * An org's own color palettes: cloning a stock palette exactly from the generator's manifest, saving and
 * validating colors and names, the derived dark set, the style that draws a palette, deleting one the site uses,
 * the palette setting accepting only the org's own palettes, and every write refused to anyone but that org's
 * admins.
 */
public class CustomPalettesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final DAO dao = DAO.getInstance();

    @Test
    public void aStockCloneCarriesItsExactColors() throws Exception {
        final Person admin = savedPerson();
        final String orgId = orgWithAdmin(admin).getId().getValue();
        final CustomPalettes palettes = commandsFor(admin);
        assertTrue(palettes.isAvailable(), "the test class path carries the generated manifest");

        final String id = create(palettes, orgId, "purple");
        assertNotNull(id);
        final CustomPalette made = palettes.find(orgId, id);
        assertEquals(made.name(), "Purple (custom)");
        assertEquals(made.source(), "purple");
        assertEquals(made.light(), palettes.stockInputs("purple", false), "a clone is exact");
        assertEquals(made.darkOverrides(), Map.of(), "dark starts automatic");
        assertEquals(palettes.inputs(made, true), palettes.stockInputs("purple", true),
                "an untouched clone's derived dark set IS its source's dark palette");
        assertEquals(palettes.inputs(made, false), made.light());

        final String again = create(palettes, orgId, "purple");
        assertEquals(palettes.find(orgId, again).name(), "Purple (custom) 2", "names stay unique");
        final String copy = create(palettes, orgId, made.ref());
        assertEquals(palettes.find(orgId, copy).name(), "Purple (custom) copy");
        assertEquals(palettes.find(orgId, copy).light(), made.light());
        assertEquals(palettes.listFor(orgId).size(), 3);

        assertNull(create(palettes, orgId, "no-such-palette"), "an unknown source is refused, never swapped");
        // A duplicate's draft is settled when the editor opens; deleting its original meanwhile costs no work.
        final CustomPalette draft = palettes.draft(orgId, "custom:" + copy);
        assertTrue(delete(palettes, orgId, copy));
        assertNull(palettes.newEditorUrl(orgId, "custom:" + copy),
                "OPENING a duplicate of a palette deleted meanwhile is refused, not turned into a Blue one");
        final String kept = idIn(palettes.saveNew(orgId, "custom:" + copy, draft.source(), "Mine",
                JSON.writeValueAsString(draft.light()), "{}"));
        assertEquals(palettes.find(orgId, kept).light(), made.light(), "...but its open draft still saves");
        assertNull(palettes.saveNew(orgId, "blue", "no-such-palette", "Other",
                JSON.writeValueAsString(draft.light()), "{}"), "a source the theme does not have is refused");
        assertNull(palettes.saveNew(orgId, "blue", null, "Other", JSON.writeValueAsString(draft.light()), "{}"));
        assertEquals(palettes.listFor(orgId).size(), 3);
    }

    @Test
    public void saveValidatesAndTheDarkSetFollowsTheAccent() throws Exception {
        final Person admin = savedPerson();
        final String orgId = orgWithAdmin(admin).getId().getValue();
        final CustomPalettes palettes = commandsFor(admin);
        final String id = create(palettes, orgId, "blue");
        final Map<String, String> light = new TreeMap<>(palettes.find(orgId, id).light());
        light.putAll(ThemeColorMath.accentFamily("#8a2be2"));

        assertTrue(palettes.save(orgId, id, "  Our   violet ", JSON.writeValueAsString(light), ""));
        final CustomPalette saved = palettes.find(orgId, id);
        assertEquals(saved.name(), "Our violet", "spaces collapse");
        assertEquals(saved.light().get(ThemeColorMath.ACCENT), "#8a2be2");
        final Map<String, String> dark = palettes.inputs(saved, true);
        assertEquals(dark.get(ThemeColorMath.HOVER), "#8a2be2", "dark hover = the light accent");
        assertEquals(dark.get(ThemeColorMath.ACCENT), light.get(ThemeColorMath.SOFT), "dark accent = light soft");
        assertEquals(dark.get("shade900"), palettes.stockInputs("blue", true).get("shade900"),
                "the surfaces keep the source's dark values");

        final String overrides = JSON.writeValueAsString(Map.of("shade900", "#101010"));
        assertTrue(palettes.save(orgId, id, "Our violet", JSON.writeValueAsString(light), overrides));
        assertEquals(palettes.inputs(palettes.find(orgId, id), true).get("shade900"), "#101010",
                "an override wins over the derivation");

        final String json = palettes.derivedDarkJson("blue", JSON.writeValueAsString(light), overrides);
        assertTrue(json.contains("\"shade900\":\"#101010\""), json);
        assertNull(palettes.derivedDarkJson("blue", "{not json", ""));

        final String style = palettes.styleOf(palettes.find(orgId, id), false);
        assertTrue(style.contains("--ct-primary-color:#8a2be2;"), style);
        assertEquals(style.split(";").length, light.size(), "one declaration per input");
        final CustomPalette tampered = new CustomPalette("x", "x", "blue",
                Map.of("primary-color", "red;}body{", "bad name", "#000000", "shade000", "#fff"), Map.of());
        assertEquals(palettes.styleOf(tampered, false), "--ct-shade000:#ffffff;",
                "a stored value that is not a color, or a name that is not a token, is never written");
    }

    @Test
    public void saveRefusesBadInput() throws Exception {
        final Person admin = savedPerson();
        final String orgId = orgWithAdmin(admin).getId().getValue();
        final CustomPalettes palettes = commandsFor(admin);
        final String id = create(palettes, orgId, "green");
        final String other = create(palettes, orgId, "red");
        final Map<String, String> light = palettes.find(orgId, id).light();
        final String lightJson = JSON.writeValueAsString(light);

        assertFalse(palettes.save(orgId, "nope", "Name", lightJson, ""), "unknown palette");
        assertFalse(palettes.save(orgId, id, "   ", lightJson, ""), "blank name");
        assertFalse(palettes.save(orgId, id, "x".repeat(41), lightJson, ""), "long name");
        assertFalse(palettes.save(orgId, id, palettes.find(orgId, other).name().toUpperCase(), lightJson, ""),
                "a name another palette has, in any case");
        final Map<String, String> notAColor = new TreeMap<>(light);
        notAColor.put("shade000", "url(x)");
        assertFalse(palettes.save(orgId, id, "Name", JSON.writeValueAsString(notAColor), ""));
        final Map<String, String> unknownInput = new TreeMap<>(light);
        unknownInput.put("bogus", "#000000");
        assertFalse(palettes.save(orgId, id, "Name", JSON.writeValueAsString(unknownInput), ""));
        final Map<String, String> missing = new TreeMap<>(light);
        missing.remove("shade000");
        assertFalse(palettes.save(orgId, id, "Name", JSON.writeValueAsString(missing), ""), "light is whole");
        assertFalse(palettes.save(orgId, id, "Name", lightJson,
                JSON.writeValueAsString(Map.of("shade500", "#000000"))), "shade500 is a light-only input");
        assertFalse(palettes.save(orgId, id, "Name", "[1,2]", ""), "unreadable");
        assertTrue(palettes.save(orgId, id, palettes.find(orgId, id).name(), lightJson, null),
                "its own name is not taken");
    }

    @Test
    public void deletingThePaletteInUseFallsBack() throws Exception {
        final Person admin = savedPerson();
        final Organization org = orgWithAdmin(admin);
        final String orgId = org.getId().getValue();
        final CustomPalettes palettes = commandsFor(admin);
        final OrgCommands orgs = new OrgCommands(() -> callerOf(admin));
        final String id = create(palettes, orgId, "orange");
        final String setting = KnownSettings.SITE_THEME_PALETTE.getName();

        assertTrue(orgs.saveOrgSettings(orgId, Map.of(setting, "custom:" + id)), "its own palette is a choice");
        assertEquals(reread(org).getSettingsOverrides().get(setting), "custom:" + id);
        assertFalse(orgs.saveOrgSettings(orgId, Map.of(setting, "custom:someone-elses")));
        assertFalse(orgs.saveOrgSettings(orgId, Map.of(setting, "teal-ish")), "the stock menu is still judged");

        assertTrue(delete(palettes, orgId, id));
        assertNull(reread(org).getSettingsOverrides().get(setting), "the site falls back to the default");
        assertNull(reread(org).getCustomPalettes(), "no empty list left on the row");
        assertFalse(delete(palettes, orgId, id), "already gone");

        final String kept = create(palettes, orgId, "orange");
        final String gone = create(palettes, orgId, "yellow");
        assertTrue(orgs.saveOrgSettings(orgId, Map.of(setting, "custom:" + kept)));
        assertTrue(delete(palettes, orgId, gone));
        assertEquals(reread(org).getSettingsOverrides().get(setting), "custom:" + kept, "another palette's stays");
    }

    @Test
    public void onlyTheOrgsAdminsWrite() throws Exception {
        final Person admin = savedPerson();
        final Person outsider = savedPerson();
        final String orgId = orgWithAdmin(admin).getId().getValue();
        final String otherOrgId = orgWithAdmin(outsider).getId().getValue();
        final String id = create(commandsFor(admin), orgId, "blue");
        final CustomPalettes foreign = commandsFor(outsider);
        final String lightJson = JSON.writeValueAsString(commandsFor(admin).find(orgId, id).light());

        assertNull(create(foreign, orgId, "blue"));
        assertFalse(foreign.save(orgId, id, "Mine now", lightJson, ""));
        assertFalse(delete(foreign, orgId, id));
        assertEquals(commandsFor(admin).find(orgId, id).name(), "Blue (custom)", "untouched");
        assertNull(foreign.find(otherOrgId, id), "one org's palette is never found under another");
        assertEquals(foreign.listFor(otherOrgId), List.of());
        assertNull(create(foreign, "no-such-org", "blue"));
    }

    @Test
    public void theLimitHolds() throws Exception {
        final Person admin = savedPerson();
        final String orgId = orgWithAdmin(admin).getId().getValue();
        final CustomPalettes palettes = commandsFor(admin);
        for (int i = 0; i < CustomPalettes.MAX_PALETTES; i++) {
            assertNotNull(create(palettes, orgId, "blue"));
        }
        assertNull(create(palettes, orgId, "blue"));
        assertFalse(palettes.canAdd(orgId), "no New palette card at the limit");
        assertTrue(delete(palettes, orgId, palettes.listFor(orgId).get(0).id()));
        final CustomPalette first = palettes.draft(orgId, "blue");
        assertNotNull(create(palettes, orgId, "green"), "a second draft, opened at the same count, saved first");
        assertNull(palettes.saveNew(orgId, "blue", first.source(), "Late " + first.name(),
                JSON.writeValueAsString(first.light()), "{}"), "the first Save re-checks the limit");
        assertEquals(palettes.listFor(orgId).size(), CustomPalettes.MAX_PALETTES);
    }

    @Test
    public void withoutTheManifestNothingIsOffered() throws Exception {
        final Person admin = savedPerson();
        final String orgId = orgWithAdmin(admin).getId().getValue();
        final CustomPalettes palettes = commandsFor(admin);
        palettes.setManifestSource(() -> null);
        assertFalse(palettes.isAvailable());
        assertEquals(palettes.stockInputs("blue", false), Map.of());
        assertNull(create(palettes, orgId, "blue"), "nothing to clone");
        assertEquals(CustomPalettes.readManifest("{broken"), Map.of());
        assertEquals(palettes.listFor(null), List.of());
        assertNull(palettes.find(null, "x"));
    }

    @Test
    public void theEditorOffersEveryInputOnceWithItsRole() {
        final CustomPalettes palettes = new CustomPalettes(() -> null);
        for (final boolean dark : new boolean[] {false, true}) {
            final List<String> offered = new java.util.ArrayList<>();
            palettes.getGroups().forEach(g -> palettes.fields(dark, g).forEach(f -> offered.add(f.input())));
            assertEquals(new java.util.TreeSet<>(offered), palettes.inputNames(dark), "every input, dark=" + dark);
            assertEquals(offered.size(), palettes.inputNames(dark).size(), "each once, dark=" + dark);
        }
        assertEquals(palettes.fields(false, CustomPalettes.GROUP_ACCENT).get(0).label(), "Accent");
        assertEquals(label(palettes, false, "shade000"), "Cards and panels");
        assertEquals(label(palettes, true, "shade000"), "Text", "the same input draws text in dark mode");

        final CustomPalettes newer = new CustomPalettes(() -> null);
        newer.setManifestSource(() -> "{\"palettes\":{\"blue\":{\"light\":{\"primary-color\":\"#000000\","
                + "\"brand-new\":\"#111111\"},\"dark\":{}}}}");
        assertEquals(newer.fields(false, CustomPalettes.GROUP_MENU).get(0),
                new CustomPalettes.PaletteField("brand-new", "brand-new", CustomPalettes.GROUP_MENU),
                "an input the catalog does not know yet is still editable, under its own name");
    }

    @Test
    public void theEditorsCommands() throws Exception {
        final Person admin = savedPerson();
        final String orgId = orgWithAdmin(admin).getId().getValue();
        final CustomPalettes palettes = commandsFor(admin);

        final String draftUrl = palettes.newEditorUrl(orgId, BrandCommands.PALETTE_CARD_NONE);
        assertEquals(draftUrl, "/admin/orgPalette.jsf?orgId=" + orgId + "&from=medj",
                "the platform default card clones the platform's own palette");
        assertEquals(palettes.listFor(orgId), List.of(), "opening the editor stores nothing");
        final CustomPalette draft = palettes.draft(orgId, "medj");
        assertEquals(draft.id(), CustomPalettes.DRAFT_ID);
        assertEquals(draft.name(), "Platform default (custom)");
        assertNull(palettes.draft(null, "medj"));
        assertNull(palettes.draft(orgId, "custom:none-such"));
        assertNull(commandsFor(savedPerson()).newEditorUrl(orgId, "blue"), "an outsider opens nothing");
        assertNull(commandsFor(savedPerson()).saveNew(orgId, "blue", "blue", "Mine",
                JSON.writeValueAsString(draft.light()), "{}"), "...and stores nothing");

        final String saved = palettes.saveNew(orgId, "", draft.source(), draft.name(),
                JSON.writeValueAsString(draft.light()), "");
        assertTrue(saved.startsWith("/admin/orgPalette.jsf?orgId=" + orgId + "&paletteId="), saved);
        assertTrue(saved.contains("&info=Saved"), "the reloaded editor says so");
        final String id = idIn(saved);
        final CustomPalette made = palettes.find(orgId, id);
        assertEquals(made.source(), "medj");
        assertEquals(made.name(), "Platform default (custom)");
        assertNull(palettes.saveNew(orgId, "medj", "medj", "  ", JSON.writeValueAsString(draft.light()), ""),
                "the first Save checks the name like every other");
        assertEquals(palettes.editUrl(orgId, made.ref()), CustomPalettes.editorUrl(orgId, id));
        assertNull(palettes.editUrl(orgId, "blue"), "a stock card has no editor");
        assertNull(palettes.editUrl(null, made.ref()));

        assertEquals(palettes.inputsJson(null, false), "{}");
        assertEquals(palettes.overridesJson(null), "{}");
        assertTrue(palettes.inputsJson(made, false).contains("\"primary-color\""));
        assertEquals(palettes.overridesJson(made.withColors(made.light(), Map.of("shade800", "#ABC", "x y", "#000"))),
                "{\"shade800\":\"#aabbcc\"}", "screened like every other read");
        assertTrue(palettes.readabilityJson().contains("\"label\":\"Text on cards\""));

        // The delete drops the palette from this admin's unsaved Appearance preview, whichever page it came from.
        final BrandCommands brand = new BrandCommands();
        final HttpSession session = FakeSessions.fresh();
        brand.setSessionSource(() -> session);
        brand.preview(orgId, new HashMap<>(Map.of("site.theme.palette", made.ref())), null);
        palettes.setBrandSource(() -> brand);
        final String gone = palettes.deleteAndReturn(orgId, made.ref());
        assertEquals(brand.previewFor(orgId).get("site.theme.palette"), "", "the preview no longer names it");
        assertTrue(gone.startsWith("/admin/orgConfig.jsf?orgId=" + orgId + "&tab=appearance&info=Deleted"), gone);
        assertNull(palettes.deleteAndReturn(orgId, id), "already gone");
        final String bare = create(palettes, orgId, "blue");
        assertNotNull(palettes.deleteAndReturn(orgId, bare), "a bare id works too");
        assertFalse(delete(commandsFor(savedPerson()), orgId, bare), "an outsider deletes nothing");
    }

    @Test
    public void hardToReadPairsAreNamedInBothModes() {
        final CustomPalettes palettes = new CustomPalettes(() -> null);
        final CustomPalette blue = new CustomPalette("b", "Blue", "blue", palettes.stockInputs("blue", false),
                Map.of());
        final List<String> stock = palettes.hardToRead(blue);
        assertTrue(stock.contains("Text on the accent, light mode (2.9 to 1)"), stock.toString());
        final Map<String, String> pale = new TreeMap<>(palettes.stockInputs("blue", false));
        pale.put("shade700", "#eeeeee");
        final List<String> hard = palettes.hardToRead(new CustomPalette("p", "Pale", "blue", pale, Map.of()));
        assertTrue(hard.stream().anyMatch(h -> h.startsWith("Text on cards, light mode")), hard.toString());
        assertTrue(hard.stream().noneMatch(h -> h.startsWith("Text on cards, dark mode")), "dark keeps its own text");
    }

    @Test
    public void theEditorsDerivationsAnswerOverAjax() throws Exception {
        final CustomPalettes palettes = new CustomPalettes(() -> null);
        final String light = JSON.writeValueAsString(palettes.stockInputs("blue", false));
        final Map<String, String> plain = palettes.derived(Map.of("source", "blue", "light", light));
        assertEquals(plain.keySet(), java.util.Set.of("dark"), "no accent asked: no family");
        final Map<String, String> matched = palettes.derived(Map.of("source", "blue", "light", light,
                "accent", "#0f7b5f", "overrides", "{}"));
        assertTrue(matched.get("family").contains("\"primary-dark-color\":\"#0b5743\""), matched.get("family"));
        assertTrue(matched.get("dark").contains("\"primary-dark-color\":\"#0f7b5f\""),
                "the family is folded in before dark is derived");
        assertEquals(palettes.derived(Map.of("light", "[not an object")), Map.of());
        palettes.answerDerivedDark();   // no FacesContext: nothing to answer, and no throw
    }

    @Test
    public void namesAreCleaned() {
        assertEquals(CustomPalettes.cleanName(" a \t b "), "a b");
        assertNull(CustomPalettes.cleanName(null));
        assertNull(CustomPalettes.cleanName(""));
        assertNotEquals(CustomPalettes.cleanName("x".repeat(40)), null);
    }

    /** A clone stored the way the editor stores one: its draft, then the first Save. The new id, or null. */
    private static String create(final CustomPalettes palettes, final String orgId, final String from)
            throws Exception {
        if (palettes.newEditorUrl(orgId, from) == null) {
            return null;
        }
        final CustomPalette draft = palettes.draft(orgId, from);
        final String saved = palettes.saveNew(orgId, from, draft.source(), draft.name(),
                JSON.writeValueAsString(draft.light()),
                JSON.writeValueAsString(draft.darkOverrides()));
        return saved == null ? null : idIn(saved);
    }

    private static boolean delete(final CustomPalettes palettes, final String orgId, final String id) {
        return palettes.deleteAndReturn(orgId, id) != null;
    }

    private static String idIn(final String url) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("paletteId=([^&]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static String label(final CustomPalettes palettes, final boolean dark, final String input) {
        return palettes.getGroups().stream().flatMap(g -> palettes.fields(dark, g).stream())
                .filter(f -> f.input().equals(input)).findFirst().orElseThrow().label();
    }

    private Organization reread(final Organization org) {
        return dao.getOrganization(org.getId(), Cached.NO).orElseThrow();
    }

    private Organization orgWithAdmin(final Person orgAdmin) {
        final OrgCommands siteAdmin = new OrgCommands(CustomPalettesTest::siteAdminCaller);
        final Organization org = siteAdmin.createOrganization("Palettes " + RandomData.genAlpha(10), null, null);
        assertTrue(siteAdmin.setOrgAdmin(org.getId().getValue(), orgAdmin.getId(), true));
        return reread(org);
    }

    private Person savedPerson() throws IOException {
        final Person person = Person.builder().first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("palettes." + RandomData.genAlpha(12).toLowerCase() + "@example.com").build();
        assertTrue(dao.savePerson(person));
        return person;
    }

    private static CustomPalettes commandsFor(final Person person) {
        return new CustomPalettes(() -> callerOf(person));
    }

    private static Caller callerOf(final Person person) {
        return new Caller(person.getId(), false, new AuditActor(person.getEmail(), person.getId().getValue()),
                grantsNothing());
    }

    private static Caller siteAdminCaller() {
        return new Caller(Person.Id.from("admin-palettes"), true, new AuditActor("admin@test", "admin"),
                grantsNothing());
    }

    private static PrivilegeCommands grantsNothing() {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(none.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return none;
    }
}
