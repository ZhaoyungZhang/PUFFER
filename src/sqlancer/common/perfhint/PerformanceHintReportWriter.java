package sqlancer.common.perfhint;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Bounded asynchronous writer shared by all PERFORMANCE_HINT DBMS adapters. */
public final class PerformanceHintReportWriter {

    private static final int MAX_WRITE_ATTEMPTS = 2;

    private final ArrayBlockingQueue<ReportTask> queue;
    private final Thread worker;
    private final SnapshotSink snapshotSink;
    private volatile boolean accepting = true;

    public PerformanceHintReportWriter(int capacity, String threadName, SnapshotSink snapshotSink) {
        queue = new ArrayBlockingQueue<>(capacity);
        this.snapshotSink = snapshotSink;
        worker = new Thread(this::run, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    public void submit(PerformanceHintReportSnapshot snapshot, CompletionCallback onComplete, FailureCallback onFailure,
            PerformanceHintTelemetry telemetry, int offerMillis, boolean formalFailure) {
        ReportTask task = new ReportTask(snapshot, onComplete, onFailure, telemetry, formalFailure);
        telemetry.count("reports.selected");
        if (!accepting) {
            telemetry.count("reports.queue_closed_caller_runs");
            write(task);
            return;
        }
        try {
            if (queue.offer(task, offerMillis, TimeUnit.MILLISECONDS)) {
                telemetry.count("reports.enqueued");
                telemetry.recordMaximum("reports.queue_max_depth", queue.size());
                return;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            telemetry.count("reports.queue_interrupted_caller_runs");
            write(task);
            return;
        }
        telemetry.count("reports.queue_backpressure");
        write(task);
    }

    private void run() {
        while (accepting || !queue.isEmpty()) {
            try {
                ReportTask task = queue.poll(100L, TimeUnit.MILLISECONDS);
                if (task != null) {
                    write(task);
                }
            } catch (InterruptedException exception) {
                if (!accepting) {
                    break;
                }
                Thread.currentThread().interrupt();
                break;
            }
        }
        ReportTask task;
        while ((task = queue.poll()) != null) {
            write(task);
        }
    }

    private void write(ReportTask task) {
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            final Path reportDirectory;
            try {
                reportDirectory = snapshotSink.write(task.snapshot);
            } catch (IOException exception) {
                if (attempt < MAX_WRITE_ATTEMPTS) {
                    task.telemetry.count("reports.retry");
                    continue;
                }
                recordWriteFailure(task, exception);
                return;
            }
            try {
                task.onComplete.accept(reportDirectory);
            } catch (IOException exception) {
                task.telemetry.count("reports.feedback_completion_failed");
            }
            task.telemetry.count("reports.written");
            task.telemetry.count(task.formalFailure ? "reports.formal_written" : "reports.observation_written");
            return;
        }
    }

    private static void recordWriteFailure(ReportTask task, IOException exception) {
        task.telemetry.count("reports.write_failed");
        try {
            task.onFailure.accept(exception);
        } catch (IOException feedbackFailure) {
            task.telemetry.count("reports.feedback_failure_after_write_failure");
        }
    }

    public synchronized void close() {
        if (!accepting) {
            return;
        }
        accepting = false;
        try {
            worker.join(TimeUnit.SECONDS.toMillis(60L));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            List<ReportTask> unflushed = new ArrayList<>();
            queue.drainTo(unflushed);
            for (ReportTask task : unflushed) {
                task.telemetry.count("reports.shutdown_timeout");
                recordWriteFailure(task, new IOException("report writer shutdown timed out before writing report"));
            }
        }
    }

    @FunctionalInterface
    public interface SnapshotSink {
        Path write(PerformanceHintReportSnapshot snapshot) throws IOException;
    }

    @FunctionalInterface
    public interface CompletionCallback {
        void accept(Path reportDirectory) throws IOException;
    }

    @FunctionalInterface
    public interface FailureCallback {
        void accept(IOException exception) throws IOException;
    }

    private static final class ReportTask {
        private final PerformanceHintReportSnapshot snapshot;
        private final CompletionCallback onComplete;
        private final FailureCallback onFailure;
        private final PerformanceHintTelemetry telemetry;
        private final boolean formalFailure;

        private ReportTask(PerformanceHintReportSnapshot snapshot, CompletionCallback onComplete,
                FailureCallback onFailure, PerformanceHintTelemetry telemetry, boolean formalFailure) {
            this.snapshot = snapshot;
            this.onComplete = onComplete;
            this.onFailure = onFailure;
            this.telemetry = telemetry;
            this.formalFailure = formalFailure;
        }
    }
}
