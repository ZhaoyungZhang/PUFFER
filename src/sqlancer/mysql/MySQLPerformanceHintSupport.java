package sqlancer.mysql;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.mysql.MySQLSchema.MySQLColumn;
import sqlancer.mysql.MySQLSchema.MySQLTable;

public final class MySQLPerformanceHintSupport {

    private static final String INFORMATION_SCHEMA_STATS_EXPIRY_RESET = "SET SESSION information_schema_stats_expiry = 0";
    private static final String OPTIMIZER_PRUNE_LEVEL_DISABLE = "SET SESSION optimizer_prune_level = 0";

    public enum RefreshMode {
        INITIAL_DATASET, QUERY_VALIDATION
    }

    private MySQLPerformanceHintSupport() {
    }

    public static void refreshStatistics(MySQLGlobalState state, List<MySQLTable> tables) throws Exception {
        refreshStatistics(state, tables, RefreshMode.QUERY_VALIDATION);
    }

    public static void refreshStatistics(MySQLGlobalState state, List<MySQLTable> tables, RefreshMode refreshMode)
            throws Exception {
        List<String> statements = buildStatisticsRefreshStatements(tables, shouldRefreshHistograms(state, refreshMode),
                resolveMaxHistogramColumnsPerTable(state, refreshMode));
        for (int i = 0; i < statements.size(); i++) {
            String statement = statements.get(i);
            if (i == 0) {
                state.executeStatement(new SQLQueryAdapter(statement, getOptionalStatsRefreshErrors()));
            } else {
                state.executeStatement(new SQLQueryAdapter(statement));
            }
        }
    }

    static List<String> buildStatisticsRefreshStatements(List<MySQLTable> tables) {
        return buildStatisticsRefreshStatements(tables, true, Integer.MAX_VALUE);
    }

    static List<String> buildStatisticsRefreshStatements(List<MySQLTable> tables, boolean includeHistograms,
            int maxHistogramColumnsPerTable) {
        List<MySQLTable> distinctTables = deduplicateTables(tables);
        if (distinctTables.isEmpty()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        statements.add(INFORMATION_SCHEMA_STATS_EXPIRY_RESET);
        statements.add(OPTIMIZER_PRUNE_LEVEL_DISABLE);
        statements.add(buildAnalyzeTableStatement(distinctTables));
        if (includeHistograms && maxHistogramColumnsPerTable > 0) {
            statements.addAll(buildHistogramStatements(distinctTables, maxHistogramColumnsPerTable));
        }
        return statements;
    }

    static String buildAnalyzeTableStatement(List<MySQLTable> tables) {
        List<MySQLTable> distinctTables = deduplicateTables(tables);
        if (distinctTables.isEmpty()) {
            throw new IllegalArgumentException("tables must not be empty");
        }
        return "ANALYZE TABLE " + distinctTables.stream().map(MySQLTable::getName).collect(Collectors.joining(", "));
    }

    static List<String> buildHistogramStatements(List<MySQLTable> tables, int maxHistogramColumnsPerTable) {
        return deduplicateTables(tables).stream().filter(table -> !table.getColumns().isEmpty())
                .map(table -> buildHistogramStatement(table, maxHistogramColumnsPerTable))
                .filter(statement -> !statement.isEmpty()).collect(Collectors.toList());
    }

    private static String buildHistogramStatement(MySQLTable table, int maxHistogramColumnsPerTable) {
        String columnList = table.getColumns().stream()
                .sorted(Comparator.comparing((MySQLColumn column) -> !column.isPrimaryKey()))
                .limit(maxHistogramColumnsPerTable).map(MySQLColumn::getName).collect(Collectors.joining(", "));
        if (columnList.isEmpty()) {
            return "";
        }
        return "ANALYZE TABLE " + table.getName() + " UPDATE HISTOGRAM ON " + columnList;
    }

    private static List<MySQLTable> deduplicateTables(List<MySQLTable> tables) {
        Map<String, MySQLTable> byName = new LinkedHashMap<>();
        for (MySQLTable table : tables) {
            byName.putIfAbsent(table.getName(), table);
        }
        return new ArrayList<>(byName.values());
    }

    private static ExpectedErrors getOptionalStatsRefreshErrors() {
        return ExpectedErrors.from("Unknown system variable 'information_schema_stats_expiry'",
                "Variable 'information_schema_stats_expiry' can't be set",
                "SESSION variable 'information_schema_stats_expiry' is read-only");
    }

    private static boolean shouldRefreshHistograms(MySQLGlobalState state, RefreshMode refreshMode) {
        if (refreshMode == RefreshMode.INITIAL_DATASET) {
            return false;
        }
        return state.getDbmsSpecificOptions().performanceEnableHistogramRefresh
                && !state.getDbmsSpecificOptions().performanceDisableHistogramRefresh;
    }

    private static int resolveMaxHistogramColumnsPerTable(MySQLGlobalState state, RefreshMode refreshMode) {
        if (refreshMode == RefreshMode.INITIAL_DATASET) {
            return 0;
        }
        return Math.max(0, state.getDbmsSpecificOptions().performanceMaxHistogramColumnsPerTable);
    }
}
