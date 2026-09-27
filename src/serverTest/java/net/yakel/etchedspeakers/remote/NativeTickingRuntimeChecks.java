package net.yakel.etchedspeakers.remote;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.yakel.etchedspeakers.network.RemotePayloads.*;
import net.yakel.etchedspeakers.registry.ModBlocks;
import net.yakel.etchedspeakers.source.model.*;
import static net.yakel.etchedspeakers.source.model.SourceTicketMode.*;

/** Actual chunk/BE/ticket lifecycle. Only remote player's transport is synthetic. No natural
 * ticking ticket, resetEmptyTime, cursor increment, or production ticket substitute in this fixture. */
@EventBusSubscriber(modid=EtchedSpeakers.MOD_ID)
public final class NativeTickingRuntimeChecks {
    private static final BlockPos POS=new BlockPos(36008,64,36008), OTHER=POS.offset(1,0,0), SPEAKER=POS.offset(512,0,0);
    private static final TicketType<BlockPos> FIXTURE=TicketType.create("native_ticking_fixture",Comparator.comparingLong(BlockPos::asLong));
    private static final UUID ID=new UUID(921,921), EPOCH=new UUID(922,922);
    private static final List<Snapshot> snapshots=new ArrayList<>();
    private static int phase,checks;
    private static long start,next,elapsed,generation,grace;
    private static ServerPlayer player;
    private static GlobalPos source,other;
    private static JukeboxBlockEntity jukebox,second;
    private static SourceRetention retention;
    private static List<BlockPos> interests=List.of();
    private static Field field(Class<?> type,String name) throws Exception { var f=type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static void check(boolean result,String reason) { if(!result) throw new AssertionError(reason); checks++; EtchedSpeakers.LOGGER.info("[ES-NATIVE-TICK-TEST] CHECK {} {}",checks,reason); }
    private static void renew() { RemoteSessions.interest(player,new Interest(EPOCH,player.level().dimension().location(),interests)); }
    private static Snapshot latest() { return snapshots.stream().filter(s->s.source().equals(source)).reduce((a,b)->b).orElseThrow(); }
    @SuppressWarnings("unchecked") private static Map<UUID,ServerPlayer> players(net.minecraft.server.MinecraftServer s) throws Exception {
        return (Map<UUID,ServerPlayer>)field(net.minecraft.server.players.PlayerList.class,"playersByUUID").get(s.getPlayerList());
    }
    private static long cursor() { return jukebox.getSongPlayer().getTicksSinceSongStarted(); }
    private static void footprint(ServerLevel level,String stage) throws Exception {
        var center=new ChunkPos(POS); int full=0,block=0,entity=0;
        var statuses=new ArrayList<String>();
        for(int z=-3;z<=3;z++) for(int x=-3;x<=3;x++) {
            var chunk=level.getChunkSource().getChunkNow(center.x+x,center.z+z);
            if(chunk!=null) {
                var status=chunk.getFullStatus(); if(status.isOrAfter(FullChunkStatus.FULL)) full++;
                if(status.isOrAfter(FullChunkStatus.BLOCK_TICKING)) block++;
                if(status.isOrAfter(FullChunkStatus.ENTITY_TICKING)) entity++;
                statuses.add(x+","+z+":"+status);
            }
        }
        var chunk=level.getChunkSource().getChunkNow(center.x,center.z);
        EtchedSpeakers.LOGGER.info("[ES-NATIVE-TICK-TEST] FOOTPRINT stage={} sourceChunk={} mode={} loaded={} status={} blockRange={} entitiesLoaded={} entityTicking={} forceTicks={} full={} block={} entity={} radius=3 statuses={}",
                stage,center,retention==null?NONE:retention.ticketMode(source),chunk!=null,chunk==null?"ABSENT":chunk.getFullStatus(),
                level.shouldTickBlocksAt(center.toLong()),level.areEntitiesLoaded(center.toLong()),level.isPositionEntityTicking(POS),
                level.getChunkSource().chunkMap.getDistanceManager().shouldForceTicks(center.toLong()),full,block,entity,statuses);
        @SuppressWarnings("unchecked") var holders=(it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkHolder>)field(ChunkMap.class,"visibleChunkMap").get(level.getChunkSource().chunkMap);
        var levels=new TreeMap<Integer,Integer>(); int radius=0;
        for(var holder:holders.values()) {
            var pos=holder.getPos(); int distance=Math.max(Math.abs(pos.x-center.x),Math.abs(pos.z-center.z));
            if(distance<=16) { levels.merge(holder.getTicketLevel(),1,Integer::sum); radius=Math.max(radius,distance); }
        }
        EtchedSpeakers.LOGGER.info("[ES-NATIVE-TICK-TEST] HOLDER_FOOTPRINT stage={} scanRadius=16 outermost={} levels={}",stage,radius,levels);
        if(stage.equals("NATIVE")) {
            check(full==9 && block==1 && entity==0,"measured 3x3 FULL support with one BLOCK_TICKING and zero ENTITY_TICKING chunks");
            check(level.areEntitiesLoaded(center.toLong()) && level.shouldTickBlocksAt(center.toLong()),"actual BE ticking prerequisites present");
        }
    }
    @SuppressWarnings("unchecked") private static long nativeTickets(ServerLevel level) throws Exception {
        var manager=level.getChunkSource().chunkMap.getDistanceManager();
        var tickets=(it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.util.SortedArraySet<Ticket<?>>>)field(DistanceManager.class,"tickets").get(manager);
        var at=tickets.get(new ChunkPos(POS).toLong());
        return at==null?0:at.stream().filter(t->t.getType()==SourceRetention.NATIVE_TICKET && t.getTicketLevel()==32 && t.isForceTicks()).count();
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(!Boolean.getBoolean("etchedspeakers.nativeTickingValidation") || phase==99) return;
        var server=event.getServer(); var level=server.overworld(); var chunks=level.getChunkSource(); var cp=new ChunkPos(POS); long now=level.getGameTime();
        try {
            if(phase==0) {
                start=now; source=GlobalPos.of(level.dimension(),POS); other=GlobalPos.of(level.dimension(),OTHER);
                retention=(SourceRetention)field(RemoteSessions.class,"RETENTION").get(null);
                check(level.players().isEmpty(),"no real nearby players or natural tracking");
                check(retention.keys().isEmpty() && nativeTickets(level)==0 && level.getForcedChunks().isEmpty(),"startup has no restored native/forced tickets");
                footprint(level,"BEFORE"); check(chunks.getChunkNow(cp.x,cp.z)==null,"source initially unloaded");
                chunks.addRegionTicket(FIXTURE,cp,0,POS,false);
                chunks.addRegionTicket(FIXTURE,new ChunkPos(SPEAKER),0,SPEAKER,false); phase=1;
            } else if(phase==1 && chunks.getChunkNow(cp.x,cp.z)!=null && chunks.getChunkNow(SPEAKER.getX()>>4,SPEAKER.getZ()>>4)!=null) {
                level.setBlock(POS,Blocks.JUKEBOX.defaultBlockState(),3);
                jukebox=(JukeboxBlockEntity)level.getBlockEntity(POS); jukebox.setTheItem(ItemStack.EMPTY);
                jukebox.setTheItem(new ItemStack(Items.MUSIC_DISC_CREATOR)); elapsed=cursor();
                for(var p:List.of(SPEAKER,SPEAKER.offset(1,0,0))) {
                    level.setBlock(p,ModBlocks.SPEAKER.get().defaultBlockState(),3);
                    ((SpeakerBlockEntity)level.getBlockEntity(p)).setSource(level.dimension(),p.equals(SPEAKER)?POS:OTHER);
                }
                var profile=new GameProfile(ID,"NativeTickProbe"); player=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
                player.setPos(SPEAKER.getX()+0.5,SPEAKER.getY()+1,SPEAKER.getZ()+0.5);
                player.connection=new ServerGamePacketListenerImpl(server,new Connection(PacketFlow.SERVERBOUND),player,CommonListenerCookie.createInitial(profile,false)) {
                    @Override public void send(Packet<?> packet,PacketSendListener listener) {
                        if(packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof Snapshot s) snapshots.add(s);
                    }
                };
                players(server).put(ID,player); phase=2; next=now+40;
            } else if(phase>=2 && now>=next) {
                switch(phase) {
                    case 2 -> {
                        footprint(level,"LOAD_ONLY"); check(cursor()==elapsed,"load-only baseline freezes actual BE cursor");
                        chunks.addRegionTicket(FIXTURE,cp,0,POS,true); // Deliberately demonstrate flag alone is insufficient.
                        phase=3; next=now+40;
                    }
                    case 3 -> {
                        footprint(level,"FLAG_ONLY"); check(cursor()==elapsed,"forceTicks alone at level 33 does not tick BE");
                        chunks.removeRegionTicket(FIXTURE,cp,0,POS,true);
                        // compareTo ignores forceTicks: previous false ticket was the same sorted key and is removed too.
                        interests=List.of(SPEAKER); renew(); phase=4; next=now+40;
                    }
                    case 4 -> {
                        check(retention.ticketMode(source)==TICKING_NATIVE && retention.listeners(source)==1 && retention.holders(source)==0,"validated remote interest acquires production native ticket");
                        footprint(level,"NATIVE"); check(nativeTickets(level)==1,"exactly one production level32 forced native ticket");
                        check(cursor()>elapsed && !latest().paused(),"BE and snapshot really advance");
                        elapsed=cursor(); generation=latest().generation(); phase=5; next=now+200;
                    }
                    case 5 -> {
                        check(cursor()-elapsed==200,"200 real server ticks advance native cursor by exactly 200");
                        check(latest().generation()==generation,"remote native continuation keeps generation");
                        interests=List.of(); renew(); phase=6; next=now+10;
                    }
                    case 6 -> {
                        var model=(ListenerRetention<?>)field(SourceRetention.class,"lifecycle").get(retention);
                        @SuppressWarnings("unchecked") var typed=(ListenerRetention<GlobalPos>)model;
                        grace=typed.get(source).grace();
                        check(grace-now>=1190 && grace-now<=1200,"real grace uses 1200 ticks");
                        check(retention.ticketMode(source)==TICKING_NATIVE,"grace retains ticking mode without listeners");
                        elapsed=cursor(); phase=7; next=now+400;
                    }
                    case 7 -> {
                        check(cursor()-elapsed==400,"empty dimension BE ticks beyond vanilla 300-tick sleep threshold during grace");
                        interests=List.of(SPEAKER); renew(); phase=8; next=now+10;
                    }
                    case 8 -> {
                        check(retention.listeners(source)==1 && latest().generation()==generation,"return during grace keeps occurrence");
                        interests=List.of(); renew(); phase=9; next=now+10;
                    }
                    case 9 -> {
                        @SuppressWarnings("unchecked") var model=(ListenerRetention<GlobalPos>)field(SourceRetention.class,"lifecycle").get(retention);
                        grace=model.get(source).grace(); elapsed=cursor(); phase=10; next=grace-1;
                    }
                    case 10 -> {
                        check(retention.alive(source) && retention.ticketMode(source)==TICKING_NATIVE,"ticket survives to final grace tick");
                        check(cursor()>elapsed+1100,"native cursor advances through entire grace");
                        phase=11; next=grace+20;
                    }
                    case 11 -> {
                        check(!retention.alive(source) && retention.ticketMode(source)==NONE && nativeTickets(level)==0,"grace expiry removes logical and actual native ticket");
                        footprint(level,"RELEASE"); elapsed=cursor(); phase=12; next=now+80;
                    }
                    case 12 -> {
                        check(chunks.getChunkNow(cp.x,cp.z)==null,"source unloads without fixture support after release");
                        check(cursor()==elapsed,"detached BE no longer ticks after release");
                        retention.release(source,false,"FIXTURE_REPEAT"); check(nativeTickets(level)==0,"repeat release harmless");
                        interests=List.of(SPEAKER); renew(); phase=13; next=now+60;
                    }
                    case 13 -> {
                        check(retention.ready(source) && retention.ticketMode(source)==TICKING_NATIVE,"initially unloaded source async loads then upgrades after server validation");
                        jukebox=(JukeboxBlockEntity)chunks.getChunkNow(cp.x,cp.z).getBlockEntity(POS);
                        var song=JukeboxSong.fromStack(level.registryAccess(),jukebox.getTheItem()).orElseThrow();
                        jukebox.getSongPlayer().setSongWithoutPlaying(song,song.value().lengthInTicks()+19L);
                        phase=14; next=now+20;
                    }
                    case 14 -> {
                        check(!jukebox.getSongPlayer().isPlaying() && !latest().active(),"real natural end stops session and subscriber");
                        check(retention.ticketMode(source)==LOAD_ONLY && nativeTickets(level)==0,"natural end removes ticking demand while listener retention remains");
                        jukebox.setTheItem(ItemStack.EMPTY); jukebox.setTheItem(new ItemStack(Items.MUSIC_DISC_CREATOR));
                        generation=latest().generation(); phase=15; next=now+20;
                    }
                    case 15 -> {
                        check(latest().active() && latest().generation()>generation && retention.ticketMode(source)==TICKING_NATIVE,"same disc reinsert creates new native occurrence/ticket");
                        generation=latest().generation(); jukebox.setTheItem(ItemStack.EMPTY); jukebox.setTheItem(new ItemStack(Items.MUSIC_DISC_PIGSTEP));
                        phase=16; next=now+20;
                    }
                    case 16 -> {
                        check(latest().generation()>generation && latest().media().endsWith("pigstep"),"different disc changes native identity");
                        level.setBlock(OTHER,Blocks.JUKEBOX.defaultBlockState(),3); second=(JukeboxBlockEntity)level.getBlockEntity(OTHER);
                        second.setTheItem(new ItemStack(Items.MUSIC_DISC_CREATOR));
                        interests=List.of(SPEAKER,SPEAKER.offset(1,0,0)); renew(); phase=17; next=now+20;
                    }
                    case 17 -> {
                        check(retention.ticketMode(other)==TICKING_NATIVE && nativeTickets(level)==2,"two BlockPos owners in one chunk have independent native tickets");
                        jukebox.setTheItem(ItemStack.EMPTY); phase=18; next=now+20;
                    }
                    case 18 -> {
                        check(!latest().active() && retention.ticketMode(source)==LOAD_ONLY,"eject stops subscriber and downgrades native demand");
                        check(retention.ticketMode(other)==TICKING_NATIVE && nativeTickets(level)==1,"eject of first owner preserves second owner ticket");
                        elapsed=second.getSongPlayer().getTicksSinceSongStarted(); phase=19; next=now+40;
                    }
                    case 19 -> {
                        check(second.getSongPlayer().getTicksSinceSongStarted()-elapsed==40,"second source really continues after first eject");
                        level.destroyBlock(OTHER,false); phase=20; next=now+20;
                    }
                    case 20 -> {
                        check(!retention.alive(other) && nativeTickets(level)==0,"source break removes its own runtime ticket");
                        jukebox.setTheItem(new ItemStack(Items.MUSIC_DISC_CREATOR)); phase=21; next=now+20;
                    }
                    case 21 -> {
                        check(retention.ticketMode(source)==TICKING_NATIVE,"native ticket reacquired for cleanup tests");
                        RemoteSessions.unload(new net.neoforged.neoforge.event.level.LevelEvent.Unload(level));
                        check(!retention.alive(source) && nativeTickets(level)==0,"production dimension unload removes ticking tickets");
                        interests=List.of(SPEAKER); renew(); phase=22; next=now+20;
                    }
                    case 22 -> {
                        check(retention.ticketMode(source)==TICKING_NATIVE,"source revalidated after fixture unload event");
                        RemoteSessions.shutdown(new net.neoforged.neoforge.event.server.ServerStoppingEvent(server));
                        check(retention.keys().isEmpty() && nativeTickets(level)==0,"production server stop removes all runtime ticket modes");
                        RemoteSessions.shutdown(new net.neoforged.neoforge.event.server.ServerStoppingEvent(server));
                        check(level.getForcedChunks().isEmpty(),"no vanilla forced chunk persistence");
                        EtchedSpeakers.LOGGER.info("[ES-NATIVE-TICK-TEST] PASS checks={} elapsedTicks={}",checks,now-start); phase=99;
                    }
                }
            }
            if(phase>=4 && phase<99 && now%40==0) renew();
            if(now-start>3500) throw new AssertionError("fixture timeout phase="+phase);
        } catch(Throwable failure) { EtchedSpeakers.LOGGER.error("[ES-NATIVE-TICK-TEST] FAIL phase="+phase,failure); phase=99; }
        if(phase==99) {
            try { players(server).remove(ID); } catch(Exception ignored) {}
            if(retention!=null) retention.clear();
            chunks.removeRegionTicket(FIXTURE,cp,0,POS,true); chunks.removeRegionTicket(FIXTURE,cp,0,POS,false);
            chunks.removeRegionTicket(FIXTURE,new ChunkPos(SPEAKER),0,SPEAKER,false); server.halt(false);
        }
    }
}
