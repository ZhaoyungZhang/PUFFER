package sqlancer.cockroachdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.cockroachdb.gen.CockroachDBPerformanceHintGenerator.HintedQueryCandidate;
import sqlancer.common.perfhint.CounterfactualCandidate;

public class TestCockroachDBPerformanceHintOracleUtils {

    @Test
    public void extendedJoinOrdinalsSkipQuotedTextAndComments() {
        String query = "SELECT 'INNER JOIN fake', \"LEFT JOIN fake\" FROM t0 /* RIGHT JOIN fake */ "
                + "RIGHT JOIN t1 ON t0.c0=t1.c0 FULL JOIN t2 ON t1.c0=t2.c0 CROSS JOIN t3";
        String[] types = { "RIGHT", "FULL", "CROSS" };
        for (int i = 0; i < types.length; i++) {
            HintedQueryCandidate candidate = new HintedQueryCandidate(types[i] + " HASH JOIN", "JOIN",
                    types[i] + "_HASH_JOIN", List.of("t" + i, "t" + (i + 1)), List.of(), i, types[i] + " JOIN");
            String expected = query.replace(types[i] + " JOIN t" + (i + 1), types[i] + " HASH JOIN t" + (i + 1));
            assertEquals(expected, CockroachDBPerformanceHintOracleUtils.applyCandidate(query, candidate));
        }
    }

    @Test
    public void indexMaterializationIgnoresFakeReferencesAndJoinMismatchIsUnchanged() {
        String query = "SELECT 'FROM t0', 'JOIN t1' FROM t0 CROSS JOIN t1";
        HintedQueryCandidate index = new HintedQueryCandidate("t1@idx_c1", "INDEX", "INDEX_REFERENCE", List.of("t1"),
                List.of("idx_c1"));
        assertEquals("SELECT 'FROM t0', 'JOIN t1' FROM t0 CROSS JOIN t1@idx_c1",
                CockroachDBPerformanceHintOracleUtils.applyCandidate(query, index));
        HintedQueryCandidate wrong = new HintedQueryCandidate("LEFT HASH JOIN", "JOIN", "LEFT_HASH_JOIN",
                List.of("t0", "t1"), List.of(), 0, "LEFT JOIN");
        assertEquals(query, CockroachDBPerformanceHintOracleUtils.applyCandidate(query, wrong));
    }

    @Test
    public void testApplyIndexCandidate() {
        String query = "SELECT t0.c0 FROM t0 WHERE t0.c1 >= 1 ORDER BY t0.c0 LIMIT 1";
        HintedQueryCandidate candidate = new HintedQueryCandidate("t0@{FORCE_INDEX=idx_c1}", "INDEX", "FORCE_INDEX",
                List.of("t0"), List.of("idx_c1"));

        String hinted = CockroachDBPerformanceHintOracleUtils.applyCandidate(query, candidate);

        assertTrue(hinted.contains("FROM t0@{FORCE_INDEX=idx_c1}"));
    }

    @Test
    public void testApplyJoinCandidateReplacesNthJoin() {
        String query = "SELECT t0.c0 AS ref0 FROM t0 INNER JOIN t1 ON t0.c1 = t1.c1 LEFT JOIN t2 ON t1.c1 = t2.c1";
        HintedQueryCandidate candidate = new HintedQueryCandidate("LEFT HASH JOIN", "JOIN", "LEFT_HASH_JOIN",
                List.of("t1", "t2"), List.of(), 1, "LEFT JOIN");

        String hinted = CockroachDBPerformanceHintOracleUtils.applyCandidate(query, candidate);

        assertTrue(hinted.contains("INNER JOIN t1"));
        assertTrue(hinted.contains("LEFT HASH JOIN t2"));
    }

    @Test
    public void testParsePlanCapturesCostAndOperations() {
        String explainText = String.join(System.lineSeparator(), "info", "top-k", " cost: 89.1800007", " index-join t0",
                " scan t0@idx_c1", "  flags: force-index=idx_c1", "  stats: [rows=10, distinct(2)=1, null(2)=0]",
                "  spans: FULL SCAN");

        CockroachDBPerformanceHintOracleUtils.PlanSummary plan = CockroachDBPerformanceHintOracleUtils
                .parsePlan(explainText);

        assertEquals(89.1800007, plan.getQueryCost());
        assertTrue(plan.getOperations().contains("TOPN"));
        assertTrue(plan.getKeys().contains("idx_c1"));
        assertTrue(plan.getAccessTypes().contains("FULL_SCAN"));
        assertTrue(plan.isUsingFilesort());
    }

    @Test
    public void deferredResultComparisonChecksAllColumnsNullsAndDuplicates() {
        List<List<String>> base = Arrays.asList(Arrays.asList("a", "1"), Arrays.asList("a", "1"),
                Arrays.asList("b", null));
        assertTrue(CockroachDBPerformanceHintOracleUtils.resultRowsMatch(base,
                Arrays.asList(Arrays.asList("b", null), Arrays.asList("a", "1"), Arrays.asList("a", "1"))));
        assertFalse(CockroachDBPerformanceHintOracleUtils.resultRowsMatch(base,
                Arrays.asList(Arrays.asList("a", "1"), Arrays.asList("b", null))));
        assertFalse(CockroachDBPerformanceHintOracleUtils.resultRowsMatch(base,
                Arrays.asList(Arrays.asList("a", "2"), Arrays.asList("a", "1"), Arrays.asList("b", null))));
    }

    @Test
    public void hintPlanEvidenceRequiresTheRequestedIndexAndJoinOperation() {
        CockroachDBPerformanceHintOracleUtils.PlanSummary accepted = new CockroachDBPerformanceHintOracleUtils.PlanSummary(
                "", 10.0, new LinkedHashSet<>(List.of("idx_join")), new LinkedHashSet<>(List.of("INDEX_SCAN")),
                new LinkedHashSet<>(List.of("HASH_JOIN")), false, false, false, null, null);
        CounterfactualCandidate hashJoin = CounterfactualCandidate.hint("SELECT 1", "JOIN", "HASH_JOIN", "HASH JOIN",
                List.of("t"), List.of("idx_join"));
        CounterfactualCandidate mergeJoin = CounterfactualCandidate.hint("SELECT 1", "JOIN", "MERGE_JOIN", "MERGE JOIN",
                List.of("t"), List.of("idx_join"));

        assertTrue(CockroachDBPerformanceHintOracleUtils.isHintAcceptedByPlan(hashJoin, accepted));
        assertFalse(CockroachDBPerformanceHintOracleUtils.isHintAcceptedByPlan(mergeJoin, accepted));
    }

    @Test
    public void maxHintedVariantsMatchesAllDbmsCap() {
        assertEquals(16, CockroachDBPerformanceHintOracleUtils.MAX_HINTED_VARIANTS);
    }
}
