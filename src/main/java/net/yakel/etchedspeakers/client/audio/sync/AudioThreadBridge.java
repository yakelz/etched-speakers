package net.yakel.etchedspeakers.client.audio.sync;

import net.yakel.etchedspeakers.client.mixin.SoundEngineAccessor;
import net.yakel.etchedspeakers.client.mixin.SoundManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngineExecutor;

/** No OpenAL call is dispatched directly from client ticks or asynchronous download callbacks. */
public final class AudioThreadBridge {
    private AudioThreadBridge() {}

    private static SoundEngineExecutor executor() {
        var engine = ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).etchedspeakers$getEngine();
        return ((SoundEngineAccessor) engine).etchedspeakers$getExecutor();
    }

    public static void execute(Runnable operation) {
        executor().execute(operation);
    }

    public static boolean isSameThread() { return executor().isSameThread(); }

    public static void sync(Runnable operation) {
        var executor = executor();
        if (executor.isSameThread()) operation.run();
        else executor.submit(operation).join();
    }
}
