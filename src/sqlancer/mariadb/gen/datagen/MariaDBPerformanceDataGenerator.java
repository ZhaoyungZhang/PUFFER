package sqlancer.mariadb.gen.datagen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.common.perfhint.PerformanceHintDataGenerationConfig;
import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;
import sqlancer.common.perfhint.PerformanceHintDataPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.ColumnPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.TablePlan;
import sqlancer.common.perfhint.PerformanceHintDataPlanner;
import sqlancer.common.perfhint.pdg.PdgIrContext;
import sqlancer.common.perfhint.pdg.PdgIrMaterializer;
import sqlancer.common.perfhint.pdg.PdgIrPlanner;
import sqlancer.common.perfhint.pdg.PdgMaterializedDatabase;
import sqlancer.common.perfhint.pdg.PdgSpecification;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.mariadb.MariaDBPerformanceInsertBatchSizeValidator;
import sqlancer.mariadb.MariaDBProvider.MariaDBGlobalState;
import sqlancer.mariadb.oracle.MariaDBPerformanceHintTelemetry;

public final class MariaDBPerformanceDataGenerator {

    private MariaDBPerformanceDataGenerator() {
    }

    public static PerformanceHintDataPlan generate(MariaDBGlobalState globalState) throws Exception {
        globalState.setPdgIrContext(null);
        PerformanceHintDataGenerationConfig config = new PerformanceHintDataGenerationConfig(
                globalState.getDbmsSpecificOptions().performanceMinTables,
                globalState.getDbmsSpecificOptions().performanceMaxTables,
                globalState.getDbmsSpecificOptions().performanceMinRowsPerTable,
                globalState.getDbmsSpecificOptions().performanceMaxRowsPerTable,
                globalState.getDbmsSpecificOptions().performanceMinColumns,
                globalState.getDbmsSpecificOptions().performanceMaxColumns,
                globalState.getDbmsSpecificOptions().performanceMaxIndexesPerTable,
                globalState.getDbmsSpecificOptions().performanceSingleColumnModelProbability,
                globalState.getDbmsSpecificOptions().performanceMultiColumnModelProbability,
                globalState.getDbmsSpecificOptions().performanceInterTableModelProbability,
                globalState.getDbmsSpecificOptions().performanceDataAblationProfile,
                globalState.getDbmsSpecificOptions().performanceInitialScale);
        PerformanceHintDataPlan plan = PerformanceHintDataPlanner.create(config, globalState.getRandomly());
        PdgSpecification specification = PdgIrPlanner.resolve("mariadb", globalState.getState().getSeedValue(), plan);
        PdgMaterializedDatabase materialized = PdgIrMaterializer.materialize(specification);
        List<List<List<Object>>> rows = materialized.getRows();
        globalState.setPerformanceDataModelDescription(specification.describe());
        globalState.setPdgIrContext(
                new PdgIrContext(specification, materialized.getProfiles(), materialized.getInMemoryIndex()));

        if (globalState.getOptions().logEachSelect()) {
            globalState.getLogger().writeCurrent("-- PERF_HINT_DATA_MODEL " + specification.describe());
        }
        MariaDBPerformanceHintTelemetry telemetry = MariaDBPerformanceHintTelemetry.getInstance(globalState);
        long startedAt = System.nanoTime();
        try {
            createTables(globalState, plan);
        } finally {
            telemetry.add("data_generation.create_table_nanos", System.nanoTime() - startedAt);
        }
        startedAt = System.nanoTime();
        try {
            insertRows(globalState, plan, rows, telemetry);
        } finally {
            telemetry.add("data_generation.insert_nanos", System.nanoTime() - startedAt);
        }
        startedAt = System.nanoTime();
        try {
            createIndexes(globalState, plan);
        } finally {
            telemetry.add("data_generation.create_index_nanos", System.nanoTime() - startedAt);
        }
        globalState.updateSchema();
        return plan;
    }

    private static void createTables(MariaDBGlobalState globalState, PerformanceHintDataPlan plan) throws Exception {
        for (TablePlan table : plan.getTables()) {
            String columns = table.getColumns().stream()
                    .map(column -> column.getName() + " " + column.getSqlType().getSql())
                    .collect(Collectors.joining(", "));
            executeRequired(globalState,
                    new SQLQueryAdapter("CREATE TABLE " + table.getName() + "(" + columns + ") ENGINE=InnoDB", true));
        }
    }

