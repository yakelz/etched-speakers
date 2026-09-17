package net.yakel.etchedspeakers.compat.etched;

import net.yakel.etchedspeakers.source.AudioSourceAdapter;
import net.yakel.etchedspeakers.source.model.SourcePlaybackState;
import net.yakel.etchedspeakers.source.model.SourcePlaybackState.*;
import net.yakel.etchedspeakers.source.model.SourceType;
import net.yakel.etchedspeakers.source.model.TrackReference;
import gg.moonflower.etched.common.block.AlbumJukeboxBlock;
import gg.moonflower.etched.common.blockentity.AlbumJukeboxBlockEntity;
import java.util.ArrayList;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class AlbumJukeboxSourceAdapter implements AudioSourceAdapter {
    @Override
    public SourceType type() {
        return SourceType.ALBUM_JUKEBOX;
    }

    @Override
    public boolean supports(BlockEntity entity) {
        return EtchedSourceDetector.isAlbumJukebox(entity);
    }

    @Override
    public SourcePlaybackState read(ServerLevel level, BlockEntity entity) {
        if (!supports(entity)) throw new IllegalArgumentException("Expected Album Jukebox");
        var jukebox = (AlbumJukeboxBlockEntity) entity;
        var tracks = new ArrayList<TrackReference>();
        for (int slot = 0; slot < jukebox.getContainerSize(); slot++) {
            tracks.addAll(EtchedTrackReader.read(level.registryAccess(), jukebox.getItem(slot), slot));
        }
        boolean powered = jukebox.getBlockState().getValue(AlbumJukeboxBlock.POWERED);
        // isPlaying() merely tests nonempty inventory and redstone. It is NOT an audio clock.
        Playback playing = powered || tracks.isEmpty() ? Playback.STOPPED : Playback.UNKNOWN;
        Reason reason = powered ? Reason.REDSTONE_DISABLED
                : tracks.isEmpty() ? Reason.NO_PLAYABLE_TRACKS : Reason.ALBUM_CLIENT_SEQUENCE;
        return new SourcePlaybackState(Availability.AVAILABLE, Optional.of(type()), playing, tracks,
                Optional.empty(), Optional.of(new ServerSelection(jukebox.getPlayingIndex(), jukebox.getTrack())),
                level.getGameTime(), reason);
    }
}
