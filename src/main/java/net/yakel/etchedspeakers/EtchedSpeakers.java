package net.yakel.etchedspeakers;

import com.mojang.logging.LogUtils;
import net.yakel.etchedspeakers.registry.ModBlockEntities;
import net.yakel.etchedspeakers.registry.ModBlocks;
import net.yakel.etchedspeakers.registry.ModDataComponents;
import net.yakel.etchedspeakers.registry.ModItems;
import net.yakel.etchedspeakers.registry.ModMenus;
import net.yakel.etchedspeakers.network.SpeakerSettingsPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/** Common entry point. Must remain safe to load on a dedicated server. */
@Mod(EtchedSpeakers.MOD_ID)
public final class EtchedSpeakers {
    public static final String MOD_ID = "etchedspeakers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EtchedSpeakers(IEventBus modEventBus) {
        ModDataComponents.DATA_COMPONENTS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        modEventBus.addListener(SpeakerSettingsPayload::register);
        modEventBus.addListener(net.yakel.etchedspeakers.network.RemotePayloads::register);
        modEventBus.addListener(ModItems::addCreativeItems);
        LOGGER.info("Etched Speakers initialized");
    }
}
