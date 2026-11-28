package sqlancer.cockroachdb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.cockroachdb.CockroachDBProvider.CockroachDBGlobalState;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBTable;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;

public final class CockroachDBPerformanceHintSupport {

    private static final String DISABLE_AUTO_STATS = "SET CLUSTER SETTING sql.stats.automatic_collection.enabled = 'off'";
    private static final int DEFAULT_PERFORMANCE_ROW_COUNT = 24;

    private CockroachDBPerformanceHintSupport() {
    }

    public static void initializePerformanceSchema(CockroachDBGlobalState state, int tableCount) throws Exception {
        int effectiveTableCount = Math.max(2, tableCount);
        for (int tableIndex = 0; tableIndex < effectiveTableCount; tableIndex++) {
            executeRequired(state, new SQLQueryAdapter(buildCreateTableStatement(tableIndex), true));
        }
        for (int tableIndex = 0; tableIndex < effectiveTableCount; tableIndex++) {
            executeRequired(state,
                    new SQLQueryAdapter(buildInsertStatement(tableIndex, DEFAULT_PERFORMANCE_ROW_COUNT)));
        }
        state.updateSchema();
        state.setPerformanceDataModelDescription("cockroach-stable-perf-schema tables=" + effectiveTableCount
                + " rows_per_table=" + DEFAULT_PERFORMANCE_ROW_COUNT);
    }

    public static void refreshStatistics(CockroachDBGlobalState state, List<CockroachDBTable> tables) throws Exception {
        List<String> statements = buildStatisticsRefreshStatements(tables);
        for (int i = 0; i < statements.size(); i++) {
            String statement = statements.get(i);
            if (i == 0) {
                state.executeStatement(new SQLQueryAdapter(statement,
                        ExpectedErrors.from("unknown cluster setting", "setting updated but timed out waiting")));
            } else {
                state.executeStatement(new SQLQueryAdapter(statement));
            }
        }
    }

    static List<String> buildStatisticsRefreshStatements(List<CockroachDBTable> tables) {
        List<CockroachDBTable> distinctTables = deduplicateTables(tables);
        if (distinctTables.isEmpty()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        statements.add(DISABLE_AUTO_STATS);
        statements.addAll(distinctTables.stream().map(CockroachDBPerformanceHintSupport::buildAnalyzeTableStatement)
                .collect(Collectors.toList()));
        return statements;
    }

    static String buildAnalyzeTableStatement(CockroachDBTable table) {
        return "ANALYZE " + table.getName();
    }

    static String buildCreateTableStatement(int tableIndex) {
        return "CREATE TABLE t" + tableIndex + "(" + "c0 INT8 PRIMARY KEY, " + "c1 INT8, " + "c2 STRING, "
                + "c3 FLOAT8, " + "c4 BOOL, " + "INDEX i" + tableIndex + "_c1 (c1), " + "INDEX i" + tableIndex
                + "_c2 (c2), " + "INDEX i" + tableIndex + "_c3 (c3)" + ")";
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

    private static List<CockroachDBTable> deduplicateTables(List<CockroachDBTable> tables) {
        Map<String, CockroachDBTable> byName = new LinkedHashMap<>();
        for (CockroachDBTable table : tables) {
            byName.putIfAbsent(table.getName(), table);
        }
        return new ArrayList<>(byName.values());
    }

    private static void executeRequired(CockroachDBGlobalState state, SQLQueryAdapter query) throws Exception {
        if (!state.executeStatement(query)) {
            throw new IgnoreMeException();
        }
    }
}
