package sqlancer.oceanbase;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLQueryProvider;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.oceanbase.OceanBaseOptions.OceanBaseOracleFactory;
import sqlancer.oceanbase.gen.OceanBaseAlterTable;
import sqlancer.oceanbase.gen.OceanBaseDeleteGenerator;
import sqlancer.oceanbase.gen.OceanBaseDropIndex;
import sqlancer.oceanbase.gen.OceanBaseInsertGenerator;
import sqlancer.oceanbase.gen.OceanBaseTableGenerator;
import sqlancer.oceanbase.gen.OceanBaseTruncateTableGenerator;
import sqlancer.oceanbase.gen.OceanBaseUpdateGenerator;
import sqlancer.oceanbase.gen.datadef.OceanBaseIndexGenerator;
import sqlancer.oceanbase.gen.datagen.OceanBasePerformanceDataGenerator;
import sqlancer.oceanbase.oracle.OceanBasePerformanceHintTelemetry;

@AutoService(DatabaseProvider.class)
public class OceanBaseProvider extends SQLProviderAdapter<OceanBaseGlobalState, OceanBaseOptions> {

    private static final Action[] QPG_MUTATION_ACTIONS = { Action.INSERT, Action.UPDATE, Action.DELETE,
            Action.CREATE_INDEX };

    public OceanBaseProvider() {
        super(OceanBaseGlobalState.class, OceanBaseOptions.class);
    }

    enum Action implements AbstractAction<OceanBaseGlobalState> {
        SHOW_TABLES((g) -> new SQLQueryAdapter("SHOW TABLES")), INSERT(OceanBaseInsertGenerator::insertRow),
        CREATE_INDEX(OceanBaseIndexGenerator::create), ALTER_TABLE(OceanBaseAlterTable::create),
        TRUNCATE_TABLE(OceanBaseTruncateTableGenerator::generate),
        SELECT_INFO((g) -> new SQLQueryAdapter(
                "select TABLE_NAME, ENGINE from information_schema.TABLES where table_schema = '" + g.getDatabaseName()
                        + "'")),
        CREATE_TABLE((g) -> {
            String tableName = DBMSCommon.createTableName(g.getSchema().getDatabaseTables().size());

            return OceanBaseTableGenerator.generate(g, tableName);
        }), DELETE(OceanBaseDeleteGenerator::delete), UPDATE(OceanBaseUpdateGenerator::update),
        DROP_INDEX(OceanBaseDropIndex::generate), ANALYZE_TABLE((g) -> new SQLQueryAdapter(
                "ANALYZE TABLE " + Randomly.fromList(g.getSchema().getDatabaseTables()).getName()));

        private final SQLQueryProvider<OceanBaseGlobalState> sqlQueryProvider;

        Action(SQLQueryProvider<OceanBaseGlobalState> sqlQueryProvider) {
            this.sqlQueryProvider = sqlQueryProvider;
        }

        @Override
        public SQLQueryAdapter getQuery(OceanBaseGlobalState globalState) throws Exception {
            return sqlQueryProvider.getQuery(globalState);
        }
    }

    private static int mapActions(OceanBaseGlobalState globalState, Action a) {
        Randomly r = globalState.getRandomly();
        boolean performanceHint = usesOracle(globalState, OceanBaseOracleFactory.PERFORMANCE_HINT);
        int nrPerformed = 0;
        switch (a) {
        case DROP_INDEX:
            nrPerformed = performanceHint ? 0 : r.getInteger(0, 2);
            break;
        case SHOW_TABLES:
            nrPerformed = r.getInteger(0, 1);
            break;
        case CREATE_TABLE:
            nrPerformed = performanceHint ? 0 : r.getInteger(0, 1);
            break;
        case INSERT:
            nrPerformed = performanceHint ? r.getInteger(8, Math.max(9, globalState.getOptions().getMaxNumberInserts()))
                    : r.getInteger(0, globalState.getOptions().getMaxNumberInserts());
            break;
        case CREATE_INDEX:
            nrPerformed = performanceHint ? r.getInteger(2, 5) : r.getInteger(0, 5);
            break;
        case UPDATE:
            nrPerformed = performanceHint ? 0 : r.getInteger(0, 5);
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
        case DELETE:
            nrPerformed = performanceHint ? 0 : r.getInteger(0, 10);
            break;
        case ANALYZE_TABLE:
            nrPerformed = 0; // Statistics are refreshed at deterministic performance/QPG checkpoints.
            break;
        default:
            throw new AssertionError(a);
        }
        return nrPerformed;
    }

