package org.paulsens.trip.util;

import jakarta.faces.context.FacesContext;
import java.util.function.Function;
import java.util.function.Supplier;

public class ScopeUtil {
    private static final ScopeUtil INSTANCE = new ScopeUtil();

    public static ScopeUtil getInstance() {
        return INSTANCE;
    }

    public <T> T getSessionMap(final String key) {
        return withCtx(ctx -> (T) ctx.getExternalContext().getSessionMap().get(key), () -> null);
    }

    public <T> T getViewMap(final String key) {
        return withCtx(ctx -> (T) ctx.getViewRoot().getViewMap().get(key), () -> null);
    }

    public <T> T getRequestMap(final String key) {
        return withCtx(ctx -> (T) ctx.getExternalContext().getRequestMap().get(key), () -> null);
    }

    private <T> T withCtx(final Function<FacesContext, T> usingFacesContext, final Supplier<T> whenNoFacesContext) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return (ctx == null) ? whenNoFacesContext.get() : usingFacesContext.apply(ctx);
    }
}
