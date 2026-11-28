package sqlancer.mariadb.oracle;

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
import sqlancer.mariadb.MariaDBOptions;
import sqlancer.mariadb.MariaDBProvider.MariaDBGlobalState;
import sqlancer.mariadb.MariaDBSchema.MariaDBColumn;
import sqlancer.mariadb.MariaDBSchema.MariaDBDataType;
import sqlancer.mariadb.MariaDBSchema.MariaDBIndex;
import sqlancer.mariadb.MariaDBSchema.MariaDBTable;
import sqlancer.mariadb.MariaDBSchema.MariaDBTable.MariaDBEngine;

public class TestMariaDBExpandedQueryGenerator {
    public static MariaDBTable table(String name) {
        List<MariaDBColumn> columns = new ArrayList<>(List.of(new MariaDBColumn("c0", MariaDBDataType.INT, true, 0),
                new MariaDBColumn("c1", MariaDBDataType.INT, false, 0),
                new MariaDBColumn("c2", MariaDBDataType.VARCHAR, false, 0),
                new MariaDBColumn("c3", MariaDBDataType.REAL, false, 0, true)));
        MariaDBTable table = new MariaDBTable(name, columns, List.of(MariaDBIndex.create("idx_c1", List.of("c1"))),
                MariaDBEngine.INNO_DB);
        columns.forEach(column -> column.setTable(table));
        return table;
    }

    public static MariaDBGlobalState state(int seed) {
        MariaDBGlobalState state = new MariaDBGlobalState();
        state.setRandomly(new Randomly(seed));
        state.setMainOptions(new MainOptions());
        MariaDBOptions options = new MariaDBOptions();
        options.performanceQueryGeneratorMode = PerformanceQueryGenerationMode.EXPANDED;
        state.setDbmsSpecificOptions(options);
        return state;
    }

    @Test
    public void thousandSeedsCoverAllFamiliesAndFeatures() {
        List<MariaDBTable> tables = List.of(table("t0"), table("t1"), table("t2"));
        Set<String> features = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (int seed = 0; seed < 1000; seed++) {
            Family family = List.of(Family.EXPRESSION, Family.AGGREGATE, Family.JOIN).get(seed % 3);
            Result<MariaDBTable, MariaDBColumn> query = MariaDBExpandedQueryGenerator.generate(state(seed), tables,
                    family, reason -> {
                        throw new AssertionError(reason);
                    });
            ExpandedQueryContract.verify(query,
                    query.tables.stream().map(MariaDBTable::getName).collect(Collectors.toList()), family);
            features.addAll(query.metadata.getFeatures());
            keys.add(query.metadata.getStructuralKey());
        }
        assertTrue(features
                .containsAll(List.of("IN", "FUNCTION", "EXPRESSION_PROJECTION", "AGGREGATE_COUNT", "AGGREGATE_SUM",
                        "AGGREGATE_MIN", "AGGREGATE_MAX", "HAVING", "GROUP_BY", "LIMIT", "DISTINCT", "JOIN_INNER",
                        "JOIN_LEFT", "JOIN_RIGHT", "JOIN_CROSS", "JOIN_STRAIGHT", "COMPOUND_ON", "NON_EQUI_JOIN")),
                features.toString());
        assertTrue(keys.size() > 100, "structural coverage must distinguish new expression and join combinations");
        assertEquals(3, tables.size(), "generation must not consume the caller's table list");
    }

    @Test
    public void singleTableJoinFallbackRetainsActualSource() {
        List<String> reasons = new ArrayList<>();
        Result<MariaDBTable, MariaDBColumn> query = MariaDBExpandedQueryGenerator.generate(state(17),
                List.of(table("t0")), Family.JOIN, reasons::add);
        assertEquals(Family.EXPRESSION, query.metadata.getFamily());
        assertEquals(List.of("fallback_single_table"), reasons);
        assertEquals(1, query.tables.size());
    }
}
