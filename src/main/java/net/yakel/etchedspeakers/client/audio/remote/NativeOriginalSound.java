package net.yakel.etchedspeakers.client.audio.remote;

import gg.moonflower.etched.api.sound.stream.MonoWrapper;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntToLongFunction;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.client.sounds.*;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

/** Positional RECORDS original; never a captured/remote master and never a Speaker output. */
final class NativeOriginalSound extends AbstractSoundInstance {
    final PreparedOriginalStream stream=new PreparedOriginalStream();
    private WeighedSoundEvents events;
    NativeOriginalSound(GlobalPos source,String event) {
        super(ResourceLocation.parse(event),SoundSource.RECORDS,SoundInstance.createUnseededRandom());
        x=source.pos().getX()+0.5; y=source.pos().getY()+0.5; z=source.pos().getZ()+0.5;
        volume=4; pitch=1; relative=false; attenuation=Attenuation.LINEAR;
    }
    @Override public WeighedSoundEvents resolve(SoundManager manager) {
        if(events!=null) return events;
        events=manager.getSoundEvent(getLocation());
        if(events!=null) {
            var selected=events.getSound(random);
            if(selected==SoundManager.EMPTY_SOUND) { events=null; return null; }
            sound=new Sound(selected.getLocation(),selected.getVolume(),selected.getPitch(),selected.getWeight(),
                    selected.getType(),true,selected.shouldPreload(),selected.getAttenuationDistance());
        }
        return events;
    }
    CompletableFuture<Void> prepare(SoundManager manager,SoundBufferLibrary buffers,IntToLongFunction target) {
        if(resolve(manager)==null || sound==SoundManager.EMPTY_SOUND)
            return CompletableFuture.failedFuture(new IOException("UNKNOWN_NATIVE_SOUND"));
        return buffers.getStream(sound.getPath(),false).thenCompose(opened->stream.prepare(new MonoWrapper(opened),target));
    }
    @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary loader,Sound sound,boolean looping) {
        try { return CompletableFuture.completedFuture(stream.take()); }
        catch(IOException failure) { return CompletableFuture.failedFuture(failure); }
    }
    void cancel() { stream.cancel(); }
}
