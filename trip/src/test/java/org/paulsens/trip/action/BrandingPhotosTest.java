package org.paulsens.trip.action;

import jakarta.faces.context.FacesContext;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.content.ContentRenderer;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The branding-image store: its versioned key layout, the history it keeps so a replaced image can be
 * recovered, the cap that stops that history growing forever, and the tenancy rule that decides who may
 * write or even list one.
 */
public class BrandingPhotosTest {

    private static final String ORG = "org-1234";
    private static final String OTHER_ORG = "org-9999";

    private final List<String> deleted = new ArrayList<>();
    private MediaCommands media;
    private boolean manageable;
    private List<String> listing;
    private BrandingPhotos photos;

    @BeforeMethod
    public void setUp() throws Exception {
        deleted.clear();
        manageable = true;
        listing = List.of();
        media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        Mockito.when(media.listKeys(ArgumentMatchers.anyString())).thenAnswer(call -> listing);
        photos = new BrandingPhotos() {
            @Override
            protected boolean mayManage(final String orgId) {
                return manageable;
            }
        };
        final Field field = BrandingPhotos.class.getDeclaredField("media");
        field.setAccessible(true);
        field.set(photos, media);
    }

    private static byte[] bytes(final String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private String storeLogo(final String content) {
        return photos.store(ORG, "logo", bytes(content), "image/png", "png");
    }

    // --- the key layout ---

    @Test
    public void aKeyNamesTheOrganizationTheRoleAndTheVersionAndParsesBackToThem() {
        final String key = BrandingPhotos.keyFor(ORG, "logo", 1725000000000L, "png");
        Assert.assertEquals(key, "org/" + ORG + "/branding/logo-1725000000000.png",
                "an organization's own namespace, which MediaCommands refuses to any other site");
        final BrandingPhotos.BrandingKey parsed = BrandingPhotos.parse(key);
        Assert.assertEquals(parsed.orgId(), ORG);
        Assert.assertEquals(parsed.role(), "logo");
        Assert.assertEquals(parsed.version(), 1725000000000L);
        Assert.assertEquals(parsed.extension(), "png");
        Assert.assertEquals(BrandingPhotos.contentTypeOf(key), "image/png");
        Assert.assertEquals(BrandingPhotos.contentTypeOf(
                BrandingPhotos.keyFor(ORG, "ogImage", 7L, "jpg")), "image/jpeg");
    }

    @Test
    public void anythingThatIsNotOneOfOurKeysParsesToNothingRatherThanToAGuess() {
        final String[] refused = {
                null,
                "",
                "profilePics/abc/1-2.jpg",
                "org/" + ORG + "/media/logo-1.png",       // the org's library, not its branding
                "org//branding/logo-1.png",               // no organization
                "org/" + ORG + "/branding/logo.png",      // no version
                "org/" + ORG + "/branding/logo-x.png",    // not a number
                "org/" + ORG + "/branding/logo--1.png",   // negative
                "org/" + ORG + "/branding/mascot-1.png",  // not a role this site has
                "org/" + ORG + "/branding/logo-1",        // no extension
                "org/" + ORG + "/branding/sub/logo-1.png",
        };
        for (final String key : refused) {
            Assert.assertNull(BrandingPhotos.parse(key), "must not parse: " + key);
            Assert.assertNull(BrandingPhotos.contentTypeOf(key), "and names no bytes: " + key);
        }
        Assert.assertNull(BrandingPhotos.contentTypeOf(BrandingPhotos.keyFor(ORG, "logo", 1L, "webp")),
                "a known role with an extension we never write is still not servable");
    }

    // --- versions, history and recovery ---

    @Test
    public void aReplacementIsANewKeyAndTheOneItReplacedIsStillThere() {
        final String first = storeLogo("first");
        final String second = storeLogo("second");

        Assert.assertNotNull(first);
        Assert.assertNotEquals(second, first, "a day-long CDN and browser cache make overwriting unusable");
        Assert.assertEquals(photos.localGet(first).map(String::new).orElse(null), "first",
                "the previous version is KEPT, which is what makes recovery possible");
        Assert.assertEquals(photos.localGet(second).map(String::new).orElse(null), "second");

        final List<BrandingPhotos.Version> history = photos.history(ORG, "logo");
        Assert.assertEquals(history.size(), 2);
        Assert.assertEquals(history.get(0).key(), second, "newest first, so the current one leads");
        Assert.assertEquals(history.get(1).key(), first);
        Assert.assertEquals(history.get(0).role(), "logo");
        Assert.assertTrue(history.get(0).when().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"),
                "a date the page can show beside the thumbnail: " + history.get(0).when());
        Assert.assertTrue(history.get(0).url().endsWith("/branding-photos/" + second),
                "with no bucket the app serves the bytes itself: " + history.get(0).url());
    }

    @Test
    public void versionsAreStrictlyIncreasingSoTwoUploadsInAMillisecondCannotCollide() {
        final List<Long> versions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            versions.add(BrandingPhotos.parse(storeLogo("v" + i)).version());
        }
        for (int i = 1; i < versions.size(); i++) {
            Assert.assertTrue(versions.get(i) > versions.get(i - 1),
                    "versions must strictly increase: " + versions);
        }
    }

