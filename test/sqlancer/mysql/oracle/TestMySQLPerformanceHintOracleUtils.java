package sqlancer.mysql.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.CounterfactualMutationKind;

public class TestMySQLPerformanceHintOracleUtils {

    @Test
    public void testExtractPlanSummary() {
        String explainJson = "{\n" + "  \"query_block\": {\n" + "    \"cost_info\": {\"query_cost\": \"123.45\"},\n"
                + "    \"ordering_operation\": {\n" + "      \"using_filesort\": true,\n" + "      \"table\": {\n"
                + "        \"access_type\": \"range\",\n" + "        \"key\": \"idx_a\",\n"
                + "        \"rows_examined_per_scan\": 42,\n" + "        \"rows_produced_per_join\": 7\n" + "      }\n"
                + "    }\n" + "  }\n" + "}";
        MySQLPerformanceHintOracleUtils.PlanSummary plan = MySQLPerformanceHintOracleUtils.parsePlan(explainJson);
        assertEquals(123.45, plan.getQueryCost());
        assertTrue(plan.getKeys().contains("idx_a"));
        assertTrue(plan.getAccessTypes().contains("range"));
        assertTrue(plan.isUsingFilesort());
        assertFalse(plan.isUsingTemporaryTable());
        assertEquals(42.0, plan.getRowsExaminedPerScan());
        assertEquals(7.0, plan.getRowsProducedPerJoin());
    }

    @Test
    public void testMissingQueryCostReturnsNull() {
        String explainJson = "{\"query_block\": {\"message\": \"Impossible WHERE\"}}";
        MySQLPerformanceHintOracleUtils.PlanSummary plan = MySQLPerformanceHintOracleUtils.parsePlan(explainJson);
        assertEquals(null, plan.getQueryCost());
    }

    @Test
    public void testInjectHint() {
        String query = "SELECT DISTINCT t0.c0 AS ref0 FROM t0";
        String hintedQuery = MySQLPerformanceHintOracleUtils.injectHint(query, "INDEX(t0, idx0)");
        assertEquals("SELECT /*+ INDEX(t0, idx0) */ DISTINCT t0.c0 AS ref0 FROM t0", hintedQuery);
    }

