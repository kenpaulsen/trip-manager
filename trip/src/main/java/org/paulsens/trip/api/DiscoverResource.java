package org.paulsens.trip.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.api.dto.DiscoverListDto;
import org.paulsens.trip.api.dto.DiscoverTripDto;
import org.paulsens.trip.api.mapper.DiscoverTripMapper;
import org.paulsens.trip.site.DiscoverIndex;
import org.paulsens.trip.site.DiscoverQuery;
import org.paulsens.trip.site.DiscoverSearch;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.web.SessionlessPaths;

/**
 * Discover (medjugorje#155): the public trips of every organization that allows it, for ANYONE, signed in or
 * not. The apps' signed-out browse and search read here, and nothing else does.
 *
 * <p><b>The one sanctioned read across organizations</b>, so it is built to be unable to say more than it
 * should rather than trusted to hold back ({@code docs/discover-tenancy-review.md}):
 * <ul>
 *   <li>What is listed is decided once, by {@code ListingScope.discoverable}, when {@link DiscoverIndex}
 *       rebuilds. This class only ever reads that index: it has no path to a trip the rule left out, so an
 *       unlisted trip and a nonexistent one are the same 404, byte for byte.</li>
 *   <li>What is said about a listed trip is {@link DiscoverTripDto}, an allow-list record, mapped before it
 *       gets here. This class never sees a {@code Trip}.</li>
 *   <li>Deliberately NOT {@code @TripApi}, and it never asks who is calling: no {@code personId()}, no
 *       session, no privilege. A signed-in caller gets exactly the anonymous answer, which is what lets the
 *       response be {@code Cache-Control: public}. ({@code SessionlessPaths} keeps the filters ahead of it
 *       from touching a session either, and both endpoints refuse, as an ordinary miss, any request that rule
 *       did not recognize: the filters and this class can then never disagree about which requests are
 *       sessionless.)</li>
 *   <li>GET only ({@code ApiSurfaceTest}). Joining a trip is the existing signed-in registration.</li>
 * </ul>
 *
 * <p>On an organization's own host the list is that organization's alone ({@code SiteContext.admits}): the
 * site boundary holds here as on every other API read. Every other host lists every discoverable trip.
 */
