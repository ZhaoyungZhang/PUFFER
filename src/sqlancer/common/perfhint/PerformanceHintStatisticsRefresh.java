package sqlancer.common.perfhint;

/**
 * Statistics checkpoints follow SQL emitted by the provider: once after dataset construction, and once after a
 * successful data/index mutation or row recovery. No database inspection or per-query freshness polling is needed.
 */
public final class PerformanceHintStatisticsRefresh {

    @FunctionalInterface
    public interface RefreshOperation {
        void run() throws Exception;
    }

    private PerformanceHintStatisticsRefresh() {
    }

    public static void run(PerformanceHintTelemetry telemetry, String reason, RefreshOperation operation)
            throws Exception {
        long startedAt = System.nanoTime();
        try {
            operation.run();
            telemetry.count("statistics.refreshed");
            telemetry.count("statistics.refresh." + reason);
        } catch (Exception exception) {
            telemetry.count("statistics.refresh_failed");
            throw exception;
        } finally {
            telemetry.add("statistics.refresh_nanos." + reason, System.nanoTime() - startedAt);
        }
    }
}
