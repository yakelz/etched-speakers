package net.yakel.etchedspeakers.tests;

import net.yakel.etchedspeakers.source.model.StreamingRecovery;

public final class StreamingRecoveryChecks {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    private static StreamingRecovery drained() {
        var r = new StreamingRecovery(); r.playing();
        r.begin(true, 4, 4, 2646000, false); r.removed(4, 0); return r;
    }
    private static boolean refill(StreamingRecovery r) { return r.finish(true, 4, 2663640, false); }
    public static void run() {
        var r = drained();
        check(r.awaitingService(), "Keep speaker bindings if desired update precedes Channel refill");
        check(refill(r), "Drained queue with fresh PCM resumes existing cursor after one minute");
        check(!refill(r), "Cannot replay replacement buffers twice");
        r.begin(true, 4, 4, 2663640, false); r.removed(4, 0);
        check(r.finish(true, 4, 2681280, false), "Repeated temporary underrun is recoverable without recreation");
        r = drained(); r.stop(); check(!refill(r), "Explicit stop during refill is terminal");
        check(!r.awaitingService(), "Terminal stop still permits immediate output cleanup");
        r.begin(true, 4, 4, 2646000, false); r.removed(4, 0); r.playing();
        check(!refill(r), "Stop cannot be undone by a late play callback");
        r = drained(); r.pause(); check(!refill(r), "Pause during refill never autoplays");
        r.unpause(); r.begin(true, 4, 4, 2646000, false); r.removed(4, 0);
        check(refill(r), "Recovery allowed again after explicit unpause");
        r = drained(); check(!r.finish(true, 0, 2646000, true), "Natural EOF does not restart");
        r = drained(); check(!r.finish(true, 1, 2647000, true), "EOF during refill is not mistaken for underrun");
        r = drained(); check(!r.finish(true, 4, 2646000, false), "No decoded progress cannot restart old PCM");
        r = drained(); check(!r.finish(true, 0, 2663640, false), "Failed buffer queueing cannot restart");
        r = drained(); check(!r.finish(false, 4, 2663640, false), "Already playing or paused source is never replayed");
        r = drained(); r.removed(3, 1); check(!refill(r), "Unremoved old buffer blocks rewind");
        r = new StreamingRecovery(); r.begin(true, 4, 4, 2646000, false); r.removed(4, 0);
        check(!refill(r), "Never-started channel cannot start itself");
        r.playing(); r.begin(true, 4, 3, 2646000, false); r.removed(4, 0);
        check(!refill(r), "Partial consumption is not confirmed underrun");
        r.begin(false, 4, 4, 2646000, false); r.removed(4, 0);
        check(!refill(r), "Stopped state must be observed before refill");
        r.begin(true, 4, 4, 2646000, true); r.removed(4, 0);
        check(!refill(r), "Previously observed EOF remains terminal");
        r = drained(); r.stop(); check(!refill(r), "Decoder exception/close gate blocks recovery");
        System.out.println("Streaming recovery checks passed: " + checks);
    }
}
