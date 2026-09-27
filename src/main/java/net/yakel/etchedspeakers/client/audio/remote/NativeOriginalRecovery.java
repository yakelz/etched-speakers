package net.yakel.etchedspeakers.client.audio.remote;

import gg.moonflower.etched.core.mixin.client.render.LevelRendererAccessor;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.network.RemotePayloads.Snapshot;
import net.yakel.etchedspeakers.source.model.*;
import net.yakel.etchedspeakers.source.model.CanonicalAlignment.Identity;

/** Recovery of missing native originals using local delivery, not remote Speaker subscriptions. */
public final class NativeOriginalRecovery {
    private static final Map<GlobalPos,Entry> SOURCES=new HashMap<>();
    private static final class Entry {
        Snapshot snapshot;
        volatile NativeDiscClock clock;
        final ClientLevel world=Minecraft.getInstance().level;
        OriginalRecovery gate=new OriginalRecovery();
        long heard,eventFence=-1,attemptStarted;
        NativeOriginalSound replacement;
        SoundInstance previous;
        boolean preparing, volumeLost;
        Identity identity() { var s=snapshot; return new Identity(s.source(),s.generation(),s.media(),s.slot(),s.index(),s.sourceKind()); }
    }
    private NativeOriginalRecovery() {}
    private static Map<BlockPos,SoundInstance> songs() {
        return ((LevelRendererAccessor)Minecraft.getInstance().levelRenderer).getPlayingJukeboxSongs();
    }
    public static void snapshot(Snapshot s) {
        if(!s.localSource() || s.nativeElapsedTicks()<0 || s.sourceKind()!=OriginalSourceKind.VANILLA_NATIVE) return;
        var e=SOURCES.get(s.source());
        var id=new Identity(s.source(),s.generation(),s.media(),s.slot(),s.index(),s.sourceKind());
        if(!CanonicalAlignment.accepts(id,s.serverTick(),e==null?null:e.identity(),e==null?0:e.snapshot.serverTick())) return;
        if(e==null) {
            if(!s.active() || SOURCES.size()>=32) return;
            e=new Entry(); SOURCES.put(s.source(),e);
        }
        if(e.snapshot!=null && !id.equals(e.identity())) { cancel(e); e.gate=new OriginalRecovery(); e.eventFence=-1; e.volumeLost=false; }
        e.snapshot=s; e.heard=System.nanoTime();
        e.clock=new NativeDiscClock(s.nativeElapsedTicks(),e.heard,Minecraft.getInstance().level.getGameTime()-s.serverTick(),s.paused());
        if(!s.active()) {
            cancel(e);
            if(!Set.of("SOURCE_NOT_TRACKED","GRACE_EXPIRED","LEASE_EXPIRED").contains(s.reason())) e.gate.terminal();
        }
    }
    private static boolean eligible(Entry e) {
        var c=Minecraft.getInstance(); var s=e.snapshot;
        if(c.level==null || c.level!=e.world || c.player==null) return false;
        var chunk=c.level.getChunkSource().getChunkNow(s.source().pos().getX()>>4,s.source().pos().getZ()>>4);
        boolean loaded=chunk!=null && chunk.getBlockEntity(s.source().pos()) instanceof JukeboxBlockEntity be && !be.isRemoved()
                && be.getBlockState().getValue(JukeboxBlock.HAS_RECORD);
        return s.serverTick()>e.eventFence && OriginalRecovery.eligible(s.active(),s.localSource(),loaded,
                c.level.dimension().equals(s.source().dimension()),c.player.distanceToSqr(Vec3.atCenterOf(s.source().pos())),
                System.nanoTime()-e.heard<10_000_000_000L);
    }
    public static void volumeChanged(boolean audible) {
        for(var e:SOURCES.values()) {
            e.gate.volume(audible);
            if(!audible) { e.volumeLost=true; cancel(e); }
        }
    }
    /** A real 1010/1011 supersedes pending work. Vanilla then replaces/stops the registered sound itself. */
    public static void sourceEvent(BlockPos pos) {
        var c=Minecraft.getInstance(); if(c.level==null) return;
        var e=SOURCES.get(GlobalPos.of(c.level.dimension(),pos));
        if(e!=null) { cancel(e); e.eventFence=e.snapshot.serverTick(); }
    }
    public static void tick() {
        long now=System.nanoTime(); var c=Minecraft.getInstance();
        var it=SOURCES.values().iterator();
        while(it.hasNext()) {
            var e=it.next();
            if(c.level!=e.world || now-e.heard>10_000_000_000L) { cancel(e); it.remove(); continue; }
            e.gate.volume(OriginalAudio.audible());
            if(!eligible(e)) { cancel(e); continue; }
            if(!OriginalAudio.audible()) continue;
            if(e.preparing) continue;
            var sound=songs().get(e.snapshot.source().pos());
            if(OriginalAudio.healthy(sound) && !e.volumeLost) {
                if(sound!=e.replacement || now-e.attemptStarted>2_000_000_000L) e.gate.healthy();
                continue;
            }
            if(e.replacement!=null && !e.volumeLost && e.replacement.stream.eof()) { e.gate.terminal(); continue; }
            if(e.replacement!=null && now-e.attemptStarted<2_000_000_000L && !e.volumeLost) continue;
            if(e.gate.begin(now)) start(e,sound);
        }
    }
    private static void start(Entry e,SoundInstance previous) {
        cancel(e);
        var c=Minecraft.getInstance(); var s=e.snapshot; var id=e.identity();
        var sound=new NativeOriginalSound(s.source(),s.location());
        e.replacement=sound; e.previous=songs().get(s.source().pos()); e.preparing=true; e.attemptStarted=System.nanoTime();
        log(e,"ORIGINAL_RECOVERY_BEGIN","NATIVE_CURSOR",0);
        sound.prepare(c.getSoundManager(),OriginalAudio.engine().etchedspeakers$getBuffers(),rate->e.clock.target(rate,System.nanoTime()))
                .whenComplete((unused,failure)->c.execute(()->ready(e,id,sound,failure)));
    }
    private static void ready(Entry e,Identity id,NativeOriginalSound sound,Throwable failure) {
        var c=Minecraft.getInstance(); var pos=e.snapshot.source().pos();
        boolean current=SOURCES.get(e.snapshot.source())==e && id.equals(e.identity()) && e.replacement==sound
                && eligible(e) && OriginalAudio.audible() && songs().get(pos)==e.previous;
        if(failure!=null || !current) {
            sound.cancel();
            if(e.replacement==sound) {
                e.preparing=false; e.replacement=null;
                Throwable cause=failure; while(cause!=null && cause.getCause()!=null) cause=cause.getCause();
                if(current && cause instanceof java.io.EOFException) e.gate.terminal();
                log(e,"ORIGINAL_RECOVERY_CANCEL",!current?"STALE_TOKEN":cause instanceof java.io.EOFException?"TARGET_BEYOND_EOF":"PREPARATION_FAILED",0);
            }
            return;
        }
        int rate=sound.stream.rate(); long frame=sound.stream.frame();
        if(e.clock.target(rate,System.nanoTime())-frame>rate/5) {
            sound.stream.catchUp(r->e.clock.target(r,System.nanoTime()))
                    .whenComplete((unused,error)->c.execute(()->ready(e,id,sound,error))); return;
        }
        if(e.previous!=null) c.getSoundManager().stop(e.previous);
        songs().put(pos,sound); // Actual vanilla 1010/1011 now owns replacement/deduplication.
        e.preparing=false; e.volumeLost=false;
        c.getSoundManager().play(sound);
        log(e,"ORIGINAL_RECOVERY_READY","NATIVE_POSITIONAL_RECORDS",frame);
    }
    private static void cancel(Entry e) {
        var sound=e.replacement;
        if(sound!=null) {
            sound.cancel();
            Minecraft.getInstance().getSoundManager().stop(sound);
            if(e.snapshot!=null) songs().remove(e.snapshot.source().pos(),sound);
        }
        e.replacement=null; e.preparing=false;
    }
    public static void reset() { for(var e:SOURCES.values()) cancel(e); SOURCES.clear(); }
    private static void log(Entry e,String event,String reason,long frame) {
        EtchedSpeakers.LOGGER.atLevel(reason.equals("PREPARATION_FAILED") ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.DEBUG)
                .log("[ES-ORIGINAL] {} source={} generation={} media={} frame={} reason={}",
                event,e.snapshot.source(),e.snapshot.generation(),e.snapshot.media(),frame,reason);
    }
}