    private static void insertRows(MariaDBGlobalState globalState, PerformanceHintDataPlan plan,
            List<List<List<Object>>> allRows, MariaDBPerformanceHintTelemetry telemetry) throws Exception {
        int batchSize = globalState.getDbmsSpecificOptions().performanceInsertBatchSize;
        if (batchSize < 1 || batchSize > MariaDBPerformanceInsertBatchSizeValidator.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("MariaDB PERFORMANCE_HINT INSERT batch size must be in [1, "
                    + MariaDBPerformanceInsertBatchSizeValidator.MAX_BATCH_SIZE + "]");
        }
        for (int tableIndex = 0; tableIndex < plan.getTables().size(); tableIndex++) {
            TablePlan table = plan.getTables().get(tableIndex);
            List<List<Object>> rows = allRows.get(tableIndex);
            telemetry.add("data_generation.insert_rows", rows.size());
            for (int offset = 0; offset < rows.size(); offset += batchSize) {
                int end = Math.min(rows.size(), offset + batchSize);
                String statement = buildInsertStatement(table, rows, offset, end);
                int statementBytes = statement.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                telemetry.count("data_generation.insert_batches");
                telemetry.add("data_generation.insert_sql_bytes", statementBytes);
                telemetry.recordMaximum("data_generation.max_insert_sql_bytes", statementBytes);
                try {
                    executeRequired(globalState, new SQLQueryAdapter(statement));
                } catch (Exception exception) {
                    telemetry.count("data_generation.failures");
                    throw exception;
                }
            }
        }
    }

    /*
     * Renders exactly one contiguous slice of pre-generated rows. Keeping this separate from execution makes it
     * explicit that changing batch size only changes statement boundaries, never the schema or generated values.
     */
    static String buildInsertStatement(TablePlan table, List<List<Object>> rows, int startInclusive, int endExclusive) {
        if (startInclusive < 0 || endExclusive > rows.size() || startInclusive >= endExclusive) {
            throw new IllegalArgumentException("invalid INSERT row range");
        }
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(table.getName()).append("(");
        sql.append(table.getColumns().stream().map(ColumnPlan::getName).collect(Collectors.joining(", ")));
        sql.append(") VALUES ");
        for (int rowIndex = startInclusive; rowIndex < endExclusive; rowIndex++) {
            if (rowIndex > startInclusive) {
                sql.append(", ");
            }
            appendRow(sql, rows.get(rowIndex), table.getColumns());
        }
        return sql.toString();
    }

    private static void appendRow(StringBuilder sql, List<Object> row, List<ColumnPlan> columns) {
        sql.append("(");
        for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
            if (columnIndex != 0) {
                sql.append(", ");
            }
            sql.append(toSqlLiteral(row.get(columnIndex), columns.get(columnIndex).getSqlType()));
        }
        sql.append(")");
    }

    private static String toSqlLiteral(Object value, SqlType type) {
        if (value == null) {
            return "NULL";
        }
        if (type == SqlType.VARCHAR) {
            return "'" + value.toString().replace("'", "''") + "'";
        }
        if (type == SqlType.DOUBLE) {
            return Double.toString(((Number) value).doubleValue());
        }
        return Long.toString(((Number) value).longValue());
    }

    private static void createIndexes(MariaDBGlobalState globalState, PerformanceHintDataPlan plan) throws Exception {
        int indexNumber = 0;
        for (TablePlan table : plan.getTables()) {
            for (List<Integer> index : table.getIndexes()) {
                String columns = index.stream().map(columnIndex -> table.getColumns().get(columnIndex).getName())
                        .collect(Collectors.joining(", "));
                String sql = "CREATE INDEX i" + indexNumber++ + " ON " + table.getName() + "(" + columns + ")";
                executeRequired(globalState, new SQLQueryAdapter(sql, true));
            }
        }
    }

    private static void executeRequired(MariaDBGlobalState globalState, SQLQueryAdapter query) throws Exception {
        if (!globalState.executeStatement(query)) {
            throw new IgnoreMeException();
        }
    }
}
