package net.yakel.etchedspeakers.source.model;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** Pure selection policy; callers supply only loaded speakers with an available master. */
public final class SpeakerSelection {
    public static final int ACTIVATE_MARGIN = 24;
    public static final int DEACTIVATE_MARGIN = 32;
    public static final int DISCOVERY_RANGE = (int) SpeakerSettings.MAX_RANGE + DEACTIVATE_MARGIN;
    public static final int MAX_ACTIVE_SPEAKERS = 32;

    private SpeakerSelection() {}

    public record Candidate<K>(K key, double distanceSquared, float audibleRange) {
        public Candidate(K key, double distanceSquared) { this(key, distanceSquared, SpeakerSettings.DEFAULT_RANGE); }
    }

    public static boolean inRange(double distanceSquared, float audibleRange, boolean current) {
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0) return false;
        float range = new SpeakerSettings(1, audibleRange).audibleRange();
        double threshold = range + (current ? DEACTIVATE_MARGIN : ACTIVATE_MARGIN);
        return current ? distanceSquared < threshold * threshold : distanceSquared <= threshold * threshold;
    }

    public static <K> List<K> select(Collection<Candidate<K>> candidates, Set<K> current, Comparator<K> tieOrder) {
        var distances = new HashMap<K, Double>();
        for (var candidate : candidates) {
            double distance = candidate.distanceSquared();
            if (inRange(distance, candidate.audibleRange(), current.contains(candidate.key()))) {
                distances.merge(candidate.key(), distance, Math::min);
            }
        }
        return distances.keySet().stream()
                .sorted(Comparator.<K>comparingDouble(distances::get).thenComparing(tieOrder))
                .limit(MAX_ACTIVE_SPEAKERS).toList();
    }
}
