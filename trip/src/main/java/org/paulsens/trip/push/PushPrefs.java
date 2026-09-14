package org.paulsens.trip.push;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.Value;

/**
 * One person's push notification settings plus their registered devices -- the content of the reserved
 * {@code push-devices} person-data row ({@code docs/push-notifications.md}). One row, not a table: the
 * person-data table already exists, is backed up, cache-invalidated and deleted with the account.
 *
 * <p>The master switch and quiet hours are PERSON-level (the app Settings screen and the website profile
 * page); the per-channel choice lives on the chat membership row ({@code ChatNotifyPref.pushMode}).
 */
@Value
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PushPrefs implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** Enough for a family's phones and every browser they own; stops a runaway client growing the row. */
    public static final int MAX_DEVICES = 20;

    /** The master switch. On by default: registering a device is the opt-in. */
    boolean enabled;
    /** {@code HH:mm} in {@link #timeZone}, or null for no quiet hours. */
    String quietHoursStart;
    String quietHoursEnd;
    /** An IANA zone id; null means UTC when quiet hours are evaluated. */
    String timeZone;
    List<PushDevice> devices;

    @JsonCreator
    public PushPrefs(
            @JsonProperty("enabled") final Boolean enabled,
            @JsonProperty("quietHoursStart") final String quietHoursStart,
            @JsonProperty("quietHoursEnd") final String quietHoursEnd,
            @JsonProperty("timeZone") final String timeZone,
            @JsonProperty("devices") final List<PushDevice> devices) {
        this.enabled = enabled == null || enabled;
        this.quietHoursStart = blankToNull(quietHoursStart);
        this.quietHoursEnd = blankToNull(quietHoursEnd);
        this.timeZone = blankToNull(timeZone);
        this.devices = devices == null ? List.of()
                : devices.stream().filter(device -> device != null && device.getKind() != null).toList();
    }

    public static PushPrefs defaults() {
        return new PushPrefs(null, null, null, null, null);
    }

    public PushPrefs withDevices(final List<PushDevice> newDevices) {
        return new PushPrefs(enabled, quietHoursStart, quietHoursEnd, timeZone, newDevices);
    }

    /** The same devices with the person-level settings replaced (null keeps a value, blank clears it). */
    public PushPrefs withSettings(final Boolean newEnabled, final String newStart, final String newEnd,
            final String newZone) {
        return new PushPrefs(newEnabled == null ? enabled : newEnabled,
                newStart == null ? quietHoursStart : newStart,
                newEnd == null ? quietHoursEnd : newEnd,
                newZone == null ? timeZone : newZone, devices);
    }

    @JsonIgnore
    public boolean hasQuietHours() {
        return quietHoursStart != null && quietHoursEnd != null;
    }

    /**
     * Whether {@code now} falls inside the quiet window. A window may cross midnight ({@code 22:00}–{@code
     * 07:00}); the boundary rule is start-inclusive, end-exclusive. Unparseable stored values read as no
     * quiet hours -- a bad row must not silence someone forever.
     */
    public boolean isQuietAt(final Instant now) {
        if (!hasQuietHours() || now == null) {
            return false;
        }
        final LocalTime start = parseTime(quietHoursStart);
        final LocalTime end = parseTime(quietHoursEnd);
        if (start == null || end == null || start.equals(end)) {
            return false;
        }
        final LocalTime local = now.atZone(zone()).toLocalTime();
        if (start.isBefore(end)) {
            return !local.isBefore(start) && local.isBefore(end);
        }
        return !local.isBefore(start) || local.isBefore(end);
    }

    /** The person's zone, else UTC; an unknown id reads as UTC rather than failing every send. */
    @JsonIgnore
    public ZoneId zone() {
        final ZoneId parsed = parseZone(timeZone);
        return parsed == null ? ZoneId.of("UTC") : parsed;
    }

    @JsonIgnore
    public List<PushDevice> devicesOf(final PushDevice.Kind kind) {
        return devices.stream().filter(device -> device.getKind() == kind).toList();
    }

    /** {@code HH:mm} → time, or null. Public so the write paths validate with the same parser the reader uses. */
    public static LocalTime parseTime(final String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(hhmm.trim());
        } catch (final DateTimeParseException ex) {
            return null;
        }
    }

    public static ZoneId parseZone(final String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(zoneId.trim());
        } catch (final java.time.DateTimeException ex) {
            return null;
        }
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
