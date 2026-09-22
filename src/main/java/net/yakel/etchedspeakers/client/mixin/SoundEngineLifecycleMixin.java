package net.yakel.etchedspeakers.client.mixin;

import net.yakel.etchedspeakers.client.audio.sync.MasterSessions;
import net.yakel.etchedspeakers.client.audio.sync.AudioDiagnostics;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public abstract class SoundEngineLifecycleMixin {
    @Shadow @Final private SoundEngineExecutor executor;
    @Unique private String etchedspeakers$cleanupReason = "SOUND_ENGINE_STOP_ALL";

    @Inject(method = "reload", at = @At("HEAD"))
    private void etchedspeakers$reloadStart(CallbackInfo callback) {
        etchedspeakers$cleanupReason = "RESOURCE_RELOAD";
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void etchedspeakers$reloadEnd(CallbackInfo callback) {
        etchedspeakers$cleanupReason = "SOUND_ENGINE_STOP_ALL";
    }

    @Inject(method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", at = @At("HEAD"))
    private void etchedspeakers$stopRequest(SoundInstance sound, CallbackInfo callback) {
        var trace = AudioDiagnostics.find(sound);
        if (trace == null) return;
        AudioDiagnostics.observe(trace.source);
        trace.stop("SOUND_ENGINE_STOP_REQUEST");
        trace.log("SOUND_ENGINE_STOP_REQUEST", "soundInstanceIdentity=" + AudioDiagnostics.identity(sound)
                + " caller=" + AudioDiagnostics.caller());
    }

    @Inject(method = "stopAll", at = @At("HEAD"))
    private void etchedspeakers$cleanupBeforeFlush(CallbackInfo callback) {
        net.yakel.etchedspeakers.client.audio.remote.RemotePlayback.reset(etchedspeakers$cleanupReason);
        AudioDiagnostics.reset(etchedspeakers$cleanupReason);
        AudioDiagnostics.event("SOUND_ENGINE_STOP_ALL", "reason=" + etchedspeakers$cleanupReason + " caller=" + AudioDiagnostics.caller());
        String reason = etchedspeakers$cleanupReason;
        if (executor.isSameThread()) MasterSessions.clearOutputs(reason);
        else executor.submit(() -> MasterSessions.clearOutputs(reason)).join();
    }
}
