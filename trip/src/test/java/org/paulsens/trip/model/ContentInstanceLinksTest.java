package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.HashMap;
import org.testng.Assert;
import org.testng.annotations.Test;

/** A trip page's section links: which sections they list by default, the editor's choice, and its JSON shape. */
public class ContentInstanceLinksTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static ContentInstance of(final String templateId) {
        return new ContentInstance("i", "s", "Title", templateId, 1, new HashMap<>(), null, 0, 0, null, "me");
    }

    @Test
    public void aSectionIsListedUnlessItsTemplateDrawsNothingToGoTo() {
        Assert.assertTrue(of("band-text").isShownInLinks());
        Assert.assertTrue(of(null).isShownInLinks(), "a row with no template id is listed as before");
        Assert.assertFalse(of("spacer").isShownInLinks(), "a spacer is nothing to go to");
        Assert.assertFalse(of("trip-pictures").isShownInLinks(), "the trip's pictures are the top of the page");
        Assert.assertFalse(of("trip-details").isShownInLinks());
    }

    @Test
    public void tickingTheBoxToTheDefaultRecordsNoChoice() {
        final ContentInstance text = of("band-text");
        text.setShownInLinks(true);
        Assert.assertNull(text.getInLinks(), "the default is no choice: later defaults still reach this row");
        final ContentInstance spacer = of("spacer");
        spacer.setShownInLinks(false);
        Assert.assertNull(spacer.getInLinks());
        text.setShownInLinks(false);
        text.setShownInLinks(true);
        Assert.assertNull(text.getInLinks(), "unticked then ticked again is back to no choice");
    }

    @Test
    public void theEditorsChoiceWinsAndIsCopied() {
        final ContentInstance spacer = of("spacer");
        spacer.setShownInLinks(true);
        Assert.assertTrue(spacer.isShownInLinks());
        Assert.assertEquals(spacer.getInLinks(), Boolean.TRUE);
        final ContentInstance text = of("band-text");
        text.setShownInLinks(false);
        Assert.assertFalse(text.isShownInLinks());
        Assert.assertFalse(text.copy().isShownInLinks(), "the drawer's working copy keeps the choice");
        Assert.assertEquals(text.copy().getInLinks(), Boolean.FALSE);
    }

    @Test
    public void theChoiceIsWrittenOnlyWhenMadeAndOldRowsRead() throws Exception {
        final String untouched = JSON.writeValueAsString(of("band-text"));
        Assert.assertFalse(untouched.contains("inLinks"), "an untouched row's JSON is unchanged: " + untouched);
        Assert.assertFalse(untouched.contains("shownInLinks"), "the derived view is never stored: " + untouched);
        final ContentInstance chosen = of("band-text");
        chosen.setShownInLinks(false);
        final String written = JSON.writeValueAsString(chosen);
        Assert.assertTrue(written.contains("\"inLinks\":false"), written);
        Assert.assertFalse(JSON.readValue(written, ContentInstance.class).isShownInLinks());
        final ContentInstance old = JSON.readValue("{\"id\":\"o\",\"templateId\":\"band-text\"}",
                ContentInstance.class);
        Assert.assertNull(old.getInLinks());
        Assert.assertTrue(old.isShownInLinks(), "a row written before the choice is listed as it always was");
    }
}
