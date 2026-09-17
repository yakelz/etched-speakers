package net.yakel.etchedspeakers.client.source;

import net.yakel.etchedspeakers.source.model.SourcePlaybackState.Availability;
import net.yakel.etchedspeakers.source.model.SourcePlaybackState.Playback;
import net.yakel.etchedspeakers.source.model.SourceType;
import net.yakel.etchedspeakers.source.model.TrackReference;
import java.util.Optional;
import net.minecraft.client.resources.sounds.SoundInstance;

/** Client knowledge only. The sound token identifies a playback occurrence, never the media itself. */
public record ClientSourcePlaybackState(Availability availability, Optional<SourceType> type,
        Playback playing, Optional<TrackReference> currentTrack, Optional<SoundInstance> sourceSound,
        long clientGameTime, String reason) {
    public ClientSourcePlaybackState {
        if (currentTrack.isPresent() != sourceSound.isPresent()
                || (currentTrack.isPresent() && (playing != Playback.PLAYING || availability != Availability.AVAILABLE))) {
            throw new IllegalArgumentException("A known client track requires an active source sound");
        }
    }

    public static ClientSourcePlaybackState unavailable(Availability availability, long time) {
        return new ClientSourcePlaybackState(availability, Optional.empty(), Playback.UNKNOWN,
                Optional.empty(), Optional.empty(), time, availability.name());
    }
}
