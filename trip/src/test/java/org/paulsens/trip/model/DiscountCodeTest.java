package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Unit tests for {@link DiscountCode} — constructor defaults, the {@code validOptionIdsString}
 * CSV helper used by the admin editor, and the JSON round-trip.
 */
public class DiscountCodeTest {

    @Test
    public void constructor_defaultsNullsSensibly() {
        final DiscountCode dc = new DiscountCode("id", "CODE", "desc", null, null, null, null, null);
        Assert.assertEquals(dc.getDiscountType(), DiscountCode.DiscountType.DISCOUNT_BY,
                "discountType defaults to DISCOUNT_BY");
        Assert.assertNotNull(dc.getValidOptionIds(), "validOptionIds defaults to empty list, never null");
        Assert.assertTrue(dc.getValidOptionIds().isEmpty());
        Assert.assertTrue(dc.getEnabled(), "enabled defaults true");
    }

    @Test
    public void constructor_keepsExplicitValues() {
        final LocalDate exp = LocalDate.of(2026, 6, 30);
        final DiscountCode dc = new DiscountCode("id1", "EARLYBIRD2026", "Early bird", exp,
                DiscountCode.DiscountType.EXACT, new BigDecimal("250.00"), List.of("full", "full-rosen"), false);
        Assert.assertEquals(dc.getId(), "id1");
        Assert.assertEquals(dc.getCode(), "EARLYBIRD2026");
        Assert.assertEquals(dc.getDescription(), "Early bird");
        Assert.assertEquals(dc.getExpiration(), exp);
        Assert.assertEquals(dc.getDiscountType(), DiscountCode.DiscountType.EXACT);
        Assert.assertEquals(dc.getAmount().compareTo(new BigDecimal("250.00")), 0);
        Assert.assertEquals(dc.getValidOptionIds(), List.of("full", "full-rosen"));
        Assert.assertFalse(dc.getEnabled());
    }

    @Test
    public void validOptionIdsString_getReturnsCsv() {
        final DiscountCode dc = new DiscountCode("id", "C", "d", null,
                DiscountCode.DiscountType.EXACT, BigDecimal.ZERO, List.of("full", "full-rosen"), true);
        Assert.assertEquals(dc.getValidOptionIdsString(), "full,full-rosen");
    }

    @Test
    public void validOptionIdsString_getEmptyWhenNone() {
        final DiscountCode dc = new DiscountCode("id", "C", "d", null, null, BigDecimal.ZERO, null, true);
        Assert.assertEquals(dc.getValidOptionIdsString(), "");
    }

    @Test
    public void validOptionIdsString_setParsesAndTrims() {
        final DiscountCode dc = new DiscountCode("id", "C", "d", null, null, BigDecimal.ZERO, null, true);
        dc.setValidOptionIdsString(" full , full-rosen ,, sat ");
        Assert.assertEquals(dc.getValidOptionIds(), List.of("full", "full-rosen", "sat"),
                "whitespace trimmed and empty entries dropped");
    }

    @Test
    public void validOptionIdsString_setBlankClearsList() {
        final DiscountCode dc = new DiscountCode("id", "C", "d", null, null, BigDecimal.ZERO,
                List.of("full"), true);
        dc.setValidOptionIdsString("   ");
        Assert.assertTrue(dc.getValidOptionIds().isEmpty());
    }

    @Test
    public void roundTripJson_serializesDaysNotHelper() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final DiscountCode dc = new DiscountCode("id1", "EARLYBIRD2026", "Early bird",
                LocalDate.of(2026, 6, 30), DiscountCode.DiscountType.EXACT, new BigDecimal("250.00"),
                List.of("full", "full-rosen"), true);
        final String json = mapper.writeValueAsString(dc);
        Assert.assertFalse(json.contains("validOptionIdsString"), "CSV helper must not appear in JSON");
        Assert.assertTrue(json.contains("\"validOptionIds\""), "validOptionIds must appear in JSON");

        final DiscountCode restored = mapper.readValue(json, DiscountCode.class);
        Assert.assertEquals(restored.getId(), "id1");
        Assert.assertEquals(restored.getCode(), "EARLYBIRD2026");
        Assert.assertEquals(restored.getExpiration(), LocalDate.of(2026, 6, 30));
        Assert.assertEquals(restored.getDiscountType(), DiscountCode.DiscountType.EXACT);
        Assert.assertEquals(restored.getAmount().compareTo(new BigDecimal("250.00")), 0);
        Assert.assertEquals(restored.getValidOptionIds(), List.of("full", "full-rosen"));
        Assert.assertTrue(restored.getEnabled());
    }
}
