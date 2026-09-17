package net.yakel.etchedspeakers.registry;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.item.LinkingToolItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EtchedSpeakers.MOD_ID);
    public static final DeferredItem<BlockItem> SPEAKER = ITEMS.registerSimpleBlockItem("speaker", ModBlocks.SPEAKER);
    public static final DeferredItem<LinkingToolItem> LINKING_TOOL = ITEMS.register(
            "linking_tool", () -> new LinkingToolItem(new Item.Properties().stacksTo(1)));

    private ModItems() {}

    public static void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS)) {
            event.accept(SPEAKER.get());
        }
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) {
            event.accept(LINKING_TOOL.get());
        }
    }
}
