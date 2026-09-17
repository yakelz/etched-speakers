package net.yakel.etchedspeakers.registry;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.menu.SpeakerSettingsMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, EtchedSpeakers.MOD_ID);
    public static final DeferredHolder<MenuType<?>, MenuType<SpeakerSettingsMenu>> SPEAKER_SETTINGS =
            MENUS.register("speaker_settings", () -> IMenuTypeExtension.create(SpeakerSettingsMenu::new));

    private ModMenus() {}
}
