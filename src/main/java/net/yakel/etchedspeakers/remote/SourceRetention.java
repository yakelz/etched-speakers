package net.yakel.etchedspeakers.remote;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.compat.AudioSourceResolver;
import net.yakel.etchedspeakers.source.model.ListenerRetention;

/** Runtime tickets only. No SavedData, NBT, forceload or synchronous chunk requests. */
final class SourceRetention {
    static final TicketType<BlockPos> TICKET=TicketType.create("etchedspeakers_listener", Comparator.comparingLong(BlockPos::asLong));
    private final ListenerRetention<GlobalPos> lifecycle=new ListenerRetention<>();
    private final Map<GlobalPos,ServerLevel> owners=new HashMap<>();
    private long lastCapWarning=-1200;
    boolean held(GlobalPos source) { return lifecycle.get(source)!=null; }
    boolean ready(GlobalPos source) { var e=lifecycle.get(source); return e!=null && e.ready(); }
    int listeners(GlobalPos source) { var e=lifecycle.get(source); return e==null?0:e.listeners(); }
    Set<GlobalPos> keys() { return lifecycle.keys(); }
    void reconcile(MinecraftServer server, Map<GlobalPos,Set<UUID>> validated, long now) {
        var oldGrace=new HashMap<GlobalPos,Long>();
        var oldCounts=new HashMap<GlobalPos,Integer>();
        for(var s:keys()) oldCounts.put(s,listeners(s));
        for(var s:keys()) oldGrace.put(s,lifecycle.get(s).grace());
        lifecycle.reconcile(validated,now);
        for(var item:validated.entrySet()) {
            var source=item.getKey(); var level=server.getLevel(source.dimension());
            if(level==null || lifecycle.denied(source)) continue;
            boolean existed=held(source);
            if(lifecycle.request(source,item.getValue(),now)) {
                if(!existed) {
                    owners.put(source,level);
                    level.getChunkSource().addRegionTicket(TICKET,new ChunkPos(source.pos()),0,source.pos(),false);
                    log("TICKET_ACQUIRE",source,"VALIDATED_SPEAKER");
                }
            } else if(now-lastCapWarning>=1200) {
                lastCapWarning=now;
                EtchedSpeakers.LOGGER.warn("[ES-RETENTION] RETENTION_DENIED reason=CAP maxSources={}",ListenerRetention.MAX_RETAINED_SOURCES);
            }
        }
        for(var s:keys()) {
            int previous=oldCounts.getOrDefault(s,0);
            if(previous!=listeners(s)) log(listeners(s)>previous?"LISTENER_ADD":"LISTENER_REMOVE",s,"SUBSCRIPTION");
            long grace=lifecycle.get(s).grace(), old=oldGrace.getOrDefault(s,-1L);
            if(grace!=old) log(grace<0?"GRACE_CANCEL":"GRACE_BEGIN",s,"SUBSCRIPTIONS_CHANGED");
        }
    }
    /** Returns released sources, so the canonical session can be closed in the same server tick. */
    Map<GlobalPos,String> tick(long now) {
        var released=new HashMap<GlobalPos,String>();
        for(var source:keys()) {
            var level=owners.get(source);
            var chunk=level.getChunkSource().getChunkNow(source.pos().getX()>>4,source.pos().getZ()>>4);
            String reason=null;
            if(chunk!=null) {
                var state=AudioSourceResolver.readState(level,source.pos());
                if(!state.available()) reason="INVALID_SOURCE";
                else if(!ready(source)) { lifecycle.ready(source); log("SOURCE_CHUNK_READY",source,"VALID_SOURCE_BE"); }
            }
            if(reason==null && lifecycle.expired(source,now)) reason=ready(source)?"GRACE_EXPIRED":"LOAD_TIMEOUT";
            if(reason!=null) { release(source,!reason.equals("GRACE_EXPIRED"),reason); released.put(source,reason); }
        }
        return released;
    }
    void release(GlobalPos source, boolean invalid, String reason) {
        if(!lifecycle.release(source,invalid)) return;
        var level=owners.remove(source);
        if(level!=null) level.getChunkSource().removeRegionTicket(TICKET,new ChunkPos(source.pos()),0,source.pos(),false);
        log("TICKET_RELEASE",source,reason);
    }
    void clear() { for(var s:keys()) release(s,false,"SERVER_STOP"); lifecycle.clear(); owners.clear(); lastCapWarning=-1200; }
    private void log(String event, GlobalPos source, String reason) {
        EtchedSpeakers.LOGGER.info("[ES-RETENTION] {} source={} listeners={} reason={}",event,source,listeners(source),reason);
    }
}
