package sqlancer.common.perfhint;

import java.util.Locale;
import java.util.regex.Pattern;

/** Admits only pinned WeTune static-rule responses for one configured DBMS. */
public final class WeTuneStaticRewriteEquivalenceOracle implements RewriteEquivalenceOracle {

    private static final String RULE_ID_PREFIX = "wetune-static:" + WeTuneStaticProtocol.UPSTREAM_COMMIT + ":"
            + WeTuneStaticProtocol.RULES_SHA256 + ":";
    private static final Pattern RULE_TRACE = Pattern.compile("[0-9]+(?:,[0-9]+)*");

    private final String dbmsName;

    public WeTuneStaticRewriteEquivalenceOracle(String dbmsName) {
        this.dbmsName = dbmsName.toLowerCase(Locale.ROOT);
    }

    @Override
    public Verdict verify(QueryRewriteRequest request, RewrittenQuery rewrittenQuery) {
        if (!dbmsName.equals(request.getDbmsName().toLowerCase(Locale.ROOT))
                || !WeTuneStaticProtocol.FAMILY.equals(rewrittenQuery.getRewriteFamily())
                || !rewrittenQuery.getRuleId().startsWith(RULE_ID_PREFIX)) {
            return Verdict.REJECT;
        }
        String trace = rewrittenQuery.getRuleId().substring(RULE_ID_PREFIX.length());
        return RULE_TRACE.matcher(trace).matches() ? Verdict.ACCEPT : Verdict.REJECT;
    }
}
