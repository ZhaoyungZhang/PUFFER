package sqlancer.cockroachdb.oracle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import sqlancer.cockroachdb.CockroachDBProvider.CockroachDBGlobalState;
import sqlancer.cockroachdb.gen.CockroachDBPerformanceHintGenerator.HintedQueryCandidate;
import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.CounterfactualMutationKind;
import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.common.perfhint.PerformanceHintRootCauseClassifier;
import sqlancer.common.perfhint.PerformanceHintRootCauseClassifier.RootCauseClassification;
import sqlancer.common.perfhint.PerformanceNineCellCategory;
import sqlancer.common.perfhint.PerformanceNineCellClassifier;
import sqlancer.common.perfhint.PerformanceNineCellOptions;

final class CockroachDBPerformanceHintOracleUtils {

    static final int MAX_TABLES = 4;
    static final int MAX_INDEXES_PER_TABLE = 2;
    static final int MAX_HINTED_VARIANTS = 16;

    private static final Pattern COST_PATTERN = Pattern.compile("cost:\\s*([0-9.]+(?:[eE][+-]?[0-9]+)?)");
    private static final Pattern STATS_ROWS_PATTERN = Pattern.compile("stats:\\s*\\[rows=([0-9.]+)");
    private static final Pattern CARDINALITY_PATTERN = Pattern
            .compile("cardinality:\\s*\\[([0-9.]+)\\s*-\\s*([0-9.]+)\\]");
    private static final Pattern INDEX_PATTERN = Pattern.compile("@([A-Za-z0-9_]+)");
    private static final Pattern EXEC_TIME_PATTERN = Pattern.compile("execution time:\\s*([0-9.]+)\\s*(ns|us|µs|ms|s)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW_COUNT_PATTERN = Pattern.compile("actual row count:\\s*([0-9.]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN_TOKEN_PATTERN = Pattern.compile(
            "\\b(INNER\\s+JOIN|LEFT\\s+JOIN|RIGHT\\s+JOIN|FULL\\s+JOIN|CROSS\\s+JOIN)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_VALUE_PATTERN = Pattern.compile("'[^']*'");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final DateTimeFormatter CASE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final AtomicLong CASE_SEQUENCE = new AtomicLong();
    private static final String BUG_REPORT_DIR_PROPERTY = "sqlancer.bugReportDir";
    private static final String BUG_REPORT_DIR_ENV = "SQLANCER_BUG_REPORT_DIR";
    private static final String DIAGNOSTIC_REPORT_DIR_NAME = "diagnostic_cases";

    private CockroachDBPerformanceHintOracleUtils() {
    }

    static String applyCandidate(String query, HintedQueryCandidate candidate) {
        String masked = sqlancer.common.perfhint.PerformanceSqlLexical.maskLiterals(query);
        if (candidate.getHintFamily().equals("JOIN")) {
            int target = candidate.getTargetJoinOrdinal() == null ? 0 : candidate.getTargetJoinOrdinal();
            Matcher matcher = JOIN_TOKEN_PATTERN.matcher(masked);
            int ordinal = 0;
            while (matcher.find()) {
                if (ordinal++ == target) {
                    String original = matcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
                    String expected = candidate.getOriginalJoinToken();
                    if (expected == null
                            || original.equals(expected.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT))) {
                        return query.substring(0, matcher.start()) + candidate.getHintSql()
                                + query.substring(matcher.end());
                    }
                    return query;
                }
            }
        } else if (candidate.getHintFamily().equals("INDEX")) {
            String tableName = candidate.getTableNames().get(0);
            Pattern reference = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+(" + Pattern.quote(tableName) + ")\\b");
            Matcher matcher = reference.matcher(masked);
            if (matcher.find()) {
                return query.substring(0, matcher.start(1)) + candidate.getHintSql() + query.substring(matcher.end(1));
            }
        }
        return query;
    }

    static PlanSummary parsePlan(String explainText) {
        if (explainText == null || explainText.isBlank()) {
            return new PlanSummary("", null, Set.of(), Set.of(), Set.of(), false, false, false, null, null);
        }
        String[] rawLines = explainText.split("\\R");
        Double queryCost = null;
        Double rowsExamined = null;
        Double rowsProduced = null;
        Set<String> keys = new LinkedHashSet<>();
        Set<String> accessTypes = new LinkedHashSet<>();
        Set<String> operations = new LinkedHashSet<>();
        boolean usingTemporaryTable = false;
        boolean usingDeduplication = false;
        boolean usingFilesort = false;

        for (String rawLine : rawLines) {
            String line = normalizeExplainLine(rawLine);
            if (line.isEmpty() || line.equals("info")) {
                continue;
            }
            if (queryCost == null) {
                Matcher costMatcher = COST_PATTERN.matcher(line);
                if (costMatcher.find()) {
                    queryCost = parseNumber(costMatcher.group(1));
                }
            }
            Matcher rowsMatcher = STATS_ROWS_PATTERN.matcher(line);
            if (rowsMatcher.find()) {
                Double rows = parseNumber(rowsMatcher.group(1));
                rowsExamined = max(rowsExamined, rows);
                rowsProduced = max(rowsProduced, rows);
            }
            Matcher cardinalityMatcher = CARDINALITY_PATTERN.matcher(line);
            if (cardinalityMatcher.find()) {
                rowsProduced = max(rowsProduced, parseNumber(cardinalityMatcher.group(2)));
            }

            keys.addAll(extractAllStrings(INDEX_PATTERN, line));
            String operation = normalizeOperation(line);
            if (!operation.isEmpty()) {
                operations.add(operation);
            }
            String accessType = normalizeAccessType(line);
            if (!accessType.isEmpty()) {
                accessTypes.add(accessType);
            }

            String lower = line.toLowerCase(Locale.ROOT);
            usingTemporaryTable |= lower.contains("temp table");
            usingDeduplication |= lower.contains("distinct") || lower.contains("group") || lower.contains("hash-agg")
                    || lower.contains("stream-agg");
            usingFilesort |= lower.startsWith("sort") || lower.startsWith("top-k");
        }

        return new PlanSummary(explainText, queryCost, keys, accessTypes, operations, usingTemporaryTable,
                usingDeduplication, usingFilesort, rowsExamined, rowsProduced);
    }

    static AnalyzeSummary parseAnalyzeSummary(String analyzeText) {
        if (analyzeText == null) {
            return new AnalyzeSummary("", null, null);
        }
        Double executionTimeMs = null;
        Double maxRows = null;
        Matcher executionMatcher = EXEC_TIME_PATTERN.matcher(analyzeText);
        while (executionMatcher.find()) {
            executionTimeMs = max(executionTimeMs,
                    convertToMilliseconds(Double.parseDouble(executionMatcher.group(1)), executionMatcher.group(2)));
        }
        Matcher rowsMatcher = ROW_COUNT_PATTERN.matcher(analyzeText);
        while (rowsMatcher.find()) {
            maxRows = max(maxRows, parseNumber(rowsMatcher.group(1)));
        }
        return new AnalyzeSummary(analyzeText, executionTimeMs, maxRows);
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    static boolean isPotentialPlanCandidate(PlanSummary defaultPlan, PlanSummary hintedPlan,
            PerformanceNineCellOptions taxonomyOptions) {
        if (defaultPlan.getQueryCost() == null || hintedPlan.getQueryCost() == null) {
            return false;
        }
        return PerformanceNineCellClassifier.isPotentialCostCandidate(defaultPlan.getQueryCost(),
                hintedPlan.getQueryCost(), taxonomyOptions) && hasMeaningfulPlanDifference(defaultPlan, hintedPlan);
    }

    static List<String> buildPlanEvidence(PlanSummary defaultPlan, PlanSummary hintedPlan,
            PerformanceNineCellOptions taxonomyOptions) {
        if (defaultPlan.getQueryCost() == null || hintedPlan.getQueryCost() == null) {
            return List.of();
        }
        List<String> evidence = new ArrayList<>();
        double costRatio = computePositiveRatio(defaultPlan.getQueryCost(), hintedPlan.getQueryCost());
        double higherCostRatio = computePositiveRatio(hintedPlan.getQueryCost(), defaultPlan.getQueryCost());
        if (costRatio > taxonomyOptions.getCostRatioUpperBound()) {
            evidence.add(String.format(Locale.US, "cost_ratio=%.4f", costRatio));
        }
        if (costRatio < taxonomyOptions.getCostRatioLowerBound()) {
            evidence.add(String.format(Locale.US, "higher_cost_ratio=%.4f", higherCostRatio));
        }
        if (costRatio >= taxonomyOptions.getCostRatioLowerBound()
                && costRatio <= taxonomyOptions.getCostRatioUpperBound()) {
            evidence.add(String.format(Locale.US, "cost_similarity_ratio=%.4f", costRatio));
        }
        appendSharedPlanEvidence(defaultPlan, hintedPlan, evidence);
        if (evidence.isEmpty() || !hasMeaningfulPlanDifference(defaultPlan, hintedPlan)) {
            return List.of();
        }
        return evidence;
    }

    /* Plan-only screening: cost is recorded but remains an A-I axis, not a gate. */
    static List<String> buildPlanDifferenceEvidence(PlanSummary defaultPlan, PlanSummary hintedPlan) {
        if (!hasMeaningfulPlanDifference(defaultPlan, hintedPlan)) {
            return List.of();
        }
        List<String> evidence = new ArrayList<>();
        if (defaultPlan.getQueryCost() != null && hintedPlan.getQueryCost() != null) {
            evidence.add(String.format(Locale.US, "cost_ratio=%.4f",
                    computePositiveRatio(defaultPlan.getQueryCost(), hintedPlan.getQueryCost())));
        }
        appendSharedPlanEvidence(defaultPlan, hintedPlan, evidence);
        return evidence;
    }

    static boolean resultRowsMatch(List<List<String>> baseRows, List<List<String>> counterfactualRows) {
        if (baseRows.size() != counterfactualRows.size()) {
            return false;
        }
        return rowMultiplicity(baseRows).equals(rowMultiplicity(counterfactualRows));
    }

    private static Map<List<String>, Integer> rowMultiplicity(List<List<String>> rows) {
        Map<List<String>, Integer> multiplicities = new HashMap<>();
        for (List<String> row : rows) {
            multiplicities.merge(new ArrayList<>(row), 1, Integer::sum);
        }
        return multiplicities;
    }

    static boolean isHintAcceptedByPlan(CounterfactualCandidate candidate, PlanSummary plan) {
        if (candidate.getKind() != CounterfactualMutationKind.HINT) {
            return true;
        }
        for (String index : candidate.getTargetIndexes()) {
            if (!containsIdentifier(plan.getKeys(), index)) {
                return false;
            }
        }
        String mutation = candidate.getMutationId().toUpperCase(Locale.ROOT);
        if (mutation.contains("HASH_JOIN") && !plan.getOperations().contains("HASH_JOIN")) {
            return false;
        }
        if (mutation.contains("MERGE_JOIN") && !plan.getOperations().contains("MERGE_JOIN")) {
            return false;
        }
        if (mutation.contains("LOOKUP_JOIN") && !plan.getOperations().contains("LOOKUP_JOIN")) {
            return false;
        }
        return !mutation.contains("NO_FULL_SCAN") || !plan.getAccessTypes().contains("FULL_SCAN");
    }

    private static boolean containsIdentifier(Set<String> identifiers, String expected) {
        String normalizedExpected = expected == null ? "" : expected.replace("\"", "").toLowerCase(Locale.ROOT);
        return identifiers.stream().map(value -> value.replace("\"", "").toLowerCase(Locale.ROOT))
                .anyMatch(normalizedExpected::equals);
    }

    static String buildSkeletonKey(String baseQuery, int tableCount) {
        String normalized = baseQuery.toUpperCase(Locale.ROOT);
        normalized = QUOTED_VALUE_PATTERN.matcher(normalized).replaceAll("?");
        normalized = NUMBER_PATTERN.matcher(normalized).replaceAll("?");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return "tables=" + tableCount + "|" + normalized;
    }

    static RootCauseClassification classifyRootCause(PerformanceHintWitness witness) {
        return PerformanceHintRootCauseClassifier.classify(witness.getBaseQuery(), witness.getCounterfactualCandidate(),
                witness.getDefaultPlan(), witness.getHintedPlan());
    }

    static String buildFamilyKey(PerformanceHintWitness witness) {
        return witness.getRootCauseIntentCategory() + "|" + witness.getCounterfactualCandidate().getKind().name() + "|"
                + witness.getCounterfactualCandidate().getFamily();
    }

    static String buildPlanSignatureKey(PerformanceHintWitness witness) {
        List<String> parts = new ArrayList<>();
        parts.add("mutation=" + witness.getCounterfactualCandidate().getMutationIdentity());
        parts.add("defaultKeys=" + joinOrNone(witness.getDefaultPlan().getKeys()));
        parts.add("defaultAccess=" + joinOrNone(witness.getDefaultPlan().getAccessTypes()));
        parts.add("defaultOps=" + joinOrNone(witness.getDefaultPlan().getOperations()));
        parts.add("counterfactualKeys=" + joinOrNone(witness.getHintedPlan().getKeys()));
        parts.add("counterfactualAccess=" + joinOrNone(witness.getHintedPlan().getAccessTypes()));
        parts.add("counterfactualOps=" + joinOrNone(witness.getHintedPlan().getOperations()));
        parts.add("evidence=" + String.join(",", witness.getPlanEvidence()));
        return String.join("|", parts);
    }

    /*
     * These keys describe the on-disk HINT-only feedback format used before counterfactual mutations were introduced.
     * They are deliberately kept here so the feedback manager can absorb an old entry when the matching HINT witness is
     * encountered, then persist only the generic identity.
     */
    static String buildLegacyHintFamilyKey(PerformanceHintWitness witness) {
        if (!witness.isHintCounterfactual()) {
            return null;
        }
        return witness.getRootCauseIntentCategory() + "|" + witness.getCounterfactualCandidate().getFamily();
    }

    static String buildLegacyHintPlanSignatureKey(PerformanceHintWitness witness) {
        if (!witness.isHintCounterfactual()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add("hint=" + witness.getCounterfactualCandidate().getMutationId());
        parts.add("keys=" + joinOrNone(witness.getHintedPlan().getKeys()));
        parts.add("access=" + joinOrNone(witness.getHintedPlan().getAccessTypes()));
        parts.add("ops=" + joinOrNone(witness.getHintedPlan().getOperations()));
        parts.add("evidence=" + String.join(",", witness.getPlanEvidence()));
        return String.join("|", parts);
    }

    static String buildLegacyHintRootCauseKey(PerformanceHintWitness witness) {
        if (!witness.isHintCounterfactual()) {
            return null;
        }
        return PerformanceHintRootCauseClassifier.classify(witness.getBaseQuery(),
                witness.getCounterfactualCandidate().getFamily(), witness.getCounterfactualCandidate().getMutationId(),
                witness.getDefaultPlan(), witness.getHintedPlan()).getSignatureKey();
    }

    static String createAssertionMessage(PerformanceHintWitness witness, Path reportDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("Potential CockroachDB PERFORMANCE_HINT counterfactual issue").append(System.lineSeparator());
        sb.append("Report: ").append(reportDir).append(System.lineSeparator());
        sb.append("Base SQL: ").append(witness.getBaseQuery()).append(System.lineSeparator());
        sb.append("Counterfactual SQL: ").append(witness.getCounterfactualQuery()).append(System.lineSeparator());
        sb.append("Mutation: ").append(witness.getCounterfactualCandidate().getMutationIdentity())
                .append(System.lineSeparator());
        sb.append(String.format(Locale.US, "Default cost: %.4f", witness.getDefaultPlan().getQueryCost()))
                .append(System.lineSeparator());
        sb.append(String.format(Locale.US, "Hinted cost: %.4f", witness.getHintedPlan().getQueryCost()))
                .append(System.lineSeparator());
        if (witness.getDefaultRuntime() != null && witness.getHintedRuntime() != null) {
            sb.append(String.format(Locale.US, "Default median runtime (ms): %.4f",
                    witness.getDefaultRuntime().getMedianMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "Hinted median runtime (ms): %.4f",
                    witness.getHintedRuntime().getMedianMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "Runtime speedup: %.4f", witness.getRuntimeSpeedup()))
                    .append(System.lineSeparator());
        }
        if (witness.getNineCellCategory() != null) {
            sb.append("Nine-cell category: ").append(witness.getNineCellCategory().getCode()).append(" (")
                    .append(witness.getNineCellCategory().getSummary()).append(")").append(System.lineSeparator());
        }
        sb.append("Plan evidence: ").append(String.join(", ", witness.getPlanEvidence()))
                .append(System.lineSeparator());
        sb.append("Root-cause signature: ").append(witness.getRootCauseSignatureKey()).append(System.lineSeparator());
        return sb.toString();
    }

    static Path writeWitness(CockroachDBGlobalState state, PerformanceHintWitness witness) throws IOException {
        return writeWitness(state, witness, witness.isDiagnosticOnly());
    }

    static Path writeWitness(CockroachDBGlobalState state, PerformanceHintWitness witness, boolean diagnosticOnly)
            throws IOException {
        return writeSnapshot(snapshotWitness(state, witness, diagnosticOnly));
    }

    static PerformanceHintReportSnapshot snapshotWitness(CockroachDBGlobalState state, PerformanceHintWitness witness,
            boolean diagnosticOnly) {
        String caseId = "case_" + CASE_TIME_FORMAT.format(LocalDateTime.now()) + "_" + state.getState().getSeedValue()
                + "_" + CASE_SEQUENCE.incrementAndGet();
        Path reportRoot = resolveReportRoot();
        if (witness.getNineCellCategory() != null) {
            reportRoot = reportRoot.resolve(witness.getNineCellCategory().getCode());
        } else if (diagnosticOnly) {
            reportRoot = reportRoot.resolve(DIAGNOSTIC_REPORT_DIR_NAME);
        }
        Path reportDir = reportRoot.resolve(caseId);
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("setup.sql", state.getState().buildSetupScript());
        if (state.getPdgIrContext() != null) {
            files.put("pdg_ir.yaml", state.getPdgIrContext().yaml());
        }
        files.put("original_query.sql", witness.getBaseQuery() + System.lineSeparator());
        files.put("counterfactual_query.sql", witness.getCounterfactualQuery() + System.lineSeparator());
        files.put("default_explain.txt", witness.getDefaultPlan().getExplainJson());
        files.put("counterfactual_explain.txt", witness.getHintedPlan().getExplainJson());
        if (witness.isHintCounterfactual()) {
            // Existing repro tooling is HINT-named; retain these aliases for HINT only.
            files.put("hinted_query.sql", witness.getCounterfactualQuery() + System.lineSeparator());
            files.put("hinted_explain.txt", witness.getHintedPlan().getExplainJson());
        }
        if (witness.getDefaultAnalyze() != null) {
            files.put("default_explain_analyze.txt", witness.getDefaultAnalyze().getAnalyzeText());
        }
        if (witness.getHintedAnalyze() != null) {
            files.put("counterfactual_explain_analyze.txt", witness.getHintedAnalyze().getAnalyzeText());
            if (witness.isHintCounterfactual()) {
                files.put("hinted_explain_analyze.txt", witness.getHintedAnalyze().getAnalyzeText());
            }
        }
        addRuntimeSampleFiles(files, witness.getRuntimeSamples());
        files.put("metrics.json", buildMetricsJson(state, witness));
        files.put("signature.json", buildSignatureJson(witness));
        files.put("report.md", buildReportMarkdown(state, witness));
        return new PerformanceHintReportSnapshot(reportDir, files);
    }

    static Path writeSnapshot(PerformanceHintReportSnapshot snapshot) throws IOException {
        Path reportDir = snapshot.getFinalReportDirectory();
        Path parent = reportDir.getParent();
        if (parent == null) {
            throw new IOException("report directory must have a parent: " + reportDir);
        }
        Files.createDirectories(parent);
        Path temporaryDirectory = Files.createTempDirectory(parent, "." + reportDir.getFileName() + ".tmp-");
        for (Map.Entry<String, String> artifact : snapshot.getFiles().entrySet()) {
            Path output = temporaryDirectory.resolve(artifact.getKey()).normalize();
            if (!output.startsWith(temporaryDirectory)) {
                throw new IOException("invalid report artifact path: " + artifact.getKey());
            }
            Path outputParent = output.getParent();
            if (outputParent != null) {
                Files.createDirectories(outputParent);
            }
            writeFile(output, artifact.getValue());
        }
        try {
            Files.move(temporaryDirectory, reportDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryDirectory, reportDir);
        }
        return reportDir;
    }

    static Path resolveReportRoot() {
        String configuredRoot = System.getProperty(BUG_REPORT_DIR_PROPERTY);
        if (configuredRoot == null || configuredRoot.isBlank()) {
            configuredRoot = System.getenv(BUG_REPORT_DIR_ENV);
        }
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return Path.of("bug_reports");
        }
        return Path.of(configuredRoot);
    }

    static String joinOrNone(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "<none>";
        }
        return values.stream().sorted().collect(Collectors.joining(", "));
    }

    static double computePositiveRatio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || numerator <= 0 || denominator <= 0) {
            return 0.0;
        }
        return numerator / denominator;
    }

    private static void appendSharedPlanEvidence(PlanSummary defaultPlan, PlanSummary hintedPlan,
            List<String> evidence) {
        if (hasKeyImprovement(defaultPlan, hintedPlan)) {
            evidence.add("hinted_plan_uses_more_specific_index");
        }
        if (!defaultPlan.getKeys().equals(hintedPlan.getKeys())) {
            evidence.add("plan_keys_changed");
        }
        if (!defaultPlan.getAccessTypes().equals(hintedPlan.getAccessTypes())) {
            evidence.add("plan_access_type_changed");
        }
        if (!defaultPlan.getOperations().equals(hintedPlan.getOperations())) {
            evidence.add("plan_operations_changed");
        }
        if (defaultPlan.isUsingFilesort() && !hintedPlan.isUsingFilesort()) {
            evidence.add("hinted_plan_avoids_filesort");
        } else if (!defaultPlan.isUsingFilesort() && hintedPlan.isUsingFilesort()) {
            evidence.add("hinted_plan_adds_filesort");
        }
        if (defaultPlan.isUsingDeduplication() != hintedPlan.isUsingDeduplication()) {
            evidence.add("plan_deduplication_changed");
        }
        if (hasRowEstimateDifference(defaultPlan.getRowsExaminedPerScan(), hintedPlan.getRowsExaminedPerScan(), 1.2)) {
            evidence.add("rows_examined_changed");
        }
        if (hasRowEstimateDifference(defaultPlan.getRowsProducedPerJoin(), hintedPlan.getRowsProducedPerJoin(), 1.2)) {
            evidence.add("rows_produced_changed");
        }
    }

    static boolean hasMeaningfulPlanDifference(PlanSummary defaultPlan, PlanSummary hintedPlan) {
        return !defaultPlan.getKeys().equals(hintedPlan.getKeys())
                || !defaultPlan.getAccessTypes().equals(hintedPlan.getAccessTypes())
                || !defaultPlan.getOperations().equals(hintedPlan.getOperations())
                || defaultPlan.isUsingDeduplication() != hintedPlan.isUsingDeduplication()
                || defaultPlan.isUsingFilesort() != hintedPlan.isUsingFilesort()
                || hasRowEstimateDifference(defaultPlan.getRowsExaminedPerScan(), hintedPlan.getRowsExaminedPerScan(),
                        1.01)
                || hasRowEstimateDifference(defaultPlan.getRowsProducedPerJoin(), hintedPlan.getRowsProducedPerJoin(),
                        1.01);
    }

    private static boolean hasKeyImprovement(PlanSummary defaultPlan, PlanSummary hintedPlan) {
        return hintedPlan.getAccessTypes().contains("INDEX_SCAN") && defaultPlan.getAccessTypes().contains("FULL_SCAN");
    }

    private static boolean hasRowEstimateDifference(Double first, Double second, double minRatio) {
        if (first == null || second == null || first <= 0 || second <= 0) {
            return false;
        }
        double ratio = Math.max(first / second, second / first);
        return ratio >= minRatio;
    }

    private static String normalizeExplainLine(String rawLine) {
        return rawLine.replace("└──", "").replace("├──", "").replace("│", "").replace("\"", "").trim();
    }

    private static String normalizeOperation(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("hash join")) {
            return "HASH_JOIN";
        }
        if (lower.startsWith("merge join")) {
            return "MERGE_JOIN";
        }
        if (lower.startsWith("lookup join") || lower.contains("inner-join (lookup")) {
            return "LOOKUP_JOIN";
        }
        if (lower.startsWith("top-k")) {
            return "TOPN";
        }
        if (lower.startsWith("sort")) {
            return "SORT";
        }
        if (lower.startsWith("scan")) {
            return "SCAN";
        }
        if (lower.startsWith("group")) {
            return "STREAM_AGG";
        }
        if (lower.contains("distinct")) {
            return "DISTINCT";
        }
        if (lower.contains("index-join")) {
            return "INDEX_JOIN";
        }
        return "";
    }

    private static String normalizeAccessType(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("spans: full scan")) {
            return "FULL_SCAN";
        }
        if (lower.startsWith("scan ") || lower.contains(" scan ")) {
            if (lower.contains("@") && !lower.contains("@t0_pkey") && !lower.contains("@t1_pkey")
                    && !lower.contains("_pkey")) {
                return "INDEX_SCAN";
            }
            if (lower.contains("@")) {
                return "PRIMARY_SCAN";
            }
        }
        return "";
    }

    private static Set<String> extractAllStrings(Pattern pattern, String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static Double parseNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Double.parseDouble(text);
    }

    private static Double max(Double first, Double second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return Math.max(first, second);
    }

    private static double convertToMilliseconds(double value, String unit) {
        String normalized = unit.toLowerCase(Locale.ROOT);
        switch (normalized) {
        case "ns":
            return value / 1_000_000.0;
        case "us":
        case "µs":
            return value / 1000.0;
        case "ms":
            return value;
        case "s":
            return value * 1000.0;
        default:
            return value;
        }
    }

    private static void addRuntimeSampleFiles(Map<String, String> files, List<RuntimeSample> runtimeSamples) {
        for (RuntimeSample runtimeSample : runtimeSamples) {
            String fileName = String.format(Locale.US, "runtime_sample_%d_%s.txt", runtimeSample.getSequence(),
                    runtimeSample.getSide().toLowerCase(Locale.ROOT));
            StringBuilder sb = new StringBuilder();
            sb.append("sample: ").append(runtimeSample.getSequence()).append(System.lineSeparator());
            sb.append("side: ").append(runtimeSample.getSide()).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "elapsed_ms: %.4f", runtimeSample.getElapsedMs()))
                    .append(System.lineSeparator());
            sb.append("timed_out: ").append(runtimeSample.isTimedOut()).append(System.lineSeparator());
            sb.append("sql:").append(System.lineSeparator()).append(runtimeSample.getQuery())
                    .append(System.lineSeparator());
            files.put(fileName, sb.toString());
        }
    }

    private static String buildMetricsJson(CockroachDBGlobalState state, PerformanceHintWitness witness) {
        List<String> fields = new ArrayList<>();
        fields.add(jsonField("dbms", "cockroachdb"));
        fields.add(jsonField("dataAblationProfile",
                state.getDbmsSpecificOptions().performanceDataAblationProfile.getId()));
        fields.add(jsonField("initialScale", state.getDbmsSpecificOptions().performanceInitialScale));
        if (state.getPdgIrContext() != null) {
            fields.add(jsonArrayField("pdgPrimitives",
                    state.getPdgIrContext().getSpecification().getTriggeredPrimitives()));
        }
        fields.add(jsonField("databaseVersion", state.getState().getDatabaseVersion()));
        fields.add(jsonField("seed", state.getState().getSeedValue()));
        fields.add(jsonField("baseQuery", witness.getBaseQuery()));
        fields.add(jsonField("mutationKind", witness.getCounterfactualCandidate().getKind().name()));
        fields.add(jsonField("mutationFamily", witness.getCounterfactualCandidate().getFamily()));
        fields.add(jsonField("mutationId", witness.getCounterfactualCandidate().getMutationId()));
        fields.add(jsonField("mutationDescriptor", witness.getCounterfactualCandidate().getDescriptor()));
        fields.add(jsonField("counterfactualQuery", witness.getCounterfactualQuery()));
        fields.add(jsonField("requestedStrategy", witness.getRequestedStrategy().name()));
        fields.add(jsonField("fallbackReason", witness.getFallbackReason()));
        fields.add(jsonArrayField("tableNames", witness.getCounterfactualCandidate().getTargetTables()));
        fields.add(jsonArrayField("indexNames", witness.getCounterfactualCandidate().getTargetIndexes()));
        if (witness.isHintCounterfactual()) {
            fields.add(jsonField("hintedQuery", witness.getCounterfactualQuery()));
            fields.add(jsonField("hintFamily", witness.getCounterfactualCandidate().getFamily()));
            fields.add(jsonField("hintType", witness.getCounterfactualCandidate().getMutationId()));
        }
        fields.add(jsonField("defaultEstimatedCost", witness.getDefaultPlan().getQueryCost()));
        fields.add(jsonField("counterfactualEstimatedCost", witness.getHintedPlan().getQueryCost()));
        fields.add(jsonField("defaultKey", joinOrNone(witness.getDefaultPlan().getKeys())));
        fields.add(jsonField("counterfactualKey", joinOrNone(witness.getHintedPlan().getKeys())));
        if (witness.isHintCounterfactual()) {
            fields.add(jsonField("hintedEstimatedCost", witness.getHintedPlan().getQueryCost()));
            fields.add(jsonField("hintedKey", joinOrNone(witness.getHintedPlan().getKeys())));
        }
        fields.add(jsonField("resultEquivalent", witness.isResultEquivalent()));
        if (witness.getNineCellCategory() != null) {
            fields.add(jsonField("nineCellCategory", witness.getNineCellCategory().getCode()));
            fields.add(jsonField("nineCellSummary", witness.getNineCellCategory().getSummary()));
        }
        fields.add(jsonArrayField("planEvidence", witness.getPlanEvidence()));
        fields.add(jsonField("rootCauseCategory", witness.getRootCauseCategory()));
        fields.add(jsonField("rootCauseIntentCategory", witness.getRootCauseIntentCategory()));
        fields.add(jsonField("rootCauseMechanismKey", witness.getRootCauseMechanismKey()));
        fields.add(jsonField("skeletonKey", witness.getSkeletonKey()));
        fields.add("\"queryGeneration\":" + witness.queryGenerationJson());
        fields.add(jsonField("familyKey", witness.getFamilyKey()));
        fields.add(jsonField("planSignatureKey", witness.getPlanSignatureKey()));
        fields.add(jsonField("rootCauseSignatureKey", witness.getRootCauseSignatureKey()));
        fields.add(jsonField("noveltyScore", witness.getNoveltyScore()));
        fields.add(jsonField("rootCauseNoveltyScore", witness.getRootCauseNoveltyScore()));
        fields.add(jsonField("selectionReason", witness.getSelectionReason()));
        fields.add(jsonField("rootCauseRepresentativeRank", witness.getRootCauseRepresentativeRank()));
        if (state.getPerformanceDataModelDescription() != null) {
            fields.add(jsonField("performanceDataModelDescription", state.getPerformanceDataModelDescription()));
            fields.add(jsonField("dataModelPlan", state.getPerformanceDataModelDescription()));
        }
        if (witness.getDefaultRuntime() != null && witness.getHintedRuntime() != null) {
            fields.add(jsonField("defaultRuntimeMedianMs", witness.getDefaultRuntime().getMedianMs()));
            fields.add(jsonField("counterfactualRuntimeMedianMs", witness.getHintedRuntime().getMedianMs()));
            if (witness.isHintCounterfactual()) {
                fields.add(jsonField("hintedRuntimeMedianMs", witness.getHintedRuntime().getMedianMs()));
            }
            fields.add(jsonField("runtimeSpeedup", witness.getRuntimeSpeedup()));
        }
        if (witness.getNineCellCategory() != null) {
            fields.add(jsonField("nineCellCategory", witness.getNineCellCategory().getCode()));
            fields.add(jsonField("nineCellSummary", witness.getNineCellCategory().getSummary()));
        }
        return "{\n  " + String.join(",\n  ", fields) + "\n}\n";
    }

    private static String buildSignatureJson(PerformanceHintWitness witness) {
        List<String> fields = new ArrayList<>();
        fields.add(jsonField("skeletonKey", witness.getSkeletonKey()));
        fields.add("\"queryGeneration\":" + witness.queryGenerationJson());
        fields.add(jsonField("familyKey", witness.getFamilyKey()));
        fields.add(jsonField("planSignatureKey", witness.getPlanSignatureKey()));
        fields.add(jsonField("rootCauseSignatureKey", witness.getRootCauseSignatureKey()));
        fields.add(jsonField("rootCauseCategory", witness.getRootCauseCategory()));
        fields.add(jsonField("rootCauseIntentCategory", witness.getRootCauseIntentCategory()));
        fields.add(jsonField("rootCauseMechanismKey", witness.getRootCauseMechanismKey()));
        fields.add(jsonField("mutationKind", witness.getCounterfactualCandidate().getKind().name()));
        fields.add(jsonField("mutationFamily", witness.getCounterfactualCandidate().getFamily()));
        fields.add(jsonField("mutationId", witness.getCounterfactualCandidate().getMutationId()));
        fields.add(jsonArrayField("planEvidence", witness.getPlanEvidence()));
        return "{\n  " + String.join(",\n  ", fields) + "\n}\n";
    }

    private static String buildReportMarkdown(CockroachDBGlobalState state, PerformanceHintWitness witness) {
        StringBuilder sb = new StringBuilder();
        sb.append("# CockroachDB PERFORMANCE_HINT counterfactual witness").append(System.lineSeparator())
                .append(System.lineSeparator());
        sb.append("- DBMS version: ").append(state.getState().getDatabaseVersion()).append(System.lineSeparator());
        sb.append("- Seed: ").append(state.getState().getSeedValue()).append(System.lineSeparator());
        sb.append("- Mutation: ").append(witness.getCounterfactualCandidate().getMutationIdentity())
                .append(System.lineSeparator());
        sb.append("- Requested strategy: ").append(witness.getRequestedStrategy()).append(System.lineSeparator());
        sb.append("- Fallback: ").append(witness.getFallbackReason()).append(System.lineSeparator());
        sb.append("- Root cause: ").append(witness.getRootCauseSignatureKey()).append(System.lineSeparator());
        if (state.getPerformanceDataModelDescription() != null) {
            sb.append("- Data model plan: ").append(state.getPerformanceDataModelDescription())
                    .append(System.lineSeparator());
        }
        sb.append("- Plan evidence: ").append(String.join(", ", witness.getPlanEvidence()))
                .append(System.lineSeparator());
        if (witness.getNineCellCategory() != null) {
            sb.append("- Nine-cell category: ").append(witness.getNineCellCategory().getCode()).append(" — ")
                    .append(witness.getNineCellCategory().getSummary()).append(System.lineSeparator());
        }
        if (witness.getDefaultRuntime() != null && witness.getHintedRuntime() != null) {
            sb.append(String.format(Locale.US, "- Runtime speedup: %.4f", witness.getRuntimeSpeedup()))
                    .append(System.lineSeparator());
        }
        sb.append(System.lineSeparator()).append("## Base Query").append(System.lineSeparator()).append("```sql")
                .append(System.lineSeparator()).append(witness.getBaseQuery()).append(System.lineSeparator())
                .append("```").append(System.lineSeparator()).append(System.lineSeparator())
                .append("## Counterfactual Query").append(System.lineSeparator()).append("```sql")
                .append(System.lineSeparator()).append(witness.getCounterfactualQuery()).append(System.lineSeparator())
                .append("```").append(System.lineSeparator());
        return sb.toString();
    }

    private static String jsonField(String key, Object value) {
        if (value == null) {
            return "\"" + escapeJson(key) + "\": null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return "\"" + escapeJson(key) + "\": " + value;
        }
        return "\"" + escapeJson(key) + "\": \"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private static String jsonArrayField(String key, List<String> values) {
        String rendered = values.stream().map(value -> "\"" + escapeJson(value) + "\"")
                .collect(Collectors.joining(", "));
        return "\"" + escapeJson(key) + "\": [" + rendered + "]";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    static final class PlanSummary implements PerformanceHintRootCauseClassifier.PlanShape {
        private final String explainJson;
        private final Double queryCost;
        private final Set<String> keys;
        private final Set<String> accessTypes;
        private final Set<String> operations;
        private final boolean usingTemporaryTable;
        private final boolean usingDeduplication;
        private final boolean usingFilesort;
        private final Double rowsExaminedPerScan;
        private final Double rowsProducedPerJoin;

        PlanSummary(String explainJson, Double queryCost, Set<String> keys, Set<String> accessTypes,
                Set<String> operations, boolean usingTemporaryTable, boolean usingDeduplication, boolean usingFilesort,
                Double rowsExaminedPerScan, Double rowsProducedPerJoin) {
            this.explainJson = explainJson;
            this.queryCost = queryCost;
            this.keys = Set.copyOf(keys);
            this.accessTypes = Set.copyOf(accessTypes);
            this.operations = Set.copyOf(operations);
            this.usingTemporaryTable = usingTemporaryTable;
            this.usingDeduplication = usingDeduplication;
            this.usingFilesort = usingFilesort;
            this.rowsExaminedPerScan = rowsExaminedPerScan;
            this.rowsProducedPerJoin = rowsProducedPerJoin;
        }

        String getExplainJson() {
            return explainJson;
        }

        Double getQueryCost() {
            return queryCost;
        }

        @Override
        public Set<String> getKeys() {
            return keys;
        }

        @Override
        public Set<String> getAccessTypes() {
            return accessTypes;
        }

        @Override
        public Set<String> getOperations() {
            return operations;
        }

        @Override
        public boolean isUsingTemporaryTable() {
            return usingTemporaryTable;
        }

        @Override
        public boolean isUsingDeduplication() {
            return usingDeduplication;
        }

        @Override
        public boolean isUsingFilesort() {
            return usingFilesort;
        }

        @Override
        public Double getRowsExaminedPerScan() {
            return rowsExaminedPerScan;
        }

        @Override
        public Double getRowsProducedPerJoin() {
            return rowsProducedPerJoin;
        }
    }

    static final class AnalyzeSummary {
        private final String analyzeText;
        private final Double executionTimeMs;
        private final Double maxRows;

        AnalyzeSummary(String analyzeText, Double executionTimeMs, Double maxRows) {
            this.analyzeText = analyzeText;
            this.executionTimeMs = executionTimeMs;
            this.maxRows = maxRows;
        }

        String getAnalyzeText() {
            return analyzeText;
        }

        Double getExecutionTimeMs() {
            return executionTimeMs;
        }

        Double getMaxRows() {
            return maxRows;
        }
    }

    static final class RuntimeSummary {
        private final List<Double> runsMs;
        private final int timeoutCount;

        RuntimeSummary(List<Double> runsMs, int timeoutCount) {
            this.runsMs = List.copyOf(runsMs);
            this.timeoutCount = timeoutCount;
        }

        double getMedianMs() {
            return median(runsMs);
        }

        double getMeanMs() {
            return runsMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }

        double getMinMs() {
            return runsMs.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        }

        double getMaxMs() {
            return runsMs.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        }

        int getTimeoutCount() {
            return timeoutCount;
        }

        List<Double> getRunsMs() {
            return runsMs;
        }
    }

    static final class RuntimeSample {
        private final int sequence;
        private final String side;
        private final double elapsedMs;
        private final boolean timedOut;
        private final String query;

        RuntimeSample(int sequence, String side, double elapsedMs, boolean timedOut, String query) {
            this.sequence = sequence;
            this.side = side;
            this.elapsedMs = elapsedMs;
            this.timedOut = timedOut;
            this.query = query;
        }

        int getSequence() {
            return sequence;
        }

        String getSide() {
            return side;
        }

        double getElapsedMs() {
            return elapsedMs;
        }

        boolean isTimedOut() {
            return timedOut;
        }

        String getQuery() {
            return query;
        }
    }

    static final class PerformanceHintWitness {
        private sqlancer.common.perfhint.PerformanceQueryMetadata queryGenerationMetadata;

        private final String baseQuery;
        private final String counterfactualQuery;
        private final CounterfactualCandidate counterfactualCandidate;
        private final PlanSummary defaultPlan;
        private final PlanSummary hintedPlan;
        private final List<String> planEvidence;
        private boolean resultEquivalent;
        private final CounterfactualMutationKind requestedStrategy;
        private final String fallbackReason;
        private RuntimeSummary defaultRuntime;
        private RuntimeSummary hintedRuntime;
        private List<RuntimeSample> runtimeSamples = List.of();
        private AnalyzeSummary defaultAnalyze;
        private AnalyzeSummary hintedAnalyze;
        private PerformanceNineCellCategory nineCellCategory;
        private String skeletonKey;
        private String familyKey;
        private String planSignatureKey;
        private String rootCauseSignatureKey;
        private String rootCauseCategory;
        private String rootCauseIntentCategory;
        private String rootCauseMechanismKey;
        private double noveltyScore;
        private double rootCauseNoveltyScore;
        private long signatureObservationCount;
        private long signatureWrittenReports;
        private long skeletonObservationCount;
        private long familyObservationCount;
        private long rootCauseObservationCount;
        private long rootCauseWrittenReports;
        private long suppressedDuplicateCount;
        private long rootCauseSuppressedCount;
        private String selectionReason = "";
        private String rootCauseRepresentativeRank = "";
        private String hintPolicyDecision = "";
        private String hintFamilyQuotaApplied = "";
        private boolean diagnosticOnly;

        void setQueryGenerationMetadata(sqlancer.common.perfhint.PerformanceQueryMetadata metadata) {
            queryGenerationMetadata = metadata;
        }

        String queryGenerationJson() {
            return queryGenerationMetadata == null ? "null" : queryGenerationMetadata.toJson();
        }

        PerformanceHintWitness(String baseQuery, String counterfactualQuery,
                CounterfactualCandidate counterfactualCandidate, PlanSummary defaultPlan, PlanSummary hintedPlan,
                List<String> planEvidence, boolean resultEquivalent) {
            this(baseQuery, counterfactualQuery, counterfactualCandidate, defaultPlan, hintedPlan, planEvidence,
                    resultEquivalent, counterfactualCandidate.getKind(), "NONE");
        }

        PerformanceHintWitness(String baseQuery, String counterfactualQuery,
                CounterfactualCandidate counterfactualCandidate, PlanSummary defaultPlan, PlanSummary hintedPlan,
                List<String> planEvidence, boolean resultEquivalent, CounterfactualMutationKind requestedStrategy,
                String fallbackReason) {
            this.baseQuery = baseQuery;
            this.counterfactualQuery = counterfactualQuery;
            this.counterfactualCandidate = counterfactualCandidate;
            this.defaultPlan = defaultPlan;
            this.hintedPlan = hintedPlan;
            this.planEvidence = List.copyOf(planEvidence);
            this.resultEquivalent = resultEquivalent;
            this.requestedStrategy = requestedStrategy;
            this.fallbackReason = fallbackReason == null ? "NONE" : fallbackReason;
        }

        String getBaseQuery() {
            return baseQuery;
        }

        String getCounterfactualQuery() {
            return counterfactualQuery;
        }

        CounterfactualCandidate getCounterfactualCandidate() {
            return counterfactualCandidate;
        }

        // Compatibility aliases retain legacy naming without retaining a DBMS-specific candidate payload.
        String getHintedQuery() {
            return getCounterfactualQuery();
        }

        CounterfactualCandidate getCandidate() {
            return getCounterfactualCandidate();
        }

        CounterfactualMutationKind getRequestedStrategy() {
            return requestedStrategy;
        }

        String getFallbackReason() {
            return fallbackReason;
        }

        boolean isHintCounterfactual() {
            return counterfactualCandidate.getKind() == CounterfactualMutationKind.HINT;
        }

        String getCounterfactualRuntimeSide() {
            return isHintCounterfactual() ? "HINTED" : "COUNTERFACTUAL";
        }

        PlanSummary getDefaultPlan() {
            return defaultPlan;
        }

        PlanSummary getHintedPlan() {
            return hintedPlan;
        }

        List<String> getPlanEvidence() {
            return planEvidence;
        }

        boolean isResultEquivalent() {
            return resultEquivalent;
        }

        void setResultEquivalent(boolean resultEquivalent) {
            this.resultEquivalent = resultEquivalent;
        }

        RuntimeSummary getDefaultRuntime() {
            return defaultRuntime;
        }

        void setDefaultRuntime(RuntimeSummary defaultRuntime) {
            this.defaultRuntime = defaultRuntime;
        }

        RuntimeSummary getHintedRuntime() {
            return hintedRuntime;
        }

        void setHintedRuntime(RuntimeSummary hintedRuntime) {
            this.hintedRuntime = hintedRuntime;
        }

        List<RuntimeSample> getRuntimeSamples() {
            return runtimeSamples;
        }

        void setRuntimeSamples(List<RuntimeSample> runtimeSamples) {
            this.runtimeSamples = List.copyOf(runtimeSamples);
        }

        AnalyzeSummary getDefaultAnalyze() {
            return defaultAnalyze;
        }

        void setDefaultAnalyze(AnalyzeSummary defaultAnalyze) {
            this.defaultAnalyze = defaultAnalyze;
        }

        AnalyzeSummary getHintedAnalyze() {
            return hintedAnalyze;
        }

        void setHintedAnalyze(AnalyzeSummary hintedAnalyze) {
            this.hintedAnalyze = hintedAnalyze;
        }

        PerformanceNineCellCategory getNineCellCategory() {
            return nineCellCategory;
        }

        void setNineCellCategory(PerformanceNineCellCategory nineCellCategory) {
            this.nineCellCategory = nineCellCategory;
        }

        String getSkeletonKey() {
            return skeletonKey;
        }

        void setSkeletonKey(String skeletonKey) {
            this.skeletonKey = skeletonKey;
        }

        String getFamilyKey() {
            return familyKey;
        }

        void setFamilyKey(String familyKey) {
            this.familyKey = familyKey;
        }

        String getPlanSignatureKey() {
            return planSignatureKey;
        }

        void setPlanSignatureKey(String planSignatureKey) {
            this.planSignatureKey = planSignatureKey;
        }

        String getRootCauseSignatureKey() {
            return rootCauseSignatureKey;
        }

        void setRootCauseSignatureKey(String rootCauseSignatureKey) {
            this.rootCauseSignatureKey = rootCauseSignatureKey;
        }

        String getRootCauseCategory() {
            return rootCauseCategory;
        }

        void setRootCauseCategory(String rootCauseCategory) {
            this.rootCauseCategory = rootCauseCategory;
        }

        String getRootCauseIntentCategory() {
            return rootCauseIntentCategory;
        }

        void setRootCauseIntentCategory(String rootCauseIntentCategory) {
            this.rootCauseIntentCategory = rootCauseIntentCategory;
        }

        String getRootCauseMechanismKey() {
            return rootCauseMechanismKey;
        }

        void setRootCauseMechanismKey(String rootCauseMechanismKey) {
            this.rootCauseMechanismKey = rootCauseMechanismKey;
        }

        double getNoveltyScore() {
            return noveltyScore;
        }

        void setNoveltyScore(double noveltyScore) {
            this.noveltyScore = noveltyScore;
        }

        double getRootCauseNoveltyScore() {
            return rootCauseNoveltyScore;
        }

        void setRootCauseNoveltyScore(double rootCauseNoveltyScore) {
            this.rootCauseNoveltyScore = rootCauseNoveltyScore;
        }

        long getSignatureObservationCount() {
            return signatureObservationCount;
        }

        void setSignatureObservationCount(long signatureObservationCount) {
            this.signatureObservationCount = signatureObservationCount;
        }

        long getSignatureWrittenReports() {
            return signatureWrittenReports;
        }

        void setSignatureWrittenReports(long signatureWrittenReports) {
            this.signatureWrittenReports = signatureWrittenReports;
        }

        long getSkeletonObservationCount() {
            return skeletonObservationCount;
        }

        void setSkeletonObservationCount(long skeletonObservationCount) {
            this.skeletonObservationCount = skeletonObservationCount;
        }

        long getFamilyObservationCount() {
            return familyObservationCount;
        }

        void setFamilyObservationCount(long familyObservationCount) {
            this.familyObservationCount = familyObservationCount;
        }

        long getRootCauseObservationCount() {
            return rootCauseObservationCount;
        }

        void setRootCauseObservationCount(long rootCauseObservationCount) {
            this.rootCauseObservationCount = rootCauseObservationCount;
        }

        long getRootCauseWrittenReports() {
            return rootCauseWrittenReports;
        }

        void setRootCauseWrittenReports(long rootCauseWrittenReports) {
            this.rootCauseWrittenReports = rootCauseWrittenReports;
        }

        long getSuppressedDuplicateCount() {
            return suppressedDuplicateCount;
        }

        void setSuppressedDuplicateCount(long suppressedDuplicateCount) {
            this.suppressedDuplicateCount = suppressedDuplicateCount;
        }

        long getRootCauseSuppressedCount() {
            return rootCauseSuppressedCount;
        }

        void setRootCauseSuppressedCount(long rootCauseSuppressedCount) {
            this.rootCauseSuppressedCount = rootCauseSuppressedCount;
        }

        String getSelectionReason() {
            return selectionReason;
        }

        void setSelectionReason(String selectionReason) {
            this.selectionReason = selectionReason;
        }

        String getRootCauseRepresentativeRank() {
            return rootCauseRepresentativeRank;
        }

        void setRootCauseRepresentativeRank(String rootCauseRepresentativeRank) {
            this.rootCauseRepresentativeRank = rootCauseRepresentativeRank;
        }

        String getHintPolicyDecision() {
            return hintPolicyDecision;
        }

        void setHintPolicyDecision(String hintPolicyDecision) {
            this.hintPolicyDecision = hintPolicyDecision;
        }

        String getHintFamilyQuotaApplied() {
            return hintFamilyQuotaApplied;
        }

        void setHintFamilyQuotaApplied(String hintFamilyQuotaApplied) {
            this.hintFamilyQuotaApplied = hintFamilyQuotaApplied;
        }

        boolean isDiagnosticOnly() {
            return diagnosticOnly;
        }

        void setDiagnosticOnly(boolean diagnosticOnly) {
            this.diagnosticOnly = diagnosticOnly;
        }

        double getRuntimeSpeedup() {
            if (defaultRuntime == null || hintedRuntime == null) {
                return 0.0;
            }
            return computePositiveRatio(defaultRuntime.getMedianMs(), hintedRuntime.getMedianMs());
        }

        double getCostRatio() {
            return computePositiveRatio(defaultPlan.getQueryCost(), hintedPlan.getQueryCost());
        }
    }
}
