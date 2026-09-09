package org.paulsens.trip.jsf;

import jakarta.faces.FacesException;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.application.ViewExpiredException;
import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerFactory;
import jakarta.faces.context.FacesContext;
import jakarta.faces.context.PartialViewContext;
import jakarta.faces.event.ExceptionQueuedEvent;
import jakarta.faces.event.ExceptionQueuedEventContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * {@link TripAjaxExceptionHandler} -- the catch-all that keeps a failed ajax postback on its own page.
 *
 * <p>Why it exists: {@code web.xml}'s catch-all {@code error-page} is {@code /index.jsf}, so an unhandled
 * exception during an ajax postback used to be answered with a redirect home. On the trip editor that also
 * discards the {@code TripEditDrafts} working copy (its token lived in the view that just died), silently
 * losing every edit since the last Save. Two unrelated causes have reached users this way: the duplicate
 * trip-event throw (2026-09-08) and Tomcat's {@code maxPartCount} ceiling on large multipart posts.
 */
public class TripAjaxExceptionHandlerTest {

    @AfterMethod
    void clearContext() {
        TestFacesContext.clear();
    }

    /** A generic ajax failure is reported in place: drained from the queue, growled, response rendered. */
    @Test
    public void ajaxFailureIsReportedOnThePage() {
        final List<ExceptionQueuedEvent> queue = queueOf(new FacesException(new IllegalStateException("dup")));
        final FacesContext ctx = TestFacesContext.install(true);
        final ExceptionHandler wrapped = Mockito.mock(ExceptionHandler.class);
        Mockito.when(wrapped.getUnhandledExceptionQueuedEvents()).thenReturn(queue);

        new TripAjaxExceptionHandler(wrapped).handle();

        Assert.assertTrue(queue.isEmpty(), "The exception must be drained, or Mojarra still redirects home");
        Mockito.verify(ctx).renderResponse();
        final ArgumentCaptor<FacesMessage> msg = ArgumentCaptor.forClass(FacesMessage.class);
        Mockito.verify(ctx).addMessage(Mockito.isNull(), msg.capture());
        Assert.assertEquals(msg.getValue().getSeverity(), FacesMessage.SEVERITY_ERROR);
        Assert.assertEquals(msg.getValue().getSummary(), TripAjaxExceptionHandler.MESSAGE);
        Assert.assertTrue(msg.getValue().getDetail().isEmpty(),
                "The growl renders a detail only where the page sets hasDetail, so it must all be in the summary");
        Assert.assertTrue(TestFacesContext.RENDER_IDS.contains(TripAjaxExceptionHandler.GROWL_ID),
                "autoUpdate does not fire on an ajax response, so the growl must be forced into the render ids");
        // The wrapped handler still runs, for anything this one chose not to take.
        Mockito.verify(wrapped).handle();
    }

    /**
     * A dead view keeps its own error page: {@code /timeout.jsf} tells the user to sign in again, which is a
     * better answer than a growl on a page the server can no longer restore.
     */
    @Test
    public void viewExpiredIsLeftToTheErrorPage() {
        final List<ExceptionQueuedEvent> queue =
                queueOf(new FacesException(new ViewExpiredException("gone", "/trip/edit.jsf")));
        final FacesContext ctx = TestFacesContext.install(true);
        final ExceptionHandler wrapped = Mockito.mock(ExceptionHandler.class);
        Mockito.when(wrapped.getUnhandledExceptionQueuedEvents()).thenReturn(queue);

        new TripAjaxExceptionHandler(wrapped).handle();

        Assert.assertEquals(queue.size(), 1, "ViewExpiredException must reach the stock handler untouched");
        Mockito.verify(ctx, Mockito.never()).renderResponse();
        Mockito.verify(ctx, Mockito.never()).addMessage(Mockito.any(), Mockito.any());
        Mockito.verify(wrapped).handle();
    }

