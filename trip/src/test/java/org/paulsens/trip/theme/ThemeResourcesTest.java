package org.paulsens.trip.theme;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.mockito.Mockito;
import org.testng.annotations.Test;

/**
 * The one reader of theme jar files answers from the web application first, then the class path, and degrades to
 * null (never a throw) when a file is missing or unreadable.
 */
public class ThemeResourcesTest {

    private static final String MANIFEST = "freya-layout/custom-theme-inputs.json";

    @Test
    public void theWebApplicationAnswersFirst() {
        final ExternalContext external = Mockito.mock(ExternalContext.class);
        Mockito.when(external.getResourceAsStream("/" + MANIFEST))
                .thenReturn(new ByteArrayInputStream("{\"from\":\"app\"}".getBytes(StandardCharsets.UTF_8)));
        final FacesContext ctx = Mockito.mock(FacesContext.class);
        Mockito.when(ctx.getExternalContext()).thenReturn(external);
        Faces.set(ctx);
        try {
            assertEquals(ThemeResources.read(MANIFEST), "{\"from\":\"app\"}");
            assertNull(ThemeResources.read("missing/file.txt"), "app, then class path: neither has it");
        } finally {
            Faces.set(null);
        }
    }

    @Test
    public void theClassPathAnswersWithoutFaces() {
        assertNotNull(ThemeResources.read(MANIFEST), "the test class path carries the generated manifest");
        assertNotNull(ThemeResources.themeCss("freya-blue-light"));
        assertNull(ThemeResources.themeCss("freya-nope-light"));
    }

    @Test
    public void withoutAContextLoaderItsOwnLoaderAnswers() {
        final Thread thread = Thread.currentThread();
        final ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            assertNotNull(ThemeResources.read(MANIFEST));
        } finally {
            thread.setContextClassLoader(before);
        }
    }

    @Test
    public void anUnreadableFileIsNull() {
        final ExternalContext external = Mockito.mock(ExternalContext.class);
        Mockito.when(external.getResourceAsStream("/broken.txt")).thenReturn(new FailingStream());
        final FacesContext ctx = Mockito.mock(FacesContext.class);
        Mockito.when(ctx.getExternalContext()).thenReturn(external);
        Faces.set(ctx);
        try {
            assertNull(ThemeResources.read("broken.txt"));
        } finally {
            Faces.set(null);
        }
    }

    /** A stream that fails on read, as a truncated jar entry would. */
    private static final class FailingStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("truncated");
        }
    }

    /** {@code FacesContext.setCurrentInstance} is protected, so reach it from a subclass. */
    private abstract static class Faces extends FacesContext {
        static void set(final FacesContext ctx) {
            FacesContext.setCurrentInstance(ctx);
        }
    }
}
