package net.yakel.etchedspeakers.client.audio.sync;

import gg.moonflower.etched.api.sound.AbstractOnlineSoundInstance;
import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.client.sound.EmptyAudioStream;
import gg.moonflower.etched.core.mixin.client.render.LevelRendererAccessor;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.core.GlobalPos;

/** Wraps the original getStream future exactly once; never calls getStream/openStream itself. */
public final class PcmTapInstaller {
    private PcmTapInstaller() {}

    public static CompletableFuture<AudioStream> wrap(AbstractOnlineSoundInstance owner, CompletableFuture<AudioStream> original) {
        var client = Minecraft.getInstance();
        var level = client.level;
        if (level == null || !(owner.getSound() instanceof AbstractOnlineSoundInstance.OnlineSound sound)
                || sound.getAudioFileType() != AudioSource.AudioFileType.FILE) return original;
        var records = ((LevelRendererAccessor) client.levelRenderer).getPlayingJukeboxSongs();
        var entry = records.entrySet().stream().filter(e -> MasterSessions.unwrap(e.getValue()) == owner).findFirst();
        if (entry.isEmpty()) return original; // Entity/radio/other sounds are not this prototype's master.
        var pos = entry.get().getKey().immutable();
        var token = entry.get().getValue();
        var source = GlobalPos.of(level.dimension(), pos);
        AudioDiagnostics.observe(source);
        AudioDiagnostics.event("STREAM_REQUEST", "source=" + SpeakerRequest.describe(source)
                + " soundInstanceIdentity=" + AudioDiagnostics.identity(owner));
        return original.thenCompose(stream -> {
            var result = new CompletableFuture<AudioStream>();
            client.execute(() -> {
                AudioDiagnostics.event("STREAM_RETURN", "source=" + SpeakerRequest.describe(source)
                        + " soundInstanceIdentity=" + AudioDiagnostics.identity(owner) + " streamIdentity=" + AudioDiagnostics.identity(stream));
                if (client.level != level || records.get(pos) != token || !client.getSoundManager().isActive(token)) {
                    String reason = client.level != level ? "WORLD_CHANGE" : records.get(pos) != token ? "SOURCE_SOUND_REPLACED" : "ORIGINAL_SOUND_INACTIVE";
                    AudioDiagnostics.event("STREAM_DISCARDED", "source=" + SpeakerRequest.describe(source) + " reason=" + reason
                            + " streamIdentity=" + AudioDiagnostics.identity(stream));
                    try { stream.close(); } catch (IOException ignored) {
                        AudioDiagnostics.event("DECODER_CLOSE", "result=FAILED reason=" + reason + " exceptionClass=" + ignored.getClass().getName());
                    }
                    result.complete(EmptyAudioStream.INSTANCE);
                } else if (stream == EmptyAudioStream.INSTANCE || !PcmTap.supports(stream.getFormat())) {
                    result.complete(stream);
                } else {
                    result.complete(new PcmTap(owner, token, source, stream));
                }
            });
            return result;
        });
    }
}
