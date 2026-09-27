package net.yakel.etchedspeakers.tests;

import net.yakel.etchedspeakers.source.model.SourcePlaybackState;
import net.yakel.etchedspeakers.source.model.SourcePlaybackState.*;
import net.yakel.etchedspeakers.source.model.SourceStateChanges;
import net.yakel.etchedspeakers.source.model.SourceStateChanges.Change;
import net.yakel.etchedspeakers.source.model.SourceType;
import net.yakel.etchedspeakers.source.model.TrackReference;
import net.yakel.etchedspeakers.source.model.PcmWindow;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Executed by Gradle check; no Minecraft bootstrap or downloaded test framework needed. */
public final class SourceStateChecks {
    private static int checks;

    public static void main(String[] args) {
        NativeDiscChecks.run();
        OriginalRecoveryChecks.run();
        LocalOnlyRecoveryChecks.run();
        NativeTickingChecks.run();
        SessionHandoffChecks.run();
        LocalCanonicalChecks.run();
        RetentionChecks.run();
        StreamingRecoveryChecks.run();
        RemoteSessionChecks.run();
        var a = url("https://example.invalid/a?token=secret", 0, 0);
        var b = url("https://example.invalid/b", 0, 1);
        check(a.equals(url(a.location(), 0, 0)), "Identity survives reconstruction");
        check(!a.mediaKey().equals(url("https://example.invalid/a?token=changed", 0, 0).mediaKey()), "Query contributes to identity");
        check(!a.toString().contains("https") && !a.toString().contains("secret"), "Diagnostic hides URL and query");
        check(!a.stableKey().equals(url(a.location(), 1, 0).stableKey()), "Slot distinguishes repeated media");
        check(!a.stableKey().equals(url(a.location(), 0, 1).stableKey()), "Index distinguishes repeated media");
        check(url(a.location(), 0, TrackReference.UNKNOWN_INDEX).stableKey().endsWith("|track=?"), "Unknown client index is explicit");
        rejects(IllegalArgumentException.class, () -> url(a.location(), 0, -2), "Index below unknown sentinel rejected");
        var empty = state(Playback.STOPPED, List.of(), Optional.empty(), 1);
        var unknown = state(Playback.UNKNOWN, List.of(a), Optional.empty(), 2);
        var playingA = state(Playback.PLAYING, List.of(a), Optional.of(a), 3);
        var playingB = state(Playback.PLAYING, List.of(b), Optional.of(b), 4);
        var insert = SourceStateChanges.compare(empty, unknown);
        check(insert.contains(Change.TRACKS_CHANGED) && !insert.contains(Change.START), "Etched insertion does not prove START");
        check(SourceStateChanges.compare(unknown, empty).contains(Change.STOP), "Eject confirms stopped condition");
        check(SourceStateChanges.compare(empty, playingA).contains(Change.START), "Known playback starts");
        check(SourceStateChanges.compare(playingA, playingB).contains(Change.TRACK_CHANGED), "Known current track changes");
        check(!SourceStateChanges.compare(playingA, unknown).contains(Change.STOP), "Unknown is not stopped");
        check(SourceStateChanges.compare(playingA, state(Playback.PLAYING, List.of(a), Optional.of(a), 999)).isEmpty(), "Observation clock alone is not a change");
        var unloaded = SourcePlaybackState.unavailable(Availability.CHUNK_UNLOADED, 5);
        check(SourceStateChanges.compare(playingA, unloaded).equals(Set.of(Change.AVAILABILITY_CHANGED)), "Unload is not STOP");
        check(SourceStateChanges.compare(unloaded, playingA).equals(Set.of(Change.AVAILABILITY_CHANGED)), "Reload is not START");
        var albumA = album(List.of(a, b), 0);
        var albumB = album(List.of(a, b), 1);
        check(SourceStateChanges.compare(albumA, albumB).equals(Set.of(Change.SERVER_SELECTION_CHANGED)), "Server selection is not known playback");
        check(albumB.currentTrack().isEmpty() && albumB.serverSelectedTrack().equals(Optional.of(b)), "Diagnostic candidate remains separate");
        var mutable = new ArrayList<>(List.of(a));
        var snapshot = state(Playback.UNKNOWN, mutable, Optional.empty(), 0);
        mutable.clear();
        check(snapshot.availableTracks().equals(List.of(a)), "Snapshot copies input list");
        rejects(UnsupportedOperationException.class, () -> snapshot.availableTracks().clear(), "Snapshot list immutable");
        rejects(IllegalArgumentException.class, () -> state(Playback.UNKNOWN, List.of(a), Optional.of(a), 0), "Unknown cannot claim current track");
        rejects(IllegalArgumentException.class, () -> state(Playback.PLAYING, List.of(a), Optional.of(b), 0), "Current track must belong to source");
        rejects(IllegalArgumentException.class, () -> SourcePlaybackState.unavailable(Availability.AVAILABLE, 0), "Unavailable factory rejects AVAILABLE");
        pcmChecks();
        System.out.println("Source state checks passed: " + checks);
        MultiSpeakerChecks.run();
        SpeakerSettingsChecks.run();
    }

