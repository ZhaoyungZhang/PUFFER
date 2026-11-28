package sqlancer.mysql.oracle;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import sqlancer.common.perfhint.PerformanceHintTelemetry;
import sqlancer.mysql.MySQLGlobalState;

/** MySQL state-directory binding for the shared telemetry implementation. */
public final class MySQLPerformanceHintTelemetry extends PerformanceHintTelemetry {

    private static final ConcurrentMap<Path, MySQLPerformanceHintTelemetry> INSTANCES = new ConcurrentHashMap<>();

    private MySQLPerformanceHintTelemetry(Path stateDirectory) {
        super(stateDirectory, "mysql");
    }

    public static MySQLPerformanceHintTelemetry getInstance(MySQLGlobalState state) {
        return forStateDirectory(
                MySQLPerformanceHintFeedbackManager.resolveStateDirectory(state.getDbmsSpecificOptions()));
    }

    static MySQLPerformanceHintTelemetry forStateDirectory(Path stateDirectory) {
        Path normalized = stateDirectory.toAbsolutePath().normalize();
        return INSTANCES.computeIfAbsent(normalized, MySQLPerformanceHintTelemetry::new);
    }
}
