package sqlancer.tidb.oracle;

import sqlancer.common.perfhint.PerformanceQueryRewriteProvider;
import sqlancer.common.perfhint.WeTuneStaticRewriteProvider;
import sqlancer.tidb.TiDBOptions;

/** TiDB option and DDL binding for the shared WeTune provider. */
final class TiDBWeTuneStaticRewriteProvider {

    private TiDBWeTuneStaticRewriteProvider() {
    }

    static PerformanceQueryRewriteProvider fromOptions(TiDBOptions options) {
        return WeTuneStaticRewriteProvider.fromConfiguration("tidb", options.performanceQueryRewriteProvider,
                options.performanceWetuneStaticWorker, options.performanceWetuneStaticTimeoutMs,
                TiDBWeTuneSchemaDdlRenderer::render);
    }
}
