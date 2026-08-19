package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;

/**
 * Cache invalidation for out-of-band writers: migration scripts (via the {@code cache-invalidate.sh}
 * helper) and admin tooling call this after writing DynamoDB behind the application's back, so the cleared
 * scope reloads on next read instead of serving stale data until the soft-TTL poll heals it.
 *
 * <p>Scopes, not raw key prefixes, on purpose -- {@code DAO.CacheScope} keeps key-layout knowledge (the
 * email index and search index travel with {@code person}) out of shell scripts. Safe but briefly
 * expensive, so it is gated like the Settings page's own clear button: {@code configAdmin}. No
 * deploy-style confirmation token -- unlike a deploy, a redundant clear costs only cache warmth.</p>
 */
@Slf4j
@Path("cache")
@TripApi
public class CacheResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.CACHE_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    @POST
    @Path("invalidate")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response invalidate(@HeaderParam(CSRF_HEADER) final String csrf, final InvalidateRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!privileges().has(ApiPrivileges.CONFIG_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Config admin access required.");
        }
        final DAO.CacheScope scope = parseScope(body);
        if (scope == null) {
            return error(400, ApiErrors.VALIDATION_FAILED, "Unknown cache scope; one of: " + scopeNames());
        }
        final List<String> cleared = DAO.getInstance().invalidate(scope);
        Audit.builder(AuditAction.CONFIG, AuditOutcome.SUCCESS)
                .currentActor(requestedBy())
                .target(AuditEventBuilder.TARGET_CONFIG, "caches")
                .message("Invalidated cache scope " + scope.name() + " via API")
                .log();
        return ok(Map.of("scope", scope.name(), "invalidated", cleared));
    }

    /** The scope to clear; the name is matched case-insensitively ({@code "person"}, {@code "all"}, ...). */
    public record InvalidateRequest(String scope) {
    }

    private static DAO.CacheScope parseScope(final InvalidateRequest body) {
        if (body == null || body.scope() == null || body.scope().isBlank()) {
            return null;
        }
        try {
            return DAO.CacheScope.valueOf(body.scope().trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    private static String scopeNames() {
        return String.join(", ", Arrays.stream(DAO.CacheScope.values()).map(Enum::name).toList());
    }

    /** Who to stamp on the audit row; matches what the settings page records. */
    private String requestedBy() {
        final String email = actor().email();
        return email == null ? "api" : email;
    }
}
