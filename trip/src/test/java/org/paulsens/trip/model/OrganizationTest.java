package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class OrganizationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        // The Lombok primitive-default trap: a builder that zeroes defaults differently than the no-arg
        // constructor silently corrupts round-trips. Both must agree (modulo the minted random id).
        final Organization built = Organization.builder().build();
        final Organization fresh = new Organization();
        built.setId(fresh.getId());
        assertEquals(built, fresh);
    }

    @Test
    public void jacksonRoundTripPreservesEverything() throws Exception {
        final Person.Id admin = Person.Id.newInstance();
        final Organization org = Organization.builder()
                .id(Organization.Id.newInstance())
                .name("Acme Inc")
                .abbreviation("Acme")
                .contactEmail("info@acme.example")
                .adminIds(List.of(admin))
                .createdBy(admin)
                .created(LocalDateTime.of(2026, 8, 17, 12, 0))
                .version(3L)
                .build();
        final Organization back = MAPPER.readValue(MAPPER.writeValueAsString(org), Organization.class);
        assertEquals(back, org);
        assertEquals(back.getVersion(), 3L);
    }

    @Test
    public void missingJsonKeysYieldSafeDefaults() throws Exception {
        final Organization org = MAPPER.readValue("{\"name\":\"CFPW\"}", Organization.class);
        assertEquals(org.getName(), "CFPW");
        assertTrue(org.getAdminIds().isEmpty(), "adminIds must never be null");
        assertEquals(org.getVersion(), 0L);
    }

    @Test
    public void nullIdMintsACanonicalUuid() {
        final Organization org = new Organization();
        // Org-scoped privileges require a canonical UUID id -- the shape Privilege's parser anchors on.
        assertTrue(org.getId().getValue().matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
    }

    @Test
    public void isAdminChecksTheList() {
        final Person.Id admin = Person.Id.newInstance();
        final Organization org = Organization.builder().adminIds(List.of(admin)).build();
        assertTrue(org.isAdmin(admin));
        assertFalse(org.isAdmin(Person.Id.newInstance()));
        assertFalse(org.isAdmin(null));
    }

    @Test
    public void mayGrantDistinguishesNeverSetFromEmpty() {
        final Organization org = new Organization();
        assertTrue(org.mayGrant("tripFinAdmin"), "null allow-list must mean everything is allowed");
        org.setGrantablePrivileges(List.of());
        assertFalse(org.mayGrant("tripFinAdmin"), "an EMPTY allow-list must mean nothing is grantable");
        org.setGrantablePrivileges(List.of("tripMgr", "peopleAdmin"));
        assertTrue(org.mayGrant("tripMgr"));
        assertFalse(org.mayGrant("tripFinAdmin"));
    }

    @Test
    public void grantablePrivilegesRoundTripAndAbsentStaysNull() throws Exception {
        // Absent field == "never restricted"; the round-trip must not manufacture an empty list, which would
        // silently flip the org from all-allowed to nothing-allowed.
        final Organization never = MAPPER.readValue("{\"name\":\"CFPW\"}", Organization.class);
        assertNull(never.getGrantablePrivileges());
        final Organization org = Organization.builder().name("Acme Inc").build();
        org.setGrantablePrivileges(List.of("tripMgr"));
        final Organization back = MAPPER.readValue(MAPPER.writeValueAsString(org), Organization.class);
        assertEquals(back.getGrantablePrivileges(), List.of("tripMgr"));
    }

    @Test
    public void shortNamePrefersAbbreviation() {
        assertEquals(Organization.builder().name("Center for Peace West").abbreviation("CFPW").build()
                .getShortName(), "CFPW");
        assertEquals(Organization.builder().name("Acme Inc").abbreviation("  ").build().getShortName(),
                "Acme Inc");
        assertEquals(Organization.builder().name("Acme Inc").build().getShortName(), "Acme Inc");
    }

    @Test
    public void stringsAreTrimmed() {
        final Organization org = Organization.builder()
                .name("  Acme Inc  ").abbreviation(" Acme ").contactEmail(" a@b.c ").build();
        assertEquals(org.getName(), "Acme Inc");
        assertEquals(org.getAbbreviation(), "Acme");
        assertEquals(org.getContactEmail(), "a@b.c");
    }

    @Test
    public void idsCompareByValue() {
        final Organization.Id a = Organization.Id.from("aaa");
        final Organization.Id b = Organization.Id.from("bbb");
        assertTrue(a.compareTo(b) < 0);
        assertEquals(a, Organization.Id.from("aaa"));
        assertNotEquals(a, b);
    }

    @Test
    public void settingsOverridesRoundTripAndAreNeverNull() throws Exception {
        final Organization org = new Organization();
        assertTrue(org.getSettingsOverrides().isEmpty(), "lazily an empty map, like paymentDefaults");
        org.getSettingsOverrides().put("site.org.name", "Acme");
        final Organization back = MAPPER.readValue(MAPPER.writeValueAsString(org), Organization.class);
        assertEquals(back.getSettingsOverrides(), java.util.Map.of("site.org.name", "Acme"));
        assertEquals(back.settingOverride("site.org.name"), "Acme");
        // A row written before the field existed reads back as "inherit everything".
        final Organization legacy = MAPPER.readValue("{\"name\":\"Old\"}", Organization.class);
        assertTrue(legacy.getSettingsOverrides().isEmpty());
        assertNull(legacy.settingOverride("site.org.name"));
    }
}
