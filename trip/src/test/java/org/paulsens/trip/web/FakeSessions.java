package org.paulsens.trip.web;

import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Sessions that really hold what is put on them. Each is a Mockito mock over a map, so a test can both assert
 * on the map and {@code Mockito.verify} calls such as {@code invalidate()}.
 *
 * <p>Get, set, remove and the attribute names all work: a fake that answered only get and set let
 * {@link Sessions}' attribute sweep (which removes, and walks the names) pass over it silently.
 */
public final class FakeSessions {
    private FakeSessions() {
    }

    /** A session whose attributes live in the given map, so the test can read and seed the map directly. */
    public static HttpSession over(final Map<String, Object> attrs) {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(ArgumentMatchers.anyString()))
                .thenAnswer(call -> attrs.get(call.<String>getArgument(0)));
        Mockito.doAnswer(call -> attrs.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(ArgumentMatchers.anyString(), ArgumentMatchers.any());
        Mockito.doAnswer(call -> attrs.remove(call.<String>getArgument(0)))
                .when(session).removeAttribute(ArgumentMatchers.anyString());
        // A copy of the names: the sweep removes attributes while it walks them.
        Mockito.when(session.getAttributeNames())
                .thenAnswer(call -> Collections.enumeration(new ArrayList<>(attrs.keySet())));
        return session;
    }

    /** A session of its own, for a test that only reads back through the session. */
    public static HttpSession fresh() {
        return over(new HashMap<>());
    }
}
