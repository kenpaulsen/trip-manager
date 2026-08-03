package org.paulsens.trip.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.model.SettingSection;

/**
 * Site settings, read-only for now -- writes are the next phase.
 *
 * <p>Shaped around the {@code KnownSettings} registry rather than around the stored rows, because the registry
 * is the source of truth: one declaration per setting drives both the code that reads it and the admin page
 * that edits it. A client that enumerated the DynamoDB rows instead would miss every setting nobody has
 * overridden yet, which is most of them.
 *
 * <p>Everything needs {@code configAdmin}. The values include mail addresses and moderation thresholds, and the
 * shape of the registry is a map of what the site can be made to do.
 */
@Slf4j
@Path("config")
@TripApi
public class ConfigResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.CONFIG_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * Every declared setting, grouped as the admin page groups them, with its current value.
     *
     * <p>One call rather than a registry call plus a values call: the two are useless apart, and fetching them
     * separately invites a client to render a property sheet from a stale half.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response settings() {
        if (!privileges().has(ApiPrivileges.CONFIG_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Config access required.");
        }
        final ConfigCommands config = Beans.get(ConfigCommands.class);
        final Map<String, String> values = config.getKnownValues();
        return ok(config.getSections().stream().map(section -> toDto(section, values)).toList());
    }

    /**
     * Stored rows that no declaration claims.
     *
     * <p>Worth its own endpoint because these are invisible on the property sheet by construction: they are
     * settings the code no longer reads, left behind by a rename or a removed feature. Nothing goes wrong while
     * they sit there, which is exactly why nobody notices them.
     */
    @GET
    @Path("unknown")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response unknown() {
        if (!privileges().has(ApiPrivileges.CONFIG_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Config access required.");
        }
        return ok(Beans.get(ConfigCommands.class).getUnknown().stream()
                .map(ConfigResource::toDto)
                .toList());
    }

    private static Map<String, Object> toDto(final SettingSection section, final Map<String, String> values) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", section.getTitle());
        result.put("note", section.getNote());
        result.put("settings", section.getSettings().stream()
                .map(def -> toDto(def, values))
                .toList());
        return result;
    }

    /**
     * A declared setting.
     *
     * <p>{@code value} is what is stored and {@code defaultValue} is what the code falls back to; a blank
     * {@code value} means unset, not empty. Keeping them separate lets a client show "using the default"
     * honestly instead of presenting the fallback as though somebody had chosen it.
     */
    private static Map<String, Object> toDto(final SettingDef def, final Map<String, String> values) {
        final String stored = values.get(def.getName());
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", def.getName());
        result.put("label", def.getLabel());
        result.put("description", def.getDescription());
        result.put("type", def.getType() == null ? null : def.getType().name());
        result.put("defaultValue", def.getDefaultValue());
        result.put("value", stored);
        result.put("usingDefault", stored == null || stored.isBlank());
        return result;
    }

    private static Map<String, Object> toDto(final Config config) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", config.getName());
        result.put("value", config.getValue());
        result.put("description", config.getDescription());
        result.put("modifiedBy", config.getModifiedBy());
        return result;
    }
}
