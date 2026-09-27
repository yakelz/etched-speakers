package net.yakel.etchedspeakers.source.model;

import java.util.*;

/** Server-thread lifecycle, independent of Minecraft. Keys are validated source identities. No persistence. */
public final class ListenerRetention<K> {
    public static final int MAX_RETAINED_SOURCES = 32, GRACE_TICKS = 200, HANDOFF_GRACE = 1200, LOAD_TIMEOUT = 600;
    public static final class Entry {
        private Set<UUID> listeners = Set.of();
        private Set<UUID> holders = Set.of();
        private long requested;
        private long grace = -1;
        private boolean ready;
        private boolean nativeRemote;
        /** Server-validated native remote occurrence; survives local handoff, cleared on stop. */
        public boolean nativeRemote() { return nativeRemote; }
        public void nativeRemote(boolean value) { nativeRemote = value; }
        Entry(long now) { requested = now; }
        public int listeners() { return listeners.size(); }
        public int holders() { return holders.size(); }
        public boolean heldBy(UUID id) { return holders.contains(id); }
        public boolean ticketNeeded() { return !listeners.isEmpty() || holders.isEmpty() && grace>=0; }
        public String state() { return !listeners.isEmpty()?"ACTIVE_REMOTE":!holders.isEmpty()?"ACTIVE_LOCAL":"HANDOFF_GRACE"; }
        public boolean contains(UUID id) { return listeners.contains(id); }
        public boolean ready() { return ready; }
        public long grace() { return grace; }
    }
    private final int graceTicks;
    /** Historical policy remains available to its regression checks; production explicitly opts into handoff. */
    public ListenerRetention() { this(GRACE_TICKS); }
    public ListenerRetention(int graceTicks) {
        if(graceTicks<=0) throw new IllegalArgumentException("Invalid grace");
        this.graceTicks=graceTicks;
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
        return request(key,listeners,Set.of(),now);
    }
    public boolean request(K key, Set<UUID> listeners, Set<UUID> holders, long now) {
        if (listeners.isEmpty() && holders.isEmpty() || denied.contains(key)) return false;
        var e = entries.get(key);
        if (e == null) {
            if (entries.size() >= MAX_RETAINED_SOURCES) return false;
            e = new Entry(now); entries.put(key, e);
        }
        e.listeners = Set.copyOf(listeners); e.holders=Set.copyOf(holders); e.grace = -1;
        return true;
    }
    public void reconcile(Map<K, Set<UUID>> validated, long now) {
        reconcile(validated,Map.of(),now);
    }
    public void reconcile(Map<K,Set<UUID>> validated, Map<K,Set<UUID>> holders, long now) {
        var interested=new HashSet<K>(validated.keySet()); interested.addAll(holders.keySet());
        denied.retainAll(interested);
        entries.forEach((key, e) -> {
            e.listeners = Set.copyOf(validated.getOrDefault(key, Set.of()));
            e.holders=Set.copyOf(holders.getOrDefault(key,Set.of()));
            if (e.listeners.isEmpty() && e.holders.isEmpty()) { if (e.grace < 0) e.grace = now + graceTicks; }
            else e.grace = -1;
        });
    }
    public void ready(K key) { var e = entries.get(key); if (e != null) e.ready = true; }
    public void loading(K key, long now) { var e=entries.get(key); if(e!=null) { e.ready=false; e.requested=now; } }
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
