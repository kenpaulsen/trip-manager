package org.paulsens.trip.chat;

/**
 * A delivery route for chat notifications, shaped after {@code audit.AuditSink} so the composition, the failure
 * behaviour and the off-thread dispatch are all the pattern this codebase already proved under a real outage.
 *
 * <p><b>{@link #notify} must never throw and never block the sender.</b> A notification is a side effect of a
 * message that has already been acknowledged; a mail provider being slow or down must cost the notification, never
 * the send. Implementations swallow their own failures and log them.
 */
public interface ChatNotifier {

    enum Channel {
        IN_APP,
        EMAIL,
        PUSH
    }

    Channel channel();

    /** Whether this route is usable right now. A disabled route is skipped without being asked to deliver. */
    boolean isEnabled();

    /** Deliver, or quietly give up. Never throws. */
    void notify(ChatNotification notification);

    default void close() {
    }
}
