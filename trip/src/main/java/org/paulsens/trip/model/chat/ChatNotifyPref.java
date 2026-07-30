package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalTime;
import lombok.Value;

/**
 * Notification preferences stored inside a {@link ChatMembership} row. No SMS fields — SMS is dropped, not
 * deferred.
 *
 * <p><b>Email defaults to {@link DeliveryMode#MENTIONS}</b>: being named in a trip's chat is the one case worth
 * interrupting someone for, and the people on these trips are not watching the site. Digests stay a positive
 * opt-in, so nobody is mailed a daily rollup they never asked for. Push stays off until APNs exists.
 *
 * <p>Because {@code null} normalises to the default, this applies to rows already written <em>and</em> to implicit
 * members who have no row at all — so turning {@code chat.mail.enabled} on will mail people who never visited a
 * preferences screen. That is the intent, and it is why an unusable address must be treated as OFF rather than
 * attempted (see {@code EmailAddresses}).
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
        this.email = email == null ? DeliveryMode.MENTIONS : email;
        this.push = push == null ? DeliveryMode.OFF : push;
        this.quietHoursStart = quietHoursStart;
        this.quietHoursEnd = quietHoursEnd;
        this.timeZone = timeZone;
    }

    public static ChatNotifyPref defaults() {
        return new ChatNotifyPref(true, DeliveryMode.MENTIONS, DeliveryMode.OFF, null, null, null);
    }
}
