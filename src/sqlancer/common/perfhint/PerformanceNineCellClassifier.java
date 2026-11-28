package sqlancer.common.perfhint;

/** Classifies a measured pair into exactly one paper-facing cost/runtime cell. */
public final class PerformanceNineCellClassifier {

    private PerformanceNineCellClassifier() {
    }

    public static PerformanceNineCellCategory classify(PerformanceNineCellOptions options,
            CostRuntimeObservation observation) {
        CostRuntimeMetrics metrics = new CostRuntimeMetrics(observation);
        CostBand costBand = classifyCost(options, metrics.getCostRatio());
        RuntimeBand runtimeBand = classifyRuntime(options, metrics);
        switch (costBand) {
        case CHEAPER:
            return runtimeBand == RuntimeBand.FASTER ? PerformanceNineCellCategory.A_CHEAPER_FASTER
                    : runtimeBand == RuntimeBand.SIMILAR ? PerformanceNineCellCategory.B_CHEAPER_SIMILAR
                            : PerformanceNineCellCategory.C_CHEAPER_SLOWER;
        case SIMILAR:
            return runtimeBand == RuntimeBand.FASTER ? PerformanceNineCellCategory.D_SIMILAR_COST_FASTER
                    : runtimeBand == RuntimeBand.SIMILAR ? PerformanceNineCellCategory.E_SIMILAR_COST_SIMILAR_RUNTIME
                            : PerformanceNineCellCategory.F_SIMILAR_COST_SLOWER;
        case COSTLIER:
            return runtimeBand == RuntimeBand.FASTER ? PerformanceNineCellCategory.G_COSTLIER_FASTER
                    : runtimeBand == RuntimeBand.SIMILAR ? PerformanceNineCellCategory.H_COSTLIER_SIMILAR
                            : PerformanceNineCellCategory.I_COSTLIER_SLOWER;
        default:
            throw new AssertionError(costBand);
        }
    }

    public static boolean hasValidCosts(double defaultCost, double counterfactualCost) {
        return defaultCost > 0.0 && counterfactualCost > 0.0;
    }

    public static boolean isPotentialCostCandidate(double defaultCost, double counterfactualCost,
            PerformanceNineCellOptions options) {
        return options.isEnabled() && hasValidCosts(defaultCost, counterfactualCost);
    }

    private static CostBand classifyCost(PerformanceNineCellOptions options, double defaultOverCounterfactualCost) {
        if (defaultOverCounterfactualCost > options.getCostRatioUpperBound()) {
            return CostBand.CHEAPER;
        }
        if (defaultOverCounterfactualCost < options.getCostRatioLowerBound()) {
            return CostBand.COSTLIER;
        }
        return CostBand.SIMILAR;
    }

    private static RuntimeBand classifyRuntime(PerformanceNineCellOptions options, CostRuntimeMetrics metrics) {
        if (metrics.getObservation().getDefaultRuntimeMedianMs() < options.getMinRuntimeMedianMs()
                || metrics.getObservation().getHintedRuntimeMedianMs() < options.getMinRuntimeMedianMs()
                || metrics.getAbsRuntimeGapMs() < options.getMinRuntimeAbsGapMs()) {
            return RuntimeBand.SIMILAR;
        }
        double runtimeRatio = metrics.getRuntimeRatio();
        if (runtimeRatio > options.getRuntimeRatioUpperBound()) {
            return RuntimeBand.FASTER;
        }
        if (runtimeRatio < options.getRuntimeRatioLowerBound()) {
            return RuntimeBand.SLOWER;
        }
        return RuntimeBand.SIMILAR;
    }

    private enum CostBand {
        CHEAPER, SIMILAR, COSTLIER
    }

    private enum RuntimeBand {
        FASTER, SIMILAR, SLOWER
    }
}
