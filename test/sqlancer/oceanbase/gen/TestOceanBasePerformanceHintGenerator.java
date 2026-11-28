package sqlancer.oceanbase.gen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseIndex;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseTable;

public class TestOceanBasePerformanceHintGenerator {

    @Test
    public void testJoinAndAggregationCandidatesUseOceanBaseSyntax() {
        OceanBaseTable t0 = new OceanBaseTable("t0", List.of(),
                List.of(OceanBaseIndex.create("i_a"), OceanBaseIndex.create("i_b")));
        OceanBaseTable t1 = new OceanBaseTable("t1", List.of(), List.of(OceanBaseIndex.create("i_c")));
        OceanBasePerformanceHintGenerator.QueryShape shape = new OceanBasePerformanceHintGenerator.QueryShape(true,
                true, true);

        List<OceanBasePerformanceHintGenerator.HintedQueryCandidate> candidates = OceanBasePerformanceHintGenerator
                .generateCandidates(List.of(t0, t1), shape, new Randomly(), 2, 64);
        Set<String> types = candidates.stream().map(OceanBasePerformanceHintGenerator.HintedQueryCandidate::getHintType)
                .collect(Collectors.toSet());

        assertTrue(types.contains("FULL"));
        assertTrue(types.contains("INDEX"));
        assertTrue(types.contains("USE_NL"));
        assertTrue(types.contains("USE_HASH"));
        assertTrue(types.contains("ORDERED"));
        assertTrue(types.contains("USE_HASH_AGGREGATION"));
        assertTrue(types.contains("NO_USE_HASH_AGGREGATION"));
        assertTrue(candidates.stream()
                .allMatch(candidate -> candidate.getHintSql().startsWith("FULL(")
                        || candidate.getHintSql().startsWith("INDEX(") || candidate.getHintSql().startsWith("LEADING(")
                        || candidate.getHintSql().equals("ORDERED") || candidate.getHintSql().endsWith("AGGREGATION")));
    }

    @Test
    public void testSingleTableShapeSkipsJoinOnlyHints() {
        OceanBaseTable t0 = new OceanBaseTable("t0", List.of(), List.of(OceanBaseIndex.create("i_a")));
        OceanBasePerformanceHintGenerator.QueryShape shape = new OceanBasePerformanceHintGenerator.QueryShape(true,
                false, false);

        List<OceanBasePerformanceHintGenerator.HintedQueryCandidate> candidates = OceanBasePerformanceHintGenerator
                .generateCandidates(List.of(t0), shape, new Randomly(), 2, 64);
        Set<String> types = candidates.stream().map(OceanBasePerformanceHintGenerator.HintedQueryCandidate::getHintType)
                .collect(Collectors.toSet());

        assertFalse(types.contains("USE_NL"));
        assertFalse(types.contains("USE_HASH"));
        assertFalse(types.contains("ORDERED"));
        assertTrue(types.contains("INDEX"));
    }
}
