package net.yakel.etchedspeakers.client.audio.sync;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

/** Temporary observation only. No play/stop/seek, error clearing, buffer mutation or extra decoder reads. */
public final class StreamingQueueDiagnostics {
    // Five queue phases per update; retain roughly twenty complete updates, plus recent reads.
    private static final int QUEUE_HISTORY = 100;
    private static final int READ_HISTORY = 20;
    private static final long SLOW_UPDATE_NS = 200_000_000L;
    private static final long SLOW_READ_NS = 100_000_000L;
    private static final long REPORT_INTERVAL_NS = 5_000_000_000L;
    private final AudioDiagnostics.Trace trace;
    private final int frameBytes;
    private final int sampleRate;
    private final ArrayDeque<QueueSample> queues = new ArrayDeque<>(QUEUE_HISTORY);
    private final ArrayDeque<ReadSample> decoderReads = new ArrayDeque<>(READ_HISTORY);
    private final ArrayDeque<ReadSample> tapReads = new ArrayDeque<>(READ_HISTORY);
    private int source;
    private Thread soundThread;
    private long updateStarted;
    private long updateInterval = -1;
    private long updateIndex;
    private long decoderStarted;
    private long tapStarted;
    private long decoderFrames;
    private long tapFrames;
    private long lastReport;
    private int lastState = -1;
    private boolean stateChanged;
    private boolean lowQueue;
    private boolean slowUpdate;
    private boolean slowRead;
    private boolean readFailure;
    private boolean stopPending;
    private boolean stoppedDumped;
    private boolean decoderEmpty;
    private boolean tapEof;
    private boolean wrongThreadReported;
    private int dumpId;
    private ReadSample lastProblemRead;
    private QueueSample lastLowQueue;
    private QueueSample lastSlowUpdate;
    private String lastDecoderTerminal = "";
    private String lastTapTerminal = "";

    public StreamingQueueDiagnostics(AudioDiagnostics.Trace trace, int frameBytes, int sampleRate) {
        this.trace = trace;
        this.frameBytes = frameBytes;
        this.sampleRate = sampleRate;
    }

    public void bind(int source) {
        this.source = source;
        // Establish ownership once; every AL query below checks this exact thread.
        if (AudioThreadBridge.isSameThread()) soundThread = Thread.currentThread();
        trace.log("QUEUE_DIAGNOSTICS_BOUND", "alSource=" + source + " sampleRate=" + sampleRate
                + " frameBytes=" + frameBytes + " queueHistory=" + QUEUE_HISTORY
                + " readHistory=" + READ_HISTORY + " slowUpdateMs=200 slowReadMs=100");
    }

    public void beginUpdate(long baseFrame) {
        long now = System.nanoTime();
        updateInterval = updateStarted == 0 ? -1 : now - updateStarted;
        updateStarted = now;
        updateIndex++;
        if (updateInterval >= SLOW_UPDATE_NS) slowUpdate = true;
        snapshot("UPDATE_HEAD", baseFrame);
    }

    public void snapshot(String phase, long baseFrame) {
        if (source == 0 || Thread.currentThread() != soundThread) {
            if (!wrongThreadReported) {
                wrongThreadReported = true;
                trace.log("QUEUE_SAMPLE_SKIPPED", "alSource=" + source + " reason=NOT_BOUND_SOUND_THREAD");
            }
            return;
        }
        long now = System.nanoTime();
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        int offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
        // A stopped source's offset can be reset. Never label base+offset its valid playback cursor.
        long masterFrame = state == AL10.AL_PLAYING || state == AL10.AL_PAUSED ? baseFrame + offset : -1;
        var sample = new QueueSample(updateIndex, phase, now, state, queued, processed, offset,
                baseFrame, masterFrame, updateInterval, elapsed(now, updateStarted), elapsed(now, decoderStarted), elapsed(now, tapStarted),
                decoderFrames, tapFrames, decoderEmpty, tapEof);
        remember(queues, sample, QUEUE_HISTORY);
        if (state != lastState) {
            stateChanged = true;
            if (state == AL10.AL_STOPPED) stopPending = true;
            lastState = state;
        }
        // Observe transient low queues too; do not print from inside remove/refill.
        if (queued <= 2 || queued == processed) { lowQueue = true; lastLowQueue = sample; }
        if (phase.equals("UPDATE_HEAD") && updateInterval >= SLOW_UPDATE_NS) lastSlowUpdate = sample;
    }

