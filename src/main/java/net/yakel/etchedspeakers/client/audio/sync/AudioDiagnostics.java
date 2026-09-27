package net.yakel.etchedspeakers.client.audio.sync;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.client.mixin.SoundEngineAccessor;
import net.yakel.etchedspeakers.client.mixin.SoundManagerAccessor;
import net.yakel.etchedspeakers.compat.AudioSourceResolver;
import gg.moonflower.etched.core.mixin.client.render.LevelRendererAccessor;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.phys.Vec3;

/** Support diagnostics: DEBUG by default, opt-in INFO with audioDiagnostics. Never controls playback or loads chunks. */
public final class AudioDiagnostics {
    private static final AtomicLong IDS = new AtomicLong();
    private static final AtomicLong EVENTS = new AtomicLong();
    private static final ConcurrentHashMap<Long, Trace> LIVE = new ConcurrentHashMap<>();
    // Client-thread only, bounded even during a long exploratory reproduction.
    private static final Map<GlobalPos, Snapshot> WATCHED = new LinkedHashMap<>();
    private static volatile Map<GlobalPos, Snapshot> snapshots = Map.of();
    private static String lastCounts = "";

    private AudioDiagnostics() {}

    public static String identity(Object value) {
        return value == null ? "none" : value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    /** No exception messages/arguments/toString(): these can contain remote URLs. */
    public static String caller() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !f.getClassName().equals(AudioDiagnostics.class.getName()))
                .limit(14).map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
                .collect(Collectors.joining("<-")));
    }

    public static void event(String event, String details) {
        if (Boolean.getBoolean("etchedspeakers.audioDiagnostics"))
            EtchedSpeakers.LOGGER.info("[ES-AUDIO-DIAG] {} seq={} {}", event, EVENTS.incrementAndGet(), details);
        else EtchedSpeakers.LOGGER.debug("[ES-AUDIO-DIAG] {} seq={} {}", event, EVENTS.incrementAndGet(), details);
    }

    /** Called on the client thread, also immediately at an observed SoundEngine.stop call. */
    public static void observe(GlobalPos source) {
        var client = Minecraft.getInstance();
        if (!client.isSameThread()) return;
        var level = client.level;
        var pos = source.pos();
        boolean sameLevel = level != null && level.dimension().equals(source.dimension());
        var chunk = sameLevel ? level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) : null;
        boolean be = chunk != null && chunk.getBlockEntity(pos) != null;
        boolean resolvable = chunk != null && AudioSourceResolver.getSourceType(level, pos).isPresent();
        SoundInstance original = sameLevel
                ? ((LevelRendererAccessor) client.levelRenderer).getPlayingJukeboxSongs().get(pos) : null;
        var engine = ((SoundManagerAccessor) client.getSoundManager()).etchedspeakers$getEngine();
        var channels = ((SoundEngineAccessor) engine).etchedspeakers$getChannels();
        boolean active = original != null && client.getSoundManager().isActive(original);
        boolean channel = original != null && channels.containsKey(original);
        var next = new Snapshot(level == null ? -1 : level.getGameTime(), System.nanoTime(),
                client.player == null ? null : client.player.position(), chunk != null, be, resolvable,
                identity(original), active, channel);
        var previous = WATCHED.put(source, next);
        if (WATCHED.size() > 512) WATCHED.remove(WATCHED.keySet().iterator().next());
        snapshots = Map.copyOf(WATCHED);
        if (previous == null || previous.loaded != next.loaded || previous.be != next.be || previous.resolvable != next.resolvable) {
            event("SOURCE_CLIENT_STATE", "source=" + SpeakerRequest.describe(source) + " " + next.describe(source)
                    + " serverChunkLoaded=UNKNOWN");
        }
        if (previous == null || previous.active != active || previous.channel != channel || !previous.sound.equals(next.sound)) {
            var trace = original == null ? null : find(original);
            event("ORIGINAL_SOUND_STATE", "source=" + SpeakerRequest.describe(source) + " masterId="
                    + (trace == null ? "none" : trace.id) + " previousSound=" + (previous == null ? "none" : previous.sound)
                    + " soundInstanceIdentity=" + next.sound + " active=" + active + " channelPresent=" + channel
                    + " " + next.describe(source));
        }
        for (var trace : LIVE.values()) {
            if (!trace.source.equals(source)) continue;
            String state = client.getSoundManager().isActive(trace.token) + "/" + channels.containsKey(trace.token);
            if (!state.equals(trace.soundState)) {
                trace.soundState = state;
                trace.log("ORIGINAL_SOUND_STATE", "active=" + client.getSoundManager().isActive(trace.token)
                        + " channelPresent=" + channels.containsKey(trace.token) + " soundInstanceIdentity=" + identity(trace.token));
            }
        }
    }

    public static void tick() {
        for (var source : java.util.List.copyOf(WATCHED.keySet())) observe(source);
    }

    public static void reset(String reason) {
        for (var trace : LIVE.values()) {
            trace.context(reason);
            trace.log("CLIENT_CONTEXT", "reason=" + reason);
        }
        // SoundEngine reload can arrive off the client thread. Never mutate client-owned maps there.
        var client = Minecraft.getInstance();
        if (client.isSameThread()) WATCHED.clear();
        else client.execute(WATCHED::clear);
    }

    public static Trace find(SoundInstance sound) {
        var owner = MasterSessions.unwrap(sound);
        return LIVE.values().stream().filter(t -> t.owner == owner).findFirst().orElse(null);
    }

    public static synchronized void counts() {
        long masters = LIVE.values().stream().filter(t -> !t.masterClosed).count();
        long decoders = LIVE.values().stream().filter(t -> !t.decoderClosed).count();
        int outputs = LIVE.values().stream().mapToInt(t -> t.outputs).sum();
        String next = "masters=" + masters + " decoders=" + decoders + " pcmWindows=" + masters + " speakerOutputs=" + outputs;
        if (!next.equals(lastCounts)) { lastCounts = next; event("RESOURCE_COUNTS", next); }
    }

    public static final class Trace {
        public final long id = IDS.incrementAndGet();
        public final GlobalPos source;
        private final SoundInstance owner;
        private final SoundInstance token;
        private final String track;
        private volatile String soundState = "";
        private volatile String contextReason = "NONE";
        private volatile String stopReason = "NONE";
        private volatile boolean masterClosed;
        private volatile boolean decoderClosed;
        private volatile int outputs;

        public Trace(GlobalPos source, SoundInstance owner, SoundInstance token, Object stream, Object pcm, String track) {
            this.source = source;
            this.owner = owner;
            this.token = token;
            this.track = track;
            observe(source);
            LIVE.put(id, this);
            log("MASTER_CREATE", "soundInstanceIdentity=" + identity(owner) + " wrappedSoundIdentity=" + identity(token)
                    + " streamIdentity=" + identity(stream) + " pcmWindowIdentity=" + identity(pcm) + " frame=0");
            counts();
        }

        public void log(String event, String details) {
            var snapshot = snapshots.get(source);
            AudioDiagnostics.event(event, "masterId=" + id + " source=" + SpeakerRequest.describe(source) + " track=" + track
                    + " " + details + " " + (snapshot == null ? "clientSnapshot=UNKNOWN" : snapshot.describe(source)));
        }

        public void stop(String reason) {
            if (stopReason.equals("NONE")) stopReason = reason;
        }

        private synchronized void context(String reason) {
            // Keep observed unload/reload/disconnect context instead of overwriting it with a generic stopAll.
            if (contextReason.equals("NONE") || !reason.equals("SOUND_ENGINE_STOP_ALL")) contextReason = reason;
        }

        public void speaker(String event, SpeakerRequest request, String reason, long frame) {
            var snapshot = snapshots.get(source);
            log(event, "speaker=" + SpeakerRequest.describe(request.speaker()) + " speakerRange=" + request.audibleRange()
                    + " distanceToSpeaker=" + (snapshot == null ? "UNKNOWN" : snapshot.distance(request.speaker()))
                    + " reason=" + reason + " masterFrame=" + frame);
        }

        public void outputs(int count) { outputs = count; counts(); }

        public void masterClosed(String reason, long baseFrame) {
            masterClosed = true;
            log("MASTER_DESTROY", "reason=" + reason + " precedingStop=" + stopReason + " contextReason=" + contextReason
                    + " baseFrame=" + baseFrame + " caller=" + caller());
            counts();
        }

        public void decoderClosed() {
            decoderClosed = true;
            counts();
            if (masterClosed) LIVE.remove(id, this);
        }
    }

    private record Snapshot(long gameTime, long sampledAt, Vec3 player, boolean loaded, boolean be,
                            boolean resolvable, String sound, boolean active, boolean channel) {
        String distance(GlobalPos key) {
            return player == null ? "UNKNOWN" : String.format(Locale.ROOT, "%.2f", player.distanceTo(Vec3.atCenterOf(key.pos())));
        }

        String describe(GlobalPos source) {
            String position = player == null ? "UNKNOWN" : String.format(Locale.ROOT, "%.2f,%.2f,%.2f", player.x, player.y, player.z);
            return "clientGameTime=" + gameTime + " playerPos=" + position + " distanceToSource=" + distance(source)
                    + " sourceClientChunkLoaded=" + loaded + " blockEntityPresent=" + be + " sourceResolvable=" + resolvable
                    + " snapshotAgeMs=" + ((System.nanoTime() - sampledAt) / 1_000_000);
        }
    }
}
