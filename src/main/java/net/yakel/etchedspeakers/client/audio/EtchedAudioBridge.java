package net.yakel.etchedspeakers.client.audio;

import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.yakel.etchedspeakers.client.source.ClientSourcePlaybackState;
import net.yakel.etchedspeakers.compat.AudioSourceResolver;
import net.yakel.etchedspeakers.source.model.SourcePlaybackState.Availability;
import net.yakel.etchedspeakers.source.model.SourcePlaybackState.Playback;
import net.yakel.etchedspeakers.source.model.SourceType;
import net.yakel.etchedspeakers.source.model.TrackReference;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.api.sound.AbstractOnlineSoundInstance.OnlineSound;
import gg.moonflower.etched.common.block.AlbumJukeboxBlock;
import gg.moonflower.etched.common.blockentity.AlbumJukeboxBlockEntity;
import gg.moonflower.etched.core.mixin.client.render.LevelRendererAccessor;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.JukeboxBlock;

/** Version-specific client bridge. Reuses Etched's existing accessor and public audio API. */
public final class EtchedAudioBridge {
    private EtchedAudioBridge() {}

    public static ClientSourcePlaybackState read(Minecraft client, SpeakerBlockEntity speaker) {
        ClientLevel level = client.level;
        if (level == null) return ClientSourcePlaybackState.unavailable(Availability.DIMENSION_UNAVAILABLE, 0);
        long time = level.getGameTime();
        if (!speaker.isLinked()) return ClientSourcePlaybackState.unavailable(Availability.UNLINKED, time);
        if (!speaker.getSourceDimension().orElseThrow().equals(level.dimension())) {
            return ClientSourcePlaybackState.unavailable(Availability.DIMENSION_UNAVAILABLE, time);
        }
        BlockPos pos = speaker.getSourcePos().orElseThrow();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return ClientSourcePlaybackState.unavailable(Availability.CHUNK_UNLOADED, time);
        var type = AudioSourceResolver.getSourceType(level, pos);
        if (type.isEmpty()) return ClientSourcePlaybackState.unavailable(Availability.SOURCE_MISSING, time);
        var blockState = chunk.getBlockState(pos);
        boolean stopped = type.get() == SourceType.VANILLA_JUKEBOX
                ? !blockState.getValue(JukeboxBlock.HAS_RECORD)
                : blockState.getValue(AlbumJukeboxBlock.POWERED) || !blockState.getValue(AlbumJukeboxBlock.HAS_RECORD);
        if (stopped) return idle(type.get(), Playback.STOPPED, time, "SOURCE_STOPPED");

        // This map holds the ORIGINAL source sound. Speaker audio is never put into it.
        var original = ((LevelRendererAccessor) client.levelRenderer).getPlayingJukeboxSongs().get(pos);
        if (original == null || !client.getSoundManager().isActive(original)) {
            return idle(type.get(), Playback.UNKNOWN, time, "NO_ACTIVE_CLIENT_SOUND");
        }
        // StopListeningSound.getSound() publicly delegates to its wrapped instance.
        String location = original.getSound() instanceof OnlineSound online
                ? online.getURL() : original.getLocation().toString();
        if (!TrackData.isValidURL(location)) return idle(type.get(), Playback.UNKNOWN, time, "UNRESOLVED_CLIENT_TRACK");
        int slot = 0;
        int index = TrackReference.UNKNOWN_INDEX;
        if (chunk.getBlockEntity(pos) instanceof AlbumJukeboxBlockEntity album) {
            slot = Math.max(TrackReference.UNKNOWN_INDEX, album.getPlayingIndex());
            index = Math.max(TrackReference.UNKNOWN_INDEX, album.getTrack());
        }
        var track = new TrackReference(TrackData.isLocalSound(location) ? TrackReference.Kind.SOUND_EVENT : TrackReference.Kind.URL,
                location, Optional.empty(), slot, index);
        return new ClientSourcePlaybackState(Availability.AVAILABLE, type, Playback.PLAYING,
                Optional.of(track), Optional.of(original), time, "ACTIVE_CLIENT_SOUND");
    }

    private static ClientSourcePlaybackState idle(SourceType type, Playback playing, long time, String reason) {
        return new ClientSourcePlaybackState(Availability.AVAILABLE, Optional.of(type), playing,
                Optional.empty(), Optional.empty(), time, reason);
    }
}
