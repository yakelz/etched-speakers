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
import net.yakel.etchedspeakers.network.RemotePayloads.Report;
import net.neoforged.neoforge.network.PacketDistributor;
import net.yakel.etchedspeakers.source.model.*;
import net.yakel.etchedspeakers.source.model.CanonicalAlignment.*;

/** Client-thread ownership; only immutable Clock and cancellation flags cross to preparation workers.
 * Original Etched decoder: future -> worker -> client validation -> PcmTap -> Channel, never two readers.
 */
public final class LocalSourceSync {
    private static final Map<GlobalPos,Entry> SOURCES=new HashMap<>();
    private static final Map<SoundInstance,Binding> BINDINGS=new WeakHashMap<>();
    private static final Map<Long,Aligned> ALIGNED=new HashMap<>();
    private static final Map<GlobalPos,BlockTrack> BLOCK_TRACKS=new HashMap<>();
    private record BlockTrack(ClientLevel world,List<gg.moonflower.etched.api.record.TrackData> tracks,int index) {}
    private static final Map<GlobalPos,OriginalSourceKind> MUTED_STARTS=new HashMap<>();
    private static boolean controlled;
    private static long lastSample;
    private static long terminalSequence=1L<<60; // Separate namespace from real master ids; client-thread only.
    private static final class Entry {
        Snapshot snapshot;
        volatile Clock clock;
        long heard, eventFence=-1;
        boolean exhausted, volumeLost;
        OriginalRecovery recovery=new OriginalRecovery();
        PreparedEnd preparedEnd;
        Identity attempted;
        long attemptedAt;
        final DriftGate drift=new DriftGate();
        Identity identity() { var s=snapshot; return new Identity(s.source(),s.generation(),s.media(),s.slot(),s.index(),s.sourceKind()); }
    }
    private static final class PreparedEnd {
        final long id=++terminalSequence,frame;
        final int rate;
        long lastSent=Long.MIN_VALUE/2;
        boolean announced;
        PreparedEnd(long frame,int rate) { this.frame=frame; this.rate=rate; }
    }
    /** Decoder EOF evidence only, no additional reads or playable replacement stream. */
    private static final class PreparationEnd extends java.io.EOFException {
        final long frame;
        final int rate;
        PreparationEnd(long frame,int rate) { super("TARGET_BEYOND_EOF"); this.frame=frame; this.rate=rate; }
    }
    /** Weak-map value must not reference its key. Preparation validity does not depend on a shared decoder. */
    public static final class Binding {
        final GlobalPos source;
        final ClientLevel world;
        final String media;
        final int slot,index;
        final OriginalSourceKind kind;
        java.lang.ref.WeakReference<MasterPlaybackSession> master;
        long masterId;
        boolean decoderEof;
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
        Binding(GlobalPos source,ClientLevel world,String media,int slot,int index,Identity creation,OriginalSourceKind kind) {
            this.source=source; this.world=world; this.media=media; this.slot=slot; this.index=index; this.creation=creation; this.kind=kind;
        }
    }
    private static final class Aligned {
        final MasterPlaybackSession master;
        final long offset;
        final Identity identity;
        final SoundInstance token;
        MasterPlaybackSession.Observation terminal;
        long announcedMaster,lastSent=Long.MIN_VALUE/2,closedAt;
        Aligned(MasterPlaybackSession master,long offset,Identity identity,SoundInstance token) {
            this.master=master; this.offset=offset; this.identity=identity; this.token=token;
        }
        MasterPlaybackSession master() { return master; }
        long offset() { return offset; }
    }
    private LocalSourceSync() {}

