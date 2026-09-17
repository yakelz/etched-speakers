package net.yakel.etchedspeakers.client.mixin;

import net.neoforged.neoforge.client.gui.ModListScreen;
import net.neoforged.neoforge.client.gui.widget.ModListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ModListScreen.class, remap = false)
public interface ModListScreenAccessor {
    @Accessor("selected")
    ModListWidget.ModEntry etchedspeakers$getSelectedMod();
}
