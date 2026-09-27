package net.yakel.etchedspeakers.remote;

import com.mojang.authlib.GameProfile;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.common.component.MusicTrackComponent;
import gg.moonflower.etched.core.registry.EtchedComponents;
import gg.moonflower.etched.core.registry.EtchedItems;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.yakel.etchedspeakers.compat.AudioSourceResolver;
import net.yakel.etchedspeakers.network.RemotePayloads.*;
import net.yakel.etchedspeakers.registry.ModBlocks;
import net.yakel.etchedspeakers.source.model.RemoteTimeline;

/** Baseline diagnosis using real BE, Etched components, server validation and captured S2C snapshots.
 * The synthetic player has no client/decoder. Only this opt-in fixture replaces transport with capture.
 */
@EventBusSubscriber(modid=EtchedSpeakers.MOD_ID)
public final class VanillaActivationRuntimeChecks {
    private static final BlockPos SOURCE=new BlockPos(32000,64,32000), SPEAKER=SOURCE.offset(192,0,0);
    private static final TicketType<BlockPos> FIXTURE=TicketType.create("vanilla_activation_fixture",Comparator.comparingLong(BlockPos::asLong));
    private static final UUID PLAYER=new UUID(300,300),EPOCH=new UUID(400,400);
    private static final List<Snapshot> snapshots=new ArrayList<>();
    private static int phase,checks;
    private static long nextTick,start,generation;
    private static ServerPlayer player;
    private static JukeboxBlockEntity jukebox;
    private static SpeakerBlockEntity speaker;
    private static GlobalPos source;
    private static SourceRetention retention;
    private static Map<GlobalPos,Object> sessions;
    private static boolean nativeMode, away;
    private static long previousElapsed;
    private static List<BlockPos> interestPositions() {
        return away?List.of():nativeMode?List.of(SPEAKER,SPEAKER.offset(1,0,0),SPEAKER.offset(2,0,0)):List.of(SPEAKER);
    }
    private static Snapshot latest() { return snapshots.getLast(); }
    private static void renew() {
        RemoteSessions.interest(player,new Interest(EPOCH,player.level().dimension().location(),interestPositions()));
    }
    private static Field field(Class<?> c,String n) throws Exception { var f=c.getDeclaredField(n); f.setAccessible(true); return f; }
    private static RemoteTimeline timeline() throws Exception {
        var e=sessions.get(source); return e==null?null:(RemoteTimeline)field(e.getClass(),"timeline").get(e);
    }
    private static void check(boolean ok,String reason) { if(!ok) throw new AssertionError(reason); checks++; }
    private static ItemStack record() {
        var record=new ItemStack(EtchedItems.ETCHED_MUSIC_DISC.get());
        record.set(EtchedComponents.MUSIC,new MusicTrackComponent(List.of(new TrackData("https://example.invalid/activation-a.mp3","fixture",Component.literal("A")))));
        return record;
    }
    private static void snapshot(String event) throws Exception {
        var state=AudioSourceResolver.readState(player.serverLevel(),SOURCE); var t=timeline();
        EtchedSpeakers.LOGGER.info("[ES-VANILLA-ACT] {} source={} generation={} listenerCount={} reason={}",event,source,
                t==null?0:t.generation(),retention.listeners(source),
                "linked="+speaker.isLinked()+",state="+state.reason()+",tracks="+state.availableTracks().size()+",active="+(t!=null&&t.active()));
    }
    @SuppressWarnings("unchecked")
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        nativeMode=Boolean.getBoolean("etchedspeakers.nativeTimelineValidation");
        if(!nativeMode && !Boolean.getBoolean("etchedspeakers.vanillaActivationValidation") || phase==99) return;
        var server=event.getServer(); var level=server.overworld(); long now=level.getGameTime();
        // Synthetic transport player is not a world entity. Keep this opt-in test world awake as real players would.
        if(nativeMode) level.resetEmptyTime();
        try {
            if(phase==0) {
                start=now; source=GlobalPos.of(level.dimension(),SOURCE);
                // Native test simulates A keeping the source in ticking range; production retention stays load-only.
                for(var pos:List.of(SOURCE,SPEAKER)) level.getChunkSource().addRegionTicket(FIXTURE,new ChunkPos(pos),nativeMode?2:0,pos,false);
                phase=1;
            }
            if(phase==1 && level.getChunkSource().getChunkNow(SOURCE.getX()>>4,SOURCE.getZ()>>4)!=null
                    && level.getChunkSource().getChunkNow(SPEAKER.getX()>>4,SPEAKER.getZ()>>4)!=null) {
                level.setBlock(SOURCE,Blocks.JUKEBOX.defaultBlockState(),3);
                level.setBlock(SPEAKER,ModBlocks.SPEAKER.get().defaultBlockState(),3);
                jukebox=(JukeboxBlockEntity)level.getBlockEntity(SOURCE);
                // The isolated fixture world is reusable; never inherit last run's inserted record.
                jukebox.setTheItem(ItemStack.EMPTY);
                speaker=(SpeakerBlockEntity)level.getBlockEntity(SPEAKER); speaker.setSource(level.dimension(),SOURCE);
                if(nativeMode) for(var pos:List.of(SPEAKER.offset(1,0,0),SPEAKER.offset(2,0,0))) {
                    level.setBlock(pos,ModBlocks.SPEAKER.get().defaultBlockState(),3);
                    ((SpeakerBlockEntity)level.getBlockEntity(pos)).setSource(level.dimension(),SOURCE);
                }
                var profile=new GameProfile(PLAYER,"ActivationProbe");
                player=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
                player.setPos(SPEAKER.getX()+0.5,SPEAKER.getY()+1,SPEAKER.getZ()+0.5);
                player.connection=new ServerGamePacketListenerImpl(server,new Connection(PacketFlow.SERVERBOUND),player,
                        CommonListenerCookie.createInitial(profile,false)) {
                    @Override public void send(Packet<?> packet,PacketSendListener listener) {
                        if(packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof Snapshot s) snapshots.add(s);
                    }
                };
                ((Map<UUID,ServerPlayer>)field(net.minecraft.server.players.PlayerList.class,"playersByUUID").get(server.getPlayerList())).put(PLAYER,player);
                retention=(SourceRetention)field(RemoteSessions.class,"RETENTION").get(null);
                sessions=(Map<GlobalPos,Object>)field(RemoteSessions.class,"SOURCES").get(null);
                renew();
                phase=2; nextTick=now+40;
            } else if(phase>=2 && now>=nextTick) {
                if(nativeMode) {
                    nativeStep(now);
                } else if(phase==2) {
                    check(retention.listeners(source)==1,"empty linked source retains interest");
                    check(timeline()!=null && !timeline().active(),"empty source has no fake playback");
                    snapshot("BASELINE_BEFORE_INSERT"); jukebox.setTheItem(record()); snapshot("BASELINE_RECORD_INSERTED");
                    phase=3; nextTick=now+40;
                } else if(phase==3) {
                    snapshot("BASELINE_WITHOUT_RELINK");
                    check(timeline().active(),"record insertion activates existing interest without relink");
                    check(snapshots.stream().anyMatch(s->s.active() && s.source().equals(source)),"existing subscriber receives active snapshot");
                    generation=timeline().generation();
                    speaker.setSource(level.dimension(),SOURCE); snapshot("BASELINE_SAME_LINK_CLICK");
                    phase=4; nextTick=now+20;
                } else if(phase==4) {
                    check(timeline().generation()==generation,"same link click does not change generation");
                    snapshot("BASELINE_AFTER_SAME_LINK_CLICK");
                    jukebox.setTheItem(ItemStack.EMPTY); phase=5; nextTick=now+20;
                } else if(phase==5) {
                    check(!timeline().active(),"eject stops session");
                    jukebox.setTheItem(record()); phase=6; nextTick=now+20;
                } else if(phase==6) {
                    check(timeline().active() && timeline().generation()>generation,"same record reinsertion activates new generation");
                    snapshot("BASELINE_REINSERT");
                    EtchedSpeakers.LOGGER.info("[ES-VANILLA-ACT-TEST] PASS baseline checks={}",checks);
                    phase=99; server.halt(false);
                }
            }
            if(phase>=2 && phase<99 && now%40==0) renew();
            if(now-start>(nativeMode?1100:600)) throw new AssertionError("Fixture timeout");
        } catch(Throwable failure) {
            EtchedSpeakers.LOGGER.error("[ES-VANILLA-ACT-TEST] FAIL phase="+phase,failure); phase=99; server.halt(false);
        }
        if(phase==99) {
            if(player!=null) ((Map<UUID,ServerPlayer>)getPlayers(server)).remove(PLAYER);
            for(var pos:List.of(SOURCE,SPEAKER)) level.getChunkSource().removeRegionTicket(FIXTURE,new ChunkPos(pos),nativeMode?2:0,pos,false);
        }
    }
    private static Object getPlayers(net.minecraft.server.MinecraftServer server) {
        try { return field(net.minecraft.server.players.PlayerList.class,"playersByUUID").get(server.getPlayerList()); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    /** Real pushLocal and ChunkMap tracking predicate; only transport/player placement is synthetic. */
    @SuppressWarnings("unchecked")
    private static void recoveryDelivery() throws Exception {
        var server=player.getServer(); var level=player.serverLevel(); var received=new ArrayList<Snapshot>();
        var profile=new GameProfile(new UUID(777,777),"OriginalRecoveryProbe");
        var local=new ServerPlayer(server,level,profile,ClientInformation.createDefault());
        local.setPos(SOURCE.getX()+1,SOURCE.getY()+1,SOURCE.getZ()+1);
        local.connection=new ServerGamePacketListenerImpl(server,new Connection(PacketFlow.SERVERBOUND),local,
                CommonListenerCookie.createInitial(profile,false)) {
            @Override public void send(Packet<?> packet,PacketSendListener listener) {
                if(packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof Snapshot s) received.add(s);
            }
        };
        var players=(Map<UUID,ServerPlayer>)getPlayers(server); players.put(local.getUUID(),local);
        var chunkMap=level.getChunkSource().chunkMap;
        var playerMap=(net.minecraft.server.level.PlayerMap)field(chunkMap.getClass(),"playerMap").get(chunkMap);
        playerMap.addPlayer(local,false);
        var entry=sessions.get(source);
        var push=RemoteSessions.class.getDeclaredMethod("pushLocal",net.minecraft.server.MinecraftServer.class,GlobalPos.class,entry.getClass(),boolean.class);
        push.setAccessible(true);
        try {
            RemoteSessions.interest(local,new Interest(new UUID(888,888),level.dimension().location(),List.of()));
            push.invoke(null,server,source,entry,true);
            check(received.isEmpty(),"near player without chunk tracking receives no original recovery");
            local.setChunkTrackingView(net.minecraft.server.level.ChunkTrackingView.of(new ChunkPos(SOURCE),2));
            push.invoke(null,server,source,entry,true);
            var current=received.getLast();
            check(current.localSource() && current.active() && current.nativeElapsedTicks()>=600,"tracking original recipient gets real active native elapsed without Speaker subscription");
            check(current.generation()==timeline().generation() && current.media().equals(latest().media()),"original delivery keeps same canonical occurrence/media");
            check(current.rate()==0 && !current.remoteOwned() && current.observerToken()==0,"native recovery does not introduce observer authority or guessed PCM format");
            local.setPos(SOURCE.getX()+500,SOURCE.getY()+1,SOURCE.getZ()+1);
            push.invoke(null,server,source,entry,true);
            check(!received.getLast().active() && received.getLast().reason().equals("SOURCE_NOT_TRACKED"),"remote-only listener loses original lease even if source chunk remains tracked");
            local.setPos(SOURCE.getX()+1,SOURCE.getY()+1,SOURCE.getZ()+1);
            push.invoke(null,server,source,entry,true);
            check(received.getLast().active() && received.getLast().generation()==current.generation(),"return near source recovers same occurrence");
            jukebox.setTheItem(ItemStack.EMPTY);
            check(!received.getLast().active(),"real eject invalidates original recovery recipient immediately");
        } finally {
            playerMap.removePlayer(local); players.remove(local.getUUID());
            RemoteSessions.logout(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(local));
        }
    }
    private static void nativeStep(long now) throws Exception {
        var level=player.serverLevel();
        if(phase==2) {
            check(retention.listeners(source)==1,"three empty-linked Speakers retain one source interest");
            check(timeline()!=null && !timeline().active(),"empty native source creates no playback");
            away=true; renew();
            jukebox.setTheItem(new ItemStack(net.minecraft.world.item.Items.MUSIC_DISC_CREATOR));
            check(jukebox.getSongPlayer().getTicksSinceSongStarted()==0,"real insertion resets server cursor");
            phase=3; nextTick=now+600;
        } else if(phase==3) {
            long observed=jukebox.getSongPlayer().getTicksSinceSongStarted();
            EtchedSpeakers.LOGGER.info("[ES-NATIVE-TEST] CURSOR elapsed={} chunkStatus={} entitiesLoaded={} blockTickRange={}",observed,
                    level.getChunkAt(SOURCE).getFullStatus(),level.areEntitiesLoaded(ChunkPos.asLong(SOURCE)),level.shouldTickBlocksAt(ChunkPos.asLong(SOURCE)));
            check(observed>=598,"real Jukebox ticks for thirty seconds without listeners; actual="+observed);
            check(!timeline().active(),"no observer or fake native clock admitted while absent");
            away=false; renew(); phase=4; nextTick=now+20;
        } else if(phase==4) {
            var s=latest(); generation=s.generation();
            check(s.active() && s.nativeElapsedTicks()>=600,"late subscriber receives actual nonzero native cursor");
            check(Math.abs(s.nativeElapsedTicks()-jukebox.getSongPlayer().getTicksSinceSongStarted())<=20,"snapshot follows BE cursor");
            check(s.rate()==0 && s.frame()==0,"server never invents PCM format");
            check(!s.paused(),"real ticking source is not paused");
            check(!s.remoteOwned() && !s.localSource() && s.observerToken()==0,"no Album ownership or observer election");
            check(sessions.size()==1,"three native Speakers share one source session");
            check(speaker.getSourcePos().orElseThrow().equals(SOURCE),"native activation never rewrites link");
            var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try { Snapshot.CODEC.encode(buf,s); check(Snapshot.CODEC.decode(buf).equals(s),"native snapshot wire round trip"); }
            finally { buf.release(); }
            RemoteSessions.remoteReport(player,new RemoteReport(EPOCH,source,generation,s.media(),17,0,0,48000,false));
            RemoteSessions.report(player,new Report(source,s.media(),0,0,17,0,48000,false,false));
            check(timeline().rate()==0 && timeline().generation()==generation,"native client reports cannot set clock or generation");
            check(new net.yakel.etchedspeakers.source.model.NativeDiscClock(s.nativeElapsedTicks(),0,0).target(48000,0)>=1_440_000,
                    "real late snapshot projects at least thirty seconds of PCM");
            recoveryDelivery();
            jukebox.setTheItem(ItemStack.EMPTY);
            check(!timeline().active() && !latest().active(),"eject immediately invalidates native occurrence");
            jukebox.setTheItem(new ItemStack(net.minecraft.world.item.Items.MUSIC_DISC_CREATOR));
            phase=5; nextTick=now+20;
        } else if(phase==5) {
            check(latest().active() && latest().generation()>generation,"same disc rapid reinsertion creates new generation");
            check(latest().nativeElapsedTicks()<=20,"reinsertion uses new start rather than old thirty seconds");
            check(!RemoteTimeline.acceptsSnapshot(generation,now+500,latest().generation(),now),"stale previous-disc snapshot rejected");
            generation=latest().generation();
            jukebox.setTheItem(new ItemStack(net.minecraft.world.item.Items.MUSIC_DISC_PIGSTEP));
            phase=6; nextTick=now+20;
        } else if(phase==6) {
            check(latest().generation()>generation && latest().media().equals("sound:minecraft:music_disc.pigstep"),"different disc changes generation and media");
            generation=latest().generation(); previousElapsed=jukebox.getSongPlayer().getTicksSinceSongStarted();
            away=true; renew(); phase=7; nextTick=now+40;
        } else if(phase==7) {
            check(timeline().active() && timeline().generation()==generation,"native source survives transit inside unchanged grace");
            check(jukebox.getSongPlayer().getTicksSinceSongStarted()>=previousElapsed+40,"cursor progresses without observer reports");
            away=false; renew(); phase=8; nextTick=now+20;
        } else if(phase==8) {
            check(latest().active() && latest().generation()==generation && latest().nativeElapsedTicks()>previousElapsed,"return resumes native timeline without frame-zero restart");
            var song=net.minecraft.world.item.JukeboxSong.fromStack(level.registryAccess(),jukebox.getTheItem()).orElseThrow();
            jukebox.getSongPlayer().setSongWithoutPlaying(song,song.value().lengthInTicks()+19L);
            phase=9; nextTick=now+20;
        } else if(phase==9) {
            check(!jukebox.getSongPlayer().isPlaying() && !timeline().active(),"real song end and padding stop native session");
            check(!latest().active(),"native end reaches subscriber without decoder EOF authority");
            jukebox.setTheItem(new ItemStack(net.minecraft.world.item.Items.MUSIC_DISC_PIGSTEP));
            level.getChunkSource().removeRegionTicket(FIXTURE,new ChunkPos(SOURCE),2,SOURCE,false);
            phase=10; nextTick=now+40;
        } else if(phase==10) {
            previousElapsed=jukebox.getSongPlayer().getTicksSinceSongStarted(); generation=latest().generation();
            check(latest().active() && !latest().paused(),"production native ticket keeps snapshots ticking after natural support leaves");
            phase=11; nextTick=now+40;
        } else if(phase==11) {
            check(jukebox.getSongPlayer().getTicksSinceSongStarted()>=previousElapsed+40,"production native ticket advances the real BE without fixture support");
            check(latest().nativeElapsedTicks()>previousElapsed && latest().generation()==generation,"remote snapshot preserves advancing cursor and occurrence");
            level.getChunkSource().addRegionTicket(FIXTURE,new ChunkPos(SOURCE),2,SOURCE,false);
            phase=12; nextTick=now+40;
        } else if(phase==12) {
            check(latest().active() && !latest().paused() && latest().generation()==generation,"ticking resume retains native generation");
            check(latest().nativeElapsedTicks()>previousElapsed,"ticking resume advances actual source cursor");
            EtchedSpeakers.LOGGER.info("[ES-NATIVE-TEST] PASS checks={}",checks);
            phase=99; player.getServer().halt(false);
        }
    }
}
