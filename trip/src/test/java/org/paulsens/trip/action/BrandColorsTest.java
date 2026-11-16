package org.paulsens.trip.action;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BrandColor;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

/**
 * An org's own named colors: adding, editing and deleting them, every value and name checked, the cap, the screening
 * of a row written by hand, the style that sets them on a page, and every write refused to anyone but that org's
 * admins.
 */
public class BrandColorsTest {

    private final DAO dao = DAO.getInstance();

    @Test
    public void addEditAndDelete() throws IOException {
        final Person admin = savedPerson();
        final String orgId = orgWithAdmin(admin).getId().getValue();
        final BrandColors colors = commandsFor(admin);
        assertEquals(colors.listFor(orgId), List.of());
        assertTrue(colors.canAdd(orgId));

        assertTrue(colors.save(orgId, "", "  Parish   gold ", "#A07A2C", ""));
        final BrandColor gold = colors.listFor(orgId).get(0);
        assertEquals(gold.name(), "Parish gold", "spaces collapse");
        assertEquals(gold.light(), "#a07a2c", "normalized");
        assertNull(gold.dark(), "blank dark = the light value in both modes");
        assertEquals(gold.valueFor(true), "#a07a2c");
        assertTrue(gold.varName().matches("--brand-[a-z0-9]{8}"), gold.varName());
        assertEquals(colors.find(orgId, gold.id()), gold);
        assertNull(colors.find(orgId, "nope"));

        assertTrue(colors.save(orgId, gold.id(), "Gold", "#a07a2c", "#e0b85c"));
        final BrandColor edited = colors.find(orgId, gold.id());
        assertEquals(edited.name(), "Gold");
        assertEquals(edited.valueFor(true), "#e0b85c");
        assertEquals(edited.varName(), gold.varName(), "the id, and so the variable, never changes");
        assertTrue(colors.save(orgId, gold.id(), "Gold", "#a07a2c", " "), "clearing the dark value");
        assertNull(colors.find(orgId, gold.id()).dark());

        assertEquals(colors.findForEdit(orgId, gold.id()), colors.find(orgId, gold.id()));
        assertTrue(colors.delete(orgId, gold.id()));
        assertNull(colors.findForEdit(orgId, gold.id()), "deleted meanwhile: refused, not opened empty");
        assertEquals(colors.listFor(orgId), List.of());
        assertNull(reread(orgId).getBrandColors(), "the last delete leaves the row as it was");
        assertFalse(colors.delete(orgId, gold.id()), "already gone");
    }

    @Test
    public void saveRefusesBadInput() throws IOException {
        final Person admin = savedPerson();
        final String orgId = orgWithAdmin(admin).getId().getValue();
        final BrandColors colors = commandsFor(admin);
        assertTrue(colors.save(orgId, null, "Navy", "#123456", null));

        assertFalse(colors.save(orgId, "nope", "Other", "#123456", ""), "unknown color");
        assertFalse(colors.save(orgId, "", "   ", "#123456", ""), "blank name");
        assertFalse(colors.save(orgId, "", "x".repeat(31), "#123456", ""), "long name");
        assertFalse(colors.save(orgId, "", "NAVY", "#654321", ""), "a name taken, in any case");
        assertFalse(colors.save(orgId, "", "Red", "red", ""), "not a hex color");
        assertFalse(colors.save(orgId, "", "Red", "#ff000080", ""), "translucent");
        assertFalse(colors.save(orgId, "", "Red", "#ff0000", "url(x)"), "a bad dark value");
        assertEquals(colors.listFor(orgId).size(), 1);
        assertTrue(colors.save(orgId, "", "Short", "#f00", ""), "#rgb is fine");
        assertEquals(colors.listFor(orgId).get(1).light(), "#ff0000");

        for (int i = colors.listFor(orgId).size(); i < BrandColors.MAX_COLORS; i++) {
            assertTrue(colors.save(orgId, "", "Color " + i, "#000000", ""));
        }
        assertFalse(colors.canAdd(orgId));
        assertFalse(colors.save(orgId, "", "One more", "#000000", ""), "the cap");
    }

