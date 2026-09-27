package net.yakel.etchedspeakers.source.model;

/** Physical demand inside the existing bounded retention lifecycle. Never a second admission set. */
public enum SourceTicketMode {
    NONE, LOAD_ONLY, TICKING_NATIVE;

    public static SourceTicketMode desired(boolean ticketNeeded, SourceTicketMode current,
            SourcePlaybackState state, int remoteListeners, boolean grace) {
        if (!ticketNeeded) return NONE;
        return nativePlaying(state) && (remoteListeners > 0 || current == TICKING_NATIVE && grace)
                ? TICKING_NATIVE : LOAD_ONLY;
    }
    public static boolean nativePlaying(SourcePlaybackState state) {
        return state.available()
                && state.reason() == SourcePlaybackState.Reason.VANILLA_SONG_PLAYER
                && state.playing() == SourcePlaybackState.Playback.PLAYING && state.currentTrack().isPresent();
    }
}
