package sqlancer.mysql.oracle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestMySQLPerformanceHintTelemetry {

    @TempDir
    Path tempDir;

    @Test
    public void testSnapshotsContainPerSecondDeltaAndCumulativeFunnelCounters() throws Exception {
        MySQLPerformanceHintTelemetry telemetry = MySQLPerformanceHintTelemetry.forStateDirectory(tempDir);
        telemetry.count("base_queries.generated");
        telemetry.count("pairs.checked");
        telemetry.count("pairs.checked");
        telemetry.reject("plan cost gate", "not taxonomy cost candidate");
        telemetry.flushNow();

        telemetry.count("pairs.checked");
        telemetry.count("pairs.oracle_qualified");
        telemetry.recordMaximum("reports.queue_max_depth", 7);
        telemetry.flushNow();

        List<String> snapshots = Files.readAllLines(telemetry.getEventFile());
        assertTrue(snapshots.size() >= 2);
        String first = snapshots.get(snapshots.size() - 2);
        String second = snapshots.get(snapshots.size() - 1);
        assertTrue(first.contains("\"intervalMillis\""));
        assertTrue(first.contains("\"pairs.checked\":2"));
        assertTrue(first.contains("\"rejected.plan_cost_gate\":1"));
        assertTrue(first.contains("\"rejected.plan_cost_gate.not_taxonomy_cost_candidate\":1"));
        assertTrue(second.contains("\"delta\""));
        assertTrue(second.contains("\"pairs.checked\":1"));
        assertTrue(second.contains("\"pairs.oracle_qualified\":1"));
        assertTrue(second.contains("\"reports.queue_max_depth\":7"));
    }
}
