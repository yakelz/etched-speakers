package net.yakel.etchedspeakers.source.model;

/** Source BE/resolver identity, independent of whether its media happens to be a URL. */
public enum OriginalSourceKind {
    UNKNOWN, ALBUM_ETCHED, VANILLA_NATIVE, VANILLA_ETCHED;
    public static OriginalSourceKind of(SourcePlaybackState state) {
        if(state.type().orElse(null)==SourceType.ALBUM_JUKEBOX) return ALBUM_ETCHED;
        return switch(state.reason()) {
            case VANILLA_SONG_PLAYER -> VANILLA_NATIVE;
            case ETCHED_CLIENT_SEQUENCE -> VANILLA_ETCHED;
            default -> UNKNOWN;
        };
    }
}
