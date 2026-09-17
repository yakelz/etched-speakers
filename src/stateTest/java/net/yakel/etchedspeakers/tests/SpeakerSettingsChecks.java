package net.yakel.etchedspeakers.tests;

import net.yakel.etchedspeakers.source.model.SpeakerSelection;
import net.yakel.etchedspeakers.source.model.SpeakerSettings;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpeakerSettingsChecks {
    private static int checks;

    public static void run() {
        check(SpeakerSettings.DEFAULT.equals(new SpeakerSettings(1, 32)), "Defaults preserved for existing speakers");
        check(SpeakerSettings.fromPersisted(Map.of()).equals(SpeakerSettings.DEFAULT), "Old NBT without settings gets defaults");
        check(new SpeakerSettings(-1, 32).volume() == 0, "Negative volume clamped");
        check(new SpeakerSettings(9, 32).volume() == 2, "Excess volume clamped");
        check(new SpeakerSettings(0, 32).volume() == 0, "Mute is valid");
        check(new SpeakerSettings(2, 32).volume() == 2, "200 percent is valid");
        check(new SpeakerSettings(1, -10).audibleRange() == 8, "Small range clamped");
        check(new SpeakerSettings(1, 10000).audibleRange() == 128, "Large range clamped");
        check(new SpeakerSettings(Float.NaN, Float.NaN).equals(SpeakerSettings.DEFAULT), "NaN cannot escape validation");
        check(new SpeakerSettings(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY).equals(new SpeakerSettings(2, 128)), "Positive infinities clamped");
        check(new SpeakerSettings(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY).equals(new SpeakerSettings(0, 8)), "Negative infinities clamped");
        var a = new SpeakerSettings(0.25F, 16);
        var b = new SpeakerSettings(1.5F, 64);
        check(a.toPersisted().equals(Map.of("Volume", 0.25F, "AudibleRange", 16F)), "Persistent field names and floats");
        check(SpeakerSettings.fromPersisted(a.toPersisted()).equals(a), "Persistence round trip");
        check(SpeakerSettings.fromPersisted(Map.of("Volume", 0.5F)).equals(new SpeakerSettings(0.5F, 32)), "Partially missing fields default independently");
        check(SpeakerSettings.fromPersisted(Map.of("Volume", -3F, "AudibleRange", 400F)).equals(new SpeakerSettings(0, 128)), "Persistence is validated too");
        var changedA = new SpeakerSettings(0, 8);
        check(b.equals(new SpeakerSettings(1.5F, 64)) && a.volume() == 0.25F && changedA.volume() == 0, "Immutable settings isolate speakers");
        check(SpeakerSelection.DISCOVERY_RANGE == 160, "Bounded search covers maximum deactivation distance");
        check(SpeakerSelection.inRange(152 * 152, 128, false), "Max range speaker discovered beyond old radius");
        check(!SpeakerSelection.inRange(153 * 153, 128, false), "Max range activation bound");
        check(SpeakerSelection.inRange(159 * 159, 128, true), "Max range hysteresis retained");
        check(!SpeakerSelection.inRange(160 * 160, 128, true), "Max range deactivation bound");
        check(SpeakerSelection.inRange(32 * 32, 8, false), "Small range activation at 8 +24");
        check(!SpeakerSelection.inRange(36 * 36, 8, false) && SpeakerSelection.inRange(36 * 36, 8, true), "Small range hysteresis");
        check(!SpeakerSelection.inRange(40 * 40, 8, true), "Small range deactivation at 8 +32");
        var candidates = List.of(new SpeakerSelection.Candidate<>("small", 10000, 8),
                new SpeakerSelection.Candidate<>("large", 10000, 128));
        check(SpeakerSelection.select(candidates, Set.of(), Comparator.naturalOrder()).equals(List.of("large")), "Selection uses each speaker's own range");
        check(SpeakerSelection.inRange(90 * 90, 64, true) && !SpeakerSelection.inRange(90 * 90, 8, true), "Range reduction deactivates only now irrelevant output");
        System.out.println("Speaker settings checks passed: " + checks);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
