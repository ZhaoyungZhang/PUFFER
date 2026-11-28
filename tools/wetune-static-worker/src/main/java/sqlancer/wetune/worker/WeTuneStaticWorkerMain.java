package sqlancer.wetune.worker;

import static wtune.sql.plan.PlanSupport.assemblePlan;
import static wtune.sql.plan.PlanSupport.translateAsAst;
import static wtune.sql.support.action.NormalizationSupport.normalizeAst;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import wtune.common.datasource.DbSupport;
import wtune.sql.SqlSupport;
import wtune.sql.ast.SqlNode;
import wtune.sql.plan.PlanContext;
import wtune.sql.plan.PlanSupport;
import wtune.sql.schema.Schema;
import wtune.sql.schema.SchemaSupport;
import wtune.superopt.optimizer.OptimizationStep;
import wtune.superopt.optimizer.Optimizer;
import wtune.superopt.substitution.SubstitutionBank;
import wtune.superopt.substitution.SubstitutionSupport;

/**
 * Java 17 JSONL worker for SQLancer's optional WeTune static rewriter. It
 * never discovers rules, proves equivalence, profiles a query, or opens a DB
 * connection.
 */
public final class WeTuneStaticWorkerMain {

    static final int PROTOCOL_VERSION = 1;
    static final int MAX_REWRITES = 16;
    static final int DEFAULT_MAX_REWRITE_STEPS = 1;
    static final int RULE_COUNT = 650;
    static final String UPSTREAM_COMMIT = "3e36def4ae6a8a1630eeb83e6fde2f727715297e";
    static final String RULES_SHA256 = "9f69a8f32afc70812df5e118f0bc6bb299e502984f1eda6969e7f9aac3bdd4fb";

    private final SubstitutionBank rules;

    private WeTuneStaticWorkerMain(SubstitutionBank rules) {
        this.rules = rules;
    }

    public static void main(String[] args) throws Exception {
        SqlSupport.muteParsingError();
        WeTuneStaticWorkerMain worker = new WeTuneStaticWorkerMain(loadRules());
        worker.run();
    }

