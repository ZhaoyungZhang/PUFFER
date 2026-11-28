package sqlancer.common.perfhint;

/** Immutable boundaries used to classify a measured pair into one A-I cell. */
public final class PerformanceNineCellOptions {

    private final boolean enabled;
    private final double costRatioLowerBound;
    private final double costRatioUpperBound;
    private final double runtimeRatioLowerBound;
    private final double runtimeRatioUpperBound;
    private final double minRuntimeMedianMs;
    private final double minRuntimeAbsGapMs;

    public PerformanceNineCellOptions(boolean enabled, double costRatioLowerBound, double costRatioUpperBound,
            double runtimeRatioLowerBound, double runtimeRatioUpperBound, double minRuntimeMedianMs,
            double minRuntimeAbsGapMs) {
        this.enabled = enabled;
        this.costRatioLowerBound = costRatioLowerBound;
        this.costRatioUpperBound = costRatioUpperBound;
        this.runtimeRatioLowerBound = runtimeRatioLowerBound;
        this.runtimeRatioUpperBound = runtimeRatioUpperBound;
        this.minRuntimeMedianMs = minRuntimeMedianMs;
        this.minRuntimeAbsGapMs = minRuntimeAbsGapMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getCostRatioLowerBound() {
        return costRatioLowerBound;
    }

    public double getCostRatioUpperBound() {
        return costRatioUpperBound;
    }

    public double getRuntimeRatioLowerBound() {
        return runtimeRatioLowerBound;
    }

    public double getRuntimeRatioUpperBound() {
        return runtimeRatioUpperBound;
    }

    public double getMinRuntimeMedianMs() {
        return minRuntimeMedianMs;
    }

    public double getMinRuntimeAbsGapMs() {
        return minRuntimeAbsGapMs;
    }
}
