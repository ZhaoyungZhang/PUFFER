package sqlancer.mysql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import com.google.auto.service.AutoService;

import sqlancer.AbstractAction;
import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.SQLProviderAdapter;
import sqlancer.StatementExecutor;
import sqlancer.common.DBMSCommon;
import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;
import sqlancer.common.perfhint.PerformanceHintDataGenerationConfig;
import sqlancer.common.perfhint.PerformanceHintStatisticsRefresh;
import sqlancer.common.qpg.QPGPlanCanonicalizer;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLQueryProvider;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.mysql.MySQLOptions.MySQLOracleFactory;
import sqlancer.mysql.MySQLSchema.MySQLColumn;
import sqlancer.mysql.MySQLSchema.MySQLDataType;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.gen.MySQLAlterTable;
import sqlancer.mysql.gen.MySQLDeleteGenerator;
import sqlancer.mysql.gen.MySQLDropIndex;
import sqlancer.mysql.gen.MySQLInsertGenerator;
import sqlancer.mysql.gen.MySQLSetGenerator;
import sqlancer.mysql.gen.MySQLTableGenerator;
import sqlancer.mysql.gen.MySQLTruncateTableGenerator;
import sqlancer.mysql.gen.MySQLUpdateGenerator;
import sqlancer.mysql.gen.admin.MySQLFlush;
import sqlancer.mysql.gen.admin.MySQLReset;
import sqlancer.mysql.gen.datadef.MySQLIndexGenerator;
import sqlancer.mysql.gen.datagen.MySQLPerformanceDataGenerator;
import sqlancer.mysql.gen.tblmaintenance.MySQLAnalyzeTable;
import sqlancer.mysql.gen.tblmaintenance.MySQLCheckTable;
import sqlancer.mysql.gen.tblmaintenance.MySQLChecksum;
import sqlancer.mysql.gen.tblmaintenance.MySQLOptimize;
import sqlancer.mysql.gen.tblmaintenance.MySQLRepair;
import sqlancer.mysql.oracle.MySQLPerformanceHintTelemetry;

@AutoService(DatabaseProvider.class)
public class MySQLProvider extends SQLProviderAdapter<MySQLGlobalState, MySQLOptions> {

    private static final Action[] QPG_MUTATION_ACTIONS = { Action.INSERT, Action.UPDATE, Action.DELETE,
            Action.CREATE_INDEX };
    private static final int PERFORMANCE_HINT_DEFAULT_MIN_TABLES = 3;
    private static final int PERFORMANCE_HINT_DEFAULT_MAX_TABLES = 5;
    private static final int PERFORMANCE_HINT_DEFAULT_MIN_INSERT_ACTIONS = 15;
    private static final int PERFORMANCE_HINT_DEFAULT_MAX_INSERT_ACTIONS = 60;
    private static final int PERFORMANCE_HINT_DEFAULT_MIN_CREATE_INDEX_ACTIONS = 2;
    private static final int PERFORMANCE_HINT_DEFAULT_MAX_CREATE_INDEX_ACTIONS = 6;
    private static final int PERFORMANCE_HINT_DEFAULT_MAX_DELETE_ACTIONS = 1;
    private static final int PERFORMANCE_HINT_DEFAULT_MAX_UPDATE_ACTIONS = 1;
    private static final int PERFORMANCE_HINT_DEFAULT_TOP_UP_INSERTS_PER_TABLE = 2;

    private static final String ENV_MIN_TABLES = "PERF_HINT_MIN_TABLES";
    private static final String ENV_MAX_TABLES = "PERF_HINT_MAX_TABLES";
    private static final String ENV_MIN_INSERT_ACTIONS = "PERF_HINT_MIN_INSERT_ACTIONS";
    private static final String ENV_MAX_INSERT_ACTIONS = "PERF_HINT_MAX_INSERT_ACTIONS";
    private static final String ENV_MIN_CREATE_INDEX_ACTIONS = "PERF_HINT_MIN_CREATE_INDEX_ACTIONS";
    private static final String ENV_MAX_CREATE_INDEX_ACTIONS = "PERF_HINT_MAX_CREATE_INDEX_ACTIONS";
    private static final String ENV_MAX_DELETE_ACTIONS = "PERF_HINT_MAX_DELETE_ACTIONS";
    private static final String ENV_MAX_UPDATE_ACTIONS = "PERF_HINT_MAX_UPDATE_ACTIONS";
    private static final String ENV_TOP_UP_INSERTS_PER_TABLE = "PERF_HINT_TOP_UP_INSERTS_PER_TABLE";

