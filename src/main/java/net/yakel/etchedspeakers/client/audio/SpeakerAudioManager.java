package net.yakel.etchedspeakers.client.audio;

import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.yakel.etchedspeakers.client.audio.sync.AudioThreadBridge;
import net.yakel.etchedspeakers.client.audio.sync.MasterSessions;
import net.yakel.etchedspeakers.client.audio.sync.SpeakerRequest;
import net.yakel.etchedspeakers.client.source.ClientSourcePlaybackState;
import net.yakel.etchedspeakers.source.model.SpeakerSelection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.GlobalPos;
import net.minecraft.sounds.SoundSource;

/** Client-thread discovery and immutable desired snapshots; native ownership stays on the sound thread. */
public final class SpeakerAudioManager {
    private static final int SEARCH_INTERVAL = 20;
    private static final Comparator<GlobalPos> KEY_ORDER = Comparator
            .comparing((GlobalPos key) -> key.dimension().location().toString())
            .thenComparingLong(key -> key.pos().asLong());
    private ClientLevel level;
    private int untilSearch;
    private List<GlobalPos> selected = List.of();
    private Map<GlobalPos, SpeakerRequest> previous = Map.of();

    public void tick(Minecraft client) {
        if (level != client.level) {
            reset(client, "LEVEL_CHANGED");
            level = client.level;
        }
        if (level == null || client.player == null || client.isPaused()) return;
        var sources = new HashMap<GlobalPos, ClientSourcePlaybackState>();
        var reasons = new HashMap<GlobalPos, String>();
        if (untilSearch-- <= 0) {
            untilSearch = SEARCH_INTERVAL - 1;
            selected = discover(client, sources);
        }
        var desired = new LinkedHashMap<GlobalPos, SpeakerRequest>();
        for (var key : selected) {
            var request = readRequest(client, key, sources, reasons);
            if (request != null) desired.put(key, request);
        }
        if (desired.equals(previous)) return;
        for (var key : previous.keySet()) {
            if (!desired.containsKey(key) && !reasons.containsKey(key)) {
                readRequest(client, key, sources, reasons);
                reasons.putIfAbsent(key, "SAFETY_LIMIT");
            }
        }
        var snapshot = Map.copyOf(desired);
        var detachReasons = Map.copyOf(reasons);
        previous = snapshot;
        AudioThreadBridge.execute(() -> MasterSessions.applyDesired(snapshot, detachReasons, "SOURCE_TRACK_CHANGED"));
    }

    /** At most 21 x 21 loaded chunks once per second; only BE collections, never a block/world scan. */
    private List<GlobalPos> discover(Minecraft client, Map<GlobalPos, ClientSourcePlaybackState> sources) {
        var candidates = new ArrayList<SpeakerSelection.Candidate<GlobalPos>>();
        int radius = SpeakerSelection.DISCOVERY_RANGE;
        int playerX = client.player.blockPosition().getX();
        int playerZ = client.player.blockPosition().getZ();
        var ignoredReasons = new HashMap<GlobalPos, String>();
        for (int x = (playerX - radius) >> 4; x <= (playerX + radius) >> 4; x++) {
            for (int z = (playerZ - radius) >> 4; z <= (playerZ + radius) >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var entity : chunk.getBlockEntities().values()) {
                    if (!(entity instanceof SpeakerBlockEntity speaker) || speaker.isRemoved() || !speaker.isLinked()) continue;
                    var key = GlobalPos.of(level.dimension(), speaker.getBlockPos().immutable());
                    double distance = distanceSquared(client, key);
                    if (distance >= radius * radius) continue;
                    if (readRequest(client, key, sources, ignoredReasons) != null) {
                        candidates.add(new SpeakerSelection.Candidate<>(key, distance, speaker.getAudibleRange()));
                    }
                }
            }
        }
        return SpeakerSelection.select(candidates, previous.keySet(), KEY_ORDER);
    }

    private SpeakerRequest readRequest(Minecraft client, GlobalPos key,
            Map<GlobalPos, ClientSourcePlaybackState> sources, Map<GlobalPos, String> reasons) {
        if (!key.dimension().equals(level.dimension())) return unavailable(key, reasons, "DIMENSION_CHANGED");
        var pos = key.pos();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return unavailable(key, reasons, "SPEAKER_CHUNK_UNLOADED");
        if (!(chunk.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) || speaker.isRemoved()) {
            return unavailable(key, reasons, "SPEAKER_REMOVED");
        }
        if (!speaker.isLinked()) return unavailable(key, reasons, "UNLINKED");
        if (!SpeakerSelection.inRange(distanceSquared(client, key), speaker.getAudibleRange(), true)) {
            return unavailable(key, reasons, "OUT_OF_RANGE");
        }
        if (!SpeakerSelection.inRange(distanceSquared(client, key), speaker.getAudibleRange(), previous.containsKey(key))) {
            return unavailable(key, reasons, "OUT_OF_ACTIVATION_RANGE");
        }
        var source = GlobalPos.of(speaker.getSourceDimension().orElseThrow(), speaker.getSourcePos().orElseThrow());
        var state = sources.computeIfAbsent(source, ignored -> EtchedAudioBridge.read(client, speaker));
        if (state.currentTrack().isEmpty()) return unavailable(key, reasons, state.reason());
        var master = MasterSessions.find(state.sourceSound().orElseThrow());
        if (master == null || master.isClosed()) return unavailable(key, reasons, "MASTER_PCM_UNAVAILABLE");
        return new SpeakerRequest(key, source, master, state.currentTrack().orElseThrow(),
                client.options.getSoundSourceVolume(SoundSource.RECORDS) * speaker.getVolume(), speaker.getAudibleRange());
    }

    private static SpeakerRequest unavailable(GlobalPos key, Map<GlobalPos, String> reasons, String reason) {
        reasons.put(key, reason);
        return null;
    }

    private static double distanceSquared(Minecraft client, GlobalPos key) {
        var pos = key.pos();
        return client.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    public void reset(Minecraft client, String reason) {
        AudioThreadBridge.execute(() -> MasterSessions.applyDesired(Map.of(), Map.of(), reason));
        previous = Map.of();
        selected = List.of();
        untilSearch = 0;
        level = null;
    }
}
