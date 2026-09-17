package net.yakel.etchedspeakers.network;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.menu.SpeakerSettingsMenu;
import net.yakel.etchedspeakers.source.model.SpeakerSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Only the active server menu identifies the block; no arbitrary client-supplied position/dimension. */
public record SpeakerSettingsPayload(int containerId, float volume, float range) implements CustomPacketPayload {
    public static final Type<SpeakerSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EtchedSpeakers.MOD_ID, "speaker_settings"));
    public static final StreamCodec<FriendlyByteBuf, SpeakerSettingsPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpeakerSettingsPayload::containerId,
            ByteBufCodecs.FLOAT, SpeakerSettingsPayload::volume,
            ByteBufCodecs.FLOAT, SpeakerSettingsPayload::range, SpeakerSettingsPayload::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, CODEC, SpeakerSettingsPayload::handle);
    }

    private static void handle(SpeakerSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof SpeakerSettingsMenu menu)
                    || menu.containerId != payload.containerId()) return;
            if (!menu.stillValid(player)) { player.closeContainer(); return; }
            var settings = new SpeakerSettings(payload.volume(), payload.range());
            menu.apply(player, settings.volume(), settings.audibleRange());
        });
    }
}