    public MySQLProvider() {
        super(MySQLGlobalState.class, MySQLOptions.class);
    }

    enum Action implements AbstractAction<MySQLGlobalState> {
        SHOW_TABLES((g) -> new SQLQueryAdapter("SHOW TABLES")), //
        INSERT(MySQLInsertGenerator::insertRow), //
        SET_VARIABLE(MySQLSetGenerator::set), //
        REPAIR(MySQLRepair::repair), //
        OPTIMIZE(MySQLOptimize::optimize), //
        CHECKSUM(MySQLChecksum::checksum), //
        CHECK_TABLE(MySQLCheckTable::check), //
        ANALYZE_TABLE(MySQLAnalyzeTable::analyze), //
        FLUSH(MySQLFlush::create), RESET(MySQLReset::create), CREATE_INDEX(MySQLIndexGenerator::create), //
        ALTER_TABLE(MySQLAlterTable::create), //
        TRUNCATE_TABLE(MySQLTruncateTableGenerator::generate), //
        SELECT_INFO((g) -> new SQLQueryAdapter(
                "select TABLE_NAME, ENGINE from information_schema.TABLES where table_schema = '" + g.getDatabaseName()
                        + "'")), //
        UPDATE(MySQLUpdateGenerator::create), //
        DELETE(MySQLDeleteGenerator::delete), //
        DROP_INDEX(MySQLDropIndex::generate);

        private final SQLQueryProvider<MySQLGlobalState> sqlQueryProvider;

        Action(SQLQueryProvider<MySQLGlobalState> sqlQueryProvider) {
            this.sqlQueryProvider = sqlQueryProvider;
        }

        @Override
        public SQLQueryAdapter getQuery(MySQLGlobalState globalState) throws Exception {
            return sqlQueryProvider.getQuery(globalState);
        }
    }

    private static int mapActions(MySQLGlobalState globalState, Action a) {
        Randomly r = globalState.getRandomly();
        boolean performanceHint = usesOracle(globalState, MySQLOracleFactory.PERFORMANCE_HINT);
        int nrPerformed = 0;
        switch (a) {
        case DROP_INDEX:
            nrPerformed = performanceHint ? 0 : r.getInteger(0, 2);
            break;
        case SHOW_TABLES:
            nrPerformed = r.getInteger(0, 1);
            break;
        case INSERT:
            if (performanceHint) {
                nrPerformed = r.getInteger(getPerformanceHintMinInsertActions(),
                        getPerformanceHintMaxInsertActions() + 1);
            } else {
                nrPerformed = r.getInteger(0, globalState.getOptions().getMaxNumberInserts());
            }
            break;
        case REPAIR:
            nrPerformed = performanceHint ? 0 : r.getInteger(0, 1);
            break;
        case SET_VARIABLE:
            nrPerformed = usesOracle(globalState, MySQLOracleFactory.PERFORMANCE_HINT) ? 0 : r.getInteger(0, 5);
            break;
        case CREATE_INDEX:
            if (performanceHint) {
                nrPerformed = r.getInteger(getPerformanceHintMinCreateIndexActions(),
                        getPerformanceHintMaxCreateIndexActions() + 1);
            } else {
                nrPerformed = r.getInteger(0, 5);
            }
            break;
        case FLUSH:
            nrPerformed = performanceHint ? 0 : (Randomly.getBooleanWithSmallProbability() ? r.getInteger(0, 1) : 0);
            break;
        case OPTIMIZE:
            // seems to yield low CPU utilization
            nrPerformed = performanceHint ? 0 : (Randomly.getBooleanWithSmallProbability() ? r.getInteger(0, 1) : 0);
            break;
        case RESET:
            // affects the global state, so do not execute
            nrPerformed = performanceHint ? 0
                    : (globalState.getOptions().getNumberConcurrentThreads() == 1 ? r.getInteger(0, 1) : 0);
            break;
        case CHECKSUM:
        case CHECK_TABLE:
        case ANALYZE_TABLE:
            nrPerformed = performanceHint ? 0 : r.getInteger(0, 2);
            break;
        case ALTER_TABLE:
            nrPerformed = performanceHint ? 0 : r.getInteger(0, 5);
            break;
        case TRUNCATE_TABLE:
            nrPerformed = performanceHint ? 0 : r.getInteger(0, 2);
            break;
        case SELECT_INFO:
            nrPerformed = performanceHint ? r.getInteger(0, 2) : r.getInteger(0, 10);
            break;
        case UPDATE:
            nrPerformed = performanceHint ? r.getInteger(0, getPerformanceHintMaxUpdateActions() + 1)
                    : r.getInteger(0, 10);
            break;
        case DELETE:
            nrPerformed = performanceHint ? r.getInteger(0, getPerformanceHintMaxDeleteActions() + 1)
                    : r.getInteger(0, 10);
            break;
        default:
            throw new AssertionError(a);
        }
        if (performanceHint && globalState.getOptions().logEachSelect()) {
            globalState.getLogger().writeCurrent(String.format("-- PH_ACTION %s count=%d%n", a.name(), nrPerformed));
        }
        return nrPerformed;
    }