    @Test
    public void onlyTheOrgsAdminsMayWrite() throws IOException {
        final Person admin = savedPerson();
        final String orgId = orgWithAdmin(admin).getId().getValue();
        assertTrue(commandsFor(admin).save(orgId, "", "Navy", "#123456", ""));
        final String id = commandsFor(admin).listFor(orgId).get(0).id();
        final BrandColors stranger = commandsFor(savedPerson());
        assertFalse(stranger.save(orgId, "", "Mine", "#000000", ""));
        assertFalse(stranger.save(orgId, id, "Mine", "#000000", ""));
        assertFalse(stranger.delete(orgId, id));
        assertFalse(commandsFor(admin).save("no-such-org", "", "Mine", "#000000", ""));
        assertEquals(commandsFor(admin).listFor(orgId).get(0).name(), "Navy");
    }

    @Test
    public void aRowWrittenByHandIsScreened() {
        final Organization org = new Organization();
        org.setBrandColors(new ArrayList<>(List.of(
                new BrandColor("ok1", "Fine", "#123456", "#abcdef"),
                new BrandColor("bad1", "Bad light", "red;}body{", null),
                new BrandColor("BAD id", "Bad id", "#123456", null),
                new BrandColor(null, "No id", "#123456", null),
                new BrandColor("ok2", "Bad dark", "#654321", "url(x)"))));
        final List<BrandColor> clean = BrandColors.colorsOf(org);
        assertEquals(clean.stream().map(BrandColor::id).toList(), List.of("ok1", "ok2"));
        assertNull(clean.get(1).dark(), "a bad dark value reads as none");
        assertEquals(BrandColors.styleOf(org, false), "--brand-ok1:#123456;--brand-ok2:#654321;");
        assertEquals(BrandColors.styleOf(org, true), "--brand-ok1:#abcdef;--brand-ok2:#654321;");
        assertEquals(BrandColors.styleOf(null, false), "");
        assertEquals(BrandColors.colorsOf(new Organization()), List.of());
        assertEquals(new BrandColors(() -> null).listFor(" "), List.of());
    }

    @Test
    public void theReadNoteMeasuresBothModes() throws IOException {
        final Person admin = savedPerson();
        final String orgId = orgWithAdmin(admin).getId().getValue();
        final BrandColors colors = commandsFor(admin);
        final String note = colors.readNote(orgId, new BrandColor("x", "x", "#000000", "#ffff00"));
        assertTrue(note.startsWith("As text on cards: 21.0 to 1 in light, "), note);
        assertFalse(note.contains("light (hard"), note);
        assertEquals(colors.starterColor(orgId), BrandColors.STARTER, "no theme on the class path: the gold");
        assertEquals(colors.starterColor(null), BrandColors.STARTER);
        final Organization blue = new Organization();
        blue.getSettingsOverrides().put("site.theme.palette", "blue");
        colors.setOrgSource(id -> blue);
        assertEquals(colors.starterColor("any"), "#5297ff", "the org's accent");
        final String pale = colors.readNote(null, new BrandColor("x", "x", "#eeeeee", null));
        assertTrue(pale.contains("in light (hard to read)"), pale);
    }

    @Test
    public void namesAreCleaned() {
        assertEquals(BrandColors.cleanName(" a \t b "), "a b");
        assertNull(BrandColors.cleanName(null));
        assertNull(BrandColors.cleanName(""));
        assertNull(BrandColors.opaque(null));
        assertEquals(BrandColors.opaque("ABCDEF"), "#abcdef");
    }

    private Organization reread(final String orgId) {
        return dao.getOrganization(Organization.Id.from(orgId), Cached.NO).orElseThrow();
    }

    private Organization orgWithAdmin(final Person orgAdmin) {
        final OrgCommands siteAdmin = new OrgCommands(BrandColorsTest::siteAdminCaller);
        final Organization org = siteAdmin.createOrganization("Brand " + RandomData.genAlpha(10), null, null);
        assertTrue(siteAdmin.setOrgAdmin(org.getId().getValue(), orgAdmin.getId(), true));
        return reread(org.getId().getValue());
    }

    private Person savedPerson() throws IOException {
        final Person person = Person.builder().first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("brand." + RandomData.genAlpha(12).toLowerCase() + "@example.com").build();
        assertTrue(dao.savePerson(person));
        return person;
    }

    private static BrandColors commandsFor(final Person person) {
        return new BrandColors(() -> callerOf(person));
    }

    private static Caller callerOf(final Person person) {
        return new Caller(person.getId(), false, new AuditActor(person.getEmail(), person.getId().getValue()),
                grantsNothing());
    }

    private static Caller siteAdminCaller() {
        return new Caller(Person.Id.from("admin-brand"), true, new AuditActor("admin@test", "admin"),
                grantsNothing());
    }

    private static PrivilegeCommands grantsNothing() {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(none.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return none;
    }
}
