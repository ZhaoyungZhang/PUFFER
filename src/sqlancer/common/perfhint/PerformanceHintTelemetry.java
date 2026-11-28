package sqlancer.common.perfhint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Low-overhead, loss-tolerant PERFORMANCE_HINT funnel telemetry. */
public class PerformanceHintTelemetry {

    private static final int SCHEMA_VERSION = 1;
    private static final long DEFAULT_FLUSH_PERIOD_SECONDS = 60L;
    private static final long MIN_FLUSH_PERIOD_SECONDS = 1L;
    private static final long MAX_FLUSH_PERIOD_SECONDS = 3600L;

    private final Path stateDirectory;
    private final Path eventFile;
    private final long processId;
    private final long startedAtEpochMillis;
    private final ConcurrentMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> maximums = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFlushedCounters = new LinkedHashMap<>();
    private final ScheduledExecutorService flusher;
    private long lastFlushEpochMillis;

    protected PerformanceHintTelemetry(Path stateDirectory, String threadPrefix) {
        this.stateDirectory = stateDirectory;
        processId = ProcessHandle.current().pid();
        startedAtEpochMillis = System.currentTimeMillis();
        lastFlushEpochMillis = startedAtEpochMillis;
        eventFile = stateDirectory.resolve("perfhint_telemetry-" + processId + "-" + startedAtEpochMillis + ".ndjson");
        long flushPeriodSeconds = configuredFlushPeriodSeconds();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadPrefix + "-perfhint-telemetry-" + processId);
            thread.setDaemon(true);
            return thread;
        };
        flusher = Executors.newSingleThreadScheduledExecutor(threadFactory);
        flusher.scheduleAtFixedRate(this::flushSafely, flushPeriodSeconds, flushPeriodSeconds, TimeUnit.SECONDS);
        Runtime.getRuntime()
                .addShutdownHook(new Thread(this::close, threadPrefix + "-perfhint-telemetry-shutdown-" + processId));
    }

    public final void count(String counter) {
        counters.computeIfAbsent(counter, ignored -> new LongAdder()).increment();
    }

    public final void add(String counter, long amount) {
        if (amount != 0L) {
            counters.computeIfAbsent(counter, ignored -> new LongAdder()).add(amount);
        }
    }

    public final void recordMaximum(String metric, long value) {
        maximums.computeIfAbsent(metric, ignored -> new AtomicLong()).accumulateAndGet(value, Math::max);
    }

    public final void reject(String stage, String reason) {
        count("rejected." + normalize(stage));
        if (reason != null && !reason.isBlank()) {
            count("rejected." + normalize(stage) + "." + normalize(reason));
        }
    }

    public final synchronized void flushNow() {
        long now = System.currentTimeMillis();
        Map<String, Long> cumulative = snapshotCounters();
        Map<String, Long> delta = new TreeMap<>();
        for (Map.Entry<String, Long> entry : cumulative.entrySet()) {
            long previous = lastFlushedCounters.getOrDefault(entry.getKey(), 0L);
            long difference = entry.getValue() - previous;
            if (difference != 0L) {
                delta.put(entry.getKey(), difference);
            }
        }
        try {
            Files.createDirectories(stateDirectory);
            Files.writeString(eventFile,
                    toJson(now, Math.max(0L, now - lastFlushEpochMillis), delta) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lastFlushedCounters.clear();
            lastFlushedCounters.putAll(cumulative);
            lastFlushEpochMillis = now;
        } catch (IOException ignored) {
            // Telemetry must never change oracle behaviour.
        }
    }

    public final Path getEventFile() {
        return eventFile;
    }

    private void flushSafely() {
        try {
            flushNow();
        } catch (RuntimeException ignored) {
            // Telemetry must never change oracle behaviour.
        }
    }

    private synchronized void close() {
        flusher.shutdownNow();
        flushNow();
    }

    private Map<String, Long> snapshotCounters() {
        Map<String, Long> snapshot = new TreeMap<>();
        for (Map.Entry<String, LongAdder> entry : counters.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().sum());
        }
        for (Map.Entry<String, AtomicLong> entry : maximums.entrySet()) {
            snapshot.merge(entry.getKey(), entry.getValue().get(), Math::max);
        }
        return snapshot;
    }

    private String toJson(long now, long intervalMillis, Map<String, Long> delta) {
        return "{\"schemaVersion\":" + SCHEMA_VERSION + ",\"runId\":\"" + processId + "-" + startedAtEpochMillis
                + "\",\"processId\":" + processId + ",\"timestamp\":\"" + Instant.ofEpochMilli(now)
                + "\",\"timestampEpochMillis\":" + now + ",\"intervalMillis\":" + intervalMillis + ",\"delta\":"
                + countersToJson(delta) + "}";
    }

    private static long configuredFlushPeriodSeconds() {
        String configured = System.getenv("PERF_HINT_TELEMETRY_FLUSH_SECONDS");
        if (configured != null && !configured.isBlank()) {
            try {
                long seconds = Long.parseLong(configured.trim());
                if (seconds >= MIN_FLUSH_PERIOD_SECONDS && seconds <= MAX_FLUSH_PERIOD_SECONDS) {
                    return seconds;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the low-I/O default.
            }
        }
        return DEFAULT_FLUSH_PERIOD_SECONDS;
    }

    private static String countersToJson(Map<String, Long> values) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append('"').append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
            first = false;
        }
        return builder.append('}').toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
