package org.paulsens.trip.api;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;
import org.glassfish.jersey.jackson.JacksonFeature;

/**
 * JAX-RS application root. Application path is {@code /} because the Jersey servlet is already mapped at
 * {@code /api/*} in {@code web.xml}. Resources use media-type versioning — no {@code /v1} path segment.
 *
 * <p>This set is explicit; there is no package scanning. A new resource, filter or provider that is not added
 * here does not fail — it 404s, with nothing in the log to say why. Check this file first when a route that
 * plainly exists cannot be reached.
 */
@ApplicationPath("/")
public class TripApiApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(
                AuditResource.class,
                AuthResource.class,
                ChatAdminResource.class,
                ChatResource.class,
                ConfigResource.class,
                DeployResource.class,
                MailResource.class,
                PrivilegesResource.class,
                PaymentsResource.class,
                PeopleResource.class,
                RegistrationsResource.class,
                TodosResource.class,
                TransactionsResource.class,
                TripsResource.class,
                TripAuthFilter.class,
                JsonExceptionMapper.class,
                // Must accompany JacksonFeature: without it Jackson uses a default mapper with no JavaTimeModule
                // and every Instant goes out as a bare epoch decimal. See ObjectMapperProvider.
                ObjectMapperProvider.class,
                JacksonFeature.class);
    }
}
