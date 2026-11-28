package sqlancer.mysql.oracle;

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
import sqlancer.mysql.MySQLGlobalState;
import sqlancer.mysql.MySQLOptions;
import sqlancer.mysql.MySQLSchema.MySQLColumn;
import sqlancer.mysql.MySQLSchema.MySQLDataType;
import sqlancer.mysql.MySQLSchema.MySQLIndex;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.MySQLSchema.MySQLTable.MySQLEngine;

public class TestMySQLExpandedQueryGenerator {
    public static MySQLTable table(String name) {
        List<MySQLColumn> columns = new ArrayList<>(List.of(new MySQLColumn("c0", MySQLDataType.INT, true, 0),
                new MySQLColumn("c1", MySQLDataType.INT, false, 0),
                new MySQLColumn("c2", MySQLDataType.VARCHAR, false, 0),
                new MySQLColumn("c3", MySQLDataType.DECIMAL, false, 0)));
        MySQLTable table = new MySQLTable(name, columns, List.of(MySQLIndex.create("idx_c1")), MySQLEngine.INNO_DB);
        columns.forEach(column -> column.setTable(table));
        return table;
    }

    public static MySQLGlobalState state(int seed) {
        MySQLGlobalState state = new MySQLGlobalState();
        state.setRandomly(new Randomly(seed));
        state.setMainOptions(new MainOptions());
        MySQLOptions options = new MySQLOptions();
        options.performanceQueryGeneratorMode = PerformanceQueryGenerationMode.EXPANDED;
        state.setDbmsSpecificOptions(options);
        return state;
    }

    @Test
    public void thousandSeedsCoverAllFamiliesAndFeatures() {
        List<MySQLTable> tables = List.of(table("t0"), table("t1"), table("t2"));
        Set<String> features = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (int seed = 0; seed < 1000; seed++) {
            Family family = List.of(Family.EXPRESSION, Family.AGGREGATE, Family.JOIN).get(seed % 3);
            Result<MySQLTable, MySQLColumn> query = MySQLExpandedQueryGenerator.generate(state(seed), tables, family,
                    reason -> {
                        throw new AssertionError(reason);
                    });
            ExpandedQueryContract.verify(query,
                    query.tables.stream().map(MySQLTable::getName).collect(Collectors.toList()), family);
            features.addAll(query.metadata.getFeatures());
            keys.add(query.metadata.getStructuralKey());
        }
        assertTrue(features.containsAll(
                List.of("CASE", "CAST", "IN", "FUNCTION", "EXPRESSION_PROJECTION", "AGGREGATE_COUNT", "AGGREGATE_SUM",
                        "AGGREGATE_MIN", "AGGREGATE_MAX", "HAVING", "GROUP_BY", "LIMIT", "DISTINCT", "JOIN_INNER",
                        "JOIN_LEFT", "JOIN_RIGHT", "JOIN_CROSS", "JOIN_STRAIGHT", "COMPOUND_ON", "NON_EQUI_JOIN")),
                features.toString());
        assertTrue(keys.size() > 100, "structural coverage must distinguish new expression and join combinations");
        assertEquals(3, tables.size(), "generation must not consume the caller's table list");
    }

    @Test
    public void singleTableJoinFallbackRetainsActualSource() {
        List<String> reasons = new ArrayList<>();
        Result<MySQLTable, MySQLColumn> query = MySQLExpandedQueryGenerator.generate(state(17), List.of(table("t0")),
                Family.JOIN, reasons::add);
        assertEquals(Family.EXPRESSION, query.metadata.getFamily());
        assertEquals(List.of("fallback_single_table"), reasons);
        assertEquals(1, query.tables.size());
    }
}
