package net.yakel.etchedspeakers.client.audio.remote;

import gg.moonflower.etched.api.sound.AbstractOnlineSoundInstance.OnlineSound;
import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.api.sound.stream.MonoWrapper;
import gg.moonflower.etched.api.sound.stream.RawAudioStream;
import gg.moonflower.etched.api.util.*;
import gg.moonflower.etched.client.sound.SoundCache;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.client.sounds.*;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.yakel.etchedspeakers.client.audio.sync.PcmTap;

/** Silent original-style clock channel. One decoder is handed from pre-roll worker to sound thread. */
final class RemoteSound extends AbstractSoundInstance {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r->{var t=new Thread(r,"EtchedSpeakers preroll");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    private static final DownloadProgressListener QUIET = new DownloadProgressListener() {
        public void progressStartRequest(Component text) {} public void progressStartDownload(float size) {}
        public void progressStagePercentage(int value) {} public void progressStartLoading() {}
        public void onSuccess() {} public void onFail() {}
    };
    private final GlobalPos sourcePos;
    private final String mediaLocation;
    private volatile boolean cancelled;
    private Prepared prepared;
    private PcmTap tap;
    private boolean handedToChannel;

    RemoteSound(GlobalPos source, String location) {
        // ChannelMixin mutes only this clock at AL level. Keep engine volume positive so category updates
        // don't stop it; speaker outputs independently apply RECORDS and per-speaker gain.
        super(ResourceLocation.fromNamespaceAndPath("etchedspeakers","remote_clock"),SoundSource.MASTER,SoundInstance.createUnseededRandom());
        this.sourcePos=source; this.mediaLocation=location;
        this.volume=1; this.relative=true; this.attenuation=Attenuation.NONE;
    }
    @Override public boolean canStartSilent() { return true; }
    @Override public WeighedSoundEvents resolve(SoundManager manager) {
        var events=new WeighedSoundEvents(getLocation(),null);
        sound=new OnlineSound(getLocation(),mediaLocation,16,QUIET,AudioSource.AudioFileType.FILE,false);
        events.addSound(sound); return events;
    }
    @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary loader, Sound sound, boolean looping) {
        synchronized(this) {
            if(cancelled || tap==null) return CompletableFuture.failedFuture(new IOException("Remote stream cancelled"));
            handedToChannel=true;
            return CompletableFuture.completedFuture(tap);
        }
    }
    CompletableFuture<Void> prepare(SoundBufferLibrary loader, LongSupplier target, int expectedRate) {
        CompletableFuture<AudioStream> opened;
        if(gg.moonflower.etched.api.record.TrackData.isLocalSound(mediaLocation)) {
            var event=Minecraft.getInstance().getSoundManager().getSoundEvent(ResourceLocation.parse(mediaLocation));
            if(event==null) return CompletableFuture.failedFuture(new IOException("Unknown sound event"));
            opened=loader.getStream(event.getSound(random).getPath(),false).thenApply(MonoWrapper::new);
        } else {
            opened=SoundCache.getAudioStream(mediaLocation,QUIET,AudioSource.AudioFileType.FILE)
                    .thenCompose(AudioSource::openStream).thenCompose(input -> submit(() -> decode(input), ()->close(input)));
        }
        return opened.thenCompose(stream -> submit(()->{
            var reader=new FrameReader(stream);
            boolean transferred=false;
            try {
                if(!PcmTap.supports(reader.getFormat()) || Math.round(reader.getFormat().getSampleRate())!=expectedRate)
                    throw new IOException("Unsupported remote format/rate");
                long start=System.nanoTime(); long frame=0;
                int chunk=Math.max(1,expectedRate/10);
                while(true) {
                    if(cancelled || System.nanoTime()-start>30_000_000_000L) throw new IOException("Preparation cancelled/timed out");
                    long wanted=target.getAsLong();
                    if(wanted<0) throw new IOException("Invalid remote target");
                    if(frame>=wanted) break;
                    var data=reader.read((int)Math.min(chunk,wanted-frame)*reader.frameBytes);
                    if(data==null || !data.hasRemaining()) throw new EOFException("Remote target beyond media");
                    frame+=data.remaining()/reader.frameBytes;
                }
                synchronized(this) {
                    if(cancelled) throw new IOException("Cancelled preparation");
                    prepared=new Prepared(reader,frame); transferred=true;
                }
                return (Void)null;
            } finally { if(!transferred) reader.close(); }
        },()->close(stream)));
    }
    synchronized PcmTap activate() {
        if(cancelled || prepared==null) return null;
        tap=new PcmTap(this,this,sourcePos,prepared.stream);
        tap.session().prepareRemote(prepared.frame);
        prepared=null;
        return tap;
    }
    synchronized void cancel() {
        if(cancelled) return;
        cancelled=true;
        if(prepared!=null) { close(prepared.stream); prepared=null; }
        // Once handed off, Minecraft Channel owns close; never close its decoder from this thread.
        if(!handedToChannel && tap!=null) { close(tap); tap=null; }
    }
    private static <T> CompletableFuture<T> submit(Callable<T> task, Runnable rejectedCleanup) {
        try { return CompletableFuture.supplyAsync(()->{
            try { return task.call(); } catch(Exception e) { throw new CompletionException(e); }
        },WORKERS); }
        catch(RejectedExecutionException e) { rejectedCleanup.run(); return CompletableFuture.failedFuture(e); }
    }
    private static AudioStream decode(InputStream input) throws Exception {
        var is=new BufferedInputStream(input); is.mark(8192);
        try {
            try { return new MonoWrapper(new JOrbisAudioStream(is)); } catch(Exception ogg) { is.reset(); }
            try { var wav=WaveDataReader.getAudioInputStream(is); return new MonoWrapper(new RawAudioStream(wav.getFormat(),wav)); }
            catch(Exception wav) { is.reset(); }
            var mp3=new Mp3InputStream(is); return new MonoWrapper(new RawAudioStream(mp3.getFormat(),mp3));
        } catch(Exception e) { close(is); throw e; }
    }
    private static void close(AutoCloseable resource) { try { resource.close(); } catch(Exception ignored) {} }
    private record Prepared(FrameReader stream,long frame) {}

    /** Decoder may return more bytes than requested. Preserve the unused suffix across the handoff. */
    private static final class FrameReader implements AudioStream {
        private final AudioStream decoder;
        private final int frameBytes;
        private ByteBuffer carry;
        private boolean closed;
        FrameReader(AudioStream decoder) {
            this.decoder=decoder; var f=decoder.getFormat(); frameBytes=f.getChannels()*f.getSampleSizeInBits()/8;
        }
        public AudioFormat getFormat() { return decoder.getFormat(); }
        public ByteBuffer read(int requested) throws IOException {
            if(closed) return null;
            int wanted=requested-requested%frameBytes; if(wanted<=0) return null;
            var out=ByteBuffer.allocateDirect(wanted);
            while(out.hasRemaining()) {
                if(carry==null || !carry.hasRemaining()) {
                    carry=decoder.read(wanted);
                    if(carry==null || !carry.hasRemaining()) break;
                    if(carry.remaining()%frameBytes!=0) throw new IOException("Unaligned remote PCM");
                }
                int n=Math.min(out.remaining(),carry.remaining()); var part=carry.duplicate();
                part.limit(part.position()+n); out.put(part); carry.position(carry.position()+n);
            }
            out.flip(); return out.hasRemaining()?out:null;
        }
        public void close() throws IOException { if(!closed) { closed=true; carry=null; decoder.close(); } }
    }
}
