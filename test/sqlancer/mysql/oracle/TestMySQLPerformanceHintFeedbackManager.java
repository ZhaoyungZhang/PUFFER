package sqlancer.mysql.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sqlancer.Randomly;
import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.CostRuntimeObservation;
import sqlancer.common.perfhint.PerformanceNineCellCategory;
import sqlancer.common.perfhint.PerformanceNineCellClassifier;
import sqlancer.common.perfhint.PerformanceNineCellOptions;
import sqlancer.mysql.MySQLOptions;

public class TestMySQLPerformanceHintFeedbackManager {

    @TempDir
    Path tempDir;

    @Test
    public void testSkeletonSkipProbabilityGrowsAfterThreshold() throws Exception {
        MySQLPerformanceHintFeedbackManager manager = MySQLPerformanceHintFeedbackManager
                .forStateDirectory(tempDir.resolve("feedback"));
        MySQLOptions options = new MySQLOptions();
        options.enablePerformanceFeedbackDedup = true;
        options.performanceFeedbackSkeletonThreshold = 2;

        MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness = buildWitness(
                "SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1");

        manager.recordWitness(witness, options);
        manager.recordWitness(witness, options);
        MySQLPerformanceHintFeedbackManager.SkeletonDecision decision = manager
                .evaluateSkeleton(witness.getSkeletonKey(), new Randomly(1), options);

        assertTrue(decision.getSkipProbability() > 0.0);
    }

    @Test
    public void testWitnessHardCapSuppressesFurtherReports() throws Exception {
        MySQLPerformanceHintFeedbackManager manager = MySQLPerformanceHintFeedbackManager
                .forStateDirectory(tempDir.resolve("cap"));
        MySQLOptions options = new MySQLOptions();
        options.enablePerformanceFeedbackDedup = true;
        options.performanceFeedbackSignatureHardCap = 2;
        options.performanceFeedbackRootCauseHardCap = 4;

        MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness = buildWitness(
                "SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1");

        assertTrue(manager.recordWitness(witness, options).shouldWriteReport());
        assertTrue(manager.recordWitness(witness, options).shouldWriteReport());
        assertFalse(manager.recordWitness(witness, options).shouldWriteReport());
    }

    @Test
    public void testFeedbackStateSummaryFilesAreWritten() throws Exception {
        Path stateDir = tempDir.resolve("persist");
        MySQLPerformanceHintFeedbackManager manager = MySQLPerformanceHintFeedbackManager.forStateDirectory(stateDir);
        MySQLOptions options = new MySQLOptions();
        options.enablePerformanceFeedbackDedup = true;

        MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness = buildWitness(
                "SELECT DISTINCTROW t2.c1 AS ref0 FROM t0, t1, t2 ORDER BY t2.c1 ASC LIMIT 1");
        witness.setSkeletonKey(MySQLPerformanceHintOracleUtils.buildSkeletonKey(witness.getBaseQuery(), 3));
        witness.setFamilyKey(MySQLPerformanceHintOracleUtils.buildFamilyKey(witness));
        witness.setPlanSignatureKey(MySQLPerformanceHintOracleUtils.buildPlanSignatureKey(witness));
        witness.setRootCauseSignatureKey(MySQLPerformanceHintOracleUtils.buildRootCauseSignatureKey(witness));

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
        MySQLPerformanceHintFeedbackManager manager = MySQLPerformanceHintFeedbackManager
                .forStateDirectory(tempDir.resolve("root-cause-cap"));
        MySQLOptions options = new MySQLOptions();
        options.enablePerformanceFeedbackDedup = true;
        options.performanceFeedbackSignatureHardCap = 2;
        options.performanceFeedbackRootCauseHardCap = 2;

        MySQLPerformanceHintOracleUtils.PerformanceHintWitness first = buildWitness(
                "SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1");
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness second = buildWitness(
                "SELECT DISTINCTROW t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1");
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness third = buildWitness(
                "SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 3");

        assertTrue(manager.recordWitness(first, options).shouldWriteReport());
        assertTrue(manager.recordWitness(second, options).shouldWriteReport());
        MySQLPerformanceHintFeedbackManager.WitnessDecision thirdDecision = manager.recordWitness(third, options);
        assertFalse(thirdDecision.shouldWriteReport());
        assertEquals("SUPPRESSED_ROOT_CAUSE_DUPLICATE", thirdDecision.getSelectionReason());
    }

