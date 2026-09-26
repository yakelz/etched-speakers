package net.yakel.etchedspeakers.remote;

import java.util.*;
import java.util.function.ToLongFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.compat.AudioSourceResolver;
import net.yakel.etchedspeakers.source.model.ListenerRetention;

/** Logical interests and physical runtime tickets have separate lifetimes. No persistence or blocking load. */
final class SourceRetention {
    static final TicketType<BlockPos> TICKET=TicketType.create("etchedspeakers_listener",Comparator.comparingLong(BlockPos::asLong));
    private final ListenerRetention<GlobalPos> lifecycle=new ListenerRetention<>(ListenerRetention.HANDOFF_GRACE);
    private final Map<GlobalPos,ServerLevel> levels=new HashMap<>();
    private final Set<GlobalPos> tickets=new HashSet<>();
    private final ToLongFunction<GlobalPos> generation;
    private long lastCapWarning=-1200;
    SourceRetention(ToLongFunction<GlobalPos> generation) { this.generation=generation; }
    boolean alive(GlobalPos source) { return lifecycle.get(source)!=null; }
    boolean ticketHeld(GlobalPos source) { return tickets.contains(source); }
    boolean ready(GlobalPos source) { var e=lifecycle.get(source); return e!=null && e.ready(); }
    int listeners(GlobalPos source) { var e=lifecycle.get(source); return e==null?0:e.listeners(); }
    int holders(GlobalPos source) { var e=lifecycle.get(source); return e==null?0:e.holders(); }
    boolean holder(GlobalPos source,UUID id) { var e=lifecycle.get(source); return e!=null && e.heldBy(id); }
    Set<GlobalPos> keys() { return lifecycle.keys(); }
    /** Admission for a real, validated local report. Reserves the same global 32-entry budget. */
    boolean admitLocal(ServerLevel level,GlobalPos source,Set<UUID> holders,long now) {
        if(alive(source)) return true;
        if(!lifecycle.request(source,Set.of(),holders,now)) return false;
        levels.put(source,level); lifecycle.ready(source);
        log("LOCAL_HOLDER_ADD",source,"VALID_LOCAL_REPORT");
        return true;
    }
    void reconcile(MinecraftServer server,Map<GlobalPos,Set<UUID>> remote,Map<GlobalPos,Set<UUID>> local,long now) {
        var previous=new HashMap<GlobalPos,Status>();
        for(var s:keys()) { var e=lifecycle.get(s); previous.put(s,new Status(e.listeners(),e.holders(),e.grace())); }
        lifecycle.reconcile(remote,local,now);
        var interested=new HashSet<GlobalPos>(remote.keySet()); interested.addAll(local.keySet());
        for(var source:interested) {
            var level=server.getLevel(source.dimension());
            if(level==null || lifecycle.denied(source)) continue;
            if(lifecycle.request(source,remote.getOrDefault(source,Set.of()),local.getOrDefault(source,Set.of()),now)) levels.put(source,level);
            else if(now-lastCapWarning>=1200) {
                lastCapWarning=now;
                EtchedSpeakers.LOGGER.warn("[ES-RETENTION] RETENTION_DENIED reason=CAP maxSources={}",ListenerRetention.MAX_RETAINED_SOURCES);
            }
        }
        for(var source:keys()) {
            var e=lifecycle.get(source); var old=previous.getOrDefault(source,new Status(0,0,-1));
            if(e.ticketNeeded() && tickets.add(source)) {
                var level=levels.get(source);
                level.getChunkSource().addRegionTicket(TICKET,new ChunkPos(source.pos()),0,source.pos(),false);
                if(level.getChunkSource().getChunkNow(source.pos().getX()>>4,source.pos().getZ()>>4)==null) lifecycle.loading(source,now);
                log(e.listeners()>0?"TICKET_REACQUIRE_REMOTE":"TICKET_ACQUIRE_HANDOFF",source,"SESSION_CONTINUITY");
            } else if(!e.ticketNeeded() && tickets.remove(source)) {
                levels.get(source).getChunkSource().removeRegionTicket(TICKET,new ChunkPos(source.pos()),0,source.pos(),false);
                log("TICKET_RELEASE_LOCAL_HOLDER",source,"NATURAL_PLAYER_TRACKING");
            }
            if(old.remote()!=e.listeners()) log(e.listeners()>old.remote()?"LISTENER_ADD":"LISTENER_REMOVE",source,"VALIDATED_SPEAKER");
            if(old.local()!=e.holders()) log(e.holders()>old.local()?"LOCAL_HOLDER_ADD":"LOCAL_HOLDER_REMOVE",source,"SOURCE_CHUNK_TRACKING");
            if(e.grace()!=old.grace()) log(e.grace()<0?"HANDOFF_GRACE_CANCEL":"HANDOFF_GRACE_BEGIN",source,"VALIDATED_INTEREST");
            if(e.listeners()>0 && old.remote()==0) log("SESSION_CONTINUE_REMOTE",source,"SAME_TIMELINE");
            else if(e.listeners()==0 && e.holders()>0 && (old.remote()>0 || old.local()==0)) log("SESSION_CONTINUE_LOCAL",source,"SAME_TIMELINE");
        }
    }
    /** Only logical expiry/invalidity closes a session. Dropping a redundant ticket never does. */
    Map<GlobalPos,String> tick(long now) {
        var released=new HashMap<GlobalPos,String>();
        for(var source:keys()) {
            var level=levels.get(source);
            var chunk=level.getChunkSource().getChunkNow(source.pos().getX()>>4,source.pos().getZ()>>4);
            String reason=null;
            if(chunk!=null) {
                if(!AudioSourceResolver.readState(level,source.pos()).available()) reason="INVALID_SOURCE";
                else if(!ready(source)) { lifecycle.ready(source); log("SOURCE_CHUNK_READY",source,"VALID_SOURCE_BE"); }
            }
            if(reason==null && lifecycle.expired(source,now)) reason=ready(source)?"HANDOFF_GRACE_EXPIRE":"LOAD_TIMEOUT";
            if(reason!=null) {
                if(reason.equals("HANDOFF_GRACE_EXPIRE")) log(reason,source,"NO_REMOTE_OR_LOCAL_INTEREST_1200_TICKS");
                release(source,!reason.equals("HANDOFF_GRACE_EXPIRE"),reason); released.put(source,reason);
            }
        }
        return released;
    }
    void release(GlobalPos source,boolean invalid,String reason) {
        if(!lifecycle.release(source,invalid)) return;
        var level=levels.remove(source);
        if(tickets.remove(source) && level!=null) level.getChunkSource().removeRegionTicket(TICKET,new ChunkPos(source.pos()),0,source.pos(),false);
        log("TICKET_RELEASE",source,reason);
    }
    void clear() { for(var s:keys()) release(s,false,"SERVER_STOP"); lifecycle.clear(); levels.clear(); tickets.clear(); lastCapWarning=-1200; }
    private record Status(int remote,int local,long grace) {}
    private void log(String event,GlobalPos source,String reason) {
        EtchedSpeakers.LOGGER.info("[ES-RETENTION] {} source={} generation={} remoteListenerCount={} localHolderCount={} ticketHeld={} reason={}",
                event,source,generation.applyAsLong(source),listeners(source),holders(source),ticketHeld(source),reason);
    }
}
