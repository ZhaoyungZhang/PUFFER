package sqlancer.cockroachdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sqlancer.common.perfhint.CostRuntimeObservation;
import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.PerformanceNineCellCategory;
import sqlancer.common.perfhint.PerformanceHintRootCauseClassifier.RootCauseClassification;
import sqlancer.common.perfhint.PerformanceNineCellClassifier;
import sqlancer.common.perfhint.PerformanceNineCellOptions;
import sqlancer.common.perfhint.RewrittenQuery;
import sqlancer.cockroachdb.CockroachDBOptions;

public class TestCockroachDBPerformanceHintFeedbackManager {

    @TempDir
    Path tempDir;

    @Test
    public void testFeedbackStateSummaryFilesAreWritten() throws Exception {
        Path stateDir = tempDir.resolve("persist");
        CockroachDBPerformanceHintFeedbackManager manager = CockroachDBPerformanceHintFeedbackManager
                .forStateDirectory(stateDir);
        CockroachDBOptions options = new CockroachDBOptions();
        options.enablePerformanceFeedbackDedup = true;

        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness = buildWitness("idx_a", 120.0, 12.0, 31.0,
                2.2);
        manager.recordWitness(witness, options);
        manager.checkpointAfterReportDrain();

        assertTrue(Files.exists(stateDir.resolve("perfhint_feedback_state.tsv")));
        assertTrue(Files.exists(stateDir.resolve("perfhint_feedback_summary.json")));
        assertTrue(Files.exists(stateDir.resolve("perfhint_feedback_events.jsonl")));
        String summary = Files.readString(stateDir.resolve("perfhint_feedback_summary.json"));
        assertTrue(summary.contains("uniqueRootCauseCount"));
        assertTrue(summary.contains("topRootCauses"));
    }

    @Test
    public void testRootCauseHardCapSuppressesDistinctSignaturesAfterCap() throws Exception {
        CockroachDBPerformanceHintFeedbackManager manager = CockroachDBPerformanceHintFeedbackManager
                .forStateDirectory(tempDir.resolve("root-cause-cap"));
        CockroachDBOptions options = new CockroachDBOptions();
        options.enablePerformanceFeedbackDedup = true;
        options.performanceFeedbackSignatureHardCap = 2;
        options.performanceFeedbackRootCauseHardCap = 2;
        options.enablePerformanceFeedbackRepresentativeReplacement = false;

        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness first = buildWitness("idx_a", 120.0, 12.0, 31.0,
                2.2);
        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness second = buildWitness("idx_b", 120.0, 12.0, 31.0,
                2.2);
        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness third = buildWitness("idx_c", 120.0, 12.0, 31.0,
                2.2);

        assertEquals(first.getRootCauseSignatureKey(), second.getRootCauseSignatureKey());
        assertEquals(first.getRootCauseSignatureKey(), third.getRootCauseSignatureKey());

        assertTrue(manager.recordWitness(first, options).shouldWriteReport());
        assertTrue(manager.recordWitness(second, options).shouldWriteReport());
        CockroachDBPerformanceHintFeedbackManager.WitnessDecision thirdDecision = manager.recordWitness(third, options);
        assertFalse(thirdDecision.shouldWriteReport());
        assertEquals("SUPPRESSED_ROOT_CAUSE_DUPLICATE", thirdDecision.getSelectionReason());
    }

    @Test
    public void testNoveltySnapshotDropsAfterObservation() throws Exception {
        CockroachDBPerformanceHintFeedbackManager manager = CockroachDBPerformanceHintFeedbackManager
                .forStateDirectory(tempDir.resolve("novelty"));
        CockroachDBOptions options = new CockroachDBOptions();
        options.enablePerformanceFeedbackDedup = true;

        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness = buildWitness("idx_a", 120.0, 12.0, 31.0,
                2.2);
        CockroachDBPerformanceHintFeedbackManager.NoveltySnapshot before = manager.estimateNoveltySnapshot(witness,
                options);
        assertEquals(1.0, before.getRootCauseNovelty());

        manager.recordWitness(witness, options);
        CockroachDBPerformanceHintFeedbackManager.NoveltySnapshot after = manager.estimateNoveltySnapshot(witness,
                options);
        assertTrue(after.getRootCauseNovelty() < 1.0);
    }