    private static void pcmChecks() {
        var window = new PcmWindow(4, 1);
        var stereo = ByteBuffer.wrap(new byte[]{1, 11, 2, 12, 3, 13});
        window.append(stereo, 2);
        check(stereo.position() == 0 && stereo.limit() == 6, "Tee preserves original buffer view");
        var out = ByteBuffer.allocate(8);
        check(window.copy(0, 3, out) == 3 && out.get(0) == 1 && out.get(2) == 3, "Mono tee copies first channel");
        window.append(ByteBuffer.wrap(new byte[]{4, 5, 6}), 1);
        check(window.startFrame() == 2 && window.endFrame() == 6 && window.capacityFrames() == 4, "Window capacity is bounded");
        out.clear();
        check(window.copy(2, 4, out) == 4 && out.get(0) == 3 && out.get(3) == 6, "Wrapped frames remain ordered");
        check(window.sequence() == 2, "Sequence advances per nonempty decoded chunk");
        rejects(IllegalArgumentException.class, () -> window.copy(0, 1, out), "Late reader cannot replay evicted PCM");
        rejects(IllegalArgumentException.class, () -> window.copy(7, 1, out), "Future frames cannot be fabricated");
        out.clear();
        check(window.copy(5, 4, out) == 1 && out.get(0) == 6, "Mid-song join copies current frame, not zero");
        window.append(ByteBuffer.allocate(0), 1);
        check(window.sequence() == 2, "EOF does not create fake sequence");
        window.append(ByteBuffer.wrap(new byte[]{7, 8, 9, 10, 11, 12}), 1);
        out.clear();
        check(window.copy(8, 4, out) == 4 && out.get(0) == 9 && out.get(3) == 12, "Oversized append retains only bounded tail");
        var separate = new PcmWindow(4, 1);
        check(separate.endFrame() == 0 && window.endFrame() == 12, "Track sessions never share cursor/data");
        window.clear();
        check(window.endFrame() == 0 && window.startFrame() == 0 && window.sequence() == 0, "Cleanup resets PCM state");
        var pcm16 = new PcmWindow(4, 2);
        rejects(IllegalArgumentException.class, () -> pcm16.append(ByteBuffer.wrap(new byte[]{1, 2, 3}), 1), "Partial sample frames rejected");
        pcm16.append(ByteBuffer.wrap(new byte[]{1, 2, 9, 9, 3, 4, 8, 8}), 2);
        out.clear();
        check(pcm16.copy(0, 2, out) == 2 && out.get(0) == 1 && out.get(1) == 2 && out.get(2) == 3 && out.get(3) == 4, "16-bit samples retain byte order");
        var tooSmall = ByteBuffer.allocate(3);
        check(pcm16.copy(0, 2, tooSmall) == 1 && tooSmall.position() == 2, "Output capacity preserves whole frames");
    }

    private static TrackReference url(String location, int slot, int track) {
        return new TrackReference(TrackReference.Kind.URL, location, Optional.empty(), slot, track);
    }

    private static SourcePlaybackState state(Playback playing, List<TrackReference> tracks, Optional<TrackReference> current, long time) {
        return new SourcePlaybackState(Availability.AVAILABLE, Optional.of(SourceType.VANILLA_JUKEBOX),
                playing, tracks, current, Optional.empty(), time, Reason.ETCHED_CLIENT_SEQUENCE);
    }

    private static SourcePlaybackState album(List<TrackReference> tracks, int index) {
        return new SourcePlaybackState(Availability.AVAILABLE, Optional.of(SourceType.ALBUM_JUKEBOX),
                Playback.UNKNOWN, tracks, Optional.empty(), Optional.of(new ServerSelection(0, index)), 1, Reason.ALBUM_CLIENT_SEQUENCE);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void rejects(Class<? extends RuntimeException> expected, Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            if (!expected.isInstance(exception)) throw new AssertionError(message, exception);
            checks++;
            return;
        }
        throw new AssertionError(message);
    }
}
