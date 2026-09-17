package net.yakel.etchedspeakers.client.audio.sync;

import gg.moonflower.etched.api.sound.AbstractOnlineSoundInstance;
import gg.moonflower.etched.api.sound.source.AudioSource;
import gg.moonflower.etched.client.sound.EmptyAudioStream;
import gg.moonflower.etched.core.mixin.client.render.LevelRendererAccessor;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;

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
        return original.thenCompose(stream -> {
            var result = new CompletableFuture<AudioStream>();
            client.execute(() -> {
                if (client.level != level || records.get(pos) != token || !client.getSoundManager().isActive(token)) {
                    try { stream.close(); } catch (IOException ignored) {}
                    result.complete(EmptyAudioStream.INSTANCE);
                } else if (stream == EmptyAudioStream.INSTANCE || !PcmTap.supports(stream.getFormat())) {
                    result.complete(stream);
                } else {
                    result.complete(new PcmTap(owner, stream));
                }
            });
            return result;
        });
    }
}
