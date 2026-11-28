package sqlancer.common.perfhint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class TestPerformanceHintStatisticsRefresh {

    @TempDir
    Path directory;

    @Test
    void failedAnalyzeIsNotReportedAsFreshAndDoesNotPreventTheNextCheckpoint() throws Exception {
        PerformanceHintTelemetry telemetry = new PerformanceHintTelemetry(directory, "statistics-test");
        IOException failure = new IOException("ANALYZE failed");
        assertSame(failure, assertThrows(IOException.class,
                () -> PerformanceHintStatisticsRefresh.run(telemetry, "initial_dataset", () -> {
                    throw failure;
                })));
        telemetry.flushNow();
        Map<String, Number> failed = counters(telemetry, 0);
        assertEquals(1, failed.get("statistics.refresh_failed").intValue());
        assertFalse(failed.containsKey("statistics.refreshed"));

        AtomicInteger invocations = new AtomicInteger();
        PerformanceHintStatisticsRefresh.run(telemetry, "initial_dataset", invocations::incrementAndGet);
        telemetry.flushNow();
        Map<String, Number> success = counters(telemetry, 1);
        assertEquals(1, invocations.get());
        assertEquals(1, success.get("statistics.refreshed").intValue());
        assertEquals(1, success.get("statistics.refresh.initial_dataset").intValue());
    }

    @Test
    void completedCheckpointsKeepDatasetAndMutationCountsSeparate() throws Exception {
        PerformanceHintTelemetry telemetry = new PerformanceHintTelemetry(directory, "statistics-test");
        AtomicInteger invocations = new AtomicInteger();
        PerformanceHintStatisticsRefresh.run(telemetry, "initial_dataset", invocations::incrementAndGet);
        PerformanceHintStatisticsRefresh.run(telemetry, "qpg_mutation", invocations::incrementAndGet);
        telemetry.flushNow();
        Map<String, Number> completed = counters(telemetry, 0);
        assertEquals(2, invocations.get());
        assertEquals(2, completed.get("statistics.refreshed").intValue());
        assertEquals(1, completed.get("statistics.refresh.initial_dataset").intValue());
        assertEquals(1, completed.get("statistics.refresh.qpg_mutation").intValue());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Number> counters(PerformanceHintTelemetry telemetry, int index) throws IOException {
        Map<String, Object> record = new Yaml().load(Files.readAllLines(telemetry.getEventFile()).get(index));
        return (Map<String, Number>) record.get("delta");
    }
}
