package sqlancer.tidb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.RewrittenQuery;

public class TestTiDBPerformanceHintCounterfactual {

    @Test
    public void testGenericWitnessIdentitySeparatesHintAndRewrite() {
        TiDBPerformanceHintOracleUtils.PlanSummary defaultPlan = plan(100.0, "TableFullScan_1", "table:t0");
        TiDBPerformanceHintOracleUtils.PlanSummary alternativePlan = plan(20.0, "IndexReader_1", "index:idx_c0");
        String baseSql = "SELECT t0.c0 FROM t0";
        CounterfactualCandidate hint = CounterfactualCandidate.hint("SELECT /*+ USE_INDEX(t0, idx_c0) */ t0.c0 FROM t0",
                "INDEX", "USE_INDEX", "USE_INDEX(t0, idx_c0)", List.of("t0"), List.of("idx_c0"));
        CounterfactualCandidate rewrite = CounterfactualCandidate.queryRewrite(
                new RewrittenQuery("SELECT t0.c0 FROM t0 WHERE TRUE", "PREDICATE", "TAUTOLOGY", "add true"),
                List.of("t0"), List.of("idx_c0"));

        TiDBPerformanceHintOracleUtils.PerformanceHintWitness hintWitness = witness(baseSql, hint, defaultPlan,
                alternativePlan);
        TiDBPerformanceHintOracleUtils.PerformanceHintWitness rewriteWitness = witness(baseSql, rewrite, defaultPlan,
                alternativePlan);

        assertTrue(hintWitness.isHintCounterfactual());
        assertEquals("HINTED", hintWitness.getCounterfactualRuntimeSide());
        assertEquals("COUNTERFACTUAL", rewriteWitness.getCounterfactualRuntimeSide());
        assertTrue(TiDBPerformanceHintOracleUtils.buildPlanSignatureKey(hintWitness)
                .startsWith("mutation=HINT|INDEX|USE_INDEX|"));
        assertTrue(TiDBPerformanceHintOracleUtils.buildPlanSignatureKey(rewriteWitness)
                .startsWith("mutation=QUERY_REWRITE|PREDICATE|TAUTOLOGY|"));
        assertNotEquals(TiDBPerformanceHintOracleUtils.buildRootCauseSignatureKey(hintWitness),
                TiDBPerformanceHintOracleUtils.buildRootCauseSignatureKey(rewriteWitness));
    }

    @Test
    public void testLegacyFeedbackKeysMigrateToHintIdentity() {
        assertEquals("mutation=HINT|INDEX|USE_INDEX|SELECT|DEFAULT=D|COUNTERFACTUAL=H",
                TiDBPerformanceHintFeedbackManager.migrateLegacyKey("P", "USE_INDEX|SELECT|DEFAULT=D|HINTED=H"));
        assertEquals("mutation=HINT|TABLE|HASH_JOIN|SELECT|DEFAULT=D|COUNTERFACTUAL=H",
                TiDBPerformanceHintFeedbackManager.migrateLegacyKey("F", "HASH_JOIN|SELECT|DEFAULT=D|HINTED=H"));
        assertEquals(
                "PLAIN_TOPN|HINT|INDEX|USE_INDEX|HINT_FAMILY=HINT_INDEX|HINT_TYPE=USE_INDEX|DEFAULT_JOIN=JOIN_OTHER",
                TiDBPerformanceHintFeedbackManager.migrateLegacyKey("R",
                        "PLAIN_TOPN|HINT_FAMILY=INDEX|HINT_TYPE=USE_INDEX|DEFAULT_JOIN=JOIN_OTHER"));
    }

    private static TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness(String baseSql,
            CounterfactualCandidate candidate, TiDBPerformanceHintOracleUtils.PlanSummary defaultPlan,
            TiDBPerformanceHintOracleUtils.PlanSummary alternativePlan) {
        TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness = new TiDBPerformanceHintOracleUtils.PerformanceHintWitness(
                baseSql, candidate.getAlternativeSql(), candidate, defaultPlan, alternativePlan,
                List.of("plan_keys_changed"), true);
        witness.setSkeletonKey(TiDBPerformanceHintOracleUtils.buildSkeletonKey(baseSql, 1));
        return witness;
    }

    private static TiDBPerformanceHintOracleUtils.PlanSummary plan(double cost, String id, String accessObject) {
        return TiDBPerformanceHintOracleUtils.parsePlan("id\testRows\testCost\ttask\taccess object\toperator info\n"
                + id + "\t10\t" + cost + "\troot\t" + accessObject + "\t");
    }
}
