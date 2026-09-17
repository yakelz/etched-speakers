package net.yakel.etchedspeakers.registry;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.block.SpeakerBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EtchedSpeakers.MOD_ID);
    public static final DeferredBlock<SpeakerBlock> SPEAKER = BLOCKS.registerBlock(
            "speaker", SpeakerBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F, 3.0F).sound(SoundType.WOOD));

    private ModBlocks() {}
}
