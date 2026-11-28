package sqlancer.mariadb.oracle;

import sqlancer.common.perfhint.PerformanceQueryRewriteProvider;
import sqlancer.common.perfhint.WeTuneStaticRewriteProvider;
import sqlancer.mariadb.MariaDBOptions;

/** MariaDB option and DDL binding for the shared WeTune provider. */
final class MariaDBWeTuneStaticRewriteProvider {

    private MariaDBWeTuneStaticRewriteProvider() {
    }

    static PerformanceQueryRewriteProvider fromOptions(MariaDBOptions options) {
        return WeTuneStaticRewriteProvider.fromConfiguration("mariadb", options.performanceQueryRewriteProvider,
                options.performanceWetuneStaticWorker, options.performanceWetuneStaticTimeoutMs,
                MariaDBWeTuneSchemaDdlRenderer::render);
    }
}
