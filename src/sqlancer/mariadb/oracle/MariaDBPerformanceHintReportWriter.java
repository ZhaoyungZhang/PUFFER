package sqlancer.mariadb.oracle;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.common.perfhint.PerformanceHintReportWriter;

/** MariaDB reservation lifecycle binding for the shared report writer. */
final class MariaDBPerformanceHintReportWriter {

    private static MariaDBPerformanceHintReportWriter instance;

    private final PerformanceHintReportWriter delegate;
    private final Set<MariaDBPerformanceHintFeedbackManager> feedbackManagers = ConcurrentHashMap.newKeySet();

    private MariaDBPerformanceHintReportWriter(int capacity) {
        this(capacity, MariaDBPerformanceHintOracleUtils::writeSnapshot);
    }

    private MariaDBPerformanceHintReportWriter(int capacity, PerformanceHintReportWriter.SnapshotSink sink) {
        delegate = new PerformanceHintReportWriter(capacity, "mariadb-perfhint-report-writer", sink);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "mariadb-perfhint-report-writer-shutdown"));
    }

    static synchronized MariaDBPerformanceHintReportWriter getInstance(int capacity) {
        if (instance == null) {
            instance = new MariaDBPerformanceHintReportWriter(capacity);
        }
        return instance;
    }

    static MariaDBPerformanceHintReportWriter createForTests(int capacity,
            PerformanceHintReportWriter.SnapshotSink sink) {
        return new MariaDBPerformanceHintReportWriter(capacity, sink);
    }

    void submit(PerformanceHintReportSnapshot snapshot,
            MariaDBPerformanceHintFeedbackManager.ReportReservation reservation,
            MariaDBPerformanceHintFeedbackManager feedbackManager, MariaDBPerformanceHintTelemetry telemetry,
            int offerMillis, boolean formalFailure) {
        feedbackManagers.add(feedbackManager);
        delegate.submit(snapshot, reportDir -> feedbackManager.completeReservation(reservation, reportDir),
                exception -> feedbackManager.failReservation(reservation, exception), telemetry, offerMillis,
                formalFailure);
    }

    private synchronized void close() {
        delegate.close();
        for (MariaDBPerformanceHintFeedbackManager feedbackManager : feedbackManagers) {
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
