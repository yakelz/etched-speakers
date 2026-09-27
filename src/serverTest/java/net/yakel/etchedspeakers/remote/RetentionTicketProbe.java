package net.yakel.etchedspeakers.remote;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.yakel.etchedspeakers.EtchedSpeakers;

/** Opt-in dev probe of REAL chunk lifecycle; never enabled by a packet or in normal runs. */
@EventBusSubscriber(modid=EtchedSpeakers.MOD_ID)
public final class RetentionTicketProbe {
    private static final BlockPos POS=new BlockPos(32000,64,32000);
    private static int phase;
    private static long started;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(!Boolean.getBoolean("etchedspeakers.retentionProbe") || phase==3 || event.getServer().getTickCount()%5!=0) return;
        var level=event.getServer().overworld(); var chunks=level.getChunkSource(); var pos=new ChunkPos(POS);
        long now=level.getGameTime(); boolean loaded=chunks.getChunkNow(pos.x,pos.z)!=null;
        if(phase==0) {
            if(loaded) { EtchedSpeakers.LOGGER.error("[ES-RETENTION-PROBE] FAIL initially loaded"); phase=3; event.getServer().halt(false); return; }
            started=now;
            chunks.addRegionTicket(SourceRetention.TICKET,pos,0,POS,false);
            phase=1; EtchedSpeakers.LOGGER.info("[ES-RETENTION-PROBE] INITIALLY_UNLOADED ticket acquired pos={}",pos);
        } else if(phase==1 && loaded) {
            chunks.removeRegionTicket(SourceRetention.TICKET,pos,0,POS,false);
            phase=2; EtchedSpeakers.LOGGER.info("[ES-RETENTION-PROBE] CHUNK_READY getChunkNow=non-null ticket released");
        } else if(phase==2 && !loaded) {
            phase=3; EtchedSpeakers.LOGGER.info("[ES-RETENTION-PROBE] PASS getChunkNow=null after release elapsedTicks={}",now-started);
        } else if(now-started>1200) {
            chunks.removeRegionTicket(SourceRetention.TICKET,pos,0,POS,false);
            phase=3; EtchedSpeakers.LOGGER.error("[ES-RETENTION-PROBE] FAIL timeout");
        }
        if(phase==3) event.getServer().halt(false);
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) {
        if(phase==1) event.getServer().overworld().getChunkSource().removeRegionTicket(SourceRetention.TICKET,new ChunkPos(POS),0,POS,false);
        phase=0;
    }
}
