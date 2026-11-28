package sqlancer.common.perfhint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.cockroachdb.CockroachDBOptions;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Backend;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.JoinType;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Kind;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode.Family;
import sqlancer.mariadb.MariaDBOptions;
import sqlancer.mysql.MySQLOptions;
import sqlancer.oceanbase.OceanBaseOptions;
import sqlancer.tidb.TiDBOptions;

public class TestPerformanceQueryGeneration {
    @Test
    public void missingJoinKeysAndDepthZeroStopAfterThreeAttempts() {
        Backend<String, String> backend = new Backend<String, String>() {
            @Override
            public String tableName(String table) {
                return table;
            }

            @Override
            public List<String> columns(String table) {
                return List.of(table + ".c0");
            }

            @Override
            public String columnSql(String column) {
                return column;
            }

            @Override
            public Kind kind(String column) {
                return column.startsWith("a") ? Kind.EXACT : Kind.STRING;
            }

            @Override
            public boolean primaryKey(String column) {
                return false;
            }

            @Override
            public String expression(List<String> columns, boolean predicate, PerformanceExpressionPolicy policy) {
                throw new AssertionError("no expression should be reached");
            }

            @Override
            public String aggregate(String function, String column, boolean distinct) {
                throw new AssertionError("no aggregate should be reached");
            }

            @Override
            public List<JoinType> joinTypes() {
                return List.of(JoinType.INNER);
            }
        };
        for (Family family : List.of(Family.JOIN, Family.EXPRESSION)) {
            List<String> reasons = new ArrayList<>();
            new Randomly(9);
            assertThrows(IgnoreMeException.class, () -> ExpandedPerformanceQuery.generate(backend, List.of("a", "b"),
                    PerformanceQueryGenerationMode.EXPANDED, family, family == Family.JOIN ? 3 : 0, reasons::add));
            assertEquals(List.of("retry", "retry", "failed"), reasons);
        }
    }

    @Test
    public void fiveOptionsAcceptModesAndRejectTypos() {
        for (Object options : List.of(new MySQLOptions(), new MariaDBOptions(), new TiDBOptions(),
                new CockroachDBOptions(), new OceanBaseOptions())) {
            for (String mode : List.of("structured", "expanded", "mixed")) {
                JCommander.newBuilder().addObject(options).build().parse("--perf-query-generator-mode", mode);
            }
            assertThrows(ParameterException.class, () -> JCommander.newBuilder().addObject(options).build()
                    .parse("--perf-query-generator-mode", "typo"));
        }
        assertEquals(PerformanceQueryGenerationMode.MIXED, new MySQLOptions().performanceQueryGeneratorMode);
        assertEquals(PerformanceQueryGenerationMode.MIXED, new MariaDBOptions().performanceQueryGeneratorMode);
        assertEquals(PerformanceQueryGenerationMode.MIXED, new TiDBOptions().performanceQueryGeneratorMode);
        assertEquals(PerformanceQueryGenerationMode.MIXED, new CockroachDBOptions().performanceQueryGeneratorMode);
        assertEquals(PerformanceQueryGenerationMode.MIXED, new OceanBaseOptions().performanceQueryGeneratorMode);
    }

    @Test
    public void structuredConsumesNoSamplingRandomness() {
        for (int seed = 0; seed < 100; seed++) {
            Randomly expected = new Randomly(seed);
            long next = expected.getInteger();
            Randomly actual = new Randomly(seed);
            assertEquals(Family.STRUCTURED, PerformanceQueryGenerationMode.STRUCTURED.chooseFamily());
            assertEquals(next, actual.getInteger());
        }
    }

    @Test
    public void mixtureHasTheAgreedBudget() {
        new Randomly(7351);
        Map<Family, Integer> counts = new HashMap<>();
        for (int i = 0; i < 30000; i++) {
            counts.merge(PerformanceQueryGenerationMode.MIXED.chooseFamily(), 1, Integer::sum);
        }
        assertTrue(counts.get(Family.STRUCTURED) > 14000 && counts.get(Family.STRUCTURED) < 16000);
        for (Family family : List.of(Family.EXPRESSION, Family.AGGREGATE, Family.JOIN)) {
            assertTrue(counts.get(family) > 4500 && counts.get(family) < 5500, counts.toString());
        }
    }

    @Test
    public void lexicalMaskPreservesPositionsAndDoesNotInventCommaJoins() {
        String sql = "SELECT 'RIGHT JOIN fake, x', \"CROSS JOIN\", f(t0.c1, 3) FROM t0 "
                + "/* FULL JOIN fake */ LEFT JOIN t1 ON t0.c0=t1.c0 -- INNER JOIN fake\n";
        String mask = PerformanceSqlLexical.maskLiterals(sql);
        assertEquals(sql.length(), mask.length());
        assertFalse(mask.contains("fake"));
        assertTrue(mask.contains("LEFT JOIN t1"));
        assertFalse(PerformanceSqlLexical.hasCommaFrom(sql));
        assertTrue(PerformanceSqlLexical.hasCommaFrom("SELECT f(c0,c1) FROM t0, t1 WHERE c0 IN (1,2)"));
        assertFalse(PerformanceSqlLexical.hasCommaFrom("SELECT c0,c1 FROM t0 WHERE c0 IN (1,2)"));
    }

    @Test
    public void metadataKeysIgnoreLiteralsButKeepShapeAndAreVersioned() {
        PerformanceQueryMetadata left = PerformanceQueryMetadata.structured(PerformanceQueryGenerationMode.STRUCTURED,
                "SELECT t0.c0 FROM t0 WHERE t0.c0 > 1");
        PerformanceQueryMetadata right = PerformanceQueryMetadata.structured(PerformanceQueryGenerationMode.STRUCTURED,
                "SELECT t0.c0 FROM t0 WHERE t0.c0 > 2");
        assertEquals(left.getStructuralKey(), right.getStructuralKey());
        assertTrue(left.getStructuralKey().startsWith("qg2|STRUCTURED|"));
        assertNotEquals(left.getStructuralKey(), PerformanceQueryMetadata
                .structured(PerformanceQueryGenerationMode.MIXED, "SELECT t0.c0 FROM t0 LEFT JOIN t1 ON t0.c0=t1.c0")
                .getStructuralKey());
        assertTrue(left.toJson().contains("\"family\":\"structured\""));
    }
}
