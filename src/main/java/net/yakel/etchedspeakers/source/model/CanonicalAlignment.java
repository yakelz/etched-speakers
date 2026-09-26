package net.yakel.etchedspeakers.source.model;

/** Pure reconciliation guards. No decoder, Minecraft clock or server authority lives here. */
public final class CanonicalAlignment {
    private CanonicalAlignment() {}
    public record Identity(Object source, long generation, String media, int slot, int index) {}
    public static boolean accepts(Identity next, long tick, Identity previous, long previousTick) {
        return next.generation()>0 && (previous==null || next.source().equals(previous.source())
                && RemoteTimeline.acceptsSnapshot(next.generation(),tick,previous.generation(),previousTick)
                && (next.generation()!=previous.generation() || next.equals(previous)));
    }
    public static boolean acceptsLocalObservation(boolean remoteOwned) { return !remoteOwned; }
    public static boolean matches(Identity canonical, Object source, String media, int slot, int index) {
        return canonical != null && canonical.source().equals(source) && canonical.media().equals(media)
                && canonical.slot()==slot && canonical.index()==index;
    }
    public static boolean keepLocalSpeaker(boolean remoteOwned, boolean integrated, boolean available,
            boolean latched, boolean sourceAvailable, double distance) {
        return !remoteOwned && RemotePlaybackPolicy.keepLocal(integrated,available,latched,sourceAvailable,distance);
    }
    /** Immutable publication to preparation workers; only receiver-local nanoTime is used. */
    public record Clock(long frame, int rate, boolean paused, long receivedNano, long delayTicks) {
        public long target(long nowNano) {
            if(rate==0) return 0;
            long base=RemoteTimeline.project(frame,rate,delayTicks,paused);
            if(base<0) return -1;
            long elapsed=Math.max(0,nowNano-receivedNano)/1_000_000;
            return Math.min(RemoteTimeline.MAX_SECONDS*rate,base+(paused?0:elapsed*rate/1000));
        }
        public long target() { return target(System.nanoTime()); }
    }
    /** Three consecutive one-second samples over 1.5s; at most one request per ten seconds. */
    public static final class DriftGate {
        private long lastRequest=Long.MIN_VALUE/2;
        private int samples;
        public boolean sample(long deltaFrames, int rate, boolean paused, long nowNano) {
            if(rate<=0 || paused || Math.abs(deltaFrames)<=rate*3L/2) { samples=0; return false; }
            if(++samples<3 || nowNano-lastRequest<10_000_000_000L) return false;
            requested(nowNano); return true;
        }
        public void requested(long nowNano) { samples=0; lastRequest=nowNano; }
    }
    /** A cancelled preparation can never become current again, even for a repeated URL. */
    public static final class Preparation {
        private final Identity identity;
        private volatile boolean cancelled;
        public Preparation(Identity identity) { this.identity=identity; }
        public boolean current(Identity current) { return !cancelled && identity.equals(current); }
        public void cancel() { cancelled=true; }
    }
}