    public void endUpdate(long baseFrame) {
        snapshot("UPDATE_RETURN", baseFrame);
        report();
    }

    /** Only after refill, or before actual channel destruction: never delay refill with a ring dump. */
    public void report() {
        boolean reported = false;
        if (stopPending && !stoppedDumped) {
            stoppedDumped = true;
            dump("AL_STOPPED");
            reported = true;
        } else if (stateChanged || ((lowQueue || slowUpdate || slowRead || readFailure)
                && (lastReport == 0 || System.nanoTime() - lastReport >= REPORT_INTERVAL_NS))) {
            var latest = queues.peekLast();
            trace.log("QUEUE_ALERT", "alSource=" + source + " stateChanged=" + stateChanged
                    + " lowQueue=" + lowQueue + " slowUpdate=" + slowUpdate + " slowRead=" + slowRead
                    + " readFailure=" + readFailure + " " + (latest == null ? "snapshot=NONE" : latest.describe()));
            if ((slowRead || readFailure) && lastProblemRead != null) readLog("READ_ALERT", lastProblemRead);
            if (lowQueue && lastLowQueue != null) {
                trace.log("QUEUE_ALERT_SAMPLE", "alSource=" + source + " reason=LOW_QUEUE " + lastLowQueue.describe());
            }
            if (slowUpdate && lastSlowUpdate != null) {
                trace.log("QUEUE_ALERT_SAMPLE", "alSource=" + source + " reason=LONG_UPDATE_INTERVAL " + lastSlowUpdate.describe());
            }
            lastReport = System.nanoTime();
            reported = true;
        }
        stateChanged = false;
        // Preserve pending alerts across rate-limited updates; reset after a report/dump only.
        if (reported) {
            lowQueue = slowUpdate = slowRead = readFailure = false;
        }
        stopPending = false;
    }

    public long readStarted(boolean decoder) {
        long now = System.nanoTime();
        if (decoder) decoderStarted = now;
        else tapStarted = now;
        return now;
    }

    public void readFinished(boolean decoder, long start, long previousStart, int requested,
            ByteBuffer result, boolean eof, Throwable failure) {
        long now = System.nanoTime();
        int bytes = result == null ? 0 : result.remaining();
        int frames = bytes / frameBytes;
        String status = failure != null ? "EXCEPTION" : result == null ? "NULL" : bytes == 0 ? "EMPTY"
                : bytes % frameBytes != 0 ? "UNALIGNED" : "DATA";
        if (decoder) { decoderFrames += frames; decoderEmpty |= failure == null && bytes == 0; }
        else { tapFrames += frames; tapEof = eof; }
        var sample = new ReadSample(decoder ? "DECODER" : "PCM_TAP", start, now, elapsed(start, previousStart),
                requested, bytes, frames, decoder ? decoderFrames : tapFrames, status, eof,
                failure == null ? "NONE" : failure.getClass().getName());
        remember(decoder ? decoderReads : tapReads, sample, READ_HISTORY);
        slowRead |= now - start >= SLOW_READ_NS;
        readFailure |= failure != null || status.equals("UNALIGNED");
        if (now - start >= SLOW_READ_NS || failure != null || status.equals("UNALIGNED")) lastProblemRead = sample;
        // Only transitions, even if Minecraft retries a repeatedly failing stream.
        String terminal = status.equals("DATA") ? "" : status + ":" + sample.exceptionClass();
        String previousTerminal = decoder ? lastDecoderTerminal : lastTapTerminal;
        if (!terminal.isEmpty() && !terminal.equals(previousTerminal)) {
            readLog("READ_TERMINAL", sample);
        }
        if (decoder) lastDecoderTerminal = terminal;
        else lastTapTerminal = terminal;
    }

