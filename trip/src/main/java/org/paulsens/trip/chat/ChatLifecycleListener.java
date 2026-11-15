package org.paulsens.trip.chat;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ChatPhotos;
import org.paulsens.trip.action.MediaEvents;
import org.paulsens.trip.action.PhotoChatCommands;
import org.paulsens.trip.reminder.ReminderScheduler;

/**
 * Starts and stops the two daily schedulers with the webapp: the chat digest's and the trip reminders'
 * ({@code reminder/ReminderScheduler}, {@code docs/reminders.md}), which rides this listener because the
 * listener order lives in the deployed {@code web.xml}.
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
            // The trip reminder scheduler (docs/reminders.md) starts here rather than from a listener of its
            // own: the listener order lives in the deployed web.xml (another repo), and this listener is already
            // placed after the bootstrap. Both need the DAO, so what stops one stops the other.
            ReminderScheduler.start();
        } catch (final RuntimeException ex) {
            log.error("Unable to start the schedulers; chat digests and scheduled trip messages will not be sent",
                    ex);
        }
        // A removed chat photo takes its comment thread with it, whichever path removed it (message
        // moderation or the admin media page) — both fire the REMOVED media event.
        MediaEvents.onPrefix(ChatPhotos.KEY_PREFIX, PhotoChatCommands::onMediaChange);
    }

    @Override
    public void contextDestroyed(final ServletContextEvent event) {
        try {
            ReminderScheduler.stop();
            ChatDigestScheduler.stop();
        } catch (final RuntimeException ex) {
            log.warn("Unable to stop the schedulers cleanly", ex);
        }
    }
}
