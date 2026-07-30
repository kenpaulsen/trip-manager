package org.paulsens.trip.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import org.paulsens.trip.dynamo.DAO;

/**
 * Gives Jersey the application's configured {@link ObjectMapper} instead of a default one.
 *
 * <p>Without this, {@code JacksonFeature} builds its own bare mapper, which has no {@code JavaTimeModule} — so
 * every {@code Instant} serialised as a bare epoch decimal. The chat feed shipped {@code "sentAt":1785406690.025},
 * which the page rendered literally next to the author's name, and worse: the client does
 * {@code Date.parse(m.sentAt)} to decide whether a message is still inside its edit window, and parsing that
 * yields {@code NaN}, so <b>the Edit button never appeared for anyone</b>.
 *
 * <p>Reusing the DAO's mapper rather than configuring a second one is deliberate: these are the same model
 * objects that go to DynamoDB, so a separate REST mapper is a second set of rules to drift out of step. It brings
 * ISO-8601 dates, {@code NON_NULL} inclusion (absent and null read the same in JavaScript), and tolerance of
 * unknown properties, which a rolling deploy needs on the wire as much as in the cache.
 */
@Provider
public class ObjectMapperProvider implements ContextResolver<ObjectMapper> {

    @Override
    public ObjectMapper getContext(final Class<?> type) {
        return DAO.getInstance().getMapper();
    }
}
