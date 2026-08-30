package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.MediaCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.api.dto.MediaItemDto;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link MediaResource}: the media library over the wire.
 *
 * <p>The behaviours most worth keeping honest are the ones the presigned upload design leans on: a confirm
 * must verify the object rather than trust the call, caller-chosen keys must stay out of other features'
 * namespaces, and a key already claimed by a row must 409 rather than quietly minting a second row over the
 * same object.
 */
public class MediaResourceTest extends ResourceTestSupport {

    private static final Person.Id ADMIN = Person.Id.from("media-admin");
    private static final Person.Id USER = Person.Id.from("media-user");

    private MediaCommands media;
    private MediaResource resource;

    @BeforeMethod
    public void bindBeans() {
        media = bindMock(MediaCommands.class);
        bindMock(PersonCommands.class);
        resource = resource(new MediaResource());
    }

    private static MediaItem item(final String id, final String key, final Boolean hidden) {
        return new MediaItem(id, key, "Title of " + id, "desc", "application/pdf", 9L, "home-docs", 1,
                LocalDateTime.of(2026, 8, 1, 12, 0), "who@example.com", null, hidden);
    }

    @Test
    public void everyAdminOperationRefusesAnOrdinaryUser() {
        signedInAs(USER);

        assertError(resource.list(), 403, ApiErrors.FORBIDDEN);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 1L)),
                403, ApiErrors.FORBIDDEN);
        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest("downloads/a.pdf", null, null, null, null, null)),
                403, ApiErrors.FORBIDDEN);
        assertError(resource.update(CSRF_OK, "id", new MediaResource.UpdateRequest(null, "t", null, null, null,
                null)), 403, ApiErrors.FORBIDDEN);
        assertError(resource.delete(CSRF_OK, "id"), 403, ApiErrors.FORBIDDEN);
        assertError(resource.refresh(CSRF_OK), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(media);
    }

    @Test
    public void everyMutationRefusesAMissingCsrfSentinel() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.uploadUrl(null,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 1L)),
                403, ApiErrors.CSRF);
        assertError(resource.confirm(null,
                new MediaResource.ConfirmRequest("downloads/a.pdf", null, null, null, null, null)),
                403, ApiErrors.CSRF);
        assertError(resource.update(null, "id", null), 403, ApiErrors.CSRF);
        assertError(resource.delete(null, "id"), 403, ApiErrors.CSRF);
        assertError(resource.refresh(null), 403, ApiErrors.CSRF);
        Mockito.verifyNoInteractions(media);
    }

    @Test
    public void listReturnsTheInventoryWithResolvedUrls() {
        signedInAsSiteAdmin(ADMIN);
        final MediaItem stored = item("m1", "downloads/guide.pdf", null);
        Mockito.when(media.getAll()).thenReturn(List.of(stored));
        Mockito.when(media.getUrl(stored)).thenReturn("https://cdn.example/downloads/guide.pdf");

        final Response response = resource.list();

        assertOk(response);
        final List<?> items = (List<?>) response.getEntity();
        Assert.assertEquals(items.size(), 1);
        final MediaItemDto dto = (MediaItemDto) items.get(0);
        Assert.assertEquals(dto.id(), "m1");
        Assert.assertEquals(dto.key(), "downloads/guide.pdf");
        Assert.assertEquals(dto.url(), "https://cdn.example/downloads/guide.pdf");
        Assert.assertFalse(dto.hidden());
    }

    /** A non-admin browsing a slot sees what a public page would render, never the hidden rows. */
    @Test
    public void slotListingServesOrdinaryUsersTheVisibleItemsOnly() {
        signedInAs(USER);
        final MediaItem visible = item("m1", "downloads/a.pdf", null);
        Mockito.when(media.getVisibleInSlot("home-docs", 0)).thenReturn(List.of(visible));

        final Response response = resource.inSlot("home-docs");

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
        Mockito.verify(media, Mockito.never()).getInSlot(ArgumentMatchers.anyString());
    }

    @Test
    public void slotListingServesAdminsEverythingInTheSlot() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.getInSlot("home-docs"))
                .thenReturn(List.of(item("m1", "downloads/a.pdf", true)));

        final Response response = resource.inSlot("home-docs");

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
        Mockito.verify(media, Mockito.never()).getVisibleInSlot(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyInt());
    }

    @Test
    public void aBlankSlotNameIsRefused() {
        signedInAs(USER);

        assertError(resource.inSlot(" "), 400, ApiErrors.BAD_REQUEST);
    }

    /** Hidden reads as absent for a non-admin: 404, not a redacted body. */
    @Test
    public void gettingOneItemRedactsByPrivilege() {
        signedInAs(USER);
        Mockito.when(media.getVisible("m1")).thenReturn(null);
        assertError(resource.get("m1"), 404, ApiErrors.NOT_FOUND);

        signedInAsSiteAdmin(ADMIN);
        final MediaResource adminResource = resource(new MediaResource());
        final MediaItem hidden = item("m1", "downloads/a.pdf", true);
        Mockito.when(media.get("m1")).thenReturn(hidden);
        Mockito.when(media.getUrl(hidden)).thenReturn("https://cdn.example/downloads/a.pdf");

        final Response response = adminResource.get("m1");

        assertOk(response);
        Assert.assertTrue(((MediaItemDto) response.getEntity()).hidden());
    }

    @Test
    public void anUploadUrlRequestIsValidatedBeforeAnythingIsSigned() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.uploadUrl(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest(" ", "application/pdf", 1L)), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", " ", 1L)), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", null)),
                400, ApiErrors.BAD_REQUEST);
        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 0L)),
                400, ApiErrors.BAD_REQUEST);
        Mockito.verify(media, Mockito.never()).presignUpload(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyLong());
    }

    @Test
    public void anOversizedUploadIsRefusedBeforeSigning() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.uploadUrl(CSRF_OK, new MediaResource.UploadUrlRequest(
                "downloads/a.pdf", "application/pdf", MediaResource.MAX_UPLOAD_BYTES + 1)),
                422, ApiErrors.VALIDATION_FAILED);
        Mockito.verifyNoInteractions(media);
    }

    /** A key is a public URL path; malformed shapes are refused as malformed, not signed. */
    @Test
    public void malformedKeysAreBadRequests() {
        signedInAsSiteAdmin(ADMIN);

        for (final String bad : List.of("down loads/a.pdf", "downloads\\a.pdf", "downloads/../secret.pdf")) {
            assertError(resource.uploadUrl(CSRF_OK,
                    new MediaResource.UploadUrlRequest(bad, "application/pdf", 1L)),
                    400, ApiErrors.BAD_REQUEST);
        }
        Mockito.verifyNoInteractions(media);
    }

    /** profilePics/ and chat/ belong to features that index those prefixes; the API must not write there. */
    @Test
    public void keysInAnotherFeaturesNamespaceAreRefused() {
        signedInAsSiteAdmin(ADMIN);

        for (final String reserved : List.of("profilePics/p1/1-1.jpg", "chat/trip-1/photo.jpg")) {
            assertError(resource.uploadUrl(CSRF_OK,
                    new MediaResource.UploadUrlRequest(reserved, "image/jpeg", 1L)),
                    422, ApiErrors.VALIDATION_FAILED);
            assertError(resource.confirm(CSRF_OK,
                    new MediaResource.ConfirmRequest(reserved, null, null, null, null, null)),
                    422, ApiErrors.VALIDATION_FAILED);
        }
        Mockito.verifyNoInteractions(media);
    }

    @Test
    public void anUploadUrlNeedsABucketToPointAt() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(false);

        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 1L)),
                503, ApiErrors.STORE_FAILED);
    }

    /** The JSF page can layer rows over one object; the API refuses to import that accident. */
    @Test
    public void aKeyAlreadyClaimedByARowIsAConflict() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.getByKey("downloads/a.pdf")).thenReturn(item("m1", "downloads/a.pdf", null));

        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 1L)),
                409, ApiErrors.CONFLICT);
        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest("downloads/a.pdf", null, null, null, null, null)),
                409, ApiErrors.CONFLICT);
    }

    @Test
    public void aFailedPresignIsAServerError() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.presignUpload("downloads/a.pdf", "application/pdf", 1L)).thenReturn(null);

        assertError(resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("downloads/a.pdf", "application/pdf", 1L)),
                500, ApiErrors.INTERNAL);
    }

    @Test
    public void anUploadUrlAnswersTheSignedUrlAndItsTerms() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.presignUpload("/downloads/a.pdf", "application/pdf", 9L))
                .thenReturn("https://bucket.s3/downloads/a.pdf?sig");

        final Response response = resource.uploadUrl(CSRF_OK,
                new MediaResource.UploadUrlRequest("/downloads/a.pdf", "application/pdf", 9L));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("url"), "https://bucket.s3/downloads/a.pdf?sig");
        // The normalized key, because that is what the signature covers and what the confirm must repeat.
        Assert.assertEquals(body.get("key"), "downloads/a.pdf");
        Assert.assertEquals(body.get("expiresInSeconds"), MediaCommands.UPLOAD_URL_TTL.toSeconds());
    }

    /** The confirm is a claim; an object that is not actually in S3 must not become a listed row. */
    @Test
    public void confirmingAnUploadThatNeverHappenedIsRefused() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.statObject("downloads/a.pdf")).thenReturn(java.util.Optional.empty());

        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest("downloads/a.pdf", "T", null, null, null, null)),
                422, ApiErrors.VALIDATION_FAILED);
        Mockito.verify(media, Mockito.never()).confirmUpload(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void confirmNeedsAKeyAndABucket() {
        signedInAsSiteAdmin(ADMIN);

        assertError(resource.confirm(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest(" ", null, null, null, null, null)), 400, ApiErrors.BAD_REQUEST);

        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest("downloads/a.pdf", null, null, null, null, null)),
                503, ApiErrors.STORE_FAILED);
    }

    @Test
    public void confirmingRecordsTheRowAndStampsTheActor() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        final MediaItem saved = item("m2", "downloads/a.pdf", null);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.statObject("downloads/a.pdf"))
                .thenReturn(java.util.Optional.of(new MediaCommands.StoredObject(9L, "application/pdf")));
        Mockito.when(media.confirmUpload("downloads/a.pdf", "Guide", "d", "home-docs", 2,
                "admin@example.com", Boolean.TRUE)).thenReturn(saved);
        Mockito.when(media.getUrl(saved)).thenReturn("https://cdn.example/downloads/a.pdf");

        final Response response = resource.confirm(CSRF_OK, new MediaResource.ConfirmRequest(
                "downloads/a.pdf", "Guide", "d", "home-docs", 2, Boolean.TRUE));

        assertOk(response);
        Assert.assertEquals(((MediaItemDto) response.getEntity()).id(), "m2");
    }

    @Test
    public void aConfirmTheStoreRefusesIsReported() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.statObject("downloads/a.pdf"))
                .thenReturn(java.util.Optional.of(new MediaCommands.StoredObject(9L, "application/pdf")));
        Mockito.when(media.confirmUpload(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(null);

        assertError(resource.confirm(CSRF_OK,
                new MediaResource.ConfirmRequest("downloads/a.pdf", null, null, null, null, null)),
                500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void updatingAnUnknownItemIs404() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.get("nope")).thenReturn(null);

        assertError(resource.update(CSRF_OK, "nope",
                new MediaResource.UpdateRequest(null, "T", null, null, null, null)), 404, ApiErrors.NOT_FOUND);
        assertError(resource.update(CSRF_OK, "nope", null), 400, ApiErrors.BAD_REQUEST);
    }

    /** A rename must not steal a key another row already owns, nor move into a reserved namespace. */
    @Test
    public void renamingGuardsTheTargetKey() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.get("m1")).thenReturn(item("m1", "downloads/a.pdf", null));
        Mockito.when(media.getByKey("downloads/b.pdf")).thenReturn(item("m2", "downloads/b.pdf", null));

        assertError(resource.update(CSRF_OK, "m1",
                new MediaResource.UpdateRequest("downloads/b.pdf", "T", null, null, null, null)),
                409, ApiErrors.CONFLICT);
        assertError(resource.update(CSRF_OK, "m1",
                new MediaResource.UpdateRequest("profilePics/p1.jpg", "T", null, null, null, null)),
                422, ApiErrors.VALIDATION_FAILED);
        Mockito.verify(media, Mockito.never()).update(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean(), ArgumentMatchers.any());
    }

    /** An omitted hidden flag preserves what the row already says instead of quietly unhiding it. */
    @Test
    public void updatePreservesHiddenWhenTheBodyIsSilent() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        final MediaItem hidden = item("m1", "downloads/a.pdf", true);
        Mockito.when(media.get("m1")).thenReturn(hidden);
        Mockito.when(media.getUrl(hidden)).thenReturn("url");
        Mockito.when(media.update("m1", null, "New title", "d", "slot", 3, true, "admin@example.com"))
                .thenReturn(true);

        final Response response = resource.update(CSRF_OK, "m1",
                new MediaResource.UpdateRequest(null, "New title", "d", "slot", 3, null));

        assertOk(response);
        Mockito.verify(media).update("m1", null, "New title", "d", "slot", 3, true, "admin@example.com");
    }

    @Test
    public void aFailedUpdateIsReported() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.get("m1")).thenReturn(item("m1", "downloads/a.pdf", null));
        Mockito.when(media.update(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyBoolean(), ArgumentMatchers.any())).thenReturn(false);

        assertError(resource.update(CSRF_OK, "m1",
                new MediaResource.UpdateRequest(null, "T", null, null, null, null)), 500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void deletingRemovesTheRowAndNamesWhatWentAway() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        Mockito.when(media.get("m1")).thenReturn(item("m1", "downloads/a.pdf", null));
        Mockito.when(media.delete("m1", "admin@example.com")).thenReturn(true);

        final Response response = resource.delete(CSRF_OK, "m1");

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("deleted"), "m1");
        Assert.assertEquals(body.get("key"), "downloads/a.pdf");
    }

    @Test
    public void deletingAnUnknownItemIs404AndAFailedDeleteIsReported() {
        signedInAsSiteAdmin(ADMIN);
        Mockito.when(media.get("nope")).thenReturn(null);
        assertError(resource.delete(CSRF_OK, "nope"), 404, ApiErrors.NOT_FOUND);

        Mockito.when(media.get("m1")).thenReturn(item("m1", "downloads/a.pdf", null));
        Mockito.when(media.delete(ArgumentMatchers.eq("m1"), ArgumentMatchers.any())).thenReturn(false);
        assertError(resource.delete(CSRF_OK, "m1"), 500, ApiErrors.STORE_FAILED);
    }

    /** The refresh must pass the REST edge's actor: AuditActor.current() records nobody off a Faces thread. */
    @Test
    public void refreshingReportsTheCountAndCarriesTheActor() {
        signedInAsSiteAdmin(ADMIN);
        session("loginEmail", "admin@example.com");
        Mockito.when(media.refreshFromDatabase(ArgumentMatchers.any(AuditActor.class))).thenReturn(42);

        final Response response = resource.refresh(CSRF_OK);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("items"), 42);
        Mockito.verify(media, Mockito.never()).refreshFromDatabase();
    }

    @Test
    public void theProducedTypeIsTheMediaMediaType() {
        Assert.assertEquals(new MediaResource().versionedType(), ApiMediaTypes.MEDIA_V1);
    }
}
