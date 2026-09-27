package net.yakel.etchedspeakers.client.mixin;

import gg.moonflower.etched.common.network.play.ClientboundPlayBlockMusicPacket;
import gg.moonflower.etched.common.network.play.handler.EtchedClientPlayPacketHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.yakel.etchedspeakers.client.audio.remote.LocalSourceSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Explicit Etched start/stop packet supersedes pending recovery, not a decoder-end notification. */
@Mixin(value=EtchedClientPlayPacketHandler.class,remap=false)
public abstract class EtchedBlockRecoveryMixin {
    @Inject(method="handlePlayBlockMusicPacket",at=@At("HEAD"),remap=false)
    private static void etchedspeakers$packet(ClientboundPlayBlockMusicPacket packet,IPayloadContext context,CallbackInfo ci) {
        LocalSourceSync.blockPacket(packet.pos());
    }
}
