package org.paulsens.trip.push;

/**
 * One push transport: APNs for phones, each browser's push service for web subscriptions. The seam that
 * lets {@link PushSender} stay transport-blind and lets local mode and tests record instead of send.
 *
 * <p><b>{@link #send} never throws.</b> Every failure is an outcome, because the sender's job is to prune a
 * dead device and move on to the next one, not to unwind a fan-out.
 */
public interface PushGateway {

    PushOutcome send(PushDevice device, PushPayload payload);
}
