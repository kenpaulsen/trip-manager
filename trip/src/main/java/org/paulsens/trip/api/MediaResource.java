package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ChatPhotos;
import org.paulsens.trip.action.MediaCommands;
import org.paulsens.trip.api.dto.MediaItemDto;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.site.SiteContext;

/**
 * The media library over the wire (issue #37): the JSF admin page's operations, ported for the mobile
 * clients, backed by the same {@code MediaCommands} bean so authorization, events and audit cannot drift
 * from the page.
 *
 * <p><b>The upload path is presigned direct-to-S3, not multipart through the container.</b> The issue left
 * this open; it is decided here, and deliberately: this app is never in the read path for a media file (that
 * is the CDN's job), and routing upload bytes through it would hold a request thread for the whole transfer,
 * spool to the task's disk, race the load balancer's idle timeout on slow mobile links, and need a multipart
 * stack the API does not have (dependency, {@code MultiPartFeature}, servlet {@code multipart-config},
 * Tomcat's {@code maxPartCount}). Instead: {@code POST /api/media/upload-url} issues a short-lived presigned
 * {@code PUT} whose signature pins the key, content type, exact length and cache policy; the client PUTs the
 * bytes straight to S3 (mobile SDKs do this in the background, with resume); {@code POST /api/media} then
 * records the row -- and verifies the object actually exists rather than trusting the call. An upload that is
 * never confirmed leaves an unlisted object reachable at its URL, the same partial state the JSF path already
 * reports as "partly uploaded"; it shows nowhere and an admin can remove it.
 *
 * <p>Everything except the slot and single-item reads needs {@code mediaAdmin}, matching the admin page.
 * Those two reads serve any signed-in caller but show only what public pages would show, unless the caller
 * holds {@code mediaAdmin} -- redaction by privilege, same rule as everywhere else on this API.
 *
 * <p>Caller-chosen keys are validated server-side: a key is a public URL path; {@code profilePics/},
 * {@code chat/} and {@code badgeImages/} belong to features that index their prefixes, and {@code org/...}
 * is an organization's own namespace, so writing there is refused outright. On an organization's host the
 * key a client asks for is stored under that org's namespace ({@code MediaCommands.siteKey}) -- the
 * upload-url response names the real key, and the confirm repeats it verbatim.
 */
