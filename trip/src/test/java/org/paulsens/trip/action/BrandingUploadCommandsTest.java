package org.paulsens.trip.action;

import jakarta.faces.component.UIComponent;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.model.Person;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;
import org.primefaces.model.file.UploadedFile;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The org Appearance page's image uploads: the shared crop dialog's contract driven per ROLE, the per-role
 * processing, the tenancy refusals, and the step that matters most — a confirmed upload lands in the page's
 * unsaved PREVIEW, not in a saved setting, so it behaves exactly like a typed URL.
 */
public class BrandingUploadCommandsTest {

    private static final String ORG = "org-abc";
    private static final String LOGO = KnownSettings.SITE_LOGO_URL.getName();
    private static final String FAVICON = KnownSettings.SITE_FAVICON_URL.getName();
    private static final String OG_IMAGE = KnownSettings.SITE_OG_IMAGE_URL.getName();
    private static final String BACKGROUND = KnownSettings.SITE_BACKGROUND_URL.getName();

    private final Map<String, String> session = new HashMap<>();
    private final Map<String, Object> viewMap = new HashMap<>();
    private final Map<String, Object> published = new HashMap<>();
    private final List<Boolean> outcomes = new ArrayList<>();

    private Caller caller;
    private boolean manageable;
    private OrgCommands orgs;
    private BrandCommands brand;
    private BrandingPhotos photos;
    private PendingUploads pendingUploads;
    private BrandingUploadCommands bean;

