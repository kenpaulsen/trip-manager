package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** The worksheet entry: builder parity with the no-arg constructor, normalization, and the JSON shape. */
public class AdvanceEntryTest {

    @Test
    public void builderDefaultsMatchTheNoArgConstructor() {
        final AdvanceEntry built = AdvanceEntry.builder().build();
        final AdvanceEntry plain = new AdvanceEntry();
        assertNotNull(built.getId());
        plain.setId(built.getId());
        assertEquals(built, plain);
        assertEquals(built.getType(), AdvanceType.OTHER);
        assertEquals(built.getCurrency(), ExpenseLine.USD);
        assertTrue(built.isUsd());
        assertTrue(built.getFields().isEmpty());
        assertTrue(built.getPersonIds().isEmpty());
        assertNull(built.getTransaction());
        assertNull(built.getParty());
        assertEquals(new AdvanceEntry.RecordedTransaction(), AdvanceEntry.RecordedTransaction.builder().build());
    }

    @Test
    public void theConstructorNormalizes() {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put(" from ", "  CFPW ");
        fields.put("blank", "   ");
        fields.put("  ", "orphan");
        fields.put("memo", "kept");
        final AdvanceEntry entry = AdvanceEntry.builder().id(" ").type(AdvanceType.CASH).currency(" eur")
                .amountMinor(-5L).note("  ").fields(fields).personIds(List.of("p1")).build();
        assertFalse(entry.getId().isBlank());
        assertEquals(entry.getCurrency(), "EUR");
        assertFalse(entry.isUsd());
        assertEquals(entry.getAmountMinor(), 0L, "a magnitude: the type carries the sign");
        assertNull(entry.getNote());
        assertEquals(entry.getFields(), Map.of("from", "CFPW", "memo", "kept"), "blank names and values drop out");
        assertEquals(entry.getParty(), "CFPW");
        entry.getPersonIds().add("p2"); // a mutable copy, never the caller's list
        assertEquals(AdvanceEntry.builder().currency("nope").build().getCurrency(), ExpenseLine.USD);
        assertNull(AdvanceEntry.builder().type(AdvanceType.PARTICIPANT).fields(Map.of("from", "x")).build().getParty(),
                "a participant entry has no party text");
    }

    @Test
    public void typesCarryTheSign() {
        assertTrue(AdvanceType.RETURNED.isReverse());
        assertEquals(AdvanceType.RETURNED.getSign(), -1);
        assertEquals(AdvanceType.RETURNED.partyKey(), "to");
        for (final AdvanceType type : List.of(AdvanceType.CASH, AdvanceType.CREDIT, AdvanceType.PARTICIPANT,
                AdvanceType.OTHER)) {
            assertFalse(type.isReverse(), type.name());
            assertEquals(type.getSign(), 1);
        }
        assertEquals(AdvanceType.CASH.partyKey(), "from");
        assertEquals(AdvanceType.CREDIT.partyKey(), "source");
        assertNull(AdvanceType.PARTICIPANT.partyKey());
        assertNull(AdvanceType.OTHER.partyKey());
        assertEquals(AdvanceType.PARTICIPANT.getLabel(), "Cash given by participant(s)");
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final AdvanceEntry entry = AdvanceEntry.builder().id("a1").type(AdvanceType.PARTICIPANT)
                .date(LocalDate.of(2026, 9, 25)).currency("EUR").amountMinor(8_300L).note("in Rome")
                .personIds(List.of("p1")).transaction(AdvanceEntry.RecordedTransaction.builder().txId("tx")
                        .personId("p1").amountCents(10_000L).recordedOn(LocalDateTime.of(2026, 10, 30, 9, 0)).build())
                .build();
        final String json = mapper.writeValueAsString(entry);
        assertFalse(json.contains("\"usd\"") || json.contains("\"party\""), "Derived flags are not stored");
        assertTrue(json.contains("\"transaction\""));
        assertEquals(mapper.readValue(json, AdvanceEntry.class), entry);
        final AdvanceEntry old = mapper.readValue("{\"id\":\"a2\",\"amountMinor\":5}", AdvanceEntry.class);
        assertEquals(old.getType(), AdvanceType.OTHER);
        assertEquals(old.getCurrency(), "USD");
        assertTrue(old.getFields().isEmpty());
    }
}