    @Override
    public void generateDatabase(OceanBaseGlobalState globalState) throws Exception {
        boolean performanceHint = usesOracle(globalState, OceanBaseOracleFactory.PERFORMANCE_HINT);
        OceanBaseOptions options = globalState.getDbmsSpecificOptions();
        if (options.performanceDataAblationProfile != PerformanceHintDataAblationProfile.NONE && (!performanceHint
                || !PerformanceHintDataGenerationConfig.isModelV1(options.performanceDataGeneratorMode))) {
            throw new IllegalArgumentException(
                    "--perf-data-ablation-profile requires oceanbase --oracle PERFORMANCE_HINT "
                            + "--perf-data-generator-mode model-v1");
        }
        if (performanceHint) {
            // JDBC may expose only MySQL compatibility; persist the full OceanBase build.
            try (Statement statement = globalState.getConnection().createStatement();
                    ResultSet result = statement.executeQuery("SELECT VERSION()")) {
                if (result.next()) {
                    globalState.getState().databaseVersion = result.getString(1);
                }
            }
            if (options.performanceDataAblationProfile != PerformanceHintDataAblationProfile.NONE) {
                try (Statement statement = globalState.getConnection().createStatement()) {
                    statement.execute("SET SESSION ob_enable_plan_cache = 0");
                }
            }
        }
        if (performanceHint && PerformanceHintDataGenerationConfig
                .isModelV1(globalState.getDbmsSpecificOptions().performanceDataGeneratorMode)) {
            OceanBasePerformanceDataGenerator.generate(globalState);
            refreshPerformanceHintStatistics(globalState, "initial_dataset");
            return;
        }
        int targetTableCount = performanceHint
                ? globalState.getRandomly().getInteger(globalState.getDbmsSpecificOptions().performanceMinTables,
                        globalState.getDbmsSpecificOptions().performanceMaxTables + 1)
                : Randomly.smallNumber() + 1;
        while (globalState.getSchema().getDatabaseTables().size() < targetTableCount) {
            String tableName = DBMSCommon.createTableName(globalState.getSchema().getDatabaseTables().size());
            SQLQueryAdapter createTable = OceanBaseTableGenerator.generate(globalState, tableName);
            globalState.executeStatement(createTable);
        }

        StatementExecutor<OceanBaseGlobalState, Action> se = new StatementExecutor<>(globalState, Action.values(),
                OceanBaseProvider::mapActions, (q) -> {
                    if (globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        se.executeStatements();
        if (performanceHint) {
            refreshPerformanceHintStatistics(globalState, "initial_dataset");
        }
    }

    @Override
    public SQLConnection createDatabase(OceanBaseGlobalState globalState) throws Exception, SQLException {
        String username = globalState.getOptions().getUserName();
        String password = globalState.getOptions().getPassword();
        String host = globalState.getOptions().getHost();
        int port = globalState.getOptions().getPort();
        if (host == null) {
            host = OceanBaseOptions.DEFAULT_HOST;
        }
        if (port == MainOptions.NO_SET_PORT) {
            port = OceanBaseOptions.DEFAULT_PORT;
        }
        if (username.endsWith("sys") || username.equals("root")) {
            throw new OceanBaseUserCheckException(
                    "please don't use sys tenant to test! Firstly create tenant then test");
        }
        String databaseName = globalState.getDatabaseName();
        globalState.getState().logStatement("DROP DATABASE IF EXISTS " + databaseName);
        globalState.getState().logStatement("CREATE DATABASE " + databaseName);
        globalState.getState().logStatement("USE " + databaseName);
        String url = String.format("jdbc:mysql://%s:%d?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                host, port);
        Connection con = DriverManager.getConnection(url, username, password);

        try (Statement s = con.createStatement()) {
            s.execute("set ob_query_timeout=" + globalState.getDbmsSpecificOptions().queryTimeout);
        }
        try (Statement s = con.createStatement()) {
            s.execute("set ob_trx_timeout=" + globalState.getDbmsSpecificOptions().trxTimeout);
        }
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
        return "oceanbase";
    }

    @Override
    public String getQueryPlan(String selectStr, OceanBaseGlobalState globalState) throws Exception {
        if (globalState.getOptions().logEachSelect()) {
            globalState.getLogger().writeCurrent(selectStr);
            globalState.getLogger().getCurrentFileWriter().flush();
        }
        try (SQLancerResultSet rs = new SQLQueryAdapter("EXPLAIN FORMAT=JSON " + selectStr)
                .executeAndGet(globalState)) {
            if (rs == null) {
                return "";
            }
            StringBuilder explainJson = new StringBuilder();
            while (rs.next()) {
                if (explainJson.length() > 0) {
                    explainJson.append(System.lineSeparator());
                }
                explainJson.append(rs.getString(1));
            }
            return QPGPlanCanonicalizer.normalizeOceanBaseJsonPlan(explainJson.toString());
        } catch (Throwable ignored) {
            return "";
        }
    }

    @Override
    protected double[] initializeWeightedAverageReward() {
        return new double[QPG_MUTATION_ACTIONS.length];
    }

    @Override
    protected void executeMutator(int index, OceanBaseGlobalState globalState) throws Exception {
        if (globalState.executeStatement(QPG_MUTATION_ACTIONS[index].getQuery(globalState))
                && usesOracle(globalState, OceanBaseOracleFactory.PERFORMANCE_HINT)) {
            refreshPerformanceHintStatistics(globalState, "qpg_mutation");
        }
    }

    @Override
    protected String getQPGMutatorName(int index) {
        return QPG_MUTATION_ACTIONS[index].name();
    }

    @Override
    public boolean addRowsToAllTables(OceanBaseGlobalState globalState) throws Exception {
        List<OceanBaseSchema.OceanBaseTable> emptyTables = globalState.getSchema().getDatabaseTables().stream()
                .filter(table -> table.getNrRows(globalState) == 0).collect(Collectors.toList());
        boolean rowsAdded = false;
        for (OceanBaseSchema.OceanBaseTable table : emptyTables) {
            rowsAdded |= globalState.executeStatement(OceanBaseInsertGenerator.insertRow(globalState, table));
        }
        if (rowsAdded && usesOracle(globalState, OceanBaseOracleFactory.PERFORMANCE_HINT)) {
            refreshPerformanceHintStatistics(globalState, "row_recovery");
        }
        return true;
    }

    private static boolean usesOracle(OceanBaseGlobalState globalState, OceanBaseOracleFactory oracle) {
        return globalState.getDbmsSpecificOptions().getTestOracleFactory().stream()
                .anyMatch(candidate -> candidate == oracle);
    }

    private static void refreshPerformanceHintStatistics(OceanBaseGlobalState globalState, String reason)
            throws Exception {
        PerformanceHintStatisticsRefresh.run(OceanBasePerformanceHintTelemetry.getInstance(globalState), reason,
                () -> OceanBasePerformanceHintSupport.refreshStatistics(globalState,
                        globalState.getSchema().getDatabaseTables()));
    }

}
