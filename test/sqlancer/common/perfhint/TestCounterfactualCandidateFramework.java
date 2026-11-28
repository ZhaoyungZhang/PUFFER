package sqlancer.common.perfhint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.cockroachdb.CockroachDBOptions;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.ColumnSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.IndexSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.TableSummary;
import sqlancer.mariadb.MariaDBOptions;
import sqlancer.mysql.MySQLOptions;
import sqlancer.oceanbase.OceanBaseOptions;
import sqlancer.tidb.TiDBOptions;

public class TestCounterfactualCandidateFramework {

    @Test
    public void testEndpointSelectorsDoNotAdvanceSeededRandomness() {
        assertEndpointDoesNotConsumeRandomness(1.0, CounterfactualMutationKind.HINT);
        assertEndpointDoesNotConsumeRandomness(0.0, CounterfactualMutationKind.QUERY_REWRITE);
    }

    @Test
    public void testIntermediateSelectorIsStableForFixedSeed() {
        List<CounterfactualMutationKind> first = selectSequence(37L, 20);
        List<CounterfactualMutationKind> second = selectSequence(37L, 20);

        assertEquals(first, second);
        assertTrue(first.contains(CounterfactualMutationKind.HINT));
        assertTrue(first.contains(CounterfactualMutationKind.QUERY_REWRITE));
    }

    @Test
    public void testInvalidHintProbabilityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CounterfactualStrategySelector(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new CounterfactualStrategySelector(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new CounterfactualStrategySelector(-0.001));
        assertThrows(IllegalArgumentException.class, () -> new CounterfactualStrategySelector(1.001));
    }

    @Test
    public void testCommandLineValidationRejectsInvalidHintProbabilityAtStartup() {
        for (Object options : List.of(new MySQLOptions(), new MariaDBOptions(), new TiDBOptions(),
                new OceanBaseOptions(), new CockroachDBOptions())) {
            assertThrows(ParameterException.class, () -> parseProbability(options, "NaN"));
            assertThrows(ParameterException.class, () -> parseProbability(options, "Infinity"));
            assertThrows(ParameterException.class, () -> parseProbability(options, "-0.01"));
            assertThrows(ParameterException.class, () -> parseProbability(options, "1.01"));
        }
        assertDoesParseProbability(new MySQLOptions(), "0.0");
        assertDoesParseProbability(new MySQLOptions(), "1.0");
    }

    @Test
    public void testSchedulerFiltersInvalidSqlDeduplicatesAndHonorsBudget() {
        String baseSql = "SELECT 1";
        List<CounterfactualCandidate> hints = List.of(hint("   ", "empty"), hint(baseSql, "base"),
                hint("SELECT /*+ H1 */ 1", "h1"), hint("SELECT /*+ H1 */ 1", "h1-duplicate"),
                hint("SELECT /*+ H2 */ 1", "h2"));

        List<ScheduledCounterfactualCandidate> scheduled = CounterfactualCandidateScheduler.schedule(baseSql, 2,
                new CounterfactualStrategySelector(1.0), new Randomly(1), hints, List.of(rewrite("SELECT 2", "r1")));

        assertEquals(List.of("SELECT /*+ H1 */ 1", "SELECT /*+ H2 */ 1"), alternativeSql(scheduled));
        assertEquals(2, scheduled.size());
        assertTrue(scheduled.stream()
                .allMatch(candidate -> candidate.getFallbackReason() == CounterfactualFallbackReason.NONE));
    }

    @Test
    public void testSchedulerFallsBackWhenRequestedPoolIsEmpty() {
        List<ScheduledCounterfactualCandidate> rewriteToHint = CounterfactualCandidateScheduler.schedule("SELECT 1", 1,
                new CounterfactualStrategySelector(0.0), new Randomly(3), List.of(hint("SELECT /*+ H */ 1", "h")),
                List.of());
        assertEquals(CounterfactualMutationKind.QUERY_REWRITE, rewriteToHint.get(0).getRequestedStrategy());
        assertEquals(CounterfactualMutationKind.HINT, rewriteToHint.get(0).getActualMutationKind());
        assertEquals(CounterfactualFallbackReason.QUERY_REWRITE_POOL_EMPTY, rewriteToHint.get(0).getFallbackReason());

        List<ScheduledCounterfactualCandidate> hintToRewrite = CounterfactualCandidateScheduler.schedule("SELECT 1", 1,
                new CounterfactualStrategySelector(1.0), new Randomly(3), List.of(), List.of(rewrite("SELECT 2", "r")));
        assertEquals(CounterfactualMutationKind.HINT, hintToRewrite.get(0).getRequestedStrategy());
        assertEquals(CounterfactualMutationKind.QUERY_REWRITE, hintToRewrite.get(0).getActualMutationKind());
        assertEquals(CounterfactualFallbackReason.HINT_POOL_EMPTY, hintToRewrite.get(0).getFallbackReason());
    }

