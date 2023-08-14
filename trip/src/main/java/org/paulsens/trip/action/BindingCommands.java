package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;

@Slf4j
@Named("bind")
@ApplicationScoped
public class BindingCommands {
    private static final long TIMEOUT = 5_000;
    private final DAO dao = DAO.getInstance();

    public List<String> getBindings(final String id, final BindingType type, final BindingType destType) {
        return dao.getBindings(id, type, destType)
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, List.of()))
                .join();
    }

    /**
     * This sets the binding for the given {@code destIds}, optionally in both directions (recommended). It also
     * removes any existing bindings for the given {@code id} and {@code type} that are currently bound to
     * {@code destType}, that are not included in the {@code destIds}. The List of ids removed will be returned.
     *
     * @param id                The <b>id</b> of the <em>source</em> object for the bindings.
     * @param type              The <b>type</b> of the id of the <em>source</em> object for the bindings.
     * @param destType          The <b>type</b> of the id of the <em>destination</em> object(s) for the bindings.
     * @param destIds           The collection of ids to be bound to the source object.
     * @param inBothDirections  A flag indicating the destination object should also have a binding back to the source.
     * @return  List of ids removed.
     */
    public List<String> setBindings(final String id, final BindingType type,
            final BindingType destType, final Collection<String> destIds, boolean inBothDirections) {
        final Set<String> oldIds = new HashSet<>(getBindings(id, type, destType));
        final Set<String> newIds = new HashSet<>(destIds); // Ensure uniqueness
        // FYI: dao.saveBinding will avoid making updates if there are no changes
        for (final String destId : newIds) {
            // Remove this so we can track what needs to be deleted from the old list.
            oldIds.remove(destId);
            // New binding...
            if (!saveBinding(id, type, destId, destType, inBothDirections)) {
                log.warn("Failed to save binding {} ({}) to or from {} ({})!",
                        type.name(), id, destType.name(), destId);
            }
        }
        // The oldIds Set now contains values that need to be removed
        for (final String destId : oldIds) {
            if (!removeBinding(id, type, destId, destType, inBothDirections)) {
                log.warn("Failed to remove binding {} ({}) to or from {} ({})!",
                        type.name(), id, destType.name(), destId);
            }
        }
        return new ArrayList<>(oldIds);
    }

    public boolean removeBinding(final String id, final BindingType type,
                                 final String destId, final BindingType destType, boolean removeInBothDirections) {
        return dao.removeBinding(id, type, destId, destType, removeInBothDirections)
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, false))
                .join();
    }

    // NOTE: While this is valid to do, setBindings is more comprehensive and what is desired in most use-cases
    public boolean saveBinding(final String id, final BindingType type,
                               final String destId, final BindingType destType, final boolean bindInBothDirections) {
        return dao.saveBinding(id, type, destId, destType, bindInBothDirections)
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, false))
                .join();
    }

    public String key(final String k1, final String k2) {
        return CompositeKey.builder().partitionKey(k1).sortKey(k2).build().getValue();
    }

    public List<String> splitKey(final String compositeKey) {
        final CompositeKey key = CompositeKey.from(compositeKey);
        return List.of(key.getPartitionKey(), key.getSortKey());
    }

    private <T> T logAndReturn(final Throwable ex, final T result) {
        log.warn("Exception!", ex);
        return result;
    }

    protected <T> T getBoundThing(
            final String id, final String bindingType, final BindingType dest, final Function<String, T> thingGetter) {
        final List<String> bindings = getBindings(id, BindingType.valueOf(bindingType), dest);
        if (bindings.isEmpty()) {
            return null;
        }
        return thingGetter.apply(bindings.get(0));
    }

    protected <T> T compositeKeyGetter(final String combinedKey, final BiFunction<String, String, T> biGetter) {
        final CompositeKey compositeKey = CompositeKey.from(combinedKey);
        return biGetter.apply(compositeKey.getPartitionKey(), compositeKey.getSortKey());
    }
}
