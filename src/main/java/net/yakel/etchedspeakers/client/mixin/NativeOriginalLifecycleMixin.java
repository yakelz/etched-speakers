package net.yakel.etchedspeakers.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.item.JukeboxSong;
import net.yakel.etchedspeakers.client.audio.remote.NativeOriginalRecovery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class NativeOriginalLifecycleMixin {
    @Inject(method="playJukeboxSong",at=@At("HEAD"))
    private void etchedspeakers$play(Holder<JukeboxSong> song,BlockPos pos,CallbackInfo ci) { NativeOriginalRecovery.sourceEvent(pos); }
    @Inject(method="stopJukeboxSongAndNotifyNearby",at=@At("HEAD"))
    private void etchedspeakers$stop(BlockPos pos,CallbackInfo ci) { NativeOriginalRecovery.sourceEvent(pos); }
}
