package net.yakel.etchedspeakers.source.model;

/** Sound-thread gate: resume only a drained queue whose old buffers were completely replaced. */
public final class StreamingRecovery {
    private boolean started, paused, terminal, candidate;
    private int oldBuffers;
    private long oldEndFrame;

    public void playing() { if (!terminal) { started = true; paused = false; } }
    public void pause() { paused = true; candidate = false; }
    public void unpause() { paused = false; }
    public void stop() { terminal = true; candidate = false; }
    public boolean awaitingService() { return started && !paused && !terminal; }

    public void begin(boolean stopped, int queued, int processed, long endFrame, boolean eof) {
        candidate = awaitingService() && !eof && stopped && queued > 0 && queued == processed;
        oldBuffers = queued;
        oldEndFrame = endFrame;
    }

    public void removed(int count, int remaining) {
        candidate &= count == oldBuffers && remaining == 0;
    }

    /** One attempt per refill; never replay old queued PCM or an empty/failing stream. */
    public boolean finish(boolean stopped, int queued, long endFrame, boolean eof) {
        boolean resume = candidate && !paused && !terminal && !eof && stopped && queued > 0 && endFrame > oldEndFrame;
        candidate = false;
        return resume;
    }
}
