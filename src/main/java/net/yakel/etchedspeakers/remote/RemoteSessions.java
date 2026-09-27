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
    /** Called after a real inventory transition, on the server thread. Read-only evidence. */
    public static void diagnoseVanillaRecord(net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.block.entity.JukeboxBlockEntity jukebox, boolean wasEmpty) {
        var source=GlobalPos.of(level.dimension(),jukebox.getBlockPos());
        var state=AudioSourceResolver.readState(level,jukebox.getBlockPos());
        var e=SOURCES.get(source);
        var item=jukebox.getTheItem();
        EtchedSpeakers.LOGGER.debug("[ES-VANILLA-ACT] RECORD_CHANGE source={} generation={} listenerCount={} media={} reason=wasEmpty:{},empty:{},state:{},tracks:{},music:{},albumCover:{},active:{}",
                source,e==null?0:e.timeline.generation(),RETENTION.listeners(source),
                state.availableTracks().stream().limit(3).map(TrackReference::mediaKey).toList(),
                wasEmpty,item.isEmpty(),state.reason(),state.availableTracks().size(),
                item.has(gg.moonflower.etched.core.registry.EtchedComponents.MUSIC),
                item.has(gg.moonflower.etched.core.registry.EtchedComponents.ALBUM_COVER),e!=null && e.timeline.active());
    }

    private static final Map<GlobalPos, Entry> SOURCES=new HashMap<>();
    private static final Map<UUID, Watch> WATCHERS=new HashMap<>();
    private static final Map<UUID, Budget> BUDGETS=new HashMap<>();
    private static long sequence;
    private static final SourceRetention RETENTION=new SourceRetention(s->{var e=SOURCES.get(s); return e==null?0:e.timeline.generation();});
    private static final int MAX_SOURCES=ListenerRetention.MAX_RETAINED_SOURCES;
    private static final class Entry {
        long nativeElapsedTicks=-1; // Native server cursor; never populated from client reports.
        RemoteTimeline timeline=new RemoteTimeline();
        final RemoteObserverLease remote=new RemoteObserverLease();
        final RemoteObserverLease local=new RemoteObserverLease();
        boolean remoteOwned, finished, localOnly;
        OriginalSourceKind kind=OriginalSourceKind.UNKNOWN;
        final Set<UUID> localViewers=new HashSet<>(); // Snapshot delivery history; holder lifetime lives in SourceRetention.
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
        if(!budget(p,true)) return;
        if(report.generation()!=0) { alignedReport(p,report); return; }
        if(!p.isAlive() || !report.source().dimension().equals(p.level().dimension())
                || p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(report.source().pos()))>64*64
                || !RemoteTimeline.valid(report.frame(),report.rate()) || report.localId()<=0) return;
        var state=AudioSourceResolver.readState(p.serverLevel(),report.source().pos());
        if(state.reason()==SourcePlaybackState.Reason.VANILLA_SONG_PLAYER) return;
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
            if(!RETENTION.admitLocal(p.serverLevel(),report.source(),holders(p.getServer(),report.source(),null),now)) return;
            e=new Entry(); SOURCES.put(report.source(),e);
        }
        if(e.localOnly) {
            if(!RETENTION.admitLocal(p.serverLevel(),report.source(),holders(p.getServer(),report.source(),null),now)) return;
            e.localOnly=false;
        }
        e.nativeElapsedTicks=-1; e.kind=OriginalSourceKind.of(state);
        if(!CanonicalAlignment.acceptsLocalObservation(e.remoteOwned)) return; // Returning local frame=0 / old track cannot roll back canonical remote authority.
        if(report.eof()) {
            if(e.timeline.active() && e.timeline.observer().equals(p.getUUID().toString())
                    && e.timeline.localId()==report.localId() && RETENTION.ready(report.source()) && RETENTION.listeners(report.source())>0) {
                next(p.getServer(),report.source(),e,now); return;
            }
            if(e.timeline.eof(p.getUUID().toString(),report.localId(),now)) {
                stopped(report.source(),e,now,"EOF"); broadcast(p.getServer(),report.source(),e);
            }
            return;
        }
        if(e.kind==OriginalSourceKind.VANILLA_ETCHED && e.timeline.active() && e.track!=null && !e.track.equals(track.get()))
            stop(p.getServer(),report.source(),e,now,"LOCAL_SEQUENCE_CHANGED");
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
        if(e.track==null) return;
        var level=server.getLevel(source.dimension());
        var viewers=new HashSet<UUID>();
        boolean active=(RETENTION.alive(source) || e.localOnly) && e.timeline.active();
        if(active && level!=null) for(var p:level.getChunkSource().chunkMap.getPlayers(new net.minecraft.world.level.ChunkPos(source.pos()),false)) {
            var w=WATCHERS.get(p.getUUID());
            if(w==null || !p.isAlive() || !w.dimension().equals(source.dimension())
                    || p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(source.pos()))>64*64) continue;
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
                active,e.nativeElapsedTicks>=0?nativePaused(p.serverLevel(),source):e.timeline.paused(),reason,active?e.local.tokenFor(p.getUUID()):0,e.remoteOwned,true,
                nativeCursor(p.serverLevel(),source,e),e.kind,localPlaylist(e)));
    }
    private static List<String> localPlaylist(Entry e) {
        return e.kind==OriginalSourceKind.VANILLA_ETCHED && e.inventory.size()<=64
                && e.inventory.stream().allMatch(t->t.location().length()<=8192)
                ?e.inventory.stream().map(TrackReference::location).toList():List.of();
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
        if(override==null && !RETENTION.alive(source)) override="RETENTION_DENIED";
        boolean active=override==null && e.timeline.active();
        if(override!=null && !override.equals("RETENTION_DENIED")) log("REMOTE_UNSUBSCRIBE",source,e,override);
        PacketDistributor.sendToPlayer(p,new Snapshot(w.epoch(),source,e.timeline.generation(),e.track.mediaKey(),
                active?e.track.location():"",e.track.slot(),e.track.trackIndex(),e.timeline.at(now),e.timeline.rate(),now,
                active,e.nativeElapsedTicks>=0?nativePaused(p.serverLevel(),source):e.timeline.paused(),override!=null?override:e.reason,active?e.remote.tokenFor(p.getUUID()):0,e.remoteOwned,false,
                nativeCursor(p.serverLevel(),source,e),e.kind,List.of()));
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
        if(e!=null && e.nativeElapsedTicks>=0) return;
        long now=p.serverLevel().getGameTime();
        if(w==null || !w.epoch().equals(r.epoch()) || !w.sources().contains(r.source())
                || RemoteTimeline.expired(now,w.renewed(),RemoteTimeline.INTEREST_LEASE)
                || !validateSpeakers(p,w.speakers(),w.sources()).contains(r.source())
                || !RETENTION.ready(r.source()) || e==null || !e.timeline.matches(r.generation(),r.media())) return;
        var state=AudioSourceResolver.readState(p.serverLevel(),r.source().pos());
        if(state.reason()==SourcePlaybackState.Reason.VANILLA_SONG_PLAYER) return;
        if(!RemoteTimeline.sourceAllowed(state,r.media()) || !state.availableTracks().contains(e.track)) return;
        if(!(r.eof()?e.timeline.acceptsHandoffEnd(r.frame(),r.rate(),now):e.timeline.acceptsRemoteCursor(r.frame(),r.rate(),now,false))) return;
        // READY is not authoritative: only record the candidate. Server assignment precedes any clock mutation.
        if(r.token()==0) {
            e.remote.ready(p.getUUID(),r.epoch(),r.master(),now);
            elect(p.getServer(),r.source(),e,now);
            send(p,w,r.source(),e,null);
            return;
        }
        if(!e.remote.accepts(p.getUUID(),r.epoch(),r.master(),r.token(),now)) return;
        if(!(r.eof()?e.timeline.handoffEnd(r.frame(),r.rate(),now):e.timeline.remoteProgress(r.frame(),r.rate(),now))) return;
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
        if(e.nativeElapsedTicks>=0) return; // The native source already has server authority.
        boolean remoteChanged=e.remote.elect(listeners(source),now);
        boolean localChanged=e.local.elect(e.remote.owner()==null?holders(server,source,null).stream().filter(id->RETENTION.holder(source,id)).collect(java.util.stream.Collectors.toSet()):Set.of(),now);
        if(remoteChanged || localChanged) {
            if(e.remote.owner()!=null) e.remoteOwned=true;
            EtchedSpeakers.LOGGER.debug("[ES-RETENTION] OBSERVER_ASSIGN source={} generation={} observer={} listeners={} frame={}",
                    source,e.timeline.generation(),e.remote.owner(),RETENTION.listeners(source),e.timeline.at(now));
            EtchedSpeakers.LOGGER.debug("[ES-RETENTION] AUTHORITY_HANDOFF source={} generation={} remoteObserver={} localObserver={} frame={}",
                    source,e.timeline.generation(),e.remote.owner(),e.local.owner(),e.timeline.at(now));
            broadcast(server,source,e);
        }
    }
    private static Set<UUID> holders(MinecraftServer server,GlobalPos source,UUID excluded) {
        var level=server.getLevel(source.dimension()); if(level==null) return Set.of();
        var result=new HashSet<UUID>();
        for(var p:level.getChunkSource().chunkMap.getPlayers(new net.minecraft.world.level.ChunkPos(source.pos()),false)) {
            if(p.isAlive() && p.level().dimension().equals(source.dimension()) && !p.getUUID().equals(excluded)
                    && server.getPlayerList().getPlayer(p.getUUID())==p) result.add(p.getUUID());
        }
        return Set.copyOf(result);
    }
    private static void reconcile(MinecraftServer server) { reconcile(server,null); }
    private static void reconcile(MinecraftServer server,UUID excluded) {
        var validated=new HashMap<GlobalPos,Set<UUID>>();
        WATCHERS.forEach((id,w)->{for(var s:w.sources()) validated.computeIfAbsent(s,k->new HashSet<>()).add(id);});
        var local=new HashMap<GlobalPos,Set<UUID>>();
        // Admission is bounded; no discovery of arbitrary blocks/chunks.
        for(var source:RETENTION.keys()) {
            var viewers=holders(server,source,excluded); if(!viewers.isEmpty()) local.put(source,viewers);
        }
        RETENTION.reconcile(server,validated,local,server.overworld().getGameTime());
    }
    /** Only a current-generation aligned original with an explicit lease may anchor an owned session. */
    private static void alignedReport(ServerPlayer p,Report r) {
        var e=SOURCES.get(r.source()); var w=WATCHERS.get(p.getUUID()); long now=p.serverLevel().getGameTime();
        if(e!=null && e.nativeElapsedTicks>=0) return;
        if(!p.isAlive() || !p.level().dimension().equals(r.source().dimension()) || r.localId()<=0 || r.paused()
                || e==null || !e.remoteOwned || w==null || !w.epoch().equals(r.epoch())
                || RemoteTimeline.expired(now,w.renewed(),RemoteTimeline.INTEREST_LEASE)
                || !RETENTION.alive(r.source()) || !RETENTION.ready(r.source())
                || !holders(p.getServer(),r.source(),null).contains(p.getUUID())
                || !e.timeline.matches(r.generation(),r.media()) || e.track==null
                || e.track.slot()!=r.slot() || e.track.trackIndex()!=r.index()) return;
        var state=AudioSourceResolver.readState(p.serverLevel(),r.source().pos());
        if(state.reason()==SourcePlaybackState.Reason.VANILLA_SONG_PLAYER) return;
        if(!RemoteTimeline.sourceAllowed(state,r.media()) || !state.availableTracks().contains(e.track)
                || !(r.eof()?e.timeline.acceptsHandoffEnd(r.frame(),r.rate(),now):e.timeline.acceptsRemoteCursor(r.frame(),r.rate(),now,false))) return;
        if(r.observerToken()==0) {
            e.local.ready(p.getUUID(),r.epoch(),r.localId(),now); elect(p.getServer(),r.source(),e,now);
            sendLocal(p,w,r.source(),e,true,e.reason); return;
        }
        if(e.remote.owner()!=null || !e.local.accepts(p.getUUID(),r.epoch(),r.localId(),r.observerToken(),now)) return;
        if(!(r.eof()?e.timeline.handoffEnd(r.frame(),r.rate(),now):e.timeline.remoteProgress(r.frame(),r.rate(),now))) return;
        e.local.ready(p.getUUID(),r.epoch(),r.localId(),now); e.local.renew(now);
        if(r.eof()) { log("SESSION_EOF",r.source(),e,"ASSIGNED_ALIGNED_LOCAL_DECODER"); next(p.getServer(),r.source(),e,now); }
    }
    private static void bootstrap(MinecraftServer server, GlobalPos source, Entry e, SourcePlaybackState state, TrackReference track, long now, String reason) {
        e.nativeElapsedTicks=-1; e.kind=OriginalSourceKind.of(state);
        if(track==null || !RemoteTimeline.sourceAllowed(state,track.mediaKey()) || track.location().length()>8192) {
            e.finished=true; stop(server,source,e,now,"NO_NEXT_TRACK"); return;
        }
        e.remote.clear(); e.local.clear(); e.finished=false; e.inventory=state.availableTracks();
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
        RETENTION.stopNative(source);
        e.remote.clear(); e.local.clear();
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
        for(var source:RETENTION.keys()) if(RETENTION.ready(source) && !SOURCES.containsKey(source)) {
            if(SOURCES.size()>=MAX_SOURCES) {
                // New ticket-free local metadata must not displace an actual retained Speaker source.
                var victim=SOURCES.entrySet().stream().filter(i->i.getValue().localOnly && !RETENTION.alive(i.getKey())).findFirst();
                victim.ifPresent(i->{ stop(server,i.getKey(),i.getValue(),clock,"LOCAL_METADATA_CAPACITY"); SOURCES.remove(i.getKey()); });
            }
            if(SOURCES.size()<MAX_SOURCES) SOURCES.put(source,new Entry());
        }
        var it=SOURCES.entrySet().iterator();
        while(it.hasNext()) {
            var item=it.next(); var source=item.getKey(); var e=item.getValue(); var level=server.getLevel(source.dimension());
            long now=level==null?clock:level.getGameTime();
            var state=level==null?null:AudioSourceResolver.readState(level,source.pos());
            if(e.localOnly && RETENTION.alive(source)) e.localOnly=false; // Real Speaker admission promotes the existing cursor, never a fake listener.
            if(e.localOnly && (level==null || nearHolders(server,source).isEmpty())) {
                stop(server,source,e,now,"LOCAL_HOLDERS_GONE"); it.remove(); continue;
            }
            if(level!=null && state!=null && (RETENTION.ready(source) || e.localOnly)
                    && state.reason()==SourcePlaybackState.Reason.VANILLA_SONG_PLAYER) {
                updateNative(level,source,e,state,now);
                continue;
            }
            if(e.nativeElapsedTicks>=0) {
                stop(server,source,e,now,"NATIVE_SOURCE_STOPPED"); e.nativeElapsedTicks=-1; e.finished=false;
            }
            if(e.timeline.active()) {
                String reason=state==null || !state.available()?
                        (RETENTION.alive(source) && !RETENTION.ready(source)?null:"SERVER_SOURCE_UNAVAILABLE")
                        : !RemoteTimeline.sourceAllowed(state,e.timeline.media()) || !state.availableTracks().contains(e.track)?"SOURCE_STOPPED_OR_TRACK_REMOVED"
                        : !RETENTION.alive(source) && RemoteTimeline.expired(now,e.timeline.lastReport(),RemoteTimeline.SESSION_LEASE)?"OBSERVER_TIMEOUT":null;
                if(reason!=null) { stop(server,source,e,now,reason); e.finished=false; }
                else {
                    e.timeline.advance(now);
                    if(RETENTION.alive(source)) elect(server,source,e,now);
                }
            }
            if(RETENTION.ready(source) && state!=null && state.available()) {
                if(!e.inventory.equals(state.availableTracks())) e.finished=false;
                e.inventory=state.availableTracks();
                if(!e.timeline.active() && !e.finished && RETENTION.listeners(source)>0 && state.playing()!=SourcePlaybackState.Playback.STOPPED)
                    bootstrap(server,source,e,state,RetainedTrackSelection.select(level,source.pos(),state,null),now,"SESSION_BOOTSTRAP_NEW");
            }
            if(e.localOnly && !e.timeline.active() || !e.timeline.active() && !RETENTION.alive(source) && now-e.changed>200) it.remove();
        }
        if(server.getTickCount()%20==0) WATCHERS.forEach((id,w)->{
            var p=server.getPlayerList().getPlayer(id); if(p!=null) for(var s:w.sources()) send(p,w,s,SOURCES.get(s),null);
        });
        for(var item:SOURCES.entrySet()) pushLocal(server,item.getKey(),item.getValue(),server.getTickCount()%20==0);
    }
    private static long nativeCursor(net.minecraft.server.level.ServerLevel level,GlobalPos source,Entry e) {
        if(e.nativeElapsedTicks<0) return -1;
        var chunk=level.getChunkSource().getChunkNow(source.pos().getX()>>4,source.pos().getZ()>>4);
        return chunk!=null && chunk.getBlockEntity(source.pos()) instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity jukebox
                && jukebox.getSongPlayer().isPlaying() ? jukebox.getSongPlayer().getTicksSinceSongStarted() : e.nativeElapsedTicks;
    }
    private static boolean nativePaused(net.minecraft.server.level.ServerLevel level,GlobalPos source) {
        var chunk=level.getChunkSource().getChunkNow(source.pos().getX()>>4,source.pos().getZ()>>4);
        // Mirror LevelChunk.isTicking, which gates the real JukeboxBlockEntity ticker.
        return chunk==null || !level.getWorldBorder().isWithinBounds(source.pos())
                || !chunk.getFullStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING)
                || !level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(source.pos()));
    }
    private static void updateNative(net.minecraft.server.level.ServerLevel level,GlobalPos source,Entry e,SourcePlaybackState state,long now) {
        if(state.currentTrack().isEmpty()) { stop(level.getServer(),source,e,now,"NATIVE_SONG_ENDED"); return; }
        var chunk=level.getChunkSource().getChunkNow(source.pos().getX()>>4,source.pos().getZ()>>4);
        if(chunk==null || !(chunk.getBlockEntity(source.pos()) instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity jukebox)) return;
        long elapsed=jukebox.getSongPlayer().getTicksSinceSongStarted();
        if(!NativeDiscClock.validTicks(elapsed)) { stop(level.getServer(),source,e,now,"NATIVE_CURSOR_INVALID"); return; }
        var track=state.currentTrack().orElseThrow();
        boolean changed=e.nativeElapsedTicks<0 || !track.equals(e.track) || elapsed<e.nativeElapsedTicks;
        if(changed && e.timeline.active()) stop(level.getServer(),source,e,now,"NATIVE_START_CHANGED");
        e.nativeElapsedTicks=elapsed; e.kind=OriginalSourceKind.VANILLA_NATIVE;
        if(!e.timeline.active() && OriginalRecovery.nativeTimeline(RETENTION.listeners(source),nearHolders(level.getServer(),source).size(),jukebox.getSongPlayer().isPlaying())) {
            e.remote.clear(); e.local.clear(); e.remoteOwned=false; e.finished=false;
            e.inventory=state.availableTracks(); e.track=track;
            e.timeline.bootstrap(track.mediaKey(),++sequence,now); e.reason="NATIVE_SERVER_TIMELINE"; e.changed=now;
            EtchedSpeakers.LOGGER.debug("[ES-NATIVE] SESSION_START source={} generation={} media={} elapsedTicks={}",
                    source,e.timeline.generation(),track.mediaKey(),elapsed);
            broadcast(level.getServer(),source,e);
        }
    }
    private static Set<UUID> nearHolders(MinecraftServer server,GlobalPos source) {
        var result=new HashSet<UUID>();
        for(var id:holders(server,source,null)) {
            var p=server.getPlayerList().getPlayer(id);
            if(p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(source.pos()))<=64*64) result.add(id);
        }
        return result;
    }
    /** Observation of an already-ticking real BE, once per second. No discovery scan, load or ticket. */
    public static void observeLocalNative(net.minecraft.server.level.ServerLevel level,net.minecraft.world.level.block.entity.JukeboxBlockEntity jukebox) {
        if(!jukebox.getSongPlayer().isPlaying() || level.getGameTime()%20!=0) return;
        var source=GlobalPos.of(level.dimension(),jukebox.getBlockPos());
        if(SOURCES.containsKey(source) || SOURCES.size()>=MAX_SOURCES || nearHolders(level.getServer(),source).isEmpty()) return;
        var state=AudioSourceResolver.readState(level,source.pos());
        if(state.reason()!=SourcePlaybackState.Reason.VANILLA_SONG_PLAYER || state.currentTrack().isEmpty()) return;
        var e=new Entry(); e.localOnly=true; SOURCES.put(source,e);
        updateNative(level,source,e,state,level.getGameTime());
        EtchedSpeakers.LOGGER.debug("[ES-LOCAL-ONLY] NATIVE_TIMELINE source={} generation={} localHolderCount={} remoteListenerCount=0 ticketHeld=false",
                source,e.timeline.generation(),nearHolders(level.getServer(),source).size());
    }
    /** Real native setTheItem starts/stops playback even when the media stays identical.
     * Invalidate only an existing native occurrence; never admit sources or create interest here. */
    public static void nativeRecordChanged(net.minecraft.server.level.ServerLevel level,net.minecraft.core.BlockPos pos) {
        var source=GlobalPos.of(level.dimension(),pos); var e=SOURCES.get(source);
        if(e!=null) stop(level.getServer(),source,e,level.getGameTime(),e.nativeElapsedTicks>=0?"NATIVE_RECORD_CHANGED":"RECORD_CHANGED");
    }
    private static void removePlayer(net.minecraft.world.entity.player.Player player) {
        WATCHERS.remove(player.getUUID()); BUDGETS.remove(player.getUUID());
        SOURCES.values().forEach(e->e.localViewers.remove(player.getUUID()));
        if(player instanceof ServerPlayer p) {
            reconcile(p.getServer(),p.getUUID());
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
        EtchedSpeakers.LOGGER.debug("[ES-REMOTE] {} source={} generation={} media={} frame={} sampleRate={} reason={}",event,source,e.timeline.generation(),e.timeline.media(),e.timeline.at(e.timeline.lastReport()),e.timeline.rate(),reason);
    }
}
