package net.yakel.etchedspeakers.remote;

import java.util.*;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.yakel.etchedspeakers.compat.AudioSourceResolver;
import net.yakel.etchedspeakers.network.RemotePayloads.*;
import net.yakel.etchedspeakers.source.model.*;

/** Ephemeral, server-thread confined. All lookups use already-loaded chunks. */
@EventBusSubscriber(modid=EtchedSpeakers.MOD_ID)
public final class RemoteSessions {
    private static final Map<GlobalPos, Entry> SOURCES=new HashMap<>();
    private static final Map<UUID, Watch> WATCHERS=new HashMap<>();
    private static final Map<UUID, Budget> BUDGETS=new HashMap<>();
    private static long sequence;
    private static final int MAX_SOURCES=512;
    private static final class Entry {
        final RemoteTimeline timeline=new RemoteTimeline();
        TrackReference track;
        String reason="OBSERVED";
        long changed;
    }
    private record Watch(UUID epoch, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                         List<net.minecraft.core.BlockPos> speakers, Set<GlobalPos> sources, long renewed) {}
    private static final class Budget { long tick; int reports, interests; }
    private static boolean budget(ServerPlayer p, boolean report) {
        long now=p.serverLevel().getGameTime();
        var b=BUDGETS.computeIfAbsent(p.getUUID(),k->new Budget());
        if(now-b.tick>=20 || now<b.tick) { b.tick=now; b.reports=0; b.interests=0; }
        return report ? ++b.reports<=40 : ++b.interests<=4;
    }
    public static void report(ServerPlayer p, Report report) {
        if(!budget(p,true) || !p.isAlive() || !report.source().dimension().equals(p.level().dimension())
                || p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(report.source().pos()))>64*64
                || !RemoteTimeline.valid(report.frame(),report.rate()) || report.localId()<=0) return;
        var state=AudioSourceResolver.readState(p.serverLevel(),report.source().pos());
        if(!RemoteTimeline.sourceAllowed(state,report.media())) return;
        // Exact occurrence when the client knows it; otherwise validate the media against the inventory.
        var track=state.availableTracks().stream().filter(t->t.mediaKey().equals(report.media()))
                .filter(t->report.slot()<0 || t.slot()==report.slot())
                .filter(t->report.index()<0 || t.trackIndex()==report.index()).findFirst();
        if(track.isEmpty() || report.slot() < -1 || report.index() < -1 || track.get().location().length()>8192) return;
        long now=p.serverLevel().getGameTime();
        var e=SOURCES.get(report.source());
        if(e==null) {
            if(report.eof() || SOURCES.size()>=MAX_SOURCES) return;
            e=new Entry(); SOURCES.put(report.source(),e);
        }
        if(report.eof()) {
            if(e.timeline.eof(p.getUUID().toString(),report.localId(),now)) {
                stopped(report.source(),e,now,"EOF"); broadcast(p.getServer(),report.source(),e);
            }
            return;
        }
        String outcome=e.timeline.observe(p.getUUID().toString(),report.localId(),report.media(),report.frame(),
                report.rate(),report.paused(),now,sequence+1);
        if(outcome.equals("REJECTED")) return;
        if(outcome.equals("CREATE") || outcome.equals("TRACK_CHANGE")) { sequence++; e.track=track.get(); }
        if(!outcome.equals("UPDATE")) {
            e.changed=now; e.reason="OBSERVED";
            log("REMOTE_OBSERVER_ACCEPTED",report.source(),e,"VALIDATED");
            log("REMOTE_SESSION_"+outcome,report.source(),e,outcome);
            broadcast(p.getServer(),report.source(),e);
        }
    }
    public static void interest(ServerPlayer p, Interest request) {
        if(!budget(p,false) || !request.dimension().equals(p.level().dimension().location()) || request.speakers().size()>32) return;
        var sources=validateSpeakers(p,request.speakers());
        var old=WATCHERS.put(p.getUUID(),new Watch(request.epoch(),p.level().dimension(),request.speakers(),sources,p.serverLevel().getGameTime()));
        if(old!=null) for(var source:old.sources()) if(!sources.contains(source)) send(p,old,source,SOURCES.get(source),"INTEREST_LOST");
        for(var source:sources) {
            var e=SOURCES.get(source);
            if(old==null || !old.sources().contains(source) || !old.epoch().equals(request.epoch())) {
                if(e!=null) log("REMOTE_SUBSCRIBE",source,e,"VALID_SPEAKER");
                send(p,WATCHERS.get(p.getUUID()),source,e,null);
            }
        }
    }
    private static Set<GlobalPos> validateSpeakers(ServerPlayer p, List<net.minecraft.core.BlockPos> positions) {
        var result=new HashSet<GlobalPos>();
        for(var pos:positions) {
            if(p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))>SpeakerSelection.DISCOVERY_RANGE*SpeakerSelection.DISCOVERY_RANGE) continue;
            var chunk=p.serverLevel().getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null || !(chunk.getBlockEntity(pos) instanceof SpeakerBlockEntity be) || !be.isLinked()
                    || !SpeakerSelection.inRange(p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)),be.getAudibleRange(),true)) continue;
            var source=GlobalPos.of(be.getSourceDimension().orElseThrow(),be.getSourcePos().orElseThrow());
            if(source.dimension().equals(p.level().dimension())) result.add(source);
        }
        return Set.copyOf(result);
    }
    private static void send(ServerPlayer p, Watch w, GlobalPos source, Entry e, String override) {
        if(e==null || e.track==null) return;
        long now=p.serverLevel().getGameTime();
        boolean active=override==null && e.timeline.active();
        if(override!=null) log("REMOTE_UNSUBSCRIBE",source,e,override);
        PacketDistributor.sendToPlayer(p,new Snapshot(w.epoch(),source,e.timeline.generation(),e.track.mediaKey(),
                active?e.track.location():"",e.track.slot(),e.track.trackIndex(),e.timeline.at(now),e.timeline.rate(),now,
                active,e.timeline.paused(),override!=null?override:e.reason));
    }
    private static void broadcast(MinecraftServer server, GlobalPos source, Entry e) {
        WATCHERS.forEach((id,w)->{
            var p=server.getPlayerList().getPlayer(id);
            if(p!=null && p.level().dimension().equals(w.dimension()) && w.sources().contains(source)) send(p,w,source,e,null);
        });
    }
    private static void stopped(GlobalPos source, Entry e, long now, String reason) {
        e.reason=reason; e.changed=now; log("REMOTE_SESSION_STOP",source,e,reason);
    }
    public static void explicitSelection(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        var source=GlobalPos.of(level.dimension(),pos); var e=SOURCES.get(source);
        if(e!=null && e.timeline.stop(level.getGameTime())) {
            stopped(source,e,level.getGameTime(),"GUI_SELECTION"); broadcast(level.getServer(),source,e);
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server=event.getServer(); if(server.getTickCount()%5!=0) return;
        var it=SOURCES.entrySet().iterator();
        while(it.hasNext()) {
            var item=it.next(); var source=item.getKey(); var e=item.getValue(); var level=server.getLevel(source.dimension());
            long now=level==null?server.overworld().getGameTime():level.getGameTime();
            if(e.timeline.active()) {
                var state=level==null?null:AudioSourceResolver.readState(level,source.pos());
                String stop=state==null || !state.available()?"SERVER_SOURCE_UNAVAILABLE"
                        : !RemoteTimeline.sourceAllowed(state,e.timeline.media())?"SOURCE_STOPPED_OR_TRACK_REMOVED"
                        : RemoteTimeline.expired(now,e.timeline.lastReport(),RemoteTimeline.SESSION_LEASE)?"OBSERVER_TIMEOUT":null;
                if(stop!=null && e.timeline.stop(now)) { stopped(source,e,now,stop); broadcast(server,source,e); }
            }
            if(!e.timeline.active() && now-e.changed>200) it.remove();
        }
        var wi=WATCHERS.entrySet().iterator();
        while(wi.hasNext()) {
            var item=wi.next(); var w=item.getValue(); var p=server.getPlayerList().getPlayer(item.getKey());
            if(p==null || !p.level().dimension().equals(w.dimension())
                    || RemoteTimeline.expired(p.serverLevel().getGameTime(),w.renewed(),RemoteTimeline.INTEREST_LEASE)) {
                if(p!=null) for(var s:w.sources()) send(p,w,s,SOURCES.get(s),"LEASE_EXPIRED");
                wi.remove(); continue;
            }
            var valid=validateSpeakers(p,w.speakers());
            if(!valid.equals(w.sources())) {
                for(var s:w.sources()) if(!valid.contains(s)) send(p,w,s,SOURCES.get(s),"INTEREST_LOST");
                w=new Watch(w.epoch(),w.dimension(),w.speakers(),valid,w.renewed()); item.setValue(w);
            }
            if(server.getTickCount()%20==0) for(var s:w.sources()) send(p,w,s,SOURCES.get(s),null);
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        WATCHERS.remove(event.getEntity().getUUID()); BUDGETS.remove(event.getEntity().getUUID());
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        WATCHERS.remove(event.getEntity().getUUID()); BUDGETS.remove(event.getEntity().getUUID());
    }
    @SubscribeEvent public static void shutdown(ServerStoppedEvent event) { SOURCES.clear(); WATCHERS.clear(); BUDGETS.clear(); sequence=0; }
    private static void log(String event, GlobalPos source, Entry e, String reason) {
        EtchedSpeakers.LOGGER.info("[ES-REMOTE] {} source={} generation={} media={} frame={} sampleRate={} reason={}",event,source,e.timeline.generation(),e.timeline.media(),e.timeline.at(e.timeline.lastReport()),e.timeline.rate(),reason);
    }
}
