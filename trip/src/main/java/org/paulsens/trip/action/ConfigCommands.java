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
import org.paulsens.trip.cache.NearCacheClient;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.ContentRenderer;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.model.SettingSection;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.site.SiteContext;

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
        return parseBoolean(name, getString(name, null), defaultValue);
    }

    /** @return the setting parsed as an int, or {@code defaultValue} if absent or not a number. */
    public int getInt(final String name, final int defaultValue) {
        return parseInt(name, getString(name, null), defaultValue);
    }

    /** @return the setting parsed as a long, or {@code defaultValue} if absent or not a number. */
    public long getLong(final String name, final long defaultValue) {
        return parseLong(name, getString(name, null), defaultValue);
    }

    private static boolean parseBoolean(final String name, final String raw, final boolean defaultValue) {
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

    private static int parseInt(final String name, final String raw, final int defaultValue) {
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

    private static long parseLong(final String name, final String raw, final long defaultValue) {
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
     *
     * <p>On an ORGANIZATION's site (the request's {@link SiteContext}) an
     * {@link SettingDef#isOrgOverridable() org-overridable} setting resolves through the ladder
     * {@link #getString(SettingDef, Organization)} describes -- the org's override first -- so a page or bean
     * reading through these overloads is per-tenant without doing anything. Every other host, and every
     * other setting, reads exactly as before. Code running with no bound request (schedulers, mail senders
     * under the system context) sees the SHARED site here and must pass the organization explicitly.
     */
    public String getString(final SettingDef def) {
        return getString(def, siteOrg(def));
    }

    public boolean getBoolean(final SettingDef def) {
        return getBoolean(def, siteOrg(def));
    }

    public int getInt(final SettingDef def) {
        return getInt(def, siteOrg(def));
    }

    public long getLong(final SettingDef def) {
        return getLong(def, siteOrg(def));
    }

    // --- the org -> site -> default ladder, with the organization named explicitly ---

    /**
     * A declared setting as it applies to ONE organization: the org's non-blank override when the setting
     * is org-overridable, else the site's stored row, else the compiled default -- except that an
     * {@link SettingDef#isOrgOnly() org-only} setting skips the site row for an org (its override or the
     * default, nothing in between). {@code org} null means the site rung: identical to the plain overload
     * off an org host. This is the form for code with no request in hand (digest and notification senders,
     * anything under {@code RequestContext.system()}): the organization comes from the entity -- the trip,
     * the chat channel's trip -- never from a session, and never from a site context that is not bound.
     */
    public String getString(final SettingDef def, final Organization org) {
        if (!appliesTo(def, org)) {
            return getString(def.getName(), def.getDefaultValue());
        }
        final String raw = rawFor(def, org);
        return raw == null ? def.getDefaultValue() : raw;
    }

    public boolean getBoolean(final SettingDef def, final Organization org) {
        if (!appliesTo(def, org)) {
            return getBoolean(def.getName(), def.booleanDefault());
        }
        return parseBoolean(def.getName(), rawFor(def, org), def.booleanDefault());
    }

    public int getInt(final SettingDef def, final Organization org) {
        if (!appliesTo(def, org)) {
            return getInt(def.getName(), def.intDefault());
        }
        return parseInt(def.getName(), rawFor(def, org), def.intDefault());
    }

    public long getLong(final SettingDef def, final Organization org) {
        if (!appliesTo(def, org)) {
            return getLong(def.getName(), def.longDefault());
        }
        return parseLong(def.getName(), rawFor(def, org), def.longDefault());
    }

    /**
     * Whether the org rung is in play at all. When it is not, the reads above take EXACTLY the pre-ladder
     * path -- the {@code (name, default)} overloads -- so a subclass or test double that overrides those
     * keeps steering every non-org read the way it always did.
     */
    private static boolean appliesTo(final SettingDef def, final Organization org) {
        return org != null && def.isOrgOverridable();
    }

    /**
     * The SITE rung alone -- the stored row or the compiled default, whatever host the request is on. What
     * the org settings editor shows as "inherited", so an org admin editing from the shared host and one
     * editing from the org's own host see the same placeholder.
     */
    public String siteString(final SettingDef def) {
        return getString(def.getName(), def.getDefaultValue());
    }

    /** The org's override, else (unless org-only) the site row; null when nothing is set at either rung. */
    private String rawFor(final SettingDef def, final Organization org) {
        final String override = org.settingOverride(def.getName());
        if (override != null) {
            return override;
        }
        return def.isOrgOnly() ? null : getString(def.getName(), null);
    }

    /**
     * The organization whose site the current request is for, when {@code def} can vary by org; null
     * otherwise (a shared or marketing host, no bound request, a site-only setting, or an org row that
     * cannot be read -- the last degrades to the site value rather than breaking a render).
     */
    private Organization siteOrg(final SettingDef def) {
        if (!def.isOrgOverridable()) {
            return null;
        }
        final SiteContext site = SiteContext.current();
        if (!site.isOrg()) {
            return null;
        }
        try {
            return DAO.getInstance().getOrganization(site.orgId(), Cached.YES).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to read the organization behind site " + site.host(), ex);
            return null;
        }
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
     * The rows the generic org settings editor ({@code admin/orgConfig.xhtml}) renders: every org-overridable
     * setting EXCEPT the look-and-feel ones, which {@code admin/orgAppearance.xhtml} edits with purpose-built
     * controls. Split here rather than in the page so the two pages cannot both offer (or both skip) one.
     */
    public List<SettingDef> getOrgConfigDefs() {
        return KnownSettings.orgOverridableNonBranding();
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

    /**
     * One stored row by name, or null when absent. The admin table's edit button resolves its row FRESH
     * from the name baked into the click at render time: the table's value is per-request, so the row
     * var at decode may no longer be the row the admin actually clicked.
     */
    public Config getSetting(final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return lookup(name).orElse(null);
    }

    /** Stored rows that no longer correspond to anything the code reads -- shown so they cannot go unnoticed. */
    public List<Config> getUnknown() {
        // Name-sorted: the admin table resolves this per request and its row buttons decode by row
        // position, so the order must be deterministic between a render and the following postback.
        return getAll().stream().filter(config -> !KnownSettings.isKnown(config.getName()))
                .sorted(Comparator.comparing(Config::getName))
                .toList();
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
            return DAO.getInstance().getAllConfig(Cached.NO).stream()
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
        final String rejection = rejection(config);
        if (rejection != null) {
            log.warn("Refusing config '{}': {}", config.getName(), rejection);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved", rejection);
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
                // The near-cache cannot read its own tuning from the read path (ConfigDAO sits on top of
                // it), so the admin save pushes: an edited cache.near.* setting takes effect immediately
                // instead of waiting for the lazy background re-sync.
                if (DAO.getInstance().getCacheClient() instanceof NearCacheClient near) {
                    near.resyncTuning();
                }
            }
            return saved;
        } catch (final RuntimeException ex) {
            log.error("Unable to save config: " + stamped.getName(), ex);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Not saved", ex.getMessage());
            return false;
        }
    }

    /**
     * Drops every entry in the shared cache's data namespace, on every running instance at once (sessions
     * and logins are untouched -- see {@code DAO.clearAllCaches}). This is the ONLY way a row DELETED
     * behind the application's back (bootstrap-home-v2.sh --purge-v1, cleanup-templates.sh --delete)
     * leaves the cache before its GC TTL: the background refresh merges rows and never removes them.
     * Plain additions never need this -- the refresh picks them up within the cache TTL.
     */
    public boolean clearAllCaches(final String requestedBy) {
        try {
            DAO.getInstance().clearAllCaches();
        } catch (final RuntimeException ex) {
            log.error("Unable to clear the shared caches", ex);
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Caches NOT cleared",
                    "The cache could not be reached; see the server log.");
            return false;
        }
        Audit.builder(AuditAction.CONFIG, AuditOutcome.SUCCESS)
                .currentActor(requestedBy)
                .target(AuditEventBuilder.TARGET_CONFIG, "caches")
                .message("Cleared the shared data-cache namespace (all instances)")
                .log();
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_INFO, "Caches cleared",
                "Pages now reload their data from DynamoDB as they are viewed.");
        return true;
    }

    /** @return whether {@link #rejection} has nothing to refuse. */
    public boolean isValid(final Config config) {
        return rejection(config) == null;
    }

    /**
     * Why a value must be refused, or null when it may be stored: it must parse as its declared type, and
     * when the key is a DECLARED setting it must also satisfy the declaration's own rules -- one of its
     * {@link SettingDef#getChoices() choices}, an http(s) URL. A null/blank value is always allowed: it means
     * "unset". Both save paths (this page's and the org settings editor's) ask here, so the two cannot
     * disagree about what a valid value is.
     */
    public String rejection(final Config config) {
        final String raw = (config.getValue() == null) ? null : config.getValue().trim();
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (!parsesAs(raw, config.getType())) {
            return "'" + raw + "' is not a valid " + config.getType() + ".";
        }
        return KnownSettings.find(config.getName()).map(def -> declarationRejection(def, raw)).orElse(null);
    }

    private static boolean parsesAs(final String raw, final Config.Type type) {
        return switch (type) {
            case STRING -> true;
            case BOOLEAN -> List.of("true", "yes", "on", "1", "false", "no", "off", "0")
                    .contains(raw.toLowerCase());
            case INT -> parses(raw, Integer::parseInt);
            case LONG -> parses(raw, Long::parseLong);
        };
    }

    /** The declaration's own rules on a non-blank, type-valid value; null when it passes. */
    private static String declarationRejection(final SettingDef def, final String raw) {
        if (!def.allows(raw)) {
            return "'" + raw + "' is not one of the choices for " + def.getLabel() + ": "
                    + String.join(", ", def.getChoices()) + ".";
        }
        if (def.isHttpUrl() && ContentRenderer.requireHttpUrl(raw).isEmpty()) {
            return "'" + raw + "' is not an http(s) URL, which " + def.getLabel() + " requires.";
        }
        if (def.isHexColor() && SettingDef.hexColor(raw) == null) {
            return "'" + raw + "' is not a hex color like #333333 or #fc0, which " + def.getLabel()
                    + " requires.";
        }
        return null;
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
            return DAO.getInstance().getConfig(name, Cached.YES);
        } catch (final RuntimeException ex) {
            // Never propagate: a settings lookup happens mid-render and must not break the page.
            log.error("Unable to read config: " + name, ex);
            return Optional.empty();
        }
    }
}
