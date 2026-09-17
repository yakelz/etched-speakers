package net.yakel.etchedspeakers.source.model;

import java.util.EnumSet;
import java.util.Set;

/** Compares observations of the SAME source. Sampling cannot recover transitions between reads. */
public final class SourceStateChanges {
    private SourceStateChanges() {}

    public enum Change { START, STOP, TRACK_CHANGED, TRACKS_CHANGED, SERVER_SELECTION_CHANGED, AVAILABILITY_CHANGED, TYPE_CHANGED, KNOWLEDGE_CHANGED }

    public static Set<Change> compare(SourcePlaybackState before, SourcePlaybackState after) {
        EnumSet<Change> result = EnumSet.noneOf(Change.class);
        if (before.availability() != after.availability()) {
            result.add(Change.AVAILABILITY_CHANGED);
        }
        // Losing/reloading a chunk is not evidence of audio stopping/starting.
        if (!before.available() || !after.available()) {
            return Set.copyOf(result);
        }
        if (!before.type().equals(after.type())) {
            result.add(Change.TYPE_CHANGED);
            return Set.copyOf(result);
        }
        if (before.playing() != after.playing()) {
            switch (after.playing()) {
                case PLAYING -> result.add(Change.START);
                case STOPPED -> result.add(Change.STOP);
                case UNKNOWN -> result.add(Change.KNOWLEDGE_CHANGED);
            }
        }
        if (before.currentTrack().isPresent() && after.currentTrack().isPresent()
                && !before.currentTrack().equals(after.currentTrack())) {
            result.add(Change.TRACK_CHANGED);
        }
        if (before.currentTrack().isPresent() != after.currentTrack().isPresent()) {
            result.add(Change.KNOWLEDGE_CHANGED);
        }
        if (!before.availableTracks().equals(after.availableTracks())) {
            result.add(Change.TRACKS_CHANGED);
        }
        if (!before.serverSelection().equals(after.serverSelection())) {
            result.add(Change.SERVER_SELECTION_CHANGED);
        }
        return Set.copyOf(result);
    }
}
