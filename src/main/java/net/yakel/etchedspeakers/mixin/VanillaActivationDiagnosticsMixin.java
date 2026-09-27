package net.yakel.etchedspeakers.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.yakel.etchedspeakers.remote.RemoteSessions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Inventory evidence and native occurrence invalidation; Etched URL activation remains untouched. */
@Mixin(JukeboxBlockEntity.class)
public abstract class VanillaActivationDiagnosticsMixin {
    @Unique private boolean etchedspeakers$wasEmpty;

    @Inject(method="tick",at=@At("RETURN"))
    private static void etchedspeakers$observeNative(net.minecraft.world.level.Level level,net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,JukeboxBlockEntity jukebox,CallbackInfo ci) {
        if(level instanceof ServerLevel server) RemoteSessions.observeLocalNative(server,jukebox);
    }

    @Inject(method = "setTheItem", at = @At("HEAD"))
    private void etchedspeakers$beforeRecord(ItemStack item, CallbackInfo ci) {
        etchedspeakers$wasEmpty = ((JukeboxBlockEntity) (Object) this).getTheItem().isEmpty();
    }

    @Inject(method = "setTheItem", at = @At("RETURN"))
    private void etchedspeakers$recordChanged(ItemStack item, CallbackInfo ci) {
        var jukebox = (JukeboxBlockEntity) (Object) this;
        if (jukebox.getLevel() instanceof ServerLevel level) {
            RemoteSessions.nativeRecordChanged(level, jukebox.getBlockPos());
            RemoteSessions.diagnoseVanillaRecord(level, jukebox, etchedspeakers$wasEmpty);
        }
    }
}
