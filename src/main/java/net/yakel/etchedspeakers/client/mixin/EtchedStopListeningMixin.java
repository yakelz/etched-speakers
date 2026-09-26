package net.yakel.etchedspeakers.client.mixin;

import gg.moonflower.etched.api.sound.StopListeningSound;
import net.yakel.etchedspeakers.client.audio.sync.AudioDiagnostics;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Temporary: distinguish Etched explicitly suppressing its next-track callback from natural EOF. */
@Mixin(value = StopListeningSound.class, remap = false)
public abstract class EtchedStopListeningMixin {
    @Shadow private boolean ignoringEvents;
    @Inject(method="onStop",at=@At("HEAD"),cancellable=true,remap=false)
    private void etchedspeakers$canonicalStop(CallbackInfo ci) {
        if(!ignoringEvents && net.yakel.etchedspeakers.client.audio.remote.LocalSourceSync.stopped((SoundInstance)(Object)this)) ci.cancel();
    }

    @Inject(method = "stopListening", at = @At("HEAD"), remap = false)
    private void etchedspeakers$listenerDisabled(CallbackInfo callback) {
        if (ignoringEvents) return;
        var trace = AudioDiagnostics.find((SoundInstance) (Object) this);
        if (trace == null) return;
        AudioDiagnostics.observe(trace.source);
        trace.log("ETCHED_STOP_LISTENING", "caller=" + AudioDiagnostics.caller());
    }
}
