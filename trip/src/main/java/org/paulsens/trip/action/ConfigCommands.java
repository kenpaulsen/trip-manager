package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.model.SettingSection;

/**
 * Runtime settings, exposed to pages as {@code #{config}}.
 *
 * <p>Every read takes a default and returns it whenever the setting is absent, unreadable or malformed. That is
 * the contract that makes a settings table safe to depend on from a page render: the compiled-in default is the
 * source of truth for "what happens if nothing is configured", so an empty table, a DynamoDB outage or a typo'd
 * value degrades to shipped behavior rather than to a broken page. Callers therefore never need a null check.
 *
 * <p>Settings the code actually reads are declared once in {@link KnownSettings}. Prefer the overloads that take
 * a {@code SettingDef} (or, from a page, the single-argument by-name form) over passing a literal key and a
 * default: those two forms take the default from the declaration, so a call site cannot disagree with what the
 * admin Settings page shows. The {@code (name, default)} pair remains for ad-hoc keys and for tests.
 *
 * <p>Example, from a page: {@code #{config.getBoolean('home.banner.enabled')}}.
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

    // --- registry-driven reads: the form every call site should use ---

    /**
     * Reads a declared setting, falling back to the default declared alongside it.
     *
     * <p>Prefer these overloads to the {@code (name, default)} pair everywhere. They are what keeps the admin
     * page and the code in agreement: the key and the default come from the same declaration, so the page
     * cannot offer a setting nothing reads, and a call site cannot quietly disagree with the default shown.
     */
    public String getString(final SettingDef def) {
        return getString(def.getName(), def.getDefaultValue());
    }

    public boolean getBoolean(final SettingDef def) {
        return getBoolean(def.getName(), def.booleanDefault());
    }

    public int getInt(final SettingDef def) {
        return getInt(def.getName(), def.intDefault());
    }

    public long getLong(final SettingDef def) {
        return getLong(def.getName(), def.longDefault());
    }

    /**
     * An int constrained to a range, for the settings whose out-of-range value has no sensible meaning.
     *
     * <p>Clamps rather than refusing, because these are read mid-request: a nonsensical number should produce
     * the nearest workable behaviour, not an exception on a page render. The save path rejects what it can, so
     * this is the second line, not the only one.
     */
    public int getInt(final SettingDef def, final int min, final int max) {
        return Math.min(Math.max(getInt(def), min), max);
    }

    /**
     * Reads a declared setting by name, for pages: {@code #{config.getString('home.banner.text')}}.
     *
     * <p>EL cannot hold a {@code SettingDef}, so a page would otherwise have to repeat the key AND the default,
     * putting a second copy of the default outside the registry -- exactly the drift this registry exists to
     * stop. These resolve the default from the declaration instead, so a page only ever names the key.
     *
     * <p>An undeclared name is a programming error, but it is discovered mid-render, so it logs and yields an
     * empty/false/zero value rather than throwing. A broken banner beats a broken home page.
     */
    public String getString(final String name) {
        return declared(name).map(this::getString).orElse("");
    }

    public boolean getBoolean(final String name) {
        return declared(name).map(this::getBoolean).orElse(false);
    }

    public int getInt(final String name) {
        return declared(name).map(this::getInt).orElse(0);
    }

    public long getLong(final String name) {
        return declared(name).map(this::getLong).orElse(0L);
    }

    private Optional<SettingDef> declared(final String name) {
        final Optional<SettingDef> def = KnownSettings.find(name);
        if (def.isEmpty()) {
            log.warn("'{}' is not a declared setting; add it to KnownSettings or nothing will ever read it", name);
        }
        return def;
    }

    // --- the admin page ---

    /** The declared settings, grouped, for the property sheet. */
    public List<SettingSection> getSections() {
        return KnownSettings.sections();
    }

    /**
     * The stored value of every declared setting, keyed by name, with an absent one as the empty string.
     *
     * <p>Empty means "unset, so the declared default applies" -- deliberately not pre-filled with the default,
     * because a page that shows defaults as if they were stored values turns every visit into a mass write of
     * settings nobody chose, and then the default can never change under them.
     */
    public Map<String, String> getKnownValues() {
        final Map<String, Config> stored = getAll().stream()
                .collect(Collectors.toMap(Config::getName, config -> config, (a, b) -> a));
        final Map<String, String> values = new HashMap<>();
        for (final SettingDef def : KnownSettings.all()) {
            final Config config = stored.get(def.getName());
            values.put(def.getName(), (config == null || config.getValue() == null) ? "" : config.getValue());
        }
        return values;
    }

    /** Stored rows that no longer correspond to anything the code reads -- shown so they cannot go unnoticed. */
    public List<Config> getUnknown() {
        return getAll().stream().filter(config -> !KnownSettings.isKnown(config.getName())).toList();
    }

    /**
     * Saves the declared settings from the page's edit map.
     *
     * <p>Only what actually changed is written, so opening the page and pressing Save does not stamp every
     * setting with a new modified-by, and blanking a field deletes the stored value rather than storing an
     * empty one -- which is what makes "blank means use the default" true rather than merely intended.
     *
     * @return true when everything valid was stored; false if any value was rejected (each reports itself).
     */
    public boolean saveKnown(final Map<String, String> values, final String modifiedBy) {
        if (values == null) {
            return true;
        }
        final Map<String, String> current = getKnownValues();
        boolean allSaved = true;
        for (final SettingDef def : KnownSettings.all()) {
            final String edited = normalize(values.get(def.getName()));
            if (edited.equals(normalize(current.get(def.getName())))) {
                continue;
            }
            final Config updated = new Config(def.getName(), edited.isEmpty() ? null : edited, def.getType(),
                    def.getDescription(), null, null);
            allSaved = save(updated, modifiedBy) && allSaved;
        }
        return allSaved;
    }

    private static String normalize(final String value) {
        return (value == null) ? "" : value.trim();
    }

    /** All settings, name-sorted, for the admin page. */
    public List<Config> getAll() {
        try {
            return DAO.getInstance().getAllConfig().stream()
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
            final boolean saved = DAO.getInstance().saveConfig(stamped);
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
            return DAO.getInstance().getConfig(name);
        } catch (final RuntimeException ex) {
            // Never propagate: a settings lookup happens mid-render and must not break the page.
            log.error("Unable to read config: " + name, ex);
            return Optional.empty();
        }
    }
}
