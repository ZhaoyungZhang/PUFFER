package sqlancer.cockroachdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.common.perfhint.PerformanceQueryRewriteProvider;
import sqlancer.common.perfhint.QueryRewriteRequest;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.ColumnSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.ForeignKeySummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.IndexSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.TableSummary;
import sqlancer.common.perfhint.RewriteEquivalenceOracle;
import sqlancer.common.perfhint.RewrittenQuery;
import sqlancer.common.perfhint.WeTuneStaticProtocol;
import sqlancer.common.perfhint.WeTuneStaticRewriteEquivalenceOracle;
import sqlancer.common.perfhint.WeTuneStaticRewriteProvider;
import sqlancer.cockroachdb.CockroachDBOptions;

public class TestCockroachDBWeTuneStaticRewriteProvider {

    @Test
    public void testWetuneProviderRequiresWorkerPath() {
        CockroachDBOptions options = new CockroachDBOptions();
        options.performanceQueryRewriteProvider = "wetune-static";
        assertThrows(IllegalArgumentException.class, () -> CockroachDBWeTuneStaticRewriteProvider.fromOptions(options));
        options.performanceWetuneStaticWorker = "tools/wetune-static-worker";
        assertThrows(IllegalArgumentException.class, () -> CockroachDBWeTuneStaticRewriteProvider.fromOptions(options));
    }

    @Test
    public void testCanonicalDdlContainsSemanticConstraints() {
        QueryRewriteSchemaSummary schema = new QueryRewriteSchemaSummary(List.of(new TableSummary("child",
                List.of(new ColumnSummary("id", "INT", "INT", false, true),
                        new ColumnSummary("parent_id", "INT", "INT", false, false),
                        new ColumnSummary("name", "VARCHAR", "VARCHAR(32)", true, false)),
                List.of(new IndexSummary("PRIMARY", List.of("id"), true, true),
                        new IndexSummary("uq_parent_name", List.of("parent_id", "name"), true, false),
                        new IndexSummary("idx_name", List.of("name"), false, false)),
                List.of(new ForeignKeySummary("fk_child_parent", List.of("parent_id"), "parent", List.of("id"))))));

        String ddl = CockroachDBWeTuneSchemaDdlRenderer.render(schema);

        assertTrue(ddl.contains("\"id\" INTEGER NOT NULL"));
        assertTrue(ddl.contains("\"name\" TEXT"));
        assertTrue(ddl.contains("PRIMARY KEY (\"id\")"));
        assertTrue(ddl.contains("CONSTRAINT \"uq_parent_name\" UNIQUE (\"parent_id\", \"name\")"));
        assertTrue(ddl.contains("CREATE INDEX \"idx_name\" ON \"child\" (\"name\")"));
        assertTrue(ddl
                .contains("CONSTRAINT \"fk_child_parent\" FOREIGN KEY (\"parent_id\") REFERENCES \"parent\" (\"id\")"));
    }

    @Test
    public void testProtocolAndPinnedEquivalenceOracle() {
        assertTrue(WeTuneStaticProtocol.request("cockroachdb", 4, "SELECT 1", "CREATE TABLE t (id INT)", 16, 100)
                .contains("\"dbms\":\"cockroachdb\""));
        String response = "{\"protocolVersion\":1,\"requestId\":4,\"status\":\"ok\",\"upstreamCommit\":\""
                + WeTuneStaticProtocol.UPSTREAM_COMMIT + "\",\"rulesSha256\":\"" + WeTuneStaticProtocol.RULES_SHA256
                + "\",\"elapsedMs\":7,\"rewrites\":[{\"sqlBase64\":\""
                + Base64.getEncoder().encodeToString("SELECT 2".getBytes(StandardCharsets.UTF_8))
                + "\",\"ruleTrace\":[\"12\",\"34\"],\"descriptionBase64\":\"\"}]}";
        WeTuneStaticProtocol.Response parsed = WeTuneStaticProtocol.parseResponse(response, 4);

        assertTrue(parsed.isOk());
        assertEquals(List.of("12", "34"), parsed.getRewrites().get(0).getRuleTrace());

        RewrittenQuery accepted = new RewrittenQuery("SELECT 2", WeTuneStaticProtocol.FAMILY, "wetune-static:"
                + WeTuneStaticProtocol.UPSTREAM_COMMIT + ":" + WeTuneStaticProtocol.RULES_SHA256 + ":12,34", "rules");
        RewriteEquivalenceOracle oracle = new WeTuneStaticRewriteEquivalenceOracle("cockroachdb");
        QueryRewriteRequest request = new QueryRewriteRequest("SELECT 1", "cockroachdb", 16,
                QueryRewriteSchemaSummary.empty());
        assertEquals(RewriteEquivalenceOracle.Verdict.ACCEPT, oracle.verify(request, accepted));
        assertEquals(RewriteEquivalenceOracle.Verdict.REJECT,
                oracle.verify(new QueryRewriteRequest("SELECT 1", "cockroachdb", 16, QueryRewriteSchemaSummary.empty()),
                        new RewrittenQuery("SELECT 2", WeTuneStaticProtocol.FAMILY, "wetune-static:bad", "rules")));
    }

