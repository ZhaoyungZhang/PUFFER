package sqlancer.mariadb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.google.auto.service.AutoService;

import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.SQLGlobalState;
import sqlancer.SQLProviderAdapter;
import sqlancer.common.DBMSCommon;
import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;
import sqlancer.common.perfhint.PerformanceHintDataGenerationConfig;
import sqlancer.common.perfhint.PerformanceHintStatisticsRefresh;
import sqlancer.common.perfhint.pdg.PdgIrContext;
import sqlancer.common.qpg.QPGPlanCanonicalizer;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.mariadb.MariaDBOptions.MariaDBOracleFactory;
import sqlancer.mariadb.MariaDBProvider.MariaDBGlobalState;
import sqlancer.mariadb.ast.MariaDBVisitor;
import sqlancer.mariadb.gen.MariaDBExpressionGenerator;
import sqlancer.mariadb.gen.MariaDBIndexGenerator;
import sqlancer.mariadb.gen.MariaDBInsertGenerator;
import sqlancer.mariadb.gen.MariaDBSetGenerator;
import sqlancer.mariadb.gen.MariaDBTableAdminCommandGenerator;
import sqlancer.mariadb.gen.MariaDBTableGenerator;
import sqlancer.mariadb.gen.MariaDBTruncateGenerator;
import sqlancer.mariadb.gen.MariaDBUpdateGenerator;
import sqlancer.mariadb.gen.datagen.MariaDBPerformanceDataGenerator;
import sqlancer.mariadb.oracle.MariaDBPerformanceHintTelemetry;

@AutoService(DatabaseProvider.class)
public class MariaDBProvider extends SQLProviderAdapter<MariaDBGlobalState, MariaDBOptions> {

    private static final Action[] QPG_MUTATION_ACTIONS = { Action.INSERT, Action.UPDATE, Action.CREATE_INDEX };
    public static final int MAX_EXPRESSION_DEPTH = 3;

    public MariaDBProvider() {
        super(MariaDBGlobalState.class, MariaDBOptions.class);
    }

    enum Action {
        ANALYZE_TABLE, //
        CHECKSUM, //
        CHECK_TABLE, //
        CREATE_INDEX, //
        INSERT, //
        OPTIMIZE, //
        REPAIR_TABLE, //
        SET, //
        TRUNCATE, //
        UPDATE, //
    }