    @Test
    public void eachRoleKeepsItsOwnHistoryAndTheOldestFallOffTheCap() {
        final List<String> keys = new ArrayList<>();
        for (int i = 0; i < BrandingPhotos.KEEP_VERSIONS + 3; i++) {
            keys.add(storeLogo("logo" + i));
        }
        photos.store(ORG, "favicon", bytes("icon"), "image/png", "png");

        Assert.assertEquals(photos.history(ORG, "logo").size(), BrandingPhotos.KEEP_VERSIONS,
                "kept for recovery, capped so a weekly re-upload cannot grow forever");
        Assert.assertEquals(photos.history(ORG, "favicon").size(), 1, "roles do not share a history");
        Assert.assertTrue(photos.localGet(keys.get(0)).isEmpty(), "the oldest are really removed");
        Assert.assertTrue(photos.localGet(keys.get(2)).isEmpty());
        Assert.assertTrue(photos.localGet(keys.getLast()).isPresent(), "and the newest are really kept");
    }

    @Test
    public void theStoredPrefixIsSeededOnceSoARestartStillSeesWhatWasUploadedBefore() {
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyBoolean())).thenReturn(true);
        listing = List.of(BrandingPhotos.keyFor(ORG, "logo", 100L, "png"),
                BrandingPhotos.keyFor(ORG, "logo", 200L, "png"),
                "org/" + ORG + "/branding/not-ours.txt");

        final List<BrandingPhotos.Version> history = photos.history(ORG, "logo");
        Assert.assertEquals(history.size(), 2, "the listing IS the inventory: there are no media rows");
        Assert.assertEquals(history.get(0).version(), 200L, "newest first");
        Assert.assertEquals(photos.history(ORG, "logo").size(), 2, "and the listing happens once");
        Mockito.verify(media, Mockito.times(1)).listKeys(ArgumentMatchers.anyString());
    }

    @Test
    public void aFailedListingIsRetriedRatherThanLeavingThatOrgPermanentlyEmpty() {
        Mockito.when(media.listKeys(ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("S3 is having a moment"));
        Assert.assertTrue(photos.history(ORG, "logo").isEmpty(), "it answers empty rather than throwing");
        Mockito.verify(media, Mockito.times(1)).listKeys(ArgumentMatchers.anyString());
        Assert.assertTrue(photos.history(ORG, "logo").isEmpty());
        Mockito.verify(media, Mockito.times(2)).listKeys(ArgumentMatchers.anyString());
    }

    // --- tenancy: an object key is a string, and a bean that takes one is one typo from another tenant ---

    @Test
    public void onlyAnAdministratorOfTheOrganisationMayWriteOrEvenListItsBranding() {
        Assert.assertNotNull(storeLogo("mine"), "an org's own administrator may replace its logo");

        manageable = false;
        Assert.assertNull(storeLogo("theirs"), "everybody else is refused, before any byte is written");
        Assert.assertTrue(photos.history(ORG, "logo").isEmpty(),
                "and cannot enumerate the org's branding by guessing its id either");

        manageable = true;
        Assert.assertEquals(photos.history(ORG, "logo").size(), 1, "the refused write really wrote nothing");
        Assert.assertTrue(photos.history(OTHER_ORG, "logo").isEmpty(), "orgs never share a history");
    }

    @Test
    public void anythingMissingOrUnknownIsRefusedWithoutTouchingTheStore() {
        Assert.assertNull(photos.store(null, "logo", bytes("x"), "image/png", "png"));
        Assert.assertNull(photos.store("  ", "logo", bytes("x"), "image/png", "png"));
        Assert.assertNull(photos.store(ORG, "mascot", bytes("x"), "image/png", "png"),
                "a role name arriving from a page is untrusted");
        Assert.assertNull(photos.store(ORG, "logo", null, "image/png", "png"));
        Assert.assertNull(photos.store(ORG, "logo", new byte[0], "image/png", "png"));
        Assert.assertTrue(photos.history(ORG, "logo").isEmpty());
        Assert.assertTrue(photos.history(ORG, "mascot").isEmpty());
        Assert.assertTrue(photos.history(null, "logo").isEmpty());
    }

    // --- the URLs, which are stored in an http(s)-validated setting ---

    @Test
    public void withNoBucketTheUrlIsStillAbsoluteBecauseTheSettingItLandsInDemandsThat() {
        final FacesContext ctx = Mockito.mock(FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(ctx.getExternalContext().getRequestScheme()).thenReturn("http");
        Mockito.when(ctx.getExternalContext().getRequestServerName()).thenReturn("acme.unitetrip.com");
        Mockito.when(ctx.getExternalContext().getRequestServerPort()).thenReturn(8080);
        Mockito.when(ctx.getExternalContext().getRequestContextPath()).thenReturn("");
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
            final String key = storeLogo("x");

            final String url = photos.urlFor(key);
            Assert.assertEquals(url, "http://acme.unitetrip.com:8080/branding-photos/" + key);
            Assert.assertEquals(ContentRenderer.requireHttpUrl(url), url,
                    "a context-relative path would be refused on save and dropped again on render");

            Mockito.when(ctx.getExternalContext().getRequestServerPort()).thenReturn(80);
            Assert.assertEquals(photos.urlFor(key), "http://acme.unitetrip.com/branding-photos/" + key,
                    "the default port is not spelled out");
            Mockito.when(ctx.getExternalContext().getRequestScheme()).thenReturn("https");
            Mockito.when(ctx.getExternalContext().getRequestServerPort()).thenReturn(443);
            Assert.assertEquals(photos.urlFor(key), "https://acme.unitetrip.com/branding-photos/" + key);
        }
    }

    // --- the two stores ---

    @Test
    public void withABucketTheObjectsGoToItAndPrunedVersionsAreInvalidatedInTheCdn() {
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyBoolean())).thenReturn(true);
        Mockito.when(media.publicUrl(ArgumentMatchers.anyString()))
                .thenAnswer(call -> "https://cdn.example/" + call.getArgument(0));
        Mockito.when(media.deleteObject(ArgumentMatchers.anyString()))
                .thenAnswer(call -> deleted.add(call.getArgument(0)));

        String oldest = null;
        for (int i = 0; i < BrandingPhotos.KEEP_VERSIONS + 1; i++) {
            final String key = storeLogo("logo" + i);
            oldest = (oldest == null) ? key : oldest;
        }
        Mockito.verify(media, Mockito.times(BrandingPhotos.KEEP_VERSIONS + 1)).putObject(
                ArgumentMatchers.startsWith("org/" + ORG + "/branding/logo-"), ArgumentMatchers.any(),
                ArgumentMatchers.eq("image/png"), ArgumentMatchers.eq(BrandingPhotos.CACHE_SECONDS),
                ArgumentMatchers.eq(false));
        Assert.assertEquals(deleted, List.of(oldest), "only the version past the cap is removed");
        Mockito.verify(media).invalidateCdn(List.of("/" + oldest));
        Assert.assertTrue(photos.history(ORG, "logo").getFirst().url().startsWith("https://cdn.example/"),
                "with a bucket the CDN serves it, not this app");
    }

    @Test
    public void aRefusedPutChangesNothingAndTheLocalStoreIsCapped() {
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyBoolean())).thenReturn(false);
        Assert.assertNull(storeLogo("nope"), "an S3 failure is reported, not swallowed");
        Assert.assertTrue(photos.history(ORG, "logo").isEmpty(), "and leaves no version behind");

        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        photos.localStoreMaxBytesForTest(10L);
        final String first = storeLogo("aaaaaaaaaa");
        storeLogo("bbbbbbbbbb");
        Assert.assertTrue(photos.localGet(first).isEmpty(),
                "the local store is a capped convenience, evicted oldest-first like every other one");
    }
}
