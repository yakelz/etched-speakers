package net.yakel.etchedspeakers.client.audio.remote;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.yakel.etchedspeakers.client.mixin.SoundEngineAccessor;
import net.yakel.etchedspeakers.client.mixin.SoundManagerAccessor;
import net.yakel.etchedspeakers.source.model.OriginalRecovery;

/** Java channel lifecycle only: no OpenAL queries on the client/render thread. */
public final class OriginalAudio {
    private OriginalAudio() {}
    static SoundEngineAccessor engine() {
        return (SoundEngineAccessor)((SoundManagerAccessor)Minecraft.getInstance().getSoundManager()).etchedspeakers$getEngine();
    }
    static boolean audible() {
        var o=Minecraft.getInstance().options;
        return OriginalRecovery.audible(o.getSoundSourceVolume(SoundSource.MASTER),o.getSoundSourceVolume(SoundSource.RECORDS));
    }
    static boolean healthy(SoundInstance sound) {
        if(sound==null) return false;
        var channel=engine().etchedspeakers$getChannels().get(sound);
        return channel!=null && !channel.isStopped();
    }
    public static void volumeChanged(SoundSource category,float value) {
        if(category!=SoundSource.MASTER && category!=SoundSource.RECORDS) return;
        var o=Minecraft.getInstance().options;
        boolean audible=OriginalRecovery.audible(category==SoundSource.MASTER?value:o.getSoundSourceVolume(SoundSource.MASTER),
                category==SoundSource.RECORDS?value:o.getSoundSourceVolume(SoundSource.RECORDS));
        LocalSourceSync.volumeChanged(audible);
        NativeOriginalRecovery.volumeChanged(audible);
    }
}
