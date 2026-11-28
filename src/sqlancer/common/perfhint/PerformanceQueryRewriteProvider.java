package sqlancer.common.perfhint;

import java.util.List;

/**
 * Supplies DBMS-aware semantic query rewrite candidates for performance testing.
 */
@FunctionalInterface
public interface PerformanceQueryRewriteProvider {

    List<RewrittenQuery> generateRewrites(QueryRewriteRequest request);

    static PerformanceQueryRewriteProvider noOp() {
        return request -> List.of();
    }
}
