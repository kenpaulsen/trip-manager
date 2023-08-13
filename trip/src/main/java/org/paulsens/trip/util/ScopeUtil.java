package org.paulsens.trip.util;

import jakarta.faces.context.FacesContext;
import java.util.function.Supplier;

public class ScopeUtil {
    @SuppressWarnings("unchecked")
    public static <T> T fromApplicationScope(final String key, final Supplier<T> fallback) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null) {
            return fallback.get();
        }
        return (T) ctx.getExternalContext().getApplicationMap().get(key);
    }
}
