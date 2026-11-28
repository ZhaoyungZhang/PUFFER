package sqlancer.mariadb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.common.perfhint.PerformanceNineCellCategory;
import sqlancer.mariadb.MariaDBOptions;

public class TestMariaDBPerformanceHintReporting {

    @TempDir
    Path temporaryDirectory;

    @Test
    public void testSnapshotDoesNotObserveSourceMapMutation() throws Exception {
        Map<String, String> sourceFiles = new LinkedHashMap<>();
        sourceFiles.put("setup.sql", "CREATE TABLE t0(c0 INT);\n");
        sourceFiles.put("original_query.sql", "SELECT c0 FROM t0;\n");
        Path reportDirectory = temporaryDirectory.resolve("reports").resolve("case_immutable");
        PerformanceHintReportSnapshot snapshot = new PerformanceHintReportSnapshot(reportDirectory, sourceFiles);

        sourceFiles.put("setup.sql", "MUTATED\n");
        sourceFiles.put("unexpected.txt", "must not be written");
        MariaDBPerformanceHintOracleUtils.writeSnapshot(snapshot);

        assertEquals("CREATE TABLE t0(c0 INT);\n",
                Files.readString(reportDirectory.resolve("setup.sql"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(reportDirectory.resolve("unexpected.txt")));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getFiles().put("unexpected.txt", "must not be writable"));
    }

    @Test
    public void testFormalWitnessPolicyRetainsFailFastUnlessExplicitlyEnabled() {
        MariaDBPerformanceHintOracleUtils.PerformanceHintWitness witness = newWitness();
        assertDoesNotThrow(
                () -> MariaDBPerformanceHintOracle.enforceFormalWitnessPolicy(true, witness, temporaryDirectory));
        AssertionError failure = assertThrows(AssertionError.class,
                () -> MariaDBPerformanceHintOracle.enforceFormalWitnessPolicy(false, witness, temporaryDirectory));
        assertTrue(failure.getMessage().contains("Potential PERFORMANCE_HINT counterfactual issue"));
    }

    @Test
    public void testConcurrentReservationsDoNotBreakHardCaps() throws Exception {
        MariaDBPerformanceHintFeedbackManager feedback = MariaDBPerformanceHintFeedbackManager
                .forStateDirectory(temporaryDirectory.resolve("feedback"));
        MariaDBOptions options = new MariaDBOptions();
        options.enablePerformanceFeedbackDedup = true;
        options.enablePerformanceFeedbackRootCauseDedup = true;
        options.performanceFeedbackSignatureHardCap = 1;
        options.performanceFeedbackRootCauseHardCap = 1;
        options.enablePerformanceFeedbackRepresentativeReplacement = false;

        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<Callable<MariaDBPerformanceHintFeedbackManager.WitnessDecision>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> feedback.recordWitness(newWitness(), options));
            }
            List<Future<MariaDBPerformanceHintFeedbackManager.WitnessDecision>> futures = executor.invokeAll(tasks);
            List<MariaDBPerformanceHintFeedbackManager.WitnessDecision> accepted = new ArrayList<>();
            for (Future<MariaDBPerformanceHintFeedbackManager.WitnessDecision> future : futures) {
                MariaDBPerformanceHintFeedbackManager.WitnessDecision decision = future.get();
                if (decision.shouldWriteReport()) {
                    accepted.add(decision);
                }
            }
            assertEquals(1, accepted.size());
            assertNotNull(accepted.get(0).getReservation());

