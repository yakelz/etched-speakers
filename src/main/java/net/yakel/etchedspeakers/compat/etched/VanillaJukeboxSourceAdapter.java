package net.yakel.etchedspeakers.compat.etched;

import net.yakel.etchedspeakers.source.AudioSourceAdapter;
import net.yakel.etchedspeakers.source.model.SourcePlaybackState;
import net.yakel.etchedspeakers.source.model.SourcePlaybackState.*;
import net.yakel.etchedspeakers.source.model.SourceType;
import net.yakel.etchedspeakers.source.model.TrackReference;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

/** Vanilla block, including the separate Etched URL-record path. */
public final class VanillaJukeboxSourceAdapter implements AudioSourceAdapter {
    @Override
    public SourceType type() {
        return SourceType.VANILLA_JUKEBOX;
    }

    @Override
    public boolean supports(BlockEntity entity) {
        return entity instanceof JukeboxBlockEntity && entity.getBlockState().is(Blocks.JUKEBOX);
    }

    @Override
    public SourcePlaybackState read(ServerLevel level, BlockEntity entity) {
        if (!supports(entity)) throw new IllegalArgumentException("Expected vanilla Jukebox");
        var jukebox = (JukeboxBlockEntity) entity;
        var record = jukebox.getTheItem();
        if (record.isEmpty()) {
            return state(level, Playback.STOPPED, List.of(), Optional.empty(), Reason.EMPTY);
        }
        var song = JukeboxSong.fromStack(level.registryAccess(), record);
        if (song.isPresent()) {
            // setTheItem uses vanilla playback whenever JUKEBOX_PLAYABLE resolves successfully.
            JukeboxSong activeSong = jukebox.getSongPlayer().getSong();
            JukeboxSong observedSong = activeSong != null ? activeSong : song.get().value();
            Optional<String> songId = observedSong.equals(song.get().value())
                    ? song.get().unwrapKey().map(key -> key.location().toString()) : Optional.empty();
            var track = new TrackReference(TrackReference.Kind.SOUND_EVENT,
                    observedSong.soundEvent().value().getLocation().toString(), songId, 0, 0);
            return state(level, activeSong != null ? Playback.PLAYING : Playback.STOPPED, List.of(track),
                    activeSong != null ? Optional.of(track) : Optional.empty(), Reason.VANILLA_SONG_PLAYER);
        }
        List<TrackReference> tracks = EtchedTrackReader.hasEtchedMusic(record)
                ? EtchedTrackReader.read(level.registryAccess(), record, 0) : List.of();
        return state(level, tracks.isEmpty() ? Playback.STOPPED : Playback.UNKNOWN, tracks, Optional.empty(),
                tracks.isEmpty() ? Reason.NO_PLAYABLE_TRACKS : Reason.ETCHED_CLIENT_SEQUENCE);
    }

    private SourcePlaybackState state(ServerLevel level, Playback playing, List<TrackReference> tracks,
                                      Optional<TrackReference> current, Reason reason) {
        return new SourcePlaybackState(Availability.AVAILABLE, Optional.of(type()), playing, tracks, current,
                Optional.empty(), level.getGameTime(), reason);
    }
}
