package net.yakel.etchedspeakers.mixin;

import gg.moonflower.etched.common.menu.AlbumJukeboxMenu;
import gg.moonflower.etched.common.network.play.SetAlbumJukeboxTrackPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.yakel.etchedspeakers.remote.RemoteSessions;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe an actually accepted GUI selection, not a guessed server-side playback index. */
@Mixin(value=AlbumJukeboxMenu.class,remap=false)
public abstract class AlbumSelectionMixin {
    @Shadow @Final private BlockPos pos;
    @Unique private int etchedspeakers$previousSlot, etchedspeakers$previousTrack;
    @Inject(method="setPlayingTrack",at=@At("HEAD"),remap=false)
    private void etchedspeakers$beforeSelection(Level level, SetAlbumJukeboxTrackPacket packet, CallbackInfoReturnable<Boolean> ci) {
        if(level instanceof ServerLevel server) {
            var chunk=server.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk!=null && chunk.getBlockEntity(pos) instanceof gg.moonflower.etched.common.blockentity.AlbumJukeboxBlockEntity album) {
                etchedspeakers$previousSlot=album.getPlayingIndex(); etchedspeakers$previousTrack=album.getTrack();
            }
        }
    }
    @Inject(method="setPlayingTrack",at=@At("RETURN"),remap=false)
    private void etchedspeakers$selection(Level level, SetAlbumJukeboxTrackPacket packet, CallbackInfoReturnable<Boolean> ci) {
        if(level instanceof ServerLevel server) {
            var chunk=server.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk!=null && chunk.getBlockEntity(pos) instanceof gg.moonflower.etched.common.blockentity.AlbumJukeboxBlockEntity album
                    && (ci.getReturnValue() || album.getPlayingIndex()!=etchedspeakers$previousSlot || album.getTrack()!=etchedspeakers$previousTrack))
                RemoteSessions.explicitSelection(server,pos);
        }
    }
}
