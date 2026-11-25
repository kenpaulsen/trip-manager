package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.function.Consumer;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

/**
 * {@link ConfigDAO#probeTable(Duration)}, the deep health check's datastore probe. Its two defining properties are the
 * ones a well-meant refactor would break silently: it reaches the TABLE on every call (a warm cache in front of it
 * would answer while DynamoDB is down), and it lets a failure through (the swallowing point read beside it would
 * turn an unreachable table into a missing row).
 */
public class ConfigDAOProbeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private static Consumer<GetItemRequest.Builder> anyRequest() {
        return ArgumentMatchers.any();
    }

    @Test
    public void everyProbeReachesTheTableEvenWithAWarmCache() {
        final Persistence store = Mockito.mock(Persistence.class, Mockito.CALLS_REAL_METHODS);
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final ConfigDAO dao = new ConfigDAO(new ObjectMapper().findAndRegisterModules(), store, cache);
        // Warm the way production is warm: with the table's loaded marker set, a point read through the cache
        // answers "not found" without asking the table (getAllConfig on this client does not set it).
        cache.putValue(CacheKeys.CONFIG_LOADED, String.valueOf(System.currentTimeMillis()), CacheKeys.GC_TTL);
        Assert.assertTrue(dao.getConfig(ConfigDAO.PROBE_ROW).isEmpty());
        Mockito.verify(store, Mockito.never()).getItem(anyRequest());
        dao.probeTable(TIMEOUT);
        dao.probeTable(TIMEOUT);
        Mockito.verify(store, Mockito.times(2)).getItem(anyRequest());
    }

    @Test
    public void anUnreachableTableThrows() {
        final Persistence store = Mockito.mock(Persistence.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doThrow(new IllegalStateException("table unreachable")).when(store).getItem(anyRequest());
        final ConfigDAO dao = new ConfigDAO(new ObjectMapper().findAndRegisterModules(), store,
                new InMemoryCacheClient());
        Assert.assertThrows(IllegalStateException.class, () -> dao.probeTable(TIMEOUT));
    }

    /** The probe carries its own call timeout, so a slow table fails with the SDK's reason, not the deadline. */
    @Test
    public void theProbeCarriesItsOwnCallTimeout() {
        final Persistence store = Mockito.mock(Persistence.class, Mockito.CALLS_REAL_METHODS);
        final ConfigDAO dao = new ConfigDAO(new ObjectMapper().findAndRegisterModules(), store,
                new InMemoryCacheClient());
        dao.probeTable(TIMEOUT);
        final ArgumentCaptor<Consumer<GetItemRequest.Builder>> sent = ArgumentCaptor.captor();
        Mockito.verify(store).getItem(sent.capture());
        final GetItemRequest.Builder request = GetItemRequest.builder();
        sent.getValue().accept(request);
        Assert.assertEquals(request.build().overrideConfiguration().orElseThrow().apiCallTimeout().orElseThrow(),
                TIMEOUT);
    }
}
