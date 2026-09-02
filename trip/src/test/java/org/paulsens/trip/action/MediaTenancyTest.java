package org.paulsens.trip.action;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Media tenancy on the WRITE side (the 2026-09-01 production question): an organization's uploads live in
 * the org's own key namespace so the same file name from two orgs, or from an org and the shared site,
 * never meets at one object; and a row can be written only from the site that owns it -- upload-over,
 * rename, delete, slot move, visibility, metadata -- whoever asks, site admins included. The S3 client is
 * injected the way {@link MediaCommandsS3Test} does it, so no bucket is ever contacted.
 */
public class MediaTenancyTest {

    private static final Organization.Id ACME = Organization.Id.from(FakeData.ACME_ORG_ID);
    private static final Organization.Id BETA = Organization.Id.from(FakeData.BETA_ORG_ID);
    private static final SiteContext ACME_SITE = SiteContext.org(ACME, "acme", "acme.localhost");
    private static final SiteContext BETA_SITE = SiteContext.org(BETA, "beta", "beta.localhost");
    private static final String ACME_PREFIX = MediaCommands.ORG_KEY_PREFIX + FakeData.ACME_ORG_ID + "/";
    private static final String BETA_PREFIX = MediaCommands.ORG_KEY_PREFIX + FakeData.BETA_ORG_ID + "/";

    private MediaCommands media;
    private S3Client s3;
    private S3Presigner presigner;

    @BeforeClass
    public void seedAndInjectClients() throws Exception {
        DAO.getInstance();
        FakeData.addFakeData();
        System.setProperty("trip.media.bucket", "test-bucket");
        media = TestCallers.mediaAsSiteAdmin();
        s3 = Mockito.mock(S3Client.class);
        presigner = Mockito.mock(S3Presigner.class);
        inject("s3", s3);
        inject("presigner", presigner);
    }

    private void inject(final String fieldName, final Object value) throws Exception {
        final Field field = MediaCommands.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(media, value);
    }

