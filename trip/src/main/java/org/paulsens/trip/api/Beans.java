package org.paulsens.trip.api;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.CDI;

/**
 * How a resource gets hold of a command bean.
 *
 * <p>There was no single answer before this. {@code ChatCommands} has a static factory that returns a shared
 * instance because it owns a rate limiter that must not be duplicated; {@code PersonCommands} has one that
 * returns a fresh instance, which is safe only because it is stateless; the other beans have no non-JSF entry
 * point at all.
 *
 * <p>The tempting fill-in for the rest -- {@code new TodoCommands()} -- is wrong in a way that does not show up
 * until runtime: it bypasses CDI, so every {@code @Inject}ed collaborator on the bean is null. Going through
 * {@link CDI} returns the same {@code @ApplicationScoped} instance JSF is using, fully injected.
 */
public final class Beans {

    private Beans() {
    }

    /**
     * The bean of the given type.
     *
     * <p>Selected with {@link Any} rather than by bare type. A plain {@code select(type)} asks for the
     * {@code @Default} qualifier, and a bean that carries any qualifier of its own other than {@code @Named}
     * does not get {@code @Default} -- so it is simply not found. {@code PassCommands} is exactly that case: it
     * is annotated {@code @FacesConfig}, which is a qualifier, and a bare select throws
     * {@code UnsatisfiedResolutionException} at runtime for it while resolving every other command bean
     * perfectly. Nothing at compile time distinguishes the two, and no unit test sees either, because there is
     * no CDI container in the test JVM -- this surfaced only against the real container.
     */
    public static <T> T get(final Class<T> type) {
        return CDI.current().select(type, Any.Literal.INSTANCE).get();
    }
}
