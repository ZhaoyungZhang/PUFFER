package sqlancer.mysql.oracle;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.common.perfhint.PerformanceHintReportWriter;

/** MySQL reservation lifecycle binding for the shared report writer. */
final class MySQLPerformanceHintReportWriter {

    private static MySQLPerformanceHintReportWriter instance;

    private final PerformanceHintReportWriter delegate;
    private final Set<MySQLPerformanceHintFeedbackManager> feedbackManagers = ConcurrentHashMap.newKeySet();

    private MySQLPerformanceHintReportWriter(int capacity) {
        this(capacity, MySQLPerformanceHintOracleUtils::writeSnapshot);
    }

    private MySQLPerformanceHintReportWriter(int capacity, PerformanceHintReportWriter.SnapshotSink sink) {
        delegate = new PerformanceHintReportWriter(capacity, "mysql-perfhint-report-writer", sink);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "mysql-perfhint-report-writer-shutdown"));
    }

    static synchronized MySQLPerformanceHintReportWriter getInstance(int capacity) {
        if (instance == null) {
            instance = new MySQLPerformanceHintReportWriter(capacity);
        }
        return instance;
    }

    static MySQLPerformanceHintReportWriter createForTests(int capacity,
            PerformanceHintReportWriter.SnapshotSink sink) {
        return new MySQLPerformanceHintReportWriter(capacity, sink);
    }

    void submit(PerformanceHintReportSnapshot snapshot,
            MySQLPerformanceHintFeedbackManager.ReportReservation reservation,
            MySQLPerformanceHintFeedbackManager feedbackManager, MySQLPerformanceHintTelemetry telemetry,
            int offerMillis, boolean formalFailure) {
        feedbackManagers.add(feedbackManager);
        delegate.submit(snapshot, reportDir -> feedbackManager.completeReservation(reservation, reportDir),
                exception -> feedbackManager.failReservation(reservation, exception), telemetry, offerMillis,
                formalFailure);
    }

    private synchronized void close() {
        delegate.close();
        for (MySQLPerformanceHintFeedbackManager feedbackManager : feedbackManagers) {
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
