package sqlancer.mariadb.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.common.perfhint.CounterfactualCandidate;

public class TestMariaDBPerformanceHintOracleUtils {

    @Test
    public void testCandidateEvidenceRequiresLowerHintedCost() {
        MariaDBPerformanceHintOracleUtils.PlanSummary defaultPlan = MariaDBPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"40\"},\"table\":{\"access_type\":\"range\",\"key\":\"idx_a\"}}}");
        MariaDBPerformanceHintOracleUtils.PlanSummary hintedPlan = MariaDBPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"100\"},\"table\":{\"access_type\":\"ref\",\"key\":\"idx_b\"}}}");

        assertFalse(MariaDBPerformanceHintOracleUtils.hasStrictlyLowerCost(defaultPlan, hintedPlan));
        assertFalse(MariaDBPerformanceHintOracleUtils.buildPlanEvidence(defaultPlan, hintedPlan, 1.0).stream()
                .anyMatch(item -> item.startsWith("cost_ratio=")));
    }

    @Test
    public void testCandidateEvidenceAllowsLowerHintedCost() {
        MariaDBPerformanceHintOracleUtils.PlanSummary defaultPlan = MariaDBPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"100\"},\"table\":{\"access_type\":\"ALL\",\"temp_table\":true}}}");
        MariaDBPerformanceHintOracleUtils.PlanSummary hintedPlan = MariaDBPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"40\"},\"table\":{\"access_type\":\"range\",\"key\":\"idx_a\"}}}");

        List<String> evidence = MariaDBPerformanceHintOracleUtils.buildPlanEvidence(defaultPlan, hintedPlan, 1.25);
        assertTrue(evidence.stream().anyMatch(item -> item.startsWith("cost_ratio=")));
        assertTrue(evidence.contains("hinted_plan_uses_more_specific_index"));
    }

    @Test
    public void testGenericCounterfactualIdentityIsUsedForWitnessKeys() {
        MariaDBPerformanceHintOracleUtils.PlanSummary defaultPlan = MariaDBPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"100\"},\"table\":{\"access_type\":\"ALL\"}}}");
        MariaDBPerformanceHintOracleUtils.PlanSummary counterfactualPlan = MariaDBPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"40\"},\"table\":{\"access_type\":\"range\",\"key\":\"idx_a\"}}}");
        CounterfactualCandidate candidate = CounterfactualCandidate.hint("SELECT /*+ INDEX(t0, idx_a) */ c0 FROM t0",
                "INDEX", "INDEX", "INDEX(t0, idx_a)", List.of("t0"), List.of("idx_a"));
        MariaDBPerformanceHintOracleUtils.PerformanceHintWitness witness = new MariaDBPerformanceHintOracleUtils.PerformanceHintWitness(
                "SELECT c0 FROM t0", candidate.getAlternativeSql(), candidate, defaultPlan, counterfactualPlan,
                List.of("hinted_plan_uses_more_specific_index"), true);
        witness.setSkeletonKey("SELECT|TABLES_1");

        assertTrue(MariaDBPerformanceHintOracleUtils.buildFamilyKey(witness).startsWith("mutation=HINT|INDEX|INDEX|"));
        assertTrue(MariaDBPerformanceHintOracleUtils.buildPlanSignatureKey(witness)
                .startsWith("mutation=HINT|INDEX|INDEX|"));
        assertTrue(MariaDBPerformanceHintOracleUtils.classifyRootCause(witness).getSignatureKey()
                .contains("|HINT|INDEX|INDEX|"));
    }

    @Test
    public void testLegacyHintFeedbackKeysMigrateToGenericIdentity() {
        assertEquals("mutation=HINT|INDEX|INDEX|SELECT|DEFAULT=D|COUNTERFACTUAL=H",
                MariaDBPerformanceHintFeedbackManager.migrateLegacyFamilyOrPlanKey("INDEX|SELECT|DEFAULT=D|HINTED=H"));
        assertEquals("mutation=HINT|TABLE|BNL|SELECT|DEFAULT=D|COUNTERFACTUAL=H",
                MariaDBPerformanceHintFeedbackManager.migrateLegacyFamilyOrPlanKey("BNL|SELECT|DEFAULT=D|HINTED=H"));
        assertEquals("PLAIN_TOPN|HINT|INDEX|INDEX|HINT_FAMILY=HINT_INDEX|HINT_TYPE=INDEX|DEFAULT_JOIN=JOIN_OTHER",
                MariaDBPerformanceHintFeedbackManager.migrateLegacyRootCauseKey(
                        "PLAIN_TOPN|HINT_FAMILY=INDEX|HINT_TYPE=INDEX|DEFAULT_JOIN=JOIN_OTHER"));
    }

    @Test
    public void testDeferredResultComparisonChecksEveryColumnAndDuplicate() {
        List<List<String>> base = Arrays.asList(Arrays.asList("a", "1"), Arrays.asList("a", "1"),
                Arrays.asList("b", null));
        List<List<String>> reorderedEquivalent = Arrays.asList(Arrays.asList("b", null), Arrays.asList("a", "1"),
                Arrays.asList("a", "1"));
        List<List<String>> missingDuplicate = Arrays.asList(Arrays.asList("a", "1"), Arrays.asList("b", null));
        List<List<String>> differentSecondColumn = Arrays.asList(Arrays.asList("a", "2"), Arrays.asList("a", "1"),
                Arrays.asList("b", null));

        assertTrue(MariaDBPerformanceHintOracleUtils.resultRowsMatch(base, reorderedEquivalent));
        assertFalse(MariaDBPerformanceHintOracleUtils.resultRowsMatch(base, missingDuplicate));
        assertFalse(MariaDBPerformanceHintOracleUtils.resultRowsMatch(base, differentSecondColumn));
    }
}
