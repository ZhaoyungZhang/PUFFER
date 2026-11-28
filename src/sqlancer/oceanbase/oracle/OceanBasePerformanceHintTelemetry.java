package sqlancer.oceanbase.oracle;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import sqlancer.common.perfhint.PerformanceHintTelemetry;
import sqlancer.oceanbase.OceanBaseGlobalState;

/** OceanBase state-directory binding for the shared telemetry implementation. */
public final class OceanBasePerformanceHintTelemetry extends PerformanceHintTelemetry {

    private static final ConcurrentMap<Path, OceanBasePerformanceHintTelemetry> INSTANCES = new ConcurrentHashMap<>();

    private OceanBasePerformanceHintTelemetry(Path stateDirectory) {
        super(stateDirectory, "oceanbase");
    }

    public static OceanBasePerformanceHintTelemetry getInstance(OceanBaseGlobalState state) {
        return forStateDirectory(
                OceanBasePerformanceHintFeedbackManager.resolveStateDirectory(state.getDbmsSpecificOptions()));
    }

    static OceanBasePerformanceHintTelemetry forStateDirectory(Path stateDirectory) {
        Path normalized = stateDirectory.toAbsolutePath().normalize();
        return INSTANCES.computeIfAbsent(normalized, OceanBasePerformanceHintTelemetry::new);
    }
}
