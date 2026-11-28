package sqlancer.cockroachdb.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBTable;
import sqlancer.cockroachdb.gen.CockroachDBPerformanceHintGenerator.JoinSpec;
import sqlancer.cockroachdb.gen.CockroachDBPerformanceHintGenerator.JoinType;
import sqlancer.common.schema.TableIndex;

public class TestCockroachDBPerformanceHintGenerator {

    @Test
    public void newJoinTypesAndPredicatesGetOnlyApplicableAlgorithms() {
        CockroachDBTable left = new CockroachDBTable("t0", List.of(), List.of(TableIndex.create("idx_a")), false);
        CockroachDBTable right = new CockroachDBTable("t1", List.of(), List.of(TableIndex.create("idx_b")), false);
        for (JoinType type : JoinType.values()) {
            for (boolean equality : List.of(false, true)) {
                JoinSpec join = new JoinSpec(0, type, "t0", "t1", true, type + " JOIN", equality, true, "t0.c0",
                        "t1.c0");
                CockroachDBPerformanceHintGenerator.QueryShape shape = new CockroachDBPerformanceHintGenerator.QueryShape(
                        2, false, false, false, false, List.of(join), type == JoinType.INNER, type == JoinType.LEFT,
                        Set.of());
                Set<String> algorithms = CockroachDBPerformanceHintGenerator
                        .generateCandidates(List.of(left, right), shape, new Randomly(1), 2, 32).stream()
                        .filter(c -> c.getHintFamily().equals("JOIN"))
                        .map(CockroachDBPerformanceHintGenerator.HintedQueryCandidate::getHintSql)
                        .collect(Collectors.toSet());
                assertTrue(algorithms.contains(type + " HASH JOIN"));
                assertEquals(equality, algorithms.contains(type + " MERGE JOIN"));
                assertEquals(equality && (type == JoinType.INNER || type == JoinType.LEFT),
                        algorithms.contains(type + " LOOKUP JOIN"));
            }
        }
    }

    @Test
    public void testGenerateCandidatesAreDeduplicatedAndCarryJoinOrdinals() {
        CockroachDBTable t0 = new CockroachDBTable("t0", List.of(),
                List.of(TableIndex.create("idx_a"), TableIndex.create("idx_b"), TableIndex.create("idx_b")), false);
        CockroachDBTable t1 = new CockroachDBTable("t1", List.of(),
                List.of(TableIndex.create("idx_c"), TableIndex.create("idx_d")), false);
        CockroachDBTable t2 = new CockroachDBTable("t2", List.of(), List.of(TableIndex.create("idx_e")), false);

        CockroachDBPerformanceHintGenerator.QueryShape shape = new CockroachDBPerformanceHintGenerator.QueryShape(3,
                true, true, true, true);
        shape = new CockroachDBPerformanceHintGenerator.QueryShape(3, true, true, true, true,
                List.of(new JoinSpec(0, JoinType.INNER, "t0", "t1", true, "INNER JOIN"),
                        new JoinSpec(1, JoinType.LEFT, "t1", "t2", true, "LEFT JOIN")),
                true, true, Set.of("t0", "t1", "t2"));

        List<CockroachDBPerformanceHintGenerator.HintedQueryCandidate> candidates = CockroachDBPerformanceHintGenerator
                .generateCandidates(List.of(t0, t1, t2), shape, new Randomly(), 2, 32);

        Set<String> uniqueKeys = candidates.stream()
                .map(candidate -> candidate.getHintSql() + "|" + candidate.getTargetJoinOrdinal())
                .collect(Collectors.toSet());
        List<CockroachDBPerformanceHintGenerator.HintedQueryCandidate> joinCandidates = candidates.stream()
                .filter(candidate -> candidate.getHintFamily().equals("JOIN")).collect(Collectors.toList());

        assertEquals(uniqueKeys.size(), candidates.size());
        assertTrue(joinCandidates.stream().anyMatch(candidate -> candidate.getHintType().equals("HASH_JOIN")
                && Integer.valueOf(0).equals(candidate.getTargetJoinOrdinal())));
        assertTrue(joinCandidates.stream().anyMatch(candidate -> candidate.getHintType().equals("LEFT_HASH_JOIN")
                && Integer.valueOf(1).equals(candidate.getTargetJoinOrdinal())));
    }

    @Test
    public void testSingleTableShapeSkipsJoinHints() {
        CockroachDBTable t0 = new CockroachDBTable("t0", List.of(), List.of(TableIndex.create("idx_a")), false);
        CockroachDBPerformanceHintGenerator.QueryShape shape = new CockroachDBPerformanceHintGenerator.QueryShape(1,
                true, false, false, false, List.of(), false, false, Set.of("t0"));

        List<CockroachDBPerformanceHintGenerator.HintedQueryCandidate> candidates = CockroachDBPerformanceHintGenerator
                .generateCandidates(List.of(t0), shape, new Randomly(), 2, 32);
        Set<String> hintTypes = candidates.stream()
                .map(CockroachDBPerformanceHintGenerator.HintedQueryCandidate::getHintType).collect(Collectors.toSet());

        assertFalse(hintTypes.contains("HASH_JOIN"));
        assertTrue(hintTypes.contains("FORCE_INDEX"));
        assertTrue(hintTypes.contains("FORCE_INDEX_NO_FULL_SCAN"));
        assertTrue(hintTypes.contains("NO_FULL_SCAN"));
    }

    @Test
    public void testNoFullScanRequiresPredicateOrJoinConstraint() {
        CockroachDBTable t0 = new CockroachDBTable("t0", List.of(), List.of(TableIndex.create("idx_a")), false);

        CockroachDBPerformanceHintGenerator.QueryShape shape = new CockroachDBPerformanceHintGenerator.QueryShape(1,
                false, false, false, false, List.of(), false, false, Set.of());
        Set<String> hintTypes = CockroachDBPerformanceHintGenerator
                .generateCandidates(List.of(t0), shape, new Randomly(), 2, 32).stream()
                .map(CockroachDBPerformanceHintGenerator.HintedQueryCandidate::getHintType).collect(Collectors.toSet());

        assertFalse(hintTypes.contains("NO_FULL_SCAN"));
        assertFalse(hintTypes.contains("FORCE_INDEX_NO_FULL_SCAN"));
    }

    @Test
    public void testLookupJoinRequiresIndexedRightTable() {
        CockroachDBTable left = new CockroachDBTable("t0", List.of(), List.of(TableIndex.create("idx_a")), false);
        CockroachDBTable rightWithoutIndex = new CockroachDBTable("t1", List.of(), List.of(), false);

        CockroachDBPerformanceHintGenerator.QueryShape shape = new CockroachDBPerformanceHintGenerator.QueryShape(2,
                true, true, false, false, List.of(new JoinSpec(0, JoinType.INNER, "t0", "t1", false, "INNER JOIN")),
                true, false, Set.of("t0", "t1"));
        Set<String> hintTypes = CockroachDBPerformanceHintGenerator
                .generateCandidates(List.of(left, rightWithoutIndex), shape, new Randomly(), 2, 32).stream()
                .map(CockroachDBPerformanceHintGenerator.HintedQueryCandidate::getHintType).collect(Collectors.toSet());

        assertFalse(hintTypes.contains("LOOKUP_JOIN"));
        assertFalse(hintTypes.contains("LEFT_LOOKUP_JOIN"));
    }
}