    @Test
    public void testDifferentNineCellCategoriesDoNotShareRootCauseCap() throws Exception {
        CockroachDBPerformanceHintFeedbackManager manager = CockroachDBPerformanceHintFeedbackManager
                .forStateDirectory(tempDir.resolve("taxonomy-scope"));
        CockroachDBOptions options = new CockroachDBOptions();
        options.enablePerformanceFeedbackDedup = true;
        options.performanceFeedbackSignatureHardCap = 4;
        options.performanceFeedbackRootCauseHardCap = 1;

        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness faster = buildWitness("idx_a", 120.0, 12.0, 31.0,
                2.2);
        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness slower = buildWitness("idx_a", 120.0, 12.0, 2.2,
                31.0);

        assertTrue(manager.recordWitness(faster, options).shouldWriteReport());
        assertTrue(manager.recordWitness(slower, options).shouldWriteReport());
    }

    @Test
    public void testParseRootCauseCountsSupportsLegacyAndCurrentFormats() {
        String rootCauseKey = "PLAIN_TOPN|HINT_FAMILY=JOIN|HINT_TYPE=MERGE_JOIN|nineCell=D";
        String legacyEncoded = Base64.getUrlEncoder()
                .encodeToString((rootCauseKey + "=3").getBytes(StandardCharsets.UTF_8));
        String currentEncoded = Base64.getUrlEncoder().encodeToString(
                (Base64.getUrlEncoder().encodeToString(rootCauseKey.getBytes(StandardCharsets.UTF_8)) + ":5")
                        .getBytes(StandardCharsets.UTF_8));

        Map<String, Long> legacyCounts = assertDoesNotThrow(
                () -> CockroachDBPerformanceHintFeedbackManager.parseRootCauseCounts(legacyEncoded));
        Map<String, Long> currentCounts = assertDoesNotThrow(
                () -> CockroachDBPerformanceHintFeedbackManager.parseRootCauseCounts(currentEncoded));

        assertEquals(3L, legacyCounts.get(rootCauseKey));
        assertEquals(5L, currentCounts.get(rootCauseKey));
    }

    @Test
    public void testLegacyHintFeedbackIsMigratedToGenericIdentity() throws Exception {
        Path stateDir = tempDir.resolve("legacy-hint-state");
        CockroachDBOptions options = new CockroachDBOptions();
        options.enablePerformanceFeedbackDedup = true;
        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness = buildWitness("idx_a", 120.0, 12.0, 31.0,
                2.2);
        String nineCellSuffix = "|nineCell=" + witness.getNineCellCategory().getCode();
        String legacyFamily = CockroachDBPerformanceHintOracleUtils.buildLegacyHintFamilyKey(witness) + nineCellSuffix;
        String legacySignature = CockroachDBPerformanceHintOracleUtils.buildLegacyHintPlanSignatureKey(witness)
                + nineCellSuffix;
        String legacyRootCause = CockroachDBPerformanceHintOracleUtils.buildLegacyHintRootCauseKey(witness)
                + nineCellSuffix;
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("perfhint_feedback_state.tsv"),
                String.join(System.lineSeparator(), "family\t" + encode(legacyFamily) + "\t4",
                        "signature\t" + encode(legacySignature) + "\t3\t0\t0.0\t0.0\t",
                        "root\t" + encode(legacyRootCause) + "\t5\t0\t0.0\t0.0\t0.0") + System.lineSeparator());