            feedback.failReservation(accepted.get(0).getReservation(), new IllegalStateException("test failure"));
            MariaDBPerformanceHintFeedbackManager.WitnessDecision retried = feedback.recordWitness(newWitness(),
                    options);
            assertTrue(retried.shouldWriteReport());
            assertNotNull(retried.getReservation());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testBoundedWriterDrainsAllReports() throws Exception {
        Path feedbackDirectory = temporaryDirectory.resolve("writer-feedback");
        MariaDBPerformanceHintFeedbackManager feedback = MariaDBPerformanceHintFeedbackManager
                .forStateDirectory(feedbackDirectory);
        MariaDBPerformanceHintTelemetry telemetry = MariaDBPerformanceHintTelemetry
                .forStateDirectory(feedbackDirectory);
        MariaDBPerformanceHintReportWriter writer = MariaDBPerformanceHintReportWriter.getInstance(1);
        try {
            for (int i = 0; i < 16; i++) {
                Path reportDirectory = temporaryDirectory.resolve("written").resolve("case_" + i);
                Map<String, String> files = Map.of("setup.sql", "CREATE TABLE t" + i + "(c INT);\n");
                MariaDBPerformanceHintFeedbackManager.ReportReservation reservation = reservationFor(i);
                writer.submit(new PerformanceHintReportSnapshot(reportDirectory, files), reservation, feedback,
                        telemetry, 1, false);
            }
        } finally {
            MariaDBPerformanceHintReportWriter.shutdownForTests();
        }
        for (int i = 0; i < 16; i++) {
            assertTrue(Files.isDirectory(temporaryDirectory.resolve("written").resolve("case_" + i)));
            assertTrue(Files.exists(temporaryDirectory.resolve("written").resolve("case_" + i).resolve("setup.sql")));
        }
        String feedbackSummary = Files.readString(feedbackDirectory.resolve("perfhint_feedback_summary.json"));
        assertTrue(feedbackSummary.contains("\"totalWitnessesWritten\": 16"));
    }

