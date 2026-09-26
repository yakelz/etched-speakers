package net.yakel.etchedspeakers.client.audio.remote;

import gg.moonflower.etched.api.sound.AbstractOnlineSoundInstance;
import gg.moonflower.etched.api.sound.SoundTracker;
import gg.moonflower.etched.api.sound.StopListeningSound;
import gg.moonflower.etched.common.block.AlbumJukeboxBlock;
import gg.moonflower.etched.common.blockentity.AlbumJukeboxBlockEntity;
import gg.moonflower.etched.client.sound.EmptyAudioStream;
import gg.moonflower.etched.core.mixin.client.render.LevelRendererAccessor;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.CommonLevelAccessor;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.client.audio.sync.*;
import gg.moonflower.etched.api.record.PlayableRecord;
import net.yakel.etchedspeakers.network.RemotePayloads.Snapshot;
import net.yakel.etchedspeakers.source.model.*;
import net.yakel.etchedspeakers.source.model.CanonicalAlignment.*;

/** Client-thread ownership; only immutable Clock and cancellation flags cross to preparation workers.
 * Original Etched decoder: future -> worker -> client validation -> PcmTap -> Channel, never two readers.
 */
public final class LocalSourceSync {
    private static final Map<GlobalPos,Entry> SOURCES=new HashMap<>();
    private static final Map<SoundInstance,Binding> BINDINGS=new WeakHashMap<>();
    private static final Map<Long,Aligned> ALIGNED=new HashMap<>();
    private static boolean controlled;
    private static long lastSample;
    private static final class Entry {
        Snapshot snapshot;
        volatile Clock clock;
        long heard;
        boolean exhausted;
        Identity attempted;
        long attemptedAt;
        final DriftGate drift=new DriftGate();
        Identity identity() { var s=snapshot; return new Identity(s.source(),s.generation(),s.media(),s.slot(),s.index()); }
    }
    /** Weak-map value must not reference its key. Preparation validity does not depend on a shared decoder. */
    public static final class Binding {
        final GlobalPos source;
        final ClientLevel world;
        final String media;
        final int slot,index;
        Identity creation;
        Preparation preparation;
        RemoteSound.FrameReader waiting;
        CompletableFuture<AudioStream> result;
        synchronized void cancel() {
            if(preparation!=null) preparation.cancel();
            // waiting is published only after the worker's last read; never cross-close a busy decoder.
            if(waiting!=null) { close(waiting); waiting=null; }
            if(result!=null && !result.isDone()) result.complete(EmptyAudioStream.INSTANCE);
        }
        long offset;
        boolean ready,failed;
        Binding(GlobalPos source,ClientLevel world,String media,int slot,int index,Identity creation) {
            this.source=source; this.world=world; this.media=media; this.slot=slot; this.index=index; this.creation=creation;
        }
    }
    private record Aligned(MasterPlaybackSession master,long offset) {}
    private LocalSourceSync() {}

