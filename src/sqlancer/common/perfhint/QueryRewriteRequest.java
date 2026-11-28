package sqlancer.common.perfhint;

import java.util.Objects;

/**
 * Input made available to a DBMS-specific query rewrite provider.
 */
public final class QueryRewriteRequest {

    private final String baseSql;
    private final String dbmsName;
    private final int candidateBudget;
    private final QueryRewriteSchemaSummary schemaSummary;

    public QueryRewriteRequest(String baseSql, String dbmsName, int candidateBudget,
            QueryRewriteSchemaSummary schemaSummary) {
        this.baseSql = baseSql == null ? "" : baseSql;
        this.dbmsName = dbmsName == null ? "" : dbmsName;
        this.candidateBudget = candidateBudget;
        this.schemaSummary = Objects.requireNonNullElseGet(schemaSummary, QueryRewriteSchemaSummary::empty);
    }

    public String getBaseSql() {
        return baseSql;
    }

    public String getDbmsName() {
        return dbmsName;
    }

    public int getCandidateBudget() {
        return candidateBudget;
    }

    public QueryRewriteSchemaSummary getSchemaSummary() {
        return schemaSummary;
    }
}
