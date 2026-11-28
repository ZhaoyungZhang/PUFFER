package sqlancer.common.perfhint;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Process-wide bridge to the isolated Java 17 WeTune static rewriter. */
public final class WeTuneStaticRewriteProvider implements PerformanceQueryRewriteProvider, AutoCloseable {

    private static final int MAX_REWRITES = 16;
    private static final int DEADLINE_GRACE_MS = 50;
    private static final int STARTUP_TIMEOUT_MS = 10_000;
    private static final int WARMUP_TIMEOUT_MS = 5_000;
    private static final Map<WorkerConfiguration, WeTuneStaticRewriteProvider> SHARED_PROVIDERS = new HashMap<>();

    private final String dbmsName;
    private final Path workerPath;
    private final int timeoutMs;
    private final SchemaDdlRenderer ddlRenderer;
    private final String warmupSql;
    private final String warmupSchema;
    private long nextRequestId;
    private Process worker;
    private BufferedReader output;
    private BufferedWriter input;
    private String lastDiagnostic = "PH_REWRITE provider=wetune-static status=not_called";
    private boolean shutdownHookRegistered;

    private WeTuneStaticRewriteProvider(String dbmsName, Path workerPath, int timeoutMs,
            SchemaDdlRenderer ddlRenderer) {
        this.dbmsName = dbmsName;
        this.workerPath = workerPath;
        this.timeoutMs = timeoutMs;
        this.ddlRenderer = ddlRenderer;
        boolean postgresStyle = "cockroachdb".equals(dbmsName);
        warmupSql = postgresStyle ? "SELECT \"id\" FROM \"sqlancer_wetune_warmup\" WHERE \"id\" = 1"
                : "SELECT `id` FROM `sqlancer_wetune_warmup` WHERE `id` = 1";
        warmupSchema = postgresStyle
                ? "CREATE TABLE \"sqlancer_wetune_warmup\" (\"id\" BIGINT NOT NULL, PRIMARY KEY (\"id\"));"
                : "CREATE TABLE `sqlancer_wetune_warmup` (`id` INT NOT NULL, PRIMARY KEY (`id`));";
    }

