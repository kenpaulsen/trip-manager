package org.paulsens.trip.chat;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Fans a notification out to every enabled route, containing each one's failures.
 *
 * <p>Mirrors {@code CompositeAuditSink}: one broken route must not deny delivery to the others, so a throwing
 * notifier is logged and stepped over rather than allowed to abort the fan-out. That matters more here than it looks
 * — the routes are independent products (mail now, push later) and a failure in a new one should not silently switch
 * off the one people actually rely on.
 */
@Slf4j
public final class CompositeChatNotifier implements ChatNotifier {

    private final List<ChatNotifier> routes;

    public CompositeChatNotifier(final List<ChatNotifier> routes) {
        this.routes = routes == null ? List.of() : List.copyOf(routes);
    }

    @Override
    public Channel channel() {
        return Channel.IN_APP;
    }

    @Override
    public boolean isEnabled() {
        return routes.stream().anyMatch(ChatNotifier::isEnabled);
    }

    @Override
    public void notify(final ChatNotification notification) {
        for (final ChatNotifier route : routes) {
            notifyOne(route, notification);
        }
    }

    private void notifyOne(final ChatNotifier route, final ChatNotification notification) {
        if (!route.isEnabled()) {
            return;
        }
        try {
            route.notify(notification);
        } catch (final RuntimeException ex) {
            log.warn("Chat notifier {} failed", route.channel(), ex);
        }
    }

    @Override
    public void close() {
        for (final ChatNotifier route : routes) {
            closeOne(route);
        }
    }

    private void closeOne(final ChatNotifier route) {
        try {
            route.close();
        } catch (final RuntimeException ex) {
            log.warn("Unable to close chat notifier {}", route.channel(), ex);
        }
    }
}