    @Test
    public void testBatchSchedulerMeasuresAllSixteenProviderRewritesInProviderOrder() {
        List<CounterfactualCandidate> rewrites = new ArrayList<>();
        List<String> expectedSql = new ArrayList<>();
        for (int index = 1; index <= 16; index++) {
            String sql = "SELECT " + (index + 1);
            rewrites.add(rewrite(sql, "r" + index));
            expectedSql.add(sql);
        }
        List<ScheduledCounterfactualCandidate> scheduled = CounterfactualCandidateScheduler.scheduleBatch("SELECT 1",
                16, CounterfactualMutationKind.QUERY_REWRITE, List.of(hint("SELECT /*+ H */ 1", "h")), rewrites);

        assertEquals(expectedSql, alternativeSql(scheduled));
        assertEquals(16, scheduled.size());
        for (ScheduledCounterfactualCandidate candidate : scheduled) {
            assertEquals(CounterfactualMutationKind.QUERY_REWRITE, candidate.getRequestedStrategy());
            assertEquals(CounterfactualMutationKind.QUERY_REWRITE, candidate.getActualMutationKind());
            assertEquals(CounterfactualFallbackReason.NONE, candidate.getFallbackReason());
        }
    }

    @Test
    public void testBatchSchedulerFallsBackOnlyWhenTheRequestedBatchIsEmpty() {
        List<ScheduledCounterfactualCandidate> scheduled = CounterfactualCandidateScheduler.scheduleBatch("SELECT 1",
                16, CounterfactualMutationKind.QUERY_REWRITE,
                List.of(hint("SELECT /*+ H1 */ 1", "h1"), hint("SELECT /*+ H2 */ 1", "h2")), List.of());

        assertEquals(List.of("SELECT /*+ H1 */ 1", "SELECT /*+ H2 */ 1"), alternativeSql(scheduled));
        for (ScheduledCounterfactualCandidate candidate : scheduled) {
            assertEquals(CounterfactualMutationKind.QUERY_REWRITE, candidate.getRequestedStrategy());
            assertEquals(CounterfactualMutationKind.HINT, candidate.getActualMutationKind());
            assertEquals(CounterfactualFallbackReason.QUERY_REWRITE_POOL_EMPTY, candidate.getFallbackReason());
        }
    }

    @Test
    public void testSchedulerDeduplicatesAcrossPoolsAfterSelection() {
        List<ScheduledCounterfactualCandidate> scheduled = CounterfactualCandidateScheduler.schedule("SELECT 1", 2,
                new CounterfactualStrategySelector(0.0), new Randomly(4),
                List.of(hint("SELECT 2", "h-duplicate"), hint("SELECT 3", "h-unique")),
                List.of(rewrite("SELECT 2", "r")));

        assertEquals(List.of("SELECT 2", "SELECT 3"), alternativeSql(scheduled));
        assertEquals(CounterfactualFallbackReason.QUERY_REWRITE_POOL_EMPTY, scheduled.get(1).getFallbackReason());
    }

    @Test
    public void testSchedulerDoesNotSampleAfterAllPoolsAreExhausted() {
        Randomly randomly = new Randomly(99L);
        CounterfactualCandidateScheduler.schedule("SELECT 1", 16, new CounterfactualStrategySelector(0.5), randomly,
                List.of(hint("SELECT /*+ H */ 1", "h")), List.of());
        int actualNextValue = randomly.getInteger(0, 1_000_000);

        Randomly baseline = new Randomly(99L);
        baseline.getInteger(0, 1_000_000); // exactly the one scheduled candidate slot
        int expectedNextValue = baseline.getInteger(0, 1_000_000);
        assertEquals(expectedNextValue, actualNextValue);
    }

    @Test
    public void testRewritePoolSelectionIsStableAndWithoutReplacement() {
        List<ScheduledCounterfactualCandidate> first = CounterfactualCandidateScheduler.schedule("SELECT 1", 3,
                new CounterfactualStrategySelector(0.0), new Randomly(71L), List.of(),
                List.of(rewrite("SELECT 2", "r2"), rewrite("SELECT 3", "r3"), rewrite("SELECT 4", "r4")));
        List<ScheduledCounterfactualCandidate> second = CounterfactualCandidateScheduler.schedule("SELECT 1", 3,
                new CounterfactualStrategySelector(0.0), new Randomly(71L), List.of(),
                List.of(rewrite("SELECT 2", "r2"), rewrite("SELECT 3", "r3"), rewrite("SELECT 4", "r4")));

        assertEquals(alternativeSql(first), alternativeSql(second));
        assertEquals(3, alternativeSql(first).stream().distinct().count());
    }

