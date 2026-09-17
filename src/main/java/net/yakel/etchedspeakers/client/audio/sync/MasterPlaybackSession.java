package net.yakel.etchedspeakers.client.audio.sync;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.source.model.OutputRegistry;
import net.yakel.etchedspeakers.source.model.PcmWindow;
import net.yakel.etchedspeakers.source.model.TrackReference;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.sound.AbstractOnlineSoundInstance.OnlineSound;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.GlobalPos;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

/** One original decoder, one PCM window, many positioned outputs. Mutable state is sound-thread only. */
public final class MasterPlaybackSession {
    private final SoundInstance owner;
    private final AudioFormat format;
    private final PcmWindow pcm;
    private final int sampleRate;
    private final int chunkFrames;
    private final ArrayDeque<Integer> originalBuffers = new ArrayDeque<>(4);
    private final OutputRegistry<GlobalPos, Binding> speakers = new OutputRegistry<>();
    private final String mediaKey;
    private String sourceIdentity = "unattached";
    private long baseFrame;
    private int originalSource;
    private volatile boolean closed;

    MasterPlaybackSession(SoundInstance owner, AudioFormat format) {
        this.owner = owner;
        this.format = format;
        sampleRate = Math.round(format.getSampleRate());
        chunkFrames = Math.max(1, sampleRate / 10);
        pcm = new PcmWindow(sampleRate / 2, format.getSampleSizeInBits() / 8);
        String location = owner.getSound() instanceof OnlineSound online ? online.getURL() : owner.getLocation().toString();
        mediaKey = new TrackReference(TrackData.isLocalSound(location) ? TrackReference.Kind.SOUND_EVENT : TrackReference.Kind.URL,
                location, Optional.empty(), -1, -1).mediaKey();
    }

    public int chunkFrames() { return chunkFrames; }
    public boolean isClosed() { return closed; }

    public void bind(int source) {
        originalSource = source;
        MasterSessions.register(owner, this);
        EtchedSpeakers.LOGGER.debug("[EtchedSpeakers] Master audio detected track={} sampleRate={} bits={} channels={} decoder=1 windowMs=500",
                mediaKey, sampleRate, format.getSampleSizeInBits(), format.getChannels());
    }

    void decoded(ByteBuffer data) {
        int frames = data.remaining() / (format.getChannels() * pcm.sampleBytes());
        pcm.append(data, format.getChannels());
        originalBuffers.addLast(frames);
    }

    public void unqueued(int count) {
        for (int i = 0; i < count; i++) {
            var frames = originalBuffers.pollFirst();
            if (frames != null) baseFrame += frames;
        }
    }

    void retainSpeakers(Collection<GlobalPos> keys, Map<GlobalPos, String> reasons, String defaultReason) {
        int before = outputCount();
        speakers.retain(keys, (key, binding) -> closeBinding(key, binding, reasons.getOrDefault(key, defaultReason)));
        reportCountChange(before);
    }

    void setDesired(Map<GlobalPos, SpeakerRequest> requests) {
        if (closed) return;
        speakers.reconcile(requests.keySet(), key -> new Binding(requests.get(key)),
                (key, binding) -> closeBinding(key, binding, "NO_LONGER_DESIRED"));
        speakers.forEach((key, binding) -> {
            var request = requests.get(key);
            sourceIdentity = SpeakerRequest.describe(request.source());
            if (binding.output != null && binding.request.gain() != request.gain()) binding.output.setGain(request.gain());
            if (binding.output != null && binding.request.audibleRange() != request.audibleRange()) binding.output.setRange(request.audibleRange());
            if (binding.output != null && (binding.request.gain() != request.gain()
                    || binding.request.audibleRange() != request.audibleRange())) {
                EtchedSpeakers.LOGGER.debug("[EtchedSpeakers] Speaker settings updated speaker={} gain={} range={}",
                        SpeakerRequest.describe(key), request.gain(), request.audibleRange());
            }
            binding.request = request;
        });
        update();
    }

    public void update() {
        if (closed || originalSource == 0 || speakers.size() == 0) return;
        int state = AL10.alGetSourcei(originalSource, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_STOPPED) { detach("MASTER_STOPPED"); return; }
        if (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED) return;
        long masterFrame = baseFrame + AL10.alGetSourcei(originalSource, AL11.AL_SAMPLE_OFFSET);
        if (masterFrame < pcm.startFrame() || masterFrame >= pcm.endFrame()) return;
        int before = outputCount();
        float pitch = AL10.alGetSourcef(originalSource, AL10.AL_PITCH);
        speakers.forEach((key, binding) -> {
            if (binding.failed) return;
            if (binding.output == null) {
                try {
                    binding.output = new SpeakerOutput(pcm, sampleRate, chunkFrames, key, binding.request.track().stableKey());
                    binding.output.setGain(binding.request.gain());
                    binding.output.setRange(binding.request.audibleRange());
                    EtchedSpeakers.LOGGER.debug("[EtchedSpeakers] Speaker JOIN speaker={} source={} track={} masterSequence={} masterFrame={} decoder=1",
                            SpeakerRequest.describe(key), sourceIdentity, binding.request.track().stableKey(), pcm.sequence(), masterFrame);
                } catch (IllegalStateException failure) {
                    binding.failed = true; // Avoid allocation attempts/log spam every update.
                    EtchedSpeakers.LOGGER.warn("[EtchedSpeakers] Speaker DETACH speaker={} reason=OPENAL_ALLOCATION_FAILED track={}",
                            SpeakerRequest.describe(key), mediaKey);
                    return;
                }
            }
            // Each output reads the original cursor freshly; no output is a clock for another.
            binding.output.update(baseFrame, originalSource, state == AL10.AL_PAUSED, pitch);
            if (binding.output.isClosed()) {
                binding.output = null;
                binding.failed = true;
            }
        });
        reportCountChange(before);
    }

    public void pause() {
        speakers.forEach((key, binding) -> { if (binding.output != null) binding.output.pause(); });
    }

    public void detach(String reason) {
        int before = outputCount();
        speakers.clear((key, binding) -> closeBinding(key, binding, reason));
        reportCountChange(before);
    }

    private void closeBinding(GlobalPos key, Binding binding, String reason) {
        if (binding.output != null) {
            binding.output.close();
            binding.output = null;
        }
        EtchedSpeakers.LOGGER.debug("[EtchedSpeakers] Speaker DETACH speaker={} source={} reason={} track={}",
                SpeakerRequest.describe(key), SpeakerRequest.describe(binding.request.source()), reason, mediaKey);
    }

    int outputCount() {
        int[] count = {0};
        speakers.forEach((key, binding) -> { if (binding.output != null && !binding.output.isClosed()) count[0]++; });
        return count[0];
    }

    private void reportCountChange(int before) {
        int after = outputCount();
        if (before == after) return;
        EtchedSpeakers.LOGGER.debug("[EtchedSpeakers] Master outputs changed source={} active={} track={}", sourceIdentity, after, mediaKey);
        MasterSessions.logCounts();
    }

    public void close() {
        if (closed) return;
        closed = true;
        detach("MASTER_CLOSED");
        MasterSessions.remove(owner, this);
        originalBuffers.clear();
        pcm.clear();
        originalSource = 0;
    }

    private static final class Binding {
        private SpeakerRequest request;
        private SpeakerOutput output;
        private boolean failed;

        private Binding(SpeakerRequest request) { this.request = request; }
    }
}
