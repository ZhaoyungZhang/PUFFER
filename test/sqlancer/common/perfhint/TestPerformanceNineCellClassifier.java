package sqlancer.common.perfhint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

public class TestPerformanceNineCellClassifier {

    private static final PerformanceNineCellOptions OPTIONS = new PerformanceNineCellOptions(true, 0.95, 1.05, 0.9, 1.3,
            0.0, 1.0);

    @Test
    public void classifiesEveryCostRuntimeCell() {
        assertEquals(PerformanceNineCellCategory.A_CHEAPER_FASTER, classify(100, 50, 100, 10));
        assertEquals(PerformanceNineCellCategory.B_CHEAPER_SIMILAR, classify(100, 50, 100, 105));
        assertEquals(PerformanceNineCellCategory.C_CHEAPER_SLOWER, classify(100, 50, 10, 100));
        assertEquals(PerformanceNineCellCategory.D_SIMILAR_COST_FASTER, classify(100, 100, 100, 10));
        assertEquals(PerformanceNineCellCategory.E_SIMILAR_COST_SIMILAR_RUNTIME, classify(100, 100, 100, 105));
        assertEquals(PerformanceNineCellCategory.F_SIMILAR_COST_SLOWER, classify(100, 100, 10, 100));
        assertEquals(PerformanceNineCellCategory.G_COSTLIER_FASTER, classify(100, 200, 100, 10));
        assertEquals(PerformanceNineCellCategory.H_COSTLIER_SIMILAR, classify(100, 200, 100, 105));
        assertEquals(PerformanceNineCellCategory.I_COSTLIER_SLOWER, classify(100, 200, 10, 100));
    }

    @Test
    public void foldsSmallRuntimeGapsIntoTheSimilarColumn() {
        assertEquals(PerformanceNineCellCategory.B_CHEAPER_SIMILAR, classify(100, 50, 0.3, 0.1));
    }

    @Test
    public void usesAsymmetricRuntimeSimilarityBounds() {
        assertEquals(PerformanceNineCellCategory.B_CHEAPER_SIMILAR, classify(100, 50, 100, 111));
        assertEquals(PerformanceNineCellCategory.C_CHEAPER_SLOWER, classify(100, 50, 100, 112));
        assertEquals(PerformanceNineCellCategory.A_CHEAPER_FASTER, classify(100, 50, 131, 100));
    }

    @Test
    public void rejectsUnknownReportCells() {
        assertThrows(IllegalArgumentException.class,
                () -> PerformanceNineCellCategory.parseReportCells("A,not-a-cell"));
    }

    private static PerformanceNineCellCategory classify(double defaultCost, double counterfactualCost,
            double defaultRuntimeMs, double counterfactualRuntimeMs) {
        return PerformanceNineCellClassifier.classify(OPTIONS,
                new CostRuntimeObservation(defaultCost, counterfactualCost, defaultRuntimeMs, counterfactualRuntimeMs,
                        defaultRuntimeMs, counterfactualRuntimeMs, defaultRuntimeMs, counterfactualRuntimeMs,
                        defaultRuntimeMs, counterfactualRuntimeMs, 0, 0, List.of(defaultRuntimeMs),
                        List.of(counterfactualRuntimeMs)));
    }
}
