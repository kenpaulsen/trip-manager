package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.model.PrivacySettings.Visibility;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PrivacySettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(PrivacySettings.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void defaultsMatchThePolicy() {
        final PrivacySettings settings = new PrivacySettings();
        Assert.assertEquals(settings.getEmail(), Visibility.LOGGED_IN);
        Assert.assertEquals(settings.getCell(), Visibility.LOGGED_IN);
        Assert.assertEquals(settings.getCity(), Visibility.LOGGED_IN);
        Assert.assertEquals(settings.getStreet(), Visibility.PRIVATE, "Street defaults private");
        Assert.assertTrue(settings.isEmailVisible());
        Assert.assertTrue(settings.isCellVisible());
        Assert.assertTrue(settings.isCityVisible());
        Assert.assertFalse(settings.isStreetVisible());
    }

    @Test
    public void nullsInJsonFallBackToDefaults() throws Exception {
        final PrivacySettings settings = mapper.readValue("{\"email\":null,\"street\":null}", PrivacySettings.class);
        Assert.assertEquals(settings, new PrivacySettings(), "Missing/null knobs must read as the defaults");
    }

    /** A visible street with a private city must still hide the street -- the rule lives in the model, not the UI. */
    @Test
    public void privateCityForcesStreetPrivate() {
        final PrivacySettings settings = new PrivacySettings(
                Visibility.LOGGED_IN, Visibility.LOGGED_IN, Visibility.PRIVATE, Visibility.LOGGED_IN);
        Assert.assertFalse(settings.isCityVisible());
        Assert.assertFalse(settings.isStreetVisible(), "Street must follow a private city");
        settings.setCity(Visibility.LOGGED_IN);
        Assert.assertTrue(settings.isStreetVisible());
    }

    @Test
    public void roundTripsThroughJson() throws Exception {
        final PrivacySettings before = new PrivacySettings(
                Visibility.PRIVATE, Visibility.PRIVATE, Visibility.LOGGED_IN, Visibility.LOGGED_IN);
        final PrivacySettings after = mapper.readValue(mapper.writeValueAsString(before), PrivacySettings.class);
        Assert.assertEquals(after, before, "To/from json failed!");
    }
}
