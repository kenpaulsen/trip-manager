package org.paulsens.trip.push;

/**
 * What one send to one device came to. A {@link PushGateway} answers exactly one of these and never throws:
 * a push is a side effect of something already acknowledged, so the worst a broken push service can cost is
 * the notification.
 */
public enum PushOutcome {
    /** Accepted by the push service (delivery to the device is the service's problem from here). */
    DELIVERED,
    /**
     * The service says this device is gone for good -- an unregistered token, a subscription the browser
     * dropped. The sender prunes it; retrying would fail the same way forever.
     */
    DROP_DEVICE,
    /** Transient: the service was busy, unreachable, or answered 5xx. Nothing is pruned. */
    RETRY,
    /** Our credentials were refused (bad key, wrong team, expired VAPID). An operator problem, not a device one. */
    AUTH,
    /** Refused for a reason that is neither the device's nor transient (payload too large, bad request). */
    FAILED
}
