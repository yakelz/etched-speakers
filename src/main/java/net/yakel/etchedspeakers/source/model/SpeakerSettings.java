package net.yakel.etchedspeakers.source.model;

import java.util.Map;

/** Persistent values shared by server validation, client selection and audio snapshots. */
public record SpeakerSettings(float volume, float audibleRange) {
    public static final float DEFAULT_VOLUME = 1.0F;
    public static final float DEFAULT_RANGE = 32.0F;
    public static final float MIN_VOLUME = 0.0F;
    public static final float MAX_VOLUME = 2.0F;
    public static final float MIN_RANGE = 8.0F;
    public static final float MAX_RANGE = 128.0F;
    public static final String VOLUME_TAG = "Volume";
    public static final String RANGE_TAG = "AudibleRange";
    public static final SpeakerSettings DEFAULT = new SpeakerSettings(DEFAULT_VOLUME, DEFAULT_RANGE);

    public SpeakerSettings {
        volume = clamp(volume, MIN_VOLUME, MAX_VOLUME, DEFAULT_VOLUME);
        audibleRange = clamp(audibleRange, MIN_RANGE, MAX_RANGE, DEFAULT_RANGE);
    }

    private static float clamp(float value, float min, float max, float fallback) {
        return Float.isNaN(value) ? fallback : Math.max(min, Math.min(max, value));
    }

    public Map<String, Float> toPersisted() { return Map.of(VOLUME_TAG, volume, RANGE_TAG, audibleRange); }

    public static SpeakerSettings fromPersisted(Map<String, Float> fields) {
        return new SpeakerSettings(fields.getOrDefault(VOLUME_TAG, DEFAULT_VOLUME), fields.getOrDefault(RANGE_TAG, DEFAULT_RANGE));
    }
}
