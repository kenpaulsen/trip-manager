package org.paulsens.trip.action;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.config.KnownSettings;

/**
 * What day it is for trip reminders ({@code docs/reminders.md}). ONE answer, asked by the Communications page
 * (is a message due, is it missed, may it be sent now) and by the daily scheduler (what is due today), so the two
 * can never disagree for part of a day.
 *
 * <p>The zone is the {@code reminders.zone} setting. A value that is not a zone id falls back to the setting's
 * own default, which is what the Settings page promises; it used to fall back to UTC, which moved the send hour
 * and the day boundary by eight hours without telling anyone.
 */
@Slf4j
public final class ReminderClock {

    private ReminderClock() {
    }

    public static ZoneId zone(final ConfigCommands config) {
        try {
            return ZoneId.of(config.getString(KnownSettings.REMINDERS_ZONE));
        } catch (final RuntimeException ex) {
            log.warn("Bad reminders.zone; using the default {}", KnownSettings.REMINDERS_ZONE.getDefaultValue());
            return ZoneId.of(KnownSettings.REMINDERS_ZONE.getDefaultValue());
        }
    }

    public static LocalDate today(final ConfigCommands config, final Instant now) {
        return LocalDate.ofInstant(now, zone(config));
    }

    /** The hour of the day (in {@link #zone}) the daily run sends: the {@code reminders.hour} setting. */
    public static int runHour(final ConfigCommands config) {
        return config.getInt(KnownSettings.REMINDERS_HOUR, 0, 23);
    }

    /** Whether today's run is still to come at {@code now}; at the run hour itself it has started. */
    public static boolean isBeforeTodaysRun(final ConfigCommands config, final Instant now) {
        return now.atZone(zone(config)).getHour() < runHour(config);
    }
}
