package net.yakel.etchedspeakers.client.audio.remote;

import gg.moonflower.etched.common.blockentity.AlbumJukeboxBlockEntity;
import gg.moonflower.etched.core.mixin.client.render.LevelRendererAccessor;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.yakel.etchedspeakers.client.audio.sync.MasterPlaybackSession;
import net.yakel.etchedspeakers.client.audio.sync.MasterSessions;
import net.yakel.etchedspeakers.network.RemotePayloads.Report;

/** Reports measured AL cursor of original Etched masters only, never remote mirrors. */
public final class PlaybackObserver {
    private static final Map<Long, Long> SENT = new HashMap<>();
    public static void tick(Minecraft client) {
        if(client.level==null || client.player==null || client.isPaused()) return;
        long tick=client.level.getGameTime();
        SENT.entrySet().removeIf(e->tick-e.getValue()>200 || tick<e.getValue());
        int reports=0;
        for(var master:MasterSessions.all()) {
            var o=master.observation(); if(o==null || tick-SENT.getOrDefault(o.id(),-1000L)<20) continue;
            if(send(client,o,false)) { SENT.put(o.id(),tick); if(++reports==16) break; }
        }
    }
    public static void eof(MasterPlaybackSession.Observation observation) {
        if(observation==null) return;
        Minecraft.getInstance().execute(()->send(Minecraft.getInstance(),observation,true));
    }
    private static boolean send(Minecraft client, MasterPlaybackSession.Observation o, boolean eof) {
        if(LocalSourceSync.owned(o.source())) return LocalSourceSync.report(o,eof);
        if(client.player==null || client.level==null || !client.level.dimension().equals(o.source().dimension())
                || client.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(o.source().pos()))>64*64) return false;
        var pos=o.source().pos(); var chunk=client.level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        if(chunk==null) return false;
        if(!eof) {
            var sound=((LevelRendererAccessor)client.levelRenderer).getPlayingJukeboxSongs().get(pos);
            var master=sound==null?null:MasterSessions.find(sound);
            if(master==null || master.diagnostic().id!=o.id()) return false;
        }
        int slot=-1,index=-1;
        if(!eof && chunk.getBlockEntity(pos) instanceof AlbumJukeboxBlockEntity album) {
            slot=album.getPlayingIndex(); index=album.getTrack();
        }
        PacketDistributor.sendToServer(new Report(o.source(),o.media(),slot,index,o.id(),LocalSourceSync.reportedFrame(o),o.rate(),o.paused(),eof));
        return true;
    }
    public static void clear() { SENT.clear(); }
}
