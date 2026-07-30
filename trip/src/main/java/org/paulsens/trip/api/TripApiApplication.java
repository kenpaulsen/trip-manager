package org.paulsens.trip.api;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;
import org.glassfish.jersey.jackson.JacksonFeature;

/**
 * JAX-RS application root. Application path is {@code /} because the Jersey servlet is already mapped at
 * {@code /api/*} in {@code web.xml}. Chat resources use media-type versioning — no {@code /v1} path segment.
 */
@ApplicationPath("/")
public class TripApiApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(
                ChatResource.class,
                ChatAuthFilter.class,
                JsonExceptionMapper.class,
                // Must accompany JacksonFeature: without it Jackson uses a default mapper with no JavaTimeModule
                // and every Instant goes out as a bare epoch decimal. See ObjectMapperProvider.
                ObjectMapperProvider.class,
                JacksonFeature.class);
    }
}
