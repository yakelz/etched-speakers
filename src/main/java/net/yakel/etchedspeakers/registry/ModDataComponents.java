package net.yakel.etchedspeakers.registry;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.component.SelectedSource;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, EtchedSpeakers.MOD_ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SelectedSource>> SELECTED_SOURCE =
            DATA_COMPONENTS.registerComponentType("selected_source", builder -> builder
                    .persistent(SelectedSource.CODEC)
                    .networkSynchronized(SelectedSource.STREAM_CODEC));

    private ModDataComponents() {}
}
