package net.yakel.etchedspeakers.client.audio.sync;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.source.model.PcmWindow;
import net.yakel.etchedspeakers.source.model.SpeakerSettings;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Locale;
import net.minecraft.core.GlobalPos;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.system.MemoryUtil;

/** One mirror AL source with independent buffer ownership; every method runs on the sound executor. */
public final class SpeakerOutput {
    private final PcmWindow pcm;
    private final int rate;
    private final int chunkFrames;
    private final String track;
    private final String speaker;
    private final int source;
    private final int alFormat;
    private final ByteBuffer scratch;
    private final ArrayDeque<QueuedBuffer> queued = new ArrayDeque<>(5);
    private long endFrame;
    private long lastWarningFrame = Long.MIN_VALUE / 2;
    private boolean started;
    private boolean closed;
    private long observations;
    private long maxDeltaFrames;
    private long firstMasterFrame = -1;
    private long lastMasterFrame;
    private boolean reportedTwoMinutes;
    private static boolean reportedGainLimit;

    SpeakerOutput(PcmWindow pcm, int rate, int chunkFrames, GlobalPos key, String track) {
        this.pcm = pcm;
        this.rate = rate;
        this.chunkFrames = chunkFrames;
        this.track = track;
        this.speaker = SpeakerRequest.describe(key);
        source = AL10.alGenSources();
        int error = AL10.alGetError();
        if (source == 0 || error != AL10.AL_NO_ERROR) {
            if (source != 0) AL10.alDeleteSources(source);
            throw new IllegalStateException("OpenAL source unavailable");
        }
        alFormat = pcm.sampleBytes() == 1 ? AL10.AL_FORMAT_MONO8 : AL10.AL_FORMAT_MONO16;
        ByteBuffer allocated = null;
        try {
            allocated = MemoryUtil.memAlloc(chunkFrames * pcm.sampleBytes());
            var pos = key.pos();
            AL10.alSource3f(source, AL10.AL_POSITION, pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F);
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
            // Same per-source distance-model extension enabled by Minecraft Library.init().
            AL10.alSourcei(source, AL10.AL_DISTANCE_MODEL, AL11.AL_LINEAR_DISTANCE_CLAMPED);
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 0.0F);
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, SpeakerSettings.DEFAULT_RANGE);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0F);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) throw new IllegalStateException("OpenAL source setup failed");
            // OpenAL Soft permits amplification, but its default MAX_GAIN would clamp 200% to 100% nearby.
            AL10.alSourcef(source, AL10.AL_MAX_GAIN, SpeakerSettings.MAX_VOLUME);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                AL10.alSourcef(source, AL10.AL_MAX_GAIN, 1.0F);
                if (!reportedGainLimit) {
                    reportedGainLimit = true;
                    EtchedSpeakers.LOGGER.warn("[EtchedSpeakers] Audio backend limits per-source gain to 100%; requested volume still controls distance attenuation");
                }
            }
        } catch (RuntimeException | Error failure) {
            if (allocated != null) MemoryUtil.memFree(allocated);
            AL10.alDeleteSources(source);
            throw failure;
        }
        scratch = allocated;
    }

    void setGain(float gain) { if (!closed) AL10.alSourcef(source, AL10.AL_GAIN, gain); }

    void setRange(float range) { if (!closed) AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, range); }

    void update(long masterBaseFrame, int masterSource, boolean paused, float pitch) {
        if (closed) return;
        removeProcessed();
        AL10.alSourcef(source, AL10.AL_PITCH, pitch);
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        long masterFrame = masterBaseFrame + AL10.alGetSourcei(masterSource, org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET);
        long mirrorFrame = queued.isEmpty() ? endFrame : queued.getFirst().startFrame + AL10.alGetSourcei(source, org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET);
        long delta = masterFrame - mirrorFrame;
        if (started && !queued.isEmpty()) {
            observations++;
            maxDeltaFrames = Math.max(maxDeltaFrames, Math.abs(delta));
            lastMasterFrame = masterFrame;
            if (!reportedTwoMinutes && masterFrame - firstMasterFrame >= rate * 120L) {
                reportedTwoMinutes = true;
                EtchedSpeakers.LOGGER.debug("[EtchedSpeakers] Speaker DRIFT_CHECK speaker={} track={} observedMs={} maxDeltaMs={} observations={}",
                        speaker, track, ms(masterFrame - firstMasterFrame), ms(maxDeltaFrames), observations);
            }
        }
        if (started && !queued.isEmpty() && Math.abs(delta) > rate / 5 && masterFrame - lastWarningFrame > rate * 5L) {
            lastWarningFrame = masterFrame;
            EtchedSpeakers.LOGGER.warn("[EtchedSpeakers] Speaker drift warning speaker={} track={} masterFrame={} speakerFrame={} deltaFrames={} deltaMs={}",
                    speaker, track, masterFrame, mirrorFrame, delta, ms(delta));
        }
        // Rejoin from the measured master cursor after underrun/overrun or >50 ms deviation.
        if (!started || queued.isEmpty() || state == AL10.AL_STOPPED || endFrame < pcm.startFrame()
                || Math.abs(delta) > rate / 20) {
            clearQueue();
            endFrame = masterFrame;
        }
        while (endFrame < pcm.endFrame() && queued.size() < 5) {
            scratch.clear();
            int frames = pcm.copy(endFrame, chunkFrames, scratch);
            if (frames == 0) break;
            scratch.flip();
            int buffer = AL10.alGenBuffers();
            if (buffer != 0) queued.addLast(new QueuedBuffer(buffer, endFrame));
            AL10.alBufferData(buffer, alFormat, scratch, rate);
            AL10.alSourceQueueBuffers(source, buffer);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                close();
                EtchedSpeakers.LOGGER.warn("[EtchedSpeakers] Speaker STOP speaker={} reason=OPENAL_QUEUE_FAILED track={}", speaker, track);
                return;
            }
            endFrame += frames;
        }
        if (!queued.isEmpty()) {
            if (paused) {
                // Queue can be prepared while paused, but must not emit audio.
                pause();
            } else if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                AL10.alSourcePlay(source);
                if (!started) {
                    started = true;
                    long currentMaster = masterBaseFrame + AL10.alGetSourcei(masterSource, org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET);
                    long frame = queued.getFirst().startFrame + AL10.alGetSourcei(source, org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET);
                    firstMasterFrame = currentMaster;
                    lastMasterFrame = currentMaster;
                    EtchedSpeakers.LOGGER.debug("[EtchedSpeakers] Speaker START speaker={} track={} masterSequence={} masterFrame={} speakerFrame={} deltaFrames={} deltaMs={}",
                            speaker, track, pcm.sequence(), currentMaster, frame, currentMaster - frame, ms(currentMaster - frame));
                }
            }
        }
    }

    void pause() { if (!closed && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) AL10.alSourcePause(source); }

    boolean isClosed() { return closed; }

    private void removeProcessed() {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            int buffer = AL10.alSourceUnqueueBuffers(source);
            AL10.alDeleteBuffers(buffer);
            queued.pollFirst();
        }
    }

    private void clearQueue() {
        AL10.alSourceStop(source);
        // Detaching the queue releases this source's references; buffers are exclusively ours.
        AL10.alSourcei(source, AL10.AL_BUFFER, 0);
        for (var buffer : queued) AL10.alDeleteBuffers(buffer.id);
        queued.clear();
    }

    void close() {
        if (closed) return;
        clearQueue();
        AL10.alDeleteSources(source);
        MemoryUtil.memFree(scratch);
        closed = true;
        if (started) {
            EtchedSpeakers.LOGGER.debug("[EtchedSpeakers] Speaker STOP statistics speaker={} track={} observations={} observedMs={} maxDeltaMs={}",
                    speaker, track, observations, ms(Math.max(0, lastMasterFrame - firstMasterFrame)), ms(maxDeltaFrames));
        }
    }

    private String ms(long frames) { return String.format(Locale.ROOT, "%.3f", frames * 1000.0 / rate); }
    private record QueuedBuffer(int id, long startFrame) {}
}