    /** A full page submit has no partial response to salvage; the existing error pages already handle it. */
    @Test
    public void nonAjaxRequestsAreUntouched() {
        final List<ExceptionQueuedEvent> queue = queueOf(new FacesException(new IllegalStateException("boom")));
        final FacesContext ctx = TestFacesContext.install(false);
        final ExceptionHandler wrapped = Mockito.mock(ExceptionHandler.class);
        Mockito.when(wrapped.getUnhandledExceptionQueuedEvents()).thenReturn(queue);

        new TripAjaxExceptionHandler(wrapped).handle();

        Assert.assertEquals(queue.size(), 1);
        Mockito.verify(ctx, Mockito.never()).renderResponse();
        Mockito.verify(wrapped).handle();
    }

    /** Several failures in one postback collapse to a single growl, not one per exception. */
    @Test
    public void manyFailuresProduceOneMessage() {
        final List<ExceptionQueuedEvent> queue = queueOf(
                new FacesException(new IllegalStateException("one")),
                new FacesException(new IllegalArgumentException("two")));
        final FacesContext ctx = TestFacesContext.install(true);
        final ExceptionHandler wrapped = Mockito.mock(ExceptionHandler.class);
        Mockito.when(wrapped.getUnhandledExceptionQueuedEvents()).thenReturn(queue);

        new TripAjaxExceptionHandler(wrapped).handle();

        Assert.assertTrue(queue.isEmpty());
        Mockito.verify(ctx, Mockito.times(1)).addMessage(Mockito.isNull(), Mockito.any());
    }

    /** No FacesContext at all (a background thread) must not NPE on the way through. */
    @Test
    public void withoutAFacesContextItJustDelegates() {
        TestFacesContext.clear();
        final ExceptionHandler wrapped = Mockito.mock(ExceptionHandler.class);
        new TripAjaxExceptionHandler(wrapped).handle();
        Mockito.verify(wrapped).handle();
    }

    /**
     * The factory is what makes any of this run: it is named in {@code faces-config.xml}, and a handler nobody
     * builds is a handler that silently stops protecting the edit pages.
     */
    @Test
    public void theFactoryBuildsTheHandlerAroundTheWrappedOne() {
        final ExceptionHandler delegate = Mockito.mock(ExceptionHandler.class);
        final ExceptionHandlerFactory wrapped = Mockito.mock(ExceptionHandlerFactory.class);
        Mockito.when(wrapped.getExceptionHandler()).thenReturn(delegate);

        final ExceptionHandler handler = new TripAjaxExceptionHandlerFactory(wrapped).getExceptionHandler();

        Assert.assertTrue(handler instanceof TripAjaxExceptionHandler);
        Assert.assertSame(((TripAjaxExceptionHandler) handler).getWrapped(), delegate);
    }

    private static List<ExceptionQueuedEvent> queueOf(final Throwable... errors) {
        final List<ExceptionQueuedEvent> queue = new ArrayList<>();
        for (final Throwable error : errors) {
            final ExceptionQueuedEventContext source = Mockito.mock(ExceptionQueuedEventContext.class);
            Mockito.when(source.getException()).thenReturn(error);
            final ExceptionQueuedEvent event = Mockito.mock(ExceptionQueuedEvent.class);
            Mockito.when(event.getSource()).thenReturn(source);
            queue.add(event);
        }
        return queue;
    }

    /** {@code FacesContext.setCurrentInstance} is protected, so reach it from a subclass. */
    private abstract static class TestFacesContext extends FacesContext {
        static final Set<String> RENDER_IDS = new HashSet<>();

        static FacesContext install(final boolean ajax) {
            RENDER_IDS.clear();
            final PartialViewContext partial = Mockito.mock(PartialViewContext.class);
            Mockito.when(partial.isAjaxRequest()).thenReturn(ajax);
            Mockito.when(partial.getRenderIds()).thenReturn(RENDER_IDS);
            final FacesContext ctx = Mockito.mock(FacesContext.class);
            Mockito.when(ctx.getPartialViewContext()).thenReturn(partial);
            FacesContext.setCurrentInstance(ctx);
            return ctx;
        }

        static void clear() {
            FacesContext.setCurrentInstance(null);
        }
    }
}
