package net.yakel.etchedspeakers.compat.etched;

import gg.moonflower.etched.common.blockentity.AlbumJukeboxBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.yakel.etchedspeakers.source.model.*;

/** Reuses Etched's actual slot traversal and track counts; never invokes client SoundTracker. */
public final class RetainedTrackSelection {
    private RetainedTrackSelection() {}
    public static TrackReference select(ServerLevel level, BlockPos pos, SourcePlaybackState state, TrackReference previous) {
        if(!state.available() || state.playing()==SourcePlaybackState.Playback.STOPPED) return null;
        var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        if(chunk==null) return null;
        if(chunk.getBlockEntity(pos) instanceof AlbumJukeboxBlockEntity album) {
            if(previous!=null) {
                // setPlayingIndex may reset track when its cached stack changes. A second call uses that cache.
                album.setPlayingIndex(previous.slot(),previous.trackIndex());
                album.setPlayingIndex(previous.slot(),previous.trackIndex());
                album.next();
            } else album.recalculatePlayingIndex(false);
            int slot=album.getPlayingIndex(), index=album.getTrack();
            return state.availableTracks().stream().filter(t->t.slot()==slot && t.trackIndex()==index).findFirst().orElse(null);
        }
        // SoundTracker.playBlockRecord advances through a disc/cover and stops at its end (no loop).
        return state.availableTracks().stream().filter(t->previous==null || t.trackIndex()>previous.trackIndex()).findFirst().orElse(null);
    }
}
