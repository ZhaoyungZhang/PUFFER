package sqlancer.common.perfhint;

import java.util.List;

public final class CostRuntimeObservation {

    private final double defaultCost;
    private final double hintedCost;
    private final double defaultRuntimeMedianMs;
    private final double hintedRuntimeMedianMs;
    private final double defaultRuntimeMeanMs;
    private final double hintedRuntimeMeanMs;
    private final double defaultRuntimeMinMs;
    private final double hintedRuntimeMinMs;
    private final double defaultRuntimeMaxMs;
    private final double hintedRuntimeMaxMs;
    private final int defaultTimeoutCount;
    private final int hintedTimeoutCount;
    private final List<Double> defaultSamplesMs;
    private final List<Double> hintedSamplesMs;

    public CostRuntimeObservation(double defaultCost, double hintedCost, double defaultRuntimeMedianMs,
            double hintedRuntimeMedianMs, double defaultRuntimeMeanMs, double hintedRuntimeMeanMs,
            double defaultRuntimeMinMs, double hintedRuntimeMinMs, double defaultRuntimeMaxMs,
            double hintedRuntimeMaxMs, int defaultTimeoutCount, int hintedTimeoutCount, List<Double> defaultSamplesMs,
            List<Double> hintedSamplesMs) {
        this.defaultCost = defaultCost;
        this.hintedCost = hintedCost;
        this.defaultRuntimeMedianMs = defaultRuntimeMedianMs;
        this.hintedRuntimeMedianMs = hintedRuntimeMedianMs;
        this.defaultRuntimeMeanMs = defaultRuntimeMeanMs;
        this.hintedRuntimeMeanMs = hintedRuntimeMeanMs;
        this.defaultRuntimeMinMs = defaultRuntimeMinMs;
        this.hintedRuntimeMinMs = hintedRuntimeMinMs;
        this.defaultRuntimeMaxMs = defaultRuntimeMaxMs;
        this.hintedRuntimeMaxMs = hintedRuntimeMaxMs;
        this.defaultTimeoutCount = defaultTimeoutCount;
        this.hintedTimeoutCount = hintedTimeoutCount;
        this.defaultSamplesMs = List.copyOf(defaultSamplesMs);
        this.hintedSamplesMs = List.copyOf(hintedSamplesMs);
    }

    public double getDefaultCost() {
        return defaultCost;
    }

    public double getHintedCost() {
        return hintedCost;
    }

    public double getDefaultRuntimeMedianMs() {
        return defaultRuntimeMedianMs;
    }

    public double getHintedRuntimeMedianMs() {
        return hintedRuntimeMedianMs;
    }

    public double getDefaultRuntimeMeanMs() {
        return defaultRuntimeMeanMs;
    }

    public double getHintedRuntimeMeanMs() {
        return hintedRuntimeMeanMs;
    }

    public double getDefaultRuntimeMinMs() {
        return defaultRuntimeMinMs;
    }

    public double getHintedRuntimeMinMs() {
        return hintedRuntimeMinMs;
    }

    public double getDefaultRuntimeMaxMs() {
        return defaultRuntimeMaxMs;
    }

    public double getHintedRuntimeMaxMs() {
        return hintedRuntimeMaxMs;
    }

    public int getDefaultTimeoutCount() {
        return defaultTimeoutCount;
    }

    public int getHintedTimeoutCount() {
        return hintedTimeoutCount;
    }

    public List<Double> getDefaultSamplesMs() {
        return defaultSamplesMs;
    }

    public List<Double> getHintedSamplesMs() {
        return hintedSamplesMs;
    }
}
