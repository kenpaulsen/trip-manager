package org.paulsens.trip.action;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.model.MediaItem;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * End-to-end check of managed media against the REAL bucket and CDN: upload, fetch the public URL, delete.
 *
 * <p>This exists because everything else about media is tested with no bucket configured, which proves only
 * that uploads are correctly refused. The path that actually matters -- bytes reach S3, CloudFront can read
 * them through Origin Access Control, the DNS name and certificate resolve, and the cache header survives --
 * involves five services and cannot be verified by reasoning about it.
 *
 * <p>Skipped unless a bucket is configured, following {@code ValkeyCacheClientIntegrationTest}: the default
 * suite must keep running with no network and no credentials. To run it:
 * <pre>
 *     env TRIP_MEDIA_BUCKET=trip-manager-media-762393489277 \
 *         TRIP_MEDIA_BASE_URL=https://files.visitqueenofpeace.com \
 *         AWS_PROFILE=cdk-deploy \
 *         mvn test -pl trip -Dtest=MediaRoundTripIntegrationTest
 * </pre>
 *
 * <p>Writes only under a {@code _ittest/} prefix and deletes what it creates, so it is safe against the live
 * bucket. The DynamoDB side runs on fake persistence as usual -- this is about S3 and CloudFront.
 */
public class MediaRoundTripIntegrationTest {

    private static final String PREFIX = "_ittest/";
    private final MediaCommands media = new MediaCommands();
    private String key;
    private String uploadedId;

    @BeforeClass
    public void requireBucket() {
        if (!media.isUploadEnabled()) {
            throw new SkipException("No " + MediaCommands.BUCKET_VAR
                    + " configured; skipping media round-trip integration test.");
        }
    }

    @AfterClass(alwaysRun = true)
    public void cleanUp() {
        if (uploadedId != null) {
            media.delete(uploadedId, "ittest");
        }
    }

    @Test
    public void uploadThenFetchThenDelete() throws Exception {
        final String body = "trip-manager media round trip " + UUID.randomUUID();
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        key = PREFIX + UUID.randomUUID() + ".txt";

        assertTrue(media.upload(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain",
                        "Integration test file", "Written by MediaRoundTripIntegrationTest", null, 0, "ittest"),
                "upload should succeed");

        // The row must exist, or the file is stored but invisible to the site.
        final List<MediaItem> all = media.getAll();
        final MediaItem stored = all.stream().filter(i -> key.equals(i.getS3Key())).findFirst().orElse(null);
        assertNotNull(stored, "uploaded file should have a media row");
        assertEquals(stored.getSize(), bytes.length);
        uploadedId = stored.getId();

        // The real proof: fetch the PUBLIC url. This exercises S3 -> OAC -> CloudFront -> DNS -> certificate,
        // none of which the upload itself touches.
        final String url = media.getUrl(stored);
        assertTrue(url.endsWith(key), "url should end with the object key: " + url);
        final HttpResponse<String> got = fetch(url);
        assertEquals(got.statusCode(), 200, "fetching " + url + " should succeed");
        assertEquals(got.body(), body, "served bytes should match what was uploaded");
        assertTrue(got.headers().firstValue("cache-control").orElse("").contains("max-age="),
                "a Cache-Control header must survive to the client, or the CDN buys us nothing");

        // Delete removes it from the site; the object goes too (S3 versioning still holds a copy).
        assertTrue(media.delete(uploadedId, "ittest"), "delete should succeed");
        uploadedId = null;
        assertFalse(media.getAll().stream().anyMatch(i -> key.equals(i.getS3Key())),
                "deleted file should no longer be listed");
    }

    private static HttpResponse<String> fetch(final String url) throws Exception {
        final HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
