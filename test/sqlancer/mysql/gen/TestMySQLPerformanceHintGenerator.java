package sqlancer.mysql.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.JCommander;

import sqlancer.Randomly;
import sqlancer.mysql.MySQLOptions;
import sqlancer.mysql.MySQLSchema.MySQLIndex;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.MySQLSchema.MySQLTable.MySQLEngine;

public class TestMySQLPerformanceHintGenerator {

    @Test
    public void testGenerateCandidatesAreDeduplicatedAndCapped() {
        MySQLTable t0 = new MySQLTable("t0", List.of(),
                List.of(MySQLIndex.create("idx_a"), MySQLIndex.create("idx_b"), MySQLIndex.create("idx_b")),
                MySQLEngine.INNO_DB);
        MySQLTable t1 = new MySQLTable("t1", List.of(),
                List.of(MySQLIndex.create("idx_c"), MySQLIndex.create("idx_d"), MySQLIndex.create("idx_e")),
                MySQLEngine.INNO_DB);
        MySQLPerformanceHintGenerator.QueryShape queryShape = new MySQLPerformanceHintGenerator.QueryShape(2, true,
                true, true, false);

        List<MySQLPerformanceHintGenerator.HintedQueryCandidate> candidates = MySQLPerformanceHintGenerator
                .generateCandidates(List.of(t0, t1), queryShape, new Randomly(), 2, 16);
        Set<String> uniqueHints = candidates.stream()
                .map(MySQLPerformanceHintGenerator.HintedQueryCandidate::getHintSql).collect(Collectors.toSet());

        assertEquals(uniqueHints.size(), candidates.size());
        assertTrue(candidates.size() <= 16);
        assertTrue(candidates.stream().allMatch(candidate -> !candidate.getHintSql().isBlank()));
    }

    @Test
    public void testSingleTableShapeSkipsJoinOnlyHints() {
        MySQLTable t0 = new MySQLTable("t0", List.of(), List.of(MySQLIndex.create("idx_a"), MySQLIndex.create("idx_b")),
                MySQLEngine.INNO_DB);
        MySQLPerformanceHintGenerator.QueryShape queryShape = new MySQLPerformanceHintGenerator.QueryShape(1, false,
                true, false, false);

        List<MySQLPerformanceHintGenerator.HintedQueryCandidate> candidates = MySQLPerformanceHintGenerator
                .generateCandidates(List.of(t0), queryShape, new Randomly(), 2, 64);
        Set<String> hintTypes = candidates.stream().map(MySQLPerformanceHintGenerator.HintedQueryCandidate::getHintType)
                .collect(Collectors.toSet());

        assertFalse(hintTypes.contains("BKA"));
        assertFalse(hintTypes.contains("HASH_JOIN"));
        assertFalse(hintTypes.contains("JOIN_FIXED_ORDER"));
        assertFalse(hintTypes.contains("JOIN_INDEX"));
        assertFalse(hintTypes.contains("ORDER_INDEX"));
        assertTrue(hintTypes.contains("INDEX"));
    }

    @Test
    public void testThreeTableShapeKeepsJoinAndOrderingHints() {
        MySQLTable t0 = new MySQLTable("t0", List.of(), List.of(MySQLIndex.create("idx_a"), MySQLIndex.create("idx_b")),
                MySQLEngine.INNO_DB);
        MySQLTable t1 = new MySQLTable("t1", List.of(), List.of(MySQLIndex.create("idx_c"), MySQLIndex.create("idx_d")),
                MySQLEngine.INNO_DB);
        MySQLTable t2 = new MySQLTable("t2", List.of(), List.of(MySQLIndex.create("idx_e"), MySQLIndex.create("idx_f")),
                MySQLEngine.INNO_DB);
        MySQLPerformanceHintGenerator.QueryShape queryShape = new MySQLPerformanceHintGenerator.QueryShape(3, true,
                true, true, true);

        List<MySQLPerformanceHintGenerator.HintedQueryCandidate> candidates = MySQLPerformanceHintGenerator
                .generateCandidates(List.of(t0, t1, t2), queryShape, new Randomly(), 2, 128);
        Set<String> hintTypes = candidates.stream().map(MySQLPerformanceHintGenerator.HintedQueryCandidate::getHintType)
                .collect(Collectors.toSet());

        assertFalse(hintTypes.contains("BKA"), "MySQL default batched_key_access=off must exclude BKA hint");
        assertTrue(hintTypes.contains("JOIN_ORDER"));
        assertTrue(hintTypes.contains("ORDER_INDEX"));
        assertTrue(hintTypes.contains("GROUP_INDEX"));
        assertTrue(hintTypes.contains("INDEX_MERGE"));

        List<MySQLPerformanceHintGenerator.HintedQueryCandidate> optInCandidates = MySQLPerformanceHintGenerator
                .generateCandidates(List.of(t0, t1, t2), queryShape, new Randomly(), 2, 128, true);
        Set<String> optInTypes = optInCandidates.stream()
                .map(MySQLPerformanceHintGenerator.HintedQueryCandidate::getHintType).collect(Collectors.toSet());
        assertTrue(optInTypes.contains("BKA"), "MySQL BKA hint should be available when explicitly enabled");

        MySQLOptions opts = new MySQLOptions();
        assertFalse(opts.performanceAllowBkaHint);
        JCommander.newBuilder().addObject(opts).build().parse("--perf-allow-bka-hint", "true");
        assertTrue(opts.performanceAllowBkaHint);
    }
}
