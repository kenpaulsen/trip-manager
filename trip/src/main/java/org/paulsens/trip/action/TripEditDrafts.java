package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;

/**
 * Work-in-progress Trip edits, held OUTSIDE every JSF scope. The edit pages accumulate partial ajax submits
 * (event dialog, registration options, field edits) into one working copy across many postbacks, which used
 * to mean a full {@code Trip} riding in viewScope — and viewScope is serialized into the HTTP session by
 * server-side state saving, which is how the 2026-08-14 outage happened (one added Trip field, every live
 * session unreadable, a site-wide 500 for returning visitors). Here the working copy stays on this process's
 * heap; the view keeps only the token.
 *
 * <p>Same token-in-session/object-elsewhere pattern as {@code PendingUploads}, and claimable only by the
 * {@code owner} who started it, so a leaked or guessed token buys nothing but that admin's own draft.
 * {@link #get} returns the SAME instance every request — accumulation works because each postback resolves
 * and mutates one heap object, exactly what viewScope used to provide without the serialization tax.
 *
 * <p>Lifecycle: a draft dies on {@link #discard} (Save/Cancel), after {@link #IDLE_TTL} without a postback,
 * or on container restart (the registry is heap-only, deliberately: a deploy discards in-flight edit drafts
 * the same way it discards pending uploads — the page restarts the draft from the saved trip). Abandoning an
 * edit mid-way costs nothing and leaks nothing; the sweep runs inline on {@link #start}, which is enough
 * because a stale draft only occupies a few KB until the next edit begins. Single-task deployment makes the
 * in-heap holder safe — there is no second instance for a postback to land on.
 */
@Named("tripEditDrafts")
@ApplicationScoped
public class TripEditDrafts {

    /**
     * Idle time, not total time: {@link #get} refreshes it on every postback, so a long editing session
     * never expires under the editor — only a genuinely abandoned draft does. Generous because trip edits
     * (descriptions especially) are written slowly, and a Trip is a few KB, not a photo.
     */
    static final Duration IDLE_TTL = Duration.ofHours(2);

    /** Far above any real number of concurrent editors; a cap so an unbounded map is impossible. */
    static final int MAX_DRAFTS = 200;

    /** One working copy. {@code lastTouched} mutates under the map's lock. */
    private static final class Draft {
        private final Trip trip;
        private final Person.Id owner;
        private Instant lastTouched;

        private Draft(final Trip trip, final Person.Id owner, final Instant lastTouched) {
            this.trip = trip;
            this.owner = owner;
            this.lastTouched = lastTouched;
        }
    }

    private final SecureRandom random = new SecureRandom();

    /** Access-ordered so the eldest entry is always the least-recently-touched. All access synchronized. */
    private final Map<String, Draft> byToken = new LinkedHashMap<>(16, 0.75f, true);

    /** Instance rather than the constant so a test can exercise eviction without 200 fixtures. */
    private int maxDrafts = MAX_DRAFTS;

    void maxDraftsForTest(final int max) {
        this.maxDrafts = max;
    }

    /**
     * Registers a working copy and returns its freshly minted token, or null when there is nothing to
     * register — no owner means an unauthenticated request that the page's auth gate is about to bounce
     * anyway, and refusing it here keeps anonymous URL-hammering from filling the registry.
     */
    public String start(final Trip workingCopy, final Person.Id owner) {
        return start(workingCopy, owner, Instant.now());
    }

    /** Test seam: lets a test supply {@code lastTouched} so expiry is exercisable without sleeping. */
    String start(final Trip workingCopy, final Person.Id owner, final Instant lastTouched) {
        if (workingCopy == null || owner == null) {
            return null;
        }
        final String token = newToken();
        synchronized (byToken) {
            drainIdle();
            byToken.put(token, new Draft(workingCopy, owner, lastTouched));
            final var iterator = byToken.entrySet().iterator();
            while (byToken.size() > maxDrafts && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        return token;
    }

    /**
     * The working copy — the SAME instance every call, so edits accumulate — only while the draft exists,
     * has not idled out, and belongs to {@code owner}. Null otherwise; the page treats null as "start over
     * from the saved trip", the same loss surface an expired view had before this registry existed.
     */
    public Trip get(final String token, final Person.Id owner) {
        if (token == null || owner == null) {
            return null;
        }
        synchronized (byToken) {
            final Draft draft = byToken.get(token);
            if (draft == null || isIdle(draft) || !owner.equals(draft.owner)) {
                return null;
            }
            draft.lastTouched = Instant.now();
            return draft.trip;
        }
    }

    /** Ends a draft once it is saved or cancelled. Unknown/null tokens are a no-op, not an error. */
    public void discard(final String token) {
        if (token == null) {
            return;
        }
        synchronized (byToken) {
            byToken.remove(token);
        }
    }

    int size() {
        synchronized (byToken) {
            return byToken.size();
        }
    }

    private void drainIdle() {
        final var iterator = byToken.entrySet().iterator();
        while (iterator.hasNext()) {
            if (isIdle(iterator.next().getValue())) {
                iterator.remove();
            }
        }
    }

    private static boolean isIdle(final Draft draft) {
        return draft.lastTouched.isBefore(Instant.now().minus(IDLE_TTL));
    }

    private String newToken() {
        final byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
