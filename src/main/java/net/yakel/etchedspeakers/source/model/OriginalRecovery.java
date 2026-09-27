package net.yakel.etchedspeakers.source.model;

/** Per-occurrence recovery budget. Client-thread only; terminal state wins over slider changes. */
public final class OriginalRecovery {
    public static boolean timelineEligible(boolean active,boolean localDelivery) { return active && localDelivery; }
    public static boolean nativeTimeline(int remoteListeners,int localHolders,boolean playing) {
        return playing && (remoteListeners>0 || localHolders>0);
    }
    public static boolean suppressEnd(boolean remoteOwned,boolean decoderEof) { return remoteOwned || !decoderEof; }
    private boolean muted, terminal;
    private int attempts;
    private long next;
    public static boolean eligible(boolean active, boolean local, boolean loaded, boolean sameDimension,
            double distanceSquared, boolean fresh) {
        return active && local && loaded && sameDimension && distanceSquared<=64*64 && fresh;
    }
    public static boolean audible(float master,float records) { return master>0 && records>0; }
    public void volume(boolean audible) {
        if(muted && audible) { attempts=0; next=0; }
        muted=!audible;
    }
    public boolean begin(long now) {
        if(muted || terminal || attempts>=3 || now<next) return false;
        attempts++; next=now+10_000_000_000L; return true;
    }
    public void healthy() { attempts=0; }
    public void terminal() { terminal=true; }
    public boolean ended() { return terminal; }
}
