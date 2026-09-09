package org.paulsens.trip.jsf;

import jakarta.faces.FacesException;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.application.ViewExpiredException;
import jakarta.faces.context.ExceptionHandler;
import jakarta.faces.context.ExceptionHandlerWrapper;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.ExceptionQueuedEvent;
import jakarta.faces.event.ExceptionQueuedEventContext;
import java.util.Iterator;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.TripUtilCommands;

/**
 * Keeps an unhandled exception during an AJAX postback on the page it happened on.
 *
 * <p>Without this, Mojarra hands an unhandled exception to the container error page, and {@code web.xml}'s
 * catch-all entry is {@code /index.jsf} -- so the partial response is a redirect and the browser leaves for
 * the home page. That is a bad outcome anywhere, but on the trip editor it is destructive: the working copy
 * lives in {@code TripEditDrafts} keyed by a token held in the view, and a fresh GET of the edit page starts a
 * NEW draft from saved state. Every edit made since the last Save is gone, with no message saying so.
 *
 * <p>It has bitten real users at least twice from unrelated causes: the duplicate trip-event check throwing
 * out of a JSFT command (2026-09-08, four times in eleven minutes for one trip manager), and Tomcat's
 * {@code maxPartCount} ceiling aborting large multipart posts (see the {@code h:form} comment in
 * {@code template.xhtml}, which describes the same "lands back on the home page" symptom). Fixing each cause
 * is right; so is making the generic failure mode non-destructive, because the next cause is unknown.
 *
 * <p>What it deliberately does NOT do:
 * <ul>
 *   <li>Touch non-ajax requests. A full page submit has no partial response to salvage, and the error pages
 *       already send those somewhere navigable.</li>
 *   <li>Swallow {@link ViewExpiredException}. That one has its own error page ({@code /timeout.jsf}) telling
 *       the user to sign in again, which is the correct and more useful answer; a growl on a view the server
 *       no longer has would leave the page dead but looking alive.</li>
 *   <li>Hide anything. Every exception it handles is logged at ERROR with its stack, exactly as before.</li>
 * </ul>
 */
@Slf4j
public class TripAjaxExceptionHandler extends ExceptionHandlerWrapper {

    /**
     * The template's one and only growl ({@code h:form id="form"} in {@code template.xhtml}), forced into the
     * render ids because the failing component's own {@code update=} may not name it -- and because
     * {@code autoUpdate} does not fire on an ajax response.
     */
    static final String GROWL_ID = "form:growl";

    /** Deliberately vague: whatever failed, the actionable part for the user is that nothing was lost. */
    static final String MESSAGE =
            "Something went wrong and that action did not complete. Your unsaved changes are still here, so you "
            + "can try again. If it keeps happening, please report it.";

    public TripAjaxExceptionHandler(final ExceptionHandler wrapped) {
        super(wrapped);
    }

    @Override
    public void handle() throws FacesException {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.getPartialViewContext().isAjaxRequest()) {
            keepAjaxErrorsOnThePage(ctx);
        }
        // Anything left in the queue (non-ajax, or a ViewExpiredException we passed over) keeps stock behavior.
        getWrapped().handle();
    }

    /** Drains the exceptions this handler is willing to report in place, leaving the rest for Mojarra. */
    private void keepAjaxErrorsOnThePage(final FacesContext ctx) {
        boolean handledAny = false;
        final Iterator<ExceptionQueuedEvent> events = getUnhandledExceptionQueuedEvents().iterator();
        while (events.hasNext()) {
            final ExceptionQueuedEventContext event = (ExceptionQueuedEventContext) events.next().getSource();
            final Throwable error = event.getException();
            if (isViewExpired(error)) {
                continue;
            }
            log.error("Reporting an ajax failure in place rather than redirecting home.", error);
            events.remove();
            handledAny = true;
        }
        if (handledAny) {
            report(ctx);
        }
    }

    /** Adds the growl, makes sure it is actually re-rendered, and skips straight to rendering the response. */
    private void report(final FacesContext ctx) {
        ctx.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, MESSAGE, ""));
        // Best effort: a page without the standard template still gets the message in its own message list.
        ctx.getPartialViewContext().getRenderIds().add(GROWL_ID);
        TripUtilCommands.addCallbackParam("actionFailed", true);
        ctx.renderResponse();
    }

    /**
     * True when the cause chain holds a {@link ViewExpiredException}. Mojarra wraps the real exception in a
     * {@code FacesException} on the way to the queue, so the top-level type is not enough to decide.
     */
    private static boolean isViewExpired(final Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ViewExpiredException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
