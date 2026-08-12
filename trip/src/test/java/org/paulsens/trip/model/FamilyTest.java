package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class FamilyTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    public void defaultsAreSafe() {
        final Family family = new Family();
        assertNotNull(family.getId(), "A null id must be replaced with a fresh one");
        assertNotNull(family.getMemberIds(), "memberIds must never be null");
        assertNotNull(family.getManagerIds(), "managerIds must never be null");
        assertTrue(family.getMemberIds().isEmpty());
        assertTrue(family.getManagerIds().isEmpty());
        assertEquals(family.getVersion(), 0L, "A never-persisted family is version 0");
        assertEquals(family.getSize(), 0);
    }

    @Test
    public void builderMatchesDefaultConstructorNormalization() {
        final Family built = Family.builder().build();
        final Family defaults = new Family();
        // Ids are random, so compare the normalized shape rather than full equality.
        assertNotNull(built.getId());
        assertEquals(built.getMemberIds(), defaults.getMemberIds());
        assertEquals(built.getManagerIds(), defaults.getManagerIds());
        assertEquals(built.getVersion(), defaults.getVersion());
        assertEquals(built.getCreated(), defaults.getCreated());
    }

    @Test
    public void constructorCopiesTheListsItIsGiven() {
        final List<Person.Id> members = new ArrayList<>(List.of(Person.Id.from("a")));
        final Family family = Family.builder().memberIds(members).build();
        members.add(Person.Id.from("b"));
        assertEquals(family.getMemberIds().size(), 1, "The family must not share the caller's list");
    }

    @Test
    public void membershipAndManagerChecks() {
        final Person.Id member = Person.Id.from("m1");
        final Person.Id manager = Person.Id.from("m2");
        final Family family = Family.builder()
                .memberIds(List.of(member, manager))
                .managerIds(List.of(manager))
                .build();
        assertTrue(family.isMember(member));
        assertTrue(family.isMember(manager));
        assertTrue(family.isManager(manager));
        assertFalse(family.isManager(member));
        assertFalse(family.isMember(Person.Id.from("stranger")));
        assertFalse(family.isMember(null));
        assertFalse(family.isManager(null));
        assertEquals(family.getSize(), 2);
    }

    /** Ids sort by value: families land in ordered collections (the consistency report sorts them). */
    @Test
    public void idsCompareByValue() {
        final Family.Id first = Family.Id.from("aaa");
        final Family.Id second = Family.Id.from("bbb");
        assertTrue(first.compareTo(second) < 0);
        assertTrue(second.compareTo(first) > 0);
        assertEquals(first.compareTo(Family.Id.from("aaa")), 0);
    }

    @Test
    public void survivesAJacksonRoundTrip() throws Exception {
        final Family family = Family.builder()
                .id(Family.Id.from("fam-1"))
                .memberIds(List.of(Person.Id.from("p1"), Person.Id.from("p2")))
                .managerIds(List.of(Person.Id.from("p1")))
                .createdBy(Person.Id.from("p1"))
                .created(LocalDateTime.of(2026, 8, 10, 12, 30))
                .version(3L)
                .build();
        final Family revived = MAPPER.readValue(MAPPER.writeValueAsString(family), Family.class);
        assertEquals(revived, family);
        // Ids serialize as bare strings (the @JsonValue contract every other Id in the model follows).
        assertTrue(MAPPER.writeValueAsString(family).contains("\"fam-1\""));
    }
}
