package org.paulsens.trip.theme;

import jakarta.faces.context.FacesContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * The ONE reader of files the Freya theme jar ships ({@code META-INF/resources/...}): a compiled
 * {@code primefaces-freya-<theme>/theme.css}, the custom palette's {@code freya-layout/custom-theme-inputs.json}.
 * Through the web application first (the jar's {@code META-INF/resources} is part of it), then the thread's context
 * class loader, then this class's own, so the same path answers in the container, in an embedded Tomcat and in a
 * unit test that puts a stand-in on the class path. Null when none of them has it, never thrown: a missing theme
 * file degrades a page, it must not break it. Only an I/O failure is logged here; a caller that needs the file logs
 * its absence.
 */
@Slf4j
public final class ThemeResources {

    private ThemeResources() {
    }

    /** The text of {@code path} (relative to {@code META-INF/resources}, no leading slash), or null. */
    public static String read(final String path) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final String fromApp = ctx == null ? null
                : read(() -> ctx.getExternalContext().getResourceAsStream("/" + path), path);
        if (fromApp != null) {
            return fromApp;
        }
        final ClassLoader context = Thread.currentThread().getContextClassLoader();
        final String fromContext = context == null ? null
                : read(() -> context.getResourceAsStream("META-INF/resources/" + path), path);
        if (fromContext != null) {
            return fromContext;
        }
        final ClassLoader own = ThemeResources.class.getClassLoader();
        return read(() -> own.getResourceAsStream("META-INF/resources/" + path), path);
    }

    /** A compiled theme's text: {@code freya-blue-light} reads {@code primefaces-freya-blue-light/theme.css}. */
    public static String themeCss(final String theme) {
        return read("primefaces-" + theme + "/theme.css");
    }

    private static String read(final Supplier<InputStream> source, final String path) {
        try (InputStream in = source.get()) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            log.warn("Unable to read the theme resource " + path, ex);
            return null;
        }
    }
}