    public static boolean owned(GlobalPos source) {
        var e=SOURCES.get(source); return e!=null && e.snapshot.active() && e.snapshot.remoteOwned();
    }
    private static boolean active(GlobalPos source) {
        var e=SOURCES.get(source); return e!=null && OriginalRecovery.timelineEligible(e.snapshot.active(),e.snapshot.localSource());
    }
    public static int observedIndex(SoundInstance token) { var b=BINDINGS.get(token); return b==null?-1:b.index; }
    public static int observedSlot(SoundInstance token) { var b=BINDINGS.get(token); return b==null?-1:b.slot; }
    public static void blockRecord(BlockPos pos,gg.moonflower.etched.api.record.TrackData[] tracks,int index) {
        if(controlled || Minecraft.getInstance().level==null || tracks.length>64) return;
        var level=Minecraft.getInstance().level; var source=GlobalPos.of(level.dimension(),pos);
        if(!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity)) return;
        if(BLOCK_TRACKS.size()<32 || BLOCK_TRACKS.containsKey(source)) {
            BLOCK_TRACKS.put(source,new BlockTrack(level,List.of(tracks),index));
            if(index<tracks.length && !OriginalAudio.audible()) MUTED_STARTS.put(source,OriginalSourceKind.VANILLA_ETCHED);
        }
    }
    public static void blockPacket(BlockPos pos) {
        var level=Minecraft.getInstance().level; if(level==null) return;
        var source=GlobalPos.of(level.dimension(),pos); var e=SOURCES.get(source);
        cancel(source); BLOCK_TRACKS.remove(source); MUTED_STARTS.remove(source);
        if(e!=null) e.eventFence=e.snapshot.serverTick();
        // Etched packet handler stops the old token; its auto-next callback must not race this explicit event.
        var sound=original(source); if(sound!=null) silence(sound);
    }
    public static long reportedFrame(MasterPlaybackSession.Observation o) {
        var aligned=ALIGNED.get(o.id()); return o.frame()+(aligned==null?0:aligned.offset());
    }
    /** Called only for originals that actually completed this generation's pre-roll. Never a raw recreation. */
    public static boolean report(MasterPlaybackSession.Observation o,boolean eof) {
        var a=ALIGNED.get(o.id()); var e=SOURCES.get(o.source());
        if(a==null || !owned(o.source()) || !a.identity.equals(e.identity()) || !o.media().equals(a.identity.media())
                || original(o.source())!=a.token || !valid(e,album(o.source()))) return false;
        if(eof) a.terminal=o;
        long now=System.nanoTime(); if(now-a.lastSent<1_000_000_000L) return true;
        long token=a.announcedMaster==o.id()?e.snapshot.observerToken():0;
        PacketDistributor.sendToServer(new Report(o.source(),o.media(),a.identity.slot(),a.identity.index(),o.id(),
                o.frame()+a.offset,o.rate(),o.paused(),eof,a.identity.generation(),e.snapshot.epoch(),token));
        a.lastSent=now; a.announcedMaster=o.id();
        return true;
    }
    public static void snapshot(Snapshot s) {
        var e=SOURCES.get(s.source());
        if(!CanonicalAlignment.accepts(new Identity(s.source(),s.generation(),s.media(),s.slot(),s.index(),s.sourceKind()),s.serverTick(),
                e==null?null:e.identity(),e==null?0:e.snapshot.serverTick())) return;
        if(e==null) {
            if(!s.active() || !s.localSource() || SOURCES.size()>=32) return;
            e=new Entry(); SOURCES.put(s.source(),e);
        }
        boolean changed=e.snapshot==null || !e.identity().equals(new Identity(s.source(),s.generation(),s.media(),s.slot(),s.index(),s.sourceKind()));
        boolean wasActive=e.snapshot!=null && e.snapshot.active();
        if(changed || !s.active()) cancel(s.source());
        e.snapshot=s; e.heard=System.nanoTime();
        e.clock=new Clock(s.frame(),s.rate(),s.paused(),e.heard,
                Math.clamp(Minecraft.getInstance().level.getGameTime()-s.serverTick(),0L,40L));
        if(changed) { e.eventFence=-1; e.recovery=new OriginalRecovery(); e.volumeLost=false; e.exhausted=false; e.preparedEnd=null; log("CANONICAL_SOURCE_VISIBLE",e,"SNAPSHOT",0); }
        if(!s.active()) {
            if(wasActive) log("LOCAL_CANONICAL_RELEASE",e,s.reason(),0);
            // Grace/tracking expiry relinquishes ownership without restarting a healthy local sound.
            if(!Set.of("GRACE_EXPIRED","SOURCE_NOT_TRACKED","LEASE_EXPIRED").contains(s.reason())
                    && !(s.reason().equals("EOF") && !s.remoteOwned())) stopOriginal(s.source());
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
        if(!active(e.snapshot.source()) || e.snapshot.serverTick()<=e.eventFence) return false;
        if(e.snapshot.sourceKind()==OriginalSourceKind.VANILLA_ETCHED) {
            var level=Minecraft.getInstance().level; var pos=e.snapshot.source().pos();
            if(level==null || !level.dimension().equals(e.snapshot.source().dimension())) return false;
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            return chunk!=null && chunk.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity be
                    && !be.isRemoved() && be.getBlockState().hasProperty(net.minecraft.world.level.block.JukeboxBlock.HAS_RECORD)
                    && be.getBlockState().getValue(net.minecraft.world.level.block.JukeboxBlock.HAS_RECORD)
                    && e.snapshot.index()>=0 && e.snapshot.index()<e.snapshot.sourcePlaylist().size()
                    && e.snapshot.sourcePlaylist().get(e.snapshot.index()).equals(e.snapshot.location());
        }
        if(e.snapshot.sourceKind()!=OriginalSourceKind.ALBUM_ETCHED || a==null || !playable(a)) return false;
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
        var a=album(source); var block=BLOCK_TRACKS.get(source);
        OriginalSourceKind kind=a!=null?OriginalSourceKind.ALBUM_ETCHED:OriginalSourceKind.VANILLA_ETCHED;
        var e=SOURCES.get(source);
        if(a==null && (block==null || block.world()!=Minecraft.getInstance().level) && !(controlled && active(source)
                && e.snapshot.sourceKind()==OriginalSourceKind.VANILLA_ETCHED)) return null;
        String location=((AbstractOnlineSoundInstance.OnlineSound)owner.getSound()).getURL();
        int slot=a!=null?a.getPlayingIndex():0;
        int index=a!=null?a.getTrack():controlled?e.snapshot.index():block.index();
        String media=new TrackReference(gg.moonflower.etched.api.record.TrackData.isLocalSound(location)?TrackReference.Kind.SOUND_EVENT:TrackReference.Kind.URL,
                location,Optional.empty(),slot,index).mediaKey();
        // Normal local initial start remains Etched's path. Only a controlled recovery or owned canonical source pre-rolls.
        Identity id=active(source) && (controlled || owned(source))?e.identity():null;
        cancel(source);
        var b=new Binding(source,Minecraft.getInstance().level,media,slot,index,id,kind);
        BINDINGS.put(token,b);
        if(id!=null) log("LOCAL_SOUND_CREATE",e,"ETCHED_ORIGINAL",0);
        return b;
    }
    /** Called with the Etched future resolved, on the client thread, before PcmTap/Channel ownership. */
    public static CompletableFuture<AudioStream> prepare(Binding b, SoundInstance token, AudioStream stream) {
        if(b==null || !active(b.source) || b.creation==null && !owned(b.source)) return CompletableFuture.completedFuture(stream);
        var e=SOURCES.get(b.source); var id=e.identity();
        if(b.kind!=id.kind() || !OriginalAudio.audible() || original(b.source)!=token || !valid(e,album(b.source)) || !CanonicalAlignment.matches(id,b.source,b.media,b.slot,b.index)
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
                try { OriginalStreamPreparation.advance(reader,()->e.clock.target(),()->e.clock.rate(),()->guard.current(id)); }
                catch(java.io.EOFException end) { throw new PreparationEnd(reader.frames,Math.round(reader.getFormat().getSampleRate())); }
                synchronized(b) {
                    if(!guard.current(id)) throw new IOException("CANCELLED");
                    b.waiting=reader; transfer=true;
                }
                return reader;
            } finally { if(!transfer) close(reader); }
        },()->close(reader)).whenComplete((prepared,failure)->Minecraft.getInstance().execute(()->{
            boolean current=guard.current(id) && SOURCES.get(b.source)==e && active(b.source) && e.identity().equals(id)
                    && Minecraft.getInstance().level==b.world && original(b.source)==token && valid(e,album(b.source));
            if(failure!=null || !current) {
                close(reader);
                synchronized(b) { b.waiting=null; }
                b.failed=true; silence(token);
                if(current && failure!=null) {
                    if(failure.getCause() instanceof PreparationEnd end) e.preparedEnd=new PreparedEnd(end.frame,end.rate);
                    if(failure.getCause() instanceof java.io.EOFException) { e.exhausted=true; e.recovery.terminal(); }
                    log("LOCAL_PREROLL_CANCEL",e,
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
        if(b!=null) { b.master=new java.lang.ref.WeakReference<>(tap.session()); b.masterId=tap.session().diagnostic().id; }
        if(b!=null && b.ready) {
            ALIGNED.put(tap.session().diagnostic().id,new Aligned(tap.session(),b.offset,b.creation,original(b.source)));
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
    public static void decoderEnded(MasterPlaybackSession.Observation observation) {
        for(var b:BINDINGS.values()) if(b.masterId==observation.id()) b.decoderEof=true;
    }
    public static boolean stopped(SoundInstance token) {
        var b=BINDINGS.get(token);
        if(b==null) return false;
        var master=b.master==null?null:b.master.get();
        boolean eof=b.decoderEof || master!=null && master.hasDecoderEof();
        var e=SOURCES.get(b.source);
        if(active(b.source) && original(b.source)==token && b.kind==e.snapshot.sourceKind()
                && CanonicalAlignment.matches(e.identity(),b.source,b.media,b.slot,b.index)
                && (b.creation==null || e.identity().equals(b.creation))) {
            if(eof) { e.exhausted=true; e.recovery.terminal(); }
            else log("ORIGINAL_CHANNEL_LOST",e,e.volumeLost?"VOLUME_ZERO":"NON_TERMINAL_CHANNEL_LOSS",0);
        }
        // A channel loss is not Etched's end-of-track signal, even before the first server snapshot.
        return OriginalRecovery.suppressEnd(owned(b.source),eof);
    }
    /** HEAD of Etched playAlbum, only for a known active retained source. */
    public static boolean playAlbum(AlbumJukeboxBlockEntity a, CommonLevelAccessor level, BlockPos pos) {
        if(controlled || Minecraft.getInstance().level!=level) return false;
        var source=GlobalPos.of(Minecraft.getInstance().level.dimension(),pos); var e=SOURCES.get(source);
        if(!OriginalAudio.audible() && playable(a) && (MUTED_STARTS.size()<32 || MUTED_STARTS.containsKey(source)))
            MUTED_STARTS.put(source,OriginalSourceKind.ALBUM_ETCHED);
        if(!owned(source)) return false;
        if(!valid(e,a)) { cancel(source); return false; }
        var sound=original(source); var b=sound==null?null:BINDINGS.get(sound);
        if(b!=null && e.identity().equals(b.creation) && !b.failed && !e.volumeLost && OriginalAudio.healthy(sound)) return true;
        if(!e.exhausted) recreate(e,a,"ETCHED_CREATE");
        return true;
    }
    private static void recreate(Entry e, AlbumJukeboxBlockEntity a, String reason) {
        if(!valid(e,a) || !near(e) || !OriginalAudio.audible()) return;
        long now=System.nanoTime();
        e.recovery.volume(true);
        if(!e.recovery.begin(now)) return;
        e.attempted=e.identity(); e.attemptedAt=now;
        e.drift.requested(now); cancel(e.snapshot.source()); e.volumeLost=false;
        log("LOCAL_RECONCILE",e,reason,0);
        controlled=true;
        try {
            if(e.snapshot.sourceKind()==OriginalSourceKind.ALBUM_ETCHED) {
                a.setPlayingIndex(e.snapshot.slot(),e.snapshot.index());
                a.setPlayingIndex(e.snapshot.slot(),e.snapshot.index());
                SoundTracker.playAlbum(a,a.getBlockState(),Minecraft.getInstance().level,e.snapshot.source().pos(),true);
            } else {
                stopOriginal(e.snapshot.source());
                var tracks=e.snapshot.sourcePlaylist().stream().map(url->new gg.moonflower.etched.api.record.TrackData(url,"",
                        net.minecraft.network.chat.Component.literal("Record"))).toArray(gg.moonflower.etched.api.record.TrackData[]::new);
                SoundTracker.playBlockRecord(e.snapshot.source().pos(),tracks,e.snapshot.index());
            }
        } finally { controlled=false; }
    }
    public static void tick(Minecraft client) {
        if(OriginalAudio.audible()) {
            var pending=new HashMap<>(MUTED_STARTS); MUTED_STARTS.clear();
            for(var item:pending.entrySet()) {
                var source=item.getKey(); var e=SOURCES.get(source);
                if(e!=null && active(source) && e.snapshot.rate()>0 && e.snapshot.serverTick()>e.eventFence) continue;
                if(client.player==null || !client.level.dimension().equals(source.dimension())
                        || client.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(source.pos()))>64*64) continue;
                var chunk=client.level.getChunkSource().getChunkNow(source.pos().getX()>>4,source.pos().getZ()>>4);
                if(chunk==null || OriginalAudio.healthy(original(source))) continue;
                if(item.getValue()==OriginalSourceKind.ALBUM_ETCHED) {
                    var a=album(source);
                    if(a!=null && playable(a)) SoundTracker.playAlbum(a,a.getBlockState(),client.level,source.pos(),true);
                } else {
                    var block=BLOCK_TRACKS.get(source);
                    if(block!=null && block.world()==client.level && chunk.getBlockEntity(source.pos()) instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity be
                            && be.getBlockState().getValue(net.minecraft.world.level.block.JukeboxBlock.HAS_RECORD))
                        SoundTracker.playBlockRecord(source.pos(),block.tracks().toArray(gg.moonflower.etched.api.record.TrackData[]::new),block.index());
                }
            }
        }
        BLOCK_TRACKS.entrySet().removeIf(item->item.getValue().world()!=client.level || client.level.getChunkSource().getChunkNow(item.getKey().pos().getX()>>4,item.getKey().pos().getZ()>>4)==null);
        long now=System.nanoTime(); boolean sample=now-lastSample>=1_000_000_000L;
        if(sample) lastSample=now;
        var alignedIterator=ALIGNED.entrySet().iterator();
        while(alignedIterator.hasNext()) {
            var a=alignedIterator.next().getValue();
            if(a.master.isClosed() && a.closedAt==0) a.closedAt=now;
            // EOF can arrive before assignment. Retain and retry terminal READY/progress for this generation.
            if(a.terminal!=null) {
                if(!report(a.terminal,true) || a.closedAt!=0 && now-a.closedAt>120_000_000_000L) alignedIterator.remove();
            } else if(a.closedAt!=0 && now-a.closedAt>5_000_000_000L) alignedIterator.remove();
        }
        var iterator=SOURCES.entrySet().iterator();
        while(iterator.hasNext()) {
            var item=iterator.next(); var e=item.getValue(); var source=item.getKey();
            if(now-e.heard>10_000_000_000L) { cancel(source); log("LOCAL_CANONICAL_RELEASE",e,"SNAPSHOT_LEASE",0); iterator.remove(); continue; }
            if(!active(source)) continue;
            var a=album(source);
            if(!valid(e,a)) { cancel(source); continue; }
            e.recovery.volume(OriginalAudio.audible());
            if(e.exhausted) {
                var end=e.preparedEnd;
                if(end!=null && owned(source) && now-end.lastSent>=1_000_000_000L) {
                    var s=e.snapshot;
                    PacketDistributor.sendToServer(new Report(source,s.media(),s.slot(),s.index(),end.id,end.frame,end.rate,
                            false,true,s.generation(),s.epoch(),end.announced?s.observerToken():0));
                    end.announced=true; end.lastSent=now;
                }
                continue;
            }
            if(!OriginalAudio.audible() || !near(e)) continue;
            var sound=original(source); var b=sound==null?null:BINDINGS.get(sound);
            // Observed local starts are kept, including their normal first decoder and natural sequencing.
            if(!owned(source) && !e.volumeLost && OriginalAudio.healthy(sound) && (b==null || !b.failed
                    && b.kind==e.snapshot.sourceKind() && CanonicalAlignment.matches(e.identity(),b.source,b.media,b.slot,b.index))) continue;
            if(sound==null || b==null || !e.identity().equals(b.creation) || e.volumeLost) {
                recreate(e,a,"LATE_SNAPSHOT_OR_NEW_GENERATION"); continue;
            }
            if(b.failed || !OriginalAudio.healthy(sound) && now-e.attemptedAt>2_000_000_000L) {
                recreate(e,a,"ORIGINAL_CHANNEL_MISSING"); continue;
            }
            if(!b.ready || !sample) continue;
            if(OriginalAudio.healthy(sound)) e.recovery.healthy();
            var master=MasterSessions.find(sound); var head=master==null?null:master.playhead();
            if(head!=null && e.drift.sample(head.frame()+b.offset-e.clock.target(),head.rate(),e.snapshot.paused(),now)) {
                log("LOCAL_DRIFT_DETECTED",e,"SUSTAINED_OVER_1500_MS",head.frame()+b.offset-e.clock.target());
                recreate(e,a,"CANONICAL_DRIFT");
            }
        }
    }
    private static boolean near(Entry e) {
        var c=Minecraft.getInstance();
        return c.player!=null && c.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(e.snapshot.source().pos()))<=64*64;
    }
    public static void volumeChanged(boolean audible) {
        for(var e:SOURCES.values()) {
            e.recovery.volume(audible);
            if(!audible) { e.volumeLost=true; cancel(e.snapshot.source()); }
        }
    }
    public static void reset(String reason) {
        for(var item:BINDINGS.entrySet()) { if(!item.getValue().ready) silence(item.getKey()); item.getValue().cancel(); }
        SOURCES.clear(); BINDINGS.clear(); ALIGNED.clear(); BLOCK_TRACKS.clear(); MUTED_STARTS.clear(); controlled=false; lastSample=0;
    }
    private static void log(String event, Entry e, String reason, long delta) {
        var s=e.snapshot;
        EtchedSpeakers.LOGGER.info("[ES-LOCAL-SYNC] {} source={} generation={} media={} target={} deltaMs={} reason={} sourceKind={} remoteOwned={}",
                event,s.source(),s.generation(),s.media(),e.clock==null?-1:e.clock.target(),s.rate()==0?0:delta*1000/s.rate(),reason,s.sourceKind(),s.remoteOwned());
    }
}
