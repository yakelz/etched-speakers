package net.yakel.etchedspeakers.client.audio.remote;

import java.io.EOFException;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import net.yakel.etchedspeakers.client.audio.sync.PcmTap;

/** Worker-only decode/discard of an original decoder that no Channel has received yet. */
final class OriginalStreamPreparation {
    private OriginalStreamPreparation() {}
    static void advance(RemoteSound.FrameReader reader, LongSupplier target, IntSupplier expectedRate,
            BooleanSupplier current) throws IOException {
        int rate=Math.round(reader.getFormat().getSampleRate());
        if(!PcmTap.supports(reader.getFormat())) throw new IOException("FORMAT_MISMATCH");
        long start=System.nanoTime();
        while(true) {
            if(!current.getAsBoolean() || System.nanoTime()-start>30_000_000_000L) throw new IOException("CANCELLED_OR_TIMEOUT");
            int expected=expectedRate.getAsInt();
            if(expected!=0 && expected!=rate) throw new IOException("FORMAT_MISMATCH");
            long wanted=target.getAsLong();
            if(wanted<0) throw new IOException("INVALID_TARGET");
            if(reader.frames>=wanted) return;
            var data=reader.read((int)Math.min(Math.max(1,rate/10),wanted-reader.frames)*reader.frameBytes);
            if(data==null || !data.hasRemaining()) throw new EOFException("TARGET_BEYOND_EOF");
        }
    }
}
