package net.yakel.etchedspeakers.compat;

import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.yakel.etchedspeakers.compat.etched.AlbumJukeboxSourceAdapter;
import net.yakel.etchedspeakers.compat.etched.VanillaJukeboxSourceAdapter;
import net.yakel.etchedspeakers.source.AudioSourceAdapter;
import net.yakel.etchedspeakers.source.model.SourcePlaybackState;
import net.yakel.etchedspeakers.source.model.SourcePlaybackState.Availability;
import net.yakel.etchedspeakers.source.model.SourceType;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Reads loaded sources on the level's owning thread; never creates chunk tickets. */
public final class AudioSourceResolver {
    private static final List<AudioSourceAdapter> ADAPTERS = List.of(
            new VanillaJukeboxSourceAdapter(), new AlbumJukeboxSourceAdapter());

    private AudioSourceResolver() {}

    public static Optional<SourceType> getSourceType(Level level, BlockPos pos) {
        return resolve(level, pos).map(AudioSourceAdapter::type);
    }

    public static Optional<AudioSourceAdapter> resolve(Level level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? Optional.empty() : findAdapter(chunk.getBlockEntity(pos));
    }

    private static Optional<AudioSourceAdapter> findAdapter(BlockEntity entity) {
        return ADAPTERS.stream().filter(adapter -> adapter.supports(entity)).findFirst();
    }

    public static SourcePlaybackState readState(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return SourcePlaybackState.unavailable(Availability.CHUNK_UNLOADED, level.getGameTime());
        var entity = chunk.getBlockEntity(pos);
        return findAdapter(entity).map(adapter -> adapter.read(level, entity))
                .orElseGet(() -> SourcePlaybackState.unavailable(Availability.SOURCE_MISSING, level.getGameTime()));
    }

    public static SourcePlaybackState readLinkedSource(ServerLevel speakerLevel, SpeakerBlockEntity speaker) {
        if (!speaker.isLinked()) return SourcePlaybackState.unavailable(Availability.UNLINKED, speakerLevel.getGameTime());
        var sourceLevel = speakerLevel.getServer().getLevel(speaker.getSourceDimension().orElseThrow());
        return sourceLevel == null
                ? SourcePlaybackState.unavailable(Availability.DIMENSION_UNAVAILABLE, speakerLevel.getGameTime())
                : readState(sourceLevel, speaker.getSourcePos().orElseThrow());
    }

    public static boolean isSupportedSource(Level level, BlockPos pos) {
        return getSourceType(level, pos).isPresent();
    }

    public static boolean isChunkLoaded(Level level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

}
