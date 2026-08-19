package org.paulsens.trip.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;

/**
 * The cache-invalidation broadcast: when one instance clears a shared-cache namespace (admin button, REST
 * endpoint, migration-script hook), every OTHER instance must forget its near-cache heap copies of those
 * keys. The initiator has already cleared Valkey and its own heap via {@code clearNamespace}, so the event
 * is an instruction to drop LOCAL heap only -- never data, and never a second Valkey sweep.
 *
 * <p>The {@code origin} id makes the split unambiguous: each JVM stamps its own broadcasts and ignores
 * them on receipt. A lost or garbled event costs only freshness until the polling soft-TTL backstop heals
 * it, so decoding failures are logged and dropped, never thrown.</p>
 */
@Slf4j
public final class CacheInvalidation {

    /** This JVM's broadcast identity; receivers skip events they originated. */
    static final String ORIGIN = UUID.randomUUID().toString();

    /** Own mapper on purpose: this class must not reach into the DAO for one, and the payload is tiny. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Publishes an invalidation for the given key prefixes. Best-effort: failures are logged only. */
    public static void broadcast(final CacheClient client, final List<String> prefixes) {
        try {
            client.publish(CacheKeys.CACHE_INVAL_CHANNEL,
                    MAPPER.writeValueAsString(new Event(ORIGIN, prefixes)));
        } catch (final JsonProcessingException | RuntimeException ex) {
            log.warn("Unable to broadcast cache invalidation for {}; other instances heal via soft TTL",
                    prefixes, ex);
        }
    }

    /** The subscribe() handler, bound to the client whose heap it maintains. */
    public static BiConsumer<String, String> handlerFor(final CacheClient client) {
        return (channel, payload) -> handle(client, payload);
    }

    static void handle(final CacheClient client, final String payload) {
        final Event event = decode(payload);
        if (event == null || event.prefixes() == null || ORIGIN.equals(event.origin())) {
            return; // own broadcast: this JVM already cleared Valkey AND its heap in clearNamespace
        }
        if (client instanceof NearCacheClient near) {
            event.prefixes().forEach(near::dropLocalNamespace);
        }
    }

    private static Event decode(final String payload) {
        try {
            return MAPPER.readValue(payload, Event.class);
        } catch (final JsonProcessingException ex) {
            log.warn("Dropping unparseable cache-invalidation payload: {}", payload);
            return null;
        }
    }

    record Event(String origin, List<String> prefixes) {
    }

    private CacheInvalidation() {
    }
}
