package org.paulsens.trip.action;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.ExpenseReceipts.Outcome;
import org.paulsens.trip.action.ExpenseReceipts.Served;
import org.paulsens.trip.action.ExpenseViews.ReceiptRow;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripExpenseReport;
import org.primefaces.event.FileUploadEvent;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Receipts end to end against the in-memory store and the static local object store: what is accepted and how
 * it is stored, the links the report keeps, the rollbacks, and the bytes the servlet will stream. One mocked
 * media bean stands in for S3 where the remote branch matters.
 */
public class ExpenseReceiptsTest {
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    private final ExpenseReceipts receipts = new ExpenseReceipts(TestCallers::siteAdmin);
    private final ExpenseCommands expenses = new ExpenseCommands(TestCallers::siteAdmin);

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    private String newTrip() throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Receipts")
                .startDate(LocalDateTime.of(2026, 9, 20, 8, 0)).people(new ArrayList<>()).build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip.getId();
    }

    private String newLine(final String tripId) {
        assertTrue(expenses.saveLine(tripId, ExpenseCommandsTest.line(null, null, "Guide", "USD", 1_440.0)));
        final TripExpenseReport report = report(tripId);
        return report.getLines().get(report.getLines().size() - 1).getId();
    }

    private static TripExpenseReport report(final String tripId) {
        return DAO.getInstance().getTripExpenseReport(tripId, Cached.NO).orElseThrow();
    }

    private static MediaItem row(final String mediaId) {
        return DAO.getInstance().getMedia(mediaId, Cached.NO).orElse(null);
    }

    @Test
    public void aLargePhotoIsStoredTwiceAndLinkedToItsLine() throws IOException {
        final String tripId = newTrip();
        final String lineId = newLine(tripId);
        final Outcome outcome = receipts.store(tripId, lineId, PhotoFixtures.jpeg(1200, 900),
                "C:\\scans\\van.jpg");
        assertTrue(outcome.ok(), outcome.problem());
        final MediaItem item = row(outcome.mediaId());
        assertNotNull(item);
        assertEquals(item.getSlot(), ExpenseReceipts.slotFor(tripId));
        assertEquals(item.getTitle(), "van.jpg", "The base name only");
        assertEquals(item.getContentType(), "image/jpeg");
        assertTrue(item.getHidden(), "Hidden: never on a public page");
        assertEquals(item.getOrgId(), FakeData.CFPW_ORG_ID);
        assertTrue(item.getS3Key().startsWith("receipts/" + tripId + "/"));
        assertNotNull(item.getSmallKey(), "Wider than 800px: a thumbnail too");
        final BufferedImage stored = ImageIO.read(new ByteArrayInputStream(receipts.served(item, false)
                .orElseThrow().bytes()));
        assertEquals(stored.getWidth(), 1200, "Within the 1280 cap: stored at its own size");
        assertEquals(report(tripId).line(lineId).getReceiptIds(), List.of(item.getId()));

        final Served full = receipts.served(item, false).orElseThrow();
        assertEquals(full.contentType(), "image/jpeg");
        final Served thumb = receipts.served(item, true).orElseThrow();
        assertTrue(thumb.bytes().length < full.bytes().length);

        final ReceiptRow listed = expenses.receiptRowsFor(tripId, lineId).get(0);
        assertEquals(listed.getUrl(), "/expense-receipts/" + item.getId());
        assertEquals(listed.getThumbUrl(), "/expense-receipts/" + item.getId() + "/thumb");
        assertFalse(listed.isPdf());
        assertFalse(listed.getUrl().endsWith(".pdf"));
    }

    @Test
    public void aLargePhotoIsScaledToTheCapWithoutCropping() throws IOException {
        final String tripId = newTrip();
        final Outcome outcome = receipts.store(tripId, null, PhotoFixtures.jpeg(4032, 3024), "phone.jpg");
        assertTrue(outcome.ok(), outcome.problem());
        final MediaItem item = row(outcome.mediaId());
        final BufferedImage stored = ImageIO.read(new ByteArrayInputStream(receipts.served(item, false)
                .orElseThrow().bytes()));
        assertEquals(stored.getWidth(), ExpenseReceipts.MAX_IMAGE_SIDE);
        assertEquals(stored.getHeight(), 960, "4032x3024 is 4:3, so 1280x960");
        assertEquals(item.getSize(), receipts.served(item, false).orElseThrow().bytes().length,
                "The row records the stored size, not the upload's");
    }

    @Test
    public void aPdfIsOneObjectAtReportLevel() throws IOException {
        final String tripId = newTrip();
        final Outcome outcome = receipts.store(tripId, null, PDF, "advance.pdf");
        assertTrue(outcome.ok(), outcome.problem());
        final MediaItem item = row(outcome.mediaId());
        assertEquals(item.getContentType(), "application/pdf");
        assertNull(item.getSmallKey());
        assertTrue(item.getS3Key().endsWith(".pdf"));
        assertEquals(report(tripId).getReceiptIds(), List.of(item.getId()));
        assertEquals(receipts.served(item, true).orElseThrow().contentType(), "application/pdf",
                "A PDF's thumb is itself");
        final ReceiptRow listed = expenses.reportPage(tripId).getReportReceipts().get(0);
        assertTrue(listed.isPdf());
        assertNull(listed.getThumbUrl());
        assertEquals(listed.getTitle(), "advance.pdf");
    }

    @Test
    public void aSmallImageNeedsNoThumbnail() throws IOException {
        final String tripId = newTrip();
        final Outcome outcome = receipts.store(tripId, "", PhotoFixtures.png(300, 200, false), null);
        assertTrue(outcome.ok(), outcome.problem());
        final MediaItem item = row(outcome.mediaId());
        assertNull(item.getSmallKey());
        assertEquals(item.getTitle(), "Receipt");
        assertEquals(receipts.served(item, true).orElseThrow().contentType(), "image/png");
    }

    @Test
    public void whatIsNotAReceiptIsRefused() throws IOException {
        final String tripId = newTrip();
        assertEquals(receipts.store(tripId, null, null, "x").problem(), "No file was received.");
        assertEquals(receipts.store(tripId, null, new byte[0], "x").problem(), "No file was received.");
        final byte[] text = "just some text, no magic".getBytes(StandardCharsets.UTF_8);
        assertEquals(receipts.store(tripId, null, text, "a.txt").problem(),
                "That file is not a receipt this site can keep: use a JPG, PNG, GIF, HEIC or PDF.");
        assertEquals(receipts.store(tripId, null, new byte[(int) ExpenseReceipts.MAX_BYTES + 1], "big").problem(),
                "A receipt is at most 16 MB.");
        final byte[] broken = PhotoFixtures.jpeg(10, 10);
        final byte[] truncated = Arrays.copyOf(broken, 20);
        assertFalse(receipts.store(tripId, null, truncated, "bad.jpg").ok(), "A broken image is the processor's no");
        final ExpenseReceipts stranger = new ExpenseReceipts(() -> TestCallers.person(Person.Id.newInstance()));
        assertTrue(stranger.store(tripId, null, PDF, "x.pdf").problem().startsWith("Not allowed"));
        assertTrue(DAO.getInstance().getMediaInSlot(ExpenseReceipts.slotFor(tripId), Cached.NO).isEmpty());
    }

    @Test
    public void aRefusedLinkLeavesNoFileOrRow() throws IOException {
        final String tripId = newTrip();
        final Outcome outcome = receipts.store(tripId, "no-such-line", PDF, "x.pdf");
        assertFalse(outcome.ok());
        assertEquals(outcome.problem(), "This expense no longer exists: reload the page.");
        assertTrue(DAO.getInstance().getMediaInSlot(ExpenseReceipts.slotFor(tripId), Cached.NO).isEmpty());

        final ExpenseReceipts rowFails = new ExpenseReceipts(TestCallers::siteAdmin) {
            @Override
            protected boolean saveRow(final MediaItem item) {
                return false;
            }
        };
        assertEquals(rowFails.store(tripId, null, PDF, "x.pdf").problem(),
                "The receipt could not be stored. Try again.");
    }

    @Test
    public void deletingUnlinksAndRemoves() throws IOException {
        final String tripId = newTrip();
        final String lineId = newLine(tripId);
        final String onLine = receipts.store(tripId, lineId, PDF, "a.pdf").mediaId();
        final String onReport = receipts.store(tripId, null, PDF, "b.pdf").mediaId();
        final MediaItem lineItem = row(onLine);

        assertEquals(receipts.remove(newTrip(), onLine), "That receipt is not on this report.", "Another trip's id");
        assertEquals(receipts.remove(tripId, null), "That receipt is not on this report.");
        final ExpenseReceipts viewer = new ExpenseReceipts(() -> TestCallers.person(Person.Id.newInstance()));
        assertTrue(viewer.remove(tripId, onLine).startsWith("Not allowed"));

        assertTrue(receipts.deleteReceipt(tripId, onReport));
        assertNull(row(onReport));
        assertTrue(report(tripId).getReceiptIds().isEmpty());
        assertFalse(receipts.deleteReceipt(tripId, onReport), "Already gone");

        // Deleting the expense deletes its receipts' files and rows too.
        assertTrue(expenses.deleteLine(tripId, lineId));
        assertNull(row(onLine));
        assertTrue(receipts.served(lineItem, false).isEmpty(), "The bytes went with it");
    }

    @Test
    public void aRowTheReportDoesNotHoldIsNotDetached() throws IOException {
        final String tripId = newTrip();
        newLine(tripId);
        final MediaItem orphan = new MediaItem(UUID.randomUUID().toString(), "receipts/" + tripId + "/o.pdf", "o",
                null, ExpenseReceipts.PDF, 3, ExpenseReceipts.slotFor(tripId), 0, LocalDateTime.now(), null, null,
                Boolean.TRUE, null);
        assertTrue(DAO.getInstance().saveMedia(orphan));
        assertEquals(receipts.remove(tripId, orphan.getId()), "That receipt is not on this report.");
        receipts.purge(List.of(orphan.getId(), "unknown"));
        assertNull(row(orphan.getId()));
        final MediaItem library = new MediaItem(UUID.randomUUID().toString(), "downloads/x.pdf", "x", null,
                ExpenseReceipts.PDF, 3, "home-docs", 0, LocalDateTime.now(), null, null, null, null);
        assertTrue(DAO.getInstance().saveMedia(library));
        receipts.purge(List.of(library.getId()));
        assertNotNull(row(library.getId()), "Only receipts are purged");
        assertTrue(DAO.getInstance().deleteMedia(library.getId()));
    }

    @Test
    public void theRemoteStoreIsUsedWhenThereIsABucket() throws IOException {
        final String tripId = newTrip();
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean())).thenReturn(true);
        Mockito.when(media.getObject(ArgumentMatchers.anyString())).thenReturn(Optional.of(PDF));
        final ExpenseReceipts remote = new ExpenseReceipts(TestCallers::siteAdmin);
        remote.setMediaForTest(media);
        final Outcome outcome = remote.store(tripId, null, PDF, "r.pdf");
        assertTrue(outcome.ok(), outcome.problem());
        final MediaItem item = row(outcome.mediaId());
        Mockito.verify(media).putObject(item.getS3Key(), PDF, ExpenseReceipts.PDF, ExpenseReceipts.CACHE_SECONDS,
                false);
        assertEquals(remote.served(item, false).orElseThrow().bytes(), PDF);
        assertNull(remote.remove(tripId, item.getId()));
        Mockito.verify(media).deleteObject(item.getS3Key());

        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean())).thenReturn(false);
        assertEquals(remote.store(tripId, null, PDF, "r.pdf").problem(),
                "The receipt could not be stored. Try again.");
    }

    @Test
    public void theUploadListenerRefusesWithoutAView() {
        final FileUploadEvent event = Mockito.mock(FileUploadEvent.class);
        receipts.handleUpload(event); // No FacesContext: no trip in the view, so the gate says no (a growl).
        Mockito.verify(event, Mockito.atLeastOnce()).getFile();
    }

    @Test
    public void helpers() {
        assertEquals(ExpenseReceipts.tripOfSlot("receipts-t1"), "t1");
        assertNull(ExpenseReceipts.tripOfSlot("tripChat-t1"));
        assertFalse(ExpenseReceipts.isReceiptSlot(null));
        assertEquals(ExpenseReceipts.path("m1"), "/expense-receipts/m1");
        assertEquals(ExpenseReceipts.title("  /tmp/a b.png "), "a b.png");
        assertEquals(ExpenseReceipts.title("dir/"), "Receipt");
        assertEquals(ExpenseReceipts.title("x".repeat(200)).length(), 120);
        assertEquals(ExpenseReceipts.typeOf("a/b.GIF"), "image/gif");
        assertEquals(ExpenseReceipts.typeOf("a/b.jpg"), "image/jpeg");
        assertTrue(ExpenseReceipts.isPdf(PDF));
        assertFalse(ExpenseReceipts.isPdf(new byte[] {'%'}));
        assertFalse(ExpenseReceipts.isPdf(null));
        assertTrue(new Outcome("m", null).ok());
        ExpenseReceipts.localStoreMaxBytesForTest(ExpenseReceipts.LOCAL_STORE_MAX_BYTES);
    }

    // ================================================================== rotate

    private static BufferedImage image(final Served served) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(served.bytes()));
    }

    @Test
    public void rotatingTurnsBothRenditionsInPlaceAndBustsTheUrls() throws IOException {
        final String tripId = newTrip();
        final String lineId = newLine(tripId);
        final String id = receipts.store(tripId, lineId, PhotoFixtures.jpeg(1200, 900), "van.jpg").mediaId();
        final MediaItem before = row(id);

        final ExpenseReceipts.RotateOutcome outcome = receipts.rotate(tripId, id,
                org.paulsens.trip.media.QuarterTurn.RIGHT);

        assertTrue(outcome.ok(), outcome.problem());
        final MediaItem after = row(id);
        assertEquals(after, outcome.item());
        assertEquals(after.getRev(), 1);
        assertEquals(after.getS3Key(), before.getS3Key(), "overwritten in place");
        assertEquals(after.getSmallKey(), before.getSmallKey());
        final BufferedImage full = image(receipts.served(after, false).orElseThrow());
        assertEquals(full.getWidth(), 900);
        assertEquals(full.getHeight(), 1200);
        assertEquals(image(receipts.served(after, true).orElseThrow()).getWidth(), 800);
        final ReceiptRow listed = expenses.receiptRowsFor(tripId, lineId).get(0);
        assertEquals(listed.getUrl(), "/expense-receipts/" + id + "?r=1");
        assertEquals(listed.getThumbUrl(), "/expense-receipts/" + id + "/thumb?r=1");
        assertTrue(receipts.rotateReceipt(tripId, id, "left"));
        assertEquals(row(id).getRev(), 2);
    }

    @Test
    public void aTurnThatNowNeedsAThumbnailMintsItsKeyAndOneThatDoesNotDropsIt() throws IOException {
        final String tripId = newTrip();
        final String id = receipts.store(tripId, null, PhotoFixtures.jpeg(600, 1000), "tall.jpg").mediaId();
        assertNull(row(id).getSmallKey(), "test setup: narrow enough for one object");

        assertTrue(receipts.rotate(tripId, id, org.paulsens.trip.media.QuarterTurn.LEFT).ok());
        final MediaItem wide = row(id);
        assertEquals(wide.getSmallKey(), "receipts/" + tripId + "/" + id + "-small.jpg");
        assertEquals(image(receipts.served(wide, true).orElseThrow()).getWidth(), 800);

        assertTrue(receipts.rotate(tripId, id, org.paulsens.trip.media.QuarterTurn.RIGHT).ok());
        final MediaItem back = row(id);
        assertNull(back.getSmallKey());
        assertEquals(back.getRev(), 2);
        assertTrue(receipts.served(wide, true).isEmpty(), "the dropped thumbnail object is deleted");
        assertEquals(image(receipts.served(back, true).orElseThrow()).getWidth(), 600);
    }

    @Test
    public void rotationRefusesPdfsStrangersOtherTripsAndBadDirections() throws IOException {
        final String tripId = newTrip();
        final String pdf = receipts.store(tripId, null, PDF, "a.pdf").mediaId();
        final String photo = receipts.store(tripId, null, PhotoFixtures.jpeg(300, 200), "p.jpg").mediaId();
        final org.paulsens.trip.media.QuarterTurn left = org.paulsens.trip.media.QuarterTurn.LEFT;

        assertEquals(receipts.rotate(tripId, pdf, left).code(), ExpenseReceipts.RotateOutcome.PDF);
        assertEquals(receipts.rotate(newTrip(), photo, left).code(), ExpenseReceipts.RotateOutcome.NOT_ON_REPORT);
        assertEquals(receipts.rotate(tripId, null, left).code(), ExpenseReceipts.RotateOutcome.NOT_ON_REPORT);
        final ExpenseReceipts viewer = new ExpenseReceipts(() -> TestCallers.person(Person.Id.newInstance()));
        assertEquals(viewer.rotate(tripId, photo, left).code(), ExpenseReceipts.RotateOutcome.NOT_ALLOWED);
        assertFalse(receipts.rotateReceipt(tripId, photo, "upside down"));
        assertFalse(receipts.rotateReceipt(tripId, pdf, "left"));
        assertEquals(row(photo).getRev(), 0, "nothing moved");
        assertNull(receipts.rotate(tripId, pdf, left).item());
    }

    @Test
    public void aReceiptAlreadyBeingRotatedIsBusyUntilReleased() throws IOException {
        final String tripId = newTrip();
        final String id = receipts.store(tripId, null, PhotoFixtures.jpeg(300, 200), "p.jpg").mediaId();
        assertTrue(org.paulsens.trip.media.RotationGuard.begin(id));
        try {
            assertEquals(receipts.rotate(tripId, id, org.paulsens.trip.media.QuarterTurn.LEFT).code(),
                    ExpenseReceipts.RotateOutcome.BUSY);
        } finally {
            org.paulsens.trip.media.RotationGuard.end(id);
        }
        assertTrue(receipts.rotate(tripId, id, org.paulsens.trip.media.QuarterTurn.LEFT).ok());
    }

    @Test
    public void remoteRotationReadsWritesAndReportsEachFailure() throws IOException {
        final String tripId = newTrip();
        final String id = receipts.store(tripId, null, PhotoFixtures.jpeg(300, 200), "p.jpg").mediaId();
        final MediaItem item = row(id);
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        final ExpenseReceipts remote = new ExpenseReceipts(TestCallers::siteAdmin);
        remote.setMediaForTest(media);
        final org.paulsens.trip.media.QuarterTurn left = org.paulsens.trip.media.QuarterTurn.LEFT;

        Mockito.when(media.getObject(item.getS3Key())).thenReturn(Optional.empty());
        assertEquals(remote.rotate(tripId, id, left).problem(), "The receipt could not be read. Try again.");

        Mockito.when(media.getObject(item.getS3Key())).thenReturn(Optional.of(new byte[] {1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13}));
        assertEquals(remote.rotate(tripId, id, left).code(), ExpenseReceipts.RotateOutcome.REJECTED);

        Mockito.when(media.getObject(item.getS3Key())).thenReturn(Optional.of(PhotoFixtures.jpeg(300, 200)));
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean())).thenReturn(false);
        assertEquals(remote.rotate(tripId, id, left).code(), ExpenseReceipts.RotateOutcome.STORE_FAILED);

        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean())).thenReturn(true);
        assertTrue(remote.rotate(tripId, id, left).ok());
        Mockito.verify(media, Mockito.times(2)).putObject(ArgumentMatchers.eq(item.getS3Key()), ArgumentMatchers.any(),
                ArgumentMatchers.eq("image/jpeg"), ArgumentMatchers.eq(ExpenseReceipts.CACHE_SECONDS),
                ArgumentMatchers.eq(false));
        Mockito.verify(media, Mockito.never()).invalidateCdn(ArgumentMatchers.anyList());
    }

    @Test
    public void aRowThatWillNotSaveIsAStoreFailure() throws IOException {
        final String tripId = newTrip();
        final String id = receipts.store(tripId, null, PhotoFixtures.jpeg(300, 200), "p.jpg").mediaId();
        final ExpenseReceipts failing = new ExpenseReceipts(TestCallers::siteAdmin) {
            @Override
            protected boolean saveRow(final MediaItem item) {
                return false;
            }
        };
        assertEquals(failing.rotate(tripId, id, org.paulsens.trip.media.QuarterTurn.LEFT).code(),
                ExpenseReceipts.RotateOutcome.STORE_FAILED);
        assertEquals(row(id).getRev(), 0);
    }
}
