package sqlancer.common.perfhint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import sqlancer.common.perfhint.PerformanceQueryGenerationMode.Family;

/** Immutable query provenance, captured before hints, feedback, and witness persistence. */
public final class PerformanceQueryMetadata {
    private final PerformanceQueryGenerationMode mode;
    private final Family family;
    private final Set<String> features;
    private final String structuralKey;

    private PerformanceQueryMetadata(Builder builder) {
        mode = builder.mode;
        family = builder.family;
        features = Collections.unmodifiableSet(new TreeSet<>(builder.features));
        structuralKey = "qg2|" + family + "|" + digest(String.join(";", builder.structure) + features.toString());
    }

    public static PerformanceQueryMetadata structured(PerformanceQueryGenerationMode mode, String sql) {
        Builder builder = new Builder(mode, Family.STRUCTURED);
        String normalized = PerformanceSqlLexical.maskLiterals(sql).toUpperCase(Locale.ROOT)
                .replaceAll("\\b[0-9]+(?:\\.[0-9]+)?\\b", "?").replaceAll("\\s+", " ").trim();
        builder.node(normalized);
        for (String clause : List.of("DISTINCT", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "LIMIT")) {
            if (normalized.contains(clause)) {
                builder.feature(clause.replace(' ', '_'));
            }
        }
        for (String type : List.of("INNER", "LEFT", "RIGHT", "FULL", "CROSS")) {
            if (normalized.contains(type + " JOIN")) {
                builder.feature("JOIN_" + type);
            }
        }
        if (normalized.contains("STRAIGHT_JOIN")) {
            builder.feature("JOIN_STRAIGHT");
        }
        return builder.build();
    }

    public Family getFamily() {
        return family;
    }

    public Set<String> getFeatures() {
        return features;
    }

    public String getStructuralKey() {
        return structuralKey;
    }

    public void record(PerformanceHintTelemetry telemetry, String stage) {
        telemetry.count("query_generation." + family.name().toLowerCase(Locale.ROOT) + "." + stage);
        if (stage.equals("generated")) {
            for (String feature : features) {
                telemetry.count("query_features." + feature.toLowerCase(Locale.ROOT));
            }
        }
    }

    public String toJson() {
        List<String> values = new ArrayList<>();
        for (String feature : features) {
            values.add("\"" + feature + "\"");
        }
        return "{\"version\":2,\"mode\":\"" + mode.getId() + "\",\"family\":\"" + family.name().toLowerCase(Locale.ROOT)
                + "\",\"structuralKey\":\"" + structuralKey + "\",\"features\":[" + String.join(",", values) + "]}";
    }

    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            String digits = "0123456789abcdef";
            for (int i = 0; i < 12; i++) {
                out.append(digits.charAt((bytes[i] & 0xff) >>> 4)).append(digits.charAt(bytes[i] & 15));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static final class Builder {
        private final PerformanceQueryGenerationMode mode;
        private final Family family;
        private final Set<String> features = new TreeSet<>();
        private final List<String> structure = new ArrayList<>();

        public Builder(PerformanceQueryGenerationMode mode, Family family) {
            this.mode = mode;
            this.family = family;
        }

        public void feature(String feature) {
            features.add(feature.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_"));
        }

        public void node(String node) {
            structure.add(node);
        }

        public PerformanceQueryMetadata build() {
            return new PerformanceQueryMetadata(this);
        }
    }
}
