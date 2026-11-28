package sqlancer.oceanbase.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.oceanbase.OceanBaseGlobalState;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseColumn;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseDataType;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseIndex;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseTable;
import sqlancer.oceanbase.oracle.OceanBasePerformanceHintOracle.BaseQuery;
import sqlancer.oceanbase.oracle.OceanBasePerformanceQueryGenerator.ColumnPair;

public class TestOceanBasePerformanceQueryGenerator {

    private static OceanBaseTable table(String name, boolean primaryKey) {
        List<OceanBaseColumn> columns = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            columns.add(new OceanBaseColumn("c" + i, OceanBaseDataType.INT, primaryKey && i == 0, 0, false));
        }
        OceanBaseTable table = new OceanBaseTable(name, columns, List.of(OceanBaseIndex.create("i0")));
        columns.forEach(column -> column.setTable(table));
        return table;
    }

    @Test
    public void exercisesProjectionDistinctOrderingLimitAndCompoundPredicates() {
        List<OceanBaseTable> tables = List.of(table("t0", false), table("t1", false), table("t2", false));
        Set<String> coverage = new HashSet<>();
        for (int seed = 0; seed < 1000; seed++) {
            OceanBaseGlobalState state = new OceanBaseGlobalState();
            state.setRandomly(new Randomly(seed));
            BaseQuery query = OceanBasePerformanceQueryGenerator.generate(state, tables);
            String sql = query.sql;
            String projection = sql.substring(sql.indexOf("SELECT ") + 7, sql.indexOf(" FROM ")).replace("DISTINCT ",
                    "");
            if (projection.contains(",")) {
                coverage.add("multi_projection");
            }
            for (String feature : List.of("DISTINCT", "GROUP BY", "ORDER BY", "LIMIT", " AND ", "STRAIGHT_JOIN")) {
                if (sql.contains(feature)) {
                    coverage.add(feature);
                }
            }
            assertEquals(query.tables.size() > 1, query.shape.hasJoin());
            assertEquals(sql.contains(" GROUP BY "), query.shape.hasGroupBy());
            if (query.shape.hasGroupBy()) {
                String group = sql.split(" GROUP BY ")[1].split(" ORDER BY | LIMIT ")[0];
                assertEquals(projection, group, "all output columns must be grouped");
            }
            if (sql.contains(" ORDER BY ")) {
                String order = sql.split(" ORDER BY ")[1].split(" LIMIT ")[0];
                for (String term : order.split(", ")) {
                    assertTrue(List.of(projection.split(", ")).contains(term.replaceAll(" (ASC|DESC)$", "")),
                            "ordering must be legal with DISTINCT and GROUP BY");
                }
            }
        }
        assertTrue(coverage.containsAll(
                List.of("multi_projection", "DISTINCT", "GROUP BY", "ORDER BY", "LIMIT", " AND ", "STRAIGHT_JOIN")),
                coverage.toString());
    }

    @Test
    public void exploresCompatibleJoinColumnsAndPrefersPrimaryKeys() {
        OceanBaseTable left = table("t0", false);
        OceanBaseTable right = table("t1", false);
        Set<String> pairs = new HashSet<>();
        for (int seed = 0; seed < 100; seed++) {
            new Randomly(seed);
            ColumnPair pair = OceanBasePerformanceQueryGenerator.chooseJoinPair(List.of(left), right);
            pairs.add(pair.left.getName() + ":" + pair.right.getName());
        }
        assertTrue(pairs.size() > 1, "join generation must explore beyond the first type-compatible pair");
        OceanBaseTable keyed = table("t2", true);
        for (int seed = 0; seed < 100; seed++) {
            new Randomly(seed);
            ColumnPair pair = OceanBasePerformanceQueryGenerator.chooseJoinPair(List.of(keyed), right);
            assertTrue(pair.left.isPrimaryKey() || pair.right.isPrimaryKey());
        }
    }
}
