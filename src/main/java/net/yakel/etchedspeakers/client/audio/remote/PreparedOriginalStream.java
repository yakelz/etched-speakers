package net.yakel.etchedspeakers.client.audio.remote;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntToLongFunction;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/** Exclusive decoder ownership: worker -> waiting -> Channel. Cancel never cross-closes a busy reader. */
final class PreparedOriginalStream implements AudioStream {
    private volatile boolean cancelled, eof;
    private RemoteSound.FrameReader reader, waiting;
    private boolean handed;
    CompletableFuture<Void> prepare(AudioStream opened,IntToLongFunction target) {
        return advance(new RemoteSound.FrameReader(opened),target);
    }
    private CompletableFuture<Void> advance(RemoteSound.FrameReader current,IntToLongFunction target) {
        return StreamPreparation.submit(()->{
            boolean transferred=false;
            try {
                int rate=Math.round(current.getFormat().getSampleRate());
                OriginalStreamPreparation.advance(current,()->target.applyAsLong(rate),()->rate,()->!cancelled);
                synchronized(this) {
                    if(cancelled) throw new IOException("CANCELLED");
                    waiting=current; transferred=true;
                }
                return (Void)null;
            } finally { if(!transferred) close(current); }
        },()->close(current));
    }
    synchronized CompletableFuture<Void> catchUp(IntToLongFunction target) {
        var current=waiting; waiting=null;
        if(current==null) return CompletableFuture.failedFuture(new IOException("NO_PREPARED_STREAM"));
        return advance(current,target);
    }
    synchronized long frame() { return waiting==null?-1:waiting.frames; }
    synchronized int rate() { return waiting==null?0:Math.round(waiting.getFormat().getSampleRate()); }
    synchronized AudioStream take() throws IOException {
        if(cancelled || waiting==null || handed) throw new IOException("STALE_HANDOFF");
        reader=waiting; waiting=null; handed=true; return this;
    }
    synchronized void cancel() {
        cancelled=true;
        if(waiting!=null) { close(waiting); waiting=null; }
        // A handed stream belongs to Channel, including its eventual close.
    }
    boolean eof() { return eof; }
    public AudioFormat getFormat() { return reader.getFormat(); }
    public ByteBuffer read(int bytes) throws IOException {
        var data=reader.read(bytes);
        if(data==null || !data.hasRemaining()) eof=true;
        return data;
    }
    public void close() throws IOException { if(reader!=null) reader.close(); }
    private static void close(AudioStream stream) { try { stream.close(); } catch(IOException ignored) {} }
}
