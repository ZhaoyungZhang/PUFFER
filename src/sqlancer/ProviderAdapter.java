package sqlancer;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sqlancer.StateToReproduce.OracleRunReproductionState;
import sqlancer.common.DBMSCommon;
import sqlancer.common.oracle.CompositeTestOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.schema.AbstractSchema;

public abstract class ProviderAdapter<G extends GlobalState<O, ? extends AbstractSchema<G, ?>, C>, O extends DBMSSpecificOptions<? extends OracleFactory<G>>, C extends SQLancerDBConnection>
        implements DatabaseProvider<G, O, C> {

    private final Class<G> globalClass;
    private final Class<O> optionClass;

    // Variables for QPG
    Map<String, QPGPlanEntry> queryPlanPool = new LinkedHashMap<>();
    double[] weightedAverageReward;
    int currentSelectRewards;
    int currentSelectCounts;
    int currentMutationOperator = -1;
    long qpgMutationSequence;
    Deque<QPGPlanObservation> recentPlanObservations = new ArrayDeque<>();
    int recentWindowNewPlans;
    Deque<Double> recentMutationRewards = new ArrayDeque<>();

    private static final class QPGPlanEntry {
        private final String selectSql;
        private final long acceptedAtQueryIndex;

        private QPGPlanEntry(String selectSql, long acceptedAtQueryIndex) {
            this.selectSql = selectSql;
            this.acceptedAtQueryIndex = acceptedAtQueryIndex;
        }
    }

    private static final class QPGPlanObservation {
        private final boolean newPlan;
        private final int poolSize;

        private QPGPlanObservation(boolean newPlan, int poolSize) {
            this.newPlan = newPlan;
            this.poolSize = poolSize;
        }
    }

    private static final class QPGStaleDecision {
        private static final int NOT_APPLICABLE = -1;
        private final boolean shouldMutate;
        private final String trigger;
        private final int existingPlanStreak;
        private final int windowSize;
        private final int windowNewPlans;
        private final int windowPoolGrowth;
        private final int recentRewardWindowSize;
        private final double recentRewardMax;

        private QPGStaleDecision(boolean shouldMutate, String trigger, int existingPlanStreak, int windowSize,
                int windowNewPlans, int windowPoolGrowth, int recentRewardWindowSize, double recentRewardMax) {
            this.shouldMutate = shouldMutate;
            this.trigger = trigger;
            this.existingPlanStreak = existingPlanStreak;
            this.windowSize = windowSize;
            this.windowNewPlans = windowNewPlans;
            this.windowPoolGrowth = windowPoolGrowth;
            this.recentRewardWindowSize = recentRewardWindowSize;
            this.recentRewardMax = recentRewardMax;
        }

        private static QPGStaleDecision none(int existingPlanStreak) {
            return new QPGStaleDecision(false, "none", existingPlanStreak, 0, NOT_APPLICABLE, NOT_APPLICABLE,
                    NOT_APPLICABLE, -1.0);
        }
    }

    protected ProviderAdapter(Class<G> globalClass, Class<O> optionClass) {
        this.globalClass = globalClass;
        this.optionClass = optionClass;
    }

    @Override
    public StateToReproduce getStateToReproduce(String databaseName) {
        return new StateToReproduce(databaseName, this);
    }

    @Override
    public Class<G> getGlobalStateClass() {
        return globalClass;
    }

    @Override
    public Class<O> getOptionClass() {
        return optionClass;
    }

    @Override
    public Reproducer<G> generateAndTestDatabase(G globalState) throws Exception {
        try {
            generateDatabase(globalState);
            checkViewsAreValid(globalState);
            globalState.getManager().incrementCreateDatabase();

            TestOracle<G> oracle = getTestOracle(globalState);
            for (int i = 0; i < globalState.getOptions().getNrQueries(); i++) {
                try (OracleRunReproductionState localState = globalState.getState().createLocalState()) {
                    assert localState != null;
                    try {
                        oracle.check();
                        globalState.getManager().incrementSelectQueryCount();
                    } catch (IgnoreMeException ignored) {
                    } catch (AssertionError e) {
                        Reproducer<G> reproducer = oracle.getLastReproducer();
                        if (reproducer != null) {
                            return reproducer;
                        }
                        throw e;
                    }
                    localState.executedWithoutError();
                }
            }
        } finally {
            globalState.getConnection().close();
        }
        return null;
    }

    protected abstract void checkViewsAreValid(G globalState) throws SQLException;

    protected TestOracle<G> getTestOracle(G globalState) throws Exception {
        List<? extends OracleFactory<G>> testOracleFactory = globalState.getDbmsSpecificOptions()
                .getTestOracleFactory();
        boolean testOracleRequiresMoreThanZeroRows = testOracleFactory.stream()
                .anyMatch(OracleFactory::requiresAllTablesToContainRows);
        boolean userRequiresMoreThanZeroRows = globalState.getOptions().testOnlyWithMoreThanZeroRows();
        boolean checkZeroRows = testOracleRequiresMoreThanZeroRows || userRequiresMoreThanZeroRows;
        if (checkZeroRows && globalState.getSchema().containsTableWithZeroRows(globalState)) {
            if (globalState.getOptions().enableQPG()) {
                addRowsToAllTables(globalState);
            } else {
                throw new IgnoreMeException();
            }
        }
        if (testOracleFactory.size() == 1) {
            return testOracleFactory.get(0).create(globalState);
        } else {
            return new CompositeTestOracle<>(testOracleFactory.stream().map(o -> {
                try {
                    return o.create(globalState);
                } catch (Exception e1) {
                    throw new AssertionError(e1);
                }
            }).collect(Collectors.toList()), globalState);
        }
    }

    public abstract void generateDatabase(G globalState) throws Exception;

    // QPG: entry function
    @Override
    public void generateAndTestDatabaseWithQueryPlanGuidance(G globalState) throws Exception {
        if (weightedAverageReward == null) {
            weightedAverageReward = initializeWeightedAverageReward(); // Same length as the list of mutators
        }
        queryPlanPool = new LinkedHashMap<>();
        qpgMutationSequence = 0;
        currentSelectRewards = 0;
        currentSelectCounts = 0;
        currentMutationOperator = -1;
        recentPlanObservations.clear();
        recentWindowNewPlans = 0;
        recentMutationRewards.clear();
        try {
            generateDatabase(globalState);
            checkViewsAreValid(globalState);
            globalState.getManager().incrementCreateDatabase();

            Long executedQueryCount = 0L;
            while (executedQueryCount < globalState.getOptions().getNrQueries()) {
                int existingPlanStreak = 0;
                TestOracle<G> oracle = getTestOracle(globalState);
                while (executedQueryCount < globalState.getOptions().getNrQueries()) {
                    try (OracleRunReproductionState localState = globalState.getState().createLocalState()) {
                        assert localState != null;
                        try {
                            oracle.check();
                            String query = oracle.getLastQueryString();
                            executedQueryCount += 1;
                            if (addQueryPlan(query, executedQueryCount, globalState)) {
                                existingPlanStreak = 0;
                            } else {
                                existingPlanStreak++;
                            }
                            globalState.getManager().incrementSelectQueryCount();
                        } catch (IgnoreMeException e) {

                        }
                        localState.executedWithoutError();
                    }
                    QPGStaleDecision staleDecision = evaluateStaleness(existingPlanStreak, globalState);
                    if (staleDecision.shouldMutate) {
                        mutateTables(globalState, staleDecision);
                        break;
                    }
                }
            }
        } finally {
            globalState.getConnection().close();
        }
    }

    // QPG: mutate tables for a new database state
    private synchronized boolean mutateTables(G globalState, QPGStaleDecision staleDecision) throws Exception {
        // Update rewards based on a set of newly generated queries in last iteration
        if (currentMutationOperator != -1) {
            double latestReward = currentSelectCounts == 0 ? 0.0
                    : ((double) currentSelectRewards / (double) currentSelectCounts)
                            * globalState.getOptions().getQPGk();
            weightedAverageReward[currentMutationOperator] += latestReward;
        }
        currentMutationOperator = -1;

        // Choose mutator based on the rewards
        int selectedActionIndex = 0;
        String selectionMode = "exploit";
        if (Randomly.getPercentage() < globalState.getOptions().getQPGProbability()) {
            selectedActionIndex = globalState.getRandomly().getInteger(0, weightedAverageReward.length);
            selectionMode = "explore";
        } else {
            selectedActionIndex = DBMSCommon.getMaxIndexInDoubleArray(weightedAverageReward);
        }
        int reward = 0;
        int poolSizeBefore = queryPlanPool.size();
        double rewardBefore = weightedAverageReward[selectedActionIndex];
        boolean mutationExecuted = false;
        qpgMutationSequence++;
        globalState.getLogger().appendQPGEvent(buildMutationSelectedEvent(globalState, selectedActionIndex,
                selectionMode, poolSizeBefore, rewardBefore, staleDecision));

        try {
            executeMutator(selectedActionIndex, globalState);
            mutationExecuted = true;
            checkViewsAreValid(globalState); // Remove the invalid views
            reward = checkQueryPlan(globalState);
        } catch (IgnoreMeException | AssertionError e) {
        } finally {
            // Update rewards based on existing queries associated with the query plan pool
            double normalizedReward = queryPlanPool.isEmpty() ? 0.0 : (double) reward / (double) queryPlanPool.size();
            updateReward(selectedActionIndex, normalizedReward, globalState);
            recentMutationRewards.addLast(normalizedReward);
            trimRecentMutationRewards(globalState);
            globalState.getLogger()
                    .appendQPGEvent(buildMutationRewardEvent(globalState, selectedActionIndex, mutationExecuted, reward,
                            normalizedReward, poolSizeBefore, queryPlanPool.size(), rewardBefore,
                            weightedAverageReward[selectedActionIndex]));
            globalState.getLogger().writeQPGPoolSnapshot(buildQPGPoolSnapshot());
            currentMutationOperator = selectedActionIndex;
        }

        // Clear the variables for storing the rewards of the action on a set of newly generated queries
        currentSelectRewards = 0;
        currentSelectCounts = 0;
        return true;
    }

    // QPG: add a query plan to the query plan pool and return true if the query plan is new
    private boolean addQueryPlan(String selectStr, long queryIndex, G globalState) throws Exception {
        String queryPlan = getQueryPlan(selectStr, globalState);

        if (globalState.getOptions().logQueryPlan()) {
            globalState.getLogger().writeQueryPlan(queryPlan);
        }

        currentSelectCounts += 1;
        if (queryPlanPool.containsKey(queryPlan)) {
            recordPlanObservation(false, queryPlanPool.size(), globalState);
            globalState.getLogger().appendQPGEvent(
                    buildPlanEvent("existing_plan_seen", queryIndex, selectStr, queryPlan, queryPlanPool.size()));
            return false;
        } else {
            queryPlanPool.put(queryPlan, new QPGPlanEntry(selectStr, queryIndex));
            currentSelectRewards += 1;
            recordPlanObservation(true, queryPlanPool.size(), globalState);
            globalState.getLogger().appendQPGEvent(
                    buildPlanEvent("new_plan_accepted", queryIndex, selectStr, queryPlan, queryPlanPool.size()));
            globalState.getLogger().writeQPGPoolSnapshot(buildQPGPoolSnapshot());
            return true;
        }
    }

    // Obtain the reward of the current action based on the queries associated with the query plan pool
    private int checkQueryPlan(G globalState) throws Exception {
        int newQueryPlanFound = 0;
        HashMap<String, QPGPlanEntry> modifiedQueryPlan = new HashMap<>();
        for (Iterator<Map.Entry<String, QPGPlanEntry>> it = queryPlanPool.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, QPGPlanEntry> item = it.next();
            String queryPlan = item.getKey();
            QPGPlanEntry entry = item.getValue();
            String selectStr = entry.selectSql;
            String newQueryPlan = getQueryPlan(selectStr, globalState);
            if (newQueryPlan.isEmpty()) { // Invalid query
                it.remove();
            } else if (!queryPlan.equals(newQueryPlan)) { // A query plan has been changed
                it.remove();
                modifiedQueryPlan.put(newQueryPlan, new QPGPlanEntry(selectStr, entry.acceptedAtQueryIndex));
                if (!queryPlanPool.containsKey(newQueryPlan)) { // A new query plan is found
                    newQueryPlanFound++;
                }
            }
        }
        queryPlanPool.putAll(modifiedQueryPlan);
        return newQueryPlanFound;
    }

    // QPG: update the reward of current action
    private void updateReward(int actionIndex, double reward, G globalState) {
        weightedAverageReward[actionIndex] += (reward - weightedAverageReward[actionIndex])
                * globalState.getOptions().getQPGk();
    }

    private void recordPlanObservation(boolean newPlan, int poolSize, G globalState) {
        int windowSize = globalState.getOptions().getQPGStaleWindowSize();
        if (windowSize <= 0) {
            return;
        }
        recentPlanObservations.addLast(new QPGPlanObservation(newPlan, poolSize));
        if (newPlan) {
            recentWindowNewPlans++;
        }
        while (recentPlanObservations.size() > windowSize) {
            QPGPlanObservation removed = recentPlanObservations.removeFirst();
            if (removed.newPlan) {
                recentWindowNewPlans--;
            }
        }
    }

    private void trimRecentMutationRewards(G globalState) {
        int rewardLookback = globalState.getOptions().getQPGStaleRewardLookback();
        if (rewardLookback <= 0) {
            recentMutationRewards.clear();
            return;
        }
        while (recentMutationRewards.size() > rewardLookback) {
            recentMutationRewards.removeFirst();
        }
    }

    private QPGStaleDecision evaluateStaleness(int existingPlanStreak, G globalState) {
        QPGStaleDecision decision = evaluateWindowStaleness(existingPlanStreak, globalState);
        if (decision.shouldMutate) {
            return decision;
        }
        decision = evaluateRewardStaleness(existingPlanStreak, globalState);
        if (decision.shouldMutate) {
            return decision;
        }
        int legacyStreakThreshold = globalState.getOptions().getQPGMaxMutationInterval();
        if (legacyStreakThreshold > 0 && existingPlanStreak >= legacyStreakThreshold) {
            return new QPGStaleDecision(true, "legacy_streak", existingPlanStreak, recentPlanObservations.size(),
                    recentPlanObservations.isEmpty() ? QPGStaleDecision.NOT_APPLICABLE : recentWindowNewPlans,
                    getCurrentWindowPoolGrowth(), recentMutationRewards.size(), getMaxRecentMutationReward());
        }
        return QPGStaleDecision.none(existingPlanStreak);
    }

    private QPGStaleDecision evaluateWindowStaleness(int existingPlanStreak, G globalState) {
        int windowSize = globalState.getOptions().getQPGStaleWindowSize();
        int maxNewPlans = globalState.getOptions().getQPGStaleWindowMaxNewPlans();
        int maxPoolGrowth = globalState.getOptions().getQPGStaleWindowMaxPoolGrowth();
        if (windowSize <= 0 || maxNewPlans < 0 || maxPoolGrowth < 0 || recentPlanObservations.size() < windowSize) {
            return QPGStaleDecision.none(existingPlanStreak);
        }
        int poolGrowth = getCurrentWindowPoolGrowth();
        if (recentWindowNewPlans <= maxNewPlans && poolGrowth <= maxPoolGrowth) {
            return new QPGStaleDecision(true, "window", existingPlanStreak, windowSize, recentWindowNewPlans,
                    poolGrowth, recentMutationRewards.size(), getMaxRecentMutationReward());
        }
        return QPGStaleDecision.none(existingPlanStreak);
    }

    private QPGStaleDecision evaluateRewardStaleness(int existingPlanStreak, G globalState) {
        int rewardLookback = globalState.getOptions().getQPGStaleRewardLookback();
        double maxRecentRewardThreshold = globalState.getOptions().getQPGStaleMaxRecentReward();
        if (rewardLookback <= 0 || maxRecentRewardThreshold < 0 || recentMutationRewards.size() < rewardLookback) {
            return QPGStaleDecision.none(existingPlanStreak);
        }
        double maxRecentReward = getMaxRecentMutationReward();
        if (maxRecentReward <= maxRecentRewardThreshold) {
            return new QPGStaleDecision(true, "reward_window", existingPlanStreak, recentPlanObservations.size(),
                    recentPlanObservations.isEmpty() ? QPGStaleDecision.NOT_APPLICABLE : recentWindowNewPlans,
                    getCurrentWindowPoolGrowth(), recentMutationRewards.size(), maxRecentReward);
        }
        return QPGStaleDecision.none(existingPlanStreak);
    }

    private int getCurrentWindowPoolGrowth() {
        if (recentPlanObservations.isEmpty()) {
            return QPGStaleDecision.NOT_APPLICABLE;
        }
        return recentPlanObservations.peekLast().poolSize - recentPlanObservations.peekFirst().poolSize;
    }

    private double getMaxRecentMutationReward() {
        if (recentMutationRewards.isEmpty()) {
            return -1.0;
        }
        double maxRecentReward = Double.NEGATIVE_INFINITY;
        for (double reward : recentMutationRewards) {
            maxRecentReward = Math.max(maxRecentReward, reward);
        }
        return maxRecentReward;
    }

    // QPG: initialize the weighted average reward of all mutation operators (required implementation in specific DBMS)
    protected double[] initializeWeightedAverageReward() {
        throw new UnsupportedOperationException();
    }

    // QPG: obtain the query plan of a query (required implementation in specific DBMS)
    protected String getQueryPlan(String selectStr, G globalState) throws Exception {
        throw new UnsupportedOperationException();
    }

    // QPG: execute a mutation operator (required implementation in specific DBMS)
    protected void executeMutator(int index, G globalState) throws Exception {
        throw new UnsupportedOperationException();
    }

    protected String getQPGMutatorName(int index) {
        return Integer.toString(index);
    }

    // QPG: add rows to all tables (required implementation in specific DBMS when enabling PQS oracle for QPG)
    protected boolean addRowsToAllTables(G globalState) throws Exception {
        throw new UnsupportedOperationException();
    }

    private String buildPlanEvent(String type, long queryIndex, String selectStr, String queryPlan, int poolSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendJsonField(sb, "type", type);
        sb.append(",");
        appendJsonField(sb, "queryIndex", Long.toString(queryIndex), false);
        sb.append(",");
        appendJsonField(sb, "planHash", Integer.toUnsignedString(queryPlan.hashCode()));
        sb.append(",");
        appendJsonField(sb, "poolSize", Integer.toString(poolSize), false);
        sb.append(",");
        appendJsonField(sb, "sql", selectStr);
        sb.append(",");
        appendJsonField(sb, "plan", queryPlan);
        sb.append("}");
        return sb.toString();
    }

    private String buildMutationSelectedEvent(G globalState, int actionIndex, String selectionMode, int poolSizeBefore,
            double rewardBefore, QPGStaleDecision staleDecision) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendJsonField(sb, "type", "mutation_selected");
        sb.append(",");
        appendJsonField(sb, "mutationSequence", Long.toString(qpgMutationSequence), false);
        sb.append(",");
        appendJsonField(sb, "actionIndex", Integer.toString(actionIndex), false);
        sb.append(",");
        appendJsonField(sb, "actionName", getQPGMutatorName(actionIndex));
        sb.append(",");
        appendJsonField(sb, "selectionMode", selectionMode);
        sb.append(",");
        appendJsonField(sb, "poolSizeBefore", Integer.toString(poolSizeBefore), false);
        sb.append(",");
        appendJsonField(sb, "rewardBefore", Double.toString(rewardBefore), false);
        sb.append(",");
        appendJsonField(sb, "staleTrigger", staleDecision.trigger);
        sb.append(",");
        appendJsonField(sb, "existingPlanStreak", Integer.toString(staleDecision.existingPlanStreak), false);
        sb.append(",");
        appendJsonField(sb, "windowSize", Integer.toString(staleDecision.windowSize), false);
        sb.append(",");
        appendJsonField(sb, "windowNewPlans", Integer.toString(staleDecision.windowNewPlans), false);
        sb.append(",");
        appendJsonField(sb, "windowPoolGrowth", Integer.toString(staleDecision.windowPoolGrowth), false);
        sb.append(",");
        appendJsonField(sb, "recentRewardWindowSize", Integer.toString(staleDecision.recentRewardWindowSize), false);
        sb.append(",");
        appendJsonField(sb, "recentRewardMax", Double.toString(staleDecision.recentRewardMax), false);
        sb.append(",");
        appendJsonField(sb, "database", globalState.getDatabaseName());
        sb.append("}");
        return sb.toString();
    }

    private String buildMutationRewardEvent(G globalState, int actionIndex, boolean mutationExecuted, int reward,
            double normalizedReward, int poolSizeBefore, int poolSizeAfter, double rewardBefore, double rewardAfter) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendJsonField(sb, "type", "mutation_reward_updated");
        sb.append(",");
        appendJsonField(sb, "mutationSequence", Long.toString(qpgMutationSequence), false);
        sb.append(",");
        appendJsonField(sb, "actionIndex", Integer.toString(actionIndex), false);
        sb.append(",");
        appendJsonField(sb, "actionName", getQPGMutatorName(actionIndex));
        sb.append(",");
        appendJsonField(sb, "mutationExecuted", Boolean.toString(mutationExecuted), false);
        sb.append(",");
        appendJsonField(sb, "rewardDeltaPlans", Integer.toString(reward), false);
        sb.append(",");
        appendJsonField(sb, "normalizedReward", Double.toString(normalizedReward), false);
        sb.append(",");
        appendJsonField(sb, "rewardBefore", Double.toString(rewardBefore), false);
        sb.append(",");
        appendJsonField(sb, "rewardAfter", Double.toString(rewardAfter), false);
        sb.append(",");
        appendJsonField(sb, "poolSizeBefore", Integer.toString(poolSizeBefore), false);
        sb.append(",");
        appendJsonField(sb, "poolSizeAfter", Integer.toString(poolSizeAfter), false);
        sb.append(",");
        appendJsonField(sb, "database", globalState.getDatabaseName());
        sb.append("}");
        return sb.toString();
    }

    private String buildQPGPoolSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"mutationSequence\": ").append(qpgMutationSequence).append(",\n");
        sb.append("  \"poolSize\": ").append(queryPlanPool.size()).append(",\n");
        sb.append("  \"entries\": [\n");
        boolean first = true;
        for (Map.Entry<String, QPGPlanEntry> entry : queryPlanPool.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("    {");
            appendJsonField(sb, "planHash", Integer.toUnsignedString(entry.getKey().hashCode()));
            sb.append(",");
            appendJsonField(sb, "acceptedAtQueryIndex", Long.toString(entry.getValue().acceptedAtQueryIndex), false);
            sb.append(",");
            appendJsonField(sb, "sql", entry.getValue().selectSql);
            sb.append(",");
            appendJsonField(sb, "plan", entry.getKey());
            sb.append("}");
        }
        sb.append("\n  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendJsonField(StringBuilder sb, String key, String value) {
        appendJsonField(sb, key, value, true);
    }

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean quoted) {
        sb.append("\"").append(escapeJson(key)).append("\": ");
        if (quoted) {
            sb.append("\"").append(escapeJson(value)).append("\"");
        } else {
            sb.append(value);
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

}
