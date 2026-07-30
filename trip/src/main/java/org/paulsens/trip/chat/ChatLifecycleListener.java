package org.paulsens.trip.chat;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import lombok.extern.slf4j.Slf4j;

/**
 * Starts and stops the chat digest scheduler with the webapp.
 *
 * <p>Declared by hand in {@code web.xml}, not annotated: {@code metadata-complete="true"} means
 * {@code @WebListener} is ignored outright, which fails silently — the listener simply never runs and nobody ever
 * receives a digest.
 *
 * <p>Startup must not be able to break the app. The scheduler is a background convenience; if it cannot start, the
 * site still serves chat, so the failure is logged and swallowed rather than allowed to abort context
 * initialisation.
 */
@Slf4j
public class ChatLifecycleListener implements ServletContextListener {

    @Override
    public void contextInitialized(final ServletContextEvent event) {
        try {
            ChatDigestScheduler.start();
        } catch (final RuntimeException ex) {
            log.error("Unable to start the chat digest scheduler; digests will not be sent", ex);
        }
    }

    @Override
    public void contextDestroyed(final ServletContextEvent event) {
        try {
            ChatDigestScheduler.stop();
        } catch (final RuntimeException ex) {
            log.warn("Unable to stop the chat digest scheduler cleanly", ex);
        }
    }
}