    @Test
    public void testLegacyStateWithoutRootCauseRowsStillLoads() throws Exception {
        Path stateDir = tempDir.resolve("legacy");
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("perfhint_feedback_state.tsv"), "META\t1\t1\t0\t0\n");
        MySQLPerformanceHintFeedbackManager manager = MySQLPerformanceHintFeedbackManager.forStateDirectory(stateDir);
        MySQLOptions options = new MySQLOptions();
        options.enablePerformanceFeedbackDedup = true;

        MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness = buildWitness(
                "SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1");
        assertTrue(manager.recordWitness(witness, options).shouldWriteReport());
    }

    @Test
    public void testNoveltySnapshotReportsRootCauseNovelty() throws Exception {
        MySQLPerformanceHintFeedbackManager manager = MySQLPerformanceHintFeedbackManager
                .forStateDirectory(tempDir.resolve("novelty"));
        MySQLOptions options = new MySQLOptions();
        options.enablePerformanceFeedbackDedup = true;

        MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness = buildWitness(
                "SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1");
        MySQLPerformanceHintFeedbackManager.NoveltySnapshot noveltyBefore = manager.estimateNoveltySnapshot(witness,
                options);
        assertEquals(1.0, noveltyBefore.getRootCauseNovelty());

        manager.recordWitness(witness, options);
        MySQLPerformanceHintFeedbackManager.NoveltySnapshot noveltyAfter = manager.estimateNoveltySnapshot(witness,
                options);
        assertTrue(noveltyAfter.getRootCauseNovelty() < 1.0);
    }

    @Test
    public void testDifferentNineCellCategoriesDoNotShareRootCauseCap() throws Exception {
        MySQLPerformanceHintFeedbackManager manager = MySQLPerformanceHintFeedbackManager
                .forStateDirectory(tempDir.resolve("taxonomy-scope"));
        MySQLOptions options = new MySQLOptions();
        options.enablePerformanceFeedbackDedup = true;
        options.performanceFeedbackSignatureHardCap = 4;
        options.performanceFeedbackRootCauseHardCap = 1;

        MySQLPerformanceHintOracleUtils.PerformanceHintWitness fasterWitness = buildWitness(
                "SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1");
        fasterWitness.setNineCellCategory(classifyObservation(120.0, 12.0, 31.0, 2.2));
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness slowerWitness = buildWitness(
                "SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1");
        slowerWitness.setNineCellCategory(classifyObservation(120.0, 12.0, 2.2, 31.0));

        assertTrue(manager.recordWitness(fasterWitness, options).shouldWriteReport());
        assertTrue(manager.recordWitness(slowerWitness, options).shouldWriteReport());
    }

    @Test
    public void testLegacyHintFeedbackKeysMigrateToGenericIdentity() throws Exception {
        Path stateDir = tempDir.resolve("legacy-hint-identity");
        Files.createDirectories(stateDir);
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness = buildWitness(
                "SELECT DISTINCT t0.c0 AS ref0 FROM t0, t1 ORDER BY t0.c0 DESC LIMIT 1");
        String legacyFamily = MySQLPerformanceHintOracleUtils.buildLegacyHintFamilyKey(witness);
        String legacySignature = MySQLPerformanceHintOracleUtils.buildLegacyHintPlanSignatureKey(witness);
        String legacyRootCause = MySQLPerformanceHintOracleUtils.buildLegacyHintRootCauseSignatureKey(witness);
        String state = "META\t0\t0\t0\t0\t0\n" + "F\t" + encode(legacyFamily) + "\t4\n" + "P\t"
                + encode(legacySignature) + "\t5\t1\t2.0\t3.0\n" + "R\t" + encode(legacyRootCause)
                + "\t6\t1\t2.0\t1.0\t3.0\n";
        Files.writeString(stateDir.resolve("perfhint_feedback_state.tsv"), state);

        MySQLPerformanceHintFeedbackManager manager = MySQLPerformanceHintFeedbackManager.forStateDirectory(stateDir);
        MySQLOptions options = new MySQLOptions();
        options.enablePerformanceFeedbackDedup = true;
        MySQLPerformanceHintFeedbackManager.WitnessDecision decision = manager.recordWitness(witness, options);
        manager.checkpointAfterReportDrain();

        assertEquals(5L, decision.getFamilyObservationCount());
        assertEquals(6L, decision.getSignatureObservationCount());
        assertEquals(7L, decision.getRootCauseObservationCount());
        String persisted = Files.readString(stateDir.resolve("perfhint_feedback_state.tsv"));
        assertFalse(persisted.contains(encode(legacyFamily)));
        assertFalse(persisted.contains(encode(legacySignature)));
        assertFalse(persisted.contains(encode(legacyRootCause)));
        String nineCellSuffix = "|nineCell=" + witness.getNineCellCategory().getCode();
        assertTrue(persisted.contains(encode(witness.getFamilyKey() + nineCellSuffix)));
        assertTrue(persisted.contains(encode(witness.getPlanSignatureKey() + nineCellSuffix)));
        assertTrue(persisted.contains(encode(witness.getRootCauseSignatureKey() + nineCellSuffix)));
    }

    private static PerformanceNineCellCategory classifyObservation(double defaultCost, double hintedCost,
            double defaultRuntimeMs, double hintedRuntimeMs) {
        PerformanceNineCellOptions taxonomyOptions = new PerformanceNineCellOptions(true, 0.9, 1.1, 0.9, 1.3, 0.0, 1.0);
        return PerformanceNineCellClassifier.classify(taxonomyOptions,
                new CostRuntimeObservation(defaultCost, hintedCost, defaultRuntimeMs, hintedRuntimeMs, defaultRuntimeMs,
                        hintedRuntimeMs, defaultRuntimeMs, hintedRuntimeMs, defaultRuntimeMs, hintedRuntimeMs, 0, 0,
                        List.of(defaultRuntimeMs, defaultRuntimeMs, defaultRuntimeMs),
                        List.of(hintedRuntimeMs, hintedRuntimeMs, hintedRuntimeMs)));
    }

    private static MySQLPerformanceHintOracleUtils.PerformanceHintWitness buildWitness(String baseQuery) {
        MySQLPerformanceHintOracleUtils.PlanSummary defaultPlan = MySQLPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"120\"},\"duplicates_removal\":{\"using_filesort\":true,"
                        + "\"table\":{\"access_type\":\"ALL\"}},\"ordering_operation\":{\"using_temporary_table\":true,"
                        + "\"operation\":\"Temporary table with deduplication\"}}}");
        MySQLPerformanceHintOracleUtils.PlanSummary hintedPlan = MySQLPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"12\"},\"nested_loop\":{\"table\":{\"access_type\":\"range\","
                        + "\"key\":\"idx_ref\"},\"operation\":\"Nested loop inner join\"}}}");
        String counterfactualQuery = "SELECT /*+ NO_BNL(t0, t1) */ " + baseQuery.substring("SELECT ".length());
        CounterfactualCandidate candidate = CounterfactualCandidate.hint(counterfactualQuery, "TABLE", "NO_BNL",
                "NO_BNL(t0, t1)", List.of("t0", "t1"), List.of());
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness = new MySQLPerformanceHintOracleUtils.PerformanceHintWitness(
                baseQuery, counterfactualQuery, candidate, defaultPlan, hintedPlan,
                MySQLPerformanceHintOracleUtils.buildPlanEvidence(defaultPlan, hintedPlan, 1.0), true);
        witness.setDefaultRuntime(new MySQLPerformanceHintOracleUtils.RuntimeSummary(List.of(30.0, 32.0, 31.0)));
        witness.setHintedRuntime(new MySQLPerformanceHintOracleUtils.RuntimeSummary(List.of(2.0, 2.5, 2.2)));
        witness.setNineCellCategory(classifyObservation(120.0, 12.0, 31.0, 2.2));
        witness.setSkeletonKey(MySQLPerformanceHintOracleUtils.buildSkeletonKey(baseQuery, 2));
        witness.setRootCauseCategory(MySQLPerformanceHintOracleUtils.detectRootCauseCategory(baseQuery));
        witness.setFamilyKey(MySQLPerformanceHintOracleUtils.buildFamilyKey(witness));
        witness.setPlanSignatureKey(MySQLPerformanceHintOracleUtils.buildPlanSignatureKey(witness));
        witness.setRootCauseSignatureKey(MySQLPerformanceHintOracleUtils.buildRootCauseSignatureKey(witness));
        witness.setNoveltyScore(1.0);
        witness.setRootCauseNoveltyScore(1.0);
        return witness;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