    public long previousReadStart(boolean decoder) { return decoder ? decoderStarted : tapStarted; }

    public void closed() {
        trace.log("READ_TOTALS", "alSource=" + source + " sampleRate=" + sampleRate
                + " decoderFrames=" + decoderFrames + " decoderSeconds=" + (decoderFrames / (double) sampleRate)
                + " tapFrames=" + tapFrames + " tapSeconds=" + (tapFrames / (double) sampleRate)
                + " decoderEmptyObserved=" + decoderEmpty + " tapEof=" + tapEof
                + " durationMeaning=" + (tapEof ? "DECODED_TO_OBSERVED_EOF" : "PARTIAL_NOT_MEDIA_DURATION"));
    }

    private void dump(String reason) {
        int id = ++dumpId;
        trace.log("QUEUE_DUMP_BEGIN", "alSource=" + source + " dumpId=" + id + " reason=" + reason
                + " nanoTime=" + System.nanoTime() + " queueSamples=" + queues.size()
                + " sampleRate=" + sampleRate + " decoderEmptyObserved=" + decoderEmpty + " tapEof=" + tapEof);
        for (var sample : queues) trace.log("QUEUE_SNAPSHOT", "alSource=" + source + " dumpId=" + id + " " + sample.describe());
        for (var sample : decoderReads) readLog("READ_SNAPSHOT", sample);
        for (var sample : tapReads) readLog("READ_SNAPSHOT", sample);
        trace.log("QUEUE_DUMP_END", "alSource=" + source + " dumpId=" + id);
        lastReport = System.nanoTime();
    }

    private void readLog(String event, ReadSample sample) {
        trace.log(event, "alSource=" + source + " dumpId=" + dumpId + " " + sample.describe());
    }

    private static long elapsed(long now, long before) { return before == 0 ? -1 : now - before; }
    private static double millis(long nanos) { return nanos < 0 ? -1 : nanos / 1_000_000.0; }
    private static <T> void remember(ArrayDeque<T> ring, T value, int capacity) {
        if (ring.size() == capacity) ring.removeFirst();
        ring.addLast(value);
    }

    private record QueueSample(long update, String phase, long nanoTime, int state, int queued, int processed,
            int offset, long base, long master, long updateGap, long updateAge, long decoderGap, long tapGap,
            long decoderFrames, long tapFrames, boolean decoderEmpty, boolean tapEof) {
        String describe() {
            return "update=" + update + " phase=" + phase + " nanoTime=" + nanoTime + " alState=" + state
                    + " queued=" + queued + " processed=" + processed + " unprocessed=" + (queued - processed)
                    + " sampleOffset=" + offset + " baseFrame=" + base + " masterFrame=" + master
                    + " updateIntervalMs=" + millis(updateGap) + " sinceUpdateStartMs=" + millis(updateAge)
                    + " sinceDecoderReadMs=" + millis(decoderGap)
                    + " sinceTapReadMs=" + millis(tapGap) + " decoderFrames=" + decoderFrames
                    + " tapFrames=" + tapFrames + " decoderEmptyObserved=" + decoderEmpty + " tapEof=" + tapEof;
        }
    }

    private record ReadSample(String kind, long start, long end, long gap, int requested, int bytes,
            int frames, long totalFrames, String status, boolean eof, String exceptionClass) {
        String describe() {
            return "kind=" + kind + " nanoTime=" + start + " endNanoTime=" + end + " intervalMs=" + millis(gap)
                    + " durationMs=" + millis(end - start) + " requestedBytes=" + requested + " returnedBytes=" + bytes
                    + " returnedFrames=" + frames + " cumulativeFrames=" + totalFrames + " result=" + status
                    + " tapEof=" + eof + " exceptionClass=" + exceptionClass;
        }
    }
}
