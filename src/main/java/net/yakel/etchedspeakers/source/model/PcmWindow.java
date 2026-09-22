package net.yakel.etchedspeakers.source.model;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Bounded mono PCM window indexed by absolute sample frames. Confined to the sound thread. */
public final class PcmWindow {
    private final byte[] bytes;
    private final int sampleBytes;
    private final int capacityFrames;
    private long endFrame;
    private long origin;
    private long sequence;

    public PcmWindow(int capacityFrames, int sampleBytes) {
        if (capacityFrames <= 0 || (sampleBytes != 1 && sampleBytes != 2)) throw new IllegalArgumentException("Invalid PCM capacity/format");
        this.capacityFrames = capacityFrames;
        this.sampleBytes = sampleBytes;
        bytes = new byte[Math.multiplyExact(capacityFrames, sampleBytes)];
    }

    /** Copies the first channel without changing the original buffer's position/limit. */
    public void append(ByteBuffer pcm, int channels) {
        if (channels < 1 || channels > 2 || pcm.remaining() % (channels * sampleBytes) != 0) {
            throw new IllegalArgumentException("Unaligned PCM");
        }
        int count = pcm.remaining() / (channels * sampleBytes);
        int first = Math.max(0, count - capacityFrames);
        for (int frame = first; frame < count; frame++) {
            int dest = (int) ((endFrame + frame) % capacityFrames) * sampleBytes;
            int src = pcm.position() + frame * channels * sampleBytes;
            for (int b = 0; b < sampleBytes; b++) bytes[dest + b] = pcm.get(src + b);
        }
        endFrame += count;
        if (count > 0) sequence++;
    }

    /** Returns copied frames. A stale/future cursor is rejected, never silently rewound. */
    public int copy(long fromFrame, int maxFrames, ByteBuffer target) {
        if (fromFrame < startFrame() || fromFrame > endFrame || maxFrames < 0) throw new IllegalArgumentException("PCM cursor outside window");
        int frames = (int) Math.min(Math.min(endFrame - fromFrame, maxFrames), target.remaining() / sampleBytes);
        int offset = (int) (fromFrame % capacityFrames) * sampleBytes;
        int length = frames * sampleBytes;
        int first = Math.min(length, bytes.length - offset);
        target.put(bytes, offset, first);
        if (first < length) target.put(bytes, 0, length - first);
        return frames;
    }

    public long startFrame() { return Math.max(origin, endFrame - capacityFrames); }
    public long endFrame() { return endFrame; }
    public long sequence() { return sequence; }
    public int capacityFrames() { return capacityFrames; }
    public int sampleBytes() { return sampleBytes; }

    public void clear() {
        Arrays.fill(bytes, (byte) 0);
        endFrame = 0;
        sequence = 0;
        origin = 0;
    }

    /** Empty window at a prepared remote decoder cursor. Capacity and read-ahead remain unchanged. */
    public void startAt(long frame) {
        if(frame < 0 || sequence != 0 || endFrame != 0) throw new IllegalStateException("Nonempty PCM origin");
        origin = endFrame = frame;
    }
}