    @Override
    public void generateDatabase(MySQLGlobalState globalState) throws Exception {
        MySQLOptions options = globalState.getDbmsSpecificOptions();
        if (options.performanceDataAblationProfile != PerformanceHintDataAblationProfile.NONE
                && (!usesOracle(globalState, MySQLOracleFactory.PERFORMANCE_HINT)
                        || !PerformanceHintDataGenerationConfig.isModelV1(options.performanceDataGeneratorMode))) {
            throw new IllegalArgumentException("--perf-data-ablation-profile requires mysql --oracle PERFORMANCE_HINT "
                    + "--perf-data-generator-mode model-v1");
        }
        if (usesOracle(globalState, MySQLOracleFactory.PERFORMANCE_HINT) && PerformanceHintDataGenerationConfig
                .isModelV1(globalState.getDbmsSpecificOptions().performanceDataGeneratorMode)) {
            MySQLPerformanceDataGenerator.generate(globalState);
            refreshPerformanceHintStatistics(globalState, "initial_dataset");
            return;
        }

        while (globalState.getSchema().getDatabaseTables().size() < getTargetTableCount(globalState)) {
            String tableName = DBMSCommon.createTableName(globalState.getSchema().getDatabaseTables().size());
            SQLQueryAdapter createTable = MySQLTableGenerator.generate(globalState, tableName);
            globalState.executeStatement(createTable);
        }

        StatementExecutor<MySQLGlobalState, Action> se = new StatementExecutor<>(globalState, Action.values(),
                MySQLProvider::mapActions, (q) -> {
                    if (globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        se.executeStatements();

        if (usesOracle(globalState, MySQLOracleFactory.PERFORMANCE_HINT)) {
            topUpTableData(globalState);
            ensureUsableIndexes(globalState);
        }

        if (usesOracle(globalState, MySQLOracleFactory.PERFORMANCE_HINT)) {
            refreshPerformanceHintStatistics(globalState, "initial_dataset");
        } else if (usesOracle(globalState, MySQLOracleFactory.CERT)) {
            MySQLPerformanceHintSupport.refreshStatistics(globalState, globalState.getSchema().getDatabaseTables(),
                    MySQLPerformanceHintSupport.RefreshMode.INITIAL_DATASET);
        }
    }

    @Override
    public SQLConnection createDatabase(MySQLGlobalState globalState) throws SQLException {
        String username = globalState.getOptions().getUserName();
        String password = globalState.getOptions().getPassword();
        String host = globalState.getOptions().getHost();
        int port = globalState.getOptions().getPort();
        if (host == null) {
            host = MySQLOptions.DEFAULT_HOST;
        }
        if (port == MainOptions.NO_SET_PORT) {
            port = MySQLOptions.DEFAULT_PORT;
        }
        String databaseName = globalState.getDatabaseName();
        globalState.getState().logStatement("DROP DATABASE IF EXISTS " + databaseName);
        globalState.getState().logStatement("CREATE DATABASE " + databaseName);
        globalState.getState().logStatement("USE " + databaseName);
        String url = String.format("jdbc:mysql://%s:%d?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                host, port);
        Connection con = DriverManager.getConnection(url, username, password);
        try (Statement s = con.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + databaseName);
        }
        try (Statement s = con.createStatement()) {
            s.execute("CREATE DATABASE " + databaseName);
        }
        try (Statement s = con.createStatement()) {
            s.execute("USE " + databaseName);
        }
        return new SQLConnection(con);
    }

    @Override
    public String getDBMSName() {
        return "mysql";
    }

    @Override
    public String getQueryPlan(String selectStr, MySQLGlobalState globalState) throws Exception {
        if (globalState.getOptions().logEachSelect()) {
            globalState.getLogger().writeCurrent(selectStr);
            globalState.getLogger().getCurrentFileWriter().flush();
        }
        SQLQueryAdapter explainQuery = new SQLQueryAdapter("EXPLAIN FORMAT=JSON " + selectStr);
        try (SQLancerResultSet rs = explainQuery.executeAndGet(globalState)) {
            if (rs == null || !rs.next()) {
                return "";
            }
            return normalizeQPGPlan(rs.getString(1));
        } catch (Throwable e) {
            return "";
        }
    }

    @Override
    protected double[] initializeWeightedAverageReward() {
        return new double[QPG_MUTATION_ACTIONS.length];
    }

    @Override
    protected void executeMutator(int index, MySQLGlobalState globalState) throws Exception {
        SQLQueryAdapter queryMutateTable = QPG_MUTATION_ACTIONS[index].getQuery(globalState);
        if (globalState.executeStatement(queryMutateTable)
                && usesOracle(globalState, MySQLOracleFactory.PERFORMANCE_HINT)) {
            refreshPerformanceHintStatistics(globalState, "qpg_mutation");
        }
    }

    @Override
    protected String getQPGMutatorName(int index) {
        return QPG_MUTATION_ACTIONS[index].name();
    }

    @Override
    public boolean addRowsToAllTables(MySQLGlobalState globalState) throws Exception {
        List<MySQLTable> tablesNoRow = globalState.getSchema().getDatabaseTables().stream()
                .filter(t -> t.getNrRows(globalState) == 0).collect(Collectors.toList());
        boolean rowsAdded = false;
        for (MySQLTable table : tablesNoRow) {
            SQLQueryAdapter queryAddRows = MySQLInsertGenerator.insertRow(globalState, table);
            rowsAdded |= globalState.executeStatement(queryAddRows);
        }
        if (rowsAdded && usesOracle(globalState, MySQLOracleFactory.PERFORMANCE_HINT)) {
            refreshPerformanceHintStatistics(globalState, "row_recovery");
        }
        return true;
    }

    private static int getTargetTableCount(MySQLGlobalState globalState) {
        if (usesOracle(globalState, MySQLOracleFactory.PERFORMANCE_HINT)) {
            return globalState.getRandomly().getInteger(getPerformanceHintMinTables(),
                    getPerformanceHintMaxTables() + 1);
        }
        return (int) Randomly.getNotCachedInteger(1, 2);
    }

    private static void topUpTableData(MySQLGlobalState globalState) throws Exception {
        for (MySQLTable table : globalState.getSchema().getDatabaseTables()) {
            for (int i = 0; i < getPerformanceHintTopUpInsertsPerTable(); i++) {
                globalState.executeStatement(MySQLInsertGenerator.insertRow(globalState, table));
            }
        }
    }

    private static void ensureUsableIndexes(MySQLGlobalState globalState) throws Exception {
        for (MySQLTable table : globalState.getSchema().getDatabaseTables()) {
            if (!table.getIndexes().isEmpty()) {
                continue;
            }
            MySQLColumn column = pickIndexableColumn(table);
            if (column == null) {
                continue;
            }
            String indexName = globalState.getSchema().getFreeIndexName();
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE INDEX ");
            sb.append(indexName);
            sb.append(" ON ");
            sb.append(table.getName());
            sb.append("(");
            sb.append(column.getName());
            if (column.getType() == MySQLDataType.VARCHAR) {
                sb.append("(8)");
            }
            sb.append(")");
            ExpectedErrors errors = new ExpectedErrors();
            errors.add("Specified key was too long");
            errors.add("Duplicate key name");
            errors.add("Duplicate entry");
            errors.add("used in key specification without a key length");
            errors.add("Data truncation");
            SQLQueryAdapter createIndex = new SQLQueryAdapter(sb.toString(), errors, true);
            if (globalState.executeStatement(createIndex)) {
                globalState.updateSchema();
            }
        }
    }

    private static MySQLColumn pickIndexableColumn(MySQLTable table) {
        return table.getColumns().stream().filter(column -> column.getType() != null).findFirst().orElse(null);
    }

    private static boolean usesOracle(MySQLGlobalState globalState, MySQLOracleFactory oracle) {
        return globalState.getDbmsSpecificOptions().getTestOracleFactory().stream().anyMatch(o -> o == oracle);
    }

    private static int getPerformanceHintMinTables() {
        return getConfiguredInt(ENV_MIN_TABLES, PERFORMANCE_HINT_DEFAULT_MIN_TABLES);
    }

    private static int getPerformanceHintMaxTables() {
        return getConfiguredIntAtLeast(ENV_MAX_TABLES, PERFORMANCE_HINT_DEFAULT_MAX_TABLES,
                getPerformanceHintMinTables());
    }

    private static int getPerformanceHintMinInsertActions() {
        return getConfiguredInt(ENV_MIN_INSERT_ACTIONS, PERFORMANCE_HINT_DEFAULT_MIN_INSERT_ACTIONS);
    }

    private static int getPerformanceHintMaxInsertActions() {
        return getConfiguredIntAtLeast(ENV_MAX_INSERT_ACTIONS, PERFORMANCE_HINT_DEFAULT_MAX_INSERT_ACTIONS,
                getPerformanceHintMinInsertActions());
    }

    private static int getPerformanceHintMinCreateIndexActions() {
        return getConfiguredInt(ENV_MIN_CREATE_INDEX_ACTIONS, PERFORMANCE_HINT_DEFAULT_MIN_CREATE_INDEX_ACTIONS);
    }

    private static int getPerformanceHintMaxCreateIndexActions() {
        return getConfiguredIntAtLeast(ENV_MAX_CREATE_INDEX_ACTIONS, PERFORMANCE_HINT_DEFAULT_MAX_CREATE_INDEX_ACTIONS,
                getPerformanceHintMinCreateIndexActions());
    }

    private static int getPerformanceHintMaxDeleteActions() {
        return getConfiguredInt(ENV_MAX_DELETE_ACTIONS, PERFORMANCE_HINT_DEFAULT_MAX_DELETE_ACTIONS);
    }

    private static int getPerformanceHintMaxUpdateActions() {
        return getConfiguredInt(ENV_MAX_UPDATE_ACTIONS, PERFORMANCE_HINT_DEFAULT_MAX_UPDATE_ACTIONS);
    }

    private static int getPerformanceHintTopUpInsertsPerTable() {
        return getConfiguredInt(ENV_TOP_UP_INSERTS_PER_TABLE, PERFORMANCE_HINT_DEFAULT_TOP_UP_INSERTS_PER_TABLE);
    }

    private static int getConfiguredInt(String envName, int defaultValue) {
        String rawValue = System.getenv(envName);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int getConfiguredIntAtLeast(String envName, int defaultValue, int minValue) {
        return Math.max(minValue, getConfiguredInt(envName, defaultValue));
    }

    private static String normalizeQPGPlan(String explainJson) {
        return QPGPlanCanonicalizer.normalizeMySQLLikeJsonPlan(explainJson);
    }

    private static void refreshPerformanceHintStatistics(MySQLGlobalState globalState, String reason) throws Exception {
        PerformanceHintStatisticsRefresh.run(MySQLPerformanceHintTelemetry.getInstance(globalState), reason,
                () -> MySQLPerformanceHintSupport.refreshStatistics(globalState,
                        globalState.getSchema().getDatabaseTables()));
    }

}
