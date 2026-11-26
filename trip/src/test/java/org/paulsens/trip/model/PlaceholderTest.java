package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

/** A prompt's two optional checks (2026-11-25): a menu's own options and the CSS length format. */
public class PlaceholderTest {

    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();

    @Test
    public void optionsAndFormatRoundTripAndAreLeftOutWhenUnset() throws Exception {
        final Placeholder menu = new Placeholder("tone", Placeholder.Type.CHOICE, "Tone", "h", false, "dark",
                List.of("plain", "dark"), Placeholder.FORMAT_CSS_LENGTH);
        final Placeholder back = MAPPER.readValue(MAPPER.writeValueAsString(menu), Placeholder.class);
        Assert.assertEquals(back, menu);
        Assert.assertTrue(back.isFixedChoice());

        final String plainJson = MAPPER.writeValueAsString(new Placeholder("t", Placeholder.Type.TEXT, "T", null,
                false));
        Assert.assertFalse(plainJson.contains("options"), plainJson);
        Assert.assertFalse(plainJson.contains("format"), plainJson);
        Assert.assertFalse(plainJson.contains("optionsText"), plainJson);
        Assert.assertFalse(plainJson.contains("fixedChoice"), plainJson);
    }

    /** A row stored before the fields existed reads with none, and a menu without options is no fixed menu. */
    @Test
    public void anOlderRowReadsWithNoOptionsAndNoFormat() throws Exception {
        final Placeholder old = MAPPER.readValue(
                "{\"name\":\"tone\",\"type\":\"TEXT\",\"label\":\"Tone\",\"hint\":\"h\",\"required\":false,"
                        + "\"sample\":\"tint\"}", Placeholder.class);
        Assert.assertEquals(old.getOptions(), List.of());
        Assert.assertNull(old.getFormat());
        Assert.assertFalse(old.isFixedChoice());
        Assert.assertFalse(new Placeholder("c", Placeholder.Type.CHOICE, "C", null, false).isFixedChoice(),
                "a provider's CHOICE has no options of its own");
    }

    @Test
    public void optionsAreCleanedAndCopiedIndependently() {
        final Placeholder ph = new Placeholder("s", Placeholder.Type.CHOICE, "S", null, false, null,
                Arrays.asList(" left ", "", null, "right", "left"), " ");
        Assert.assertEquals(ph.getOptions(), List.of("left", "right"));
        Assert.assertNull(ph.getFormat(), "a blank format is none");
        Assert.assertEquals(ph.getOptionsText(), "left, right");
        final Placeholder copy = ph.copy();
        copy.setOptionsText("up");
        Assert.assertEquals(ph.getOptions(), List.of("left", "right"), "editing a copy leaves the original");
        copy.setOptionsText(null);
        Assert.assertEquals(copy.getOptions(), List.of());
        copy.setOptions(new ArrayList<>(List.of(" ")));
        Assert.assertEquals(copy.getOptions(), List.of(), "nothing left is none");
    }
}
