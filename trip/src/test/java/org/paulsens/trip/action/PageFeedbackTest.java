package org.paulsens.trip.action;

import org.testng.Assert;
import org.testng.annotations.Test;

/** The one join rule for a headline and its explanation, now that ~140 call sites rely on it. */
public class PageFeedbackTest {

    @Test
    public void aHeadlineAndAnExplanationBecomeOneLine() {
        Assert.assertEquals(PageFeedback.compose("Not saved", "A setting needs a name."),
                "Not saved: A setting needs a name.");
    }

    /** A summary that already ends a sentence is not given a second stop. */
    @Test
    public void aFinishedSentenceTakesTheDetailAfterASpace() {
        Assert.assertEquals(PageFeedback.compose("Invitation sent to a@b.", "They can sign in now."),
                "Invitation sent to a@b. They can sign in now.");
        Assert.assertEquals(PageFeedback.compose("'Pack' Todo Created!", "It is on your list."),
                "'Pack' Todo Created! It is on your list.");
        Assert.assertEquals(PageFeedback.compose("Really?", "Yes."), "Really? Yes.");
        Assert.assertEquals(PageFeedback.compose("Note:", "x"), "Note: x");
    }

    @Test
    public void aMissingHalfIsJustTheOtherHalf() {
        Assert.assertEquals(PageFeedback.compose("Saved", null), "Saved");
        Assert.assertEquals(PageFeedback.compose("Saved", "  "), "Saved");
        Assert.assertEquals(PageFeedback.compose(null, "Only detail"), "Only detail");
        Assert.assertEquals(PageFeedback.compose("", "Only detail"), "Only detail");
    }

    /** Every entry point is safe with no FacesContext: the message is logged, never thrown away loudly. */
    @Test
    public void everyFormIsHarmlessOffAFacesThread() {
        PageFeedback.info("i");
        PageFeedback.warn("w");
        PageFeedback.error("e");
        PageFeedback.info("i", "d");
        PageFeedback.warn("w", "d");
        PageFeedback.error("e", "d");
        Assert.assertFalse(PageFeedback.refuse("r"));
        Assert.assertFalse(PageFeedback.refuse("r", "d"));
        Assert.assertEquals(PageFeedback.refuseAction("r"), "");
        PageFeedback.callbackParam("k", true);
    }
}
