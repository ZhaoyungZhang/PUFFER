package sqlancer.oceanbase.gen.datagen;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import sqlancer.oceanbase.OceanBaseGlobalState;
import sqlancer.oceanbase.OceanBasePerformanceInsertBatchSizeValidator;
import sqlancer.oceanbase.oracle.OceanBasePerformanceHintTelemetry;

/** Builds a reproducible, skewed data set that gives OceanBase optimizer hints meaningful alternatives. */
public final class OceanBasePerformanceDataGenerator {

    private OceanBasePerformanceDataGenerator() {
    }

    public static PerformanceHintDataPlan generate(OceanBaseGlobalState globalState) throws Exception {
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
        PdgSpecification specification = PdgIrPlanner.resolve("oceanbase", globalState.getState().getSeedValue(), plan);
        PdgMaterializedDatabase materialized = PdgIrMaterializer.materialize(specification);
        List<List<List<Object>>> rows = materialized.getRows();
        globalState.setPerformanceDataModelDescription(specification.describe());
        globalState.setPdgIrContext(
                new PdgIrContext(specification, materialized.getProfiles(), materialized.getInMemoryIndex()));
        if (globalState.getOptions().logEachSelect()) {
            globalState.getLogger().writeCurrent("-- PERF_HINT_DATA_MODEL " + specification.describe());
        }
        OceanBasePerformanceHintTelemetry telemetry = OceanBasePerformanceHintTelemetry.getInstance(globalState);
        boolean inlineIndexes = isInlineDdlMode();
        long startedAt = System.nanoTime();
        try {
            createTables(globalState, plan, inlineIndexes);
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
                createIndexes(globalState, plan);
            } finally {
                telemetry.add("data_generation.create_index_nanos", System.nanoTime() - startedAt);
            }
        }
        globalState.updateSchema();
        return plan;
    }

    private static boolean isInlineDdlMode() {
        String mode = System.getProperty("ob.ddl.mode", System.getenv().getOrDefault("OB_DDL_MODE", "inline"));
        return !"separate".equalsIgnoreCase(mode.trim());
    }

    private static void createTables(OceanBaseGlobalState globalState, PerformanceHintDataPlan plan,
            boolean inlineIndexes) throws Exception {
        int[] indexCounter = new int[1];
        for (TablePlan table : plan.getTables()) {
            List<String> definitions = new ArrayList<>();
            for (ColumnPlan column : table.getColumns()) {
                definitions.add(column.getName() + " " + column.getSqlType().getSql());
            }
            if (inlineIndexes) {
                for (IndexDef def : collectIndexDefs(table, indexCounter)) {
                    definitions.add("INDEX " + def.name + "(" + def.columns + ")");
                }
            }
            // The shared model intentionally permits duplicate/skewed c0 values and relationship fan-out. Do not
            // turn it into a primary key: that would reject valid generated distributions before they reach the
            // optimizer. OceanBase supports tables without an explicit primary key in MySQL mode.
            executeRequired(globalState, new SQLQueryAdapter(
                    "CREATE TABLE " + table.getName() + "(" + String.join(", ", definitions) + ")", true));
        }
    }

    private static List<IndexDef> collectIndexDefs(TablePlan table, int[] indexCounter) {
        List<IndexDef> defs = new ArrayList<>();
        boolean createdNonPrimaryIndex = false;
        for (List<Integer> index : table.getIndexes()) {
            if (index.size() == 1 && index.get(0) == 0) {
                continue; // The primary key already covers this access path.
            }
            String columns = index.stream().map(i -> table.getColumns().get(i).getName())
                    .collect(Collectors.joining(", "));
            defs.add(new IndexDef("i" + indexCounter[0]++, columns));
            createdNonPrimaryIndex = true;
        }
        if (!createdNonPrimaryIndex && table.getColumns().size() > 1) {
            defs.add(new IndexDef("i" + indexCounter[0]++, table.getColumns().get(1).getName()));
        }
        return defs;
    }

    private static final class IndexDef {
        private final String name;
        private final String columns;

        private IndexDef(String name, String columns) {
            this.name = name;
            this.columns = columns;
        }
    }

    private static void insertRows(OceanBaseGlobalState globalState, PerformanceHintDataPlan plan,
            List<List<List<Object>>> allRows, OceanBasePerformanceHintTelemetry telemetry) throws Exception {
        int batchSize = globalState.getDbmsSpecificOptions().performanceInsertBatchSize;
        if (batchSize < 1 || batchSize > OceanBasePerformanceInsertBatchSizeValidator.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("OceanBase PERFORMANCE_HINT INSERT batch size must be in [1, "
                    + OceanBasePerformanceInsertBatchSizeValidator.MAX_BATCH_SIZE + "]");
        }
        for (int tableIndex = 0; tableIndex < plan.getTables().size(); tableIndex++) {
            TablePlan table = plan.getTables().get(tableIndex);
            List<List<Object>> rows = allRows.get(tableIndex);
            telemetry.add("data_generation.insert_rows", rows.size());
            for (int offset = 0; offset < rows.size(); offset += batchSize) {
                int end = Math.min(rows.size(), offset + batchSize);
                String statement = buildInsertStatement(table, rows, offset, end);
                int statementBytes = statement.getBytes(StandardCharsets.UTF_8).length;
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

    /* Renders one contiguous slice; changing batch size never changes generated values. */
    static String buildInsertStatement(TablePlan table, List<List<Object>> rows, int startInclusive, int endExclusive) {
        if (startInclusive < 0 || endExclusive > rows.size() || startInclusive >= endExclusive) {
            throw new IllegalArgumentException("invalid INSERT row range");
        }
        String columns = table.getColumns().stream().map(ColumnPlan::getName).collect(Collectors.joining(", "));
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table.getName()).append("(").append(columns)
                .append(") VALUES ");
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
            if (columnIndex > 0) {
                sql.append(", ");
            }
            sql.append(toSqlLiteral(row.get(columnIndex), columns.get(columnIndex).getSqlType()));
        }
        sql.append(")");
    }

    static String toSqlLiteral(Object value, SqlType type) {
        if (value == null) {
            return "NULL";
        }
        if (type == SqlType.VARCHAR) {
            return "'" + value.toString().replace("'", "''").replace("\\", "\\\\") + "'";
        }
        if (type == SqlType.DOUBLE) {
            return Double.toString(((Number) value).doubleValue());
        }
        return Long.toString(((Number) value).longValue());
    }

    private static void createIndexes(OceanBaseGlobalState globalState, PerformanceHintDataPlan plan) throws Exception {
        int[] indexCounter = new int[1];
        for (TablePlan table : plan.getTables()) {
            for (IndexDef def : collectIndexDefs(table, indexCounter)) {
                executeRequired(globalState, new SQLQueryAdapter(
                        "CREATE INDEX " + def.name + " ON " + table.getName() + "(" + def.columns + ")", true));
            }
        }
    }

    private static void executeRequired(OceanBaseGlobalState globalState, SQLQueryAdapter query) throws Exception {
        if (!globalState.executeStatement(query)) {
            throw new IgnoreMeException();
        }
    }
}
