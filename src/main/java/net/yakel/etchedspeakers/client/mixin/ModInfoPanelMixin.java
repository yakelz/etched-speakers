package net.yakel.etchedspeakers.client.mixin;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.neoforged.neoforge.client.gui.ModListScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Keep logo layout, scrolling and text hit testing aligned for our mod's taller banner. */
@Mixin(targets = "net.neoforged.neoforge.client.gui.ModListScreen$InfoPanel", remap = false)
public abstract class ModInfoPanelMixin {
    @Shadow @Final private ModListScreen this$0;

    @Unique
    private boolean etchedspeakers$isSelected() {
        var selected = ((ModListScreenAccessor) this$0).etchedspeakers$getSelectedMod();
        return selected != null && EtchedSpeakers.MOD_ID.equals(selected.getInfo().getModId());
    }

    @ModifyConstant(method = {"drawPanel", "getContentHeight"}, constant = @Constant(intValue = 50))
    private int etchedspeakers$bannerHeight(int original) {
        return etchedspeakers$isSelected() ? 100 : original;
    }

    @ModifyConstant(method = "findTextLine", constant = @Constant(doubleValue = 50.0))
    private double etchedspeakers$textOffset(double original) {
        return etchedspeakers$isSelected() ? 100.0 : original;
    }
}
