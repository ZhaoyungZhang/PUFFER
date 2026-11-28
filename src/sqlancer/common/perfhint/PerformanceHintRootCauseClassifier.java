package sqlancer.common.perfhint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class PerformanceHintRootCauseClassifier {

    private PerformanceHintRootCauseClassifier() {
    }

    public interface PlanShape {
        Set<String> getKeys();

        Set<String> getAccessTypes();

        Set<String> getOperations();

        boolean isUsingTemporaryTable();

        boolean isUsingDeduplication();

        boolean isUsingFilesort();

        Double getRowsExaminedPerScan();

        Double getRowsProducedPerJoin();
    }

    public static final class RootCauseClassification {
        private final String intentCategory;
        private final String mechanismKey;
        private final String signatureKey;

        public RootCauseClassification(String intentCategory, String mechanismKey, String signatureKey) {
            this.intentCategory = intentCategory;
            this.mechanismKey = mechanismKey;
            this.signatureKey = signatureKey;
        }

        public String getIntentCategory() {
            return intentCategory;
        }

        public String getMechanismKey() {
            return mechanismKey;
        }

        public String getSignatureKey() {
            return signatureKey;
        }
    }

    public static RootCauseClassification classify(String baseQuery, String hintFamily, String hintType,
            PlanShape defaultPlan, PlanShape hintedPlan) {
        String intent = detectIntentCategory(baseQuery);
        String mechanism = buildMechanismKey(baseQuery, hintFamily, hintType, defaultPlan, hintedPlan);
        return new RootCauseClassification(intent, mechanism, intent + "|" + mechanism);
    }

    /**
     * Generic counterpart of the legacy HINT-only classifier. The mutation identity is part of the signature so a
     * rewrite and a hint that yield the same plan shape are never deduplicated as one root cause.
     */
    public static RootCauseClassification classify(String baseQuery, CounterfactualCandidate candidate,
            PlanShape defaultPlan, PlanShape counterfactualPlan) {
        String intent = detectIntentCategory(baseQuery);
        String family = candidate == null ? "" : candidate.getFamily();
        String mutationId = candidate == null ? "" : candidate.getMutationId();
        // Keep the mechanism portion compatible with the legacy HINT
        // classifier; the enclosing signature below provides the new mutation
        // kind/family/id identity.
        String mechanism = buildMechanismKey(baseQuery, family, mutationId, defaultPlan, counterfactualPlan);
        String identity = candidate == null ? "UNKNOWN" : candidate.getMutationIdentity();
        return new RootCauseClassification(intent, mechanism, intent + "|" + identity + "|" + mechanism);
    }

    public static String detectIntentCategory(String baseQuery) {
        String upperQuery = baseQuery.toUpperCase(Locale.ROOT);
        if (upperQuery.contains("GROUP BY") && upperQuery.contains("ORDER BY") && upperQuery.contains("LIMIT")) {
            return "GROUP_BY_TOPN";
        }
        if (upperQuery.contains("DISTINCT") && upperQuery.contains("ORDER BY") && upperQuery.contains("LIMIT")) {
            return "DISTINCT_TOPN";
        }
        if (upperQuery.contains("DISTINCT")) {
            return "DISTINCT_EARLY_STOP";
        }
        return "PLAIN_TOPN";
    }

    private static String buildMechanismKey(String baseQuery, String hintFamily, String hintType, PlanShape defaultPlan,
            PlanShape hintedPlan) {
        List<String> parts = new ArrayList<>();
        parts.add("HINT_FAMILY=" + sanitize(hintFamily));
        parts.add("HINT_TYPE=" + sanitize(hintType));
        parts.add("DEFAULT_JOIN=" + detectJoinCategory(defaultPlan));
        parts.add("HINTED_JOIN=" + detectJoinCategory(hintedPlan));
        parts.add("BLOCKING=" + detectBlockingTrait(defaultPlan) + "->" + detectBlockingTrait(hintedPlan));
        parts.add("ROW_GOAL=" + detectRowGoalTrait(baseQuery, hintedPlan));
        parts.add("ORDERING=" + detectOrderingTrait(baseQuery, defaultPlan, hintedPlan));
        parts.add("DEDUP=" + detectDedupStrategy(defaultPlan) + "->" + detectDedupStrategy(hintedPlan));
        parts.add("ACCESS_DELTA=" + detectAccessPathDelta(defaultPlan, hintedPlan));
        parts.add("STATS=" + detectStatsSensitiveTrait(defaultPlan, hintedPlan));
        parts.add("SIMPLIFY=" + detectPlanSimplification(defaultPlan, hintedPlan));
        return String.join("|", parts);
    }

    private static String detectJoinCategory(PlanShape plan) {
        if (plan.getOperations().contains("HASH_JOIN")) {
            return "HASH_JOIN";
        }
        if (plan.getOperations().contains("NESTED_LOOP")) {
            return "NESTED_LOOP";
        }
        if (plan.getOperations().contains("MERGE_JOIN")) {
            return "MERGE_JOIN";
        }
        return "JOIN_OTHER";
    }

    private static String detectBlockingTrait(PlanShape plan) {
        if (plan.isUsingDeduplication() || plan.getOperations().contains("TEMP_DEDUP")) {
            return "TEMP_DEDUP";
        }
        if (plan.isUsingFilesort() || plan.getOperations().contains("SORT")) {
            return "SORT_TOPN";
        }
        if (plan.getOperations().contains("STREAM_RESULTS") || plan.getOperations().contains("TOPN")) {
            return "STREAM_TOPN";
        }
        if (plan.isUsingTemporaryTable() || plan.getOperations().contains("TEMP_TABLE")) {
            return "TEMP_TABLE";
        }
        return "NONE";
    }

    private static String detectRowGoalTrait(String baseQuery, PlanShape hintedPlan) {
        String upperQuery = baseQuery.toUpperCase(Locale.ROOT);
        if (upperQuery.contains("GROUP BY")) {
            return "GROUP_EARLY_STOP";
        }
        if (upperQuery.contains("ORDER BY") && upperQuery.contains("LIMIT")) {
            return "ORDERED_TOPN";
        }
        if (hintedPlan.getOperations().contains("LIMIT_SMALL")) {
            return "INNER_LIMIT_ONE";
        }
        return "NONE";
    }

    private static String detectOrderingTrait(String baseQuery, PlanShape defaultPlan, PlanShape hintedPlan) {
        String upperQuery = baseQuery.toUpperCase(Locale.ROOT);
        if (!upperQuery.contains("ORDER BY")) {
            return "NO_ORDER";
        }
        boolean defaultSorted = defaultPlan.isUsingFilesort() || defaultPlan.getOperations().contains("SORT");
        boolean hintedSorted = hintedPlan.isUsingFilesort() || hintedPlan.getOperations().contains("SORT");
        if (defaultSorted && !hintedSorted) {
            return "ORDER_PRESERVED";
        }
        if (!defaultSorted && hintedSorted) {
            return "ORDER_REGRESSED";
        }
        return defaultSorted ? "ORDER_SORTED" : "ORDER_STREAMED";
    }

    private static String detectDedupStrategy(PlanShape plan) {
        if (plan.isUsingDeduplication() || plan.getOperations().contains("TEMP_DEDUP")) {
            return "TEMP_DEDUP";
        }
        if (plan.getOperations().contains("STREAM_AGG") || plan.getOperations().contains("HASH_AGG")) {
            return "AGG_DEDUP";
        }
        return "NONE";
    }

    private static String detectAccessPathDelta(PlanShape defaultPlan, PlanShape hintedPlan) {
        String defaultAccess = summarizeAccessTypes(defaultPlan);
        String hintedAccess = summarizeAccessTypes(hintedPlan);
        if (!defaultPlan.getKeys().equals(hintedPlan.getKeys())) {
            return defaultAccess + "->" + hintedAccess + ":KEY_CHANGED";
        }
        if (!defaultAccess.equals(hintedAccess)) {
            return defaultAccess + "->" + hintedAccess;
        }
        return defaultAccess + "->" + hintedAccess + ":UNCHANGED";
    }

    private static String detectStatsSensitiveTrait(PlanShape defaultPlan, PlanShape hintedPlan) {
        Double defaultRows = maxNullable(defaultPlan.getRowsExaminedPerScan(), defaultPlan.getRowsProducedPerJoin());
        Double hintedRows = maxNullable(hintedPlan.getRowsExaminedPerScan(), hintedPlan.getRowsProducedPerJoin());
        if (defaultRows == null || hintedRows == null || defaultRows <= 0 || hintedRows <= 0) {
            return "UNKNOWN";
        }
        double ratio = defaultRows / hintedRows;
        if (ratio >= 5.0 || ratio <= 0.2) {
            return "ROWS_DIVERGENT";
        }
        if (ratio >= 2.0 || ratio <= 0.5) {
            return "ROWS_SHIFTED";
        }
        return "ROWS_STABLE";
    }

    private static String detectPlanSimplification(PlanShape defaultPlan, PlanShape hintedPlan) {
        int defaultOps = defaultPlan.getOperations().size();
        int hintedOps = hintedPlan.getOperations().size();
        if (hintedOps < defaultOps) {
            return "SIMPLER";
        }
        if (hintedOps > defaultOps) {
            return "MORE_COMPLEX";
        }
        return "SAME_COMPLEXITY";
    }

    private static String summarizeAccessTypes(PlanShape plan) {
        if (plan.getAccessTypes().isEmpty()) {
            return "NONE";
        }
        return plan.getAccessTypes().stream().sorted().collect(Collectors.joining("+"));
    }

    private static Double maxNullable(Double first, Double second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return Math.max(first, second);
    }

    private static String sanitize(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return "UNKNOWN";
        }
        return rawValue.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
}