@Slf4j
@Path("media")
@TripApi
public class MediaResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.MEDIA_V1;

    /** Matches the JSF uploader's ceiling (web.xml {@code max-file-size}), so neither door takes more. */
    static final long MAX_UPLOAD_BYTES = 62_914_560L;

    @Override
    protected String versionedType() {
        return V1;
    }

    /** The whole inventory, newest first -- the admin page's list, chat photos included. */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response list() {
        if (!mediaAdminHere()) {
            return error(403, ApiErrors.FORBIDDEN, "Media administration required.");
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        return ok(media.getAll().stream().map(item -> toDto(media, item)).toList());
    }

    /**
     * One slot's items, ordered for display. A non-admin gets what a public page would render -- visible
     * items only -- so a mobile client can build the same home sections the site shows.
     */
    @GET
    @Path("slots/{slot}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response inSlot(@PathParam("slot") final String slot) {
        if (slot == null || slot.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "A slot name is required.");
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        final boolean admin = mediaAdminHere();
        if (!admin && !mayBrowseSlot(slot)) {
            // Same answer as an unknown slot: a slot name is guessable (tripChat-{tripId}), and "exists but
            // not yours" would confirm another tenant's trip.
            return error(404, ApiErrors.NOT_FOUND, "No such slot.");
        }
        return ok((admin ? media.getInSlot(slot) : media.getVisibleInSlot(slot, 0)).stream()
                .map(item -> toDto(media, item))
                .toList());
    }

    /**
     * Media administration for the SITE this request is for: a global mediaAdmin anywhere, or on an
     * organization's own site its org-scoped media editor. Which ITEMS such an editor may then change is
     * the commands' ownership rule ({@code MediaCommands.mayManage}): a foreign row reads as absent.
     */
    private boolean mediaAdminHere() {
        if (privileges().has(ApiPrivileges.MEDIA_ADMIN)) {
            return true;
        }
        final SiteContext site = SiteContext.current();
        return site.isOrg() && privileges().has(ApiPrivileges.MEDIA_ADMIN, site.orgId().getValue());
    }

    /**
     * A chat slot is one trip's album: a non-admin may browse it only as that trip's member, or when the
     * trip is publicly listed on the SITE this request is for -- a signed-in traveller of one org must not
     * enumerate another org's album by trip id. Library slots are site-scoped per item by the commands.
     */
    boolean mayBrowseSlot(final String slot) {
        final String tripId = ChatPhotos.tripOfSlot(slot);
        if (tripId == null) {
            return true;
        }
        final Trip trip = DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        if (trip == null) {
            return false;
        }
        final Person.Id me = personId();
        if (me != null && trip.getPeople() != null && trip.getPeople().contains(me)) {
            return true;
        }
        return Boolean.TRUE.equals(trip.getOpenToPublic()) && ListingScope.forSite().shows(trip.getOrgId());
    }

    /** One item by row id. A non-admin sees it only if a public page would (hidden reads as absent). */
    @GET
    @Path("{id}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("id") final String id) {
        final MediaCommands media = Beans.get(MediaCommands.class);
        final MediaItem item = mediaAdminHere()
                ? media.get(id) : media.getVisible(id);
        if (item == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such media item.");
        }
        return ok(toDto(media, item));
    }

    /** What a client asks for before uploading: the key it wants, and what it will actually send. */
    public record UploadUrlRequest(String key, String contentType, Long size) {
    }

    /**
     * Step one of an upload: a presigned S3 {@code PUT} for exactly this key, content type and length.
     *
     * <p>409 when a row already claims the key -- the JSF page can silently layer rows over one object, and
     * this API refuses to import that accident. The client PUTs the bytes to the returned URL (sending the
     * same {@code Content-Type} it declared here; S3 rejects anything the signature does not cover), then
     * records the row with {@code POST /api/media}.
     */
    @POST
    @Path("upload-url")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response uploadUrl(@HeaderParam(CSRF_HEADER) final String csrf, final UploadUrlRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!mediaAdminHere()) {
            return error(403, ApiErrors.FORBIDDEN, "Media administration required.");
        }
        if (body == null || body.key() == null || body.key().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "An object key is required.");
        }
        if (body.contentType() == null || body.contentType().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "A content type is required.");
        }
        if (body.size() == null || body.size() <= 0) {
            return error(400, ApiErrors.BAD_REQUEST, "The exact size in bytes is required.");
        }
        if (body.size() > MAX_UPLOAD_BYTES) {
            return error(422, ApiErrors.VALIDATION_FAILED,
                    "That file is too large; the limit is " + MAX_UPLOAD_BYTES + " bytes.");
        }
        final Response keyProblem = keyRefusal(body.key());
        if (keyProblem != null) {
            return keyProblem;
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        if (!media.isUploadEnabled()) {
            return error(503, ApiErrors.STORE_FAILED, "Uploads are not available in this deployment.");
        }
        final String stored = MediaCommands.siteKey(body.key());
        if (media.getByKey(stored) != null) {
            return error(409, ApiErrors.CONFLICT, "A media item already uses that key.");
        }
        final String url = media.presignUpload(stored, body.contentType(), body.size());
        if (url == null) {
            return error(500, ApiErrors.INTERNAL, "Could not issue an upload URL.");
        }
        return ok(Map.of(
                "url", url,
                "key", stored,
                "contentType", body.contentType(),
                "size", body.size(),
                "expiresInSeconds", MediaCommands.UPLOAD_URL_TTL.toSeconds()));
    }

    /** The metadata a client records once its bytes are in S3. Size and type are read from S3, not from here. */
    public record ConfirmRequest(String key, String title, String description, String slot, Integer position,
            Boolean hidden) {
    }

    /**
     * Step two of an upload: verify the object exists and record its row, firing the same media events and
     * audit the JSF upload does -- this is what keeps {@code ProfilePhotos} and the slot listings coherent.
     *
     * <p>422 when nothing is stored at the key: a confirm is a claim, and an unverified claim would list a
     * file that 404s for every visitor.
     */
    @POST
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response confirm(@HeaderParam(CSRF_HEADER) final String csrf, final ConfirmRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!mediaAdminHere()) {
            return error(403, ApiErrors.FORBIDDEN, "Media administration required.");
        }
        if (body == null || body.key() == null || body.key().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "An object key is required.");
        }
        final Response keyProblem = keyRefusal(body.key());
        if (keyProblem != null) {
            return keyProblem;
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        if (!media.isUploadEnabled()) {
            return error(503, ApiErrors.STORE_FAILED, "Uploads are not available in this deployment.");
        }
        final String stored = MediaCommands.siteKey(body.key());
        if (media.getByKey(stored) != null) {
            return error(409, ApiErrors.CONFLICT, "A media item already uses that key.");
        }
        if (media.statObject(stored).isEmpty()) {
            return error(422, ApiErrors.VALIDATION_FAILED, "Nothing is stored at that key; upload it first.");
        }
        final MediaItem saved = media.confirmUpload(stored, body.title(), body.description(), body.slot(),
                body.position() == null ? 0 : body.position(), requestedBy(), body.hidden());
        if (saved == null) {
            return error(500, ApiErrors.STORE_FAILED, "The file is stored but its row could not be recorded.");
        }
        return ok(toDto(media, saved));
    }

    /** Edited metadata. A changed {@code key} is a RENAME of the stored object -- old URLs break. */
    public record UpdateRequest(String key, String title, String description, String slot, Integer position,
            Boolean hidden) {
    }

    /**
     * Saves edited metadata, moving the stored object when the key changed. {@code uploaded} is preserved --
     * it records when the bytes were written; who edited what is the audit trail's job. A row this caller
     * may not manage on THIS site (another tenant's, or the shared site's from an org host) reads as absent.
     */
    @PUT
    @Path("{id}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response update(@HeaderParam(CSRF_HEADER) final String csrf, @PathParam("id") final String id,
            final UpdateRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!mediaAdminHere()) {
            return error(403, ApiErrors.FORBIDDEN, "Media administration required.");
        }
        if (body == null) {
            return error(400, ApiErrors.BAD_REQUEST, "A body is required.");
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        final MediaItem existing = media.getManageable(id);
        if (existing == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such media item.");
        }
        // The rename target as the commands will store it: an org's file stays in the org's namespace.
        final String target = MediaCommands.keyForOwner(existing.getOrgId(), body.key());
        if (target != null && !target.equals(existing.getS3Key())) {
            final Response keyProblem = keyRefusal(body.key());
            if (keyProblem != null) {
                return keyProblem;
            }
            final MediaItem holder = media.getByKey(target);
            if (holder != null && !holder.getId().equals(existing.getId())) {
                return error(409, ApiErrors.CONFLICT, "Another media item already uses that key.");
            }
        }
        final boolean hidden = body.hidden() == null ? existing.getHidden() : body.hidden();
        if (!media.update(id, body.key(), body.title(), body.description(), body.slot(), body.position(),
                hidden, requestedBy())) {
            return error(500, ApiErrors.STORE_FAILED, "The save failed; nothing was changed.");
        }
        return ok(toDto(media, media.get(id)));
    }

    /**
     * Removes the row and the object. S3 versioning keeps the bytes recoverable, but URLs stop working. As
     * with {@link #update}, a row that is not this caller's to manage on this site is 404.
     */
    @DELETE
    @Path("{id}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response delete(@HeaderParam(CSRF_HEADER) final String csrf, @PathParam("id") final String id) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!mediaAdminHere()) {
            return error(403, ApiErrors.FORBIDDEN, "Media administration required.");
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        final MediaItem existing = media.getManageable(id);
        if (existing == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such media item.");
        }
        if (!media.delete(id, requestedBy())) {
            return error(500, ApiErrors.STORE_FAILED, "The delete failed.");
        }
        return ok(Map.of("deleted", existing.getId(), "key", existing.getS3Key()));
    }

    /**
     * Drops the cached inventory so the next read comes from the table -- for rows written behind the
     * application's back. Same operation as the admin page's refresh button.
     */
    @POST
    @Path("refresh")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response refresh(@HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!mediaAdminHere()) {
            return error(403, ApiErrors.FORBIDDEN, "Media administration required.");
        }
        return ok(Map.of("items", Beans.get(MediaCommands.class).refreshFromDatabase(actor())));
    }

    /**
     * Why a caller-chosen key cannot be used, or null when it can. Malformed shapes are 400; a well-formed
     * key inside another feature's namespace is a 422 refusal, not a malformed request.
     */
    private Response keyRefusal(final String key) {
        final String trimmed = key.trim();
        if (trimmed.matches(".*\\s.*") || trimmed.contains("\\") || trimmed.contains("..")) {
            return error(400, ApiErrors.BAD_REQUEST, "Keys cannot contain whitespace, backslashes or '..'.");
        }
        if (MediaCommands.isReservedKey(trimmed)) {
            return error(422, ApiErrors.VALIDATION_FAILED,
                    "That key belongs to another feature's namespace (profile, chat or badge photos) or to "
                            + "another site's.");
        }
        return null;
    }

    /** Who to stamp on rows and audit records; the email, matching what the admin page records. */
    private String requestedBy() {
        final String email = actor().email();
        return email == null ? "api" : email;
    }

    private static MediaItemDto toDto(final MediaCommands media, final MediaItem item) {
        return MediaItemDto.of(item, item == null ? null : media.getUrl(item));
    }
}
