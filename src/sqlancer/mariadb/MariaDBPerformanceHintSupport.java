package sqlancer.mariadb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.mariadb.MariaDBProvider.MariaDBGlobalState;
import sqlancer.mariadb.MariaDBSchema.MariaDBTable;

public final class MariaDBPerformanceHintSupport {

    private static final String OPTIMIZER_PRUNE_LEVEL_DISABLE = "SET SESSION optimizer_prune_level = 0";

    private MariaDBPerformanceHintSupport() {
    }

    public static void refreshStatistics(MariaDBGlobalState state, List<MariaDBTable> tables) throws Exception {
        List<String> statements = buildStatisticsRefreshStatements(tables);
        for (int i = 0; i < statements.size(); i++) {
            String statement = statements.get(i);
            if (i == 0) {
                state.executeStatement(new SQLQueryAdapter(statement, getOptionalStatsRefreshErrors()));
            } else {
                state.executeStatement(new SQLQueryAdapter(statement));
            }
        }
    }

    static List<String> buildStatisticsRefreshStatements(List<MariaDBTable> tables) {
        List<MariaDBTable> distinctTables = deduplicateTables(tables);
        if (distinctTables.isEmpty()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        statements.add(OPTIMIZER_PRUNE_LEVEL_DISABLE);
        // PERSISTENT collects engine statistics too; avoid an additional ordinary ANALYZE of the same dataset.
        for (MariaDBTable table : distinctTables) {
            statements.add(buildPersistentAnalyzeStatement(table));
        }
        return statements;
    }

    static String buildAnalyzeTableStatement(List<MariaDBTable> tables) {
        List<MariaDBTable> distinctTables = deduplicateTables(tables);
        if (distinctTables.isEmpty()) {
            throw new IllegalArgumentException("tables must not be empty");
        }
        return "ANALYZE TABLE " + distinctTables.stream().map(MariaDBTable::getName).collect(Collectors.joining(", "));
    }

    static String buildPersistentAnalyzeStatement(MariaDBTable table) {
        return "ANALYZE TABLE " + table.getName() + " PERSISTENT FOR ALL";
    }

    private static List<MariaDBTable> deduplicateTables(List<MariaDBTable> tables) {
        Map<String, MariaDBTable> byName = new LinkedHashMap<>();
        for (MariaDBTable table : tables) {
            byName.putIfAbsent(table.getName(), table);
        }
        return new ArrayList<>(byName.values());
    }

    private static ExpectedErrors getOptionalStatsRefreshErrors() {
        return ExpectedErrors.from("SESSION variable 'optimizer_prune_level' is read-only");
    }
}
