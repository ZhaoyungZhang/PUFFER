package sqlancer.common.perfhint;

public final class CostRuntimeMetrics {

    public enum CostComparisonDirection {
        HINTED_LOWER, HINTED_HIGHER, SIMILAR, UNKNOWN
    }

    public enum RuntimeComparisonDirection {
        HINTED_FASTER, HINTED_SLOWER, SIMILAR, UNKNOWN
    }

    public enum FasterSide {
        DEFAULT, HINTED, TIED, UNKNOWN
    }

    private final CostRuntimeObservation observation;
    private final double costRatio;
    private final double higherCostRatio;
    private final double runtimeRatio;
    private final double runtimeRegressionRatio;
    private final double absRuntimeGapMs;
    private final double symmetricRuntimeRatio;
    private final CostComparisonDirection costComparisonDirection;
    private final RuntimeComparisonDirection runtimeComparisonDirection;
    private final FasterSide fasterSide;

    public CostRuntimeMetrics(CostRuntimeObservation observation) {
        this.observation = observation;
        this.costRatio = computePositiveRatio(observation.getDefaultCost(), observation.getHintedCost());
        this.higherCostRatio = computePositiveRatio(observation.getHintedCost(), observation.getDefaultCost());
        this.runtimeRatio = computePositiveRatio(observation.getDefaultRuntimeMedianMs(),
                observation.getHintedRuntimeMedianMs());
        this.runtimeRegressionRatio = computePositiveRatio(observation.getHintedRuntimeMedianMs(),
                observation.getDefaultRuntimeMedianMs());
        this.absRuntimeGapMs = Math
                .abs(observation.getDefaultRuntimeMedianMs() - observation.getHintedRuntimeMedianMs());
        this.symmetricRuntimeRatio = Math.max(runtimeRatio, runtimeRegressionRatio);
        this.costComparisonDirection = determineCostDirection(observation);
        this.runtimeComparisonDirection = determineRuntimeDirection(observation);
        this.fasterSide = determineFasterSide(observation);
    }

    public CostRuntimeObservation getObservation() {
        return observation;
    }

    public double getCostRatio() {
        return costRatio;
    }

    public double getHigherCostRatio() {
        return higherCostRatio;
    }

    public double getRuntimeRatio() {
        return runtimeRatio;
    }

    public double getRuntimeRegressionRatio() {
        return runtimeRegressionRatio;
    }

    public double getAbsRuntimeGapMs() {
        return absRuntimeGapMs;
    }

    public double getSymmetricRuntimeRatio() {
        return symmetricRuntimeRatio;
    }

    public CostComparisonDirection getCostComparisonDirection() {
        return costComparisonDirection;
    }

    public RuntimeComparisonDirection getRuntimeComparisonDirection() {
        return runtimeComparisonDirection;
    }

    public FasterSide getFasterSide() {
        return fasterSide;
    }

    public static double computePositiveRatio(double numerator, double denominator) {
        if (numerator <= 0.0 || denominator <= 0.0) {
            return 0.0;
        }
        return numerator / denominator;
    }

    private static CostComparisonDirection determineCostDirection(CostRuntimeObservation observation) {
        if (observation.getDefaultCost() <= 0.0 || observation.getHintedCost() <= 0.0) {
            return CostComparisonDirection.UNKNOWN;
        }
        if (observation.getHintedCost() < observation.getDefaultCost()) {
            return CostComparisonDirection.HINTED_LOWER;
        }
        if (observation.getHintedCost() > observation.getDefaultCost()) {
            return CostComparisonDirection.HINTED_HIGHER;
        }
        return CostComparisonDirection.SIMILAR;
    }

    private static RuntimeComparisonDirection determineRuntimeDirection(CostRuntimeObservation observation) {
        if (observation.getDefaultRuntimeMedianMs() <= 0.0 || observation.getHintedRuntimeMedianMs() <= 0.0) {
            return RuntimeComparisonDirection.UNKNOWN;
        }
        if (observation.getHintedRuntimeMedianMs() < observation.getDefaultRuntimeMedianMs()) {
            return RuntimeComparisonDirection.HINTED_FASTER;
        }
        if (observation.getHintedRuntimeMedianMs() > observation.getDefaultRuntimeMedianMs()) {
            return RuntimeComparisonDirection.HINTED_SLOWER;
        }
        return RuntimeComparisonDirection.SIMILAR;
    }

    private static FasterSide determineFasterSide(CostRuntimeObservation observation) {
        if (observation.getDefaultRuntimeMedianMs() <= 0.0 || observation.getHintedRuntimeMedianMs() <= 0.0) {
            return FasterSide.UNKNOWN;
        }
        if (observation.getHintedRuntimeMedianMs() < observation.getDefaultRuntimeMedianMs()) {
            return FasterSide.HINTED;
        }
        if (observation.getHintedRuntimeMedianMs() > observation.getDefaultRuntimeMedianMs()) {
            return FasterSide.DEFAULT;
        }
        return FasterSide.TIED;
    }
}
