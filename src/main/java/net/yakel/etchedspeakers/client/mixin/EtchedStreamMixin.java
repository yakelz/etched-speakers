package net.yakel.etchedspeakers.client.mixin;

import net.yakel.etchedspeakers.client.audio.sync.PcmTapInstaller;
import gg.moonflower.etched.api.sound.AbstractOnlineSoundInstance;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractOnlineSoundInstance.class, remap = false)
public abstract class EtchedStreamMixin {
    @Inject(method = "getStream(Lnet/minecraft/client/sounds/SoundBufferLibrary;Lnet/minecraft/client/resources/sounds/Sound;Z)Ljava/util/concurrent/CompletableFuture;",
            at = @At("RETURN"), cancellable = true, remap = false)
    private void etchedspeakers$tap(SoundBufferLibrary loader, Sound sound, boolean loop,
            CallbackInfoReturnable<CompletableFuture<AudioStream>> callback) {
        callback.setReturnValue(PcmTapInstaller.wrap((AbstractOnlineSoundInstance) (Object) this, callback.getReturnValue()));
    }
}
