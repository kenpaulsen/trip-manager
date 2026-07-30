package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalTime;
import lombok.Value;

/**
 * Notification preferences stored inside a {@link ChatMembership} row. Defaults keep email and push off so v1
 * needs no migration when P3 turns delivery on. No SMS fields — SMS is dropped, not deferred.
 */
@Value
public class ChatNotifyPref implements Serializable {

    public enum DeliveryMode {
        OFF,
        MENTIONS,
        ALL,
        DIGEST_HOURLY,
        DIGEST_DAILY
    }

    boolean inApp;
    DeliveryMode email;
    /** Reserved; inert until APNs lands. */
    DeliveryMode push;
    LocalTime quietHoursStart;
    LocalTime quietHoursEnd;
    String timeZone;

    @JsonCreator
    public ChatNotifyPref(
            @JsonProperty("inApp") final Boolean inApp,
            @JsonProperty("email") final DeliveryMode email,
            @JsonProperty("push") final DeliveryMode push,
            @JsonProperty("quietHoursStart") final LocalTime quietHoursStart,
            @JsonProperty("quietHoursEnd") final LocalTime quietHoursEnd,
            @JsonProperty("timeZone") final String timeZone) {
        this.inApp = inApp == null || inApp;
        this.email = email == null ? DeliveryMode.OFF : email;
        this.push = push == null ? DeliveryMode.OFF : push;
        this.quietHoursStart = quietHoursStart;
        this.quietHoursEnd = quietHoursEnd;
        this.timeZone = timeZone;
    }

    public static ChatNotifyPref defaults() {
        return new ChatNotifyPref(true, DeliveryMode.OFF, DeliveryMode.OFF, null, null, null);
    }
}
