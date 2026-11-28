package sqlancer.cockroachdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.cockroachdb.CockroachDBOptions;
import sqlancer.cockroachdb.CockroachDBProvider.CockroachDBGlobalState;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBColumn;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBDataType;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBTable;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Result;
import sqlancer.common.perfhint.ExpandedQueryContract;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode.Family;
import sqlancer.common.perfhint.PerformanceSqlLexical;
import sqlancer.common.schema.TableIndex;

public class TestCockroachDBExpandedQueryGenerator {
    public static CockroachDBTable table(String name) {
        List<CockroachDBColumn> columns = new ArrayList<>(
                List.of(new CockroachDBColumn("c0", CockroachDBDataType.INT.get(), true, false),
                        new CockroachDBColumn("c1", CockroachDBDataType.INT.get(), false, true),
                        new CockroachDBColumn("c2", CockroachDBDataType.STRING.get(), false, true),
                        new CockroachDBColumn("c3", CockroachDBDataType.DECIMAL.get(), false, true)));
        CockroachDBTable table = new CockroachDBTable(name, columns, List.of(TableIndex.create("idx_c1")), false);
        columns.forEach(column -> column.setTable(table));
        return table;
    }

    public static CockroachDBGlobalState state(int seed) {
        CockroachDBGlobalState state = new CockroachDBGlobalState();
        state.setRandomly(new Randomly(seed));
        state.setMainOptions(new MainOptions());
        CockroachDBOptions options = new CockroachDBOptions();
        options.performanceQueryGeneratorMode = PerformanceQueryGenerationMode.EXPANDED;
        state.setDbmsSpecificOptions(options);
        return state;
    }

    @Test
    public void thousandSeedsCoverAllFamiliesAndFeatures() {
        List<CockroachDBTable> tables = List.of(table("t0"), table("t1"), table("t2"));
        Set<String> features = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (int seed = 0; seed < 1000; seed++) {
            Family family = List.of(Family.EXPRESSION, Family.AGGREGATE, Family.JOIN).get(seed % 3);
            Result<CockroachDBTable, CockroachDBColumn> query = CockroachDBExpandedQueryGenerator.generate(state(seed),
                    tables, family, reason -> {
                        throw new AssertionError(reason);
                    });
            ExpandedQueryContract.verify(query,
                    query.tables.stream().map(CockroachDBTable::getName).collect(Collectors.toList()), family);
            String syntax = PerformanceSqlLexical.maskLiterals(query.sql);
            assertTrue(!syntax.contains(" COLLATE "), "collated strings require a distinct expression type");
            assertTrue(!syntax.contains("/"), "integer division returns DECIMAL and violates the INT contract");
            features.addAll(query.metadata.getFeatures());
            keys.add(query.metadata.getStructuralKey());
        }
        assertTrue(features.containsAll(
                List.of("CASE", "CAST", "IN", "FUNCTION", "EXPRESSION_PROJECTION", "AGGREGATE_COUNT", "AGGREGATE_SUM",
                        "AGGREGATE_MIN", "AGGREGATE_MAX", "HAVING", "GROUP_BY", "LIMIT", "DISTINCT", "JOIN_INNER",
                        "JOIN_LEFT", "JOIN_RIGHT", "JOIN_CROSS", "JOIN_FULL", "COMPOUND_ON", "NON_EQUI_JOIN")),
                features.toString());
        assertTrue(keys.size() > 100, "structural coverage must distinguish new expression and join combinations");
        assertEquals(3, tables.size(), "generation must not consume the caller's table list");
    }

    @Test
    public void singleTableJoinFallbackRetainsActualSource() {
        List<String> reasons = new ArrayList<>();
        Result<CockroachDBTable, CockroachDBColumn> query = CockroachDBExpandedQueryGenerator.generate(state(17),
                List.of(table("t0")), Family.JOIN, reasons::add);
        assertEquals(Family.EXPRESSION, query.metadata.getFamily());
        assertEquals(List.of("fallback_single_table"), reasons);
        assertEquals(1, query.tables.size());
    }
}
