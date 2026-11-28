package sqlancer.oceanbase.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.IndexSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.TableSummary;
import sqlancer.common.perfhint.RewrittenQuery;
import sqlancer.common.perfhint.WeTuneStaticProtocol;
import sqlancer.common.perfhint.WeTuneStaticRewriteProvider;
import sqlancer.oceanbase.OceanBaseOptions;

public class TestOceanBaseWeTuneStaticRewriteProvider {

    @Test
    public void requiresAWorkerWhenWetuneIsSelected() {
        OceanBaseOptions options = new OceanBaseOptions();
        options.performanceQueryRewriteProvider = "wetune-static";
        assertThrows(IllegalArgumentException.class, () -> OceanBaseWeTuneStaticRewriteProvider.fromOptions(options));
    }

    @Test
    public void rendersMySqlCompatibleDdlAndPinsTheProtocolToOceanBase() {
        QueryRewriteSchemaSummary schema = new QueryRewriteSchemaSummary(List.of(new TableSummary("t",
                List.of(new ColumnSummary("id", "INT", "INT", false, true),
                        new ColumnSummary("name", "VARCHAR", "VARCHAR(32)", true, false)),
                List.of(new IndexSummary("PRIMARY", List.of("id"), true, true),
                        new IndexSummary("idx_name", List.of("name"), false, false)))));

        String ddl = OceanBaseWeTuneSchemaDdlRenderer.render(schema);
        assertTrue(ddl.contains("`id` INT NOT NULL"));
        assertTrue(ddl.contains("PRIMARY KEY (`id`)"));
        assertTrue(ddl.contains("KEY `idx_name` (`name`)"));
        assertTrue(WeTuneStaticProtocol.request("oceanbase", 4, "SELECT 1", ddl, 16, 100)
                .contains("\"dbms\":\"oceanbase\""));
    }

    @Test
    public void reusesAReadyWorkerAndChecksAllReturnedRewrites() throws Exception {
        Path worker = Files.createTempFile("sqlancer-wetune-oceanbase", ".sh");
        String rewriteSql = Base64.getEncoder().encodeToString("SELECT 2".getBytes(StandardCharsets.UTF_8));
        String script = "#!/bin/sh\n" + "printf '%s\\n' '" + readyResponse() + "'\n" + "while IFS= read -r line; do\n"
                + "  request_id=$(printf '%s' \"$line\" | sed -n 's/.*\\\"requestId\\\":\\([0-9][0-9]*\\).*/\\1/p')\n"
                + "  printf '{\"protocolVersion\":1,\"requestId\":%s,\"status\":\"ok\",\"upstreamCommit\":\""
                + WeTuneStaticProtocol.UPSTREAM_COMMIT + "\",\"rulesSha256\":\"" + WeTuneStaticProtocol.RULES_SHA256
                + "\",\"elapsedMs\":1,\"rewrites\":[{\"sqlBase64\":\"" + rewriteSql
                + "\",\"ruleTrace\":[\"7\"],\"descriptionBase64\":\"\"}]}\\n' \"$request_id\"\n" + "done\n";
        Files.writeString(worker, script, StandardCharsets.UTF_8);
        assertTrue(worker.toFile().setExecutable(true));

        OceanBaseOptions options = new OceanBaseOptions();
        options.performanceQueryRewriteProvider = "wetune-static";
        options.performanceWetuneStaticWorker = worker.toString();
        options.performanceWetuneStaticTimeoutMs = 100;
        PerformanceQueryRewriteProvider provider = OceanBaseWeTuneStaticRewriteProvider.fromOptions(options);
        QueryRewriteRequest request = new QueryRewriteRequest("SELECT 1", "oceanbase", 16,
                new QueryRewriteSchemaSummary(
                        List.of(new TableSummary("t", List.of(new ColumnSummary("id", "INT")), List.of()))));
        try {
            List<RewrittenQuery> rewrites = provider.generateRewrites(request);
            assertEquals(1, rewrites.size(), ((WeTuneStaticRewriteProvider) provider).getLastDiagnostic());
            assertEquals("SELECT 2", rewrites.get(0).getRewrittenSql());
            assertTrue(((WeTuneStaticRewriteProvider) provider).getLastDiagnostic().contains("candidates=1"));
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
