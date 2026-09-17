package net.yakel.etchedspeakers.source.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Media identity plus its occurrence in the source's playlist. No mutable Etched/Minecraft objects. */
public record TrackReference(Kind kind, String location, Optional<String> songId, int slot, int trackIndex) {
    /** The client sound can expose its URL without exposing its playlist occurrence. */
    public static final int UNKNOWN_INDEX = -1;

    public TrackReference {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(location);
        Objects.requireNonNull(songId);
        if (location.isBlank() || slot < UNKNOWN_INDEX || trackIndex < UNKNOWN_INDEX) {
            throw new IllegalArgumentException("Track requires a location and slot/index >= -1 (unknown)");
        }
    }

    public enum Kind { URL, SOUND_EVENT }

    /** Hash the complete URL (including query); never expose credentials or tokens in diagnostics. */
    public String mediaKey() {
        if (kind == Kind.SOUND_EVENT) {
            return "sound:" + location;
        }
        try {
            return "url:sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(location.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java must provide SHA-256", e);
        }
    }

    public String stableKey() {
        return mediaKey() + songId.map(id -> "|song=" + id).orElse("")
                + "|slot=" + (slot == UNKNOWN_INDEX ? "?" : slot)
                + "|track=" + (trackIndex == UNKNOWN_INDEX ? "?" : trackIndex);
    }

    @Override
    public String toString() {
        return stableKey();
    }
}
