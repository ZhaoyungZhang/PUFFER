package sqlancer.tidb.oracle;

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
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Result;
import sqlancer.common.perfhint.ExpandedQueryContract;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode.Family;
import sqlancer.common.schema.TableIndex;
import sqlancer.tidb.TiDBOptions;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBCompositeDataType;
import sqlancer.tidb.TiDBSchema.TiDBDataType;
import sqlancer.tidb.TiDBSchema.TiDBTable;

public class TestTiDBExpandedQueryGenerator {
    public static TiDBTable table(String name) {
        List<TiDBColumn> columns = new ArrayList<>(
                List.of(new TiDBColumn("c0", new TiDBCompositeDataType(TiDBDataType.INT, 8), true, false, false),
                        new TiDBColumn("c1", new TiDBCompositeDataType(TiDBDataType.INT, 8), false, true, false),
                        new TiDBColumn("c2", new TiDBCompositeDataType(TiDBDataType.TEXT, 8), false, true, false),
                        new TiDBColumn("c3", new TiDBCompositeDataType(TiDBDataType.DECIMAL, 8), false, true, false)));
        TiDBTable table = new TiDBTable(name, columns, List.of(TableIndex.create("idx_c1")), false);
        columns.forEach(column -> column.setTable(table));
        return table;
    }

    public static TiDBGlobalState state(int seed) {
        TiDBGlobalState state = new TiDBGlobalState();
        state.setRandomly(new Randomly(seed));
        state.setMainOptions(new MainOptions());
        TiDBOptions options = new TiDBOptions();
        options.performanceQueryGeneratorMode = PerformanceQueryGenerationMode.EXPANDED;
        state.setDbmsSpecificOptions(options);
        return state;
    }

    @Test
    public void thousandSeedsCoverAllFamiliesAndFeatures() {
        List<TiDBTable> tables = List.of(table("t0"), table("t1"), table("t2"));
        Set<String> features = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (int seed = 0; seed < 1000; seed++) {
            Family family = List.of(Family.EXPRESSION, Family.AGGREGATE, Family.JOIN).get(seed % 3);
            Result<TiDBTable, TiDBColumn> query = TiDBExpandedQueryGenerator.generate(state(seed), tables, family,
                    reason -> {
                        throw new AssertionError(reason);
                    });
            ExpandedQueryContract.verify(query,
                    query.tables.stream().map(TiDBTable::getName).collect(Collectors.toList()), family);
            features.addAll(query.metadata.getFeatures());
            keys.add(query.metadata.getStructuralKey());
        }
        assertTrue(features.containsAll(List.of("CASE", "CAST", "BIT_OPERATION", "FUNCTION", "EXPRESSION_PROJECTION",
                "AGGREGATE_COUNT", "AGGREGATE_SUM", "AGGREGATE_MIN", "AGGREGATE_MAX", "HAVING", "GROUP_BY", "LIMIT",
                "DISTINCT", "JOIN_INNER", "JOIN_LEFT", "JOIN_RIGHT", "JOIN_CROSS", "JOIN_STRAIGHT", "COMPOUND_ON",
                "NON_EQUI_JOIN")), features.toString());
        assertTrue(keys.size() > 100, "structural coverage must distinguish new expression and join combinations");
        assertEquals(3, tables.size(), "generation must not consume the caller's table list");
    }

    @Test
    public void singleTableJoinFallbackRetainsActualSource() {
        List<String> reasons = new ArrayList<>();
        Result<TiDBTable, TiDBColumn> query = TiDBExpandedQueryGenerator.generate(state(17), List.of(table("t0")),
                Family.JOIN, reasons::add);
        assertEquals(Family.EXPRESSION, query.metadata.getFamily());
        assertEquals(List.of("fallback_single_table"), reasons);
        assertEquals(1, query.tables.size());
    }
}
