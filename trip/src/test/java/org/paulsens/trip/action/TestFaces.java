package org.paulsens.trip.action;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * A mock Faces request for the bean tests: a FacesContext whose external context answers the test's own request
 * map (what {@code RequestMemo} memoizes into), installed as the current instance. Always pair {@link #install}
 * with {@link #clear} in a finally block: the instance is thread-bound and would leak into the next test.
 * {@code FacesContext.setCurrentInstance} is protected, hence the abstract subclass.
 */
public abstract class TestFaces extends FacesContext {

    public static FacesContext install(final Map<String, Object> requestMap) {
        final ExternalContext external = Mockito.mock(ExternalContext.class);
        Mockito.when(external.getRequestMap()).thenReturn(requestMap);
        final FacesContext ctx = Mockito.mock(FacesContext.class);
        Mockito.when(ctx.getExternalContext()).thenReturn(external);
        return install(ctx);
    }

    /** Installs a context the test stubbed itself (a partial view, a servlet request, a flash). */
    public static FacesContext install(final FacesContext ctx) {
        FacesContext.setCurrentInstance(ctx);
        return ctx;
    }

    public static void clear() {
        FacesContext.setCurrentInstance(null);
    }

    /**
     * Every message {@code call} adds to a request answering {@code requestMap}, in order: what the page's growl
     * would show, with each one's severity (so a warning is told from an info line). Installs and clears itself.
     */
    public static List<FacesMessage> messagesOf(final Map<String, Object> requestMap, final Runnable call) {
        final FacesContext ctx = install(requestMap);
        try {
            call.run();
            final ArgumentCaptor<FacesMessage> messages = ArgumentCaptor.forClass(FacesMessage.class);
            Mockito.verify(ctx, Mockito.atLeast(0)).addMessage(Mockito.any(), messages.capture());
            return messages.getAllValues();
        } finally {
            clear();
        }
    }

    /** The summaries of {@link #messagesOf}: the growl lines alone. */
    public static List<String> growlsOf(final Map<String, Object> requestMap, final Runnable call) {
        return messagesOf(requestMap, call).stream().map(FacesMessage::getSummary).toList();
    }
}
