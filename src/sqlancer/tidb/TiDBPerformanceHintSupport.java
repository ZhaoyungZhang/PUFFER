package sqlancer.tidb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBTable;

public final class TiDBPerformanceHintSupport {

    private static final String DISABLE_PREPARED_PLAN_CACHE = "SET SESSION tidb_enable_prepared_plan_cache = OFF";
    private static final String DISABLE_NON_PREPARED_PLAN_CACHE = "SET SESSION tidb_enable_non_prepared_plan_cache = OFF";
    private static final String FORCE_ANALYZE_VERSION_2 = "SET SESSION tidb_analyze_version = 2";
    private static final int DEFAULT_PERFORMANCE_ROW_COUNT = 24;

    private TiDBPerformanceHintSupport() {
    }

    public static void initializePerformanceSchema(TiDBGlobalState state, int tableCount) throws Exception {
        int effectiveTableCount = Math.max(3, tableCount);
        for (int tableIndex = 0; tableIndex < effectiveTableCount; tableIndex++) {
            executeRequired(state, new SQLQueryAdapter(buildCreateTableStatement(tableIndex), true));
        }
        for (int tableIndex = 0; tableIndex < effectiveTableCount; tableIndex++) {
            executeRequired(state,
                    new SQLQueryAdapter(buildInsertStatement(tableIndex, DEFAULT_PERFORMANCE_ROW_COUNT)));
        }
        state.updateSchema();
        state.setPerformanceDataModelDescription("tidb-stable-perf-schema tables=" + effectiveTableCount
                + " rows_per_table=" + DEFAULT_PERFORMANCE_ROW_COUNT);
    }

    public static void refreshStatistics(TiDBGlobalState state, List<TiDBTable> tables) throws Exception {
        List<String> statements = buildStatisticsRefreshStatements(tables);
        for (int i = 0; i < statements.size(); i++) {
            String statement = statements.get(i);
            if (i < 3) {
                state.executeStatement(new SQLQueryAdapter(statement, getOptionalStatsRefreshErrors()));
            } else {
                state.executeStatement(new SQLQueryAdapter(statement));
            }
        }
    }

    static List<String> buildStatisticsRefreshStatements(List<TiDBTable> tables) {
        List<TiDBTable> distinctTables = deduplicateTables(tables);
        if (distinctTables.isEmpty()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        statements.add(DISABLE_PREPARED_PLAN_CACHE);
        statements.add(DISABLE_NON_PREPARED_PLAN_CACHE);
        statements.add(FORCE_ANALYZE_VERSION_2);
        statements.add(buildAnalyzeTableStatement(distinctTables));
        return statements;
    }

    static String buildAnalyzeTableStatement(List<TiDBTable> tables) {
        List<TiDBTable> distinctTables = deduplicateTables(tables);
        if (distinctTables.isEmpty()) {
            throw new IllegalArgumentException("tables must not be empty");
        }
        return "ANALYZE TABLE " + distinctTables.stream().map(TiDBTable::getName).collect(Collectors.joining(", "))
                + " ALL COLUMNS";
    }

    private static List<TiDBTable> deduplicateTables(List<TiDBTable> tables) {
        Map<String, TiDBTable> byName = new LinkedHashMap<>();
        for (TiDBTable table : tables) {
            byName.putIfAbsent(table.getName(), table);
        }
        return new ArrayList<>(byName.values());
    }

    private static ExpectedErrors getOptionalStatsRefreshErrors() {
        return ExpectedErrors.from("Unknown system variable", "Variable 'tidb_analyze_version' can't be set",
                "Variable 'tidb_enable_non_prepared_plan_cache' can't be set",
                "Variable 'tidb_enable_prepared_plan_cache' can't be set");
    }

    static String buildCreateTableStatement(int tableIndex) {
        return "CREATE TABLE t" + tableIndex + "(" + "c0 BIGINT NOT NULL, " + "c1 BIGINT, " + "c2 VARCHAR(64), "
                + "c3 DOUBLE, " + "c4 BOOLEAN, " + "PRIMARY KEY (c0), " + "KEY i" + tableIndex + "_c1 (c1), " + "KEY i"
                + tableIndex + "_c2 (c2), " + "KEY i" + tableIndex + "_c3 (c3)" + ")";
    }

    static String buildInsertStatement(int tableIndex, int rowCount) {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO t").append(tableIndex).append("(c0, c1, c2, c3, c4) VALUES ");
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            if (rowIndex != 0) {
                sql.append(", ");
            }
            long primaryKey = tableIndex * 1000L + rowIndex + 1L;
            long bucket = rowIndex % 6L;
            String group = "'grp_" + (rowIndex % 4) + "'";
            double measure = tableIndex * 100.0 + rowIndex * 0.5;
            String c1 = rowIndex % 11 == 0 ? "NULL" : Long.toString(bucket);
            String c2 = rowIndex % 7 == 0 ? "NULL" : group;
            String c3 = rowIndex % 13 == 0 ? "NULL" : Double.toString(measure);
            String c4 = rowIndex % 2 == 0 ? "TRUE" : "FALSE";
            sql.append("(").append(primaryKey).append(", ").append(c1).append(", ").append(c2).append(", ").append(c3)
                    .append(", ").append(c4).append(")");
        }
        return sql.toString();
    }

    private static void executeRequired(TiDBGlobalState state, SQLQueryAdapter query) throws Exception {
        if (!state.executeStatement(query)) {
            throw new IgnoreMeException();
        }
    }
}