    public static boolean owned(GlobalPos source) {
        var e=SOURCES.get(source); return e!=null && e.snapshot.active() && e.snapshot.remoteOwned();
    }
    public static long reportedFrame(MasterPlaybackSession.Observation o) {
        var aligned=ALIGNED.get(o.id()); return o.frame()+(aligned==null?0:aligned.offset());
    }
    public static void snapshot(Snapshot s) {
        var e=SOURCES.get(s.source());
        if(!CanonicalAlignment.accepts(new Identity(s.source(),s.generation(),s.media(),s.slot(),s.index()),s.serverTick(),
                e==null?null:e.identity(),e==null?0:e.snapshot.serverTick())) return;
        if(e==null) {
            if(!s.active() || !s.remoteOwned() || SOURCES.size()>=32) return;
            e=new Entry(); SOURCES.put(s.source(),e);
        }
        boolean changed=e.snapshot==null || !e.identity().equals(new Identity(s.source(),s.generation(),s.media(),s.slot(),s.index()));
        boolean wasActive=e.snapshot!=null && e.snapshot.active();
        if(changed || !s.active()) cancel(s.source());
        e.snapshot=s; e.heard=System.nanoTime();
        e.clock=new Clock(s.frame(),s.rate(),s.paused(),e.heard,
                Math.clamp(Minecraft.getInstance().level.getGameTime()-s.serverTick(),0L,40L));
        if(changed) { e.exhausted=false; log("CANONICAL_SOURCE_VISIBLE",e,"SNAPSHOT",0); }
        if(!s.active() || !s.remoteOwned()) {
            if(wasActive) log("LOCAL_CANONICAL_RELEASE",e,s.reason(),0);
            // Grace/tracking expiry relinquishes ownership without restarting a healthy local sound.
            if(!Set.of("GRACE_EXPIRED","SOURCE_NOT_TRACKED","LEASE_EXPIRED").contains(s.reason())) stopOriginal(s.source());
        }
    }
    private static AlbumJukeboxBlockEntity album(GlobalPos source) {
        var level=Minecraft.getInstance().level;
        if(level==null || !level.dimension().equals(source.dimension())) return null;
        var chunk=level.getChunkSource().getChunkNow(source.pos().getX()>>4,source.pos().getZ()>>4);
        return chunk!=null && chunk.getBlockEntity(source.pos()) instanceof AlbumJukeboxBlockEntity a && !a.isRemoved()?a:null;
    }
    private static boolean playable(AlbumJukeboxBlockEntity a) {
        var state=a.getBlockState();
        return state.hasProperty(AlbumJukeboxBlock.POWERED) && !state.getValue(AlbumJukeboxBlock.POWERED)
                && state.getValue(AlbumJukeboxBlock.HAS_RECORD);
    }
    private static boolean valid(Entry e, AlbumJukeboxBlockEntity a) {
        if(a==null || !playable(a) || !owned(e.snapshot.source())) return false;
        var s=e.snapshot;
        if(s.slot()<0 || s.slot()>=a.getContainerSize() || s.index()<0) return false;
        var tracks=PlayableRecord.getTracks(Minecraft.getInstance().level.registryAccess(),a.getItem(s.slot()));
        if(s.index()>=tracks.size()) return false;
        var track=tracks.get(s.index());
        return track.isValid() && track.url().equals(s.location());
    }
    private static SoundInstance original(GlobalPos source) {
        return ((LevelRendererAccessor)Minecraft.getInstance().levelRenderer).getPlayingJukeboxSongs().get(source.pos());
    }
    private static void stopOriginal(GlobalPos source) {
        var sound=original(source);
        if(sound!=null) {
            if(sound instanceof StopListeningSound listener) listener.stopListening();
            Minecraft.getInstance().getSoundManager().stop(sound);
            ((LevelRendererAccessor)Minecraft.getInstance().levelRenderer).getPlayingJukeboxSongs().remove(source.pos(),sound);
        }
    }
    private static void cancel(GlobalPos source) {
        for(var item:BINDINGS.entrySet()) {
            var b=item.getValue();
            if(b.source.equals(source) && b.preparation!=null) { if(!b.ready) silence(item.getKey()); b.cancel(); }
        }
    }
    public static Binding created(GlobalPos source, SoundInstance token, AbstractOnlineSoundInstance owner) {
        var a=album(source);
        if(a==null) return null; // Vanilla activation and unrelated Etched players are untouched.
        String location=((AbstractOnlineSoundInstance.OnlineSound)owner.getSound()).getURL();
        String media=new TrackReference(gg.moonflower.etched.api.record.TrackData.isLocalSound(location)?TrackReference.Kind.SOUND_EVENT:TrackReference.Kind.URL,
                location,Optional.empty(),a.getPlayingIndex(),a.getTrack()).mediaKey();
        var e=SOURCES.get(source);
        Identity id=owned(source)?e.identity():null;
        cancel(source);
        var b=new Binding(source,Minecraft.getInstance().level,media,a.getPlayingIndex(),a.getTrack(),id);
        BINDINGS.put(token,b);
        if(id!=null) log("LOCAL_SOUND_CREATE",e,"ETCHED_ORIGINAL",0);
        return b;
    }
    /** Called with the Etched future resolved, on the client thread, before PcmTap/Channel ownership. */
    public static CompletableFuture<AudioStream> prepare(Binding b, SoundInstance token, AudioStream stream) {
        if(b==null || !owned(b.source)) return CompletableFuture.completedFuture(stream);
        var e=SOURCES.get(b.source); var id=e.identity();
        if(!valid(e,album(b.source)) || !CanonicalAlignment.matches(id,b.source,b.media,b.slot,b.index)
                || b.creation!=null && !b.creation.equals(id)) {
            b.failed=true; silence(token); close(stream);
            log("LOCAL_RECONCILE_SKIPPED",e,"MEDIA_OR_GENERATION_MISMATCH",0);
            return CompletableFuture.completedFuture(EmptyAudioStream.INSTANCE);
        }
        b.creation=id;
        var guard=new Preparation(id); b.preparation=guard;
        log("LOCAL_PREROLL_BEGIN",e,"BEFORE_CHANNEL_HANDOFF",0);
        var result=new CompletableFuture<AudioStream>(); b.result=result;
        advance(b,token,e,id,guard,new RemoteSound.FrameReader(stream),result);
        return result;
    }
    private static void advance(Binding b, SoundInstance token, Entry e, Identity id, Preparation guard,
            RemoteSound.FrameReader reader, CompletableFuture<AudioStream> result) {
        StreamPreparation.submit(()->{
            boolean transfer=false;
            try {
                OriginalStreamPreparation.advance(reader,()->e.clock.target(),()->e.clock.rate(),()->guard.current(id));
                synchronized(b) {
                    if(!guard.current(id)) throw new IOException("CANCELLED");
                    b.waiting=reader; transfer=true;
                }
                return reader;
            } finally { if(!transfer) close(reader); }
        },()->close(reader)).whenComplete((prepared,failure)->Minecraft.getInstance().execute(()->{
            boolean current=guard.current(id) && SOURCES.get(b.source)==e && owned(b.source) && e.identity().equals(id)
                    && Minecraft.getInstance().level==b.world && original(b.source)==token && valid(e,album(b.source));
            if(failure!=null || !current) {
                close(reader);
                synchronized(b) { b.waiting=null; }
                b.failed=true; silence(token);
                if(current && failure!=null) { e.exhausted=true; log("LOCAL_PREROLL_CANCEL",e,
                        failure.getCause() instanceof java.io.EOFException?"TARGET_BEYOND_EOF":"PREPARATION_FAILED",0); }
                else log("LOCAL_PREROLL_CANCEL",e,"STALE_PREPARATION",0);
                result.complete(EmptyAudioStream.INSTANCE); return;
            }
            synchronized(b) { b.waiting=null; }
            // A delayed render-thread handoff (e.g. holding the window) must catch up again off-thread.
            if(e.clock.target()-reader.frames>Math.round(reader.getFormat().getSampleRate())/5) {
                advance(b,token,e,id,guard,reader,result); return;
            }
            b.offset=reader.frames; b.ready=true;
            log("LOCAL_PREROLL_READY",e,"EXCLUSIVE_HANDOFF",b.offset-e.clock.target());
            result.complete(reader);
        }));
    }
    /** Invoked immediately before the future is completed and Channel may begin reading. */
    public static void attached(Binding b, PcmTap tap) {
        if(b!=null && b.ready) {
            ALIGNED.put(tap.session().diagnostic().id,new Aligned(tap.session(),b.offset));
            var e=SOURCES.get(b.source); if(e!=null) log("LOCAL_SOUND_ALIGNED",e,"LOCAL_PCM_REMAINS_RELATIVE",0);
        }
    }
    private static void silence(SoundInstance token) { if(token instanceof StopListeningSound s) s.stopListening(); }
    private static void close(AudioStream stream) { try { stream.close(); } catch(IOException ignored) {} }