    @Test
    public void testCandidateEvidence() {
        MySQLPerformanceHintOracleUtils.PlanSummary defaultPlan = MySQLPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"100\"},\"table\":{\"access_type\":\"ALL\",\"using_temporary_table\":true}}}");
        MySQLPerformanceHintOracleUtils.PlanSummary hintedPlan = MySQLPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"40\"},\"table\":{\"access_type\":\"range\",\"key\":\"idx_a\"}}}");
        List<String> evidence = MySQLPerformanceHintOracleUtils.buildPlanEvidence(defaultPlan, hintedPlan, 1.25);
        assertTrue(evidence.contains("hinted_plan_uses_more_specific_index"));
        assertTrue(evidence.contains("hinted_plan_avoids_temporary_table"));
        assertTrue(evidence.stream().anyMatch(item -> item.startsWith("cost_ratio=")));
    }

    @Test
    public void testCandidateRequiresLowerHintedCost() {
        MySQLPerformanceHintOracleUtils.PlanSummary defaultPlan = MySQLPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"40\"},\"table\":{\"access_type\":\"range\",\"key\":\"idx_a\"}}}");
        MySQLPerformanceHintOracleUtils.PlanSummary hintedPlan = MySQLPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"100\"},\"table\":{\"access_type\":\"ref\",\"key\":\"idx_b\"}}}");
        assertFalse(MySQLPerformanceHintOracleUtils.hasStrictlyLowerCost(defaultPlan, hintedPlan));
        assertTrue(MySQLPerformanceHintOracleUtils.buildPlanEvidence(defaultPlan, hintedPlan, 1.0).isEmpty());
    }

    @Test
    public void testOptimisticPlanEvidenceAcceptsAnyMeaningfullyDifferentPlan() {
        MySQLPerformanceHintOracleUtils.PlanSummary defaultPlan = MySQLPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"40\"},\"table\":{\"access_type\":\"range\",\"key\":\"idx_a\"}}}");
        MySQLPerformanceHintOracleUtils.PlanSummary counterfactualPlan = MySQLPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"100\"},\"table\":{\"access_type\":\"ALL\"}}}");

        assertTrue(MySQLPerformanceHintOracleUtils.hasMeaningfulPlanDifference(defaultPlan, counterfactualPlan));
        assertFalse(
                MySQLPerformanceHintOracleUtils.buildPlanDifferenceEvidence(defaultPlan, counterfactualPlan).isEmpty());
    }

    @Test
    public void testStrictResultMultisetUsesAllColumnsAndDuplicateMultiplicity() {
        List<List<String>> baseRows = List.of(List.of("1", "a"), List.of("1", "a"), List.of("2", "b"));
        assertTrue(MySQLPerformanceHintOracleUtils.resultRowsMatch(baseRows,
                List.of(List.of("2", "b"), List.of("1", "a"), List.of("1", "a"))));
        assertFalse(MySQLPerformanceHintOracleUtils.resultRowsMatch(baseRows,
                List.of(List.of("2", "different"), List.of("1", "a"), List.of("1", "a"))));
        assertFalse(MySQLPerformanceHintOracleUtils.resultRowsMatch(baseRows,
                List.of(List.of("2", "b"), List.of("1", "a"), List.of("3", "c"))));
        assertFalse(MySQLPerformanceHintOracleUtils.resultRowsMatch(baseRows,
                List.of(List.of("2", "b"), List.of("1", "a"))));
        assertTrue(MySQLPerformanceHintOracleUtils.resultRowsMatch(List.of(Arrays.asList("1", null)),
                List.of(Arrays.asList("1", null))));
    }

    @Test
    public void testMedian() {
        assertEquals(3.0, MySQLPerformanceHintOracleUtils.median(List.of(1.0, 5.0, 3.0)));
        assertEquals(2.5, MySQLPerformanceHintOracleUtils.median(List.of(1.0, 4.0, 3.0, 2.0)));
    }

    @Test
    public void testRuntimeSummaryMean() {
        MySQLPerformanceHintOracleUtils.RuntimeSummary runtimeSummary = new MySQLPerformanceHintOracleUtils.RuntimeSummary(
                List.of(1.0, 2.0, 3.0, 4.0, 5.0));
        assertEquals(3.0, runtimeSummary.getMeanMs());
        assertEquals(3.0, runtimeSummary.getMedianMs());
    }

    @Test
    public void testPlanSummaryExtractsOperationsAndDeduplication() {
        String explainJson = "{" + "\"query_block\":{\"cost_info\":{\"query_cost\":\"10\"},"
                + "\"ordering_operation\":{\"operation\":\"Temporary table with deduplication\",\"deduplication\":true,"
                + "\"using_filesort\":true}}}";
        MySQLPerformanceHintOracleUtils.PlanSummary plan = MySQLPerformanceHintOracleUtils.parsePlan(explainJson);
        assertTrue(plan.getOperations().contains("TEMP_DEDUP"));
        assertTrue(plan.isUsingDeduplication());
        assertTrue(plan.isUsingFilesort());
    }

    @Test
    public void testSkeletonKeyCapturesClauses() {
        String query = "SELECT DISTINCTROW t2.c1 AS ref0 FROM t0, t1, t2 ORDER BY t2.c1 ASC LIMIT 1";
        String skeletonKey = MySQLPerformanceHintOracleUtils.buildSkeletonKey(query, 3);
        assertEquals("SELECT_DISTINCTROW|TABLES_3|ORDER_BY|LIMIT|COMMA_JOIN", skeletonKey);
    }

    @Test
    public void testPlanSignatureIgnoresCostRatio() {
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness first = buildWitness(
                "SELECT DISTINCTROW t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1", 100.0, 10.0,
                "Temporary table with deduplication", "Nested loop inner join", List.of("cost_ratio=10.0000"));
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness second = buildWitness(
                "SELECT DISTINCTROW t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1", 200.0, 10.0,
                "Temporary table with deduplication", "Nested loop inner join", List.of("cost_ratio=20.0000"));
        assertEquals(MySQLPerformanceHintOracleUtils.buildPlanSignatureKey(first),
                MySQLPerformanceHintOracleUtils.buildPlanSignatureKey(second));
    }

    @Test
    public void testRootCauseSignatureNormalizesDistinctVariants() {
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness distinct = buildWitness(
                "SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1", 120.0, 12.0,
                "Temporary table with deduplication", "Nested loop inner join", List.of("cost_ratio=10.0000"));
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness distinctRow = buildWitness(
                "SELECT DISTINCTROW t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1", 120.0, 12.0,
                "Temporary table with deduplication", "Nested loop inner join", List.of("cost_ratio=10.0000"));
        assertEquals(MySQLPerformanceHintOracleUtils.buildRootCauseSignatureKey(distinct),
                MySQLPerformanceHintOracleUtils.buildRootCauseSignatureKey(distinctRow));
    }

    @Test
    public void testFilterOperationsAreNormalizedInsideRootCauseSignature() {
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness first = buildWitness(
                "SELECT ALL t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1", 120.0, 12.0,
                "Filter: cast(t0.c0 as signed)", "Nested loop inner join", List.of("cost_ratio=10.0000"));
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness second = buildWitness(
                "SELECT ALL t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1", 120.0, 12.0,
                "Filter: cast(t1.c1 as signed)", "Nested loop inner join", List.of("cost_ratio=10.0000"));
        assertEquals(MySQLPerformanceHintOracleUtils.buildRootCauseSignatureKey(first),
                MySQLPerformanceHintOracleUtils.buildRootCauseSignatureKey(second));
    }

    @Test
    public void testRootCauseCategoryBuckets() {
        assertEquals("DISTINCT_EARLY_STOP",
                MySQLPerformanceHintOracleUtils.detectRootCauseCategory("SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1"));
        assertEquals("DISTINCT_TOPN", MySQLPerformanceHintOracleUtils
                .detectRootCauseCategory("SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1"));
        assertEquals("GROUP_BY_TOPN", MySQLPerformanceHintOracleUtils.detectRootCauseCategory(
                "SELECT ALL t0.c0 AS ref0 FROM t0, t1 GROUP BY t0.c0 ORDER BY t0.c0 DESC LIMIT 1"));
        assertEquals("PLAIN_TOPN", MySQLPerformanceHintOracleUtils
                .detectRootCauseCategory("SELECT ALL t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1"));
    }

    @Test
    public void testRewriteDoesNotShareHintIdentityForTheSamePlanShape() {
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness hintWitness = buildWitness(
                "SELECT ALL t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1", 120.0, 12.0,
                "Temporary table with deduplication", "Nested loop inner join", List.of("cost_ratio=10.0000"));
        CounterfactualCandidate rewrite = new CounterfactualCandidate(CounterfactualMutationKind.QUERY_REWRITE,
                "SELECT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1", "NORMALIZATION", "RULE_1",
                "test rewrite", List.of("t0", "t1"), List.of());
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness rewriteWitness = new MySQLPerformanceHintOracleUtils.PerformanceHintWitness(
                hintWitness.getBaseQuery(), rewrite.getAlternativeSql(), rewrite, hintWitness.getDefaultPlan(),
                hintWitness.getHintedPlan(), hintWitness.getPlanEvidence(), true);
        rewriteWitness.setSkeletonKey(hintWitness.getSkeletonKey());

        assertFalse(rewriteWitness.isHintCounterfactual());
        assertEquals("COUNTERFACTUAL", rewriteWitness.getCounterfactualRuntimeSide());
        assertNotEquals(MySQLPerformanceHintOracleUtils.buildPlanSignatureKey(hintWitness),
                MySQLPerformanceHintOracleUtils.buildPlanSignatureKey(rewriteWitness));
        assertNotEquals(MySQLPerformanceHintOracleUtils.buildRootCauseSignatureKey(hintWitness),
                MySQLPerformanceHintOracleUtils.buildRootCauseSignatureKey(rewriteWitness));
    }

    private static MySQLPerformanceHintOracleUtils.PerformanceHintWitness buildWitness(String baseQuery,
            double defaultCost, double hintedCost, String defaultOperation, String hintedOperation,
            List<String> evidence) {
        String defaultPlanJson = String.format(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"%.1f\"},\"duplicates_removal\":{\"using_filesort\":true,"
                        + "\"table\":{\"access_type\":\"ALL\"}},\"ordering_operation\":{\"using_temporary_table\":true,"
                        + "\"operation\":\"%s\"}}}",
                defaultCost, defaultOperation);
        String hintedPlanJson = String.format(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"%.1f\"},\"nested_loop\":{\"table\":{\"access_type\":\"range\","
                        + "\"key\":\"idx_ref\"},\"operation\":\"%s\"}}}",
                hintedCost, hintedOperation);
        MySQLPerformanceHintOracleUtils.PlanSummary defaultPlan = MySQLPerformanceHintOracleUtils
                .parsePlan(defaultPlanJson);
        MySQLPerformanceHintOracleUtils.PlanSummary hintedPlan = MySQLPerformanceHintOracleUtils
                .parsePlan(hintedPlanJson);
        String counterfactualQuery = "SELECT /*+ NO_BNL(t0, t1) */ " + baseQuery.substring("SELECT ".length());
        CounterfactualCandidate candidate = CounterfactualCandidate.hint(counterfactualQuery, "TABLE", "NO_BNL",
                "NO_BNL(t0, t1)", List.of("t0", "t1"), List.of());
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness = new MySQLPerformanceHintOracleUtils.PerformanceHintWitness(
                baseQuery, counterfactualQuery, candidate, defaultPlan, hintedPlan, evidence, true);
        witness.setSkeletonKey(MySQLPerformanceHintOracleUtils.buildSkeletonKey(baseQuery, 2));
        witness.setRootCauseCategory(MySQLPerformanceHintOracleUtils.detectRootCauseCategory(baseQuery));
        witness.setFamilyKey(MySQLPerformanceHintOracleUtils.buildFamilyKey(witness));
        witness.setPlanSignatureKey(MySQLPerformanceHintOracleUtils.buildPlanSignatureKey(witness));
        witness.setRootCauseSignatureKey(MySQLPerformanceHintOracleUtils.buildRootCauseSignatureKey(witness));
        return witness;
    }
}
