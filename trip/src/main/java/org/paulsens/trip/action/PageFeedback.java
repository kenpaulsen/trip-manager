package org.paulsens.trip.action;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.primefaces.PrimeFaces;

/**
 * How a command bean talks back to the page that called it: a growl, a refusal, an ajax callback param.
 *
 * <p>These used to be private helpers on nine command beans and an inline guard on eight more, each a copy
 * of the last. Two rules live in them, and each copy had to remember both on its own. The message goes in
 * the SUMMARY: the template's growl renders a detail only when the page sets {@code hasDetail}, which only
 * an {@code ?infoDetail=}-style query parameter does, so on an ajax postback a detail is invisible -- the
 * profile and chat dialogs had been showing their users "Not allowed" and "No photo" with the explanation
 * silently dropped. And a callback param is published only inside an ajax Faces request, because outside
 * one (a seed, a REST call, a unit test) there is no partial response to put it in. The two-arg forms
 * exist because a headline plus an explanation is how most callers naturally think; {@link #message} joins
 * them into the one line the growl can actually show.
 *
 * <p>Every method is a no-op outside a Faces request, so a command that reports this way stays callable from
 * anywhere; {@link TripUtilCommands#addMessage} logs the dropped message so the reason is not lost.
 */
public final class PageFeedback {

    private PageFeedback() {
    }

    public static void info(final String message) {
        message(FacesMessage.SEVERITY_INFO, message, null);
    }

    public static void warn(final String message) {
        message(FacesMessage.SEVERITY_WARN, message, null);
    }

    public static void error(final String message) {
        message(FacesMessage.SEVERITY_ERROR, message, null);
    }

    /** A headline and its explanation, shown as one line: see {@link #message}. */
    public static void info(final String summary, final String detail) {
        message(FacesMessage.SEVERITY_INFO, summary, detail);
    }

    public static void warn(final String summary, final String detail) {
        message(FacesMessage.SEVERITY_WARN, summary, detail);
    }

    public static void error(final String summary, final String detail) {
        message(FacesMessage.SEVERITY_ERROR, summary, detail);
    }

    /** Growls the reason and answers {@code false}: the shape of a command the page reads as a boolean. */
    public static boolean refuse(final String reason) {
        error(reason);
        return false;
    }

    public static boolean refuse(final String summary, final String detail) {
        error(summary, detail);
        return false;
    }

    /**
     * The one place a headline and its explanation are joined. A {@code FacesMessage} has both fields, and
     * 140-odd call sites (Java and pages) filled both -- but the template's growl shows only the summary on
     * an ajax postback, so the explanation was silently dropped everywhere. Rather than fix every caller,
     * the pair is rendered as {@code "Not saved: A setting needs a name."}; a summary that already ends a
     * sentence just gets the detail after it. The detail field is left empty on purpose: were a page ever
     * to set {@code hasDetail}, the text must not appear twice.
     */
    public static void message(final FacesMessage.Severity severity, final String summary,
            final String detail) {
        TripUtilCommands.addFacesMessage(severity, compose(summary, detail), "");
    }

    static String compose(final String summary, final String detail) {
        if (detail == null || detail.isBlank()) {
            return summary;
        }
        if (summary == null || summary.isBlank()) {
            return detail;
        }
        final char last = summary.charAt(summary.length() - 1);
        final String joiner = (last == '.' || last == '!' || last == '?' || last == ':') ? " " : ": ";
        return summary + joiner + detail;
    }

    /**
     * Growls the reason and answers the empty outcome, which keeps the browser on the page: the shape of an
     * {@code action=} method whose String return would otherwise be fed to navigation.
     */
    public static String refuseAction(final String reason) {
        error(reason);
        return "";
    }

    /**
     * Publishes one named ajax callback param, readable as {@code args.<name>} in a component's
     * {@code oncomplete}.
     *
     * @param name   the callback param name.
     * @param value  its value; serialized into the partial response by PrimeFaces.
     */
    public static void callbackParam(final String name, final Object value) {
        if (FacesContext.getCurrentInstance() == null) {
            return;
        }
        final PrimeFaces pf = PrimeFaces.current();
        if (pf != null && pf.isAjaxRequest()) {
            pf.ajax().addCallbackParam(name, value);
        }
    }
}
