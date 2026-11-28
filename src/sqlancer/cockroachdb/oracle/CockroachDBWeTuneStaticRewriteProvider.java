package sqlancer.cockroachdb.oracle;

import java.nio.file.Path;
import java.nio.file.Paths;

import sqlancer.cockroachdb.CockroachDBOptions;
import sqlancer.common.perfhint.PerformanceQueryRewriteProvider;
import sqlancer.common.perfhint.WeTuneStaticRewriteProvider;

/** CockroachDB option and DDL binding for the shared WeTune provider. */
final class CockroachDBWeTuneStaticRewriteProvider {

    private CockroachDBWeTuneStaticRewriteProvider() {
    }

    static PerformanceQueryRewriteProvider fromOptions(CockroachDBOptions options) {
        String configuredWorker = options.performanceWetuneStaticWorker == null ? ""
                : options.performanceWetuneStaticWorker.trim();
        if ("wetune-static".equalsIgnoreCase(options.performanceQueryRewriteProvider) && !configuredWorker.isEmpty()) {
            Path workerPath = Paths.get(configuredWorker);
            if (!workerPath.isAbsolute()) {
                throw new IllegalArgumentException("--perf-wetune-static-worker must be an absolute executable path");
            }
        }
        return WeTuneStaticRewriteProvider.fromConfiguration("cockroachdb", options.performanceQueryRewriteProvider,
                options.performanceWetuneStaticWorker, options.performanceWetuneStaticTimeoutMs,
                CockroachDBWeTuneSchemaDdlRenderer::render);
    }
}
