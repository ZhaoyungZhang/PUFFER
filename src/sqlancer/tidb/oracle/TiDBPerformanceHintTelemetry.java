package sqlancer.tidb.oracle;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import sqlancer.common.perfhint.PerformanceHintTelemetry;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;

/** TiDB state-directory binding for the shared telemetry implementation. */
public final class TiDBPerformanceHintTelemetry extends PerformanceHintTelemetry {

    private static final ConcurrentMap<Path, TiDBPerformanceHintTelemetry> INSTANCES = new ConcurrentHashMap<>();

    private TiDBPerformanceHintTelemetry(Path stateDirectory) {
        super(stateDirectory, "tidb");
    }

    public static TiDBPerformanceHintTelemetry getInstance(TiDBGlobalState state) {
        return forStateDirectory(
                TiDBPerformanceHintFeedbackManager.resolveStateDirectory(state.getDbmsSpecificOptions()));
    }

    static TiDBPerformanceHintTelemetry forStateDirectory(Path stateDirectory) {
        Path normalized = stateDirectory.toAbsolutePath().normalize();
        return INSTANCES.computeIfAbsent(normalized, TiDBPerformanceHintTelemetry::new);
    }
}
