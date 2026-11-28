package sqlancer.common.perfhint;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, fully rendered PERFORMANCE_HINT report artifacts. */
public final class PerformanceHintReportSnapshot {

    private final Path finalReportDirectory;
    private final Map<String, String> files;

    public PerformanceHintReportSnapshot(Path finalReportDirectory, Map<String, String> files) {
        this.finalReportDirectory = Objects.requireNonNull(finalReportDirectory);
        this.files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }

    public Path getFinalReportDirectory() {
        return finalReportDirectory;
    }

    public Map<String, String> getFiles() {
        return files;
    }
}
