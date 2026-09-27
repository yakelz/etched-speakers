package net.yakel.etchedspeakers.remote;

import com.mojang.authlib.GameProfile;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.component.MusicTrackComponent;
import gg.moonflower.etched.common.blockentity.AlbumJukeboxBlockEntity;
import gg.moonflower.etched.core.registry.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
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
import net.yakel.etchedspeakers.compat.AudioSourceResolver;
import net.yakel.etchedspeakers.network.RemotePayloads.*;
import net.yakel.etchedspeakers.source.model.*;

/** Starts with NO Speaker, NO retained session and one tracked local player.
 * Native clock/BE are real. Etched PCM reports are explicit synthetic measured anchors, not audible tests.
 */
@EventBusSubscriber(modid=EtchedSpeakers.MOD_ID)
public final class LocalOriginalRuntimeChecks {
    private static final BlockPos POS=new BlockPos(34000,64,34000),ALBUM=POS.offset(2,0,0);
    private static final UUID ID=new UUID(911,911),EPOCH=new UUID(912,912);
    private static final TicketType<BlockPos> FIXTURE=TicketType.create("local_original_fixture",Comparator.comparingLong(BlockPos::asLong));
    private static final List<Snapshot> snapshots=new ArrayList<>();
    private static int phase,checks;
    private static long start,next,generation,elapsed,measuredAt;
    private static ServerPlayer player;
    private static GlobalPos source;
    private static JukeboxBlockEntity jukebox;
    private static Map<GlobalPos,Object> sessions;
    private static SourceRetention retention;
    private static Field field(Class<?> type,String name) throws Exception { var f=type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static RemoteTimeline timeline(GlobalPos pos) throws Exception {
        var e=sessions.get(pos); return e==null?null:(RemoteTimeline)field(e.getClass(),"timeline").get(e);
    }
    private static Snapshot latest(GlobalPos pos) { return snapshots.stream().filter(s->s.source().equals(pos)).reduce((a,b)->b).orElseThrow(); }
    private static void check(boolean result,String reason) { if(!result) throw new AssertionError(reason); checks++; }
    private static void renew() { RemoteSessions.interest(player,new Interest(EPOCH,player.level().dimension().location(),List.of())); }
    private static ItemStack url() {
        var stack=new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        stack.set(EtchedComponents.MUSIC,new MusicTrackComponent(List.of(new TrackData("https://example.invalid/local-only.mp3","fixture",Component.literal("Local")))));
        return stack;
    }
    private static void measured(GlobalPos pos,long id,long frame) {
        var track=AudioSourceResolver.readState(player.serverLevel(),pos.pos()).availableTracks().getFirst();
        RemoteSessions.report(player,new Report(pos,track.mediaKey(),track.slot(),track.trackIndex(),id,frame,44100,false,false));
    }
    @SuppressWarnings("unchecked")
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(!Boolean.getBoolean("etchedspeakers.localOriginalValidation") || phase==99) return;
        var server=event.getServer(); var level=server.overworld(); long now=level.getGameTime();
        level.resetEmptyTime();
        try {
            if(player!=null) {
                // No socket/client exists in this fixture. Model completed chunk delivery explicitly;
                // otherwise vanilla marks it pending forever and correctly denies the tracking predicate.
                ((it.unimi.dsi.fastutil.longs.LongSet)field(player.connection.chunkSender.getClass(),"pendingChunks")
                        .get(player.connection.chunkSender)).clear();
            }
            if(phase==0) {
                start=now; source=GlobalPos.of(level.dimension(),POS);
                // Fixture-only ticket simulates naturally ticking player chunks. Mod retention must stay ticket-free.
                level.getChunkSource().addRegionTicket(FIXTURE,new ChunkPos(POS),2,POS,false); phase=1;
            }
            if(phase==1 && level.getChunkSource().getChunkNow(POS.getX()>>4,POS.getZ()>>4)!=null) {
                level.setBlock(POS,Blocks.JUKEBOX.defaultBlockState(),3);
                jukebox=(JukeboxBlockEntity)level.getBlockEntity(POS); jukebox.setTheItem(ItemStack.EMPTY);
                var profile=new GameProfile(ID,"LocalOnlyProbe"); player=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
                player.setPos(POS.getX()+1,POS.getY()+1,POS.getZ()+1);
                player.connection=new ServerGamePacketListenerImpl(server,new Connection(PacketFlow.SERVERBOUND),player,CommonListenerCookie.createInitial(profile,false)) {
                    @Override public void send(Packet<?> packet,PacketSendListener listener) {
                        if(packet instanceof ClientboundCustomPayloadPacket p && p.payload() instanceof Snapshot s) snapshots.add(s);
                    }
                };
                ((Map<UUID,ServerPlayer>)field(net.minecraft.server.players.PlayerList.class,"playersByUUID").get(server.getPlayerList())).put(ID,player);
                var map=level.getChunkSource().chunkMap;
                ((PlayerMap)field(map.getClass(),"playerMap").get(map)).addPlayer(player,false);
                player.setChunkTrackingView(ChunkTrackingView.of(new ChunkPos(POS),2));
                sessions=(Map<GlobalPos,Object>)field(RemoteSessions.class,"SOURCES").get(null);
                retention=(SourceRetention)field(RemoteSessions.class,"RETENTION").get(null);
                renew(); check(sessions.isEmpty() && retention.keys().isEmpty(),"starts without remote or retained source");
                check(map.getPlayers(new ChunkPos(POS),false).contains(player),"one real tracking holder predicate");
                jukebox.setTheItem(new ItemStack(Items.MUSIC_DISC_CREATOR)); phase=2; next=now+60;
            } else if(phase>=2 && now>=next) {
                if(phase==2) {
                    EtchedSpeakers.LOGGER.info("[ES-LOCAL-ONLY-TEST] NATIVE elapsed={} tracked={} entries={}",
                            jukebox.getSongPlayer().getTicksSinceSongStarted(),level.getChunkSource().chunkMap.getPlayers(new ChunkPos(POS),false).contains(player),sessions.size());
                    var s=latest(source); generation=s.generation(); elapsed=s.nativeElapsedTicks();
                    check(s.active() && s.localSource() && s.sourceKind()==OriginalSourceKind.VANILLA_NATIVE,"native timeline delivered with only local holder");
                    check(elapsed>=40 && jukebox.getSongPlayer().getTicksSinceSongStarted()>=elapsed,"actual native ticks advance");
                    check(retention.listeners(source)==0 && !retention.ticketHeld(source) && !retention.alive(source),"local-only native creates no retention ticket or grace entry");
                    check(!s.remoteOwned() && s.observerToken()==0 && s.rate()==0,"native local state never assigns observer or guessed format");
                    player.setPos(POS.getX()+500,POS.getY()+1,POS.getZ()+1); phase=3; next=now+10;
                } else if(phase==3) {
                    check(!sessions.containsKey(source),"last local holder departure removes native state without 60-second radio");
                    check(!latest(source).active() && !retention.ticketHeld(source),"departure revokes original and keeps no ticket");
                    player.setPos(POS.getX()+1,POS.getY()+1,POS.getZ()+1); phase=4; next=now+40;
                } else if(phase==4) {
                    check(latest(source).active() && latest(source).nativeElapsedTicks()>elapsed,"return reads actual ongoing native cursor");
                    generation=latest(source).generation(); jukebox.setTheItem(ItemStack.EMPTY);
                    check(!latest(source).active(),"eject invalidates local native immediately");
                    jukebox.setTheItem(new ItemStack(Items.MUSIC_DISC_CREATOR)); phase=5; next=now+30;
                } else if(phase==5) {
                    check(latest(source).generation()>generation && latest(source).nativeElapsedTicks()<=30,"same native disc reinsertion starts new occurrence");
                    jukebox.setTheItem(url()); measured(source,100,441000); measuredAt=now; phase=6; next=now+100;
                } else if(phase==6) {
                    var s=latest(source); generation=s.generation();
                    check(AudioSourceResolver.readState(level,POS).reason()==SourcePlaybackState.Reason.ETCHED_CLIENT_SEQUENCE,"real URL item uses ordinary Etched resolver");
                    check(s.sourceKind()==OriginalSourceKind.VANILLA_ETCHED && s.active() && s.nativeElapsedTicks()==-1,"native to URL transition has distinct recovery identity");
                    check(!s.remoteOwned() && retention.listeners(source)==0 && retention.holders(source)==1,"URL local-only timeline needs no remote ownership");
                    check(!retention.ticketHeld(source),"URL natural holder requires no mod ticket");
                    check(s.frame()==441000+(s.serverTick()-measuredAt)*44100/20 && now-s.serverTick()<=20
                            && timeline(source).at(now)>=661500 && s.sourcePlaylist().size()==1,"measured URL anchor projects while Channel reports are absent");
                    var gate=new OriginalRecovery(); gate.volume(false); check(!gate.begin(0),"URL output loss waits for volume");
                    gate.volume(true); check(gate.begin(1) && !gate.begin(2),"URL restore requests one bounded attempt");
                    measured(source,101,timeline(source).at(now));
                    check(timeline(source).generation()==generation && timeline(source).at(now)>441000,"replacement URL decoder preserves media occurrence/current frame");
                    var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    try { Snapshot.CODEC.encode(buffer,latest(source)); check(Snapshot.CODEC.decode(buffer).equals(latest(source)),"typed playlist snapshot wire round trip"); }
                    finally { buffer.release(); }
                    jukebox.setTheItem(ItemStack.EMPTY); check(!latest(source).active(),"URL eject invalidates local recovery immediately");
                    jukebox.setTheItem(url()); measured(source,102,2205);
                    check(timeline(source).generation()>generation,"same URL reinsertion cannot reuse old occurrence");
                    level.setBlock(ALBUM,EtchedBlocks.ALBUM_JUKEBOX.get().defaultBlockState(),3);
                    var album=(AlbumJukeboxBlockEntity)level.getBlockEntity(ALBUM);
                    album.setItem(0,url()); album.setPlayingIndex(0,0); album.setPlayingIndex(0,0);
                    measured(GlobalPos.of(level.dimension(),ALBUM),200,882000); measuredAt=now; phase=7; next=now+100;
                } else if(phase==7) {
                    var pos=GlobalPos.of(level.dimension(),ALBUM); var s=latest(pos);
                    check(s.sourceKind()==OriginalSourceKind.ALBUM_ETCHED && s.active() && !s.remoteOwned(),"local-only Album is deliverable without remote ownership");
                    check(s.frame()==882000+(s.serverTick()-measuredAt)*44100/20 && now-s.serverTick()<=20
                            && timeline(pos).at(now)>=1102500,"Album measured timeline survives five seconds without Channel reports");
                    check(retention.listeners(pos)==0 && !retention.ticketHeld(pos),"Album recovery adds no fake Speaker or remote ticket");
                    var e=sessions.get(pos);
                    check(((RemoteObserverLease)field(e.getClass(),"remote").get(e)).owner()==null
                            && ((RemoteObserverLease)field(e.getClass(),"local").get(e)).owner()==null,"local recovery does not invent elected observers");
                    generation=s.generation(); measured(pos,201,timeline(pos).at(now));
                    check(timeline(pos).generation()==generation,"Album replacement keeps canonical generation");
                    var album=(AlbumJukeboxBlockEntity)level.getBlockEntity(ALBUM); album.setItem(0,ItemStack.EMPTY);
                    phase=8; next=now+10;
                } else if(phase==8) {
                    check(!latest(GlobalPos.of(level.dimension(),ALBUM)).active(),"Album eject invalidates local recovery");
                    EtchedSpeakers.LOGGER.info("[ES-LOCAL-ONLY-TEST] PASS checks={}",checks); phase=99; server.halt(false);
                }
            }
            if(player!=null && phase<99 && now%40==0) renew();
            if(now-start>500) throw new AssertionError("Local-only fixture timeout");
        } catch(Throwable failure) { EtchedSpeakers.LOGGER.error("[ES-LOCAL-ONLY-TEST] FAIL phase="+phase,failure); phase=99; server.halt(false); }
        if(phase==99) {
            level.getChunkSource().removeRegionTicket(FIXTURE,new ChunkPos(POS),2,POS,false);
        }
    }
}
