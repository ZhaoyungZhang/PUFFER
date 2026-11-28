package sqlancer.oceanbase.oracle;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.common.perfhint.PerformanceHintReportWriter;

/** OceanBase reservation lifecycle binding for the shared report writer. */
final class OceanBasePerformanceHintReportWriter {

    private static OceanBasePerformanceHintReportWriter instance;

    private final PerformanceHintReportWriter delegate;
    private final Set<OceanBasePerformanceHintFeedbackManager> feedbackManagers = ConcurrentHashMap.newKeySet();

    private OceanBasePerformanceHintReportWriter(int capacity) {
        this(capacity, OceanBasePerformanceHintOracleUtils::writeSnapshot);
    }

    private OceanBasePerformanceHintReportWriter(int capacity, PerformanceHintReportWriter.SnapshotSink sink) {
        delegate = new PerformanceHintReportWriter(capacity, "oceanbase-perfhint-report-writer", sink);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "oceanbase-perfhint-report-writer-shutdown"));
    }

    static synchronized OceanBasePerformanceHintReportWriter getInstance(int capacity) {
        if (instance == null) {
            instance = new OceanBasePerformanceHintReportWriter(capacity);
        }
        return instance;
    }

    static OceanBasePerformanceHintReportWriter createForTests(int capacity,
            PerformanceHintReportWriter.SnapshotSink sink) {
        return new OceanBasePerformanceHintReportWriter(capacity, sink);
    }

    void submit(PerformanceHintReportSnapshot snapshot,
            OceanBasePerformanceHintFeedbackManager.ReportReservation reservation,
            OceanBasePerformanceHintFeedbackManager feedbackManager, OceanBasePerformanceHintTelemetry telemetry,
            int offerMillis, boolean formalFailure) {
        feedbackManagers.add(feedbackManager);
        delegate.submit(snapshot, reportDir -> feedbackManager.completeReservation(reservation, reportDir),
                exception -> feedbackManager.failReservation(reservation, exception), telemetry, offerMillis,
                formalFailure);
    }

    private synchronized void close() {
        delegate.close();
        for (OceanBasePerformanceHintFeedbackManager feedbackManager : feedbackManagers) {
            try {
                feedbackManager.checkpointAfterReportDrain();
            } catch (Exception ignored) {
                // A later checkpoint or feedback shutdown hook can retry.
            }
        }
    }

    static synchronized void shutdownForTests() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    void closeForTests() {
        close();
    }
}
