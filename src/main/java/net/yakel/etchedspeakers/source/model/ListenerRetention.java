package net.yakel.etchedspeakers.source.model;

import java.util.*;

/** Server-thread lifecycle, independent of Minecraft. Keys are validated source identities. No persistence. */
public final class ListenerRetention<K> {
    public static final int MAX_RETAINED_SOURCES = 32, GRACE_TICKS = 200, LOAD_TIMEOUT = 600;
    public static final class Entry {
        private Set<UUID> listeners = Set.of();
        private final long requested;
        private long grace = -1;
        private boolean ready;
        Entry(long now) { requested = now; }
        public int listeners() { return listeners.size(); }
        public boolean contains(UUID id) { return listeners.contains(id); }
        public boolean ready() { return ready; }
        public long grace() { return grace; }
    }
    private final Map<K, Entry> entries = new LinkedHashMap<>();
    // An invalid target is denied until all listeners leave it, not retried every heartbeat.
    private final Set<K> denied = new HashSet<>();
    public static boolean validSpeaker(boolean alive, boolean sameDimension, boolean loadedSpeaker,
            boolean linked, float volume, double distanceSquared, float range, boolean current) {
        return alive && sameDimension && loadedSpeaker && linked && volume>0
                && SpeakerSelection.inRange(distanceSquared,range,current);
    }
    public Set<K> keys() { return Set.copyOf(entries.keySet()); }
    public Entry get(K key) { return entries.get(key); }
    public boolean request(K key, Set<UUID> listeners, long now) {
        if (listeners.isEmpty() || denied.contains(key)) return false;
        var e = entries.get(key);
        if (e == null) {
            if (entries.size() >= MAX_RETAINED_SOURCES) return false;
            e = new Entry(now); entries.put(key, e);
        }
        e.listeners = Set.copyOf(listeners); e.grace = -1;
        return true;
    }
    public void reconcile(Map<K, Set<UUID>> validated, long now) {
        denied.retainAll(validated.keySet());
        entries.forEach((key, e) -> {
            e.listeners = Set.copyOf(validated.getOrDefault(key, Set.of()));
            if (e.listeners.isEmpty()) { if (e.grace < 0) e.grace = now + GRACE_TICKS; }
            else e.grace = -1;
        });
    }
    public void ready(K key) { var e = entries.get(key); if (e != null) e.ready = true; }
    public boolean expired(K key, long now) {
        var e = entries.get(key);
        return e != null && (e.grace >= 0 && now >= e.grace || !e.ready && now-e.requested >= LOAD_TIMEOUT);
    }
    public boolean release(K key, boolean invalid) {
        if (invalid) denied.add(key);
        return entries.remove(key) != null;
    }
    public boolean denied(K key) { return denied.contains(key); }
    public void clear() { entries.clear(); denied.clear(); }
}
