package sqlancer.mysql.oracle;

import sqlancer.common.perfhint.PerformanceQueryRewriteProvider;
import sqlancer.common.perfhint.WeTuneStaticRewriteProvider;
import sqlancer.mysql.MySQLOptions;

/** MySQL option and DDL binding for the shared WeTune provider. */
final class MySQLWeTuneStaticRewriteProvider {

    private MySQLWeTuneStaticRewriteProvider() {
    }

    static PerformanceQueryRewriteProvider fromOptions(MySQLOptions options) {
        return WeTuneStaticRewriteProvider.fromConfiguration("mysql", options.performanceQueryRewriteProvider,
                options.performanceWetuneStaticWorker, options.performanceWetuneStaticTimeoutMs,
                MySQLWeTuneSchemaDdlRenderer::render);
    }
}
