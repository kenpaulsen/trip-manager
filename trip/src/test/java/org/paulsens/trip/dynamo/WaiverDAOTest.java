package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.NoopCacheClient;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.WaiverAcceptance;
import org.paulsens.trip.model.WaiverDocument;
import org.paulsens.trip.model.WaiverVersion;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/** The two waiver tables through the DAO facade against the in-memory fake ({@code docs/waivers.md}). */
public class WaiverDAOTest {

    @Test
    public void documentsArePartitionedByOrg() throws IOException {
        final String org = "org-" + RandomData.genAlpha(8);
        final WaiverDocument doc = document(org, "B title");
        final WaiverDocument first = document(org, "a title");
        assertTrue(DAO.getInstance().saveWaiverDocument(doc));
        assertTrue(DAO.getInstance().saveWaiverDocument(first));
        assertEquals(DAO.getInstance().getWaiverDocuments(org, Cached.NO).stream().map(WaiverDocument::getTitle)
                .toList(), List.of("a title", "B title"), "case-insensitive title order");
        assertEquals(DAO.getInstance().getWaiverDocument(org, doc.getId(), Cached.YES).orElseThrow(), doc);
        assertTrue(DAO.getInstance().getWaiverDocument("other-org", doc.getId(), Cached.NO).isEmpty(),
                "another org's id simply misses");
        assertTrue(DAO.getInstance().getWaiverDocuments(null, Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getWaiverDocuments(" ", Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getWaiverDocument(org, null, Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getWaiverDocument(null, doc.getId(), Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getWaiverDocument(org, " ", Cached.NO).isEmpty());
        assertEquals(DAO.getInstance().invalidate(DAO.CacheScope.WAIVER), List.of(CacheKeys.WAIVER_PREFIX));
    }

    @Test
    public void anOversizedDocumentIsRefusedBeforeTheStore() {
        final WaiverDocument huge = document("org-" + RandomData.genAlpha(8), "Huge");
        huge.getVersions().add(WaiverVersion.builder().number(2).bodyHtml("x".repeat(400_000)).build());
        assertThrows(IllegalArgumentException.class, () -> DAO.getInstance().saveWaiverDocument(huge));
        assertEquals(huge.getVersion(), 0L);
    }

    @Test
    public void aRefusedPutRestoresTheVersion() throws IOException {
        final Persistence refusing = Mockito.mock(Persistence.class);
        final PutItemResponse failed = (PutItemResponse) PutItemResponse.builder()
                .sdkHttpResponse(SdkHttpResponse.builder().statusCode(500).build()).build();
        Mockito.when(refusing.putItem(ArgumentMatchers.any())).thenReturn(failed);
        final WaiverDAO dao = new WaiverDAO(DAO.getInstance().getMapper(), refusing, new NoopCacheClient());
        final WaiverDocument doc = document("org-x", "T");
        assertFalse(dao.saveDocument(doc));
        assertEquals(doc.getVersion(), 0L);
    }

    @Test
    public void acceptancesAreWriteOnce() throws IOException {
        final String trip = "trip-" + RandomData.genAlpha(8);
        final Person.Id kid = Person.Id.newInstance();
        final Person.Id other = Person.Id.newInstance();
        final WaiverAcceptance row = acceptance(trip, kid, 1, "first");
        assertTrue(DAO.getInstance().saveWaiverAcceptance(row));
        assertFalse(DAO.getInstance().saveWaiverAcceptance(acceptance(trip, kid, 1, "second")),
                "the same traveler, document and version is never overwritten");
        assertTrue(DAO.getInstance().saveWaiverAcceptance(acceptance(trip, kid, 2, "v2")));
        assertTrue(DAO.getInstance().saveWaiverAcceptance(acceptance(trip, other, 1, "other")));
        assertEquals(DAO.getInstance().getWaiverAcceptances(trip, kid).stream().map(WaiverAcceptance::getSignerName)
                .toList(), List.of("first", "v2"));
        assertEquals(DAO.getInstance().getWaiverAcceptances(trip).size(), 3);
        assertTrue(DAO.getInstance().getWaiverAcceptances(trip, null).isEmpty());
        assertTrue(DAO.getInstance().getWaiverAcceptances(null).isEmpty());
        assertTrue(DAO.getInstance().getWaiverAcceptances(" ").isEmpty());
    }

    @Test
    public void corruptRowsReadAsAbsent() {
        final InMemoryPersistence persistence = new InMemoryPersistence();
        final WaiverDAO dao = new WaiverDAO(DAO.getInstance().getMapper(), persistence, new InMemoryCacheClient());
        persistence.putItem(b -> b.tableName(WaiverDAO.WAIVERS_TABLE).item(Map.of(
                WaiverDAO.ORG_ID, AttributeValue.builder().s("org-c").build(),
                WaiverDAO.ID, AttributeValue.builder().s("bad").build(),
                "content", AttributeValue.builder().s("{not json").build())));
        persistence.putItem(b -> b.tableName(WaiverDAO.WAIVERS_TABLE).item(Map.of(
                WaiverDAO.ORG_ID, AttributeValue.builder().s("org-c").build(),
                WaiverDAO.ID, AttributeValue.builder().s("none").build())));
        persistence.putItem(b -> b.tableName(WaiverDAO.ACCEPTANCES_TABLE).item(Map.of(
                WaiverDAO.TRIP_ID, AttributeValue.builder().s("trip-c").build(),
                WaiverDAO.SK, AttributeValue.builder().s("x").build(),
                "content", AttributeValue.builder().s("{not json").build())));
        assertTrue(dao.getDocuments("org-c").isEmpty());
        assertTrue(dao.getAcceptances("trip-c").isEmpty());
    }

    private static WaiverDocument document(final String org, final String title) {
        return WaiverDocument.builder().orgId(org).title(title)
                .versions(List.of(WaiverVersion.builder().number(1).title(title).bodyHtml("<p>x</p>").build()))
                .build();
    }

    private static WaiverAcceptance acceptance(final String trip, final Person.Id who, final int version,
            final String signer) {
        return WaiverAcceptance.builder().tripId(trip).personId(who).waiverId("w").version(version)
                .signerId(who).signerName(signer).acceptedAt(Instant.now()).build();
    }
}
