package org.paulsens.trip.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import org.paulsens.trip.audit.RequestContext;

/**
 * Stamps every API response with the language it was answered in ({@code docs/i18n.md},
 * {@code docs/api-contract.md} "Languages").
 *
 * <p>A client cannot assume its {@code Accept-Language} was honored: the site may not offer that language, or
 * an older app build is being answered in the default on purpose. {@code Content-Language} is how it knows what
 * the server-authored text in the body actually is. The language itself was resolved before the resource ran, by
 * {@code SessionRecoveryFilter}; this only reports it.
 */
@Provider
public class ContentLanguageFilter implements ContainerResponseFilter {

    @Override
    public void filter(final ContainerRequestContext request, final ContainerResponseContext response) {
        response.getHeaders().putSingle(HttpHeaders.CONTENT_LANGUAGE, RequestContext.currentLocale().toLanguageTag());
    }
}
