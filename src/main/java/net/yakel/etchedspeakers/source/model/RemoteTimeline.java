package net.yakel.etchedspeakers.source.model;

/** Pure canonical timeline. Server ticks anchor actual observer frames, never invent a track start. */
public final class RemoteTimeline {
    public static final int OBSERVER_LEASE = 100, SESSION_LEASE = 200, INTEREST_LEASE = 120;
    public static final long MAX_SECONDS = 6 * 60 * 60;
    private long generation, frame, tick, lastReport, localId;
    private int rate;
    private String media = "", observer = "";
    private long offset;
    private boolean active, paused;

    public static boolean valid(long frame, int rate) {
        return rate >= 8000 && rate <= 192000 && frame >= 0 && frame <= MAX_SECONDS * rate;
    }
    public static long project(long frame, int rate, long elapsedTicks, boolean paused) {
        if (!valid(frame, rate)) return -1;
        return Math.min(MAX_SECONDS * rate, frame + (paused ? 0 : Math.clamp(elapsedTicks, 0L, 1200L) * rate / 20));
    }
    public static boolean expired(long now, long renewed, int lease) { return now - renewed > lease; }
    public static boolean acceptsSnapshot(long generation, long tick, long oldGeneration, long oldTick) {
        return generation > 0 && (generation > oldGeneration || generation == oldGeneration && tick >= oldTick);
    }
    public static boolean sourceAllowed(SourcePlaybackState state, String media) {
        return state.available() && state.playing() != SourcePlaybackState.Playback.STOPPED
                && state.availableTracks().stream().anyMatch(t -> t.mediaKey().equals(media));
    }
    public boolean canObserve(String player, long now) {
        return observer.isEmpty() || observer.equals(player) || expired(now, lastReport, OBSERVER_LEASE);
    }
    /** Returns CREATE / TRACK_CHANGE / CONTINUE / UPDATE / REJECTED. */
    public String observe(String player, long id, String key, long reportedFrame, int sampleRate,
            boolean isPaused, long now, long nextGeneration) {
        if (!valid(reportedFrame, sampleRate) || id <= 0 || !canObserve(player, now)) return "REJECTED";
        if (!active && id == localId && player.equals(observer)) return "REJECTED"; // old terminal master
        String result;
        if (!active || !media.equals(key)) {
            result = active ? "TRACK_CHANGE" : "CREATE";
            generation = nextGeneration;
            frame = reportedFrame;
            media = key;
            rate = sampleRate;
            offset = 0;
        } else {
            if (sampleRate != rate) return "REJECTED";
            long expected = at(now);
            boolean rebind = id != localId || !observer.equals(player);
            if (rebind) offset = expected - reportedFrame;
            long observed = reportedFrame + offset;
            // A recreated local frame=0 cannot rewind a canonical session. Reject large jumps.
            frame = Math.abs(observed - expected) <= rate * 2L ? Math.max(0, observed) : expected;
            result = rebind ? "CONTINUE" : "UPDATE";
        }
        observer = player; localId = id; paused = isPaused; tick = now; lastReport = now; active = true;
        return result;
    }
    public boolean eof(String player, long id, long now) {
        if (!active || !observer.equals(player) || id != localId) return false;
        return stop(now);
    }
    public boolean stop(long now) {
        if (!active) return false;
        frame = at(now); tick = now; active = false;
        return true;
    }
    public long at(long now) { return project(frame, rate, now - tick, paused || !active); }
    public long generation() { return generation; }
    public int rate() { return rate; }
    public boolean active() { return active; }
    public boolean paused() { return paused; }
    public long lastReport() { return lastReport; }
    public String media() { return media; }
    public String observer() { return observer; }
    public long localId() { return localId; }
}
