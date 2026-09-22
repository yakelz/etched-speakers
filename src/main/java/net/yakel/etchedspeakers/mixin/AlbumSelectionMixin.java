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
    @Inject(method="setPlayingTrack",at=@At("RETURN"),remap=false)
    private void etchedspeakers$selection(Level level, SetAlbumJukeboxTrackPacket packet, CallbackInfoReturnable<Boolean> ci) {
        if(level instanceof ServerLevel server && ci.getReturnValue()) RemoteSessions.explicitSelection(server,pos);
    }
}
