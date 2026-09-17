package net.yakel.etchedspeakers.client.gui;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = EtchedSpeakers.MOD_ID, value = Dist.CLIENT)
public final class SpeakerScreens {
    private SpeakerScreens() {}

    @SubscribeEvent
    public static void register(RegisterMenuScreensEvent event) {
        event.register(ModMenus.SPEAKER_SETTINGS.get(), SpeakerSettingsScreen::new);
    }
}
