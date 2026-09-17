package net.yakel.etchedspeakers.compat.etched;

import gg.moonflower.etched.common.blockentity.AlbumJukeboxBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.entity.BlockEntity;

/** The only Etched dependency needed for source selection. Never loads source chunks. */
public final class EtchedSourceDetector {
    private EtchedSourceDetector() {}

    public static boolean isAlbumJukebox(@Nullable BlockEntity blockEntity) {
        return blockEntity instanceof AlbumJukeboxBlockEntity;
    }
}