        CockroachDBPerformanceHintFeedbackManager manager = CockroachDBPerformanceHintFeedbackManager
                .forStateDirectory(stateDir);
        CockroachDBPerformanceHintFeedbackManager.WitnessDecision decision = manager.recordWitness(witness, options);
        manager.checkpointAfterReportDrain();

        assertEquals(5L, decision.getFamilyObservationCount());
        assertEquals(4L, decision.getSignatureObservationCount());
        assertEquals(6L, decision.getRootCauseObservationCount());
        String persisted = Files.readString(stateDir.resolve("perfhint_feedback_state.tsv"));
        assertTrue(persisted.contains(encode(witness.getPlanSignatureKey() + nineCellSuffix)));
        assertFalse(persisted.contains(encode(legacySignature)));
    }

    @Test
    public void testHintAndRewriteWithTheSamePlanShapeDoNotShareFeedbackIdentity() throws Exception {
        CockroachDBPerformanceHintFeedbackManager manager = CockroachDBPerformanceHintFeedbackManager
                .forStateDirectory(tempDir.resolve("cross-kind-dedup"));
        CockroachDBOptions options = new CockroachDBOptions();
        options.enablePerformanceFeedbackDedup = true;
        options.performanceFeedbackSignatureHardCap = 1;
        options.performanceFeedbackRootCauseHardCap = 1;
        options.enablePerformanceFeedbackRepresentativeReplacement = false;

        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness hint = buildWitness("idx_a", 120.0, 12.0, 31.0,
                2.2);
        CounterfactualCandidate rewriteCandidate = CounterfactualCandidate.queryRewrite(
                new RewrittenQuery("SELECT DISTINCT t0.c0 FROM t0 INNER JOIN t1 ON t0.c1 = t1.c1 "
                        + "WHERE t0.c1 >= 1 ORDER BY t0.c0 LIMIT 1", "algebra", "RULE_1", "test rewrite"),
                List.of("t0", "t1"), List.of("idx_a"));
        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness rewrite = new CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness(
                hint.getBaseQuery(), rewriteCandidate.getAlternativeSql(), rewriteCandidate, hint.getDefaultPlan(),
                hint.getHintedPlan(), hint.getPlanEvidence(), true);
        rewrite.setDefaultRuntime(hint.getDefaultRuntime());
        rewrite.setHintedRuntime(hint.getHintedRuntime());
        rewrite.setSkeletonKey(hint.getSkeletonKey());
        RootCauseClassification rootCause = CockroachDBPerformanceHintOracleUtils.classifyRootCause(rewrite);
        rewrite.setRootCauseIntentCategory(rootCause.getIntentCategory());
        rewrite.setRootCauseMechanismKey(rootCause.getMechanismKey());
        rewrite.setRootCauseSignatureKey(rootCause.getSignatureKey());
        rewrite.setRootCauseCategory(rootCause.getMechanismKey());
        rewrite.setFamilyKey(CockroachDBPerformanceHintOracleUtils.buildFamilyKey(rewrite));
        rewrite.setPlanSignatureKey(CockroachDBPerformanceHintOracleUtils.buildPlanSignatureKey(rewrite));
        rewrite.setNineCellCategory(hint.getNineCellCategory());

        assertFalse(hint.getPlanSignatureKey().equals(rewrite.getPlanSignatureKey()));
        assertFalse(hint.getRootCauseSignatureKey().equals(rewrite.getRootCauseSignatureKey()));
        assertTrue(manager.recordWitness(hint, options).shouldWriteReport());
        assertTrue(manager.recordWitness(rewrite, options).shouldWriteReport());
    }

    private static CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness buildWitness(String indexName,
            double defaultCost, double hintedCost, double defaultRuntimeMs, double hintedRuntimeMs) {
        String baseQuery = "SELECT DISTINCT t0.c0 FROM t0 INNER JOIN t1 ON t0.c1 = t1.c1 WHERE t0.c1 >= 1 ORDER BY t0.c0 LIMIT 1";
        String defaultPlanText = String.join(System.lineSeparator(), "top-k", " cost: " + defaultCost, " hash join",
                " scan t0@primary", "  stats: [rows=100, distinct(2)=1, null(2)=0]", "  spans: FULL SCAN");
        String hintedPlanText = String.join(System.lineSeparator(), "top-k", " cost: " + hintedCost, " hash join",
                " scan t0@" + indexName, "  flags: force-index=" + indexName,
                "  stats: [rows=10, distinct(2)=1, null(2)=0]", "  spans: [/1 - /10]");

        CockroachDBPerformanceHintOracleUtils.PlanSummary defaultPlan = CockroachDBPerformanceHintOracleUtils
                .parsePlan(defaultPlanText);
        CockroachDBPerformanceHintOracleUtils.PlanSummary hintedPlan = CockroachDBPerformanceHintOracleUtils
                .parsePlan(hintedPlanText);
        CounterfactualCandidate candidate = CounterfactualCandidate.hint(
                baseQuery.replace("t0", "t0@{FORCE_INDEX=" + indexName + "}"), "INDEX", "FORCE_INDEX",
                "t0@{FORCE_INDEX=" + indexName + "}", List.of("t0"), List.of(indexName));
        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness = new CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness(
                baseQuery, candidate.getAlternativeSql(), candidate, defaultPlan, hintedPlan,
                CockroachDBPerformanceHintOracleUtils.buildPlanEvidence(defaultPlan, hintedPlan, nineCellOptions()),
                true);
        witness.setDefaultRuntime(new CockroachDBPerformanceHintOracleUtils.RuntimeSummary(
                List.of(defaultRuntimeMs, defaultRuntimeMs, defaultRuntimeMs), 0));
        witness.setHintedRuntime(new CockroachDBPerformanceHintOracleUtils.RuntimeSummary(
                List.of(hintedRuntimeMs, hintedRuntimeMs, hintedRuntimeMs), 0));
        witness.setSkeletonKey(CockroachDBPerformanceHintOracleUtils.buildSkeletonKey(baseQuery, 2));
        RootCauseClassification rootCause = CockroachDBPerformanceHintOracleUtils.classifyRootCause(witness);
        witness.setRootCauseIntentCategory(rootCause.getIntentCategory());
        witness.setRootCauseMechanismKey(rootCause.getMechanismKey());
        witness.setRootCauseSignatureKey(rootCause.getSignatureKey());
        witness.setRootCauseCategory(rootCause.getMechanismKey());
        witness.setNineCellCategory(classifyObservation(defaultCost, hintedCost, defaultRuntimeMs, hintedRuntimeMs));
        witness.setFamilyKey(CockroachDBPerformanceHintOracleUtils.buildFamilyKey(witness));
        witness.setPlanSignatureKey(CockroachDBPerformanceHintOracleUtils.buildPlanSignatureKey(witness));
        witness.setNoveltyScore(1.0);
        witness.setRootCauseNoveltyScore(1.0);
        return witness;
    }

    private static PerformanceNineCellOptions nineCellOptions() {
        return new PerformanceNineCellOptions(true, 0.9, 1.1, 0.9, 1.3, 0.0, 1.0);
    }

    private static PerformanceNineCellCategory classifyObservation(double defaultCost, double hintedCost,
            double defaultRuntimeMs, double hintedRuntimeMs) {
        return PerformanceNineCellClassifier.classify(nineCellOptions(),
                new CostRuntimeObservation(defaultCost, hintedCost, defaultRuntimeMs, hintedRuntimeMs, defaultRuntimeMs,
                        hintedRuntimeMs, defaultRuntimeMs, hintedRuntimeMs, defaultRuntimeMs, hintedRuntimeMs, 0, 0,
                        List.of(defaultRuntimeMs, defaultRuntimeMs, defaultRuntimeMs),
                        List.of(hintedRuntimeMs, hintedRuntimeMs, hintedRuntimeMs)));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