    public static PerformanceQueryRewriteProvider fromConfiguration(String dbmsName, String configuredProvider,
            String configuredWorker, int timeoutMs, SchemaDdlRenderer ddlRenderer) {
        String normalizedProvider = configuredProvider == null ? "none"
                : configuredProvider.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(normalizedProvider)) {
            return PerformanceQueryRewriteProvider.noOp();
        }
        if (!"wetune-static".equals(normalizedProvider)) {
            throw new IllegalArgumentException(
                    "unknown " + dbmsName + " PERFORMANCE_HINT rewrite provider: " + normalizedProvider);
        }
        String normalizedWorker = configuredWorker == null ? "" : configuredWorker.trim();
        if (normalizedWorker.isEmpty()) {
            throw new IllegalArgumentException(
                    "--perf-wetune-static-worker is required when --perf-query-rewrite-provider=wetune-static");
        }
        String normalizedDbms = dbmsName.toLowerCase(Locale.ROOT);
        WorkerConfiguration configuration = new WorkerConfiguration(normalizedDbms,
                Paths.get(normalizedWorker).toAbsolutePath().normalize(), timeoutMs);
        synchronized (SHARED_PROVIDERS) {
            return SHARED_PROVIDERS.computeIfAbsent(configuration,
                    ignored -> new WeTuneStaticRewriteProvider(normalizedDbms, configuration.workerPath,
                            configuration.timeoutMs, ddlRenderer));
        }
    }

    @Override
    public synchronized List<RewrittenQuery> generateRewrites(QueryRewriteRequest request) {
        if (request.getCandidateBudget() <= 0 || !dbmsName.equalsIgnoreCase(request.getDbmsName())) {
            return List.of();
        }
        long requestId = ++nextRequestId;
        long startedAt = System.nanoTime();
        try {
            ensureWorker();
            String schemaDdl = ddlRenderer.render(request.getSchemaSummary());
            input.write(WeTuneStaticProtocol.request(dbmsName, requestId, request.getBaseSql(), schemaDdl,
                    Math.min(MAX_REWRITES, request.getCandidateBudget()), timeoutMs));
            input.newLine();
            input.flush();
            WeTuneStaticProtocol.Response response = WeTuneStaticProtocol
                    .parseResponse(readLine(timeoutMs + DEADLINE_GRACE_MS), requestId);
            if (!response.isOk()) {
                lastDiagnostic = diagnostic("error=" + response.getError(), startedAt, response.getElapsedMs(), 0);
                return List.of();
            }
            List<RewrittenQuery> rewrites = toStableRewrites(response.getRewrites(), request.getCandidateBudget());
            lastDiagnostic = diagnostic("status=ok", startedAt, response.getElapsedMs(), rewrites.size());
            return rewrites;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            closeWorker();
            lastDiagnostic = diagnostic("error=interrupted", startedAt, -1, 0);
            return List.of();
        } catch (Exception exception) {
            closeWorker();
            lastDiagnostic = diagnostic("error=" + diagnosticError(exception), startedAt, -1, 0);
            return List.of();
        }
    }

    public synchronized String getLastDiagnostic() {
        return lastDiagnostic;
    }

    private void ensureWorker() throws IOException, InterruptedException {
        if (worker != null && worker.isAlive()) {
            return;
        }
        closeWorker();
        if (!Files.isRegularFile(workerPath) || !Files.isExecutable(workerPath)) {
            throw new IOException("worker_not_executable");
        }
        ProcessBuilder builder = new ProcessBuilder(workerPath.toString());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        worker = builder.start();
        input = new BufferedWriter(new OutputStreamWriter(worker.getOutputStream(), StandardCharsets.UTF_8));
        output = new BufferedReader(new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8));
        if (!WeTuneStaticProtocol.isReady(readLine(STARTUP_TIMEOUT_MS))) {
            throw new IOException("worker_not_ready");
        }
        warmUpWorker();
        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::closeWorker, "sqlancer-wetune-static-shutdown"));
            shutdownHookRegistered = true;
        }
    }

    private void warmUpWorker() throws IOException, InterruptedException {
        input.write(WeTuneStaticProtocol.request(dbmsName, 0, warmupSql, warmupSchema, 1, WARMUP_TIMEOUT_MS));
        input.newLine();
        input.flush();
        WeTuneStaticProtocol.Response warmup = WeTuneStaticProtocol
                .parseResponse(readLine(WARMUP_TIMEOUT_MS + DEADLINE_GRACE_MS), 0);
        if (!warmup.isOk()) {
            throw new IOException("worker_warmup_" + warmup.getError());
        }
    }

    private String readLine(long deadlineMs) throws IOException, InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> line = new AtomicReference<>();
        AtomicReference<IOException> error = new AtomicReference<>();
        final BufferedReader currentOutput = output;
        Thread reader = new Thread(() -> {
            try {
                line.set(currentOutput == null ? null : currentOutput.readLine());
            } catch (IOException exception) {
                error.set(exception);
            } finally {
                completed.countDown();
            }
        }, "sqlancer-wetune-static-response");
        reader.setDaemon(true);
        reader.start();
        if (!completed.await(deadlineMs, TimeUnit.MILLISECONDS)) {
            throw new IOException("worker_timeout");
        }
        if (error.get() != null) {
            throw error.get();
        }
        if (line.get() == null) {
            throw new IOException("worker_eof");
        }
        return line.get();
    }

    private static List<RewrittenQuery> toStableRewrites(List<WeTuneStaticProtocol.Rewrite> workerRewrites,
            int candidateBudget) {
        Map<String, RewrittenQuery> distinct = new LinkedHashMap<>();
        for (WeTuneStaticProtocol.Rewrite workerRewrite : workerRewrites) {
            String trace = String.join(",", workerRewrite.getRuleTrace());
            String ruleId = "wetune-static:" + WeTuneStaticProtocol.UPSTREAM_COMMIT + ":"
                    + WeTuneStaticProtocol.RULES_SHA256 + ":" + trace;
            String description = workerRewrite.getDescription().isEmpty() ? "WeTune static rule trace: " + trace
                    : workerRewrite.getDescription();
            distinct.putIfAbsent(workerRewrite.getSql(),
                    new RewrittenQuery(workerRewrite.getSql(), WeTuneStaticProtocol.FAMILY, ruleId, description));
        }
        List<RewrittenQuery> sorted = new ArrayList<>(distinct.values());
        sorted.sort(Comparator.comparing(RewrittenQuery::getRewrittenSql).thenComparing(RewrittenQuery::getRuleId));
        return List.copyOf(sorted.subList(0, Math.min(Math.min(MAX_REWRITES, candidateBudget), sorted.size())));
    }

    private String diagnostic(String status, long startedAt, long workerElapsedMs, int count) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return String.format(Locale.US,
                "PH_REWRITE provider=wetune-static %s elapsedMs=%d workerElapsedMs=%d candidates=%d rulesSha256=%s",
                status, elapsedMs, workerElapsedMs, count, WeTuneStaticProtocol.RULES_SHA256);
    }

    private static String diagnosticError(Exception exception) {
        String type = exception.getClass().getSimpleName();
        String message = exception.getMessage();
        return message == null || message.isBlank() ? type : type + ":" + message.replaceAll("\\s+", "_");
    }

    @Override
    public synchronized void close() {
        closeWorker();
        synchronized (SHARED_PROVIDERS) {
            SHARED_PROVIDERS.values().removeIf(provider -> provider == this);
        }
    }

    private synchronized void closeWorker() {
        if (worker != null) {
            worker.destroy();
            if (worker.isAlive()) {
                worker.destroyForcibly();
            }
        }
        worker = null;
        input = null;
        output = null;
    }

    @FunctionalInterface
    public interface SchemaDdlRenderer {
        String render(QueryRewriteSchemaSummary schema);
    }

    private static final class WorkerConfiguration {
        private final String dbmsName;
        private final Path workerPath;
        private final int timeoutMs;

        private WorkerConfiguration(String dbmsName, Path workerPath, int timeoutMs) {
            this.dbmsName = dbmsName;
            this.workerPath = workerPath;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof WorkerConfiguration)) {
                return false;
            }
            WorkerConfiguration that = (WorkerConfiguration) other;
            return timeoutMs == that.timeoutMs && dbmsName.equals(that.dbmsName) && workerPath.equals(that.workerPath);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * dbmsName.hashCode() + workerPath.hashCode()) + timeoutMs;
        }
    }
}
