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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ChatAlbumAccess;
import org.paulsens.trip.action.ChatPhotos;
import org.paulsens.trip.action.ExpenseReceipts;
import org.paulsens.trip.action.MediaCommands;
import org.paulsens.trip.action.PageStylesheets;
import org.paulsens.trip.api.dto.MediaBasesDto;
import org.paulsens.trip.api.dto.MediaItemDto;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.media.UploadType;
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
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.media.adminRequired"));
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        return ok(media.getAll().stream().map(item -> toDto(media, item)).toList());
    }

    /**
     * Where chat and profile photos are served from, so a client can turn the keys it already holds
     * ({@code ChatAttachment.s3Key}/{@code thumbKey}, a profile slot key) into URLs: {@code base + key}.
     * The chat message shape itself stays key-only on purpose -- {@code ChatAttachment} is a persisted,
     * Serializable model, and a URL is a property of the deployment, not of the photo.
     */
    @GET
    @Path("bases")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response bases() {
        final MediaCommands media = Beans.get(MediaCommands.class);
        final ChatPhotos chatPhotos = ChatPhotos.getChatPhotos();
        final boolean remote = chatPhotos.isRemoteStore();
        final String chatBase = remote ? chatPhotos.getPublicBase() : absoluteUrl("/chat-photos/");
        final String profileBase = media.isUploadEnabled() ? media.publicUrl("") : absoluteUrl("/profile-photos/");
        return ok(new MediaBasesDto(chatBase, profileBase, remote));
    }

    /**
     * One slot's items, ordered for display. A non-admin gets what a public page would render -- visible
     * items only -- so a mobile client can build the same home sections the site shows. A trip's own
     * members get its whole chat album, hidden rows included, as the website's trip media page shows them:
     * "hidden" means kept off the PUBLIC site, and the uploader must be able to find the photo to change
     * their mind. Someone the album admits only because they read the trip's chat (an invited guest off the
     * roster, perhaps of another organization; owner decision 2026-11-19, "Match the chat") gets exactly the
     * rows the chat shows them, hidden ones included ({@code ChatAlbumAccess}: not before
     * they joined when the channel hides history from new members, not past its retention), plus, when the trip
     * is listed here, the public rows anyone may browse.
     *
     * <p>An administrator's listing is still a TENANT's listing. A library slot is a shared name
     * ({@code home-docs} exists on every site), so the admin gets this site's items in it, hidden included
     * ({@code MediaCommands.getDiscoverableInSlot}), never every organization's. A chat slot is one trip's
     * album: being a media admin of the site the request came to opens it only for whoever manages THAT
     * album ({@link #managesSlotAlbum}); anyone else is an ordinary caller there.
     */
    @GET
    @Path("slots/{slot}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response inSlot(@PathParam("slot") final String slot) {
        if (slot == null || slot.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.media.slotNameRequired"));
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        // Membership first: it is the apps' ordinary case and already answers "everything" for an album, so
        // only a non-member's request pays for the album-manager question.
        final boolean member = isMemberOfSlotTrip(slot);
        final boolean admin = ChatPhotos.isChatSlot(slot) ? (!member && managesSlotAlbum(slot)) : mediaAdminHere();
        // A receipt slot is no one's to browse here, admins included: receipts go out only through the
        // expense report's own gated servlet. Same answer as an unknown slot.
        final List<MediaItem> rows = ExpenseReceipts.isReceiptSlot(slot) ? null
                : (admin || member) ? media.getDiscoverableInSlot(slot) : rowsForNonMember(media, slot);
        return rows == null ? error(404, ApiErrors.NOT_FOUND, Text.get("api.media.noSuchSlot"))
                : ok(rows.stream().map(item -> toDto(media, item)).toList());
    }

    /**
     * What a caller who neither is on the trip nor manages its album gets: the public rows when the album is
     * listed here, the rows the chat shows them when they read it, both in album order. Neither: null, which
     * is the same 404 as an unknown slot (a slot name is guessable, and "exists but not yours" would confirm
     * another tenant's trip).
     */
    private List<MediaItem> rowsForNonMember(final MediaCommands media, final String slot) {
        final List<MediaItem> throughChat = rowsThroughChat(media, slot);
        final boolean browses = mayBrowseSlot(slot);
        if (throughChat == null && !browses) {
            return null;
        }
        if (throughChat == null) {
            return media.getVisibleInSlot(slot, 0);
        }
        if (!browses) {
            return throughChat;
        }
        final Set<String> seen = throughChat.stream().map(MediaItem::getId).collect(Collectors.toSet());
        return media.getDiscoverableInSlot(slot).stream()
                .filter(item -> seen.contains(item.getId()) || !item.getHidden())
                .toList();
    }

    /** The album rows the trip's chat shows the caller, or null when it is no chat slot or they do not read it. */
    private List<MediaItem> rowsThroughChat(final MediaCommands media, final String slot) {
        final String tripId = ChatPhotos.tripOfSlot(slot);
        final Person.Id me = personId();
        return tripId == null || me == null ? null
                : Beans.get(ChatAlbumAccess.class).visibleThroughChat(tripId, me,
                        () -> media.getDiscoverableInSlot(slot));
    }

    /**
     * Whether the caller administers the album a chat slot names ({@code MediaCommands.managesAlbum}: a
     * global media admin, the media editor of the trip's OWN organization, or the trip's manager). Read
     * fresh: the answer opens another tenant's hidden photos.
     */
    boolean managesSlotAlbum(final String slot) {
        final String tripId = ChatPhotos.tripOfSlot(slot);
        return tripId != null && MediaCommands.managesAlbum(caller(),
                DAO.getInstance().getTrip(tripId, Cached.NO).orElse(null));
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
     * Whether the caller is on the roster of the trip a chat slot names; false for any other slot. A reader of
     * the trip's chat who is not on its roster is NOT a member here: they get the chat's rows
     * ({@link #rowsThroughChat}), not the whole album.
     */
    boolean isMemberOfSlotTrip(final String slot) {
        final String tripId = ChatPhotos.tripOfSlot(slot);
        if (tripId == null) {
            return false;
        }
        final Person.Id me = personId();
        return me != null && DAO.getInstance().getTrip(tripId, Cached.YES)
                .map(trip -> trip.getPeople() != null && trip.getPeople().contains(me)).orElse(false);
    }

    /**
     * A chat slot is one trip's album: a non-admin may browse it only as that trip's member, or when the
     * trip is publicly listed on the SITE this request is for -- a signed-in traveller of one org must not
     * enumerate another org's album by trip id. On the product's own host, whose API lists every organization,
     * "publicly listed" also means {@code ListingScope.albumAcrossOrgs}: before 2026-11-17 the album of an open
     * trip was browsable there by any signed-in account, even when its organization had turned Shared sites off.
     * That rule asks for the organization's consent and nothing about dates, so an album stays open after its
     * trip ends, as it does on the website. "Member" is anyone who may read the trip ({@code canReadTrip}: its
     * roster, a role on it, managing someone on it), the open listing's own exception.
     * Library slots are site-scoped per item by the commands.
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
        if (me != null && trip.getPeople() != null && canReadTrip(trip)) {
            return true;
        }
        if (!Boolean.TRUE.equals(trip.getOpenToPublic()) || !ListingScope.forSite().listsSiteWide(trip.getOrgId())) {
            return false;
        }
        // On the product's own host the site lists every organization, so the album of another organization's
        // open trip is browsable only where that organization lets its trips cross organizations at all.
        return !SiteContext.current().reachesEverything() || ListingScope.albumAcrossOrgs(trip, orgIdsOf(me));
    }

    /**
     * One item by row id. An admin sees the items that are theirs to manage here, hidden or not
     * ({@code getManageable}: the ownership rule every write re-checks, so a known id does not reach
     * another tenant's hidden row); everything else, for everyone, is what a public page would show
     * (hidden reads as absent).
     */
    @GET
    @Path("{id}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("id") final String id) {
        final MediaCommands media = Beans.get(MediaCommands.class);
        final MediaItem managed = mediaAdminHere() ? media.getManageable(id) : null;
        final MediaItem item = managed != null ? managed : media.getVisible(id);
        // A receipt's DTO would carry its CDN URL: it reads as absent here (see inSlot).
        if (item == null || ExpenseReceipts.isReceiptSlot(item.getSlot())) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.media.noSuchItem"));
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        if (!mediaAdminHere()) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.media.adminRequired"));
        }
        if (body == null || body.key() == null || body.key().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.media.keyRequired"));
        }
        if (body.contentType() == null || body.contentType().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.media.contentTypeRequired"));
        }
        if (body.size() == null || body.size() <= 0) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.media.sizeRequired"));
        }
        if (body.size() > MAX_UPLOAD_BYTES) {
            return error(422, ApiErrors.VALIDATION_FAILED,
                    Text.get("api.media.fileTooLarge", String.valueOf(MAX_UPLOAD_BYTES)));
        }
        final Response keyProblem = keyRefusal(body.key());
        if (keyProblem != null) {
            return keyProblem;
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        if (!media.isUploadEnabled()) {
            return error(503, ApiErrors.STORE_FAILED, Text.get("api.media.uploadsUnavailable"));
        }
        final String stored = MediaCommands.siteKey(body.key());
        if (media.getByKey(stored) != null) {
            return error(409, ApiErrors.CONFLICT, Text.get("api.media.keyTaken"));
        }
        if (!UploadType.mayDeclare(body.contentType())) {
            // The bytes go straight to S3 and are served from the media host every organization shares, so
            // only a type that cannot run there is signed (docs/security-headers.md, "nosniff").
            return error(422, ApiErrors.VALIDATION_FAILED, Text.get("api.media.typeNotUploadable"));
        }
        final String url = media.presignUpload(stored, body.contentType(), body.size());
        if (url == null) {
            return error(500, ApiErrors.INTERNAL, Text.get("api.media.uploadUrlNotIssued"));
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        if (!mediaAdminHere()) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.media.adminRequired"));
        }
        if (body == null || body.key() == null || body.key().isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.media.keyRequired"));
        }
        final Response keyProblem = keyRefusal(body.key());
        if (keyProblem != null) {
            return keyProblem;
        }
        final String slotProblem = MediaCommands.slotRefusal(body.slot(), null);
        if (slotProblem != null) {
            return error(422, ApiErrors.VALIDATION_FAILED, slotProblem);
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        if (!media.isUploadEnabled()) {
            return error(503, ApiErrors.STORE_FAILED, Text.get("api.media.uploadsUnavailable"));
        }
        final String stored = MediaCommands.siteKey(body.key());
        if (media.getByKey(stored) != null) {
            return error(409, ApiErrors.CONFLICT, Text.get("api.media.keyTaken"));
        }
        if (media.statObject(stored).isEmpty()) {
            return error(422, ApiErrors.VALIDATION_FAILED, Text.get("api.media.nothingStored"));
        }
        final MediaItem saved = media.confirmUpload(stored, body.title(), body.description(), body.slot(),
                body.position() == null ? 0 : body.position(), requestedBy(), body.hidden());
        if (saved == null) {
            return error(500, ApiErrors.STORE_FAILED, Text.get("api.media.rowNotRecorded"));
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        if (!mediaAdminHere()) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.media.adminRequired"));
        }
        if (body == null) {
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.shared.bodyRequired"));
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        final MediaItem existing = media.getManageable(id);
        if (existing == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.media.noSuchItem"));
        }
        // A library edit cannot MOVE a row into a trip's album (or a hotel's, or a report's): see slotRefusal.
        final String slotProblem = MediaCommands.slotRefusal(body.slot(), existing.getSlot());
        if (slotProblem != null) {
            return error(422, ApiErrors.VALIDATION_FAILED, slotProblem);
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
                return error(409, ApiErrors.CONFLICT, Text.get("api.media.keyTakenByAnother"));
            }
            // Moving a stylesheet a page links would leave the page linking a missing file (as a delete would).
            final String usedBy = new PageStylesheets(this::caller).usersInWords(existing);
            if (usedBy != null) {
                return error(409, ApiErrors.CONFLICT, Text.get("feedback.pageStylesheets.stillUsed", usedBy));
            }
        }
        final boolean hidden = body.hidden() == null ? existing.getHidden() : body.hidden();
        if (!media.update(id, body.key(), body.title(), body.description(), body.slot(), body.position(),
                hidden, requestedBy())) {
            return error(500, ApiErrors.STORE_FAILED, Text.get("api.media.saveFailed"));
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        if (!mediaAdminHere()) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.media.adminRequired"));
        }
        final MediaCommands media = Beans.get(MediaCommands.class);
        final MediaItem existing = media.getManageable(id);
        if (existing == null) {
            return error(404, ApiErrors.NOT_FOUND, Text.get("api.media.noSuchItem"));
        }
        // A stylesheet a page still links is refused (it would leave the page linking a missing file): a 409 naming
        // the pages, never a 500 that reads like a fault worth retrying.
        final String usedBy = new PageStylesheets(this::caller).usersInWords(existing);
        if (usedBy != null) {
            return error(409, ApiErrors.CONFLICT, Text.get("feedback.pageStylesheets.stillUsed", usedBy));
        }
        if (!media.delete(id, requestedBy())) {
            return error(500, ApiErrors.STORE_FAILED, Text.get("api.media.deleteFailed"));
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
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        if (!mediaAdminHere()) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.media.adminRequired"));
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
            return error(400, ApiErrors.BAD_REQUEST, Text.get("api.media.keyBadCharacters"));
        }
        if (MediaCommands.isReservedKey(trimmed)) {
            return error(422, ApiErrors.VALIDATION_FAILED,
                    Text.get("api.media.keyForeignNamespace"));
        }
        return null;
    }

    /**
     * Both URLs carry the item's cache buster: a rotated picture keeps its key, so only the query moves. Also the
     * shape of an album photo the chat's album sources answer ({@code ChatResource.albumPhotos}), and the start of
     * Discover's public album photo ({@code DiscoverTripMapper.publicAlbum}).
     */
    public static MediaItemDto toDto(final MediaCommands media, final MediaItem item) {
        if (item == null) {
            return null;
        }
        return MediaItemDto.of(item, media.getUrl(item) + item.getCacheBuster(),
                media.publicUrl(item.getDisplayKey()) + item.getCacheBuster());
    }
}
