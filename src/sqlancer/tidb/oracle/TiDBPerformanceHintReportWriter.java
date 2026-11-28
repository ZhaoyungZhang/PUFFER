package sqlancer.tidb.oracle;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.common.perfhint.PerformanceHintReportWriter;

/** TiDB reservation lifecycle binding for the shared report writer. */
final class TiDBPerformanceHintReportWriter {

    private static TiDBPerformanceHintReportWriter instance;

    private final PerformanceHintReportWriter delegate;
    private final Set<TiDBPerformanceHintFeedbackManager> feedbackManagers = ConcurrentHashMap.newKeySet();

    private TiDBPerformanceHintReportWriter(int capacity) {
        this(capacity, TiDBPerformanceHintOracleUtils::writeSnapshot);
    }

    private TiDBPerformanceHintReportWriter(int capacity, PerformanceHintReportWriter.SnapshotSink sink) {
        delegate = new PerformanceHintReportWriter(capacity, "tidb-perfhint-report-writer", sink);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "tidb-perfhint-report-writer-shutdown"));
    }

    static synchronized TiDBPerformanceHintReportWriter getInstance(int capacity) {
        if (instance == null) {
            instance = new TiDBPerformanceHintReportWriter(capacity);
        }
        return instance;
    }

    static TiDBPerformanceHintReportWriter createForTests(int capacity, PerformanceHintReportWriter.SnapshotSink sink) {
        return new TiDBPerformanceHintReportWriter(capacity, sink);
    }

    void submit(PerformanceHintReportSnapshot snapshot,
            TiDBPerformanceHintFeedbackManager.ReportReservation reservation,
            TiDBPerformanceHintFeedbackManager feedbackManager, TiDBPerformanceHintTelemetry telemetry, int offerMillis,
            boolean formalFailure) {
        feedbackManagers.add(feedbackManager);
        delegate.submit(snapshot, reportDir -> feedbackManager.completeReservation(reservation, reportDir),
                exception -> feedbackManager.failReservation(reservation, exception), telemetry, offerMillis,
                formalFailure);
    }

    private synchronized void close() {
        delegate.close();
        for (TiDBPerformanceHintFeedbackManager feedbackManager : feedbackManagers) {
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
