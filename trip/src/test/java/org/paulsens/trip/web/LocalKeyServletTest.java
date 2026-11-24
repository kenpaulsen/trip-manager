package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Function;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.AlbumLibrary;
import org.paulsens.trip.action.BadgePhotoCommands;
import org.paulsens.trip.action.BrandingPhotos;
import org.paulsens.trip.action.LodgingUploadCommands;
import org.paulsens.trip.action.TripPictureCommands;
import org.paulsens.trip.action.TripStylesheets;
import org.paulsens.trip.api.Beans;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * What the key-store servlets share, checked once across all of them: the per-servlet tests inject their store,
 * so none of them sees how a servlet the CONTAINER built finds it. That is {@code Beans.get} (which selects with
 * the Any qualifier), asked on the first request and never again.
 */
public class LocalKeyServletTest {

    private static final byte[] BYTES = {7, 8, 9};

    /** One servlet as the container makes it, the store it must look up, and a key that store holds. */
    private record Case<S>(LocalKeyServlet servlet, Class<S> storeType, String key, String contentType,
            String cacheControl, Function<S, Optional<byte[]>> read) {
    }

    @DataProvider
    public Object[][] servlets() {
        return new Object[][] {
            {new Case<>(new BadgePhotoServlet(), BadgePhotoCommands.class, "badgeImages/trip-1/1.jpg", "image/jpeg",
                    LocalKeyServlet.PRIVATE_HOUR, store -> store.localGet("badgeImages/trip-1/1.jpg"))},
            {new Case<>(new LodgingPhotoServlet(), LodgingUploadCommands.class, "lodging/acc/a.jpg", "image/jpeg",
                    LocalKeyServlet.PRIVATE_HOUR, store -> store.localGet("lodging/acc/a.jpg"))},
            {new Case<>(new TripPictureServlet(), TripPictureCommands.class, "tripPictures/trip-1/1-480.jpg",
                    "image/jpeg", LocalKeyServlet.PUBLIC_HOUR,
                    store -> store.localGet("tripPictures/trip-1/1-480.jpg"))},
            {new Case<>(new BrandingPhotoServlet(), BrandingPhotos.class, "org/org-1/branding/logo-1.png",
                    "image/png", LocalKeyServlet.PRIVATE_HOUR,
                    store -> store.localGet("org/org-1/branding/logo-1.png"))},
            {new Case<>(new AlbumLibraryServlet(), AlbumLibrary.class, "org/a/album-photos/1.jpg", "image/jpeg",
                    LocalKeyServlet.PUBLIC_HOUR, store -> store.localGet("org/a/album-photos/1.jpg"))},
            {new Case<>(new StylesheetServlet(), TripStylesheets.class, "org/org-1/stylesheets/pw-styles.css",
                    "text/css; charset=utf-8", "no-cache",
                    store -> store.localGet("org/org-1/stylesheets/pw-styles.css"))},
        };
    }

    @Test(dataProvider = "servlets")
    public <S> void aContainerBuiltServletFindsItsStoreOnceAndServesFromIt(final Case<S> served) throws IOException {
        final S store = Mockito.mock(served.storeType());
        Mockito.when(served.read().apply(store)).thenReturn(Optional.of(BYTES));
        final HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getPathInfo()).thenReturn("/" + served.key());
        final HttpServletResponse resp = Mockito.mock(HttpServletResponse.class);
        final ServletOutputStream body = Mockito.mock(ServletOutputStream.class);
        Mockito.when(resp.getOutputStream()).thenReturn(body);

        try (MockedStatic<Beans> beans = Mockito.mockStatic(Beans.class)) {
            beans.when(() -> Beans.get(served.storeType())).thenReturn(store);
            served.servlet().doGet(req, resp);
            served.servlet().doGet(req, resp);
            beans.verify(() -> Beans.get(served.storeType()), Mockito.times(1));
        }

        Mockito.verify(body, Mockito.times(2)).write(BYTES);
        Mockito.verify(resp, Mockito.times(2)).setContentType(served.contentType());
        Mockito.verify(resp, Mockito.times(2)).setContentLength(BYTES.length);
        Mockito.verify(resp, Mockito.times(2)).setHeader("Cache-Control", served.cacheControl());
        Mockito.verify(resp, Mockito.never()).sendError(Mockito.anyInt());
    }

    /** The refusals every one of them makes before its store is asked: no path, and a path that climbs. */
    @Test(dataProvider = "servlets")
    public <S> void aMissingOrClimbingPathIs404WithoutAskingTheStore(final Case<S> served) throws IOException {
        try (MockedStatic<Beans> beans = Mockito.mockStatic(Beans.class)) {
            for (final String path : new String[] {null, "/" + served.key().replaceFirst("/", "/../")}) {
                final HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
                Mockito.when(req.getPathInfo()).thenReturn(path);
                final HttpServletResponse resp = Mockito.mock(HttpServletResponse.class);
                served.servlet().doGet(req, resp);
                Mockito.verify(resp).sendError(HttpServletResponse.SC_NOT_FOUND);
            }
            beans.verifyNoInteractions();
        }
    }
}
