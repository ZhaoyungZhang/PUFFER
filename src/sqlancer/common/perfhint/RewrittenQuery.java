package sqlancer.common.perfhint;

/**
 * A query produced by a rewrite rule before it is admitted to the performance testing pipeline.
 */
public final class RewrittenQuery {

    private final String rewrittenSql;
    private final String rewriteFamily;
    private final String ruleId;
    private final String description;

    public RewrittenQuery(String rewrittenSql, String rewriteFamily, String ruleId, String description) {
        this.rewrittenSql = rewrittenSql == null ? "" : rewrittenSql;
        this.rewriteFamily = rewriteFamily == null ? "" : rewriteFamily;
        this.ruleId = ruleId == null ? "" : ruleId;
        this.description = description == null ? "" : description;
    }

    public String getRewrittenSql() {
        return rewrittenSql;
    }

    public String getRewriteFamily() {
        return rewriteFamily;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getDescription() {
        return description;
    }
}
