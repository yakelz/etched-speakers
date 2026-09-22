package net.yakel.etchedspeakers.client.audio.sync;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.core.GlobalPos;

/** The ONLY reader of the original decoder. SpeakerOutput reads PcmWindow, never this stream. */
public final class PcmTap implements AudioStream {
    private final AudioStream decoder;
    private final AudioFormat format;
    private final int frameBytes;
    private ByteBuffer carry;
    private boolean closed;
    private boolean eof;
    private final MasterPlaybackSession session;

    public PcmTap(SoundInstance owner, SoundInstance token, GlobalPos source, AudioStream decoder) {
        this.decoder = decoder;
        format = decoder.getFormat();
        frameBytes = format.getChannels() * format.getSampleSizeInBits() / 8;
        session = new MasterPlaybackSession(owner, token, source, decoder, format);
        session.diagnostic().log("STREAM_WRAPPED", "streamIdentity=" + AudioDiagnostics.identity(decoder)
                + " tapIdentity=" + AudioDiagnostics.identity(this));
    }

    public static boolean supports(AudioFormat format) {
        return format.getSampleRate() >= 8000 && format.getSampleRate() <= 192000
                && (format.getChannels() == 1 || format.getChannels() == 2)
                && ((format.getSampleSizeInBits() == 16 && format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED))
                    || (format.getSampleSizeInBits() == 8 && format.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED)));
    }

    public MasterPlaybackSession session() { return session; }

    @Override
    public AudioFormat getFormat() { return format; }

    @Override
    public ByteBuffer read(int amount) throws IOException {
        var queue = session.queueDiagnostic();
        long previous = queue.previousReadStart(false);
        long start = queue.readStarted(false);
        ByteBuffer result = null;
        Throwable error = null;
        try {
            result = readPcm(amount);
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            session.recovery().stop();
            error = failure;
            throw failure;
        } finally {
            queue.readFinished(false, start, previous, amount, result, eof, error);
        }
    }

    private ByteBuffer readPcm(int amount) throws IOException {
        if (closed || eof) return null;
        int wanted = Math.min(amount, session.chunkFrames() * frameBytes);
        wanted -= wanted % frameBytes;
        if (wanted <= 0) return null;
        ByteBuffer result = ByteBuffer.allocateDirect(wanted);
        while (result.hasRemaining()) {
            if (carry == null || !carry.hasRemaining()) {
                carry = readDecoder(wanted);
                if (carry == null || !carry.hasRemaining()) {
                    eof = true;
                    session.decoderEof();
                    session.diagnostic().stop("EOF");
                    session.diagnostic().log("EOF", "streamIdentity=" + AudioDiagnostics.identity(decoder));
                    break;
                }
                if (carry.remaining() % frameBytes != 0) throw new IOException("Unaligned decoded PCM frame");
            }
            int length = Math.min(result.remaining(), carry.remaining());
            var part = carry.duplicate();
            part.limit(part.position() + length);
            result.put(part);
            carry.position(carry.position() + length);
        }
        result.flip();
        if (!result.hasRemaining()) return null;
        session.decoded(result);
        return result;
    }

    private ByteBuffer readDecoder(int wanted) throws IOException {
        var queue = session.queueDiagnostic();
        long previous = queue.previousReadStart(true);
        long start = queue.readStarted(true);
        ByteBuffer result = null;
        Throwable error = null;
        try {
            result = decoder.read(wanted);
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            error = failure;
            throw failure;
        } finally {
            queue.readFinished(true, start, previous, wanted, result, eof, error);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        carry = null;
        session.queueDiagnostic().closed();
        session.diagnostic().log("STREAM_CLOSE", "reason=PCM_TAP_CLOSE eof=" + eof + " caller=" + AudioDiagnostics.caller());
        AudioThreadBridge.sync(() -> session.close("PCM_TAP_CLOSE"));
        try {
            decoder.close();
            session.diagnostic().log("DECODER_CLOSE", "result=SUCCESS streamIdentity=" + AudioDiagnostics.identity(decoder));
            session.diagnostic().decoderClosed();
        } catch (IOException failure) {
            session.diagnostic().log("DECODER_CLOSE", "result=FAILED exceptionClass=" + failure.getClass().getName());
            throw failure;
        }
    }
}
