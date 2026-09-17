package net.yakel.etchedspeakers.client.mixin;

import net.yakel.etchedspeakers.client.audio.sync.MasterSessions;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public abstract class SoundEngineLifecycleMixin {
    @Shadow @Final private SoundEngineExecutor executor;

    @Inject(method = "stopAll", at = @At("HEAD"))
    private void etchedspeakers$cleanupBeforeFlush(CallbackInfo callback) {
        if (executor.isSameThread()) MasterSessions.clearOutputs();
        else executor.submit(MasterSessions::clearOutputs).join();
    }
}
