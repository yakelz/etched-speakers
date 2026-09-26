package net.yakel.etchedspeakers.remote;

import java.util.*;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.yakel.etchedspeakers.compat.etched.RetainedTrackSelection;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.yakel.etchedspeakers.compat.AudioSourceResolver;
import net.yakel.etchedspeakers.network.RemotePayloads.*;
import net.yakel.etchedspeakers.source.model.*;

/** Ephemeral, server-thread confined. Reads stay nonblocking; retention alone owns runtime tickets. */
@EventBusSubscriber(modid=EtchedSpeakers.MOD_ID)
public final class RemoteSessions {
    private static final Map<GlobalPos, Entry> SOURCES=new HashMap<>();
    private static final Map<UUID, Watch> WATCHERS=new HashMap<>();
    private static final Map<UUID, Budget> BUDGETS=new HashMap<>();
    private static long sequence;
    private static final SourceRetention RETENTION=new SourceRetention();
    private static final int MAX_SOURCES=512;
    private static final class Entry {
        RemoteTimeline timeline=new RemoteTimeline();
        final RemoteObserverLease remote=new RemoteObserverLease();
        boolean remoteOwned, finished;
        final Set<UUID> localViewers=new HashSet<>(); // Delivery only, never retention listeners.
        List<TrackReference> inventory=List.of();
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
        return report ? ++b.reports<=80 : ++b.interests<=4;
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
        if(!CanonicalAlignment.acceptsLocalObservation(e.remoteOwned)) return; // Returning local frame=0 / old track cannot roll back canonical remote authority.
        if(report.eof()) {
            if(e.timeline.active() && e.timeline.observer().equals(p.getUUID().toString())
                    && e.timeline.localId()==report.localId() && RETENTION.ready(report.source())) {
                next(p.getServer(),report.source(),e,now); return;
            }
            if(e.timeline.eof(p.getUUID().toString(),report.localId(),now)) {
                stopped(report.source(),e,now,"EOF"); broadcast(p.getServer(),report.source(),e);
            }
            return;
        }
        if(e.timeline.rate()==0 && e.timeline.active()) e.timeline=new RemoteTimeline();
        String outcome=e.timeline.observe(p.getUUID().toString(),report.localId(),report.media(),report.frame(),
                report.rate(),report.paused(),now,sequence+1);
        if(outcome.equals("REJECTED")) return;
        if(outcome.equals("CREATE") || outcome.equals("TRACK_CHANGE")) { sequence++; e.track=track.get(); e.finished=false; e.inventory=state.availableTracks(); }
        if(!outcome.equals("UPDATE")) {
            e.changed=now; e.reason="OBSERVED";
            log("REMOTE_OBSERVER_ACCEPTED",report.source(),e,"VALIDATED");
            log("REMOTE_SESSION_"+outcome,report.source(),e,outcome);
            broadcast(p.getServer(),report.source(),e);
        }
    }
    public static void interest(ServerPlayer p, Interest request) {
        if(!p.isAlive() || !budget(p,false) || !request.dimension().equals(p.level().dimension().location()) || request.speakers().size()>32) return;
        var previous=WATCHERS.get(p.getUUID());
        var sources=validateSpeakers(p,request.speakers(),previous==null?Set.of():previous.sources());
        var old=WATCHERS.put(p.getUUID(),new Watch(request.epoch(),p.level().dimension(),request.speakers(),sources,p.serverLevel().getGameTime()));
        reconcile(p.getServer());
        if(old!=null) for(var source:old.sources()) if(!sources.contains(source)) send(p,old,source,SOURCES.get(source),"INTEREST_LOST");
        for(var source:sources) {
            var e=SOURCES.get(source);
            if(old==null || !old.sources().contains(source) || !old.epoch().equals(request.epoch())) {
                if(e!=null) log("REMOTE_SUBSCRIBE",source,e,"VALID_SPEAKER");
                send(p,WATCHERS.get(p.getUUID()),source,e,null);
            }
        }
    }
    /** Public chunk tracking API. No source request, new listener or ticket is created. */
    private static void pushLocal(MinecraftServer server, GlobalPos source, Entry e, boolean heartbeat) {
        if(e.track==null || !e.remoteOwned) return;
        var level=server.getLevel(source.dimension());
        var viewers=new HashSet<UUID>();
        boolean active=RETENTION.held(source) && e.timeline.active();
        if(active && level!=null) for(var p:level.getChunkSource().chunkMap.getPlayers(new net.minecraft.world.level.ChunkPos(source.pos()),false)) {
            var w=WATCHERS.get(p.getUUID());
            if(w==null || !p.isAlive() || !w.dimension().equals(source.dimension())) continue;
            viewers.add(p.getUUID());
            if(heartbeat || !e.localViewers.contains(p.getUUID())) sendLocal(p,w,source,e,true,e.reason);
        }
        for(var id:e.localViewers) if(!viewers.contains(id)) {
            var p=server.getPlayerList().getPlayer(id); var w=WATCHERS.get(id);
            if(p!=null && w!=null && p.level().dimension().equals(source.dimension()))
                sendLocal(p,w,source,e,false,active?"SOURCE_NOT_TRACKED":e.reason);
        }
        e.localViewers.clear(); e.localViewers.addAll(viewers);
    }
    private static void sendLocal(ServerPlayer p, Watch w, GlobalPos source, Entry e, boolean active, String reason) {
        long now=p.serverLevel().getGameTime();
        PacketDistributor.sendToPlayer(p,new Snapshot(w.epoch(),source,e.timeline.generation(),e.track.mediaKey(),
                active?e.track.location():"",e.track.slot(),e.track.trackIndex(),e.timeline.at(now),e.timeline.rate(),now,
                active,e.timeline.paused(),reason,0,e.remoteOwned,true));
    }
    private static Set<GlobalPos> validateSpeakers(ServerPlayer p, List<net.minecraft.core.BlockPos> positions, Set<GlobalPos> previous) {
        var result=new HashSet<GlobalPos>();
        if(!p.isAlive()) return Set.of();
        for(var pos:positions) {
            if(p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))>SpeakerSelection.DISCOVERY_RANGE*SpeakerSelection.DISCOVERY_RANGE) continue;
            var chunk=p.serverLevel().getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null || !(chunk.getBlockEntity(pos) instanceof SpeakerBlockEntity be) || be.isRemoved() || !be.isLinked() || be.getVolume()<=0) continue;
            var source=GlobalPos.of(be.getSourceDimension().orElseThrow(),be.getSourcePos().orElseThrow());
            if(ListenerRetention.validSpeaker(p.isAlive(),source.dimension().equals(p.level().dimension()),!be.isRemoved(),be.isLinked(),be.getVolume(),
                    p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)),be.getAudibleRange(),previous.contains(source))) result.add(source);
        }
        return Set.copyOf(result);
    }
    private static void send(ServerPlayer p, Watch w, GlobalPos source, Entry e, String override) {
        if(e==null || e.track==null) return;
        long now=p.serverLevel().getGameTime();
        if(override==null && !RETENTION.held(source)) override="RETENTION_DENIED";
        boolean active=override==null && e.timeline.active();
        if(override!=null && !override.equals("RETENTION_DENIED")) log("REMOTE_UNSUBSCRIBE",source,e,override);
        PacketDistributor.sendToPlayer(p,new Snapshot(w.epoch(),source,e.timeline.generation(),e.track.mediaKey(),
                active?e.track.location():"",e.track.slot(),e.track.trackIndex(),e.timeline.at(now),e.timeline.rate(),now,
                active,e.timeline.paused(),override!=null?override:e.reason,active?e.remote.tokenFor(p.getUUID()):0,e.remoteOwned,false));
    }
    private static void broadcast(MinecraftServer server, GlobalPos source, Entry e) {
        WATCHERS.forEach((id,w)->{
            var p=server.getPlayerList().getPlayer(id);
            if(p!=null && p.level().dimension().equals(w.dimension()) && w.sources().contains(source)) send(p,w,source,e,null);
        });
        pushLocal(server,source,e,true);
    }
    private static void stopped(GlobalPos source, Entry e, long now, String reason) {
        e.reason=reason; e.changed=now; log("REMOTE_SESSION_STOP",source,e,reason);
    }
    public static void remoteReport(ServerPlayer p, RemoteReport r) {
        if(!p.isAlive() || !budget(p,true) || !p.level().dimension().equals(r.source().dimension())
                || r.master()<=0 || !RemoteTimeline.valid(r.frame(),r.rate())) return;
        var w=WATCHERS.get(p.getUUID()); var e=SOURCES.get(r.source());
        long now=p.serverLevel().getGameTime();
        if(w==null || !w.epoch().equals(r.epoch()) || !w.sources().contains(r.source())
                || RemoteTimeline.expired(now,w.renewed(),RemoteTimeline.INTEREST_LEASE)
                || !validateSpeakers(p,w.speakers(),w.sources()).contains(r.source())
                || !RETENTION.ready(r.source()) || e==null || !e.timeline.matches(r.generation(),r.media())) return;
        var state=AudioSourceResolver.readState(p.serverLevel(),r.source().pos());
        if(!RemoteTimeline.sourceAllowed(state,r.media()) || !state.availableTracks().contains(e.track)) return;
        if(!e.timeline.acceptsRemoteCursor(r.frame(),r.rate(),now,r.eof())) return;
        // READY is not authoritative: only record the candidate. Server assignment precedes any clock mutation.
        if(r.token()==0) {
            e.remote.ready(p.getUUID(),r.epoch(),r.master(),now);
            elect(p.getServer(),r.source(),e,now);
            send(p,w,r.source(),e,null);
            return;
        }
        if(!e.remote.accepts(p.getUUID(),r.epoch(),r.master(),r.token(),now)) return;
        if(!e.timeline.remoteProgress(r.frame(),r.rate(),now,r.eof())) return;
        e.remote.ready(p.getUUID(),r.epoch(),r.master(),now); e.remote.renew(now);
        if(r.eof()) {
            log("SESSION_EOF",r.source(),e,"ASSIGNED_REMOTE_DECODER");
            next(p.getServer(),r.source(),e,now);
        }
    }
    private static Set<UUID> listeners(GlobalPos source) {
        var ids=new HashSet<UUID>(); WATCHERS.forEach((id,w)->{if(w.sources().contains(source)) ids.add(id);});
        return ids;
    }
    private static void elect(MinecraftServer server, GlobalPos source, Entry e, long now) {
        if(e.remote.elect(listeners(source),now)) {
            if(e.remote.owner()!=null) e.remoteOwned=true;
            EtchedSpeakers.LOGGER.info("[ES-RETENTION] OBSERVER_ASSIGN source={} generation={} observer={} listeners={} frame={}",
                    source,e.timeline.generation(),e.remote.owner(),RETENTION.listeners(source),e.timeline.at(now));
            broadcast(server,source,e);
        }
    }
    private static void reconcile(MinecraftServer server) {
        var validated=new HashMap<GlobalPos,Set<UUID>>();
        WATCHERS.forEach((id,w)->{for(var s:w.sources()) validated.computeIfAbsent(s,k->new HashSet<>()).add(id);});
        RETENTION.reconcile(server,validated,server.overworld().getGameTime());
    }
    private static void bootstrap(MinecraftServer server, GlobalPos source, Entry e, SourcePlaybackState state, TrackReference track, long now, String reason) {
        if(track==null || !RemoteTimeline.sourceAllowed(state,track.mediaKey()) || track.location().length()>8192) {
            e.finished=true; stop(server,source,e,now,"NO_NEXT_TRACK"); return;
        }
        e.remote.clear(); e.finished=false; e.inventory=state.availableTracks();
        e.track=track; e.timeline.bootstrap(track.mediaKey(),++sequence,now); e.changed=now; e.reason=reason;
        log(reason,source,e,"NEW_TRACK_FRAME_ZERO_RATE_PENDING"); broadcast(server,source,e);
    }
    private static void next(MinecraftServer server, GlobalPos source, Entry e, long now) {
        var level=server.getLevel(source.dimension());
        if(level==null || !RETENTION.ready(source)) { stop(server,source,e,now,"SOURCE_UNAVAILABLE"); return; }
        var state=AudioSourceResolver.readState(level,source.pos());
        bootstrap(server,source,e,state,RetainedTrackSelection.select(level,source.pos(),state,e.track),now,"SESSION_NEXT");
    }
    private static void stop(MinecraftServer server, GlobalPos source, Entry e, long now, String reason) {
        e.remote.clear();
        if(e.timeline.stop(now)) { stopped(source,e,now,reason); broadcast(server,source,e); }
    }
    public static void explicitSelection(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        var source=GlobalPos.of(level.dimension(),pos); var e=SOURCES.get(source);
        if(e!=null) {
            stop(level.getServer(),source,e,level.getGameTime(),"GUI_SELECTION"); e.finished=false;
            if(RETENTION.ready(source)) {
                var state=AudioSourceResolver.readState(level,pos);
                // The accepted menu command already applied Etched's selection logic; preserve that occurrence.
                bootstrap(level.getServer(),source,e,state,state.serverSelectedTrack().orElse(null),level.getGameTime(),"GUI_SELECTION");
            }
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server=event.getServer(); if(server.getTickCount()%5!=0) return;
        var wi=WATCHERS.entrySet().iterator();
        while(wi.hasNext()) {
            var item=wi.next(); var w=item.getValue(); var p=server.getPlayerList().getPlayer(item.getKey());
            if(p==null || !p.isAlive() || !p.level().dimension().equals(w.dimension())
                    || RemoteTimeline.expired(p.serverLevel().getGameTime(),w.renewed(),RemoteTimeline.INTEREST_LEASE)) {
                if(p!=null) for(var s:w.sources()) send(p,w,s,SOURCES.get(s),"LEASE_EXPIRED");
                wi.remove(); continue;
            }
            var valid=validateSpeakers(p,w.speakers(),w.sources());
            if(!valid.equals(w.sources())) {
                for(var s:w.sources()) if(!valid.contains(s)) send(p,w,s,SOURCES.get(s),"INTEREST_LOST");
                item.setValue(new Watch(w.epoch(),w.dimension(),w.speakers(),valid,w.renewed()));
            }
        }
        reconcile(server);
        long clock=server.overworld().getGameTime();
        RETENTION.tick(clock).forEach((s,reason)->{
            var e=SOURCES.get(s); if(e!=null) { stop(server,s,e,clock,reason); SOURCES.remove(s); }
        });
        for(var source:RETENTION.keys()) if(RETENTION.ready(source) && SOURCES.size()<MAX_SOURCES) SOURCES.computeIfAbsent(source,k->new Entry());
        var it=SOURCES.entrySet().iterator();
        while(it.hasNext()) {
            var item=it.next(); var source=item.getKey(); var e=item.getValue(); var level=server.getLevel(source.dimension());
            long now=level==null?clock:level.getGameTime();
            var state=level==null?null:AudioSourceResolver.readState(level,source.pos());
            if(e.timeline.active()) {
                String reason=state==null || !state.available()?
                        (RETENTION.held(source) && !RETENTION.ready(source)?null:"SERVER_SOURCE_UNAVAILABLE")
                        : !RemoteTimeline.sourceAllowed(state,e.timeline.media()) || !state.availableTracks().contains(e.track)?"SOURCE_STOPPED_OR_TRACK_REMOVED"
                        : !RETENTION.held(source) && RemoteTimeline.expired(now,e.timeline.lastReport(),RemoteTimeline.SESSION_LEASE)?"OBSERVER_TIMEOUT":null;
                if(reason!=null) { stop(server,source,e,now,reason); e.finished=false; }
                else {
                    e.timeline.advance(now);
                    if(RETENTION.held(source)) elect(server,source,e,now);
                }
            }
            if(RETENTION.ready(source) && state!=null && state.available()) {
                if(!e.inventory.equals(state.availableTracks())) e.finished=false;
                e.inventory=state.availableTracks();
                if(!e.timeline.active() && !e.finished && RETENTION.listeners(source)>0 && state.playing()!=SourcePlaybackState.Playback.STOPPED)
                    bootstrap(server,source,e,state,RetainedTrackSelection.select(level,source.pos(),state,null),now,"SESSION_BOOTSTRAP_NEW");
            }
            if(!e.timeline.active() && !RETENTION.held(source) && now-e.changed>200) it.remove();
        }
        if(server.getTickCount()%20==0) WATCHERS.forEach((id,w)->{
            var p=server.getPlayerList().getPlayer(id); if(p!=null) for(var s:w.sources()) send(p,w,s,SOURCES.get(s),null);
        });
        for(var source:RETENTION.keys()) {
            var e=SOURCES.get(source); if(e!=null) pushLocal(server,source,e,server.getTickCount()%20==0);
        }
    }
    private static void removePlayer(net.minecraft.world.entity.player.Player player) {
        WATCHERS.remove(player.getUUID()); BUDGETS.remove(player.getUUID());
        SOURCES.values().forEach(e->e.localViewers.remove(player.getUUID()));
        if(player instanceof ServerPlayer p) {
            reconcile(p.getServer());
            SOURCES.forEach((s,e)->elect(p.getServer(),s,e,p.serverLevel().getGameTime()));
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { removePlayer(event.getEntity()); }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) { removePlayer(event.getEntity()); }
    @SubscribeEvent public static void death(LivingDeathEvent event) {
        if(event.getEntity() instanceof ServerPlayer p) removePlayer(p);
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            WATCHERS.entrySet().removeIf(e->e.getValue().dimension().equals(level.dimension()));
            for(var s:RETENTION.keys()) if(s.dimension().equals(level.dimension())) RETENTION.release(s,false,"LEVEL_UNLOAD");
            SOURCES.keySet().removeIf(s->s.dimension().equals(level.dimension()));
        }
    }
    @SubscribeEvent public static void shutdown(ServerStoppingEvent event) {
        RETENTION.clear(); SOURCES.clear(); WATCHERS.clear(); BUDGETS.clear(); sequence=0;
    }
    private static void log(String event, GlobalPos source, Entry e, String reason) {
        EtchedSpeakers.LOGGER.info("[ES-REMOTE] {} source={} generation={} media={} frame={} sampleRate={} reason={}",event,source,e.timeline.generation(),e.timeline.media(),e.timeline.at(e.timeline.lastReport()),e.timeline.rate(),reason);
    }
}
