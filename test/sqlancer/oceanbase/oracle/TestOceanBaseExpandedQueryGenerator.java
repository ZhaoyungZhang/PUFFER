package sqlancer.oceanbase.oracle;

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
import sqlancer.oceanbase.OceanBaseGlobalState;
import sqlancer.oceanbase.OceanBaseOptions;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseColumn;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseDataType;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseIndex;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseTable;

public class TestOceanBaseExpandedQueryGenerator {
    public static OceanBaseTable table(String name) {
        List<OceanBaseColumn> columns = new ArrayList<>(
                List.of(new OceanBaseColumn("c0", OceanBaseDataType.INT, true, 0, false),
                        new OceanBaseColumn("c1", OceanBaseDataType.INT, false, 0, false),
                        new OceanBaseColumn("c2", OceanBaseDataType.VARCHAR, false, 0, false),
                        new OceanBaseColumn("c3", OceanBaseDataType.DECIMAL, false, 0, false)));
        OceanBaseTable table = new OceanBaseTable(name, columns, List.of(OceanBaseIndex.create("idx_c1")));
        columns.forEach(column -> column.setTable(table));
        return table;
    }

    public static OceanBaseGlobalState state(int seed) {
        OceanBaseGlobalState state = new OceanBaseGlobalState();
        state.setRandomly(new Randomly(seed));
        state.setMainOptions(new MainOptions());
        OceanBaseOptions options = new OceanBaseOptions();
        options.performanceQueryGeneratorMode = PerformanceQueryGenerationMode.EXPANDED;
        state.setDbmsSpecificOptions(options);
        return state;
    }

    @Test
    public void thousandSeedsCoverAllFamiliesAndFeatures() {
        List<OceanBaseTable> tables = List.of(table("t0"), table("t1"), table("t2"));
        Set<String> features = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (int seed = 0; seed < 1000; seed++) {
            Family family = List.of(Family.EXPRESSION, Family.AGGREGATE, Family.JOIN).get(seed % 3);
            Result<OceanBaseTable, OceanBaseColumn> query = OceanBaseExpandedQueryGenerator.generate(state(seed),
                    tables, family, reason -> {
                        throw new AssertionError(reason);
                    });
            ExpandedQueryContract.verify(query,
                    query.tables.stream().map(OceanBaseTable::getName).collect(Collectors.toList()), family);
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
        Result<OceanBaseTable, OceanBaseColumn> query = OceanBaseExpandedQueryGenerator.generate(state(17),
                List.of(table("t0")), Family.JOIN, reasons::add);
        assertEquals(Family.EXPRESSION, query.metadata.getFamily());
        assertEquals(List.of("fallback_single_table"), reasons);
        assertEquals(1, query.tables.size());
    }
}
