package net.yakel.etchedspeakers.client.audio.remote;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.client.audio.sync.*;
import net.yakel.etchedspeakers.client.mixin.*;
import net.yakel.etchedspeakers.network.RemotePayloads;
import net.yakel.etchedspeakers.network.RemotePayloads.*;
import net.yakel.etchedspeakers.source.model.*;

/** Client-thread control, immutable clock published to pre-roll worker. No local source BE required. */
public final class RemotePlayback {
    private static final Map<GlobalPos, Entry> ENTRIES=new HashMap<>();
    private static final Map<GlobalPos,BlockPos> INTEREST=new HashMap<>();
    private static final Set<BlockPos> SPEAKERS=new HashSet<>();
    private static Set<BlockPos> sentSpeakers=Set.of();
    private static long lastInterest=-40;
    private static UUID epoch=UUID.randomUUID();
    private static ClientLevel level;
    private static int untilInterest;
    private static long ticks;
    private static final class Entry {
        Snapshot snapshot;
        volatile Clock clock;
        RemoteSound sound;
        MasterPlaybackSession master;
        long heardAt, relevantAt, preparedAt;
        boolean failed, remoteLatched, remoteLatchedReady;
        int driftSamples;
    }
    private record Clock(long frame,int rate,boolean paused,long receivedNano,long delayTicks) {
        long target() {
            long elapsed=Math.max(0,System.nanoTime()-receivedNano);
            long projected=RemoteTimeline.project(frame,rate,delayTicks,paused);
            return Math.min(RemoteTimeline.MAX_SECONDS*rate,projected+(paused?0:elapsed/1_000_000*rate/1000));
        }
    }
    public static void tick(Minecraft client) {
        RemotePayloads.clientReceiver=RemotePlayback::snapshot;
        if(level!=client.level) { reset("LEVEL_CHANGE"); level=client.level; }
        if(level==null || client.player==null || client.isPaused()) return;
        ticks++; INTEREST.clear(); SPEAKERS.clear(); PlaybackObserver.tick(client);
        var iterator=ENTRIES.entrySet().iterator();
        while(iterator.hasNext()) {
            var item=iterator.next(); var e=item.getValue();
            if(ticks-e.heardAt>120 || ticks-e.relevantAt>100) { close(item.getKey(),e,"LEASE_EXPIRED"); iterator.remove(); }
            else if(client.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER)<=0) {
                close(item.getKey(),e,"MASTER_MUTED"); e.failed=false;
            }
            else if(e.master!=null && e.master.isClosed()) {
                // Do not implement automatic underrun recovery in the remote-source stage.
                e.failed=true; close(item.getKey(),e,"CHANNEL_ENDED");
            }
        }
    }
    public static void interested(GlobalPos source, BlockPos speaker) {
        if(SPEAKERS.size()<32 || SPEAKERS.contains(speaker)) {
            INTEREST.put(source,speaker); SPEAKERS.add(speaker);
        }
        var e=ENTRIES.get(source); if(e!=null) e.relevantAt=ticks;
    }
    public static void endTick(Minecraft client) {
        if(level==null || client.player==null) return;
        if(untilInterest--<=0 || !sentSpeakers.equals(SPEAKERS) && ticks-lastInterest>=5) {
            untilInterest=39;
            lastInterest=ticks; sentSpeakers=Set.copyOf(SPEAKERS);
            PacketDistributor.sendToServer(new Interest(epoch,level.dimension().location(),List.copyOf(SPEAKERS)));
        }
    }
    public static TrackReference track(GlobalPos source) {
        var e=ENTRIES.get(source); if(e==null || e.snapshot==null || !e.snapshot.active()) return null;
        var s=e.snapshot;
        return new TrackReference(gg.moonflower.etched.api.record.TrackData.isLocalSound(s.location())?TrackReference.Kind.SOUND_EVENT:TrackReference.Kind.URL,
                s.location(),Optional.empty(),s.slot(),s.index());
    }
    public static boolean knownStopped(GlobalPos source) { var e=ENTRIES.get(source); return e!=null && e.snapshot!=null && !e.snapshot.active(); }
    public static MasterPlaybackSession choose(GlobalPos source, MasterPlaybackSession local, boolean sourceAvailable) {
        var client=Minecraft.getInstance();
        boolean integrated=client.hasSingleplayerServer();
        if(local!=null && local.isClosed()) local=null;
        // Integrated playback already has the real clock. Observer distance/lease must not replace or stop it.
        if(integrated && RemotePlaybackPolicy.keepLocal(true,local!=null,false,sourceAvailable,0)) {
            var existing=ENTRIES.get(source);
            if(existing!=null && existing.sound!=null) close(source,existing,"LOCAL_AVAILABLE");
            return local;
        }
        var e=ENTRIES.get(source); if(e==null || e.snapshot==null) return local;
        if(!e.snapshot.active()) return null;
        if(local!=null && !local.mediaKey().equals(e.snapshot.media())) local=null;
        e.relevantAt=ticks;
        if(e.master!=null && !e.master.isClosed()) return MasterSessions.find(e.sound)==e.master?e.master:null;
        boolean needsRemote=!RemotePlaybackPolicy.keepLocal(integrated,local!=null,e.remoteLatched,sourceAvailable,
                client.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(source.pos())));
        if(!needsRemote) return local;
        e.remoteLatched=true;
        if(e.sound==null && !e.failed && client.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER)>0) prepare(source,e);
        // Preserve an existing current timeline while the asynchronous pre-roll catches up.
        var cursor=local==null?null:local.playhead();
        return cursor!=null && Math.abs(cursor.frame()-e.clock.target())<e.snapshot.rate()
                && !e.remoteLatchedReady ? local : null;
    }
    private static void snapshot(Snapshot s) {
        var client=Minecraft.getInstance();
        if(level==null || client.level!=level || !s.epoch().equals(epoch) || !s.source().dimension().equals(level.dimension())
                || !INTEREST.containsKey(s.source()) || !RemoteTimeline.valid(s.frame(),s.rate()) || s.generation()<=0) return;
        if(s.active()) {
            if(!gg.moonflower.etched.api.record.TrackData.isValidURL(s.location())) return;
            var track=new TrackReference(gg.moonflower.etched.api.record.TrackData.isLocalSound(s.location())?TrackReference.Kind.SOUND_EVENT:TrackReference.Kind.URL,
                    s.location(),Optional.empty(),-1,-1);
            if(!track.mediaKey().equals(s.media()) || s.slot() < -1 || s.index() < -1) return;
        }
        var e=ENTRIES.computeIfAbsent(s.source(),ignored->new Entry());
        if(e.snapshot!=null && !RemoteTimeline.acceptsSnapshot(s.generation(),s.serverTick(),e.snapshot.generation(),e.snapshot.serverTick())) return;
        if(e.snapshot!=null && e.snapshot.generation()!=s.generation()) { close(s.source(),e,"GENERATION_CHANGED"); e.failed=false; }
        e.snapshot=s; e.heardAt=e.relevantAt=ticks;
        e.clock=new Clock(s.frame(),s.rate(),s.paused(),System.nanoTime(),Math.clamp(level.getGameTime()-s.serverTick(),0L,40L));
        if(!s.active()) close(s.source(),e,s.reason());
        else if(e.sound!=null && e.master!=null && !e.master.isClosed()) {
            var engine=((SoundManagerAccessor)client.getSoundManager()).etchedspeakers$getEngine();
            var handle=((SoundEngineAccessor)engine).etchedspeakers$getChannels().get(e.sound);
            if(handle!=null) handle.execute(channel->{ if(s.paused()) channel.pause(); else channel.unpause(); });
            var cursor=e.master.playhead();
            e.driftSamples=cursor!=null && !s.paused() && Math.abs(cursor.frame()-e.clock.target())>s.rate()*3L ? e.driftSamples+1:0;
            if(e.driftSamples>=3 && ticks-e.preparedAt>200) {
                close(s.source(),e,"CANONICAL_DRIFT"); e.driftSamples=0; prepare(s.source(),e);
            }
        }
    }
    private static void prepare(GlobalPos source, Entry e) {
        var client=Minecraft.getInstance(); var world=level; var session=e.snapshot;
        var sound=new RemoteSound(source,session.location()); e.sound=sound; e.preparedAt=ticks;
        sound.resolve(client.getSoundManager());
        log("REMOTE_MASTER_PREPARE",source,e,"BOOTSTRAP");
        var engine=((SoundManagerAccessor)client.getSoundManager()).etchedspeakers$getEngine();
        var buffers=((SoundEngineAccessor)engine).etchedspeakers$getBuffers();
        sound.prepare(buffers,()->e.clock.target(),session.rate()).whenComplete((ignored,failure)->client.execute(()->{
            if(failure!=null || level!=world || ENTRIES.get(source)!=e || e.sound!=sound || !e.snapshot.active()
                    || e.snapshot.generation()!=session.generation()) {
                sound.cancel();
                if(e.sound==sound) { e.sound=null; e.failed=true; log("REMOTE_MASTER_CLOSE",source,e,failure==null?"CANCELLED":"PREPARE_FAILED"); }
                return;
            }
            var tap=sound.activate(); if(tap==null) return;
            e.master=tap.session(); e.remoteLatchedReady=true;
            client.getSoundManager().play(sound);
            var handle=((SoundEngineAccessor)engine).etchedspeakers$getChannels().get(sound);
            if(handle==null) { e.failed=true; close(source,e,"CHANNEL_NOT_ALLOCATED"); return; }
            if(e.snapshot.paused()) handle.execute(channel->channel.pause());
            log("REMOTE_MASTER_READY",source,e,"CURRENT_FRAME");
        }));
    }
    private static void close(GlobalPos source, Entry e, String reason) {
        if(e.sound!=null) {
            Minecraft.getInstance().getSoundManager().stop(e.sound); e.sound.cancel(); e.sound=null;
            log("REMOTE_MASTER_CLOSE",source,e,reason);
        }
        e.master=null;
    }
    public static void reset(String reason) {
        ENTRIES.forEach((s,e)->close(s,e,reason)); ENTRIES.clear(); INTEREST.clear(); SPEAKERS.clear();
        sentSpeakers=Set.of(); lastInterest=-40;
        PlaybackObserver.clear(); epoch=UUID.randomUUID(); untilInterest=0; ticks=0; level=null;
    }
    private static void log(String event, GlobalPos source, Entry e, String reason) {
        EtchedSpeakers.LOGGER.info("[ES-REMOTE] {} source={} generation={} media={} frame={} reason={}",event,source,
                e.snapshot.generation(),e.snapshot.media(),e.clock==null?-1:e.clock.target(),reason);
    }
}
