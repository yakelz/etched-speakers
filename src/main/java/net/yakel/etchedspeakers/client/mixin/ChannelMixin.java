package net.yakel.etchedspeakers.client.mixin;

import net.yakel.etchedspeakers.client.audio.sync.AudioThreadBridge;
import net.yakel.etchedspeakers.client.audio.sync.MasterPlaybackSession;
import net.yakel.etchedspeakers.client.audio.sync.PcmTap;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.AudioStream;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Channel.class)
public abstract class ChannelMixin {
    @Shadow @Final private int source;
    @Shadow private int streamingBufferSize;
    @Unique private MasterPlaybackSession etchedspeakers$session;

    @Inject(method = "attachBufferStream", at = @At("HEAD"))
    private void etchedspeakers$bind(AudioStream stream, CallbackInfo callback) {
        if (stream instanceof PcmTap tap) {
            etchedspeakers$session = tap.session();
            etchedspeakers$session.bind(source);
        }
    }

    @Inject(method = "attachBufferStream", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/Channel;pumpBuffers(I)V"))
    private void etchedspeakers$smallChunks(AudioStream stream, CallbackInfo callback) {
        if (etchedspeakers$session != null) {
            streamingBufferSize = etchedspeakers$session.chunkFrames() * stream.getFormat().getChannels()
                    * stream.getFormat().getSampleSizeInBits() / 8;
        }
    }

    @Inject(method = "removeProcessedBuffers", at = @At("RETURN"))
    private void etchedspeakers$advance(CallbackInfoReturnable<Integer> callback) {
        if (etchedspeakers$session != null) etchedspeakers$session.unqueued(callback.getReturnValue());
    }

    @Inject(method = {"updateStream", "play", "unpause"}, at = @At("RETURN"))
    private void etchedspeakers$service(CallbackInfo callback) {
        if (etchedspeakers$session != null) etchedspeakers$session.update();
    }

    @Inject(method = "pause", at = @At("RETURN"))
    private void etchedspeakers$pause(CallbackInfo callback) {
        if (etchedspeakers$session != null) etchedspeakers$session.pause();
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void etchedspeakers$stop(CallbackInfo callback) {
        if (etchedspeakers$session != null) AudioThreadBridge.sync(() -> etchedspeakers$session.detach("MASTER_STOP"));
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void etchedspeakers$destroy(CallbackInfo callback) {
        if (etchedspeakers$session != null) {
            AudioThreadBridge.sync(etchedspeakers$session::close);
            etchedspeakers$session = null;
        }
    }
}
