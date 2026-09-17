package net.yakel.etchedspeakers.registry;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EtchedSpeakers.MOD_ID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SpeakerBlockEntity>> SPEAKER =
            BLOCK_ENTITIES.register("speaker",
                    () -> BlockEntityType.Builder.of(SpeakerBlockEntity::new, ModBlocks.SPEAKER.get()).build(null));

    private ModBlockEntities() {}
}
