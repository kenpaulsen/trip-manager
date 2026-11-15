package org.paulsens.trip.action;

import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import java.util.Map;
import org.mockito.Mockito;

/**
 * A mock Faces request for the bean tests: a FacesContext whose external context answers the test's own request
 * map (what {@code RequestMemo} memoizes into), installed as the current instance. Always pair {@link #install}
 * with {@link #clear} in a finally block: the instance is thread-bound and would leak into the next test.
 * {@code FacesContext.setCurrentInstance} is protected, hence the abstract subclass.
 */
abstract class TestFaces extends FacesContext {

    static FacesContext install(final Map<String, Object> requestMap) {
        final ExternalContext external = Mockito.mock(ExternalContext.class);
        Mockito.when(external.getRequestMap()).thenReturn(requestMap);
        final FacesContext ctx = Mockito.mock(FacesContext.class);
        Mockito.when(ctx.getExternalContext()).thenReturn(external);
        FacesContext.setCurrentInstance(ctx);
        return ctx;
    }

    static void clear() {
        FacesContext.setCurrentInstance(null);
    }
}
