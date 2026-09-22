package net.yakel.etchedspeakers.client.mixin;

import net.yakel.etchedspeakers.client.audio.sync.AudioThreadBridge;
import net.yakel.etchedspeakers.client.audio.sync.MasterPlaybackSession;
import net.yakel.etchedspeakers.client.audio.sync.PcmTap;
import net.yakel.etchedspeakers.client.audio.sync.AudioDiagnostics;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.AudioStream;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.openal.AL10;

@Mixin(Channel.class)
public abstract class ChannelMixin {
    @Shadow @Final private int source;
    @Shadow private int streamingBufferSize;
    @Shadow public abstract void setVolume(float volume);
    @Unique private MasterPlaybackSession etchedspeakers$session;

    @Inject(method = "attachBufferStream", at = @At("HEAD"))
    private void etchedspeakers$bind(AudioStream stream, CallbackInfo callback) {
        if (stream instanceof PcmTap tap) {
            etchedspeakers$session = tap.session();
            etchedspeakers$session.bind(source);
            if (etchedspeakers$session.isRemote()) setVolume(0);
        }
    }

    @ModifyVariable(method = "setVolume", at = @At("HEAD"), argsOnly = true)
    private float etchedspeakers$muteRemoteClock(float volume) {
        return etchedspeakers$session != null && etchedspeakers$session.isRemote() ? 0 : volume;
    }

    @Inject(method = "attachBufferStream", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/Channel;pumpBuffers(I)V"))
    private void etchedspeakers$smallChunks(AudioStream stream, CallbackInfo callback) {
        if (etchedspeakers$session != null) {
            streamingBufferSize = etchedspeakers$session.chunkFrames() * stream.getFormat().getChannels()
                    * stream.getFormat().getSampleSizeInBits() / 8;
        }
    }

    @Inject(method = "updateStream", at = @At("HEAD"))
    private void etchedspeakers$queueUpdateStart(CallbackInfo callback) {
        if (etchedspeakers$session != null) {
            etchedspeakers$session.recovery().begin(
                    AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED,
                    AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED),
                    AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED),
                    etchedspeakers$session.decodedEndFrame(), etchedspeakers$session.hasDecoderEof());
            etchedspeakers$session.queueDiagnostic().beginUpdate(etchedspeakers$session.diagnosticBaseFrame());
        }
    }

    @Inject(method = "removeProcessedBuffers", at = @At("HEAD"))
    private void etchedspeakers$queueBeforeRemove(CallbackInfoReturnable<Integer> callback) {
        etchedspeakers$queueSnapshot("BEFORE_REMOVE");
    }

    @Inject(method = "pumpBuffers", at = @At("RETURN"))
    private void etchedspeakers$queueAfterRefill(int count, CallbackInfo callback) {
        etchedspeakers$queueSnapshot("AFTER_QUEUEING");
    }

    @Inject(method = "removeProcessedBuffers", at = @At("RETURN"))
    private void etchedspeakers$advance(CallbackInfoReturnable<Integer> callback) {
        if (etchedspeakers$session != null) {
            etchedspeakers$session.unqueued(callback.getReturnValue());
            etchedspeakers$session.recovery().removed(callback.getReturnValue(),
                    AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED));
            etchedspeakers$queueSnapshot("AFTER_REMOVE");
        }
    }

    @Inject(method = "updateStream", at = @At("RETURN"))
    private void etchedspeakers$queueUpdateEnd(CallbackInfo callback) {
        if (etchedspeakers$session != null) {
            if (!etchedspeakers$session.isClosed() && etchedspeakers$session.recovery().finish(
                    AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED,
                    AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED),
                    etchedspeakers$session.decodedEndFrame(), etchedspeakers$session.hasDecoderEof())) {
                // All consumed buffers were unqueued; only newly decoded PCM remains. No seek/reopen/reset.
                AL10.alSourcePlay(source);
                boolean resumed = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
                if (!resumed) etchedspeakers$session.recovery().stop();
                net.yakel.etchedspeakers.EtchedSpeakers.LOGGER.info(
                        "[ES-AUDIO] UNDERRUN_{} masterId={} alSource={} source={} media={} baseFrame={}",
                        resumed ? "RESUMED" : "RESUME_FAILED", etchedspeakers$session.diagnostic().id, source,
                        etchedspeakers$session.sourceKey(), etchedspeakers$session.mediaKey(),
                        etchedspeakers$session.diagnosticBaseFrame());
            }
            etchedspeakers$session.queueDiagnostic().endUpdate(etchedspeakers$session.diagnosticBaseFrame());
            etchedspeakers$session.update();
        }
    }

    @Inject(method = {"play", "unpause"}, at = @At("RETURN"))
    private void etchedspeakers$service(CallbackInfo callback) {
        if (etchedspeakers$session != null) {
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) etchedspeakers$session.recovery().playing();
            etchedspeakers$queueSnapshot("PLAY_OR_UNPAUSE_RETURN");
            etchedspeakers$session.queueDiagnostic().report();
            etchedspeakers$session.update();
        }
    }

    @Inject(method = "pause", at = @At("RETURN"))
    private void etchedspeakers$pause(CallbackInfo callback) {
        if (etchedspeakers$session != null) {
            etchedspeakers$session.recovery().pause();
            etchedspeakers$queueSnapshot("PAUSE_RETURN");
            etchedspeakers$session.queueDiagnostic().report();
            etchedspeakers$session.pause();
        }
    }

    @Inject(method = "unpause", at = @At("HEAD"))
    private void etchedspeakers$unpauseIntent(CallbackInfo callback) {
        if (etchedspeakers$session != null) etchedspeakers$session.recovery().unpause();
    }

    @Unique
    private void etchedspeakers$queueSnapshot(String phase) {
        if (etchedspeakers$session != null) {
            etchedspeakers$session.queueDiagnostic().snapshot(phase, etchedspeakers$session.diagnosticBaseFrame());
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void etchedspeakers$stop(CallbackInfo callback) {
        if (etchedspeakers$session != null) {
            etchedspeakers$session.recovery().stop();
            var trace = etchedspeakers$session.diagnostic();
            trace.stop("ORIGINAL_CHANNEL_STOP");
            etchedspeakers$queueSnapshot("EXPLICIT_STOP_HEAD");
            trace.log("ORIGINAL_CHANNEL_STOP", "alSource=" + source + " caller=" + AudioDiagnostics.caller());
            AudioThreadBridge.sync(() -> etchedspeakers$session.detach("MASTER_STOP"));
        }
    }

    @Inject(method = "stop", at = @At("RETURN"))
    private void etchedspeakers$queueExplicitStop(CallbackInfo callback) {
        if (etchedspeakers$session != null) {
            etchedspeakers$queueSnapshot("EXPLICIT_STOP_RETURN");
            etchedspeakers$session.queueDiagnostic().report();
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void etchedspeakers$destroy(CallbackInfo callback) {
        if (etchedspeakers$session != null) {
            etchedspeakers$queueSnapshot("DESTROY_HEAD");
            etchedspeakers$session.queueDiagnostic().report();
            etchedspeakers$session.diagnostic().log("ORIGINAL_CHANNEL_DESTROY", "alSource=" + source + " caller=" + AudioDiagnostics.caller());
            AudioThreadBridge.sync(() -> etchedspeakers$session.close("ORIGINAL_CHANNEL_DESTROY"));
            etchedspeakers$session = null;
        }
    }
}
