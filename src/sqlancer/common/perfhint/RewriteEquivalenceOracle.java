package sqlancer.common.perfhint;

/**
 * Validates whether a generated query rewrite is safe to compare with the base query. An unavailable oracle
 * intentionally does not admit rewrites.
 */
@FunctionalInterface
public interface RewriteEquivalenceOracle {

    enum Verdict {
        ACCEPT, REJECT, UNAVAILABLE
    }

    Verdict verify(QueryRewriteRequest request, RewrittenQuery rewrittenQuery);

    static RewriteEquivalenceOracle unavailable() {
        return (request, rewrittenQuery) -> Verdict.UNAVAILABLE;
    }
}
