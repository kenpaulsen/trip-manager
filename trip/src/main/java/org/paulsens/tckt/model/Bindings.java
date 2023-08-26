package org.paulsens.tckt.model;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.Getter;
import org.paulsens.tckt.dao.FilesystemPersistence;

public class Bindings implements Map<Binding.Id, Binding> {
    @Getter
    private final Map<Binding.Id, Binding> wrapped;

    public Bindings(Map<Binding.Id, Binding> bindings) {
        this.wrapped = (bindings == null) ? new ConcurrentHashMap<>() : bindings;
    }

    /**
     * Returns a {@code Stream} of Objects where the given criteria match the Bindings. The Objects of the targetIds
     * are returned in the stream.
     * @param persistence   The FilesystemPersistence used to store stuff.
     * @param constraints   The List of constraints to apply to the Bindings.
     * @return The Object(s) if any pointed to be the targetIds of the Bindings (or an empty stream if none found).
     */
    public Stream<Object> resolve(final FilesystemPersistence persistence, final List<Predicate<Binding>> constraints) {
        Stream<Binding> stream = wrapped.entrySet().stream().map(Entry::getValue);
        for (final Predicate<Binding> constraint : constraints) {
            stream = stream.filter(constraint);
        }
        return stream.map(binding -> binding.getDestId().getType().resolve(persistence, binding.getDestId().getValue()))
                .filter(Objects::nonNull);
    }

    @Override
    public int size() {
        return wrapped.size();
    }

    @Override
    public boolean isEmpty() {
        return wrapped.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return wrapped.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return wrapped.containsValue(value);
    }

    @Override
    public Binding get(Object key) {
        return wrapped.get(key);
    }

    @Override
    public Binding put(Binding.Id key, Binding value) {
        return wrapped.put(key, value);
    }

    @Override
    public Binding remove(Object key) {
        return wrapped.remove(key);
    }

    @Override
    public void putAll(Map<? extends Binding.Id, ? extends Binding> m) {
        wrapped.putAll(m);
    }

    @Override
    public void clear() {
        wrapped.clear();
    }

    @Override
    public Set<Binding.Id> keySet() {
        return wrapped.keySet();
    }

    @Override
    public Collection<Binding> values() {
        return wrapped.values();
    }

    @Override
    public Set<Entry<Binding.Id, Binding>> entrySet() {
        return wrapped.entrySet();
    }
}
