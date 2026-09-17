package net.yakel.etchedspeakers.tests;

import net.yakel.etchedspeakers.source.model.OutputRegistry;
import net.yakel.etchedspeakers.source.model.PcmWindow;
import net.yakel.etchedspeakers.source.model.SpeakerSelection;
import net.yakel.etchedspeakers.source.model.SpeakerSelection.Candidate;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Selection/ownership checks exercise the production model without pretending to test OpenAL. */
public final class MultiSpeakerChecks {
    private static int checks;

    public static void run() {
        selection();
        ownership();
        System.out.println("Multi speaker checks passed: " + checks);
    }

    private static List<String> select(List<Candidate<String>> candidates, String... active) {
        return SpeakerSelection.select(candidates, Set.of(active), Comparator.naturalOrder());
    }

    private static Candidate<String> at(String key, double distance) { return new Candidate<>(key, distance * distance); }

    private static void selection() {
        check(select(List.of(at("A", 20), at("B", 40), at("C", 55))).equals(List.of("A", "B", "C")), "All in-range speakers selected");
        check(select(List.of(at("A", 56))).equals(List.of("A")), "Activate at 56 inclusive");
        check(select(List.of(at("A", 56.01))).isEmpty(), "New output beyond activation range excluded");
        check(select(List.of(at("A", 60)), "A").equals(List.of("A")), "Existing output retained in hysteresis band");
        check(select(List.of(at("A", 63.99)), "A").equals(List.of("A")), "Existing output retained just below 64");
        check(select(List.of(at("A", 64)), "A").isEmpty(), "Deactivate at 64 inclusive");
        check(select(List.of(at("A", 60))).isEmpty(), "Detached output cannot reactivate in hysteresis band");
        check(select(List.of(at("A", 55.9)), "A").equals(select(List.of(at("A", 56.1)), "A")), "Small motion near activation boundary does not toggle");
        check(select(List.of(at("A", 10), at(new String("A"), 9), at("B", 11))).equals(List.of("A", "B")), "Duplicate/recreated keys cannot consume two slots");
        check(select(List.of(at("B", 10), at("A", 10))).equals(List.of("A", "B")), "Distance ties have stable key ordering");
        check(select(List.of(at("C", 12), at("B", 4), at("A", 9))).equals(List.of("B", "A", "C")), "Distance priority");
        check(select(List.of(new Candidate<>("bad", Double.NaN), new Candidate<>("negative", -1.0), at("far", 5000))).isEmpty(), "Invalid/far candidates rejected");
        var many = new ArrayList<Candidate<String>>();
        for (int i = 40; i >= 1; i--) many.add(at(String.format("S%02d", i), i));
        var limited = select(many);
        check(limited.size() == 32 && limited.getFirst().equals("S01") && limited.getLast().equals("S32"), "Global safety limit selects nearest 32");
        check(many.size() == 40, "Selection does not delete world candidates/links");
        java.util.Collections.reverse(many);
        check(select(many).equals(limited), "Discovery ordering cannot change selected keys");
        var moved = new ArrayList<>(many);
        moved.removeIf(c -> c.key().equals("S40"));
        moved.add(at("S40", 0));
        var afterMotion = select(moved, limited.toArray(String[]::new));
        check(afterMotion.size() == 32 && afterMotion.contains("S40") && !afterMotion.contains("S32"), "Movement only replaces lowest priority at safety limit");
    }

    private static void ownership() {
        var pcm = new PcmWindow(8, 1);
        pcm.append(ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5, 6}), 1);
        var outputs = new OutputRegistry<String, ModelOutput>();
        var closed = new ArrayList<String>();
        java.util.function.BiConsumer<String, ModelOutput> close = (key, output) -> {
            check(!output.closed, "Output closed exactly once");
            output.closed = true;
            closed.add(key);
        };
        outputs.reconcile(List.of("A", "B", "C", "A"), key -> new ModelOutput(pcm), close);
        var a = outputs.get("A");
        var b = outputs.get("B");
        var c = outputs.get("C");
        check(outputs.size() == 3, "Multiple outputs attach with duplicate key deduplicated");
        check(a.pcm == b.pcm && b.pcm == c.pcm && a.pcm == pcm, "All outputs use one shared PCM window");
        outputs.reconcile(List.of("C", "A", "B"), key -> { throw new AssertionError("Unexpected recreation"); }, close);
        check(outputs.get("A") == a && outputs.get("B") == b && closed.isEmpty(), "Reordered desired set preserves current outputs");
        outputs.reconcile(List.of("A", "C"), key -> new ModelOutput(pcm), close);
        check(b.closed && !a.closed && !c.closed && outputs.size() == 2, "Unlink/break one output leaves siblings alive");
        check(pcm.endFrame() == 6, "Detach does not clear master PCM");
        outputs.reconcile(List.of("A", "C", new String("B")), key -> new ModelOutput(pcm), close);
        check(outputs.get("B") != b && outputs.get("B").pcm == pcm, "Relink/recreated key uses same current master window");
        var late = ByteBuffer.allocate(1);
        check(outputs.get("B").pcm.copy(5, 1, late) == 1 && late.get(0) == 6, "Late output can read current shared frame");
        outputs.clear(close);
        check(outputs.size() == 0 && a.closed && c.closed, "Session close removes all outputs");
        int closes = closed.size();
        outputs.clear(close);
        check(closed.size() == closes, "Repeated cleanup is idempotent");

        var nextPcm = new PcmWindow(8, 1);
        var next = new OutputRegistry<String, ModelOutput>();
        next.reconcile(List.of("A", "B"), key -> new ModelOutput(nextPcm), close);
        check(a.closed && next.get("A").pcm == nextPcm && next.get("B").pcm != pcm, "Track change closes old group and uses new timeline");
        var other = new OutputRegistry<String, ModelOutput>();
        other.reconcile(List.of("X"), key -> new ModelOutput(pcm), close);
        next.clear(close);
        check(!other.get("X").closed && other.get("X").pcm == pcm, "Closing source A does not affect source B");
        // A key moving between masters is removed globally before adding it to the new master.
        other.retain(Set.of(), close);
        next.reconcile(List.of("X"), key -> new ModelOutput(nextPcm), close);
        check(other.size() == 0 && next.size() == 1 && next.get("X").pcm == nextPcm, "Source relink transfers ownership without duplicate output");
        next.clear(close);
    }

    private static final class ModelOutput {
        final PcmWindow pcm;
        boolean closed;
        ModelOutput(PcmWindow pcm) { this.pcm = pcm; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
