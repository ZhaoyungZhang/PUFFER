package sqlancer.common.perfhint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Restricted JSONL protocol used by the isolated Java 17 WeTune worker. */
public final class WeTuneStaticProtocol {

    public static final int VERSION = 1;
    public static final String UPSTREAM_COMMIT = "3e36def4ae6a8a1630eeb83e6fde2f727715297e";
    public static final String RULES_SHA256 = "9f69a8f32afc70812df5e118f0bc6bb299e502984f1eda6969e7f9aac3bdd4fb";
    public static final String FAMILY = "WETUNE_STATIC";

    private static final Pattern OBJECT_PATTERN = Pattern.compile("\\{([^{}]*)\\}");

    private WeTuneStaticProtocol() {
    }

    public static String request(String dbmsName, long requestId, String baseSql, String schemaDdl, int candidateBudget,
            int timeoutMs) {
        return "{\"protocolVersion\":" + VERSION + ",\"requestId\":" + requestId + ",\"dbms\":\"" + dbmsName
                + "\",\"baseSqlBase64\":\"" + encode(baseSql) + "\",\"schemaDdlBase64\":\"" + encode(schemaDdl)
                + "\",\"candidateBudget\":" + candidateBudget + ",\"timeoutMs\":" + timeoutMs + "}";
    }

    public static Response parseResponse(String line, long expectedRequestId) {
        if (line == null || line.trim().isEmpty()) {
            return Response.error("empty_response");
        }
        if (integerField(line, "protocolVersion", -1) != VERSION) {
            return Response.error("protocol_version");
        }
        if (integerField(line, "requestId", -1) != expectedRequestId) {
            return Response.error("request_id");
        }
        if (!"ok".equals(stringField(line, "status"))) {
            return Response.error(emptyToDefault(stringField(line, "error"), "worker_error"));
        }
        if (!UPSTREAM_COMMIT.equals(stringField(line, "upstreamCommit"))
                || !RULES_SHA256.equals(stringField(line, "rulesSha256"))) {
            return Response.error("manifest_mismatch");
        }

        List<Rewrite> rewrites = new ArrayList<>();
        Matcher matcher = OBJECT_PATTERN.matcher(arrayField(line, "rewrites"));
        while (matcher.find()) {
            String rewrite = matcher.group(1);
            String sql = decode(stringField(rewrite, "sqlBase64"));
            String description = decode(stringField(rewrite, "descriptionBase64"));
            List<String> trace = stringArrayField(rewrite, "ruleTrace");
            if (!sql.isEmpty() && !trace.isEmpty()) {
                rewrites.add(new Rewrite(sql, trace, description));
            }
        }
        return Response.ok(rewrites, integerField(line, "elapsedMs", -1));
    }

    public static boolean isReady(String line) {
        return line != null && integerField(line, "protocolVersion", -1) == VERSION
                && "ready".equals(stringField(line, "status"))
                && UPSTREAM_COMMIT.equals(stringField(line, "upstreamCommit"))
                && RULES_SHA256.equals(stringField(line, "rulesSha256"));
    }

    public static String encode(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String stringField(String json, String name) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int integerField(String json, String name, int fallback) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\"\\s*:\\s*(-?[0-9]+)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String arrayField(String json, String name) {
        String marker = "\"" + name + "\"";
        int markerIndex = json.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        int opening = json.indexOf('[', markerIndex + marker.length());
        if (opening < 0) {
            return "";
        }
        int depth = 0;
        for (int index = opening; index < json.length(); index++) {
            char character = json.charAt(index);
            if (character == '[') {
                depth++;
            } else if (character == ']' && --depth == 0) {
                return json.substring(opening + 1, index);
            }
        }
        return "";
    }

    private static List<String> stringArrayField(String json, String name) {
        String payload = arrayField(json, name).trim();
        if (payload.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String token : payload.split(",")) {
            String value = token.trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty() && value.matches("[0-9]+")) {
                values.add(value);
            } else {
                return List.of();
            }
        }
        return List.copyOf(values);
    }

    private static String emptyToDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    public static final class Rewrite {
        private final String sql;
        private final List<String> ruleTrace;
        private final String description;

        private Rewrite(String sql, List<String> ruleTrace, String description) {
            this.sql = sql;
            this.ruleTrace = List.copyOf(ruleTrace);
            this.description = description;
        }

        public String getSql() {
            return sql;
        }

        public List<String> getRuleTrace() {
            return ruleTrace;
        }

        public String getDescription() {
            return description;
        }
    }

    public static final class Response {
        private final List<Rewrite> rewrites;
        private final long elapsedMs;
        private final String error;

        private Response(List<Rewrite> rewrites, long elapsedMs, String error) {
            this.rewrites = rewrites;
            this.elapsedMs = elapsedMs;
            this.error = error;
        }

        private static Response ok(List<Rewrite> rewrites, long elapsedMs) {
            return new Response(List.copyOf(rewrites), elapsedMs, "");
        }

        private static Response error(String error) {
            return new Response(List.of(), -1, error);
        }

        public boolean isOk() {
            return error.isEmpty();
        }

        public List<Rewrite> getRewrites() {
            return rewrites;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public String getError() {
            return error;
        }
    }
}
