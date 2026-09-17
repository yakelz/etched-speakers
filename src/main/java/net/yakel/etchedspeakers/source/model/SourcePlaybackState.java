package net.yakel.etchedspeakers.source.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A server observation, not a claim about audio reaching a client's speakers. */
public record SourcePlaybackState(
        Availability availability,
        Optional<SourceType> type,
        Playback playing,
        List<TrackReference> availableTracks,
        Optional<TrackReference> currentTrack,
        Optional<ServerSelection> serverSelection,
        long serverGameTime,
        Reason reason) {

    public SourcePlaybackState {
        Objects.requireNonNull(availability);
        Objects.requireNonNull(type);
        Objects.requireNonNull(playing);
        availableTracks = List.copyOf(availableTracks);
        Objects.requireNonNull(currentTrack);
        Objects.requireNonNull(serverSelection);
        Objects.requireNonNull(reason);
        if (availability == Availability.AVAILABLE && type.isEmpty()) {
            throw new IllegalArgumentException("Available source requires a type");
        }
        if (availability != Availability.AVAILABLE && (playing != Playback.UNKNOWN
                || !availableTracks.isEmpty() || currentTrack.isPresent() || serverSelection.isPresent())) {
            throw new IllegalArgumentException("Unavailable source cannot claim playback or tracks");
        }
        if (currentTrack.isPresent() && (playing != Playback.PLAYING || !availableTracks.contains(currentTrack.get()))) {
            throw new IllegalArgumentException("Known current track requires confirmed playback and playlist membership");
        }
    }

    public boolean available() {
        return availability == Availability.AVAILABLE;
    }

    public boolean hasTracks() {
        return !availableTracks.isEmpty();
    }

    /** Diagnostic only: this can be stale after client-side automatic next. */
    public Optional<TrackReference> serverSelectedTrack() {
        return serverSelection.flatMap(selection -> availableTracks.stream()
                .filter(track -> track.slot() == selection.slot() && track.trackIndex() == selection.trackIndex()).findFirst());
    }

    public static SourcePlaybackState unavailable(Availability availability, long gameTime) {
        if (availability == Availability.AVAILABLE) {
            throw new IllegalArgumentException("Expected an unavailable status");
        }
        return new SourcePlaybackState(availability, Optional.empty(), Playback.UNKNOWN, List.of(),
                Optional.empty(), Optional.empty(), gameTime, Reason.UNAVAILABLE);
    }

    public enum Availability { AVAILABLE, UNLINKED, CHUNK_UNLOADED, SOURCE_MISSING, DIMENSION_UNAVAILABLE }
    public enum Playback { PLAYING, STOPPED, UNKNOWN }
    public enum Reason { EMPTY, NO_PLAYABLE_TRACKS, VANILLA_SONG_PLAYER, ETCHED_CLIENT_SEQUENCE, ALBUM_CLIENT_SEQUENCE, REDSTONE_DISABLED, UNAVAILABLE }
    public record ServerSelection(int slot, int trackIndex) {}
}