    private void run() throws IOException {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
            output.println(Response.ready().toJson());
            String line;
            while ((line = input.readLine()) != null) {
                Request request = Request.parse(line);
                if (request == null) {
                    output.println(Response.error(-1, "invalid_request").toJson());
                    continue;
                }
                try {
                    output.println(rewrite(request).toJson());
                } catch (Throwable exception) {
                    output.println(Response.error(request.requestId, errorCode(exception)).toJson());
                }
            }
        }
    }

    private Response rewrite(Request request) {
        long startedAt = System.nanoTime();
        String dialect = "cockroachdb".equals(request.dbms) ? DbSupport.PostgreSQL : DbSupport.MySQL;
        Schema schema = SchemaSupport.parseSchema(dialect, request.schemaDdl);
        SqlNode ast = SqlSupport.parseSql(dialect, request.baseSql);
        if (ast == null || schema == null || !PlanSupport.isSupported(ast)) {
            return Response.error(request.requestId, "parse_or_schema");
        }
        ast.context().setSchema(schema);
        normalizeAst(ast);
        PlanContext plan = assemblePlan(ast, schema);
        if (plan == null) {
            return Response.error(request.requestId, "plan_assembly");
        }

        Optimizer optimizer = Optimizer.mk(rules);
        optimizer.setTimeout(request.timeoutMs);
        optimizer.setTracing(true);
        Set<PlanContext> optimized = optimizer.optimize(plan);
        int maxRewriteSteps = configuredMaxRewriteSteps();
        List<Rewrite> rewrites = new ArrayList<>();
        for (PlanContext optimizedPlan : optimized) {
            List<OptimizationStep> steps = optimizer.traceOf(optimizedPlan);
            if (steps.isEmpty() || steps.size() > maxRewriteSteps) {
                continue;
            }
            SqlNode rewrittenAst = translateAsAst(optimizedPlan, optimizedPlan.root(), false);
            if (rewrittenAst == null) {
                continue;
            }
            List<String> trace = new ArrayList<>();
            for (OptimizationStep step : steps) {
                if (step.ruleId() <= 0) {
                    trace.clear();
                    break;
                }
                trace.add(String.valueOf(step.ruleId()));
            }
            String rewrittenSql = rewrittenAst.toString(false);
            if (!trace.isEmpty() && !rewrittenSql.trim().isEmpty() && !rewrittenSql.equals(request.baseSql)) {
                rewrites.add(new Rewrite(rewrittenSql, trace));
            }
        }
        rewrites.sort(Comparator.comparing((Rewrite rewrite) -> rewrite.sql).thenComparing(Rewrite::traceString));
        List<Rewrite> distinct = new ArrayList<>();
        String previousSql = null;
        for (Rewrite rewrite : rewrites) {
            if (!rewrite.sql.equals(previousSql)) {
                distinct.add(rewrite);
                previousSql = rewrite.sql;
            }
            if (distinct.size() >= Math.min(MAX_REWRITES, request.candidateBudget)) {
                break;
            }
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        return Response.ok(request.requestId, distinct, elapsedMs);
    }

    /** Exposes the CockroachDB PostgreSQL parse boundary to the worker's tests. */
    static boolean supportsCockroachPostgreSql(String schemaDdl, String sql) {
        Schema schema = SchemaSupport.parseSchema(DbSupport.PostgreSQL, schemaDdl);
        SqlNode ast = SqlSupport.parseSql(DbSupport.PostgreSQL, sql);
        return schema != null && ast != null && PlanSupport.isSupported(ast);
    }

    private static int configuredMaxRewriteSteps() {
        String raw = System.getProperty("wetune.maxRewriteSteps",
                System.getenv().getOrDefault("WETUNE_MAX_REWRITE_STEPS", ""));
        if (raw != null && !raw.isBlank()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed >= 1) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to single-step default
            }
        }
        return DEFAULT_MAX_REWRITE_STEPS;
    }

    static SubstitutionBank loadRules() throws IOException {
        byte[] bytes;
        try (InputStream input = WeTuneStaticWorkerMain.class.getResourceAsStream("/rules.txt")) {
            if (input == null) {
                throw new IOException("rules_resource_missing");
            }
            bytes = input.readAllBytes();
        }
        if (!RULES_SHA256.equals(sha256(bytes))) {
            throw new IOException("rules_sha256_mismatch");
        }
        List<String> lines = List.of(new String(bytes, StandardCharsets.UTF_8).split("\\R"));
        SubstitutionBank bank = SubstitutionSupport.loadBank(lines);
        if (bank.size() != RULE_COUNT) {
            throw new IOException("rules_count_mismatch:" + bank.size());
        }
        return bank;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("sha256_unavailable", exception);
        }
    }

    private static String errorCode(Throwable exception) {
        String name = exception.getClass().getSimpleName();
        return name == null || name.isEmpty() ? "worker_error" : name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static final class Request {
        private static final Pattern NUMBER_FIELD = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*(-?[0-9]+)");
        private static final Pattern STRING_FIELD = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");

        private final long requestId;
        private final String dbms;
        private final String baseSql;
        private final String schemaDdl;
        private final int candidateBudget;
        private final long timeoutMs;

        private Request(long requestId, String dbms, String baseSql, String schemaDdl, int candidateBudget,
                long timeoutMs) {
            this.requestId = requestId;
            this.dbms = dbms;
            this.baseSql = baseSql;
            this.schemaDdl = schemaDdl;
            this.candidateBudget = candidateBudget;
            this.timeoutMs = timeoutMs;
        }

        private static Request parse(String json) {
            String dbms = string(json, "dbms");
            if (number(json, "protocolVersion", -1) != PROTOCOL_VERSION
                    || !("mysql".equals(dbms) || "mariadb".equals(dbms) || "tidb".equals(dbms)
                            || "oceanbase".equals(dbms) || "cockroachdb".equals(dbms))) {
                return null;
            }
            long requestId = number(json, "requestId", -1);
            int candidateBudget = (int) number(json, "candidateBudget", -1);
            long timeoutMs = number(json, "timeoutMs", -1);
            String baseSql = decode(string(json, "baseSqlBase64"));
            String schemaDdl = decode(string(json, "schemaDdlBase64"));
            if (requestId < 0 || candidateBudget <= 0 || timeoutMs <= 0 || baseSql.isEmpty() || schemaDdl.isEmpty()) {
                return null;
            }
            return new Request(requestId, dbms, baseSql, schemaDdl, Math.min(MAX_REWRITES, candidateBudget), timeoutMs);
        }

        private static long number(String json, String name, long fallback) {
            Matcher matcher = NUMBER_FIELD.matcher(json);
            while (matcher.find()) {
                if (name.equals(matcher.group(1))) {
                    try {
                        return Long.parseLong(matcher.group(2));
                    } catch (NumberFormatException ignored) {
                        return fallback;
                    }
                }
            }
            return fallback;
        }

        private static String string(String json, String name) {
            Matcher matcher = STRING_FIELD.matcher(json);
            while (matcher.find()) {
                if (name.equals(matcher.group(1))) {
                    return matcher.group(2);
                }
            }
            return "";
        }
    }

    private static final class Rewrite {
        private final String sql;
        private final List<String> trace;

        private Rewrite(String sql, List<String> trace) {
            this.sql = sql;
            this.trace = List.copyOf(trace);
        }

        private String traceString() {
            return String.join(",", trace);
        }
    }

    private static final class Response {
        private final long requestId;
        private final List<Rewrite> rewrites;
        private final long elapsedMs;
        private final String error;
        private final boolean ready;

        private Response(long requestId, List<Rewrite> rewrites, long elapsedMs, String error, boolean ready) {
            this.requestId = requestId;
            this.rewrites = rewrites;
            this.elapsedMs = elapsedMs;
            this.error = error;
            this.ready = ready;
        }

        private static Response ok(long requestId, List<Rewrite> rewrites, long elapsedMs) {
            return new Response(requestId, List.copyOf(rewrites), elapsedMs, "", false);
        }

        private static Response error(long requestId, String error) {
            return new Response(requestId, List.of(), -1, error, false);
        }

        private static Response ready() {
            return new Response(-1, List.of(), -1, "", true);
        }

        private String toJson() {
            if (ready) {
                return "{\"protocolVersion\":" + PROTOCOL_VERSION + ",\"status\":\"ready\",\"upstreamCommit\":\""
                        + UPSTREAM_COMMIT + "\",\"rulesSha256\":\"" + RULES_SHA256 + "\"}";
            }
            StringBuilder json = new StringBuilder("{\"protocolVersion\":").append(PROTOCOL_VERSION)
                    .append(",\"requestId\":").append(requestId).append(",\"status\":\"")
                    .append(error.isEmpty() ? "ok" : "error").append("\",\"upstreamCommit\":\"")
                    .append(UPSTREAM_COMMIT).append("\",\"rulesSha256\":\"").append(RULES_SHA256).append("\"");
            if (error.isEmpty()) {
                json.append(",\"elapsedMs\":").append(elapsedMs).append(",\"rewrites\":[");
                for (int index = 0; index < rewrites.size(); index++) {
                    if (index > 0) {
                        json.append(',');
                    }
                    Rewrite rewrite = rewrites.get(index);
                    json.append("{\"sqlBase64\":\"").append(encode(rewrite.sql))
                            .append("\",\"ruleTrace\":[");
                    for (int traceIndex = 0; traceIndex < rewrite.trace.size(); traceIndex++) {
                        if (traceIndex > 0) {
                            json.append(',');
                        }
                        json.append('\"').append(rewrite.trace.get(traceIndex)).append('\"');
                    }
                    json.append("],\"descriptionBase64\":\"")
                            .append(encode("WeTune static rule trace: " + rewrite.traceString())).append("\"}");
                }
                return json.append("]}").toString();
            }
            return json.append(",\"error\":\"").append(error).append("\",\"rewrites\":[]}").toString();
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }
}
