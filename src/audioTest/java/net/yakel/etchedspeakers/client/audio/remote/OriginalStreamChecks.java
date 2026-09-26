package net.yakel.etchedspeakers.client.audio.remote;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/** Uses the production worker/reader/advance code with bounded synthetic PCM. No OpenAL or game bootstrap. */
public final class OriginalStreamChecks {
    private static int checks;
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); checks++; }
    private static final class Decoder implements AudioStream {
        final int total;
        int frame, closes, reads;
        Thread readerThread;
        boolean empty, invalid, fail;
        final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        boolean blocked;
        Decoder(int total) { this.total=total; }
        public AudioFormat getFormat() { return new AudioFormat(44100,16,1,true,false); }
        public ByteBuffer read(int requested) throws IOException {
            readerThread=Thread.currentThread(); reads++;
            if(blocked) { entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new IOException("timeout"); }
                catch(InterruptedException ex) { throw new IOException(ex); } }
            if(fail) throw new IOException("test decode error");
            if(empty) return ByteBuffer.allocate(0);
            if(frame>=total) return null;
            int count=Math.min(total-frame,17); // Deliberately over-returns small requests.
            var out=ByteBuffer.allocate(count*2+(invalid?1:0));
            for(int i=0;i<count;i++) { out.putShort((short)frame++); }
            if(invalid) out.put((byte)1);
            out.flip(); return out;
        }
        public void close() { closes++; }
    }
    private static void fails(Class<? extends Throwable> type, RunnableIo work,String label) throws Exception {
        try { work.run(); } catch(Throwable ex) { check(type.isInstance(ex),label); return; }
        throw new AssertionError(label);
    }
    @FunctionalInterface private interface RunnableIo { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        var nativeDecoder=new Decoder(10000); var nativeReader=new RemoteSound.FrameReader(nativeDecoder);
        var nativeClock=new net.yakel.etchedspeakers.source.model.NativeDiscClock(2,0,0);
        long nativeFrame=StreamPreparation.submit(()->RemoteSound.preRoll(nativeReader,rate->nativeClock.target(rate,0),()->false),()->{}).get(5,TimeUnit.SECONDS);
        check(nativeFrame==4410 && nativeReader.frames==4410,"native ticks use opened decoder format in real remote pre-roll");
        check(nativeDecoder.readerThread!=Thread.currentThread(),"native decode-discard stays off caller thread");
        check(nativeReader.read(2).getShort()==(short)4410,"first audible sample follows native target, including decoder carry");
        nativeReader.close();
        var nativeCancelled=new Decoder(100); var nativeCancelledReader=new RemoteSound.FrameReader(nativeCancelled);
        fails(IOException.class,()->RemoteSound.preRoll(nativeCancelledReader,rate->40,()->true),"native preparation cancellation rejects stale work");
        check(nativeCancelled.reads==0,"cancelled native generation never decodes"); nativeCancelledReader.close();
        var decoder=new Decoder(1000); var reader=new RemoteSound.FrameReader(decoder);
        var target=new AtomicLong(5); var queries=new AtomicInteger();
        OriginalStreamPreparation.advance(reader,()->queries.incrementAndGet()>1?target.updateAndGet(v->9):5,()->44100,()->true);
        check(reader.frames==9,"advance catches moving target exactly");
        check(decoder.reads==1,"over-returned suffix is retained, not discarded");
        var tail=reader.read(2);
        check(tail.getShort()==9,"first audible frame follows pre-roll with no gap/replay");
        check(reader.frames==10,"delivery cursor includes original offset");
        reader.close(); reader.close(); check(decoder.closes==1,"idempotent close");
        check(reader.read(2)==null,"closed reader never touches decoder");
        var shortDecoder=new Decoder(8); var shortReader=new RemoteSound.FrameReader(shortDecoder);
        fails(EOFException.class,()->OriginalStreamPreparation.advance(shortReader,()->20,()->44100,()->true),"EOF beyond target rejected");
        check(shortReader.frames==8,"EOF preserves actual count without wrapping"); shortReader.close();
        var empty=new Decoder(100); empty.empty=true; var emptyReader=new RemoteSound.FrameReader(empty);
        fails(EOFException.class,()->OriginalStreamPreparation.advance(emptyReader,()->20,()->44100,()->true),"empty buffer is terminal"); emptyReader.close();
        var bad=new Decoder(100); bad.invalid=true; var badReader=new RemoteSound.FrameReader(bad);
        fails(IOException.class,()->OriginalStreamPreparation.advance(badReader,()->20,()->44100,()->true),"partial PCM frame rejected"); badReader.close();
        var wrongRate=new Decoder(100); var wrongReader=new RemoteSound.FrameReader(wrongRate);
        fails(IOException.class,()->OriginalStreamPreparation.advance(wrongReader,()->20,()->48000,()->true),"sample rate mismatch rejected");
        check(wrongRate.reads==0,"rate validation precedes decoding"); wrongReader.close();
        var error=new Decoder(100); error.fail=true; var errorReader=new RemoteSound.FrameReader(error);
        fails(IOException.class,()->OriginalStreamPreparation.advance(errorReader,()->20,()->44100,()->true),"decoder error propagated"); errorReader.close();
        var cancelled=new Decoder(100); var cancelledReader=new RemoteSound.FrameReader(cancelled);
        fails(IOException.class,()->OriginalStreamPreparation.advance(cancelledReader,()->20,()->44100,()->false),"stale task cancelled before reading");
        check(cancelled.reads==0,"cancel never starts decoder"); cancelledReader.close();
        var async=new Decoder(100); async.blocked=true; var asyncReader=new RemoteSound.FrameReader(async);
        var current=new AtomicBoolean(true);
        var future=StreamPreparation.submit(()->{
            try { OriginalStreamPreparation.advance(asyncReader,()->50,()->44100,current::get); return true; }
            finally { asyncReader.close(); }
        },()->{try {asyncReader.close();}catch(IOException ex){throw new RuntimeException(ex);}});
        check(async.entered.await(5,TimeUnit.SECONDS),"worker started");
        check(async.readerThread!=Thread.currentThread(),"decoding never runs on calling thread");
        current.set(false);
        check(async.closes==0,"cancellation does not cross-close an in-flight decoder read");
        async.release.countDown();
        fails(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS),"worker observes cancellation before handoff");
        check(async.closes==1,"worker closes cancelled decoder once");
        System.out.println("Original stream checks: "+checks+" PASS");
    }
}
