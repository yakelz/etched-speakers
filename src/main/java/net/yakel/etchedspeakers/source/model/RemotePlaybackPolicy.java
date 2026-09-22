package net.yakel.etchedspeakers.source.model;

/** Route choice is independent of the speaker's audible range. */
public final class RemotePlaybackPolicy {
    private RemotePlaybackPolicy() {}

    public static boolean keepLocal(boolean integrated, boolean localAvailable, boolean remoteLatched,
            boolean sourceAvailable, double sourceDistanceSquared) {
        return localAvailable && (integrated || !remoteLatched && sourceAvailable && sourceDistanceSquared <= 64 * 64);
    }
}
