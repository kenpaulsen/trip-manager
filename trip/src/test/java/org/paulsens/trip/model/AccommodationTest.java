package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class AccommodationTest {
    private static final Address PODBRDO = new Address("Podbrdo 25", "Medjugorje", null, "88266");

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        final Accommodation built = Accommodation.builder().build();
        final Accommodation fresh = new Accommodation();
        built.setId(fresh.getId());
        assertEquals(built, fresh);
        assertNotNull(fresh.getId());
        assertNotNull(fresh.getAddress(), "Address is never null, the Person convention");
        assertTrue(fresh.getRooms().isEmpty());
        assertFalse(fresh.isRetired());
        assertTrue(fresh.floors().isEmpty());
        assertNull(fresh.room("nope"));
        assertNull(fresh.roomType("nope"));
        assertNull(fresh.roomLabel(null));
        assertNull(fresh.floorMap("1"));
        assertFalse(fresh.usedBy(null));
    }

    @Test
    public void normalizesEmailAndTrimsText() {
        final Accommodation acc = Accommodation.builder().name(" Pansion ").email(" INFO@Pansion.BA ")
                .phone(" +387 36 651 ").website(" https://www.pansion.ba/ ").build();
        assertEquals(acc.getName(), "Pansion");
        assertEquals(acc.getEmail(), "info@pansion.ba");
        assertEquals(acc.getPhone(), "+387 36 651");
        assertEquals(acc.getWebsite(), "https://www.pansion.ba/");
    }

    @Test
    public void inventoryLookupsFindRoomsTypesFloorsAndMaps() {
        final RoomType dbl = RoomType.builder().id("rt-d").name("Double").maxPeople(2).build();
        final Room r114 = Room.builder().id("r-114").roomNumber("114").floor("1").roomTypeId("rt-d").build();
        final Room r201 = Room.builder().id("r-201").roomNumber("201").floor("2").roomTypeId("rt-d").build();
        final Room r115 = Room.builder().id("r-115").roomNumber("115").floor("1").roomTypeId("rt-d").build();
        final Accommodation acc = Accommodation.builder().roomTypes(List.of(dbl)).rooms(List.of(r114, r201, r115))
                .floorMaps(List.of(new FloorMap("2", "m-2"), new FloorMap("3", "m-3"))).build();
        assertEquals(acc.roomType("rt-d"), dbl);
        assertEquals(acc.room("r-201"), r201);
        assertEquals(acc.roomLabel("r-114"), "114");
        assertNull(acc.roomLabel("r-999"));
        assertEquals(acc.roomsOnFloor("1"), List.of(r114, r115));
        assertTrue(acc.roomsOnFloor("9").isEmpty());
        assertEquals(acc.floors(), List.of("1", "2", "3"), "Room floors first (in order), then map-only floors");
        assertEquals(acc.floorMap("2").getMediaId(), "m-2");
        assertNull(acc.floorMap("1"));
    }

    @Test
    public void usedByAndRetiredFlag() {
        final Organization.Id acme = Organization.Id.from("org-acme");
        final Accommodation acc = Accommodation.builder().orgIds(List.of(acme)).build();
        assertTrue(acc.usedBy(acme));
        assertFalse(acc.usedBy(Organization.Id.from("org-beta")));
        acc.setRetired(true);
        assertTrue(acc.isRetired());
    }

    @Test
    public void discoveryMatchesOnAnyNormalizedField() {
        final Accommodation acc = Accommodation.builder().name("Pansion Dragićević").email("info@pansion.ba")
                .phone("+387 36 651 123").website("https://www.pansion.ba/rooms").address(PODBRDO).build();
        assertTrue(acc.matchesDiscovery("pansion dragićević", null, null, null, null), "Name, case-insensitive");
        assertTrue(acc.matchesDiscovery("PANSION-DRAGIĆEVIĆ", null, null, null, null), "Punctuation ignored");
        assertTrue(acc.matchesDiscovery(null, "Info@Pansion.BA ", null, null, null));
        assertTrue(acc.matchesDiscovery(null, null, "(387) 36-651-123", null, null), "Digits only");
        assertTrue(acc.matchesDiscovery(null, null, null, "pansion.ba", null), "Host without www or path");
        assertTrue(acc.matchesDiscovery(null, null, null, "http://WWW.pansion.ba", null));
        assertTrue(acc.matchesDiscovery(null, null, null, null,
                new Address("podbrdo  25", "MEDJUGORJE", "x", "88 266")), "Street+city+zip");
        assertFalse(acc.matchesDiscovery("Hotel Other", "x@y.z", "111", "other.ba",
                new Address("Elsewhere 1", "Medjugorje", null, "88266")));
        assertFalse(acc.matchesDiscovery(null, null, null, null, null));
        assertFalse(acc.matchesDiscovery("", "", "", "", new Address()), "Blank never matches blank");
        final Accommodation blank = Accommodation.builder().build();
        assertFalse(blank.matchesDiscovery("", "", "", "", null));
    }

    @Test
    public void websiteNormalizationSurvivesGarbage() {
        assertEquals(Accommodation.normalizeWebsite("https://www.Pansion.ba/x?y"), "pansion.ba");
        assertEquals(Accommodation.normalizeWebsite("pansion.ba"), "pansion.ba");
        assertEquals(Accommodation.normalizeWebsite("not a url at all"), "not a url at all");
        assertEquals(Accommodation.normalizeWebsite("http://[bad"), "http://[bad");
        assertNull(Accommodation.normalizeWebsite("  "));
        assertNull(Accommodation.normalizeAddress(new Address(null, "Rome", null, null)));
        assertNull(Accommodation.normalizePhone(null));
    }

    @Test
    public void jacksonRoundTripPreservesEverythingIncludingRetired() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Accommodation acc = Accommodation.builder().id(Accommodation.Id.from("acc-1")).name("Pansion")
                .description("Near the hill").address(PODBRDO).email("info@pansion.ba").phone("+387").website("p.ba")
                .contactId(Person.Id.from("person-ivan")).photoIds(List.of("m-1"))
                .roomTypes(List.of(RoomType.builder().id("rt").name("Double").build()))
                .rooms(List.of(Room.builder().id("r").roomNumber("1").roomTypeId("rt").build()))
                .floorMaps(List.of(new FloorMap("1", "m-9"))).orgIds(List.of(Organization.Id.from("org-1")))
                .createdBy(Person.Id.from("person-ken")).created(LocalDateTime.of(2026, 9, 6, 8, 0)).version(3L)
                .build();
        acc.setRetired(true);
        final String json = mapper.writeValueAsString(acc);
        final Accommodation restored = mapper.readValue(json, Accommodation.class);
        assertEquals(restored, acc);
        assertTrue(restored.isRetired());
        assertEquals(restored.getId().compareTo(Accommodation.Id.from("acc-2")) < 0, true);
        assertNotNull(Accommodation.Id.newInstance().getValue());
    }
}
