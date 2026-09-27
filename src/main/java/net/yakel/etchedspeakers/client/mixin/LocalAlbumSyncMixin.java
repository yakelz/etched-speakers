package net.yakel.etchedspeakers.client.mixin;

import gg.moonflower.etched.api.sound.SoundTracker;
import gg.moonflower.etched.common.blockentity.AlbumJukeboxBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.yakel.etchedspeakers.client.audio.remote.LocalSourceSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client-only: retain Etched's own sound creation/positional audio, guard retained selection races. */
@Mixin(value=SoundTracker.class,remap=false)
public abstract class LocalAlbumSyncMixin {
    @Inject(method="playBlockRecord(Lnet/minecraft/core/BlockPos;[Lgg/moonflower/etched/api/record/TrackData;ILjava/util/UUID;)V",at=@At("HEAD"),remap=false)
    private static void etchedspeakers$block(BlockPos pos,gg.moonflower.etched.api.record.TrackData[] tracks,int index,
            java.util.UUID storage,CallbackInfo ci) {
        if(storage==null) LocalSourceSync.blockRecord(pos,tracks,index);
    }
    @Inject(method="playAlbum",at=@At("HEAD"),cancellable=true,remap=false)
    private static void etchedspeakers$canonicalAlbum(AlbumJukeboxBlockEntity album, BlockState state,
            CommonLevelAccessor level, BlockPos pos, boolean force, CallbackInfo ci) {
        if(LocalSourceSync.playAlbum(album,level,pos)) ci.cancel();
    }
    @Inject(method="playNextRecord",at=@At("HEAD"),cancellable=true,remap=false)
    private static void etchedspeakers$canonicalNext(CommonLevelAccessor level, BlockPos pos, CallbackInfo ci) {
        if(LocalSourceSync.autoNext(level,pos)) ci.cancel();
    }
}
