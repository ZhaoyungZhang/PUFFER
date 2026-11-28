package sqlancer.cockroachdb.oracle;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import sqlancer.cockroachdb.CockroachDBProvider.CockroachDBGlobalState;
import sqlancer.common.perfhint.PerformanceHintTelemetry;

/** CockroachDB state-directory binding for the shared telemetry implementation. */
public final class CockroachDBPerformanceHintTelemetry extends PerformanceHintTelemetry {

    private static final ConcurrentMap<Path, CockroachDBPerformanceHintTelemetry> INSTANCES = new ConcurrentHashMap<>();

    private CockroachDBPerformanceHintTelemetry(Path stateDirectory) {
        super(stateDirectory, "cockroachdb");
    }

    public static CockroachDBPerformanceHintTelemetry getInstance(CockroachDBGlobalState state) {
        return forStateDirectory(
                CockroachDBPerformanceHintFeedbackManager.resolveStateDirectory(state.getDbmsSpecificOptions()));
    }

    static CockroachDBPerformanceHintTelemetry forStateDirectory(Path stateDirectory) {
        Path normalized = stateDirectory.toAbsolutePath().normalize();
        return INSTANCES.computeIfAbsent(normalized, CockroachDBPerformanceHintTelemetry::new);
    }
}
