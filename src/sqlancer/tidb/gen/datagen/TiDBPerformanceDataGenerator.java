package sqlancer.tidb.gen.datagen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
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
import sqlancer.tidb.TiDBPerformanceInsertBatchSizeValidator;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.oracle.TiDBPerformanceHintTelemetry;

public final class TiDBPerformanceDataGenerator {

    private static final ConcurrentHashMap<Integer, Semaphore> DDL_SEMAPHORES = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface DdlAction {
        void run() throws Exception;
    }

    private TiDBPerformanceDataGenerator() {
    }

    public static void runWithDdlPermit(DdlAction action) throws Exception {
        Semaphore semaphore = getDdlSemaphore();
        if (semaphore == null) {
            action.run();
            return;
        }
        semaphore.acquire();
        try {
            action.run();
        } finally {
            semaphore.release();
        }
    }

    static boolean isInlineDdlMode() {
        String mode = System.getProperty("tidb.ddl.mode", System.getenv().getOrDefault("TIDB_DDL_MODE", "inline"));
        return !"separate".equalsIgnoreCase(mode.trim());
    }

    private static Semaphore getDdlSemaphore() {
        String raw = System.getProperty("tidb.max.concurrent.ddl",
                System.getenv().getOrDefault("TIDB_MAX_CONCURRENT_DDL", "0"));
        try {
            int permits = Integer.parseInt(raw.trim());
            return permits > 0 ? DDL_SEMAPHORES.computeIfAbsent(permits, Semaphore::new) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static PerformanceHintDataPlan generate(TiDBGlobalState globalState) throws Exception {
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
        PdgSpecification specification = PdgIrPlanner.resolve("tidb", globalState.getState().getSeedValue(), plan);
        PdgMaterializedDatabase materialized = PdgIrMaterializer.materialize(specification);
        List<List<List<Object>>> rows = materialized.getRows();
        globalState.setPerformanceDataModelDescription(specification.describe());
        globalState.setPdgIrContext(
                new PdgIrContext(specification, materialized.getProfiles(), materialized.getInMemoryIndex()));

        if (globalState.getOptions().logEachSelect()) {
            globalState.getLogger().writeCurrent("-- PERF_HINT_DATA_MODEL " + specification.describe());
        }
        TiDBPerformanceHintTelemetry telemetry = TiDBPerformanceHintTelemetry.getInstance(globalState);
        boolean inlineIndexes = isInlineDdlMode();
        long startedAt = System.nanoTime();
        try {
            runWithDdlPermit(() -> createTables(globalState, plan, inlineIndexes));
        } finally {
            telemetry.add("data_generation.create_table_nanos", System.nanoTime() - startedAt);
        }
        startedAt = System.nanoTime();
        try {
            insertRows(globalState, plan, rows, telemetry);
        } finally {
            telemetry.add("data_generation.insert_nanos", System.nanoTime() - startedAt);
        }
        if (!inlineIndexes) {
            startedAt = System.nanoTime();
            try {
                runWithDdlPermit(() -> createIndexes(globalState, plan));
            } finally {
                telemetry.add("data_generation.create_index_nanos", System.nanoTime() - startedAt);
            }
        }
        globalState.updateSchema();
        return plan;
    }

    static List<String> buildCreateTableStatements(PerformanceHintDataPlan plan, boolean inlineIndexes) {
        List<String> statements = new ArrayList<>(plan.getTables().size());
        int indexNumber = 0;
        for (TablePlan table : plan.getTables()) {
            List<String> definitions = new ArrayList<>(
                    table.getColumns().size() + (inlineIndexes ? table.getIndexes().size() : 0));
            for (ColumnPlan column : table.getColumns()) {
                definitions.add(column.getName() + " " + column.getSqlType().getSql());
            }
            if (inlineIndexes) {
                for (List<Integer> index : table.getIndexes()) {
                    String cols = index.stream().map(idx -> table.getColumns().get(idx).getName())
                            .collect(Collectors.joining(", "));
                    definitions.add("INDEX i" + indexNumber++ + " (" + cols + ")");
                }
            }
            statements.add("CREATE TABLE " + table.getName() + "(" + String.join(", ", definitions) + ")");
        }
        return statements;
    }

    private static void createTables(TiDBGlobalState globalState, PerformanceHintDataPlan plan, boolean inlineIndexes)
            throws Exception {
        for (String sql : buildCreateTableStatements(plan, inlineIndexes)) {
            executeRequired(globalState, new SQLQueryAdapter(sql, true));
        }
    }

    private static void insertRows(TiDBGlobalState globalState, PerformanceHintDataPlan plan,
            List<List<List<Object>>> allRows, TiDBPerformanceHintTelemetry telemetry) throws Exception {
        int batchSize = globalState.getDbmsSpecificOptions().performanceInsertBatchSize;
        if (batchSize < 1 || batchSize > TiDBPerformanceInsertBatchSizeValidator.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("TiDB PERFORMANCE_HINT INSERT batch size must be in [1, "
                    + TiDBPerformanceInsertBatchSizeValidator.MAX_BATCH_SIZE + "]");
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

    private static void createIndexes(TiDBGlobalState globalState, PerformanceHintDataPlan plan) throws Exception {
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

    private static void executeRequired(TiDBGlobalState globalState, SQLQueryAdapter query) throws Exception {
        if (!globalState.executeStatement(query)) {
            throw new IgnoreMeException();
        }
    }
}
