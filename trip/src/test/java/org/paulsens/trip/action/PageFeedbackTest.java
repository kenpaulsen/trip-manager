package org.paulsens.trip.action;

import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.Flash;
import jakarta.faces.context.PartialViewContext;
import java.util.ArrayList;
import java.util.List;
import org.mockito.Mockito;
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

    /** A refusal for a form to show: handed over whole, answered false, and no growl beside it. */
    @Test
    public void refuseIntoHandsTheReasonOverAndAnswersFalse() {
        final List<String> got = new ArrayList<>();
        Assert.assertFalse(PageFeedback.refuseInto(got::add, "No."));
        Assert.assertEquals(got, List.of("No."));
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
        PageFeedback.failure("f");
        PageFeedback.callbackParam("k", true);
        PageFeedback.keepForNextPage();
        PageFeedback.alsoRender("form:x");
    }

    /**
     * On a faces thread: a message is kept for the page a redirect leads to, and a region is added to what an
     * ajax request redraws only when the request IS ajax.
     */
    @Test
    public void aRequestCanKeepItsMessagesAndAddARegionToRedraw() {
        final FacesContext context = Mockito.mock(FacesContext.class);
        final ExternalContext external = Mockito.mock(ExternalContext.class);
        final Flash flash = Mockito.mock(Flash.class);
        final PartialViewContext partial = Mockito.mock(PartialViewContext.class);
        final List<String> redrawn = new ArrayList<>();
        Mockito.when(context.getExternalContext()).thenReturn(external);
        Mockito.when(external.getFlash()).thenReturn(flash);
        Mockito.when(context.getPartialViewContext()).thenReturn(partial);
        Mockito.when(partial.getRenderIds()).thenReturn(redrawn);
        TestFaces.install(context);
        try {
            PageFeedback.keepForNextPage();
            Mockito.verify(flash).setKeepMessages(true);

            Mockito.when(partial.isAjaxRequest()).thenReturn(false);
            PageFeedback.alsoRender("form:contentDlg");
            Assert.assertEquals(redrawn, List.of(), "a full page request redraws everything anyway");

            Mockito.when(partial.isAjaxRequest()).thenReturn(true);
            PageFeedback.alsoRender("form:contentDlg");
            Assert.assertEquals(redrawn, List.of("form:contentDlg"));
        } finally {
            TestFaces.clear();
        }
    }
}
