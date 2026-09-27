package net.yakel.etchedspeakers.client.audio;

import net.yakel.etchedspeakers.blockentity.SpeakerBlockEntity;
import net.yakel.etchedspeakers.client.audio.sync.AudioThreadBridge;
import net.yakel.etchedspeakers.client.audio.sync.AudioDiagnostics;
import net.yakel.etchedspeakers.client.audio.sync.MasterSessions;
import net.yakel.etchedspeakers.client.audio.sync.SpeakerRequest;
import net.yakel.etchedspeakers.client.audio.remote.RemotePlayback;
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
    private final Map<GlobalPos, String> activationDiagnostics = new HashMap<>();

    public void tick(Minecraft client) {
        if (level != client.level) {
            reset(client, "LEVEL_CHANGED");
            level = client.level;
        }
        if (level == null || client.player == null || client.isPaused()) return;
        RemotePlayback.tick(client);
        AudioDiagnostics.tick();
        var sources = new HashMap<GlobalPos, ClientSourcePlaybackState>();
        var reasons = new HashMap<GlobalPos, String>();
        if (untilSearch-- <= 0) {
            untilSearch = SEARCH_INTERVAL - 1;
            selected = discover(client, sources);
            activationDiagnostics.keySet().retainAll(selected);
        }
        var desired = new LinkedHashMap<GlobalPos, SpeakerRequest>();
        for (var key : selected) {
            var request = readRequest(client, key, sources, reasons);
            if (request != null) desired.put(key, request);
        }
        RemotePlayback.endTick(client);
        if (desired.equals(previous)) return;
        for (var key : previous.keySet()) {
            if (!desired.containsKey(key) && !reasons.containsKey(key)) {
                readRequest(client, key, sources, reasons);
                reasons.putIfAbsent(key, "SAFETY_LIMIT");
            }
            var old = previous.get(key);
            var next = desired.get(key);
            if (next == null || old.master() != next.master()) {
                old.master().diagnostic().speaker("SPEAKER_DESIRED_REMOVED", old,
                        reasons.getOrDefault(key, next == null ? "SAFETY_LIMIT" : "SOURCE_TRACK_CHANGED"), -1);
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
                    candidates.add(new SpeakerSelection.Candidate<>(key, distance, speaker.getAudibleRange()));
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
        RemotePlayback.interested(source, pos);
        AudioDiagnostics.observe(source);
        var state = sources.computeIfAbsent(source, ignored -> EtchedAudioBridge.read(client, speaker));
        var local = state.sourceSound().map(MasterSessions::find).orElseGet(()->MasterSessions.local(source));
        var master = RemotePlayback.choose(source, local, state.currentTrack().isPresent());
        if (state.type().orElse(null) == net.yakel.etchedspeakers.source.model.SourceType.VANILLA_JUKEBOX) {
            var original = ((gg.moonflower.etched.core.mixin.client.render.LevelRendererAccessor)
                    client.levelRenderer).getPlayingJukeboxSongs().get(source.pos());
            String evidence = source + ",state:" + state.reason()
                    + ",sound:" + (original == null ? "none" : AudioDiagnostics.identity(original))
                    + ",soundActive:" + (original != null && client.getSoundManager().isActive(original))
                    + ",localMaster:" + (local == null ? "none" : local.diagnostic().id)
                    + ",chosenMaster:" + (master == null ? "none" : master.diagnostic().id)
                    + "," + RemotePlayback.activationDiagnostic(source);
            if (!evidence.equals(activationDiagnostics.put(key, evidence))) {
                net.yakel.etchedspeakers.EtchedSpeakers.LOGGER.debug(
                        "[ES-VANILLA-ACT] CLIENT_ROUTE source={} speaker={} reason={}", source, key.pos(), evidence);
            }
        }
        var track = master!=null && !master.isRemote() && state.currentTrack().isPresent()
                ? state.currentTrack().get() : RemotePlayback.track(source);
        if(track==null) {
            if (state.currentTrack().isEmpty()) return unavailable(key, reasons, state.reason());
            track=state.currentTrack().get();
        }
        if (master == null || master.isClosed()) return unavailable(key, reasons, "MASTER_PCM_UNAVAILABLE");
        return new SpeakerRequest(key, source, master, track,
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
        RemotePlayback.reset(reason);
        AudioDiagnostics.reset(reason);
        AudioThreadBridge.execute(() -> MasterSessions.applyDesired(Map.of(), Map.of(), reason));
        previous = Map.of();
        selected = List.of();
        activationDiagnostics.clear();
        untilSearch = 0;
        level = null;
    }
}