@Path("discover/trips")
public class DiscoverResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.DISCOVER_V1;
    /** A minute: the index's own refresh interval, so a cached answer is never staler than a fresh one could be. */
    private static final String CACHE_CONTROL = "public, max-age=60";
    private static final String NO_SUCH_TRIP = "No such trip.";

    private final DiscoverIndex index;
    private final PublicRateLimiter limiter;

    @Context
    private UriInfo uriInfo;

    public DiscoverResource() {
        this(DiscoverIndex.getInstance(), new PublicRateLimiter());
    }

    /** Test seam: an index and a limiter of the test's own. */
    DiscoverResource(final DiscoverIndex index, final PublicRateLimiter limiter) {
        this.index = index;
        this.limiter = limiter;
    }

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * The listed trips, searched ({@code q}), filtered ({@code country}, {@code tag}, {@code language},
     * {@code kind}, {@code org}, {@code state}, {@code month}, each repeatable; {@code from}/{@code to}), ordered
     * ({@code sort}) and paged ({@code limit}, {@code cursor}); {@code docs/api-contract.md} has the contract.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response list() {
        return list(uriInfo.getQueryParameters());
    }

    /** {@link #list()} on explicit parameters (the testable half; a JAX-RS multivalued map is this shape). */
    Response list(final Map<String, List<String>> params) {
        if (!SessionlessPaths.matches(request)) {
            return error(404, ApiErrors.NOT_FOUND, NO_SUCH_TRIP);
        }
        final PublicRateLimiter.Decision allowance = limiter.check(request.getRemoteAddr(), Instant.now());
        if (!allowance.allowed()) {
            return tooMany(allowance);
        }
        final DiscoverQuery query;
        try {
            query = DiscoverQuery.parse(params);
        } catch (final IllegalArgumentException ex) {
            return error(400, ApiErrors.BAD_REQUEST, ex.getMessage());
        }
        final DiscoverIndex.Snapshot snapshot = index.current();
        final SiteContext site = SiteContext.current();
        final String etag = etagOf(snapshot.fingerprint(), query.toString(), site);
        if (matches(etag)) {
            return notModified(etag);
        }
        final DiscoverSearch.Result found = DiscoverSearch.search(snapshot.entries().stream()
                .filter(entry -> site.admits(entry.trip().orgId())).toList(), query);
        return cacheable(new DiscoverListDto(found.items().stream().map(this::absolute).toList(), found.total(),
                found.nextCursor(), found.facets()), etag);
    }

    /**
     * One listed trip. A trip that is not listed (Shared sites off, off its homepage, ended, another
     * organization's on this organization's host) answers exactly what a trip that never existed does: telling
     * the two apart would confirm that an id is real.
     */
    @GET
    @Path("{tripId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("tripId") final String tripId) {
        if (!SessionlessPaths.matches(request)) {
            return error(404, ApiErrors.NOT_FOUND, NO_SUCH_TRIP);
        }
        final PublicRateLimiter.Decision allowance = limiter.check(request.getRemoteAddr(), Instant.now());
        if (!allowance.allowed()) {
            return tooMany(allowance);
        }
        final SiteContext site = SiteContext.current();
        final Optional<DiscoverIndex.Entry> listed = index.current().find(tripId)
                .filter(entry -> site.admits(entry.trip().orgId()));
        if (listed.isEmpty()) {
            return error(404, ApiErrors.NOT_FOUND, NO_SUCH_TRIP);
        }
        final String etag = etagOf(listed.get().etag(), "", site);
        return matches(etag) ? notModified(etag) : cacheable(absolute(listed.get().trip()), etag);
    }

    private DiscoverTripDto absolute(final DiscoverTripDto trip) {
        return DiscoverTripMapper.absolute(trip, this::absoluteUrl);
    }

    private Response cacheable(final Object entity, final String etag) {
        return Response.ok(entity)
                .type(negotiatedType())
                .header("Vary", "Accept")
                .header("Cache-Control", CACHE_CONTROL)
                .header("ETag", etag)
                .build();
    }

    private Response notModified(final String etag) {
        return Response.notModified()
                .header("Vary", "Accept")
                .header("Cache-Control", CACHE_CONTROL)
                .header("ETag", etag)
                .build();
    }

    private Response tooMany(final PublicRateLimiter.Decision allowance) {
        return Response.fromResponse(error(429, ApiErrors.RATE_LIMITED, "Too many requests. Try again shortly."))
                .header("Retry-After", allowance.retryAfterSeconds())
                .header("Cache-Control", "no-store")
                .build();
    }

    /**
     * A weak validator for one answer: what the listed trips say ({@code content}, which changes only when they
     * do, so a rebuild that changed nothing keeps it), which question was asked, and the three request facts an
     * answer also depends on: the host's organization (it narrows the list), the origin its local-mode URLs are
     * made absolute on, and the media type it is stamped with. Weak because the same trips serialize to
     * different bytes under gzip.
     */
    private String etagOf(final String content, final String question, final SiteContext site) {
        final String asked = String.join("|", question, site.isOrg() ? site.orgId().getValue() : "",
                absoluteUrl("/"), negotiatedType());
        return "W/\"" + content + "-" + Integer.toHexString(asked.hashCode()) + "\"";
    }

    /** Whether the caller already holds this answer ({@code If-None-Match}, weak comparison, any of a list). */
    private boolean matches(final String etag) {
        final String header = request.getHeader("If-None-Match");
        if (header == null || header.isBlank()) {
            return false;
        }
        final String opaque = opaqueOf(etag);
        return Arrays.stream(header.split(",")).map(String::trim)
                .anyMatch(candidate -> "*".equals(candidate) || opaque.equals(opaqueOf(candidate)));
    }

    /** A validator without its weakness marker: weak comparison ignores it on both sides. */
    private static String opaqueOf(final String etag) {
        return etag.startsWith("W/") ? etag.substring(2) : etag;
    }
}
