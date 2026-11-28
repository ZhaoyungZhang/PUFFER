package sqlancer.oceanbase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseTable;

/** Statistics maintenance for the OceanBase performance oracle. */
public final class OceanBasePerformanceHintSupport {

    public enum RefreshMode {
        INITIAL_DATASET, QUERY_VALIDATION
    }

    private OceanBasePerformanceHintSupport() {
    }

    public static void refreshStatistics(OceanBaseGlobalState state, List<OceanBaseTable> tables) throws Exception {
        refreshStatistics(state, tables, RefreshMode.QUERY_VALIDATION);
    }

    public static void refreshStatistics(OceanBaseGlobalState state, List<OceanBaseTable> tables, RefreshMode mode)
            throws Exception {
        // OceanBase CE's MySQL mode supports ANALYZE TABLE. Deliberately do not issue MySQL histogram statements.
        for (String statement : buildStatisticsRefreshStatements(tables)) {
            state.executeStatement(new SQLQueryAdapter(statement));
        }
    }

    static List<String> buildStatisticsRefreshStatements(List<OceanBaseTable> tables) {
        List<OceanBaseTable> distinct = deduplicateTables(tables);
        if (distinct.isEmpty()) {
            return List.of();
        }
        return List.of(
                "ANALYZE TABLE " + distinct.stream().map(OceanBaseTable::getName).collect(Collectors.joining(", ")));
    }

    static String buildAnalyzeTableStatement(List<OceanBaseTable> tables) {
        List<String> statements = buildStatisticsRefreshStatements(tables);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("tables must not be empty");
        }
        return statements.get(0);
    }

    private static List<OceanBaseTable> deduplicateTables(List<OceanBaseTable> tables) {
        Map<String, OceanBaseTable> byName = new LinkedHashMap<>();
        for (OceanBaseTable table : tables) {
            byName.putIfAbsent(table.getName(), table);
        }
        return new ArrayList<>(byName.values());
    }
}