    @BeforeMethod
    public void setUp() throws Exception {
        session.clear();
        viewMap.clear();
        published.clear();
        outcomes.clear();
        manageable = true;
        caller = new Caller(Person.Id.newInstance(), true, AuditActor.from(null), new PrivilegeCommands());

        orgs = Mockito.mock(OrgCommands.class);
        Mockito.when(orgs.canManageOrg(ArgumentMatchers.anyString())).thenAnswer(call -> manageable);

        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        Mockito.when(media.listKeys(ArgumentMatchers.anyString())).thenReturn(List.of());
        photos = new BrandingPhotos() {
            @Override
            protected boolean mayManage(final String orgId) {
                return manageable;
            }
        };
        set(BrandingPhotos.class, photos, "media", media);

        final HttpSession httpSession = fakeSession();
        brand = new BrandCommands();
        brand.setSessionSource(() -> httpSession);
        brand.setOrgSource(id -> Optional.empty());

        pendingUploads = new PendingUploads();
        bean = new BrandingUploadCommands() {
            @Override
            protected Caller caller() {
                return caller;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected <T> T viewMap(final String key) {
                return (T) viewMap.get(key);
            }

            @Override
            protected String sessionGet(final String key) {
                return session.get(key);
            }

            @Override
            protected void sessionPut(final String key, final String value) {
                if (value == null) {
                    session.remove(key);
                } else {
                    session.put(key, value);
                }
            }

            @Override
            protected void publishOutcome(final boolean stored) {
                outcomes.add(stored);
            }

            @Override
            protected void publishParam(final String name, final Object value) {
                published.put(name, value);
            }

            @Override
            protected String contextPath() {
                return "";
            }
        };
        set(BrandingUploadCommands.class, bean, "brandingPhotos", photos);
        set(BrandingUploadCommands.class, bean, "brand", brand);
        set(BrandingUploadCommands.class, bean, "orgs", orgs);
        bean.setPendingUploadsForTest(pendingUploads);
    }

    private static void set(final Class<?> type, final Object target, final String field, final Object value)
            throws Exception {
        final Field declared = type.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    /** A session that really holds what is put on it, so the preview can be read back as the page reads it. */
    private static HttpSession fakeSession() {
        final Map<String, Object> attributes = new HashMap<>();
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(ArgumentMatchers.anyString()))
                .thenAnswer(call -> attributes.get(call.<String>getArgument(0)));
        Mockito.doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(ArgumentMatchers.anyString(), ArgumentMatchers.any());
        Mockito.doAnswer(call -> attributes.remove(call.<String>getArgument(0)))
                .when(session).removeAttribute(ArgumentMatchers.anyString());
        return session;
    }

    private static FileUploadEvent uploadOf(final byte[] bytes) {
        final UploadedFile file = Mockito.mock(UploadedFile.class);
        Mockito.when(file.getContent()).thenReturn(bytes);
        Mockito.when(file.getSize()).thenReturn((long) bytes.length);
        Mockito.when(file.getFileName()).thenReturn("brand.jpg");
        return new FileUploadEvent(Mockito.mock(UIComponent.class), file, 1);
    }

    /** Opens the dialog for a role and parks an upload in it — the state every crop test starts from. */
    private void upload(final String role, final int width, final int height) {
        bean.startUpload(ORG, role);
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(width, height)));
    }

    private String previewed(final String setting) {
        return brand.appearanceEdit(ORG).get(setting);
    }

    // --- the four roles ---

    @Test
    public void everyRoleIsReachableByItsStoredKeyAndNothingElseIs() {
        for (final BrandingRole role : BrandingRole.values()) {
            Assert.assertEquals(BrandingRole.of(role.getKey()).orElseThrow(), role);
            Assert.assertEquals(BrandingRole.of(" " + role.getKey() + " ").orElseThrow(), role,
                    "a page's value arrives with whatever whitespace the page had");
            Assert.assertEquals(BrandingRole.valueOf(role.name()), role);
            Assert.assertFalse(role.getSetting().getName().isBlank(), "each role owns one Branding setting");
        }
        Assert.assertTrue(BrandingRole.of("mascot").isEmpty());
        Assert.assertTrue(BrandingRole.of(null).isEmpty());
        Assert.assertNotEquals(BrandingRole.LOGO.getKey(), BrandingRole.LOGO.name(),
                "the stored key is deliberately not the constant's name: renaming one is not a migration");
        Assert.assertEquals(BrandingRole.OG_IMAGE.getKey(), "ogImage");
        Assert.assertEquals(BrandingRole.LOGO.getSetting(), KnownSettings.SITE_LOGO_URL);
        Assert.assertEquals(BrandingRole.BACKGROUND.getMaxBytes(), 1024L * 1024L,
                "the user's stated budget, applied to the ENCODED bytes");
        Assert.assertEquals(BrandingRole.LOGO.getMaxBytes(), 0L, "a logo is bounded in pixels, not bytes");
    }

    // --- the dialog contract, per role ---

    @Test
    public void theDialogTakesItsHeaderAndItsCropShapeFromTheRoleBeingUploaded() {
        Assert.assertEquals(bean.getDialogHeader(), "Upload Image", "nothing open yet");
        Assert.assertEquals(bean.getCropAspect(), "", "and nothing to lock the crop to");
        Assert.assertEquals(bean.getRoleKey(), "");

        bean.startUpload(ORG, "favicon");
        Assert.assertEquals(bean.getDialogHeader(), "Favicon");
        Assert.assertEquals(bean.getCropAspect(), "1.0", "a tab icon is square");
        Assert.assertEquals(bean.getRoleKey(), "favicon");

        bean.startUpload(ORG, "ogImage");
        Assert.assertEquals(bean.getCropAspect(), String.valueOf(1200d / 630d),
                "a link preview is Open Graph's 1.91:1, which is why the dialog takes a RATIO now");

        bean.startUpload(ORG, "logo");
        Assert.assertEquals(bean.getCropAspect(), "", "a logo is rarely square, so it crops free");
        bean.startUpload(ORG, "background");
        Assert.assertEquals(bean.getCropAspect(), "");
    }

    @Test
    public void anUploadIsParkedForTheOpenRoleAndCancellingDropsIt() {
        Assert.assertFalse(bean.isUploadPending(), "the dialog opens on its chooser");
        upload("logo", 1400, 1000);
        Assert.assertTrue(bean.isUploadPending(), "which is what swaps it to the cropper");

        bean.cancelUpload();
        Assert.assertFalse(bean.isUploadPending());
        Assert.assertNull(session.get(BrandingUploadCommands.TOKEN_KEY),
                "abandoned bytes go now, not in fifteen minutes");

        // Re-opening the dialog for another role also drops whatever the last one left parked.
        upload("logo", 400, 300);
        bean.startUpload(ORG, "favicon");
        Assert.assertFalse(bean.isUploadPending());
    }

    @Test
    public void aTokenMintedForOneRoleCannotBeSpentOnAnother() {
        upload("logo", 1400, 1000);
        session.put(BrandingUploadCommands.ROLE_KEY, "favicon");

        Assert.assertFalse(bean.applyCrop(null), "the parked upload was for the logo, and says so");
        Assert.assertTrue(photos.history(ORG, "favicon").isEmpty());
        Assert.assertEquals(previewed(FAVICON), "", "and nothing reached the page's unsaved values");
    }

    // --- what each role actually stores, and where the URL lands ---

    @Test
    public void aConfirmedLogoIsStoredAsAPngAndLandsInThePagesUnsavedPreview() {
        upload("logo", 1400, 1000);
        viewMap.put("photoCrop", new CroppedImage(null, null, 100, 50, 400, 200));

        bean.confirmCrop();

        Assert.assertEquals(outcomes, List.of(true), "the dialog closes only on a true cropStored");
        final List<BrandingPhotos.Version> stored = photos.history(ORG, "logo");
        Assert.assertEquals(stored.size(), 1);
        Assert.assertTrue(stored.getFirst().key().endsWith(".png"),
                "PNG, so a transparent logo keeps its transparency: " + stored.getFirst().key());
        Assert.assertEquals(previewed(LOGO), stored.getFirst().url(),
                "the URL goes into the page's PREVIEW, not into a saved row: Save is still the page's");
        Assert.assertEquals(published.get("brandUrl"), brand.appearanceUrl(ORG),
                "and the dialog's JS goes back to the page, which re-renders with it applied");
        Assert.assertFalse(bean.isUploadPending(), "the parked bytes are consumed on success");
    }

    @Test
    public void aFaviconIsStoredSquareAndAnOgImageAtTheLinkPreviewShape() {
        upload("favicon", 900, 900);
        bean.confirmFullPhoto();
        Assert.assertEquals(outcomes, List.of(true));
        Assert.assertTrue(photos.history(ORG, "favicon").getFirst().key().endsWith(".png"),
                "browsers accept PNG for rel=icon, and this codebase writes neither ICO nor SVG");
        Assert.assertEquals(previewed(FAVICON), photos.history(ORG, "favicon").getFirst().url());

        upload("ogImage", 2000, 1500);
        bean.confirmFullPhoto();
        Assert.assertTrue(photos.history(ORG, "ogImage").getFirst().key().endsWith(".jpg"));
        Assert.assertEquals(previewed(OG_IMAGE), photos.history(ORG, "ogImage").getFirst().url());
        Assert.assertEquals(previewed(FAVICON), photos.history(ORG, "favicon").getFirst().url(),
                "one upload never disturbs another field's unsaved value");
    }

    @Test
    public void abackgroundUploadAlsoMovesTheChooserToImageOrThePageWouldHideWhatItJustSet() {
        upload("background", 2400, 1200);
        bean.confirmFullPhoto();

        Assert.assertEquals(outcomes, List.of(true));
        Assert.assertEquals(previewed(BACKGROUND), photos.history(ORG, "background").getFirst().url());
        Assert.assertEquals(brand.appearanceEdit(ORG).get(BrandCommands.BG_MODE_KEY),
                BrandCommands.BG_MODE_IMAGE,
                "the three-way chooser has to follow, or the page comes back showing a colour");
    }

    @Test
    public void anUploadExtendsTheEditsAlreadyInFlightRatherThanRevertingThem() {
        brand.preview(ORG, Map.of(KnownSettings.SITE_THEME_PALETTE.getName(), "purple"), null);
        upload("logo", 1400, 1000);
        bean.confirmFullPhoto();

        Assert.assertEquals(brand.appearanceEdit(ORG).get(KnownSettings.SITE_THEME_PALETTE.getName()),
                "purple", "the dialog's forms carry no orgId parameter, so the preview is read BY ORG");
        Assert.assertEquals(previewed(LOGO), photos.history(ORG, "logo").getFirst().url());
    }

    @Test
    public void anAreaSmallerThanTheRecommendationIsAWarningAndNotARefusal() {
        upload("logo", 300, 200);
        bean.confirmFullPhoto();
        Assert.assertEquals(outcomes, List.of(true),
                "the person holding the only copy of their own logo decides, exactly as for badge images");
        Assert.assertEquals(photos.history(ORG, "logo").size(), 1);
    }

    // --- refusals ---

    @Test
    public void onlyAnAdministratorOfTheOrganisationMayOpenTheDialogUploadOrConfirm() {
        manageable = false;
        bean.startUpload(ORG, "logo");
        Assert.assertNull(session.get(BrandingUploadCommands.ROLE_KEY), "the dialog is not armed at all");
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(800, 600)));
        Assert.assertFalse(bean.isUploadPending());

        // And an upload parked while still an administrator is refused at confirm time too.
        manageable = true;
        upload("logo", 800, 600);
        manageable = false;
        Assert.assertFalse(bean.applyCrop(null), "authorization is per call, never inherited from the dialog");
        Assert.assertTrue(photos.history("any", "logo").isEmpty());
    }

    @Test
    public void anUnknownRoleIsRefusedRatherThanGuessed() {
        bean.startUpload(ORG, "mascot");
        Assert.assertNull(session.get(BrandingUploadCommands.ROLE_KEY));
        bean.handleUpload(uploadOf(PhotoFixtures.jpeg(800, 600)));
        Assert.assertFalse(bean.isUploadPending(), "with no role open there is nothing to upload FOR");
        bean.confirmCrop();
        Assert.assertEquals(outcomes, List.of(false));
    }

    @Test
    public void anEmptyAnOversizedAndAnUndecodableUploadAreEachRefusedWithTheirOwnReason() {
        bean.startUpload(ORG, "logo");

        final UploadedFile empty = Mockito.mock(UploadedFile.class);
        Mockito.when(empty.getSize()).thenReturn(0L);
        bean.handleUpload(new FileUploadEvent(Mockito.mock(UIComponent.class), empty, 1));
        Assert.assertFalse(bean.isUploadPending(), "nothing was included");

        final UploadedFile huge = Mockito.mock(UploadedFile.class);
        Mockito.when(huge.getSize()).thenReturn(BrandingUploadCommands.MAX_UPLOAD_BYTES + 1);
        bean.handleUpload(new FileUploadEvent(Mockito.mock(UIComponent.class), huge, 1));
        Assert.assertFalse(bean.isUploadPending(), "the belt-and-braces size gate");

        bean.handleUpload(uploadOf("this is not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Assert.assertFalse(bean.isUploadPending(), "and unsupported bytes get the sniffer's own message");
    }

    @Test
    public void confirmingWithNothingParkedSaysSoInsteadOfStoringAnything() {
        bean.startUpload(ORG, "logo");
        bean.confirmCrop();
        Assert.assertEquals(outcomes, List.of(false));
        Assert.assertTrue(photos.history(ORG, "logo").isEmpty());
    }

    @Test
    public void aStoreThatRefusesTheBytesLeavesTheDialogOpenAndTheSettingAlone() throws Exception {
        final BrandingPhotos refusing = new BrandingPhotos() {
            @Override
            public String store(final String orgId, final String roleKey, final byte[] bytes,
                    final String contentType, final String extension) {
                return null;
            }
        };
        set(BrandingUploadCommands.class, bean, "brandingPhotos", refusing);
        upload("logo", 800, 600);

        Assert.assertFalse(bean.applyCrop(null));
        Assert.assertEquals(previewed(LOGO), "", "a failed store must not point the site at nothing");
        Assert.assertTrue(bean.isUploadPending(), "and the parked bytes stay, so Save can be retried");
    }

    // --- recovery ---

    @Test
    public void aPreviousVersionCanBePutBackAndOnlyByAnAdministratorOfItsOwnOrganisation() {
        upload("logo", 1400, 1000);
        bean.confirmFullPhoto();
        final String first = photos.history(ORG, "logo").getFirst().key();
        upload("logo", 1200, 900);
        bean.confirmFullPhoto();
        final String second = photos.history(ORG, "logo").getFirst().key();

        Assert.assertNotEquals(second, first, "a replacement is a new key, so the old one still resolves");
        Assert.assertEquals(previewed(LOGO), photos.urlFor(second), "the newest is what the site shows");

        Assert.assertTrue(bean.useVersion(ORG, first), "and the one it replaced can be put back");
        Assert.assertEquals(previewed(LOGO), photos.urlFor(first),
                "through the same preview, so Save is still what commits it");
        Assert.assertEquals(published.get("brandUrl"), brand.appearanceUrl(ORG));

        Assert.assertFalse(bean.useVersion("org-somebody-else", first),
                "the key carries its own organization, so a foreign one simply does not match");
        Assert.assertFalse(bean.useVersion(ORG, "org/" + ORG + "/media/brochure.pdf"),
                "nor does anything that is not a branding key");
        Assert.assertFalse(bean.useVersion(null, first));
        manageable = false;
        Assert.assertFalse(bean.useVersion(ORG, first), "and a non-administrator is refused outright");
        Assert.assertEquals(previewed(LOGO), photos.urlFor(first), "with the setting left as it was");
    }

    @Test
    public void thePagesHistoryListIsTheStoresOwnAnswer() {
        upload("logo", 800, 600);
        bean.confirmFullPhoto();
        Assert.assertEquals(bean.history(ORG, "logo"), photos.history(ORG, "logo"));
        Assert.assertTrue(bean.history(ORG, "favicon").isEmpty());
    }

    // --- the Faces seams themselves, which the tests above deliberately replace ---

    @Test
    public void theFacesSeamsAreSafeWithNoFacesContextAtAll() {
        final BrandingUploadCommands real = new BrandingUploadCommands();
        Assert.assertEquals(real.contextPath(), "", "no context path outside a request");
        real.sessionPut("k", "v");
        real.sessionPut("k", null);
        real.publishParam("x", true);
        real.publishOutcome(true);
        Assert.assertNull(real.sessionGet("k"), "ScopeUtil answers null rather than throwing");
        Assert.assertNull(real.viewMap("photoCrop"));
        Assert.assertNotNull(real.caller(), "and an unauthenticated caller is still a caller");
        Assert.assertNull(FacesContext.getCurrentInstance(), "nothing here creates one");
    }
}
