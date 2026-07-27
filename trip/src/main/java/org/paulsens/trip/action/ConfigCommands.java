package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Config;

/**
 * Runtime settings, exposed to pages as {@code #{config}}.
 *
 * <p>Every read takes a default and returns it whenever the setting is absent, unreadable or malformed. That is
 * the contract that makes a settings table safe to depend on from a page render: the compiled-in default is the
 * source of truth for "what happens if nothing is configured", so an empty table, a DynamoDB outage or a typo'd
 * value degrades to shipped behavior rather than to a broken page. Callers therefore never need a null check.
 *
 * <p>Example: {@code #{config.getBoolean('home.banner.enabled', false)}}.
 */
@Slf4j
@Named("config")
@ApplicationScoped
public class ConfigCommands {

    /** @return the setting's value, or {@code defaultValue} if it is absent or unreadable. */
    public String getString(final String name, final String defaultValue) {
        return lookup(name).map(Config::getValue).filter(v -> v != null).orElse(defaultValue);
    }

    /**
     * @return the setting parsed as a boolean. Anything other than a recognised true/false spelling yields
     *         {@code defaultValue} -- a mistyped value must not silently read as false.
     */
    public boolean getBoolean(final String name, final boolean defaultValue) {
        final String raw = getString(name, null);
        if (raw == null) {
            return defaultValue;
        }
        final String v = raw.trim().toLowerCase();
        if (List.of("true", "yes", "on", "1").contains(v)) {
            return true;
        }
        if (List.of("false", "no", "off", "0").contains(v)) {
            return false;
        }
        log.warn("Config '{}' is not a boolean ('{}'); using default {}", name, raw, defaultValue);
        return defaultValue;
    }

    /** @return the setting parsed as an int, or {@code defaultValue} if absent or not a number. */
    public int getInt(final String name, final int defaultValue) {
        final String raw = getString(name, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (final NumberFormatException ex) {
            log.warn("Config '{}' is not an int ('{}'); using default {}", name, raw, defaultValue);
            return defaultValue;
        }
    }

    /** @return the setting parsed as a long, or {@code defaultValue} if absent or not a number. */
    public long getLong(final String name, final long defaultValue) {
        final String raw = getString(name, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (final NumberFormatException ex) {
            log.warn("Config '{}' is not a long ('{}'); using default {}", name, raw, defaultValue);
            return defaultValue;
        }
    }

    /** All settings, name-sorted, for the admin page. */
    public List<Config> getAll() {
        try {
            return DAO.getInstance().getAllConfig().join().stream()
                    .sorted(Comparator.comparing(Config::getName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (final RuntimeException ex) {
            log.error("Unable to list config settings", ex);
            return List.of();
        }
    }

    /**
     * Saves a setting from its parts, for the admin page. {@code type} is the {@link Config.Type} name; an
     * unrecognised one falls back to STRING rather than failing the save.
     *
     * @return true when stored.
     */
    public boolean saveNew(final String name, final String value, final String type, final String description,
            final String modifiedBy) {
        Config.Type parsed;
        try {
            parsed = (type == null || type.isBlank()) ? Config.Type.STRING
                    : Config.Type.valueOf(type.trim().toUpperCase());
        } catch (final IllegalArgumentException ex) {
            parsed = Config.Type.STRING;
        }
        return save(new Config(name, value, parsed, description, null, null), modifiedBy);
    }

    /**
     * Saves a setting and records who changed it. Validates against the declared type so a bad edit is refused
     * at save time, rather than being accepted and then silently ignored on every subsequent read.
     *
     * @return true when stored.
     */
    public boolean save(final Config config, final String modifiedBy) {
        if (config == null || config.getName() == null || config.getName().isBlank()) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved", "A setting needs a name.");
            return false;
        }
        if (!isValid(config)) {
            log.warn("Refusing config '{}': '{}' is not a valid {}",
                    config.getName(), config.getValue(), config.getType());
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved",
                    "'" + config.getValue() + "' is not a valid " + config.getType() + ".");
            return false;
        }
        final Config stamped = new Config(config.getName().trim(), config.getValue(), config.getType(),
                config.getDescription(), LocalDateTime.now(), modifiedBy);
        try {
            final boolean saved = DAO.getInstance().saveConfig(stamped).join();
            if (saved) {
                Audit.builder(AuditAction.CONFIG, AuditOutcome.SUCCESS)
                        .currentActor(modifiedBy)
                        .target(AuditEventBuilder.TARGET_CONFIG, stamped.getName())
                        .message("Set " + stamped.getName() + " = " + stamped.getValue())
                        .log();
            }
            return saved;
        } catch (final RuntimeException ex) {
            log.error("Unable to save config: " + stamped.getName(), ex);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved", ex.getMessage());
            return false;
        }
    }

    /** @return whether the value parses as its declared type (a null/blank value is allowed: it means "unset"). */
    public boolean isValid(final Config config) {
        final String raw = (config.getValue() == null) ? null : config.getValue().trim();
        if (raw == null || raw.isEmpty()) {
            return true;
        }
        return switch (config.getType()) {
            case STRING -> true;
            case BOOLEAN -> List.of("true", "yes", "on", "1", "false", "no", "off", "0")
                    .contains(raw.toLowerCase());
            case INT -> parses(raw, Integer::parseInt);
            case LONG -> parses(raw, Long::parseLong);
        };
    }

    private static boolean parses(final String raw, final java.util.function.Function<String, Number> parser) {
        try {
            parser.apply(raw);
            return true;
        } catch (final NumberFormatException ex) {
            return false;
        }
    }

    private Optional<Config> lookup(final String name) {
        try {
            return DAO.getInstance().getConfig(name).join();
        } catch (final RuntimeException ex) {
            // Never propagate: a settings lookup happens mid-render and must not break the page.
            log.error("Unable to read config: " + name, ex);
            return Optional.empty();
        }
    }
}
