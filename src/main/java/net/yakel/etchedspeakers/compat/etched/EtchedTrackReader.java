package net.yakel.etchedspeakers.compat.etched;

import net.yakel.etchedspeakers.source.model.TrackReference;
import gg.moonflower.etched.api.record.PlayableRecord;
import gg.moonflower.etched.api.record.TrackData;
import gg.moonflower.etched.core.registry.EtchedComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;

final class EtchedTrackReader {
    private EtchedTrackReader() {}

    static boolean hasEtchedMusic(ItemStack stack) {
        return stack.has(EtchedComponents.MUSIC) || stack.has(EtchedComponents.ALBUM_COVER);
    }

    static List<TrackReference> read(HolderLookup.Provider registries, ItemStack stack, int slot) {
        List<TrackData> tracks = PlayableRecord.getTracks(registries, stack);
        List<TrackReference> result = new ArrayList<>();
        var vanillaSong = JukeboxSong.fromStack(registries, stack);
        for (int index = 0; index < tracks.size(); index++) {
            TrackData track = tracks.get(index);
            if (!track.isValid()) {
                continue; // Preserve the original index, even if invalid entries were skipped.
            }
            Optional<String> songId = index == 0
                    ? vanillaSong.flatMap(holder -> holder.unwrapKey().map(key -> key.location().toString()))
                    : Optional.empty();
            result.add(new TrackReference(TrackData.isLocalSound(track.url())
                    ? TrackReference.Kind.SOUND_EVENT : TrackReference.Kind.URL, track.url(), songId, slot, index));
        }
        return List.copyOf(result);
    }
}
