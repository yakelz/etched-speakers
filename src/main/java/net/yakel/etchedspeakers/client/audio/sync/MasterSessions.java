package net.yakel.etchedspeakers.client.audio.sync;

import net.yakel.etchedspeakers.EtchedSpeakers;
import net.yakel.etchedspeakers.source.model.SpeakerSelection;
import gg.moonflower.etched.api.sound.WrappedSoundInstance;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.GlobalPos;

/** Registry only of attached original streams; decoder identity is never inferred from a URL. */
public final class MasterSessions {
    private static final ConcurrentHashMap<SoundInstance, MasterPlaybackSession> SESSIONS = new ConcurrentHashMap<>();

    private MasterSessions() {}

    public static SoundInstance unwrap(SoundInstance sound) {
        for (int depth = 0; depth < 8 && sound instanceof WrappedSoundInstance wrapped; depth++) {
            var parent = wrapped.getParent();
            if (parent == sound) break;
            sound = parent;
        }
        return sound;
    }

    public static MasterPlaybackSession find(SoundInstance original) {
        return SESSIONS.get(unwrap(original));
    }
    public static java.util.List<MasterPlaybackSession> all() { return java.util.List.copyOf(SESSIONS.values()); }
    public static MasterPlaybackSession local(GlobalPos source) {
        return SESSIONS.values().stream().filter(s -> !s.isRemote() && !s.isClosed() && s.sourceKey().equals(source))
                .max(java.util.Comparator.comparingLong(s -> s.diagnostic().id)).orElse(null);
    }

    static void register(SoundInstance owner, MasterPlaybackSession session) {
        SESSIONS.put(owner, session);
        logCounts();
    }

    static void remove(SoundInstance owner, MasterPlaybackSession session) {
        SESSIONS.remove(owner, session);
        logCounts();
    }

    /** Sound thread only. Remove across ALL masters before adding so the native limit never overshoots. */
    public static void applyDesired(Map<GlobalPos, SpeakerRequest> desired,
            Map<GlobalPos, String> reasons, String defaultReason) {
        if (desired.size() > SpeakerSelection.MAX_ACTIVE_SPEAKERS) throw new IllegalArgumentException("Too many speaker requests");
        var groups = new LinkedHashMap<MasterPlaybackSession, Map<GlobalPos, SpeakerRequest>>();
        desired.forEach((key, request) -> {
            if (!request.master().isClosed() && SESSIONS.containsValue(request.master())) {
                groups.computeIfAbsent(request.master(), ignored -> new LinkedHashMap<>()).put(key, request);
            }
        });
        for (var session : SESSIONS.values()) {
            session.retainSpeakers(groups.getOrDefault(session, Map.of()).keySet(), reasons, defaultReason);
        }
        groups.forEach(MasterPlaybackSession::setDesired);
    }

    static void logCounts() {
        int outputs = SESSIONS.values().stream().mapToInt(MasterPlaybackSession::outputCount).sum();
        EtchedSpeakers.LOGGER.debug("[EtchedSpeakers] Audio resources masters={} activeSpeakerOutputs={} decoders={} pcmWindows={}",
                SESSIONS.size(), outputs, SESSIONS.size(), SESSIONS.size());
    }

    /** Called synchronously on the sound executor before SoundEngine flush/context destruction. */
    public static void clearOutputs(String reason) {
        for (var session : SESSIONS.values()) session.detach(reason);
    }
}
