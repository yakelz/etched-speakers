package net.yakel.etchedspeakers.source.model;

/** Server JukeboxSongPlayer time, converted only with the opened decoder's actual sample rate. */
public record NativeDiscClock(long elapsedTicks, long receivedNano, long delayTicks, boolean paused) {
    public NativeDiscClock(long elapsedTicks,long receivedNano,long delayTicks) { this(elapsedTicks,receivedNano,delayTicks,false); }
    public NativeDiscClock {
        if (!validTicks(elapsedTicks)) throw new IllegalArgumentException("Invalid native elapsed ticks");
        delayTicks = Math.clamp(delayTicks, 0L, 40L);
    }
    public static boolean validTicks(long ticks) { return ticks >= 0 && ticks <= RemoteTimeline.MAX_SECONDS * 20; }
    public long target(int rate, long nowNano) {
        if (!RemoteTimeline.valid(0, rate)) return -1;
        long elapsedNanos = paused ? 0 : Math.clamp(nowNano - receivedNano, 0L, 60_000_000_000L);
        return Math.min(RemoteTimeline.MAX_SECONDS * rate,
                (elapsedTicks + (paused ? 0 : delayTicks)) * rate / 20 + elapsedNanos * rate / 1_000_000_000L);
    }
    public long target(int rate) { return target(rate, System.nanoTime()); }
}
