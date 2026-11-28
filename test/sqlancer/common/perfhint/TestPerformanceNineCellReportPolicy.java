package sqlancer.common.perfhint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class TestPerformanceNineCellReportPolicy {

    private static final Set<PerformanceNineCellCategory> REPORT_CELLS = EnumSet.of(
            PerformanceNineCellCategory.A_CHEAPER_FASTER, PerformanceNineCellCategory.B_CHEAPER_SIMILAR,
            PerformanceNineCellCategory.C_CHEAPER_SLOWER, PerformanceNineCellCategory.D_SIMILAR_COST_FASTER,
            PerformanceNineCellCategory.G_COSTLIER_FASTER);

    @Test
    public void gatesHarmfulCellsByRuntimeRatio() {
        assertFalse(report(PerformanceNineCellCategory.A_CHEAPER_FASTER, observation(20, 10, 4.99, 1)));
        assertTrue(report(PerformanceNineCellCategory.A_CHEAPER_FASTER, observation(20, 10, 5, 1)));
        assertTrue(report(PerformanceNineCellCategory.D_SIMILAR_COST_FASTER, observation(10, 10, 10, 1)));
        assertTrue(report(PerformanceNineCellCategory.G_COSTLIER_FASTER, observation(10, 20, 5, 1)));
    }

    @Test
    public void gatesLatentCellsByCostRatio() {
        assertFalse(report(PerformanceNineCellCategory.B_CHEAPER_SIMILAR, observation(9.99, 1, 1, 1)));
        assertTrue(report(PerformanceNineCellCategory.B_CHEAPER_SIMILAR, observation(10, 1, 1, 1)));
        assertTrue(report(PerformanceNineCellCategory.C_CHEAPER_SLOWER, observation(20, 1, 1, 2)));
    }

    private static boolean report(PerformanceNineCellCategory category, CostRuntimeObservation observation) {
        return PerformanceNineCellReportPolicy.shouldReport(true, REPORT_CELLS, category, observation, 5.0, 10.0);
    }

    private static CostRuntimeObservation observation(double defaultCost, double hintedCost, double defaultRuntime,
            double hintedRuntime) {
        return new CostRuntimeObservation(defaultCost, hintedCost, defaultRuntime, hintedRuntime, defaultRuntime,
                hintedRuntime, defaultRuntime, hintedRuntime, defaultRuntime, hintedRuntime, 0, 0,
                List.of(defaultRuntime), List.of(hintedRuntime));
    }
}
