package net.yakel.etchedspeakers.client.audio.sync;

import net.yakel.etchedspeakers.source.model.TrackReference;
import net.minecraft.core.GlobalPos;

/** Immutable client-to-sound-thread snapshot entry. Contains no BlockEntity or mutable world state. */
public record SpeakerRequest(GlobalPos speaker, GlobalPos source, MasterPlaybackSession master,
        TrackReference track, float gain, float audibleRange) {
    public static String describe(GlobalPos pos) {
        return pos.dimension().location() + "/" + pos.pos().getX() + "/" + pos.pos().getY() + "/" + pos.pos().getZ();
    }
}
