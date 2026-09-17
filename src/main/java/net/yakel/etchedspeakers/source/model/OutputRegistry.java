package net.yakel.etchedspeakers.source.model;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Thread-confined ownership. Reordering a desired set never recreates its existing outputs. */
public final class OutputRegistry<K, V> {
    private final Map<K, V> entries = new LinkedHashMap<>();

    public void reconcile(Collection<K> desired, Function<K, V> create, BiConsumer<K, V> close) {
        var keys = new LinkedHashSet<>(desired);
        retain(keys, close);
        for (K key : keys) entries.computeIfAbsent(key, create);
    }

    /** Removal phase can be run for every master before allocating any new native sources. */
    public void retain(Collection<K> desired, BiConsumer<K, V> close) {
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!desired.contains(entry.getKey())) {
                iterator.remove();
                close.accept(entry.getKey(), entry.getValue());
            }
        }
    }

    public void clear(BiConsumer<K, V> close) { retain(List.of(), close); }
    public V get(K key) { return entries.get(key); }
    public int size() { return entries.size(); }
    public void forEach(BiConsumer<K, V> action) { entries.forEach(action); }
}
