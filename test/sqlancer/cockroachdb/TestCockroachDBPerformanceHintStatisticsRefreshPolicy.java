package sqlancer.cockroachdb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.cockroachdb.CockroachDBProvider.Action;

class TestCockroachDBPerformanceHintStatisticsRefreshPolicy {

    @Test
    void successfulPerformanceHintDataAndMetadataMutationsRefreshStatistics() {
        for (Action action : new Action[] { Action.CREATE_TABLE, Action.CREATE_INDEX, Action.INSERT, Action.UPDATE,
                Action.DELETE, Action.TRUNCATE, Action.DROP_TABLE }) {
            assertTrue(CockroachDBProvider.shouldRefreshPerformanceHintStatistics(true, true, action), action::name);
        }
    }

    @Test
    void failedOrNonPerformanceHintMutationsDoNotRefreshStatistics() {
        assertFalse(CockroachDBProvider.shouldRefreshPerformanceHintStatistics(true, false, Action.INSERT));
        assertFalse(CockroachDBProvider.shouldRefreshPerformanceHintStatistics(false, true, Action.INSERT));
    }

    @Test
    void inspectionAndTransactionActionsDoNotRefreshStatistics() {
        for (Action action : new Action[] { Action.CREATE_VIEW, Action.DROP_VIEW, Action.SET_SESSION,
                Action.SET_CLUSTER_SETTING, Action.SHOW, Action.EXPLAIN, Action.SCRUB, Action.TRANSACTION,
                Action.COMMENT_ON, Action.SPLIT }) {
            assertFalse(CockroachDBProvider.shouldRefreshPerformanceHintStatistics(true, true, action), action::name);
        }
    }

    @Test
    void explicitStatisticsCollectionDoesNotTriggerAnotherAnalyze() {
        assertFalse(CockroachDBProvider.shouldRefreshPerformanceHintStatistics(true, true, Action.CREATE_STATISTICS));
    }
}