    @Test
    public void testQueueBackpressureUsesCallerRunsWithoutDroppingReport() throws Exception {
        Path feedbackDirectory = temporaryDirectory.resolve("backpressure-feedback");
        MariaDBPerformanceHintFeedbackManager feedback = MariaDBPerformanceHintFeedbackManager
                .forStateDirectory(feedbackDirectory);
        MariaDBPerformanceHintTelemetry telemetry = MariaDBPerformanceHintTelemetry
                .forStateDirectory(feedbackDirectory);
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch callerRunsWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseWrites = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        MariaDBPerformanceHintReportWriter writer = MariaDBPerformanceHintReportWriter.createForTests(1, snapshot -> {
            int writeNumber = writes.incrementAndGet();
            if (writeNumber <= 2) {
                if (writeNumber == 1) {
                    firstWriteEntered.countDown();
                } else {
                    callerRunsWriteEntered.countDown();
                }
                try {
                    if (!releaseWrites.await(5, TimeUnit.SECONDS)) {
                        throw new java.io.IOException("test writer was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("test writer was interrupted", exception);
                }
            }
            Files.createDirectories(snapshot.getFinalReportDirectory());
            Files.writeString(snapshot.getFinalReportDirectory().resolve("setup.sql"), "CREATE TABLE t(c INT);\n");
            return snapshot.getFinalReportDirectory();
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            writer.submit(snapshotFor("first"), reservationFor(101), feedback, telemetry, 1, false);
            assertTrue(firstWriteEntered.await(5, TimeUnit.SECONDS), "worker should begin the first write");
            writer.submit(snapshotFor("second"), reservationFor(102), feedback, telemetry, 1, false);
            Future<?> thirdSubmission = executor.submit(
                    () -> writer.submit(snapshotFor("third"), reservationFor(103), feedback, telemetry, 1, false));
            assertTrue(callerRunsWriteEntered.await(5, TimeUnit.SECONDS),
                    "caller-runs should enter the blocked writer");
            assertFalse(thirdSubmission.isDone());
            releaseWrites.countDown();
            thirdSubmission.get(5, TimeUnit.SECONDS);
        } finally {
            releaseWrites.countDown();
            executor.shutdownNow();
            writer.closeForTests();
        }
        telemetry.flushNow();
        String telemetryEvent = Files.readString(telemetry.getEventFile());
        assertTrue(telemetryEvent.contains("\"reports.queue_backpressure\":1"));
        assertTrue(telemetryEvent.contains("\"reports.written\":3"));
        try (Stream<Path> reports = Files.list(temporaryDirectory.resolve("written"))) {
            assertEquals(3L, reports.count());
        }
    }

    @Test
    public void testWriteFailureRetriesThenReleasesReservation() throws Exception {
        Path feedbackDirectory = temporaryDirectory.resolve("write-failure-feedback");
        MariaDBPerformanceHintFeedbackManager feedback = MariaDBPerformanceHintFeedbackManager
                .forStateDirectory(feedbackDirectory);
        MariaDBPerformanceHintTelemetry telemetry = MariaDBPerformanceHintTelemetry
                .forStateDirectory(feedbackDirectory);
        Path blockedParent = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(blockedParent, "regular file");
        MariaDBPerformanceHintReportWriter writer = MariaDBPerformanceHintReportWriter.getInstance(1);
        try {
            writer.submit(
                    new PerformanceHintReportSnapshot(blockedParent.resolve("case"),
                            Map.of("setup.sql", "CREATE TABLE t(c INT);\n")),
                    reservationFor(104), feedback, telemetry, 1, false);
        } finally {
            MariaDBPerformanceHintReportWriter.shutdownForTests();
        }
        telemetry.flushNow();
        String telemetryEvent = Files.readString(telemetry.getEventFile());
        assertTrue(telemetryEvent.contains("\"reports.retry\":1"));
        assertTrue(telemetryEvent.contains("\"reports.write_failed\":1"));
        String feedbackSummary = Files.readString(feedbackDirectory.resolve("perfhint_feedback_summary.json"));
        assertTrue(feedbackSummary.contains("\"totalWitnessesWriteFailed\": 1"));
        assertFalse(Files.exists(blockedParent.resolve("case")));
    }

    private static MariaDBPerformanceHintOracleUtils.PerformanceHintWitness newWitness() {
        MariaDBPerformanceHintOracleUtils.PlanSummary defaultPlan = MariaDBPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"100\"},\"table\":{\"access_type\":\"ALL\"}}}");
        MariaDBPerformanceHintOracleUtils.PlanSummary counterfactualPlan = MariaDBPerformanceHintOracleUtils.parsePlan(
                "{\"query_block\":{\"cost_info\":{\"query_cost\":\"10\"},\"table\":{\"access_type\":\"range\",\"key\":\"i0\"}}}");
        CounterfactualCandidate candidate = CounterfactualCandidate.hint("SELECT /*+ INDEX(t0, i0) */ c0 FROM t0",
                "INDEX", "INDEX", "INDEX(t0, i0)", List.of("t0"), List.of("i0"));
        MariaDBPerformanceHintOracleUtils.PerformanceHintWitness witness = new MariaDBPerformanceHintOracleUtils.PerformanceHintWitness(
                "SELECT c0 FROM t0", candidate.getAlternativeSql(), candidate, defaultPlan, counterfactualPlan,
                List.of("index_changed"), true);
        witness.setSkeletonKey("SELECT|TABLES_1");
        witness.setFamilyKey("family");
        witness.setPlanSignatureKey("signature");
        witness.setRootCauseSignatureKey("root-cause");
        witness.setNineCellCategory(PerformanceNineCellCategory.B_CHEAPER_SIMILAR);
        return witness;
    }

    private static MariaDBPerformanceHintFeedbackManager.ReportReservation reservationFor(int id) {
        return new MariaDBPerformanceHintFeedbackManager.ReportReservation("skeleton-" + id, "family-" + id,
                "signature-" + id, "root-" + id, 2.0, 10.0, 1.0, 1.0, "best_runtime_speedup", "NEW_ROOT_CAUSE", "B");
    }

    private PerformanceHintReportSnapshot snapshotFor(String id) {
        Path reportDirectory = temporaryDirectory.resolve("written").resolve("case_" + id);
        return new PerformanceHintReportSnapshot(reportDirectory,
                Map.of("setup.sql", "CREATE TABLE " + id + "(c INT);\n"));
    }
}
