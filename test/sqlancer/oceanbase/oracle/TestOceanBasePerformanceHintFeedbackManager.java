package sqlancer.oceanbase.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sqlancer.Randomly;
import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.oceanbase.OceanBaseOptions;

public class TestOceanBasePerformanceHintFeedbackManager {

    @TempDir
    Path tempDir;

    @Test
    public void testDominantRootCauseIsScopedToSkeleton() throws Exception {
        Path stateDirectory = tempDir.resolve("feedback");
        OceanBasePerformanceHintFeedbackManager manager = OceanBasePerformanceHintFeedbackManager
                .forStateDirectory(stateDirectory);
        OceanBaseOptions options = new OceanBaseOptions();
        options.enablePerformanceFeedbackDedup = true;
        options.performanceFeedbackSkeletonThreshold = 2;
        options.performanceFeedbackRootCauseSkeletonShareThreshold = 0.7;
        options.performanceFeedbackRootCauseSkipMultiplier = 1.5;

        manager.recordWitness(witness("skeleton-a", "cause;a", "signature-a1"), options);
        manager.recordWitness(witness("skeleton-a", "cause;b", "signature-a2"), options);
        for (int i = 0; i < 10; i++) {
            manager.recordWitness(witness("skeleton-b", "cause;c", "signature-b"), options);
        }

        OceanBasePerformanceHintFeedbackManager.SkeletonDecision decision = manager.evaluateSkeleton("skeleton-a",
                new Randomly(1), options);

        assertEquals(1.0 / 3.0, decision.getSkipProbability(), 0.000001);
        assertEquals(2L, decision.getHistoricalCount());
        assertTrue(Files.readString(stateDirectory.resolve("feedback-state.tsv")).contains("SRO\t"));
    }

    private static OceanBasePerformanceHintOracleUtils.PerformanceHintWitness witness(String skeletonKey,
            String rootCauseKey, String signatureKey) {
        OceanBasePerformanceHintOracleUtils.PlanSummary plan = OceanBasePerformanceHintOracleUtils
                .parsePlan("{\"OPERATOR\":\"TABLE FULL SCAN\",\"NAME\":\"t0\",\"EST.ROWS\":10,\"EST.TIME(us)\":10}");
        CounterfactualCandidate candidate = CounterfactualCandidate.hint("SELECT /*+ FULL(t0) */ t0.c0 FROM t0",
                "ACCESS", "FULL", "FULL(t0)", List.of("t0"), List.of());
        OceanBasePerformanceHintOracleUtils.PerformanceHintWitness witness = new OceanBasePerformanceHintOracleUtils.PerformanceHintWitness(
                "SELECT t0.c0 FROM t0", candidate.getAlternativeSql(), candidate, plan, plan,
                List.of("operators_changed"));
        witness.setFeedbackKeys(skeletonKey, signatureKey, rootCauseKey);
        return witness;
    }
}