    @Test
    public void testProviderReusesAReadyWorker() throws Exception {
        Path worker = Files.createTempFile("sqlancer-wetune-static", ".sh");
        String sql = Base64.getEncoder().encodeToString("SELECT 2".getBytes(StandardCharsets.UTF_8));
        String script = "#!/bin/sh\n" + "printf '%s\\n' '" + readyResponse() + "'\n" + "while IFS= read -r line; do\n"
                + "  request_id=$(printf '%s' \"$line\" | sed -n 's/.*\\\"requestId\\\":\\([0-9][0-9]*\\).*/\\1/p')\n"
                + "  printf '{\"protocolVersion\":1,\"requestId\":%s,\"status\":\"ok\",\"upstreamCommit\":\""
                + WeTuneStaticProtocol.UPSTREAM_COMMIT + "\",\"rulesSha256\":\"" + WeTuneStaticProtocol.RULES_SHA256
                + "\",\"elapsedMs\":1,\"rewrites\":[{\"sqlBase64\":\"" + sql
                + "\",\"ruleTrace\":[\"7\"],\"descriptionBase64\":\"\"}]}\\n' \"$request_id\"\n" + "done\n";
        Files.writeString(worker, script, StandardCharsets.UTF_8);
        assertTrue(worker.toFile().setExecutable(true));

        CockroachDBOptions options = new CockroachDBOptions();
        options.performanceQueryRewriteProvider = "wetune-static";
        options.performanceWetuneStaticWorker = worker.toString();
        options.performanceWetuneStaticTimeoutMs = 100;
        PerformanceQueryRewriteProvider provider = CockroachDBWeTuneStaticRewriteProvider.fromOptions(options);
        PerformanceQueryRewriteProvider sameCampaignWorker = CockroachDBWeTuneStaticRewriteProvider
                .fromOptions(options);
        QueryRewriteRequest request = new QueryRewriteRequest("SELECT 1", "cockroachdb", 16,
                new QueryRewriteSchemaSummary(
                        List.of(new TableSummary("t", List.of(new ColumnSummary("id", "INT")), List.of()))));
        try {
            assertTrue(provider == sameCampaignWorker);
            List<RewrittenQuery> first = provider.generateRewrites(request);
            assertEquals(1, first.size(), ((WeTuneStaticRewriteProvider) provider).getLastDiagnostic());
            assertEquals("SELECT 2", provider.generateRewrites(request).get(0).getRewrittenSql());
            assertFalse(((WeTuneStaticRewriteProvider) provider).getLastDiagnostic().contains("error="));
        } finally {
            ((WeTuneStaticRewriteProvider) provider).close();
            Files.deleteIfExists(worker);
        }
    }

    private static String readyResponse() {
        return "{\"protocolVersion\":1,\"status\":\"ready\",\"upstreamCommit\":\""
                + WeTuneStaticProtocol.UPSTREAM_COMMIT + "\",\"rulesSha256\":\"" + WeTuneStaticProtocol.RULES_SHA256
                + "\"}";
    }
}