    @BeforeMethod
    public void resetClients() {
        Mockito.reset(s3, presigner);
        Mockito.when(s3.headObject(ArgumentMatchers.any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(7L).contentType("image/jpeg").build());
    }

    @AfterClass(alwaysRun = true)
    public void clearBucket() {
        System.clearProperty("trip.media.bucket");
    }

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    private boolean upload(final String key) {
        final byte[] bytes = "bytes".getBytes(StandardCharsets.UTF_8);
        return media.upload(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg", "Title", null,
                "homepage", 0, "admin@example.com");
    }

    private List<String> putKeys() {
        final ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        Mockito.verify(s3, Mockito.atLeast(0)).putObject(put.capture(), ArgumentMatchers.any(RequestBody.class));
        return put.getAllValues().stream().map(PutObjectRequest::key).toList();
    }

    private static MediaItem seed(final String key, final String orgId) {
        final MediaItem item = new MediaItem("tenancy-" + UUID.randomUUID(), key, "T", null, "image/jpeg", 1L,
                "homepage", 0, LocalDateTime.now(), "seed", null, null, orgId);
        Assert.assertTrue(DAO.getInstance().saveMedia(item));
        return item;
    }

    private static void drop(final MediaItem item) {
        DAO.getInstance().deleteMedia(item.getId());
    }

    @Test
    public void keysAreNamespacedPerOrgAndLeftAloneOnTheSharedSite() throws Exception {
        Assert.assertEquals(MediaCommands.siteKey("background.jpg"), "background.jpg", "shared: as typed");
        Assert.assertEquals(MediaCommands.siteKey("/downloads/x.pdf"), "downloads/x.pdf");
        Assert.assertNull(MediaCommands.siteKey(null));
        Assert.assertNull(MediaCommands.siteKey("  "));
        Assert.assertEquals(media.getSiteKeyPrefix(), "");

        final String acmes = onSite(ACME_SITE, () -> MediaCommands.siteKey("background.jpg"));
        final String betas = onSite(BETA_SITE, () -> MediaCommands.siteKey("background.jpg"));
        Assert.assertEquals(acmes, ACME_PREFIX + "background.jpg");
        Assert.assertEquals(betas, BETA_PREFIX + "background.jpg");
        Assert.assertNotEquals(acmes, betas, "the same name from two orgs is two keys");
        Assert.assertEquals(onSite(ACME_SITE, () -> MediaCommands.siteKey(acmes)), acmes,
                "already namespaced: unchanged, so the upload-url key can be repeated at confirm");
        Assert.assertEquals(onSite(ACME_SITE, () -> MediaCommands.siteKey("/" + acmes)), acmes);
        Assert.assertEquals(onSite(ACME_SITE, () -> MediaCommands.siteKey(betas)), betas,
                "another org's namespace is not nested: left as is, for the reserved check to refuse");
        Assert.assertEquals(onSite(ACME_SITE, media::getSiteKeyPrefix), ACME_PREFIX);

        Assert.assertEquals(MediaCommands.keyForOwner(null, "a.jpg"), "a.jpg");
        Assert.assertEquals(MediaCommands.keyForOwner(FakeData.ACME_ORG_ID, "a.jpg"), ACME_PREFIX + "a.jpg");
        Assert.assertNull(MediaCommands.keyForOwner(FakeData.ACME_ORG_ID, null));
    }

    @Test
    public void reservedPrefixesAreTheOtherFeaturesAndEveryOtherSitesNamespace() throws Exception {
        for (final String reserved : List.of("profilePics/p/1.jpg", "chat/t/p.jpg", "badgeImages/t/1.jpg")) {
            Assert.assertTrue(MediaCommands.isReservedKey(reserved), reserved);
            Assert.assertTrue(onSite(ACME_SITE, () -> MediaCommands.isReservedKey(reserved)), reserved);
        }
        Assert.assertTrue(MediaCommands.isReservedKey(ACME_PREFIX + "x.jpg"), "shared host: never under org/");
        Assert.assertTrue(MediaCommands.isReservedKey("org/"));
        Assert.assertTrue(onSite(BETA_SITE, () -> MediaCommands.isReservedKey(ACME_PREFIX + "x.jpg")),
                "another tenant's namespace");
        Assert.assertTrue(onSite(ACME_SITE, () -> MediaCommands.isReservedKey(ACME_PREFIX)),
                "the bare prefix names no object");
        Assert.assertFalse(onSite(ACME_SITE, () -> MediaCommands.isReservedKey(ACME_PREFIX + "x.jpg")),
                "an org's own namespace, on its own host");
        Assert.assertFalse(MediaCommands.isReservedKey("downloads/guide.pdf"));
    }

    @Test
    public void theSameFileNameFromTwoOrgsAndTheSharedSiteIsThreeObjectsAndThreeRows() throws Exception {
        final String name = "background-" + System.nanoTime() + ".jpg";
        Assert.assertTrue(onSite(ACME_SITE, () -> upload(name)));
        Assert.assertTrue(onSite(BETA_SITE, () -> upload(name)));
        Assert.assertTrue(upload(name));

        Assert.assertEquals(putKeys(), List.of(ACME_PREFIX + name, BETA_PREFIX + name, name),
                "three distinct objects, the shared site's under the bare key as always");
        final MediaItem acmes = media.getByKey(ACME_PREFIX + name);
        final MediaItem betas = media.getByKey(BETA_PREFIX + name);
        final MediaItem shared = media.getByKey(name);
        Assert.assertEquals(acmes.getOrgId(), FakeData.ACME_ORG_ID);
        Assert.assertEquals(betas.getOrgId(), FakeData.BETA_ORG_ID);
        Assert.assertNull(shared.getOrgId());
        Assert.assertEquals(List.of(acmes.getId(), betas.getId(), shared.getId()).stream().distinct().count(), 3L,
                "row ids are fresh UUIDs, never derived from the key");
        // Each is discoverable and deletable on its own site only.
        Assert.assertTrue(onSite(ACME_SITE, () -> media.getCurated().contains(acmes)));
        Assert.assertFalse(onSite(ACME_SITE, () -> media.getCurated().contains(betas)));
        Assert.assertFalse(media.getCurated().contains(acmes));
        Assert.assertFalse(media.delete(acmes.getId(), "x"), "not from the shared host");
        Assert.assertTrue(onSite(ACME_SITE, () -> media.delete(acmes.getId(), "x")));
        Assert.assertTrue(onSite(BETA_SITE, () -> media.delete(betas.getId(), "x")));
        Assert.assertTrue(media.delete(shared.getId(), "x"));
    }

    /** The overwrite hole the namespace closes, and the belt for the rows that predate it. */
    @Test
    public void anUploadNeverOverwritesAnotherOwnersObject() throws Exception {
        // Acme's fixture document predates the layout: a bare key, owned by orgId. The shared site's admin
        // asking for exactly that path must not replace Acme's bytes.
        final MediaItem acmeLegacy = DAO.getInstance().getMedia("fake-acme-doc", Cached.NO).orElseThrow();
        Assert.assertFalse(acmeLegacy.getS3Key().startsWith(MediaCommands.ORG_KEY_PREFIX), "a bare legacy key");
        Assert.assertFalse(upload(acmeLegacy.getS3Key()), "the shared site cannot overwrite a tenant's file");
        Mockito.verifyNoInteractions(s3);
        // From another org's host, or Acme's own, the same request lands in THAT org's namespace: a new
        // object each time, never an overwrite of the bare-keyed original.
        Assert.assertTrue(onSite(BETA_SITE, () -> upload(acmeLegacy.getS3Key())));
        Assert.assertTrue(onSite(ACME_SITE, () -> upload(acmeLegacy.getS3Key())));
        Assert.assertEquals(putKeys(),
                List.of(BETA_PREFIX + acmeLegacy.getS3Key(), ACME_PREFIX + acmeLegacy.getS3Key()));
        Assert.assertTrue(onSite(BETA_SITE,
                () -> media.delete(media.getByKey(BETA_PREFIX + acmeLegacy.getS3Key()).getId(), "x")));
        Assert.assertTrue(onSite(ACME_SITE,
                () -> media.delete(media.getByKey(ACME_PREFIX + acmeLegacy.getS3Key()).getId(), "x")));
        Assert.assertEquals(DAO.getInstance().getMedia("fake-acme-doc", Cached.NO).orElseThrow(), acmeLegacy,
                "the original row is untouched");

        // Reserved namespaces are refused on the PAGE path too (the API already refused them).
        Mockito.reset(s3);
        Assert.assertFalse(upload("profilePics/p1/1.jpg"));
        Assert.assertFalse(upload(ACME_PREFIX + "sneaky.jpg"), "the shared host never writes under org/");
        Assert.assertFalse(onSite(ACME_SITE, () -> upload(BETA_PREFIX + "sneaky.jpg")));
        Mockito.verifyNoInteractions(s3);
        // A feature prefix typed on an org host is harmless: it lands INSIDE the org's namespace, which no
        // feature indexes, so it is simply namespaced rather than refused.
        Assert.assertTrue(onSite(ACME_SITE, () -> upload("badgeImages/t/1.jpg")));
        Assert.assertEquals(putKeys(), List.of(ACME_PREFIX + "badgeImages/t/1.jpg"));
        Assert.assertTrue(onSite(ACME_SITE,
                () -> media.delete(media.getByKey(ACME_PREFIX + "badgeImages/t/1.jpg").getId(), "x")));

        // Re-uploading over one's OWN file stays the page's replace path.
        Mockito.reset(s3);
        final String own = "downloads/own-" + System.nanoTime() + ".pdf";
        Assert.assertTrue(upload(own));
        Assert.assertTrue(upload(own), "replacing your own file under the same name");
        Assert.assertEquals(putKeys().size(), 2);
        for (final MediaItem row : DAO.getInstance().getAllMedia(Cached.NO)) {
            if (own.equals(row.getS3Key())) {
                drop(row);
            }
        }
    }

    /** The site half of the write rule, for a SITE ADMIN: Acme's rows on Acme's host and nowhere else. */
    @Test
    public void writesAreConfinedToTheOwnersSiteEvenForASiteAdmin() throws Exception {
        final MediaItem acmes = DAO.getInstance().getMedia("fake-acme-doc", Cached.NO).orElseThrow();
        for (final SiteContext elsewhere : List.of(SiteContext.shared("localhost"), BETA_SITE,
                SiteContext.marketing("www.localhost"))) {
            onSite(elsewhere, () -> {
                Assert.assertFalse(media.mayManage(acmes), elsewhere.toString());
                Assert.assertNull(media.getManageable("fake-acme-doc"), "the edit page: absent");
                Assert.assertFalse(media.setHidden("fake-acme-doc", true, "x"));
                Assert.assertFalse(media.update("fake-acme-doc", null, "Stolen", null, null, null, false, "x"));
                Assert.assertFalse(media.update("fake-acme-doc", "downloads/moved.pdf", "T", null, null, null,
                        "x"));
                Assert.assertFalse(media.assignToSlot("fake-acme-doc", "homepage", "x"));
                Assert.assertFalse(media.delete("fake-acme-doc", "x"));
                return null;
            });
        }
        Assert.assertEquals(DAO.getInstance().getMedia("fake-acme-doc", Cached.NO).orElseThrow(), acmes,
                "nothing changed");

        onSite(ACME_SITE, () -> {
            Assert.assertNotNull(media.getManageable("fake-acme-doc"), "on Acme's host a site admin manages it");
            Assert.assertTrue(media.setHidden("fake-acme-doc", true, "x"));
            Assert.assertTrue(media.setHidden("fake-acme-doc", false, "x"));
            Assert.assertTrue(media.update("fake-acme-doc", null, acmes.getTitle(), acmes.getDescription(),
                    acmes.getSlot(), acmes.getPosition(), false, "x"));
            // ...and the shared site's row is not writable from an org host, site admin or not.
            Assert.assertNull(media.getManageable("fake-doc-1"));
            Assert.assertFalse(media.setHidden("fake-doc-1", true, "x"));
            Assert.assertFalse(media.delete("fake-doc-1", "x"));
            return null;
        });
        Assert.assertEquals(DAO.getInstance().getMedia("fake-acme-doc", Cached.NO).orElseThrow(), acmes);

        // A chat album is the trip's: moderated wherever the trip's pages are, whichever org owns the rows.
        final MediaItem chat = new MediaItem("chat-x", "chat/t/p.jpg", "p", null, "image/jpeg", 1L,
                "tripChat-t", 0, LocalDateTime.now(), "x", null, null, FakeData.BETA_ORG_ID);
        Assert.assertTrue(MediaCommands.writableHere(chat));
        Assert.assertTrue(media.mayManage(chat));
        Assert.assertTrue(onSite(ACME_SITE, () -> media.mayManage(chat)));
    }

    @Test
    public void aRenameStaysInTheOwnersNamespaceAndNeverLandsOnAnotherRow() throws Exception {
        final MediaItem legacy = seed("downloads/legacy-" + System.nanoTime() + ".pdf", FakeData.ACME_ORG_ID);
        final MediaItem other = seed(ACME_PREFIX + "downloads/other-" + System.nanoTime() + ".pdf",
                FakeData.ACME_ORG_ID);
        final MediaItem shared = seed("downloads/shared-" + System.nanoTime() + ".pdf", null);
        try {
            final String wanted = "downloads/renamed-" + System.nanoTime() + ".pdf";
            Assert.assertTrue(onSite(ACME_SITE, () -> media.update(legacy.getId(), wanted, "T", null, null, null,
                    "x")));
            Assert.assertEquals(media.get(legacy.getId()).getS3Key(), ACME_PREFIX + wanted,
                    "a bare legacy key moves INTO the org's namespace on rename, whatever was typed");
            Assert.assertFalse(onSite(ACME_SITE, () -> media.update(legacy.getId(), other.getS3Key(), "T", null,
                    null, null, "x")), "never onto another row's key");
            Assert.assertFalse(onSite(ACME_SITE, () -> media.update(legacy.getId(), "downloads/other-"
                    + other.getS3Key().substring(other.getS3Key().lastIndexOf('-') + 1), "T", null, null, null,
                    "x")), "...even typed bare, since it namespaces to the same key");
            Assert.assertEquals(media.get(legacy.getId()).getS3Key(), ACME_PREFIX + wanted);
            Assert.assertTrue(onSite(ACME_SITE, () -> media.update(legacy.getId(), ACME_PREFIX + wanted, "T2",
                    null, null, null, "x")), "the same key is a metadata edit, not a rename");

            // The shared site's rows: reserved prefixes and every org namespace are off limits.
            Assert.assertFalse(media.update(shared.getId(), "profilePics/p.jpg", "T", null, null, null, "x"));
            Assert.assertFalse(media.update(shared.getId(), ACME_PREFIX + "taken.pdf", "T", null, null, null,
                    "x"), "a shared row cannot be moved into a tenant's namespace");
            final String siteDocKey = DAO.getInstance().getMedia("fake-doc-1", Cached.NO).orElseThrow().getS3Key();
            Assert.assertFalse(media.update(shared.getId(), siteDocKey, "T", null, null, null, "x"),
                    "nor onto a bare key another row holds");
            Assert.assertEquals(media.get(shared.getId()).getS3Key(), shared.getS3Key());
            Assert.assertNull(media.renameRefusal(shared, "downloads/free-" + System.nanoTime() + ".pdf"));
        } finally {
            drop(legacy);
            drop(other);
            drop(shared);
        }
    }

    /** The API's two-step upload signs and records the namespaced key on an org host. */
    @Test
    public void presignAndConfirmUseTheNamespacedKey() throws Exception {
        final PresignedPutObjectRequest signed = Mockito.mock(PresignedPutObjectRequest.class);
        Mockito.when(signed.url()).thenAnswer(call -> URI.create("https://bucket.s3/x?sig").toURL());
        Mockito.when(presigner.presignPutObject(ArgumentMatchers.any(PutObjectPresignRequest.class)))
                .thenReturn(signed);

        Assert.assertNotNull(onSite(ACME_SITE, () -> media.presignUpload("downloads/a.pdf", "application/pdf",
                9L)));
        final ArgumentCaptor<PutObjectPresignRequest> captor =
                ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        Mockito.verify(presigner).presignPutObject(captor.capture());
        Assert.assertEquals(captor.getValue().putObjectRequest().key(), ACME_PREFIX + "downloads/a.pdf",
                "the signature pins the namespaced key");
        Assert.assertNull(media.presignUpload(ACME_PREFIX + "a.pdf", "application/pdf", 9L),
                "the shared host signs nothing under org/");
        Assert.assertNull(onSite(BETA_SITE, () -> media.presignUpload(ACME_PREFIX + "a.pdf", "application/pdf",
                9L)));

        final String name = "downloads/confirm-" + System.nanoTime() + ".pdf";
        final MediaItem saved = onSite(ACME_SITE, () -> media.confirmUpload(name, "T", null, "home-docs", 0,
                "admin@example.com", null));
        Assert.assertNotNull(saved);
        Assert.assertEquals(saved.getS3Key(), ACME_PREFIX + name);
        Assert.assertEquals(saved.getOrgId(), FakeData.ACME_ORG_ID);
        Assert.assertNull(media.confirmUpload(ACME_PREFIX + name, "T", null, null, 0, "a", null),
                "the shared host cannot record a row in a tenant's namespace");
        Assert.assertNull(onSite(ACME_SITE, () -> media.confirmUpload(BETA_PREFIX + "b.pdf", "T", null, null, 0,
                "a", null)));
        Assert.assertTrue(onSite(ACME_SITE, () -> media.delete(saved.getId(), "x")));
    }
}
