package org.paulsens.trip.action;

import jakarta.faces.context.FacesContext;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-request memoization in the Faces request map, the ONE copy of the idiom: a page's EL evaluates the same
 * value several times per render (and again in each JSF phase), so a bean that answers from a read or a
 * computation keeps the answer for the rest of the request.
 *
 * <p>What it is not: a cache. The map dies with the request, so a value never outlives a write by more than the
 * request that made it, and a write within the same request is invisible to a key already answered; a caller
 * that must see its own write either reads fresh or {@link #forget forgets} the key. Outside a Faces request (a
 * REST call, a background thread, a unit test without a context) there is no map and every call computes.
 */
final class RequestMemo {

    private RequestMemo() {
    }

    /** The current Faces request's map, or null outside a Faces request. */
    static Map<String, Object> facesRequestMap() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return ctx == null ? null : ctx.getExternalContext().getRequestMap();
    }

    /** {@link #get(Map, String, Supplier)} on the current Faces request's map. */
    static <T> T get(final String key, final Supplier<T> compute) {
        return get(facesRequestMap(), key, compute);
    }

    /**
     * The value memoized under {@code key} in {@code map}, computing and storing it on the first ask; with no map
     * (null), simply computed. A null answer is memoized too ({@code containsKey}), so a lookup that finds nothing
     * is not repeated. Keys carry their own prefix: two beans sharing a key would read each other's values.
     */
    @SuppressWarnings("unchecked")
    static <T> T get(final Map<String, Object> map, final String key, final Supplier<T> compute) {
        if (map == null) {
            return compute.get();
        }
        if (map.containsKey(key)) {
            return (T) map.get(key);
        }
        final T value = compute.get();
        map.put(key, value);
        return value;
    }

    /** Drops one memoized key (after a write the rest of the request must see); a no-op with no map. */
    static void forget(final Map<String, Object> map, final String key) {
        if (map != null) {
            map.remove(key);
        }
    }
}