    @Test
    public void testRewriteProviderAndEquivalenceOracleAdmission() {
        QueryRewriteRequest request = new QueryRewriteRequest("SELECT 1", "mysql", 16,
                new QueryRewriteSchemaSummary(List.of()));
        PerformanceQueryRewriteProvider provider = ignored -> List.of(rewriteQuery("SELECT 2", "accept"),
                rewriteQuery("SELECT 3", "reject"));
        RewriteEquivalenceOracle oracle = (ignored, rewritten) -> "accept".equals(rewritten.getRuleId())
                ? RewriteEquivalenceOracle.Verdict.ACCEPT : RewriteEquivalenceOracle.Verdict.REJECT;

        List<CounterfactualCandidate> admitted = CounterfactualCandidateScheduler
                .collectValidatedRewriteCandidates(request, provider, oracle, List.of("t0"), List.of("i0"));

        assertEquals(1, admitted.size());
        assertEquals(CounterfactualMutationKind.QUERY_REWRITE, admitted.get(0).getKind());
        assertEquals("SELECT 2", admitted.get(0).getAlternativeSql());
        assertEquals("QUERY_REWRITE|algebra|accept", admitted.get(0).getMutationIdentity());
        assertTrue(CounterfactualCandidateScheduler.collectValidatedRewriteCandidates(request,
                PerformanceQueryRewriteProvider.noOp(), RewriteEquivalenceOracle.unavailable(), List.of(), List.of())
                .isEmpty());
        assertTrue(CounterfactualCandidateScheduler.collectValidatedRewriteCandidates(request, provider,
                RewriteEquivalenceOracle.unavailable(), List.of(), List.of()).isEmpty());
    }

    @Test
    public void testSchemaSummaryAndCandidateAreImmutable() {
        List<String> indexedColumns = new ArrayList<>(List.of("c0"));
        IndexSummary index = new IndexSummary("i0", indexedColumns);
        QueryRewriteSchemaSummary summary = new QueryRewriteSchemaSummary(
                List.of(new TableSummary("t0", List.of(new ColumnSummary("c0", "INT")), List.of(index))));
        CounterfactualCandidate candidate = CounterfactualCandidate.hint("SELECT /*+ H */ 1", "join", "BNL", "BNL(t0)",
                List.of("t0"), List.of("i0"));

        indexedColumns.add("c1");
        assertEquals(List.of("c0"), summary.getTables().get(0).getIndexes().get(0).getOrderedColumns());
        assertThrows(UnsupportedOperationException.class, () -> candidate.getTargetTables().add("t1"));
        assertFalse(candidate.getMutationIdentity().isEmpty());
    }

    @Test
    public void testMySQLDefaultHintProbabilityIsHalf() {
        assertEquals(0.5, new MySQLOptions().performanceCounterfactualHintProbability);
    }

    @Test
    public void testMySQLWeTuneCommandLineValidation() {
        assertThrows(ParameterException.class, () -> JCommander.newBuilder().addObject(new MySQLOptions()).build()
                .parse("--perf-query-rewrite-provider=unknown"));
        assertThrows(ParameterException.class, () -> JCommander.newBuilder().addObject(new MySQLOptions()).build()
                .parse("--perf-wetune-static-timeout-ms=0"));
        JCommander.newBuilder().addObject(new MySQLOptions()).build()
                .parse("--perf-query-rewrite-provider=wetune-static", "--perf-wetune-static-timeout-ms=100");
    }

    private static void assertEndpointDoesNotConsumeRandomness(double hintProbability,
            CounterfactualMutationKind expectedKind) {
        CounterfactualStrategySelector selector = new CounterfactualStrategySelector(hintProbability);
        Randomly randomly = new Randomly(1234L);
        assertEquals(expectedKind, selector.select(randomly));
        int actualNextValue = randomly.getInteger(0, 1_000_000);

        Randomly baseline = new Randomly(1234L);
        int expectedNextValue = baseline.getInteger(0, 1_000_000);
        assertEquals(expectedNextValue, actualNextValue);
    }

    private static List<CounterfactualMutationKind> selectSequence(long seed, int count) {
        CounterfactualStrategySelector selector = new CounterfactualStrategySelector(0.5);
        Randomly randomly = new Randomly(seed);
        List<CounterfactualMutationKind> sequence = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sequence.add(selector.select(randomly));
        }
        return sequence;
    }

    private static CounterfactualCandidate hint(String sql, String hintType) {
        return CounterfactualCandidate.hint(sql, "hint-family", hintType, "hint(" + hintType + ")", List.of("t0"),
                List.of("i0"));
    }

    private static CounterfactualCandidate rewrite(String sql, String ruleId) {
        return CounterfactualCandidate.queryRewrite(rewriteQuery(sql, ruleId), List.of("t0"), List.of("i0"));
    }

    private static RewrittenQuery rewriteQuery(String sql, String ruleId) {
        return new RewrittenQuery(sql, "algebra", ruleId, "test rewrite");
    }

    private static List<String> alternativeSql(List<ScheduledCounterfactualCandidate> candidates) {
        return candidates.stream().map(candidate -> candidate.getCandidate().getAlternativeSql())
                .collect(Collectors.toList());
    }

    private static void parseProbability(Object options, String probability) {
        JCommander.newBuilder().addObject(options).build()
                .parse("--perf-counterfactual-hint-probability=" + probability);
    }

    private static void assertDoesParseProbability(Object options, String probability) {
        parseProbability(options, probability);
    }
}
