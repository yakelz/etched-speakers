package net.yakel.etchedspeakers.source;

import net.yakel.etchedspeakers.source.model.SourcePlaybackState;
import net.yakel.etchedspeakers.source.model.SourceType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

public interface AudioSourceAdapter {
    SourceType type();
    boolean supports(BlockEntity blockEntity);

    /** Main server thread, already-loaded matching BE; no mutation, HTTP or audio operations. */
    SourcePlaybackState read(ServerLevel level, BlockEntity blockEntity);
}