    /** Etched auto-next must wait for the retained generation, rather than racing its own selection. */
    public static boolean autoNext(CommonLevelAccessor level, BlockPos pos) {
        var client=Minecraft.getInstance();
        if(client.level!=level) return false;
        var source=GlobalPos.of(client.level.dimension(),pos); var e=SOURCES.get(source);
        if(!owned(source)) return false;
        return true;
    }
    public static boolean stopped(SoundInstance token) {
        var b=BINDINGS.get(token);
        if(b==null || !owned(b.source)) return false;
        var e=SOURCES.get(b.source);
        if(original(b.source)==token && e.identity().equals(b.creation)) e.exhausted=true;
        return true;
    }
    /** HEAD of Etched playAlbum, only for a known active retained source. */
    public static boolean playAlbum(AlbumJukeboxBlockEntity a, CommonLevelAccessor level, BlockPos pos) {
        if(controlled || Minecraft.getInstance().level!=level) return false;
        var source=GlobalPos.of(Minecraft.getInstance().level.dimension(),pos); var e=SOURCES.get(source);
        if(!owned(source)) return false;
        if(!valid(e,a)) { cancel(source); return false; }
        var sound=original(source); var b=sound==null?null:BINDINGS.get(sound);
        if(b!=null && e.identity().equals(b.creation) && !b.failed && Minecraft.getInstance().getSoundManager().isActive(sound)) return true;
        if(!e.exhausted) recreate(e,a,"ETCHED_CREATE");
        return true;
    }
    private static void recreate(Entry e, AlbumJukeboxBlockEntity a, String reason) {
        if(!valid(e,a)) return;
        long now=System.nanoTime();
        if(e.identity().equals(e.attempted) && now-e.attemptedAt<10_000_000_000L) return;
        e.attempted=e.identity(); e.attemptedAt=now;
        e.drift.requested(now); cancel(e.snapshot.source());
        log("LOCAL_RECONCILE",e,reason,0);
        controlled=true;
        try {
            a.setPlayingIndex(e.snapshot.slot(),e.snapshot.index());
            a.setPlayingIndex(e.snapshot.slot(),e.snapshot.index());
            SoundTracker.playAlbum(a,a.getBlockState(),Minecraft.getInstance().level,e.snapshot.source().pos(),true);
        } finally { controlled=false; }
    }
    public static void tick(Minecraft client) {
        long now=System.nanoTime(); boolean sample=now-lastSample>=1_000_000_000L;
        if(sample) lastSample=now;
        ALIGNED.entrySet().removeIf(item->item.getValue().master().isClosed());
        var iterator=SOURCES.entrySet().iterator();
        while(iterator.hasNext()) {
            var item=iterator.next(); var e=item.getValue(); var source=item.getKey();
            if(now-e.heard>10_000_000_000L) { cancel(source); log("LOCAL_CANONICAL_RELEASE",e,"SNAPSHOT_LEASE",0); iterator.remove(); continue; }
            if(!owned(source)) continue;
            var a=album(source);
            if(!valid(e,a)) { cancel(source); continue; }
            if(e.exhausted) continue;
            var sound=original(source); var b=sound==null?null:BINDINGS.get(sound);
            if(sound==null || b==null || !e.identity().equals(b.creation)) {
                recreate(e,a,"LATE_SNAPSHOT_OR_NEW_GENERATION"); continue;
            }
            if(b.failed || !b.ready || !sample) continue;
            var master=MasterSessions.find(sound); var head=master==null?null:master.playhead();
            if(head!=null && e.drift.sample(head.frame()+b.offset-e.clock.target(),head.rate(),e.snapshot.paused(),now)) {
                log("LOCAL_DRIFT_DETECTED",e,"SUSTAINED_OVER_1500_MS",head.frame()+b.offset-e.clock.target());
                recreate(e,a,"CANONICAL_DRIFT");
            }
        }
    }
    public static void reset(String reason) {
        for(var item:BINDINGS.entrySet()) { if(!item.getValue().ready) silence(item.getKey()); item.getValue().cancel(); }
        SOURCES.clear(); BINDINGS.clear(); ALIGNED.clear(); controlled=false; lastSample=0;
    }
    private static void log(String event, Entry e, String reason, long delta) {
        var s=e.snapshot;
        EtchedSpeakers.LOGGER.info("[ES-LOCAL-SYNC] {} source={} generation={} media={} target={} deltaMs={} reason={}",
                event,s.source(),s.generation(),s.media(),e.clock==null?-1:e.clock.target(),s.rate()==0?0:delta*1000/s.rate(),reason);
    }
}
