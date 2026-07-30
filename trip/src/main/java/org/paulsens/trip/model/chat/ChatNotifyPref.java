package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalTime;
import lombok.Value;

/**
 * Notification preferences stored inside a {@link ChatMembership} row. No SMS fields — SMS is dropped, not
 * deferred.
 *
 * <p><b>Two independent choices, not one ladder.</b> "Tell me when I am named" and "send me a daily summary" are
 * different questions with different answers — plenty of people want both, and plenty want only the first — so a
 * single mode forced a false exclusive choice between them. There is deliberately <b>no "every message" option</b>:
 * for a 200-person trip that is the setting that makes someone abandon email entirely.
 *
 * <p>Defaults: mentions <b>on</b>, digest <b>off</b>. Being named is the one case worth interrupting someone for;
 * a daily rollup nobody asked for is not. Because a null normalises to the default, this holds for rows written
 * before the fields existed <em>and</em> for implicit members who have no row at all.
 */
@Value
public class ChatNotifyPref implements Serializable {

    /**
     * The old single-mode field. Retained only to read rows written before the split — never written back.
     * {@code ALL} is gone as a choice; a row that has it is read as "mentions on", the nearest surviving intent.
     */
    public enum DeliveryMode {
        OFF,
        MENTIONS,
        ALL,
        DIGEST_HOURLY,
        DIGEST_DAILY
    }

    boolean inApp;
    /** Email me when someone names me in a message. */
    boolean mentionEmail;
    /** Email me one summary a day of what I have missed. */
    boolean dailyDigest;
    /** Reserved; inert until APNs lands. */
    DeliveryMode push;
    LocalTime quietHoursStart;
    LocalTime quietHoursEnd;
    String timeZone;

    @JsonCreator
    public ChatNotifyPref(
            @JsonProperty("inApp") final Boolean inApp,
            @JsonProperty("mentionEmail") final Boolean mentionEmail,
            @JsonProperty("dailyDigest") final Boolean dailyDigest,
            @JsonProperty("email") final DeliveryMode legacyEmail,
            @JsonProperty("push") final DeliveryMode push,
            @JsonProperty("quietHoursStart") final LocalTime quietHoursStart,
            @JsonProperty("quietHoursEnd") final LocalTime quietHoursEnd,
            @JsonProperty("timeZone") final String timeZone) {
        this.inApp = inApp == null || inApp;
        // A stored row wins; otherwise fall back to the old single mode; otherwise the default. Written this way
        // so no migration script is needed: rows convert themselves the first time they are read.
        this.mentionEmail = mentionEmail != null ? mentionEmail : mentionsFrom(legacyEmail);
        this.dailyDigest = dailyDigest != null ? dailyDigest : digestFrom(legacyEmail);
        this.push = push == null ? DeliveryMode.OFF : push;
        this.quietHoursStart = quietHoursStart;
        this.quietHoursEnd = quietHoursEnd;
        this.timeZone = timeZone;
    }

    /** Absent means the default (on); an explicit old mode maps to whether it included mentions. */
    private static boolean mentionsFrom(final DeliveryMode legacy) {
        if (legacy == null) {
            return true;
        }
        return legacy == DeliveryMode.MENTIONS || legacy == DeliveryMode.ALL;
    }

    /** Both old digest modes become the one daily digest; there is no hourly option any more. */
    private static boolean digestFrom(final DeliveryMode legacy) {
        return legacy == DeliveryMode.DIGEST_DAILY || legacy == DeliveryMode.DIGEST_HOURLY;
    }

    public static ChatNotifyPref defaults() {
        return new ChatNotifyPref(true, true, false, null, DeliveryMode.OFF, null, null, null);
    }

    /** The same preferences with the two email choices replaced. */
    public ChatNotifyPref withEmail(final boolean mentions, final boolean digest) {
        return new ChatNotifyPref(inApp, mentions, digest, null, push, quietHoursStart, quietHoursEnd, timeZone);
    }

    /** Whether any email at all is wanted, for callers that only need to know if this person is reachable. */
    @JsonIgnore
    public boolean isAnyEmail() {
        return mentionEmail || dailyDigest;
    }
}
