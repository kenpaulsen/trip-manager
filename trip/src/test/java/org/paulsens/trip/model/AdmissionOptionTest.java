package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Unit tests for {@link AdmissionOption}, focused on the constructor defaults, the JSON
 * round-trip, and the {@code daysString} CSV helpers used by the admin editor.
 */
public class AdmissionOptionTest {

    @Test
    public void constructor_defaultsNullsSensibly() {
        final AdmissionOption opt = new AdmissionOption("id", "Label", new BigDecimal("10.00"), null, null, null);
        Assert.assertNotNull(opt.getDays(), "days defaults to empty list, never null");
        Assert.assertTrue(opt.getDays().isEmpty());
        Assert.assertFalse(opt.getRosenDiscounted(), "rosenDiscounted defaults false");
        Assert.assertTrue(opt.getShow(), "show defaults true");
    }

    @Test
    public void daysString_getReturnsCsv() {
        final AdmissionOption opt = new AdmissionOption(
                "id", "Label", BigDecimal.ZERO, List.of("FRI", "SAT", "SUN"), false, true);
        Assert.assertEquals(opt.getDaysString(), "FRI,SAT,SUN");
    }

    @Test
    public void daysString_getEmptyWhenNoDays() {
        final AdmissionOption opt = new AdmissionOption("id", "Label", BigDecimal.ZERO, null, false, true);
        Assert.assertEquals(opt.getDaysString(), "");
    }

    @Test
    public void daysString_setParsesAndTrims() {
        final AdmissionOption opt = new AdmissionOption("id", "Label", BigDecimal.ZERO, null, false, true);
        opt.setDaysString(" FRI , SAT ,, SUN ");
        Assert.assertEquals(opt.getDays(), List.of("FRI", "SAT", "SUN"),
                "whitespace trimmed and empty entries dropped");
    }

    @Test
    public void daysString_setBlankClearsList() {
        final AdmissionOption opt = new AdmissionOption(
                "id", "Label", BigDecimal.ZERO, List.of("FRI"), false, true);
        opt.setDaysString("   ");
        Assert.assertTrue(opt.getDays().isEmpty());
    }

    @Test
    public void daysString_isNotSerializedButDaysIs() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final AdmissionOption opt = new AdmissionOption(
                "full-std", "Full Weekend", new BigDecimal("340.00"), List.of("FRI", "SAT", "SUN"), false, true);
        final String json = mapper.writeValueAsString(opt);
        Assert.assertFalse(json.contains("daysString"), "daysString must not appear in JSON");
        Assert.assertTrue(json.contains("\"days\""), "days must appear in JSON");

        final AdmissionOption restored = mapper.readValue(json, AdmissionOption.class);
        Assert.assertEquals(restored.getId(), "full-std");
        Assert.assertEquals(restored.getPrice().compareTo(new BigDecimal("340.00")), 0);
        Assert.assertEquals(restored.getDays(), List.of("FRI", "SAT", "SUN"));
    }
}
