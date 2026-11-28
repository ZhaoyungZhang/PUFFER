package sqlancer.mariadb.oracle;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import sqlancer.common.perfhint.PerformanceHintTelemetry;
import sqlancer.mariadb.MariaDBProvider.MariaDBGlobalState;

/** MariaDB state-directory binding for the shared telemetry implementation. */
public final class MariaDBPerformanceHintTelemetry extends PerformanceHintTelemetry {

    private static final ConcurrentMap<Path, MariaDBPerformanceHintTelemetry> INSTANCES = new ConcurrentHashMap<>();

    private MariaDBPerformanceHintTelemetry(Path stateDirectory) {
        super(stateDirectory, "mariadb");
    }

    public static MariaDBPerformanceHintTelemetry getInstance(MariaDBGlobalState state) {
        return forStateDirectory(
                MariaDBPerformanceHintFeedbackManager.resolveStateDirectory(state.getDbmsSpecificOptions()));
    }

    static MariaDBPerformanceHintTelemetry forStateDirectory(Path stateDirectory) {
        Path normalized = stateDirectory.toAbsolutePath().normalize();
        return INSTANCES.computeIfAbsent(normalized, MariaDBPerformanceHintTelemetry::new);
    }
}
