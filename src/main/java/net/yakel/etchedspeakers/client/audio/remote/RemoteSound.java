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
import java.util.function.IntToLongFunction;
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
    private static final DownloadProgressListener QUIET = new DownloadProgressListener() {
        public void progressStartRequest(Component text) {} public void progressStartDownload(float size) {}
        public void progressStagePercentage(int value) {} public void progressStartLoading() {}
        public void onSuccess() {} public void onFail() {}
    };
    private final GlobalPos sourcePos;
    private final String mediaLocation;
    private volatile boolean cancelled;
    private Prepared prepared;
    private volatile FrameReader frameReader;
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
    CompletableFuture<Void> prepare(SoundBufferLibrary loader, IntToLongFunction target, int expectedRate) {
        CompletableFuture<AudioStream> opened;
        if(gg.moonflower.etched.api.record.TrackData.isLocalSound(mediaLocation)) {
            var event=Minecraft.getInstance().getSoundManager().getSoundEvent(ResourceLocation.parse(mediaLocation));
            if(event==null) return CompletableFuture.failedFuture(new IOException("Unknown sound event"));
            opened=loader.getStream(event.getSound(random).getPath(),false).thenApply(MonoWrapper::new);
        } else {
            opened=SoundCache.getAudioStream(mediaLocation,QUIET,AudioSource.AudioFileType.FILE)
                    .thenCompose(AudioSource::openStream).thenCompose(input -> StreamPreparation.submit(() -> decode(input), ()->close(input)));
        }
        return opened.thenCompose(stream -> StreamPreparation.submit(()->{
            var reader=new FrameReader(stream);
            boolean transferred=false;
            try {
                if(!PcmTap.supports(reader.getFormat()) || expectedRate!=0 && Math.round(reader.getFormat().getSampleRate())!=expectedRate)
                    throw new IOException("Unsupported remote format/rate");
                long frame=preRoll(reader,target,()->cancelled);
                synchronized(this) {
                    if(cancelled) throw new IOException("Cancelled preparation");
                    prepared=new Prepared(reader,frame); frameReader=reader; transferred=true;
                }
                return (Void)null;
            } finally { if(!transferred) reader.close(); }
        },()->close(stream)));
    }
    long eofFrame() { var reader=frameReader; return reader!=null && reader.eof ? reader.frames : -1; }
    /** Runs exclusively on StreamPreparation workers. Same bounded path for native and Etched media. */
    static long preRoll(FrameReader reader,IntToLongFunction target,java.util.function.BooleanSupplier cancelled) throws IOException {
        long start=System.nanoTime(),frame=0;
        int rate=Math.round(reader.getFormat().getSampleRate()),chunk=Math.max(1,rate/10);
        while(true) {
            if(cancelled.getAsBoolean() || System.nanoTime()-start>30_000_000_000L) throw new IOException("Preparation cancelled/timed out");
            long wanted=target.applyAsLong(rate);
            if(wanted<0) throw new IOException("Invalid remote target");
            if(frame>=wanted) return frame;
            var data=reader.read((int)Math.min(chunk,wanted-frame)*reader.frameBytes);
            if(data==null || !data.hasRemaining()) return frame; // Real EOF; do not wrap or fabricate samples.
            frame+=data.remaining()/reader.frameBytes;
        }
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
    static final class FrameReader implements AudioStream {
        private final AudioStream decoder;
        final int frameBytes;
        private ByteBuffer carry;
        private boolean closed;
        volatile long frames;
        private volatile boolean eof;
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
                    if(carry==null || !carry.hasRemaining()) { eof=true; break; }
                    if(carry.remaining()%frameBytes!=0) throw new IOException("Unaligned remote PCM");
                }
                int n=Math.min(out.remaining(),carry.remaining()); var part=carry.duplicate();
                part.limit(part.position()+n); out.put(part); carry.position(carry.position()+n);
            }
            out.flip(); frames+=out.remaining()/frameBytes; return out.hasRemaining()?out:null;
        }
        public void close() throws IOException { if(!closed) { closed=true; carry=null; decoder.close(); } }
    }
}
