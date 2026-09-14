package org.paulsens.trip.push;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * The gateway for local mode, tests, and a deployment with no push secret: records every send (for
 * assertions and the local recipe) and answers a fixed outcome. Logs only the kind and the device label --
 * never the payload body, which carries chat snippets.
 */
@Slf4j
public final class LoggingPushGateway implements PushGateway {

    /** One recorded send. */
    public record Sent(PushDevice device, PushPayload payload) {
    }

    /** Bounded so a long-running local container cannot grow it without limit. */
    static final int MAX_RECORDED = 200;

    private final PushOutcome outcome;
    private final String why;
    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

    /** Records and answers DELIVERED: local mode and tests. */
    public LoggingPushGateway() {
        this(PushOutcome.DELIVERED, null);
    }

    /**
     * @param why logged once with the first send when the outcome is not DELIVERED (a production task with
     *        no secret says so exactly once rather than per push)
     */
    public LoggingPushGateway(final PushOutcome outcome, final String why) {
        this.outcome = outcome;
        this.why = why;
    }

    @Override
    public PushOutcome send(final PushDevice device, final PushPayload payload) {
        if (device == null || payload == null) {
            return PushOutcome.FAILED;
        }
        if (why != null && sent.isEmpty()) {
            log.warn("Push not sent ({}): {}", outcome, why);
        }
        log.info("push[{}] {} -> {} '{}' ({})", outcome, payload.getKind(), device.getKind().wire(),
                device.getLabel(), device.id());
        if (sent.size() >= MAX_RECORDED) {
            sent.remove(0);
        }
        sent.add(new Sent(device, payload));
        return outcome;
    }

    /** Everything sent so far, oldest first. */
    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }
}
