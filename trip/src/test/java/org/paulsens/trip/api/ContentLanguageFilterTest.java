package org.paulsens.trip.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.mockito.Mockito;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.model.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link ContentLanguageFilter}: every API response names the language it was answered in. */
public class ContentLanguageFilterTest {

    @Test
    public void theResponseCarriesTheRequestsLanguage() {
        Assert.assertEquals(stamped(), "en", "off a request: English");
        final RequestContext croatian = RequestContext.system().withLocale(Language.Croatian.getLocale());
        Assert.assertEquals(ScopedValue.where(RequestContext.SCOPE, croatian).call(this::stamped), "hr");
    }

    @Test
    public void theFilterIsRegisteredWithTheApplication() {
        Assert.assertTrue(new TripApiApplication().getClasses().contains(ContentLanguageFilter.class));
    }

    private String stamped() {
        final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        // A resource that set its own header must not leave two values behind.
        headers.add("Content-Language", "xx");
        final ContainerResponseContext response = Mockito.mock(ContainerResponseContext.class);
        Mockito.when(response.getHeaders()).thenReturn(headers);
        new ContentLanguageFilter().filter(Mockito.mock(ContainerRequestContext.class), response);
        Assert.assertEquals(headers.get("Content-Language").size(), 1);
        return String.valueOf(headers.getFirst("Content-Language"));
    }
}
