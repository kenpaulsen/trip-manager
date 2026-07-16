package org.paulsens.trip.cache;

/**
 * Shared wiring helpers for the data layer.
 */
public final class CacheSupport {
    private CacheSupport() {
    }

    /**
     * Soft revalidate against Dynamo is only safe when the cache is not the sole store. Local mode uses
     * {@link InMemoryCacheClient} as the datastore with empty FakeData loaders — revalidate would wipe data.
     */
    public static boolean softRevalidateEnabled(final CacheClient cacheClient) {
        return !(cacheClient instanceof InMemoryCacheClient);
    }
}
