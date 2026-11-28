package sqlancer.oceanbase.oracle;

import sqlancer.common.perfhint.PerformanceQueryRewriteProvider;
import sqlancer.common.perfhint.WeTuneStaticRewriteProvider;
import sqlancer.oceanbase.OceanBaseOptions;

/** OceanBase option and DDL binding for the shared WeTune provider. */
final class OceanBaseWeTuneStaticRewriteProvider {

    private OceanBaseWeTuneStaticRewriteProvider() {
    }

    static PerformanceQueryRewriteProvider fromOptions(OceanBaseOptions options) {
        return WeTuneStaticRewriteProvider.fromConfiguration("oceanbase", options.performanceQueryRewriteProvider,
                options.performanceWetuneStaticWorker, options.performanceWetuneStaticTimeoutMs,
                OceanBaseWeTuneSchemaDdlRenderer::render);
    }
}
