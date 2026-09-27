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
import net.yakel.etchedspeakers.source.model.SourceTicketMode;

/** Logical interests and physical runtime tickets have separate lifetimes. No persistence or blocking load. */
final class SourceRetention {
    static final TicketType<BlockPos> TICKET=TicketType.create("etchedspeakers_listener",Comparator.comparingLong(BlockPos::asLong));
    static final TicketType<BlockPos> NATIVE_TICKET=TicketType.create("etchedspeakers_native_ticking",Comparator.comparingLong(BlockPos::asLong));
    private final ListenerRetention<GlobalPos> lifecycle=new ListenerRetention<>(ListenerRetention.HANDOFF_GRACE);
    private final Map<GlobalPos,ServerLevel> levels=new HashMap<>();
    private final Map<GlobalPos,SourceTicketMode> tickets=new HashMap<>();
    private final ToLongFunction<GlobalPos> generation;
    private long lastCapWarning=-1200;
    SourceRetention(ToLongFunction<GlobalPos> generation) { this.generation=generation; }
    boolean alive(GlobalPos source) { return lifecycle.get(source)!=null; }
    boolean ticketHeld(GlobalPos source) { return tickets.containsKey(source); }
    SourceTicketMode ticketMode(GlobalPos source) { return tickets.getOrDefault(source,SourceTicketMode.NONE); }
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
            if(e.ticketNeeded() && !ticketHeld(source)) {
                var level=levels.get(source);
                mode(source,SourceTicketMode.LOAD_ONLY,"SESSION_CONTINUITY");
                if(level.getChunkSource().getChunkNow(source.pos().getX()>>4,source.pos().getZ()>>4)==null) lifecycle.loading(source,now);
                log(e.listeners()>0?"TICKET_REACQUIRE_REMOTE":"TICKET_ACQUIRE_HANDOFF",source,"SESSION_CONTINUITY");
            } else if(!e.ticketNeeded() && ticketHeld(source) && !(e.nativeRemote() && ticketMode(source)==SourceTicketMode.TICKING_NATIVE)) {
                mode(source,SourceTicketMode.NONE,"NATURAL_PLAYER_TRACKING");
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
                var state=AudioSourceResolver.readState(level,source.pos());
                if(!state.available()) reason="INVALID_SOURCE";
                else if(!ready(source)) { lifecycle.ready(source); log("SOURCE_CHUNK_READY",source,"VALID_SOURCE_BE"); }
                var entry=lifecycle.get(source);
                entry.nativeRemote(SourceTicketMode.nativePlaying(state) && (entry.nativeRemote() || entry.listeners()>0));
                // Chunk tracking alone does not prove natural BLOCK_TICKING. Once a real remote
                // native occurrence admitted the ticket, keep it across local handoff until stop
                // or lifetime expiry, instead of flapping against our own observed ticking status.
                boolean continuity=entry.grace()>=0 || entry.holders()>0;
                mode(source,SourceTicketMode.desired(entry.ticketNeeded() || entry.nativeRemote() && continuity,
                        entry.nativeRemote()?SourceTicketMode.TICKING_NATIVE:SourceTicketMode.LOAD_ONLY,
                        state,entry.listeners(),continuity),"SERVER_SOURCE_STATE");
            }
            if(reason==null && lifecycle.expired(source,now)) reason=ready(source)?"HANDOFF_GRACE_EXPIRE":"LOAD_TIMEOUT";
            if(reason!=null) {
                if(reason.equals("HANDOFF_GRACE_EXPIRE")) log(reason,source,"NO_REMOTE_OR_LOCAL_INTEREST_1200_TICKS");
                release(source,!reason.equals("HANDOFF_GRACE_EXPIRE"),reason); released.put(source,reason);
            } else if(ticketMode(source)==SourceTicketMode.TICKING_NATIVE) {
                // Raw runtime region tickets are not in ForcedChunksSavedData. Without this public
                // wakeup, an entirely empty dimension skips every BE after 300 ticks of grace.
                level.resetEmptyTime();
                if(now%200==0 && chunk!=null && chunk.getBlockEntity(source.pos()) instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity jukebox)
                    EtchedSpeakers.LOGGER.debug("[ES-NATIVE-TICKING] PROGRESS source={} generation={} elapsedTicks={} status={} entitiesLoaded={} remoteListeners={} holders={}",
                            source,generation.applyAsLong(source),jukebox.getSongPlayer().getTicksSinceSongStarted(),chunk.getFullStatus(),
                            level.areEntitiesLoaded(new ChunkPos(source.pos()).toLong()),listeners(source),holders(source));
            }
        }
        return released;
    }
    void release(GlobalPos source,boolean invalid,String reason) {
        if(!lifecycle.release(source,invalid)) return;
        mode(source,SourceTicketMode.NONE,reason);
        levels.remove(source);
        log("TICKET_RELEASE",source,reason);
    }
    void stopNative(GlobalPos source) {
        var entry=lifecycle.get(source); if(entry!=null) entry.nativeRemote(false);
        if(ticketMode(source)==SourceTicketMode.TICKING_NATIVE) mode(source,SourceTicketMode.LOAD_ONLY,"SESSION_STOPPED");
    }
    private void mode(GlobalPos source,SourceTicketMode next,String reason) {
        var old=ticketMode(source);
        if(old==next) return;
        var level=levels.get(source); var chunks=level.getChunkSource(); var pos=new ChunkPos(source.pos());
        // Add before remove on the server thread: coverage never drops; only one mode remains.
        if(next!=SourceTicketMode.NONE) chunks.addRegionTicket(next==SourceTicketMode.TICKING_NATIVE?NATIVE_TICKET:TICKET,
                pos,next==SourceTicketMode.TICKING_NATIVE?1:0,source.pos(),next==SourceTicketMode.TICKING_NATIVE);
        if(old!=SourceTicketMode.NONE) chunks.removeRegionTicket(old==SourceTicketMode.TICKING_NATIVE?NATIVE_TICKET:TICKET,
                pos,old==SourceTicketMode.TICKING_NATIVE?1:0,source.pos(),old==SourceTicketMode.TICKING_NATIVE);
        if(next==SourceTicketMode.NONE) tickets.remove(source); else tickets.put(source,next);
        EtchedSpeakers.LOGGER.debug("[ES-RETENTION] TICKET_MODE source={} chunk={} generation={} old={} new={} level={} forceTicks={} reason={}",
                source,pos,generation.applyAsLong(source),old,next,next==SourceTicketMode.NONE?-1:next==SourceTicketMode.TICKING_NATIVE?32:33,
                next==SourceTicketMode.TICKING_NATIVE,reason);
    }
    void clear() { for(var s:keys()) release(s,false,"SERVER_STOP"); lifecycle.clear(); levels.clear(); tickets.clear(); lastCapWarning=-1200; }
    private record Status(int remote,int local,long grace) {}
    private void log(String event,GlobalPos source,String reason) {
        EtchedSpeakers.LOGGER.atLevel(reason.equals("INVALID_SOURCE") ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.DEBUG)
                .log("[ES-RETENTION] {} source={} generation={} remoteListenerCount={} localHolderCount={} ticketHeld={} reason={}",
                event,source,generation.applyAsLong(source),listeners(source),holders(source),ticketHeld(source),reason);
    }
}
