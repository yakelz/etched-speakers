package net.yakel.etchedspeakers.client.audio.remote;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.yakel.etchedspeakers.EtchedSpeakers;

/** Isolated startup/real-resource smoke check. No world, channel playback, or audible PASS. */
@EventBusSubscriber(modid=EtchedSpeakers.MOD_ID,value=Dist.CLIENT)
public final class OriginalRecoveryClientChecks {
    private static boolean started;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var client=Minecraft.getInstance();
        if(!Boolean.getBoolean("etchedspeakers.originalClientValidation") || started || !(client.screen instanceof TitleScreen)) return;
        started=true;
        try {
            Class.forName("gg.moonflower.etched.api.sound.SoundTracker");
            Class.forName("gg.moonflower.etched.common.network.play.handler.EtchedClientPlayPacketHandler");
            var sound=new NativeOriginalSound(GlobalPos.of(Level.OVERWORLD,new BlockPos(10,64,10)),"minecraft:music_disc.creator");
            if(sound.getSource()!=SoundSource.RECORDS || sound.isRelative() || sound.getX()!=10.5)
                throw new AssertionError("Original positional semantics");
            sound.prepare(client.getSoundManager(),OriginalAudio.engine().etchedspeakers$getBuffers(),rate->2L*rate)
                    .whenComplete((unused,error)->client.execute(()->{
                        try {
                            if(error!=null) throw new AssertionError("Actual Creator resource pre-roll",error);
                            if(sound.stream.rate()!=48000 || sound.stream.frame()!=96000 || !sound.getSound().shouldStream() || sound.getVolume()!=4)
                                throw new AssertionError("Actual Creator 48k / two-second target / streaming resolution");
                            EtchedSpeakers.LOGGER.info("[ES-ORIGINAL-CLIENT-TEST] PASS client mixins loaded; actual Creator targetFrame=96000 sampleRate=48000; no audible playback");
                        } catch(Throwable failure) { EtchedSpeakers.LOGGER.error("[ES-ORIGINAL-CLIENT-TEST] FAIL",failure); }
                        finally { sound.cancel(); client.stop(); }
                    }));
        } catch(Throwable failure) { EtchedSpeakers.LOGGER.error("[ES-ORIGINAL-CLIENT-TEST] FAIL",failure); client.stop(); }
    }
}
