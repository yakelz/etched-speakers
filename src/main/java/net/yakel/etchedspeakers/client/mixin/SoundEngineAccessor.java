package net.yakel.etchedspeakers.client.mixin;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.resources.sounds.SoundInstance;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("soundBuffers")
    net.minecraft.client.sounds.SoundBufferLibrary etchedspeakers$getBuffers();
    @Accessor("executor")
    SoundEngineExecutor etchedspeakers$getExecutor();

    @Accessor("instanceToChannel")
    Map<SoundInstance, ChannelAccess.ChannelHandle> etchedspeakers$getChannels();
}
