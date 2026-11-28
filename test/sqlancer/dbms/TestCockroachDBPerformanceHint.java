package sqlancer.dbms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import sqlancer.Main;

public class TestCockroachDBPerformanceHint {

    @Test
    public void testCockroachDBPerformanceHintSmoke() {
        String cockroachDB = System.getenv("COCKROACHDB_AVAILABLE");
        boolean cockroachDBIsAvailable = cockroachDB != null && cockroachDB.equalsIgnoreCase("true");
        assumeTrue(cockroachDBIsAvailable);
        assertEquals(0, Main.executeMain(new String[] { "--random-seed", "0", "--timeout-seconds", TestConfig.SECONDS,
                "--num-threads", "1", "--num-queries", "20", "--username", "root", "--qpg-enable", "true",
                "--qpg-log-query-plan", "true", "cockroachdb", "--oracle", "PERFORMANCE_HINT",
                "--perf-enable-runtime-validation", "--perf-data-generator-mode", "model-v1",
                "--perf-enable-feedback-dedup", "--perf-repeat", "3", "--perf-taxonomy-cost-ratio-lower-bound", "0.9",
                "--perf-taxonomy-cost-ratio-upper-bound", "1.1", "--perf-taxonomy-runtime-ratio-lower-bound", "0.9",
                "--perf-taxonomy-runtime-ratio-upper-bound", "1.3", "--perf-report-harmful-runtime-ratio", "5.0",
                "--perf-report-cost-anomaly-ratio", "10.0" }));
    }
}
