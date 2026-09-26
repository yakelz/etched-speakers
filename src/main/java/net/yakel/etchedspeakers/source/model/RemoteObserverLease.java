package net.yakel.etchedspeakers.source.model;

import java.util.*;

/** A ready decoder is a candidate, never an authority until elected by the server. */
public final class RemoteObserverLease {
    public static final int LEASE = 100;
    private record Ready(UUID epoch, long master, long tick) {}
    private final Map<UUID, Ready> candidates = new TreeMap<>();
    private UUID owner;
    private long token, sequence, renewed;
    public void ready(UUID player, UUID epoch, long master, long now) {
        var old=candidates.get(player);
        if (old != null && (!old.epoch.equals(epoch) || old.master != master) && player.equals(owner)) owner=null;
        candidates.put(player, new Ready(epoch, master, now));
    }
    public boolean elect(Set<UUID> listeners, long now) {
        UUID previous=owner; long previousToken=token;
        candidates.entrySet().removeIf(e -> !listeners.contains(e.getKey()) || now-e.getValue().tick > LEASE);
        if (owner != null && (!candidates.containsKey(owner) || now-renewed > LEASE)) {
            candidates.remove(owner); owner=null;
        }
        if (owner == null && !candidates.isEmpty()) {
            owner=candidates.keySet().iterator().next(); token=++sequence; renewed=now;
        }
        return !Objects.equals(previous,owner) || previousToken!=token;
    }
    public boolean accepts(UUID player, UUID epoch, long master, long leaseToken, long now) {
        var r=candidates.get(player);
        return owner != null && owner.equals(player) && token==leaseToken && leaseToken>0
                && r != null && r.epoch.equals(epoch) && r.master==master && now-renewed<=LEASE;
    }
    public void renew(long now) { renewed=now; }
    public long tokenFor(UUID player) { return player.equals(owner) ? token : 0; }
    public UUID owner() { return owner; }
    public void clear() { candidates.clear(); owner=null; token=0; /* never reuse a lease token */ }
}
