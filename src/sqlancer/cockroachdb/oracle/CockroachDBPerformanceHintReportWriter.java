package sqlancer.cockroachdb.oracle;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.common.perfhint.PerformanceHintReportWriter;

/** CockroachDB reservation lifecycle binding for the shared report writer. */
final class CockroachDBPerformanceHintReportWriter {

    private static CockroachDBPerformanceHintReportWriter instance;

    private final PerformanceHintReportWriter delegate;
    private final Set<CockroachDBPerformanceHintFeedbackManager> feedbackManagers = ConcurrentHashMap.newKeySet();

    private CockroachDBPerformanceHintReportWriter(int capacity) {
        this(capacity, CockroachDBPerformanceHintOracleUtils::writeSnapshot);
    }

    private CockroachDBPerformanceHintReportWriter(int capacity, PerformanceHintReportWriter.SnapshotSink sink) {
        delegate = new PerformanceHintReportWriter(capacity, "cockroachdb-perfhint-report-writer", sink);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "cockroachdb-perfhint-report-writer-shutdown"));
    }

    static synchronized CockroachDBPerformanceHintReportWriter getInstance(int capacity) {
        if (instance == null) {
            instance = new CockroachDBPerformanceHintReportWriter(capacity);
        }
        return instance;
    }

    static CockroachDBPerformanceHintReportWriter createForTests(int capacity,
            PerformanceHintReportWriter.SnapshotSink sink) {
        return new CockroachDBPerformanceHintReportWriter(capacity, sink);
    }

    void submit(PerformanceHintReportSnapshot snapshot,
            CockroachDBPerformanceHintFeedbackManager.ReportReservation reservation,
            CockroachDBPerformanceHintFeedbackManager feedbackManager, CockroachDBPerformanceHintTelemetry telemetry,
            int offerMillis, boolean formalFailure) {
        feedbackManagers.add(feedbackManager);
        delegate.submit(snapshot, reportDir -> feedbackManager.completeReservation(reservation, reportDir),
                exception -> feedbackManager.failReservation(reservation, exception), telemetry, offerMillis,
                formalFailure);
    }

    private synchronized void close() {
        delegate.close();
        for (CockroachDBPerformanceHintFeedbackManager feedbackManager : feedbackManagers) {
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
