package sqlancer.oceanbase.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.common.perfhint.PerformanceNineCellOptions;

public class TestOceanBasePerformanceHintOracleUtils {

    @Test
    public void testParsePlanUsesOceanBaseEstimateFields() {
        String json = "{\"ID\":0,\"OPERATOR\":\"HASH JOIN\",\"NAME\":\"\",\"EST.ROWS\":4,"
                + "\"EST.TIME(us)\":67,\"CHILD\":[{\"ID\":1,\"OPERATOR\":\"TABLE RANGE SCAN\","
                + "\"NAME\":\"t0(i_c1)\",\"EST.ROWS\":2,\"EST.TIME(us)\":3}]}";

        OceanBasePerformanceHintOracleUtils.PlanSummary plan = OceanBasePerformanceHintOracleUtils.parsePlan(json);

        assertEquals(67.0, plan.getEstimatedTimeUs());
        assertEquals(4.0, plan.getEstimatedRows());
        assertTrue(plan.getOperators().contains("HASH JOIN"));
        assertTrue(plan.getNames().contains("T0(I_C1)"));
    }

    @Test
    public void testHintInjectionAndUsedHintValidation() {
        assertEquals("SELECT /*+ INDEX(t0 i_c1) */ t0.c0 FROM t0",
                OceanBasePerformanceHintOracleUtils.injectHint("SELECT t0.c0 FROM t0", "INDEX(t0 i_c1)"));

        String extended = "Used Hint:\n/*+ NO_USE_HASH_AGGREGATION */\nQb name trace:\n";
        assertTrue(OceanBasePerformanceHintOracleUtils.isHintAccepted(extended, "NO_USE_HASH_AGGREGATION"));
        assertFalse(OceanBasePerformanceHintOracleUtils.isHintAccepted(extended, "USE_HASH_AGGREGATION"));
    }

    @Test
    public void testPlanEvidenceRequiresRealPlanChange() {
        OceanBasePerformanceHintOracleUtils.PlanSummary defaultPlan = OceanBasePerformanceHintOracleUtils
                .parsePlan("{\"OPERATOR\":\"TABLE FULL SCAN\",\"NAME\":\"t0\",\"EST.ROWS\":100,\"EST.TIME(us)\":100}");
        OceanBasePerformanceHintOracleUtils.PlanSummary hintedPlan = OceanBasePerformanceHintOracleUtils.parsePlan(
                "{\"OPERATOR\":\"TABLE RANGE SCAN\",\"NAME\":\"t0(i_c1)\",\"EST.ROWS\":2,\"EST.TIME(us)\":10}");

        List<String> evidence = OceanBasePerformanceHintOracleUtils.buildPlanEvidence(defaultPlan, hintedPlan,
                taxonomyOptions());

        assertTrue(evidence.stream().anyMatch(item -> item.startsWith("est_time_ratio=")));
        assertTrue(evidence.contains("operators_changed"));
        assertTrue(evidence.contains("access_names_changed"));
    }

    @Test
    public void testMedian() {
        assertEquals(3.0, OceanBasePerformanceHintOracleUtils.median(List.of(1.0, 5.0, 3.0)));
        assertEquals(2.5, OceanBasePerformanceHintOracleUtils.median(List.of(1.0, 4.0, 3.0, 2.0)));
    }

    @Test
    public void testStrictResultRowsCompareEveryColumnAndDuplicate() {
        List<List<String>> base = Arrays.asList(Arrays.asList("a", "1"), Arrays.asList("a", "1"),
                Arrays.asList("b", null));
        assertTrue(OceanBasePerformanceHintOracleUtils.resultRowsMatch(base,
                Arrays.asList(Arrays.asList("b", null), Arrays.asList("a", "1"), Arrays.asList("a", "1"))));
        assertFalse(OceanBasePerformanceHintOracleUtils.resultRowsMatch(base,
                Arrays.asList(Arrays.asList("b", null), Arrays.asList("a", "1"), Arrays.asList("a", "2"))));
        assertFalse(OceanBasePerformanceHintOracleUtils.resultRowsMatch(base,
                Arrays.asList(Arrays.asList("b", null), Arrays.asList("a", "1"))));
    }

    private static PerformanceNineCellOptions taxonomyOptions() {
        return new PerformanceNineCellOptions(true, 0.9, 1.1, 0.9, 1.3, 0.0, 1.0);
    }
}
