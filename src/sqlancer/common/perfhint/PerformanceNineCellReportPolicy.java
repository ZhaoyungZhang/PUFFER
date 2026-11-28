package sqlancer.common.perfhint;

import java.util.Set;

/** Applies the persistence threshold after the exhaustive A-I classification. */
public final class PerformanceNineCellReportPolicy {

    private PerformanceNineCellReportPolicy() {
    }

    public static boolean shouldReport(boolean taxonomyEnabled, Set<PerformanceNineCellCategory> reportCells,
            PerformanceNineCellCategory category, CostRuntimeObservation observation, double harmfulRuntimeRatio,
            double costAnomalyRatio) {
        if (!taxonomyEnabled || !reportCells.contains(category)) {
            return false;
        }
        CostRuntimeMetrics metrics = new CostRuntimeMetrics(observation);
        if (category.isConfirmedHarmful()) {
            return metrics.getRuntimeRatio() >= harmfulRuntimeRatio;
        }
        if (category == PerformanceNineCellCategory.B_CHEAPER_SIMILAR
                || category == PerformanceNineCellCategory.C_CHEAPER_SLOWER) {
            return metrics.getCostRatio() >= costAnomalyRatio;
        }
        return true;
    }
}
