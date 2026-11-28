package sqlancer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sqlancer.common.log.LoggableFactory;
import sqlancer.common.log.SQLLoggableFactory;
import sqlancer.common.perfhint.PerformanceHintDataModel.ColumnDistributionType;
import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;
import sqlancer.common.perfhint.PerformanceHintDataPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.ColumnPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.TablePlan;
import sqlancer.common.perfhint.pdg.PdgIrPlanner;
import sqlancer.common.perfhint.pdg.PdgSpecification;
import sqlancer.common.query.Query;
import sqlancer.mysql.MySQLSchema;

public class TestMainDatabaseSeedRotation {

    private static final String STUB_DBMS_NAME = "seed-rotation-stub";
    private static final List<StateToReproduce> CAPTURED_STATES = new ArrayList<>();

    @AfterEach
    public void cleanUpStubLogs() {
        CAPTURED_STATES.clear();
        deleteRecursively(new File(Main.LOG_DIRECTORY, STUB_DBMS_NAME));
    }

    private static void deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        target.delete();
    }

    @Test
    public void deriveDatabaseSeedProducesDistinctPositiveSeedsPerIteration() {
        long baseSeed = 987654321L;
        assertEquals(baseSeed, Main.deriveDatabaseSeed(baseSeed, 0));

        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 16; i++) {
            long derived = Main.deriveDatabaseSeed(baseSeed, i);
            assertTrue(derived >= 1000L, "Expected positive seed >= 1000 for iteration " + i);
            assertTrue(seen.add(derived), "Duplicate derived seed at iteration " + i + ": " + derived);
        }
        assertEquals(Main.deriveDatabaseSeed(baseSeed, 3), Main.deriveDatabaseSeed(baseSeed, 3));
    }

    @Test
    public void consecutiveDatabasesOnSameThreadHaveDistinctSeedsAndPdgParameters() throws Exception {
        CAPTURED_STATES.clear();
        long threadSeed = 246813579L;
        Randomly sharedRandomly = new Randomly(threadSeed);
        MainOptions options = new MainOptions();
        Main.DBMSExecutorFactory<StubGlobalState, StubOptions, StubConnection> factory = new Main.DBMSExecutorFactory<>(
                new StubProvider(), options);

        long dbSeed0 = Main.deriveDatabaseSeed(threadSeed, 0);
        long dbSeed1 = Main.deriveDatabaseSeed(threadSeed, 1);

        Main.DBMSExecutor<StubGlobalState, StubOptions, StubConnection> exec0 = factory.getDBMSExecutor("rot_db0",
                sharedRandomly, dbSeed0);
        exec0.run();
        Main.DBMSExecutor<StubGlobalState, StubOptions, StubConnection> exec1 = factory.getDBMSExecutor("rot_db0",
                sharedRandomly, dbSeed1);
        exec1.run();

        assertEquals(2, CAPTURED_STATES.size());
        long recorded0 = CAPTURED_STATES.get(0).getSeedValue();
        long recorded1 = CAPTURED_STATES.get(1).getSeedValue();
        assertEquals(threadSeed, recorded0);
        assertNotEquals(recorded0, recorded1);

        PerformanceHintDataPlan plan = new PerformanceHintDataPlan(List
                .of(new TablePlan("t0", 100, List.of(new ColumnPlan("c0", SqlType.BIGINT, ColumnDistributionType.ZIPF),
                        new ColumnPlan("c1", SqlType.BIGINT, ColumnDistributionType.GAUSSIAN)))),
                List.of());

        PdgSpecification spec0 = PdgIrPlanner.resolve("mysql", recorded0, plan);
        PdgSpecification spec1 = PdgIrPlanner.resolve("mysql", recorded1, plan);
        Object valueSeed0 = spec0.getTables().get(0).getColumns().get(0).getProducer().getParameters()
                .get("value_seed");
        Object valueSeed1 = spec1.getTables().get(0).getColumns().get(0).getProducer().getParameters()
                .get("value_seed");
        assertNotEquals(valueSeed0, valueSeed1);
    }

    public static final class StubConnection implements SQLancerDBConnection {
        public StubConnection() {
        }

        @Override
        public String getDatabaseVersion() {
            return "stub-1.0";
        }

        @Override
        public void close() {
        }
    }

    public static final class StubOptions implements DBMSSpecificOptions<OracleFactory<StubGlobalState>> {
        public StubOptions() {
        }

        @Override
        public List<OracleFactory<StubGlobalState>> getTestOracleFactory() {
            return Collections.emptyList();
        }
    }

    public static final class StubGlobalState extends GlobalState<StubOptions, MySQLSchema, StubConnection> {
        public StubGlobalState() {
        }

        @Override
        protected void executeEpilogue(Query<?> q, boolean success, ExecutionTimer timer) {
        }

        @Override
        protected MySQLSchema readSchema() {
            return null;
        }
    }

    public static final class StubProvider implements DatabaseProvider<StubGlobalState, StubOptions, StubConnection> {
        public StubProvider() {
        }

        @Override
        public Class<StubGlobalState> getGlobalStateClass() {
            return StubGlobalState.class;
        }

        @Override
        public Class<StubOptions> getOptionClass() {
            return StubOptions.class;
        }

        @Override
        public Reproducer<StubGlobalState> generateAndTestDatabase(StubGlobalState globalState) {
            CAPTURED_STATES.add(globalState.getState());
            return null;
        }

        @Override
        public void generateAndTestDatabaseWithQueryPlanGuidance(StubGlobalState globalState) {
            CAPTURED_STATES.add(globalState.getState());
        }

        @Override
        public StubConnection createDatabase(StubGlobalState globalState) {
            return new StubConnection();
        }

        @Override
        public String getDBMSName() {
            return STUB_DBMS_NAME;
        }

        @Override
        public LoggableFactory getLoggableFactory() {
            return new SQLLoggableFactory();
        }

        @Override
        public StateToReproduce getStateToReproduce(String databaseName) {
            return new StateToReproduce(databaseName, this);
        }
    }
}
