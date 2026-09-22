package net.yakel.etchedspeakers.client;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.client.audio.SpeakerAudioManager;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.yakel.etchedspeakers.client.audio.sync.AudioDiagnostics;

/** Dist filter is processed by the loader before this class (and its client imports) is loaded. */
@EventBusSubscriber(modid = EtchedSpeakers.MOD_ID, value = Dist.CLIENT)
public final class EtchedSpeakersClient {
    private static final SpeakerAudioManager AUDIO = new SpeakerAudioManager();

    private EtchedSpeakersClient() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        AUDIO.tick(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        AUDIO.reset(Minecraft.getInstance(), "DISCONNECT");
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) AudioDiagnostics.reset("WORLD_UNLOAD");
    }

}
