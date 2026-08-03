package org.paulsens.trip.api;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Name-binding that requires a signed-in session: applies {@link TripAuthFilter}.
 *
 * <p>Note what forgetting this does. An unbound resource is not a broken resource that fails loudly -- it is an
 * <em>open</em> resource that answers everybody. Every resource on this API carries it, and {@code AuthResource}
 * is the single deliberate exception, at the method level, for {@code login}.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface TripApi {
}