    @Override
    public void generateDatabase(MariaDBGlobalState globalState) throws Exception {
        MainOptions options = globalState.getOptions();
        boolean performanceHint = usesOracle(globalState, MariaDBOracleFactory.PERFORMANCE_HINT);
        MariaDBOptions dbmsOptions = globalState.getDbmsSpecificOptions();
        if (dbmsOptions.performanceDataAblationProfile != PerformanceHintDataAblationProfile.NONE && (!performanceHint
                || !PerformanceHintDataGenerationConfig.isModelV1(dbmsOptions.performanceDataGeneratorMode))) {
            throw new IllegalArgumentException(
                    "--perf-data-ablation-profile requires mariadb --oracle PERFORMANCE_HINT "
                            + "--perf-data-generator-mode model-v1");
        }
        if (performanceHint && PerformanceHintDataGenerationConfig
                .isModelV1(globalState.getDbmsSpecificOptions().performanceDataGeneratorMode)) {
            MariaDBPerformanceDataGenerator.generate(globalState);
            long refreshStartedAt = System.nanoTime();
            try {
                refreshPerformanceHintStatistics(globalState, "initial_dataset");
            } finally {
                MariaDBPerformanceHintTelemetry.getInstance(globalState).add("data_generation.analyze_nanos",
                        System.nanoTime() - refreshStartedAt);
            }
            return;
        }

        int targetTableCount = Math.max(performanceHint ? 2 : 1, Randomly.smallNumber() + 1);
        while (globalState.getSchema().getDatabaseTables().size() < targetTableCount) {
            String tableName = DBMSCommon.createTableName(globalState.getSchema().getDatabaseTables().size());
            SQLQueryAdapter createTable = MariaDBTableGenerator.generate(tableName, globalState.getRandomly(),
                    globalState.getSchema());
            globalState.executeStatement(createTable);
        }

        int[] nrRemaining = new int[Action.values().length];
        List<Action> actions = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < Action.values().length; i++) {
            Action action = Action.values()[i];
            int nrPerformed = 0;
            switch (action) {
            case CHECKSUM:
            case CHECK_TABLE:
            case TRUNCATE:
            case REPAIR_TABLE:
            case OPTIMIZE:
                nrPerformed = performanceHint ? 0 : globalState.getRandomly().getInteger(0, 2);
                break;
            case ANALYZE_TABLE:
                nrPerformed = performanceHint ? 0 : globalState.getRandomly().getInteger(0, 2);
                break;
            case UPDATE:
                nrPerformed = performanceHint ? globalState.getRandomly().getInteger(0, 2)
                        : globalState.getRandomly().getInteger(0, 2);
                break;
            case CREATE_INDEX:
                nrPerformed = performanceHint ? globalState.getRandomly().getInteger(1, 3)
                        : globalState.getRandomly().getInteger(0, 2);
                break;
            case SET:
                nrPerformed = performanceHint ? 0 : 20;
                break;
            case INSERT:
                nrPerformed = performanceHint
                        ? globalState.getRandomly().getInteger(Math.min(8, options.getMaxNumberInserts()),
                                options.getMaxNumberInserts() + 1)
                        : globalState.getRandomly().getInteger(0, options.getMaxNumberInserts());
                break;
            default:
                throw new AssertionError(action);
            }
            if (nrPerformed != 0) {
                actions.add(action);
            }
            nrRemaining[action.ordinal()] = nrPerformed;
            total += nrPerformed;
        }
        while (total != 0) {
            Action nextAction = null;
            int selection = globalState.getRandomly().getInteger(0, total);
            int previousRange = 0;
            for (int i = 0; i < nrRemaining.length; i++) {
                if (previousRange <= selection && selection < previousRange + nrRemaining[i]) {
                    nextAction = Action.values()[i];
                    break;
                } else {
                    previousRange += nrRemaining[i];
                }
            }
            assert nextAction != null;
            assert nrRemaining[nextAction.ordinal()] > 0;
            nrRemaining[nextAction.ordinal()]--;
            SQLQueryAdapter query;
            try {
                switch (nextAction) {
                case CHECKSUM:
                    query = MariaDBTableAdminCommandGenerator.checksumTable(globalState.getSchema());
                    break;
                case CHECK_TABLE:
                    query = MariaDBTableAdminCommandGenerator.checkTable(globalState.getSchema());
                    break;
                case TRUNCATE:
                    query = MariaDBTruncateGenerator.truncate(globalState.getSchema());
                    break;
                case REPAIR_TABLE:
                    query = MariaDBTableAdminCommandGenerator.repairTable(globalState.getSchema());
                    break;
                case INSERT:
                    query = MariaDBInsertGenerator.insert(globalState.getSchema(), globalState.getRandomly());
                    break;
                case OPTIMIZE:
                    query = MariaDBTableAdminCommandGenerator.optimizeTable(globalState.getSchema());
                    break;
                case ANALYZE_TABLE:
                    query = MariaDBTableAdminCommandGenerator.analyzeTable(globalState.getSchema());
                    break;
                case UPDATE:
                    query = MariaDBUpdateGenerator.update(globalState.getSchema(), globalState.getRandomly());
                    break;
                case CREATE_INDEX:
                    query = MariaDBIndexGenerator.generate(globalState.getSchema());
                    break;
                case SET:
                    query = MariaDBSetGenerator.set(globalState.getRandomly(), options);
                    break;
                default:
                    throw new AssertionError(nextAction);
                }
            } catch (IgnoreMeException e) {
                total--;
                continue;
            }
            try {
                globalState.executeStatement(query);
            } catch (Throwable t) {
                System.err.println(query.getQueryString());
                throw t;
            }
            total--;
        }
        if (performanceHint) {
            refreshPerformanceHintStatistics(globalState, "initial_dataset");
        }
    }

    public static class MariaDBGlobalState extends SQLGlobalState<MariaDBOptions, MariaDBSchema> {

        private String performanceDataModelDescription;
        private PdgIrContext pdgIrContext;

        @Override
        protected MariaDBSchema readSchema() throws SQLException {
            return MariaDBSchema.fromConnection(getConnection(), getDatabaseName());
        }

        public String getPerformanceDataModelDescription() {
            return performanceDataModelDescription;
        }

        public void setPerformanceDataModelDescription(String performanceDataModelDescription) {
            this.performanceDataModelDescription = performanceDataModelDescription;
        }

        public PdgIrContext getPdgIrContext() {
            return pdgIrContext;
        }

        public void setPdgIrContext(PdgIrContext pdgIrContext) {
            this.pdgIrContext = pdgIrContext;
        }

    }

    @Override
    public SQLConnection createDatabase(MariaDBGlobalState globalState) throws SQLException {
        globalState.getState().logStatement("DROP DATABASE IF EXISTS " + globalState.getDatabaseName());
        globalState.getState().logStatement("CREATE DATABASE " + globalState.getDatabaseName());
        globalState.getState().logStatement("USE " + globalState.getDatabaseName());
        String username = globalState.getOptions().getUserName();
        String password = globalState.getOptions().getPassword();
        String host = globalState.getOptions().getHost();
        int port = globalState.getOptions().getPort();
        if (host == null) {
            host = MariaDBOptions.DEFAULT_HOST;
        }
        if (port == MainOptions.NO_SET_PORT) {
            port = MariaDBOptions.DEFAULT_PORT;
        }
        String url = String.format("jdbc:mariadb://%s:%d", host, port);
        Connection con = DriverManager.getConnection(url, username, password);
        try (Statement s = con.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + globalState.getDatabaseName());
        }
        try (Statement s = con.createStatement()) {
            s.execute("CREATE DATABASE " + globalState.getDatabaseName());
        }
        try (Statement s = con.createStatement()) {
            s.execute("USE " + globalState.getDatabaseName());
        }
        return new SQLConnection(con);
    }

    @Override
    public String getDBMSName() {
        return "mariadb";
    }

    @Override
    public String getQueryPlan(String selectStr, MariaDBGlobalState globalState) throws Exception {
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
    protected void executeMutator(int index, MariaDBGlobalState globalState) throws Exception {
        SQLQueryAdapter queryMutateTable = getQPGMutationQuery(QPG_MUTATION_ACTIONS[index], globalState);
        if (globalState.executeStatement(queryMutateTable)) {
            if (usesOracle(globalState, MariaDBOracleFactory.PERFORMANCE_HINT)) {
                refreshPerformanceHintStatistics(globalState, "qpg_mutation");
            } else {
                MariaDBPerformanceHintSupport.refreshStatistics(globalState,
                        globalState.getSchema().getDatabaseTables());
            }
        }
    }

    @Override
    protected String getQPGMutatorName(int index) {
        return QPG_MUTATION_ACTIONS[index].name();
    }

    @Override
    protected boolean addRowsToAllTables(MariaDBGlobalState globalState) throws Exception {
        List<MariaDBSchema.MariaDBTable> tablesNoRow = globalState.getSchema().getDatabaseTables().stream()
                .filter(t -> t.getNrRows(globalState) == 0).collect(java.util.stream.Collectors.toList());
        boolean rowsAdded = false;
        for (MariaDBSchema.MariaDBTable table : tablesNoRow) {
            rowsAdded |= globalState.executeStatement(buildInsertForTable(table, globalState.getRandomly()));
        }
        if (rowsAdded) {
            if (usesOracle(globalState, MariaDBOracleFactory.PERFORMANCE_HINT)) {
                refreshPerformanceHintStatistics(globalState, "row_recovery");
            } else {
                MariaDBPerformanceHintSupport.refreshStatistics(globalState,
                        globalState.getSchema().getDatabaseTables());
            }
        }
        return true;
    }

    private static boolean usesOracle(MariaDBGlobalState globalState, MariaDBOracleFactory oracle) {
        return globalState.getDbmsSpecificOptions().oracles.stream().anyMatch(o -> o == oracle);
    }

    private static SQLQueryAdapter getQPGMutationQuery(Action action, MariaDBGlobalState globalState) {
        switch (action) {
        case INSERT:
            return MariaDBInsertGenerator.insert(globalState.getSchema(), globalState.getRandomly());
        case UPDATE:
            return MariaDBUpdateGenerator.update(globalState.getSchema(), globalState.getRandomly());
        case CREATE_INDEX:
            return MariaDBIndexGenerator.generate(globalState.getSchema());
        case ANALYZE_TABLE:
            return MariaDBTableAdminCommandGenerator.analyzeTable(globalState.getSchema());
        default:
            throw new AssertionError(action);
        }
    }

    private static SQLQueryAdapter buildInsertForTable(MariaDBSchema.MariaDBTable table, Randomly randomly) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ");
        sb.append(table.getName());
        sb.append(" VALUES (");
        for (int i = 0; i < table.getColumns().size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            if (Randomly.getBooleanWithSmallProbability()) {
                sb.append(MariaDBVisitor.asString(MariaDBExpressionGenerator.getRandomConstant(randomly)));
            } else {
                sb.append(MariaDBVisitor.asString(
                        MariaDBExpressionGenerator.getRandomConstant(randomly, table.getColumns().get(i).getType())));
            }
        }
        sb.append(")");
        ExpectedErrors errors = new ExpectedErrors();
        MariaDBErrors.addInsertErrors(errors);
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private static String normalizeQPGPlan(String explainJson) {
        return QPGPlanCanonicalizer.normalizeMySQLLikeJsonPlan(explainJson);
    }

    private static void refreshPerformanceHintStatistics(MariaDBGlobalState globalState, String reason)
            throws Exception {
        PerformanceHintStatisticsRefresh.run(MariaDBPerformanceHintTelemetry.getInstance(globalState), reason,
                () -> MariaDBPerformanceHintSupport.refreshStatistics(globalState,
                        globalState.getSchema().getDatabaseTables()));
    }

}
