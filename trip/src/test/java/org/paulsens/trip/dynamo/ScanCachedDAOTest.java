package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The whole-table-scan load path of the {@link org.paulsens.trip.cache.PartitionScanCache}-backed DAOs
 * ({@link PrivilegesDAO}, {@link ConfigDAO}, {@link MediaDAO}), against the real DynamoDB engine.
 *
 * <p>The ordinary DAO tests run with an {@link InMemoryCacheClient}, and
 * {@code CacheSupport.softRevalidateEnabled} deliberately turns the scan path OFF for that client (revalidating
 * an in-memory store against empty local data would wipe it). So the cold-cache scan -- the code production runs
 * on its first read after a deploy, and the self-heal for console edits -- was never executed by any test. These
 * tests hand the DAOs a cache the support check does not recognise as in-memory, which is exactly the production
 * configuration shape: a real cache in front of the real table.
 */
public class ScanCachedDAOTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
    }

    /** A cache that behaves like Valkey (shared, real) but is not an InMemoryCacheClient by type. */
    private static CacheClient valkeyLike() {
        return Mockito.mock(CacheClient.class, AdditionalAnswers.delegatesTo(new InMemoryCacheClient()));
    }

    // --- PrivilegesDAO ---

    @Test
    public void aColdPrivilegeListingIsBuiltByScanningTheTable() {
        // A trip-scoped id is base name + a CANONICAL-UUID trip id; anything else silently reads as global.
        final String tripId = java.util.UUID.randomUUID().toString();
        final Privilege scoped = new Privilege(Privilege.idFor("scanPriv", tripId), "scoped", List.of());
        final PrivilegesDAO writer = new PrivilegesDAO(mapper, DynamoLocal.persistence());
        Assert.assertTrue(writer.savePrivilege(scoped).join());

        // A different DAO with a cold (valkey-like) cache: its first listing must come from the SCAN.
        final PrivilegesDAO reader = new PrivilegesDAO(mapper, DynamoLocal.persistence(), valkeyLike());

        Assert.assertTrue(reader.getTripPrivileges(tripId).join().contains(scoped),
                "the cold cache must scan the table, not answer empty");
        Assert.assertTrue(reader.getPrivilege(scoped.getId()).join().isPresent(),
                "after the scan, a point lookup is served from the partition hash");
        reader.clearCache();
        Assert.assertTrue(reader.getTripPrivileges(tripId).join().contains(scoped),
                "an invalidated cache rebuilds by scanning again");
    }

    /** A row the console mangled must vanish from the listing, never appear as a null element or kill it. */
    @Test
    public void anUnparseablePrivilegeRowIsSkippedByTheScan() {
        final String tripId = java.util.UUID.randomUUID().toString();
        final Persistence persistence = DynamoLocal.persistence();
        final PrivilegesDAO writer = new PrivilegesDAO(mapper, persistence);
        Assert.assertTrue(writer.savePrivilege(
                new Privilege(Privilege.idFor("goodPriv", tripId), "fine", List.of())).join());
        persistence.putItem(b -> b.tableName("privs").item(Map.of(
                "name", AttributeValue.builder().s(Privilege.idFor("badPriv", tripId)).build(),
                "content", AttributeValue.builder().s("{ not json").build()))).join();

        final List<Privilege> listed = new PrivilegesDAO(mapper, DynamoLocal.persistence(), valkeyLike())
                .getTripPrivileges(tripId).join();

        Assert.assertEquals(listed.size(), 1, "the mangled row is skipped, the good one survives: " + listed);
    }

    // --- ConfigDAO ---

    @Test
    public void aColdConfigListingIsBuiltByScanningTheTable() {
        final String name = "scan.config." + RandomData.genAlpha(8);
        final Config setting = new Config(name, "42", Config.Type.INT, "test row", LocalDateTime.now(), "test");
        Assert.assertTrue(new ConfigDAO(mapper, DynamoLocal.persistence()).saveConfig(setting).join());

        final ConfigDAO reader = new ConfigDAO(mapper, DynamoLocal.persistence(), valkeyLike());

        Assert.assertTrue(reader.getAllConfig().join().stream().anyMatch(c -> name.equals(c.getName())));
        Assert.assertEquals(reader.getConfig(name).join().map(Config::getValue), Optional.of("42"));
        reader.clearCache();
        Assert.assertTrue(reader.getAllConfig().join().stream().anyMatch(c -> name.equals(c.getName())));
    }

    @Test
    public void aConfigRowWithNoNameIsRefusedBeforeItTouchesTheTable() {
        final ConfigDAO dao = new ConfigDAO(mapper, DynamoLocal.persistence());

        Assert.assertFalse(dao.saveConfig(null).join());
        Assert.assertFalse(dao.saveConfig(
                new Config("  ", "v", Config.Type.STRING, null, null, null)).join());
    }

    // --- MediaDAO ---

    private static MediaItem media(final String id, final String slot, final int position) {
        return new MediaItem(id, "downloads/" + id + ".pdf", "Title " + id, null, "application/pdf",
                1234L, slot, position, LocalDateTime.now(), "test");
    }

    @Test
    public void aColdMediaListingScansAndASlotQueryFiltersAndOrders() {
        final String slot = "slot-" + RandomData.genAlpha(8);
        final MediaDAO writer = new MediaDAO(mapper, DynamoLocal.persistence());
        Assert.assertTrue(writer.saveMedia(media("m2-" + slot, slot, 2)).join());
        Assert.assertTrue(writer.saveMedia(media("m1-" + slot, slot, 1)).join());
        Assert.assertTrue(writer.saveMedia(media("other-" + slot, "elsewhere", 0)).join());

        final MediaDAO reader = new MediaDAO(mapper, DynamoLocal.persistence(), valkeyLike());
        final List<MediaItem> inSlot = reader.getMediaInSlot(" " + slot.toUpperCase() + " ").join();

        Assert.assertEquals(inSlot.stream().map(MediaItem::getId).toList(),
                List.of("m1-" + slot, "m2-" + slot),
                "slot matching is trimmed and case-insensitive; order is by position");
        Assert.assertEquals(reader.getMediaInSlot("   ").join(), List.of());
        Assert.assertEquals(reader.getMediaInSlot(null).join(), List.of());
    }

    @Test
    public void deletingMediaInvalidatesTheCacheSoTheNextListingRebuilds() {
        final String slot = "del-" + RandomData.genAlpha(8);
        final String id = "gone-" + slot;
        final MediaDAO dao = new MediaDAO(mapper, DynamoLocal.persistence(), valkeyLike());
        Assert.assertTrue(dao.saveMedia(media(id, slot, 0)).join());
        Assert.assertEquals(dao.getMediaInSlot(slot).join().size(), 1);

        Assert.assertTrue(dao.deleteMedia(id).join());

        Assert.assertTrue(dao.getMediaInSlot(slot).join().isEmpty(),
                "the rebuild after a delete must no longer see the row");
        Assert.assertFalse(dao.deleteMedia(null).join());
        Assert.assertFalse(dao.deleteMedia(" ").join());
    }

    @Test
    public void mediaGuardsItsInputsAndMissesCleanly() {
        final MediaDAO dao = new MediaDAO(mapper, DynamoLocal.persistence(), valkeyLike());

        Assert.assertFalse(dao.saveMedia(null).join());
        Assert.assertFalse(dao.saveMedia(media(" ", "slot", 0)).join());
        Assert.assertTrue(dao.getMedia(null).join().isEmpty());
        Assert.assertTrue(dao.getMedia("never-saved-" + RandomData.genAlpha(8)).join().isEmpty());
    }

    /** The point-read fallback: an id missing from a cold cache is fetched by key, then cached. */
    @Test
    public void aColdPointReadFallsBackToTheTableByKey() {
        final String id = "point-" + RandomData.genAlpha(8);
        Assert.assertTrue(new MediaDAO(mapper, DynamoLocal.persistence())
                .saveMedia(media(id, null, 0)).join());

        final MediaDAO reader = new MediaDAO(mapper, DynamoLocal.persistence(), valkeyLike());

        Assert.assertEquals(reader.getMedia(id).join().map(MediaItem::getId), Optional.of(id),
                "a cold cache with no marker must point-read the row rather than answer empty");
    }
}
