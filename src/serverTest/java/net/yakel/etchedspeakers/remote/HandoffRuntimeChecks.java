package net.yakel.etchedspeakers.remote;

import gg.moonflower.etched.common.blockentity.AlbumJukeboxBlockEntity;
import gg.moonflower.etched.core.registry.EtchedBlocks;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.compat.AudioSourceResolver;
import net.yakel.etchedspeakers.source.model.*;

/** Real dedicated-server lifecycle with controlled validated-interest inputs; no real player/audio claim.
 * Only loaded by the optional init script in a separate disposable world, never packaged in the mod.
 */
@EventBusSubscriber(modid=EtchedSpeakers.MOD_ID)
public final class HandoffRuntimeChecks {
    private static final BlockPos POS=new BlockPos(32000,64,32000);
    private static final TicketType<BlockPos> FIXTURE=TicketType.create("handoff_fixture",Comparator.comparingLong(BlockPos::asLong));
    private static final UUID HOLDER=new UUID(100,100),EPOCH=new UUID(200,200);
    private static int phase,checks;
    private static long started,graceTick;
    private static SourceRetention retention;
    private static Map<GlobalPos,Object> sessions;
    private static Object entry;
    private static RemoteTimeline timeline;
    private static RemoteObserverLease local,remote;
    private static GlobalPos source;
    private static Field field(Class<?> type,String name) throws Exception {
        var f=type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void check(boolean ok,String message) {
        if(!ok) throw new AssertionError(message);
        checks++; EtchedSpeakers.LOGGER.info("[ES-HANDOFF-TEST] CHECK {} {}",checks,message);
    }
    @SuppressWarnings("unchecked")
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(!Boolean.getBoolean("etchedspeakers.handoffValidation") || phase==5) return;
        var server=event.getServer(); var level=server.overworld(); var chunks=level.getChunkSource();
        long now=level.getGameTime(); var cp=new ChunkPos(POS);
        try {
            if(phase==0) {
                started=now; source=GlobalPos.of(level.dimension(),POS);
                check(server.getPlayerList().getPlayerCount()==0,"isolated server has no players");
                check(chunks.getChunkNow(cp.x,cp.z)==null,"fixture chunk initially unloaded");
                chunks.addRegionTicket(FIXTURE,cp,0,POS,false); phase=1;
            } else if(phase==1 && chunks.getChunkNow(cp.x,cp.z)!=null) {
                level.setBlock(POS,EtchedBlocks.ALBUM_JUKEBOX.get().defaultBlockState(),3);
                var album=(AlbumJukeboxBlockEntity)level.getBlockEntity(POS);
                album.setItem(0,new ItemStack(Items.MUSIC_DISC_CAT));
                album.setItem(1,new ItemStack(Items.MUSIC_DISC_BLOCKS));
                var state=AudioSourceResolver.readState(level,POS);
                check(state.availableTracks().size()==2,"real Album inventory resolves two playable tracks");
                retention=(SourceRetention)field(RemoteSessions.class,"RETENTION").get(null);
                sessions=(Map<GlobalPos,Object>)field(RemoteSessions.class,"SOURCES").get(null);
                check(retention.admitLocal(level,source,Set.of(HOLDER),now),"validated local admission reserves entry");
                check(retention.alive(source) && !retention.ticketHeld(source),"local-only entry has no runtime ticket");
                var type=Class.forName(RemoteSessions.class.getName()+"$Entry");
                var constructor=type.getDeclaredConstructor(); constructor.setAccessible(true); entry=constructor.newInstance();
                timeline=(RemoteTimeline)field(type,"timeline").get(entry);
                local=(RemoteObserverLease)field(type,"local").get(entry);
                remote=(RemoteObserverLease)field(type,"remote").get(entry);
                var first=state.availableTracks().getFirst();
                timeline.bootstrap(first.mediaKey(),1,now-600);
                check(timeline.remoteProgress(0,44100,now-600),"fixture negotiates rate before advancing clock");
                timeline.advance(now);
                field(RemoteSessions.class,"sequence").setLong(null,1);
                field(type,"track").set(entry,first); field(type,"inventory").set(entry,state.availableTracks());
                field(type,"remoteOwned").setBoolean(entry,true); sessions.put(source,entry);
                local.ready(HOLDER,EPOCH,1,now); local.elect(Set.of(HOLDER),now);
                check(local.accepts(HOLDER,EPOCH,1,local.tokenFor(HOLDER),now),"aligned-local lease assigned in fixture");
                check(timeline.handoffEnd(44100*30L,44100,now),"assigned terminal cursor accepted");
                var next=RemoteSessions.class.getDeclaredMethod("next",net.minecraft.server.MinecraftServer.class,
                        GlobalPos.class,type,long.class); next.setAccessible(true); next.invoke(null,server,source,entry,now);
                check(timeline.generation()==2 && !timeline.media().equals(first.mediaKey()),"production next selects second real Album track once");
                check(album.getPlayingIndex()==1 && timeline.at(now)==0,"Album selection and new-track frame agree");
                check(local.owner()==null && remote.owner()==null,"track transition clears both observer authorities");
                check(!timeline.matches(1,first.mediaKey()),"duplicate old-generation EOF is stale");
                timeline.remoteProgress(0,44100,now);
                local.ready(HOLDER,EPOCH,2,now); local.elect(Set.of(HOLDER),now);
                // Production maintenance now sees no actual holders, starts grace and acquires its own ticket.
                phase=2;
            } else if(phase==2 && retention.ticketHeld(source)) {
                var model=(ListenerRetention<GlobalPos>)field(SourceRetention.class,"lifecycle").get(retention);
                graceTick=model.get(source).grace();
                check(graceTick>now && graceTick-now<=1200,"production starts bounded 1200-tick grace");
                chunks.removeRegionTicket(FIXTURE,cp,0,POS,false); phase=3;
                check(retention.listeners(source)==0 && retention.holders(source)==0,"no roles during transit");
            } else if(phase==3) {
                if(now<graceTick) {
                    if(now==graceTick-1) {
                        check(retention.alive(source) && retention.ticketHeld(source) && timeline.active(),"entry ticket and timeline survive through grace boundary");
                        check(chunks.getChunkNow(cp.x,cp.z)!=null,"runtime ticket actually keeps source chunk loaded");
                    }
                } else if(!retention.alive(source)) {
                    check(now-graceTick<=5,"expiry occurs within production five-tick maintenance cadence");
                    check(!retention.ticketHeld(source) && !timeline.active(),"expiry releases ticket and stops actual canonical timeline");
                    check(!sessions.containsKey(source) && local.owner()==null && remote.owner()==null,"expiry removes source and both observer states");
                    retention.release(source,false,"TEST_IDEMPOTENCE");
                    check(!retention.alive(source) && !retention.ticketHeld(source),"repeated cleanup is harmless");
                    phase=4;
                }
            } else if(phase==4 && chunks.getChunkNow(cp.x,cp.z)==null) {
                check(true,"source chunk really unloads after all fixture/runtime tickets release");
                EtchedSpeakers.LOGGER.info("[ES-HANDOFF-TEST] PASS checks={} elapsedTicks={}",checks,now-started);
                phase=5; server.halt(false);
            }
            if(now-started>2000) throw new AssertionError("runtime lifecycle timeout phase="+phase);
        } catch(Throwable failure) {
            EtchedSpeakers.LOGGER.error("[ES-HANDOFF-TEST] FAIL phase="+phase,failure);
            chunks.removeRegionTicket(FIXTURE,cp,0,POS,false);
            if(retention!=null) retention.clear(); phase=5; server.halt(false);
        }
    }
}
