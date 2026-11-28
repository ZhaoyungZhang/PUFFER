package sqlancer.mysql.oracle;

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

import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.CounterfactualMutationKind;
import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.common.perfhint.PerformanceHintRootCauseClassifier;
import sqlancer.common.perfhint.PerformanceHintRootCauseClassifier.RootCauseClassification;
import sqlancer.common.perfhint.PerformanceNineCellCategory;
import sqlancer.common.perfhint.PerformanceNineCellClassifier;
import sqlancer.common.perfhint.PerformanceNineCellOptions;
import sqlancer.mysql.MySQLGlobalState;

final class MySQLPerformanceHintOracleUtils {

    static final int MAX_TABLES = 3;
    static final int MAX_INDEXES_PER_TABLE = 2;
    static final int MAX_HINTED_VARIANTS = 16;

    private static final String SELECT_PREFIX = "SELECT ";
    private static final Pattern QUERY_COST_PATTERN = Pattern.compile("\"query_cost\"\\s*:\\s*\"?([-+0-9.eE]+)\"?");
    private static final Pattern ESTIMATED_TOTAL_COST_PATTERN = Pattern
            .compile("\"estimated_total_cost\"\\s*:\\s*\"?([-+0-9.eE]+)\"?");
    private static final Pattern KEY_PATTERN = Pattern.compile("\"key\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern INDEX_NAME_PATTERN = Pattern.compile("\"index_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ACCESS_TYPE_PATTERN = Pattern.compile("\"access_type\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern OPERATION_PATTERN = Pattern.compile("\"operation\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BOOLEAN_PATTERN_TEMPLATE = Pattern.compile("\"%s\"\\s*:\\s*(true|false)");
    private static final Pattern ROWS_EXAMINED_PATTERN = Pattern
            .compile("\"rows_examined_per_scan\"\\s*:\\s*\"?([-+0-9.eE]+)\"?");
    private static final Pattern ROWS_PRODUCED_PATTERN = Pattern
            .compile("\"rows_produced_per_join\"\\s*:\\s*\"?([-+0-9.eE]+)\"?");
    private static final Pattern ESTIMATED_ROWS_PATTERN = Pattern
            .compile("\"estimated_rows\"\\s*:\\s*\"?([-+0-9.eE]+)\"?");
    private static final Pattern ANALYZE_NODE_PATTERN = Pattern
            .compile("actual time=([-+0-9.eE]+)\\.\\.([-+0-9.eE]+) rows=([-+0-9.eE]+) loops=([-+0-9.eE]+)");
    private static final DateTimeFormatter CASE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String BUG_REPORT_DIR_PROPERTY = "sqlancer.bugReportDir";
    private static final String BUG_REPORT_DIR_ENV = "SQLANCER_BUG_REPORT_DIR";
    private static final String TIMEOUT_REPORT_DIR_NAME = "timeout_cases";
    private static final AtomicLong CASE_SEQUENCE = new AtomicLong();

    private MySQLPerformanceHintOracleUtils() {
    }

    static String injectHint(String query, String hintSql) {
        if (!query.startsWith(SELECT_PREFIX)) {
            throw new AssertionError(query);
        }
        return SELECT_PREFIX + "/*+ " + hintSql + " */ " + query.substring(SELECT_PREFIX.length());
    }

    static PlanSummary parsePlan(String explainJson) {
        Double queryCost = extractFirstNumber(QUERY_COST_PATTERN, explainJson);
        if (queryCost == null) {
            queryCost = extractMaxNumber(ESTIMATED_TOTAL_COST_PATTERN, explainJson);
        }
        Set<String> keys = extractAllStrings(KEY_PATTERN, explainJson);
        keys.addAll(extractAllStrings(INDEX_NAME_PATTERN, explainJson));
        Set<String> accessTypes = extractAllStrings(ACCESS_TYPE_PATTERN, explainJson);
        Set<String> operations = extractAllStrings(OPERATION_PATTERN, explainJson).stream()
                .map(MySQLPerformanceHintOracleUtils::normalizeOperation)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean usingTemporaryTable = containsTrueFlag(explainJson, "using_temporary_table")
                || containsTrueFlag(explainJson, "temp_table");
        boolean usingDeduplication = containsTrueFlag(explainJson, "deduplication");
        boolean usingFilesort = containsTrueFlag(explainJson, "using_filesort");
        Double rowsExamined = extractMaxNumber(ROWS_EXAMINED_PATTERN, explainJson);
        Double rowsProduced = extractMaxNumber(ROWS_PRODUCED_PATTERN, explainJson);
        Double estimatedRows = extractMaxNumber(ESTIMATED_ROWS_PATTERN, explainJson);
        if (rowsExamined == null) {
            rowsExamined = estimatedRows;
        }
        if (rowsProduced == null) {
            rowsProduced = estimatedRows;
        }
        return new PlanSummary(explainJson, queryCost, keys, accessTypes, operations, usingTemporaryTable,
                usingDeduplication, usingFilesort, rowsExamined, rowsProduced);
    }

    static AnalyzeSummary parseAnalyzeSummary(String analyzeText) {
        Matcher matcher = ANALYZE_NODE_PATTERN.matcher(analyzeText);
        Double maxEndTimeMs = null;
        Double maxRows = null;
        Double maxLoops = null;
        while (matcher.find()) {
            maxEndTimeMs = max(maxEndTimeMs, Double.parseDouble(matcher.group(2)));
            maxRows = max(maxRows, Double.parseDouble(matcher.group(3)));
            maxLoops = max(maxLoops, Double.parseDouble(matcher.group(4)));
        }
        return new AnalyzeSummary(analyzeText, maxEndTimeMs, maxRows, maxLoops);
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

    static boolean isCandidate(PlanSummary defaultPlan, PlanSummary hintedPlan, double costRatioThreshold) {
        return hasStrictlyLowerCost(defaultPlan, hintedPlan)
                && !buildPlanEvidence(defaultPlan, hintedPlan, costRatioThreshold).isEmpty();
    }

    static boolean isPotentialPlanCandidate(PlanSummary defaultPlan, PlanSummary hintedPlan,
            PerformanceNineCellOptions taxonomyOptions) {
        if (defaultPlan.getQueryCost() == null || hintedPlan.getQueryCost() == null) {
            return false;
        }
        return PerformanceNineCellClassifier.isPotentialCostCandidate(defaultPlan.getQueryCost(),
                hintedPlan.getQueryCost(), taxonomyOptions) && hasMeaningfulPlanDifference(defaultPlan, hintedPlan);
    }

    static boolean hasStrictlyLowerCost(PlanSummary defaultPlan, PlanSummary hintedPlan) {
        return defaultPlan.getQueryCost() != null && hintedPlan.getQueryCost() != null
                && hintedPlan.getQueryCost() < defaultPlan.getQueryCost();
    }

    static List<String> buildPlanEvidence(PlanSummary defaultPlan, PlanSummary hintedPlan, double costRatioThreshold) {
        if (!hasStrictlyLowerCost(defaultPlan, hintedPlan)) {
            return List.of();
        }
        List<String> evidence = new ArrayList<>();
        double costRatio = computePositiveRatio(defaultPlan.getQueryCost(), hintedPlan.getQueryCost());
        if (costRatio < costRatioThreshold) {
            return List.of();
        }
        evidence.add(String.format(Locale.US, "cost_ratio=%.4f", costRatio));
        if (hasKeyImprovement(defaultPlan, hintedPlan)) {
            evidence.add("hinted_plan_uses_more_specific_index");
        }
        if (defaultPlan.isUsingTemporaryTable() && !hintedPlan.isUsingTemporaryTable()) {
            evidence.add("hinted_plan_avoids_temporary_table");
        }
        if (defaultPlan.isUsingFilesort() && !hintedPlan.isUsingFilesort()) {
            evidence.add("hinted_plan_avoids_filesort");
        }
        return evidence;
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

    /*
     * Produces the plan-only evidence used by the optimistic counterfactual pipeline. Cost is deliberately recorded
     * rather than filtered here: the cost band is one axis of the final nine-cell outcome, not a precondition for
     * measuring runtime.
     */
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

    /*
     * Strict SQL result equivalence: every projected column and duplicate row multiplicity must agree. This
     * intentionally ignores the legacy result-size-only option because deferred verification protects reports.
     */
    static boolean resultRowsMatch(List<List<String>> baseRows, List<List<String>> counterfactualRows) {
        if (baseRows.size() != counterfactualRows.size()) {
            return false;
        }
        return rowMultiplicity(baseRows).equals(rowMultiplicity(counterfactualRows));
    }

    private static Map<List<String>, Integer> rowMultiplicity(List<List<String>> rows) {
        Map<List<String>, Integer> multiplicities = new HashMap<>();
        for (List<String> row : rows) {
            // ArrayList preserves SQL NULL values, unlike List.copyOf().
            List<String> key = new ArrayList<>(row);
            multiplicities.merge(key, 1, Integer::sum);
        }
        return multiplicities;
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
        if (defaultPlan.isUsingTemporaryTable() && !hintedPlan.isUsingTemporaryTable()) {
            evidence.add("hinted_plan_avoids_temporary_table");
        } else if (!defaultPlan.isUsingTemporaryTable() && hintedPlan.isUsingTemporaryTable()) {
            evidence.add("hinted_plan_adds_temporary_table");
        }
        if (defaultPlan.isUsingFilesort() && !hintedPlan.isUsingFilesort()) {
            evidence.add("hinted_plan_avoids_filesort");
        } else if (!defaultPlan.isUsingFilesort() && hintedPlan.isUsingFilesort()) {
            evidence.add("hinted_plan_adds_filesort");
        }
        if (defaultPlan.isUsingDeduplication() != hintedPlan.isUsingDeduplication()) {
            evidence.add("plan_deduplication_changed");
        }
    }

    static boolean hasMeaningfulPlanDifference(PlanSummary defaultPlan, PlanSummary hintedPlan) {
        return !defaultPlan.getKeys().equals(hintedPlan.getKeys())
                || !defaultPlan.getAccessTypes().equals(hintedPlan.getAccessTypes())
                || !defaultPlan.getOperations().equals(hintedPlan.getOperations())
                || defaultPlan.isUsingTemporaryTable() != hintedPlan.isUsingTemporaryTable()
                || defaultPlan.isUsingDeduplication() != hintedPlan.isUsingDeduplication()
                || defaultPlan.isUsingFilesort() != hintedPlan.isUsingFilesort();
    }

    static String createAssertionMessage(PerformanceHintWitness witness, Path reportDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("Potential PERFORMANCE_HINT issue").append(System.lineSeparator());
        sb.append("Report: ").append(reportDir).append(System.lineSeparator());
        sb.append("Base SQL: ").append(witness.getBaseQuery()).append(System.lineSeparator());
        sb.append("Counterfactual SQL: ").append(witness.getCounterfactualQuery()).append(System.lineSeparator());
        sb.append("Mutation: ").append(witness.getCounterfactualCandidate().getMutationIdentity())
                .append(System.lineSeparator());
        sb.append("Requested strategy: ").append(witness.getRequestedStrategy()).append(System.lineSeparator());
        sb.append("Fallback: ").append(witness.getCounterfactualFallbackReason()).append(System.lineSeparator());
        if (witness.isHintCounterfactual()) {
            sb.append("Hint family/type: ").append(witness.getCounterfactualCandidate().getFamily()).append("/")
                    .append(witness.getCounterfactualCandidate().getMutationId()).append(System.lineSeparator());
        }
        sb.append(String.format(Locale.US, "Default cost: %.4f", witness.getDefaultPlan().getQueryCost()))
                .append(System.lineSeparator());
        sb.append(String.format(Locale.US, "%s cost: %.4f", counterfactualLabel(witness),
                witness.getHintedPlan().getQueryCost())).append(System.lineSeparator());
        sb.append("Default keys: ").append(joinOrNone(witness.getDefaultPlan().getKeys()))
                .append(System.lineSeparator());
        sb.append(counterfactualLabel(witness)).append(" keys: ").append(joinOrNone(witness.getHintedPlan().getKeys()))
                .append(System.lineSeparator());
        if (witness.hasTimeoutObservation()) {
            sb.append("Timeout cap (ms): ").append(String.format(Locale.US, "%.4f", witness.getTimeoutCapMs()))
                    .append(System.lineSeparator());
            sb.append("Default timeout count: ").append(witness.getDefaultTimeoutCount())
                    .append(System.lineSeparator());
            sb.append(counterfactualLabel(witness)).append(" timeout count: ").append(witness.getHintedTimeoutCount())
                    .append(System.lineSeparator());
        }
        if (witness.getDefaultRuntime() != null && witness.getHintedRuntime() != null) {
            sb.append(String.format(Locale.US, "Default median runtime (ms): %.4f",
                    witness.getDefaultRuntime().getMedianMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "%s median runtime (ms): %.4f", counterfactualLabel(witness),
                    witness.getHintedRuntime().getMedianMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "Runtime speedup: %.4f", computePositiveRatio(
                    witness.getDefaultRuntime().getMedianMs(), witness.getHintedRuntime().getMedianMs())))
                    .append(System.lineSeparator());
            sb.append(String.format(Locale.US, "Default average runtime (ms): %.4f",
                    witness.getDefaultRuntime().getMeanMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "%s average runtime (ms): %.4f", counterfactualLabel(witness),
                    witness.getHintedRuntime().getMeanMs())).append(System.lineSeparator());
        }
        if (witness.getNineCellCategory() != null) {
            sb.append("Nine-cell category: ").append(witness.getNineCellCategory().getCode()).append(" (")
                    .append(witness.getNineCellCategory().getSummary()).append(")").append(System.lineSeparator());
        }
        sb.append("Plan evidence: ").append(String.join(", ", witness.getPlanEvidence()))
                .append(System.lineSeparator());
        if (witness.getPlanSignatureKey() != null) {
            sb.append("Plan signature: ").append(witness.getPlanSignatureKey()).append(System.lineSeparator());
        }
        if (witness.getFamilyKey() != null) {
            sb.append("Root-cause family: ").append(witness.getFamilyKey()).append(System.lineSeparator());
        }
        if (witness.getRootCauseSignatureKey() != null) {
            sb.append("Root-cause signature: ").append(witness.getRootCauseSignatureKey())
                    .append(System.lineSeparator());
            sb.append("Root-cause category: ").append(witness.getRootCauseCategory()).append(System.lineSeparator());
            sb.append("Selection reason: ").append(witness.getSelectionReason()).append(System.lineSeparator());
        }
        sb.append("Default EXPLAIN FORMAT=JSON: ").append(System.lineSeparator())
                .append(witness.getDefaultPlan().getExplainJson()).append(System.lineSeparator());
        sb.append("Counterfactual EXPLAIN FORMAT=JSON: ").append(System.lineSeparator())
                .append(witness.getHintedPlan().getExplainJson());
        return sb.toString();
    }

    static Path writeWitness(MySQLGlobalState state, PerformanceHintWitness witness) throws IOException {
        return writeSnapshot(snapshotWitness(state, witness));
    }

    static PerformanceHintReportSnapshot snapshotWitness(MySQLGlobalState state, PerformanceHintWitness witness) {
        String caseId = "case_" + CASE_TIME_FORMAT.format(LocalDateTime.now()) + "_" + state.getState().getSeedValue()
                + "_" + CASE_SEQUENCE.incrementAndGet();
        // B/C are first-class candidates too; all reported cells share one directory layout.
        Path reportRoot = resolveReportRoot();
        if (witness.getNineCellCategory() != null) {
            reportRoot = reportRoot.resolve(witness.getNineCellCategory().getCode());
        }
        Path reportDir = reportRoot.resolve(caseId);
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("setup.sql", state.getState().buildSetupScript());
        if (state.getPdgIrContext() != null) {
            files.put("pdg_ir.yaml", state.getPdgIrContext().yaml());
        }
        files.put("original_query.sql", witness.getBaseQuery() + System.lineSeparator());
        files.put("counterfactual_query.sql", witness.getCounterfactualQuery() + System.lineSeparator());
        files.put("default_explain.json", witness.getDefaultPlan().getExplainJson());
        files.put("counterfactual_explain.json", witness.getHintedPlan().getExplainJson());
        if (witness.isHintCounterfactual()) {
            // Keep the legacy HINT-named artifact contract for existing repro tooling.
            files.put("hinted_query.sql", witness.getHintedQuery() + System.lineSeparator());
            files.put("hinted_explain.json", witness.getHintedPlan().getExplainJson());
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
        Path temporaryDir = Files.createTempDirectory(parent, "." + reportDir.getFileName() + ".tmp-");
        for (Map.Entry<String, String> artifact : snapshot.getFiles().entrySet()) {
            Path output = temporaryDir.resolve(artifact.getKey()).normalize();
            if (!output.startsWith(temporaryDir)) {
                throw new IOException("invalid report artifact path: " + artifact.getKey());
            }
            Path outputParent = output.getParent();
            if (outputParent != null) {
                Files.createDirectories(outputParent);
            }
            writeFile(output, artifact.getValue());
        }
        try {
            Files.move(temporaryDir, reportDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryDir, reportDir);
        }
        return reportDir;
    }

    static Path writeTimeoutObservation(MySQLGlobalState state, TimeoutObservation observation) throws IOException {
        Path reportDir = resolveReportRoot().resolve(TIMEOUT_REPORT_DIR_NAME).resolve(
                "timeout_case_" + CASE_TIME_FORMAT.format(LocalDateTime.now()) + "_" + state.getState().getSeedValue());
        Files.createDirectories(reportDir);
        writeFile(reportDir.resolve("setup.sql"), state.getState().buildSetupScript());
        if (state.getPdgIrContext() != null) {
            writeFile(reportDir.resolve("pdg_ir.yaml"), state.getPdgIrContext().yaml());
        }
        writeFile(reportDir.resolve("original_query.sql"), observation.getBaseQuery() + System.lineSeparator());
        writeFile(reportDir.resolve("counterfactual_query.sql"),
                observation.getCounterfactualQuery() + System.lineSeparator());
        if (observation.getDefaultPlan() != null) {
            writeFile(reportDir.resolve("default_explain.json"), observation.getDefaultPlan().getExplainJson());
        }
        if (observation.getHintedPlan() != null) {
            writeFile(reportDir.resolve("counterfactual_explain.json"), observation.getHintedPlan().getExplainJson());
        }
        if (observation.isHintCounterfactual()) {
            writeFile(reportDir.resolve("hinted_query.sql"), observation.getHintedQuery() + System.lineSeparator());
            if (observation.getHintedPlan() != null) {
                writeFile(reportDir.resolve("hinted_explain.json"), observation.getHintedPlan().getExplainJson());
            }
        }
        writeFile(reportDir.resolve("timeout_metrics.json"), buildTimeoutMetricsJson(state, observation));
        writeFile(reportDir.resolve("timeout_report.md"), buildTimeoutReportMarkdown(state, observation));
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
        if (values.isEmpty()) {
            return "<none>";
        }
        return String.join(", ", values);
    }

    static double computePositiveRatio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || numerator <= 0 || denominator <= 0) {
            return 0;
        }
        return numerator / denominator;
    }

    private static void addRuntimeSampleFiles(Map<String, String> files, List<RuntimeSample> runtimeSamples) {
        for (RuntimeSample runtimeSample : runtimeSamples) {
            String fileName = String.format(Locale.US, "runtime_sample_%d_%s.txt", runtimeSample.getSequence(),
                    runtimeSample.getSide().toLowerCase(Locale.ROOT));
            files.put(fileName, buildRuntimeSampleText(runtimeSample));
        }
    }

    private static String buildRuntimeSampleText(RuntimeSample runtimeSample) {
        StringBuilder sb = new StringBuilder();
        sb.append("sample: ").append(runtimeSample.getSequence()).append(System.lineSeparator());
        sb.append("side: ").append(runtimeSample.getSide()).append(System.lineSeparator());
        sb.append(String.format(Locale.US, "elapsed_ms: %.4f", runtimeSample.getElapsedMs()))
                .append(System.lineSeparator());
        sb.append("timed_out: ").append(runtimeSample.isTimedOut()).append(System.lineSeparator())
                .append(System.lineSeparator());
        sb.append("sql:").append(System.lineSeparator()).append(runtimeSample.getQuery());
        if (!runtimeSample.getQuery().endsWith(System.lineSeparator())) {
            sb.append(System.lineSeparator());
        }
        sb.append(System.lineSeparator()).append("plan:").append(System.lineSeparator())
                .append(runtimeSample.getExplainText());
        if (!runtimeSample.getExplainText().endsWith(System.lineSeparator())) {
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static String buildMetricsJson(MySQLGlobalState state, PerformanceHintWitness witness) {
        List<String> fields = new ArrayList<>();
        fields.add(jsonField("dbms", "mysql"));
        fields.add(jsonField("databaseVersion", state.getState().getDatabaseVersion()));
        fields.add(jsonField("seed", state.getState().getSeedValue()));
        appendNineCellConfiguration(fields, state);
        addDataModelFields(fields, state);
        fields.add(jsonField("baseQuery", witness.getBaseQuery()));
        appendCounterfactualMetadata(fields, witness);
        if (witness.isHintCounterfactual()) {
            // The legacy hint fields intentionally remain HINT-only. A rewrite must
            // never masquerade as a hint to report consumers.
            fields.add(jsonField("hintedQuery", witness.getHintedQuery()));
            fields.add(jsonField("hintFamily", witness.getCounterfactualCandidate().getFamily()));
            fields.add(jsonField("hintType", witness.getCounterfactualCandidate().getMutationId()));
            fields.add(jsonArrayField("tableNames", witness.getCounterfactualCandidate().getTargetTables()));
            fields.add(jsonArrayField("indexNames", witness.getCounterfactualCandidate().getTargetIndexes()));
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
        fields.add(jsonField("queryTimeoutSeconds", witness.getTimeoutCapMs() / 1000.0));
        fields.add(jsonField("timeoutObserved", witness.hasTimeoutObservation()));
        fields.add(jsonField("defaultTimeoutCount", (long) witness.getDefaultTimeoutCount()));
        fields.add(jsonField("counterfactualTimeoutCount", (long) witness.getHintedTimeoutCount()));
        if (witness.isHintCounterfactual()) {
            fields.add(jsonField("hintedTimeoutCount", (long) witness.getHintedTimeoutCount()));
        }
        fields.add(jsonField("runtimeValidated",
                witness.getDefaultRuntime() != null && witness.getHintedRuntime() != null));
        if (witness.getDefaultRuntime() != null && witness.getHintedRuntime() != null) {
            fields.add(jsonField("defaultRuntimeMedianMs", witness.getDefaultRuntime().getMedianMs()));
            fields.add(jsonField("counterfactualRuntimeMedianMs", witness.getHintedRuntime().getMedianMs()));
            fields.add(jsonField("defaultRuntimeAverageMs", witness.getDefaultRuntime().getMeanMs()));
            fields.add(jsonField("counterfactualRuntimeAverageMs", witness.getHintedRuntime().getMeanMs()));
            fields.add(jsonField("runtimeSpeedup", computePositiveRatio(witness.getDefaultRuntime().getMedianMs(),
                    witness.getHintedRuntime().getMedianMs())));
            if (witness.isHintCounterfactual()) {
                fields.add(jsonField("hintedRuntimeMedianMs", witness.getHintedRuntime().getMedianMs()));
                fields.add(jsonField("hintedRuntimeAverageMs", witness.getHintedRuntime().getMeanMs()));
            }
        }
        appendNineCellJsonFields(fields, witness);
        fields.add(jsonArrayField("planEvidence", witness.getPlanEvidence()));
        fields.add(jsonField("rootCauseCategory", witness.getRootCauseCategory()));
        fields.add(jsonField("rootCauseIntentCategory", witness.getRootCauseIntentCategory()));
        fields.add(jsonField("rootCauseMechanismKey", witness.getRootCauseMechanismKey()));
        fields.add(jsonField("rootCauseNoveltyClass", witness.getRootCauseNoveltyClass()));
        fields.add(jsonField("skeletonKey", witness.getSkeletonKey()));
        fields.add("\"queryGeneration\":" + witness.queryGenerationJson());
        fields.add(jsonField("familyKey", witness.getFamilyKey()));
        fields.add(jsonField("planSignatureKey", witness.getPlanSignatureKey()));
        fields.add(jsonField("rootCauseSignatureKey", witness.getRootCauseSignatureKey()));
        fields.add(jsonField("noveltyScore", witness.getNoveltyScore()));
        fields.add(jsonField("rootCauseNoveltyScore", witness.getRootCauseNoveltyScore()));
        fields.add(jsonField("signatureObservationCount", witness.getSignatureObservationCount()));
        fields.add(jsonField("signatureWrittenReports", witness.getSignatureWrittenReports()));
        fields.add(jsonField("skeletonObservationCount", witness.getSkeletonObservationCount()));
        fields.add(jsonField("familyObservationCount", witness.getFamilyObservationCount()));
        fields.add(jsonField("rootCauseObservationCount", witness.getRootCauseObservationCount()));
        fields.add(jsonField("rootCauseWrittenReports", witness.getRootCauseWrittenReports()));
        fields.add(jsonField("feedbackSuppressedDuplicates", witness.getSuppressedDuplicateCount()));
        fields.add(jsonField("feedbackSuppressedByRootCause", witness.getRootCauseSuppressedCount()));
        fields.add(jsonField("selectionReason", witness.getSelectionReason()));
        fields.add(jsonField("rootCauseRepresentativeRank", witness.getRootCauseRepresentativeRank()));
        if (witness.isHintCounterfactual()) {
            fields.add(jsonField("hintPolicyDecision", witness.getHintPolicyDecision()));
            fields.add(jsonField("hintFamilyQuotaApplied", witness.getHintFamilyQuotaApplied()));
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
        fields.add(jsonField("rootCauseNoveltyClass", witness.getRootCauseNoveltyClass()));
        appendCounterfactualMetadata(fields, witness);
        if (witness.isHintCounterfactual()) {
            fields.add(jsonField("hintType", witness.getCounterfactualCandidate().getMutationId()));
        }
        appendNineCellJsonFields(fields, witness);
        fields.add(jsonArrayField("planEvidence", witness.getPlanEvidence()));
        fields.add(jsonField("noveltyScore", witness.getNoveltyScore()));
        fields.add(jsonField("rootCauseNoveltyScore", witness.getRootCauseNoveltyScore()));
        fields.add(jsonField("runtimeSpeedup", witness.getRuntimeSpeedup()));
        fields.add(jsonField("costRatio", witness.getCostRatio()));
        fields.add(jsonField("signatureObservationCount", witness.getSignatureObservationCount()));
        fields.add(jsonField("signatureWrittenReports", witness.getSignatureWrittenReports()));
        fields.add(jsonField("skeletonObservationCount", witness.getSkeletonObservationCount()));
        fields.add(jsonField("familyObservationCount", witness.getFamilyObservationCount()));
        fields.add(jsonField("rootCauseObservationCount", witness.getRootCauseObservationCount()));
        fields.add(jsonField("rootCauseWrittenReports", witness.getRootCauseWrittenReports()));
        fields.add(jsonField("selectionReason", witness.getSelectionReason()));
        fields.add(jsonField("rootCauseRepresentativeRank", witness.getRootCauseRepresentativeRank()));
        if (witness.isHintCounterfactual()) {
            fields.add(jsonField("hintPolicyDecision", witness.getHintPolicyDecision()));
            fields.add(jsonField("hintFamilyQuotaApplied", witness.getHintFamilyQuotaApplied()));
        }
        return "{\n  " + String.join(",\n  ", fields) + "\n}\n";
    }

    private static String buildTimeoutMetricsJson(MySQLGlobalState state, TimeoutObservation observation) {
        List<String> fields = new ArrayList<>();
        fields.add(jsonField("dbms", "mysql"));
        fields.add(jsonField("databaseVersion", state.getState().getDatabaseVersion()));
        fields.add(jsonField("seed", state.getState().getSeedValue()));
        addDataModelFields(fields, state);
        fields.add(jsonField("baseQuery", observation.getBaseQuery()));
        appendCounterfactualMetadata(fields, observation);
        if (observation.isHintCounterfactual()) {
            fields.add(jsonField("hintedQuery", observation.getHintedQuery()));
            fields.add(jsonField("hintFamily", observation.getCounterfactualCandidate().getFamily()));
            fields.add(jsonField("hintType", observation.getCounterfactualCandidate().getMutationId()));
        }
        fields.add(jsonField("phase", observation.getPhase()));
        fields.add(jsonField("timedOutSide", observation.getTimedOutSide()));
        fields.add(jsonField("queryTimeoutSeconds", observation.getTimeoutCapMs() / 1000.0));
        fields.add(jsonField("recordedRuntimeMs", observation.getRecordedRuntimeMs()));
        if (observation.getDefaultPlan() != null) {
            fields.add(jsonField("defaultEstimatedCost", observation.getDefaultPlan().getQueryCost()));
        }
        if (observation.getHintedPlan() != null) {
            fields.add(jsonField("counterfactualEstimatedCost", observation.getHintedPlan().getQueryCost()));
            if (observation.isHintCounterfactual()) {
                fields.add(jsonField("hintedEstimatedCost", observation.getHintedPlan().getQueryCost()));
            }
        }
        return "{\n  " + String.join(",\n  ", fields) + "\n}\n";
    }

    private static String buildReportMarkdown(MySQLGlobalState state, PerformanceHintWitness witness) {
        StringBuilder sb = new StringBuilder();
        sb.append("# PERFORMANCE_HINT Witness").append(System.lineSeparator()).append(System.lineSeparator());
        sb.append("- DBMS: mysql ").append(state.getState().getDatabaseVersion()).append(System.lineSeparator());
        sb.append("- Seed: ").append(state.getState().getSeedValue()).append(System.lineSeparator());
        sb.append("- Nine-cell cost-similarity interval (C/C'): [")
                .append(state.getDbmsSpecificOptions().performanceTaxonomyCostRatioLowerBound).append(", ")
                .append(state.getDbmsSpecificOptions().performanceTaxonomyCostRatioUpperBound).append("]")
                .append(System.lineSeparator());
        sb.append("- Nine-cell runtime-similarity interval (T/T'): [")
                .append(state.getDbmsSpecificOptions().performanceTaxonomyRuntimeRatioLowerBound).append(", ")
                .append(state.getDbmsSpecificOptions().performanceTaxonomyRuntimeRatioUpperBound).append("]")
                .append(System.lineSeparator());
        sb.append("- Harmful-runtime report threshold (T/T'): ")
                .append(state.getDbmsSpecificOptions().performanceReportHarmfulRuntimeRatio)
                .append(System.lineSeparator());
        sb.append("- Cost-anomaly report threshold (C/C'): ")
                .append(state.getDbmsSpecificOptions().performanceReportCostAnomalyRatio)
                .append(System.lineSeparator());
        appendDataModelMarkdown(sb, state);
        appendCounterfactualMarkdownMetadata(sb, witness);
        sb.append("- Root cause category: ").append(witness.getRootCauseCategory()).append(System.lineSeparator());
        sb.append("- Root cause intent category: ").append(witness.getRootCauseIntentCategory())
                .append(System.lineSeparator());
        sb.append("- Root cause mechanism key: ").append(witness.getRootCauseMechanismKey())
                .append(System.lineSeparator());
        sb.append("- Root cause novelty class: ").append(witness.getRootCauseNoveltyClass())
                .append(System.lineSeparator());
        if (witness.getNineCellCategory() != null) {
            sb.append("- Nine-cell category: ").append(witness.getNineCellCategory().getCode()).append(" (")
                    .append(witness.getNineCellCategory().getSummary()).append(")").append(System.lineSeparator());
        }
        sb.append("- Selection reason: ").append(witness.getSelectionReason()).append(System.lineSeparator());
        if (witness.isHintCounterfactual()) {
            sb.append("- Hint policy decision: ").append(witness.getHintPolicyDecision()).append(System.lineSeparator())
                    .append("- Hint family quota applied: ").append(witness.getHintFamilyQuotaApplied())
                    .append(System.lineSeparator());
        }
        sb.append(System.lineSeparator());
        if (witness.getPlanSignatureKey() != null) {
            sb.append("- Plan signature: ").append(witness.getPlanSignatureKey()).append(System.lineSeparator());
            sb.append("- Root-cause family: ").append(witness.getFamilyKey()).append(System.lineSeparator());
            sb.append("- Root-cause signature: ").append(witness.getRootCauseSignatureKey())
                    .append(System.lineSeparator());
            sb.append("- Novelty score: ").append(formatNullableDouble(witness.getNoveltyScore()))
                    .append(System.lineSeparator());
            sb.append("- Root-cause novelty score: ").append(formatNullableDouble(witness.getRootCauseNoveltyScore()))
                    .append(System.lineSeparator());
            sb.append("- Signature observations: ").append(witness.getSignatureObservationCount())
                    .append(System.lineSeparator()).append("- Root-cause observations: ")
                    .append(witness.getRootCauseObservationCount()).append(System.lineSeparator())
                    .append(System.lineSeparator());
        }
        if (witness.hasTimeoutObservation()) {
            sb.append("- Timeout cap (ms): ").append(String.format(Locale.US, "%.4f", witness.getTimeoutCapMs()))
                    .append(System.lineSeparator());
            sb.append("- Default timeout count: ").append(witness.getDefaultTimeoutCount())
                    .append(System.lineSeparator());
            sb.append("- ").append(counterfactualLabel(witness)).append(" timeout count: ")
                    .append(witness.getHintedTimeoutCount()).append(System.lineSeparator())
                    .append(System.lineSeparator());
        }
        sb.append("## Original Query").append(System.lineSeparator()).append("```sql").append(System.lineSeparator())
                .append(witness.getBaseQuery()).append(System.lineSeparator()).append("```")
                .append(System.lineSeparator()).append(System.lineSeparator());
        sb.append("## ").append(counterfactualLabel(witness)).append(" Query").append(System.lineSeparator())
                .append("```sql").append(System.lineSeparator()).append(witness.getCounterfactualQuery())
                .append(System.lineSeparator()).append("```").append(System.lineSeparator())
                .append(System.lineSeparator());
        sb.append("## Cost Comparison").append(System.lineSeparator());
        sb.append(String.format(Locale.US, "- Default cost: %.4f", witness.getDefaultPlan().getQueryCost()))
                .append(System.lineSeparator());
        sb.append(String.format(Locale.US, "- %s cost: %.4f", counterfactualLabel(witness),
                witness.getHintedPlan().getQueryCost())).append(System.lineSeparator());
        sb.append(String.format(Locale.US, "- Default/hinted cost ratio: %.4f", witness.getCostRatio()))
                .append(System.lineSeparator());
        sb.append(String.format(Locale.US, "- %s/default cost ratio: %.4f", counterfactualLabel(witness),
                witness.getHigherCostRatio())).append(System.lineSeparator());
        sb.append("- Plan evidence: ").append(String.join(", ", witness.getPlanEvidence()))
                .append(System.lineSeparator()).append(System.lineSeparator());
        if (witness.getDefaultRuntime() != null && witness.getHintedRuntime() != null) {
            sb.append("## Runtime Comparison").append(System.lineSeparator());
            sb.append(String.format(Locale.US, "- Default median runtime (ms): %.4f",
                    witness.getDefaultRuntime().getMedianMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "- %s median runtime (ms): %.4f", counterfactualLabel(witness),
                    witness.getHintedRuntime().getMedianMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "- Runtime speedup: %.4f", computePositiveRatio(
                    witness.getDefaultRuntime().getMedianMs(), witness.getHintedRuntime().getMedianMs())))
                    .append(System.lineSeparator());
            sb.append(String.format(Locale.US, "- Default average runtime (ms): %.4f",
                    witness.getDefaultRuntime().getMeanMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "- %s average runtime (ms): %.4f", counterfactualLabel(witness),
                    witness.getHintedRuntime().getMeanMs())).append(System.lineSeparator())
                    .append(String.format(Locale.US, "- %s/default runtime ratio: %.4f", counterfactualLabel(witness),
                            witness.getRuntimeRegressionRatio()))
                    .append(System.lineSeparator())
                    .append(String.format(Locale.US, "- Absolute median runtime gap (ms): %.4f",
                            witness.getAbsoluteRuntimeGapMs()))
                    .append(System.lineSeparator()).append(System.lineSeparator());
        }
        if (witness.getDefaultAnalyze() != null && witness.getHintedAnalyze() != null) {
            sb.append("## EXPLAIN ANALYZE Summary").append(System.lineSeparator());
            sb.append("- Default max actual time (ms): ")
                    .append(formatNullableDouble(witness.getDefaultAnalyze().getMaxActualTimeMs()))
                    .append(System.lineSeparator());
            sb.append("- ").append(counterfactualLabel(witness)).append(" max actual time (ms): ")
                    .append(formatNullableDouble(witness.getHintedAnalyze().getMaxActualTimeMs()))
                    .append(System.lineSeparator()).append(System.lineSeparator());
        }
        sb.append("## Reproduction").append(System.lineSeparator());
        sb.append("1. Re-run SQLancer with `mysql --oracle PERFORMANCE_HINT`.").append(System.lineSeparator());
        sb.append("2. Execute `original_query.sql` and `counterfactual_query.sql` on the generated database.")
                .append(System.lineSeparator());
        sb.append("3. Compare `default_explain.json` and `counterfactual_explain.json`.")
                .append(System.lineSeparator());
        return sb.toString();
    }

    private static String buildTimeoutReportMarkdown(MySQLGlobalState state, TimeoutObservation observation) {
        StringBuilder sb = new StringBuilder();
        sb.append("# PERFORMANCE_HINT Timeout Observation").append(System.lineSeparator())
                .append(System.lineSeparator());
        sb.append("- DBMS: mysql ").append(state.getState().getDatabaseVersion()).append(System.lineSeparator());
        sb.append("- Seed: ").append(state.getState().getSeedValue()).append(System.lineSeparator());
        appendDataModelMarkdown(sb, state);
        appendCounterfactualMarkdownMetadata(sb, observation);
        sb.append("- Phase: ").append(observation.getPhase()).append(System.lineSeparator());
        sb.append("- Timed out side: ").append(observation.getTimedOutSide()).append(System.lineSeparator());
        sb.append("- Timeout cap (ms): ").append(String.format(Locale.US, "%.4f", observation.getTimeoutCapMs()))
                .append(System.lineSeparator());
        sb.append("- Recorded runtime (ms): ")
                .append(String.format(Locale.US, "%.4f", observation.getRecordedRuntimeMs()))
                .append(System.lineSeparator()).append(System.lineSeparator());
        sb.append("## Original Query").append(System.lineSeparator()).append("```sql").append(System.lineSeparator())
                .append(observation.getBaseQuery()).append(System.lineSeparator()).append("```")
                .append(System.lineSeparator()).append(System.lineSeparator());
        sb.append("## ").append(counterfactualLabel(observation)).append(" Query").append(System.lineSeparator())
                .append("```sql").append(System.lineSeparator()).append(observation.getCounterfactualQuery())
                .append(System.lineSeparator()).append("```").append(System.lineSeparator());
        return sb.toString();
    }

    private static void addDataModelFields(List<String> fields, MySQLGlobalState state) {
        fields.add(jsonField("generatorMode", state.getDbmsSpecificOptions().performanceDataGeneratorMode));
        fields.add(jsonField("dataGeneratorMode", state.getDbmsSpecificOptions().performanceDataGeneratorMode));
        fields.add(jsonField("dataAblationProfile",
                state.getDbmsSpecificOptions().performanceDataAblationProfile.getId()));
        fields.add(jsonField("initialScale", state.getDbmsSpecificOptions().performanceInitialScale));
        if (state.getPdgIrContext() != null) {
            fields.add(jsonArrayField("pdgPrimitives",
                    state.getPdgIrContext().getSpecification().getTriggeredPrimitives()));
        }
        if (state.getPerformanceDataModelDescription() != null) {
            fields.add(jsonField("performanceDataModelDescription", state.getPerformanceDataModelDescription()));
            fields.add(jsonField("dataModelPlan", state.getPerformanceDataModelDescription()));
        }
    }

    private static void appendNineCellConfiguration(List<String> fields, MySQLGlobalState state) {
        fields.add(jsonField("nineCellCostRatioLowerBound",
                state.getDbmsSpecificOptions().performanceTaxonomyCostRatioLowerBound));
        fields.add(jsonField("nineCellCostRatioUpperBound",
                state.getDbmsSpecificOptions().performanceTaxonomyCostRatioUpperBound));
        fields.add(jsonField("nineCellRuntimeRatioLowerBound",
                state.getDbmsSpecificOptions().performanceTaxonomyRuntimeRatioLowerBound));
        fields.add(jsonField("nineCellRuntimeRatioUpperBound",
                state.getDbmsSpecificOptions().performanceTaxonomyRuntimeRatioUpperBound));
        fields.add(jsonField("reportHarmfulRuntimeRatio",
                state.getDbmsSpecificOptions().performanceReportHarmfulRuntimeRatio));
        fields.add(
                jsonField("reportCostAnomalyRatio", state.getDbmsSpecificOptions().performanceReportCostAnomalyRatio));
        fields.add(jsonField("nineCellMinRuntimeMedianMs",
                state.getDbmsSpecificOptions().performanceTaxonomyMinRuntimeMedianMs));
        fields.add(jsonField("nineCellMinRuntimeAbsGapMs",
                state.getDbmsSpecificOptions().performanceTaxonomyMinRuntimeAbsGapMs));
        fields.add(jsonField("nineCellReportCells", state.getDbmsSpecificOptions().performanceTaxonomyReportCells));
    }

    private static void appendCounterfactualMetadata(List<String> fields, PerformanceHintWitness witness) {
        CounterfactualCandidate candidate = witness.getCounterfactualCandidate();
        fields.add(jsonField("mutationKind", candidate.getKind().name()));
        fields.add(jsonField("mutationFamily", candidate.getFamily()));
        fields.add(jsonField("mutationId", candidate.getMutationId()));
        fields.add(jsonField("mutationDescriptor", candidate.getDescriptor()));
        fields.add(jsonField("counterfactualQuery", witness.getCounterfactualQuery()));
        fields.add(jsonField("requestedStrategy", witness.getRequestedStrategy()));
        fields.add(jsonField("fallbackReason", witness.getCounterfactualFallbackReason()));
        fields.add(jsonArrayField("targetTables", candidate.getTargetTables()));
        fields.add(jsonArrayField("targetIndexes", candidate.getTargetIndexes()));
    }

    private static void appendCounterfactualMetadata(List<String> fields, TimeoutObservation observation) {
        CounterfactualCandidate candidate = observation.getCounterfactualCandidate();
        fields.add(jsonField("mutationKind", candidate.getKind().name()));
        fields.add(jsonField("mutationFamily", candidate.getFamily()));
        fields.add(jsonField("mutationId", candidate.getMutationId()));
        fields.add(jsonField("mutationDescriptor", candidate.getDescriptor()));
        fields.add(jsonField("counterfactualQuery", observation.getCounterfactualQuery()));
        fields.add(jsonField("requestedStrategy", observation.getRequestedStrategy()));
        fields.add(jsonField("fallbackReason", observation.getCounterfactualFallbackReason()));
        fields.add(jsonArrayField("targetTables", candidate.getTargetTables()));
        fields.add(jsonArrayField("targetIndexes", candidate.getTargetIndexes()));
    }

    private static void appendCounterfactualMarkdownMetadata(StringBuilder sb, PerformanceHintWitness witness) {
        CounterfactualCandidate candidate = witness.getCounterfactualCandidate();
        sb.append("- Mutation kind: ").append(candidate.getKind()).append(System.lineSeparator());
        sb.append("- Mutation family: ").append(candidate.getFamily()).append(System.lineSeparator());
        sb.append("- Mutation ID: ").append(candidate.getMutationId()).append(System.lineSeparator());
        sb.append("- Mutation descriptor: ").append(candidate.getDescriptor()).append(System.lineSeparator());
        sb.append("- Requested strategy: ").append(witness.getRequestedStrategy()).append(System.lineSeparator());
        sb.append("- Fallback: ").append(witness.getCounterfactualFallbackReason()).append(System.lineSeparator());
        if (witness.isHintCounterfactual()) {
            sb.append("- Hint family/type: ").append(candidate.getFamily()).append("/")
                    .append(candidate.getMutationId()).append(System.lineSeparator());
        }
    }

    private static void appendCounterfactualMarkdownMetadata(StringBuilder sb, TimeoutObservation observation) {
        CounterfactualCandidate candidate = observation.getCounterfactualCandidate();
        sb.append("- Mutation kind: ").append(candidate.getKind()).append(System.lineSeparator());
        sb.append("- Mutation family: ").append(candidate.getFamily()).append(System.lineSeparator());
        sb.append("- Mutation ID: ").append(candidate.getMutationId()).append(System.lineSeparator());
        sb.append("- Mutation descriptor: ").append(candidate.getDescriptor()).append(System.lineSeparator());
        sb.append("- Requested strategy: ").append(observation.getRequestedStrategy()).append(System.lineSeparator());
        sb.append("- Fallback: ").append(observation.getCounterfactualFallbackReason()).append(System.lineSeparator());
        if (observation.isHintCounterfactual()) {
            sb.append("- Hint family/type: ").append(candidate.getFamily()).append("/")
                    .append(candidate.getMutationId()).append(System.lineSeparator());
        }
    }

    private static String counterfactualLabel(PerformanceHintWitness witness) {
        return witness.isHintCounterfactual() ? "Hinted" : "Counterfactual";
    }

    private static String counterfactualLabel(TimeoutObservation observation) {
        return observation.isHintCounterfactual() ? "Hinted" : "Counterfactual";
    }

    private static void appendNineCellJsonFields(List<String> fields, PerformanceHintWitness witness) {
        if (witness.getNineCellCategory() == null) {
            return;
        }
        fields.add(jsonField("nineCellCategory", witness.getNineCellCategory().getCode()));
        fields.add(jsonField("nineCellSummary", witness.getNineCellCategory().getSummary()));
        fields.add(jsonField("costRatio", witness.getCostRatio()));
        fields.add(jsonField("higherCostRatio", witness.getHigherCostRatio()));
        fields.add(jsonField("runtimeRatio", witness.getRuntimeSpeedup()));
        fields.add(jsonField("runtimeRegressionRatio", witness.getRuntimeRegressionRatio()));
        fields.add(jsonField("absoluteRuntimeGapMs", witness.getAbsoluteRuntimeGapMs()));
    }

    private static void appendDataModelMarkdown(StringBuilder sb, MySQLGlobalState state) {
        sb.append("- Data generator: ").append(state.getDbmsSpecificOptions().performanceDataGeneratorMode)
                .append(System.lineSeparator());
        sb.append("- Data ablation profile: ")
                .append(state.getDbmsSpecificOptions().performanceDataAblationProfile.getId())
                .append(System.lineSeparator());
        if (state.getPerformanceDataModelDescription() != null) {
            sb.append("- Data model plan: ").append(state.getPerformanceDataModelDescription())
                    .append(System.lineSeparator());
        }
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String jsonField(String key, String value) {
        return "\"" + escapeJson(key) + "\": \"" + escapeJson(value) + "\"";
    }

    private static String jsonField(String key, long value) {
        return "\"" + escapeJson(key) + "\": " + value;
    }

    private static String jsonField(String key, boolean value) {
        return "\"" + escapeJson(key) + "\": " + value;
    }

    private static String jsonField(String key, Double value) {
        if (value == null) {
            return "\"" + escapeJson(key) + "\": null";
        }
        return "\"" + escapeJson(key) + "\": " + String.format(Locale.US, "%.6f", value);
    }

    private static String jsonArrayField(String key, List<String> values) {
        String jsonArray = values.stream().map(value -> "\"" + escapeJson(value) + "\"")
                .collect(Collectors.joining(", "));
        return "\"" + escapeJson(key) + "\": [" + jsonArray + "]";
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
            case '\\':
                sb.append("\\\\");
                break;
            case '"':
                sb.append("\\\"");
                break;
            case '\n':
                sb.append("\\n");
                break;
            case '\r':
                sb.append("\\r");
                break;
            case '\t':
                sb.append("\\t");
                break;
            case '\b':
                sb.append("\\b");
                break;
            case '\f':
                sb.append("\\f");
                break;
            default:
                if (current < 0x20) {
                    sb.append(String.format(Locale.US, "\\u%04x", (int) current));
                } else {
                    sb.append(current);
                }
                break;
            }
        }
        return sb.toString();
    }

    private static String formatNullableDouble(Double value) {
        if (value == null) {
            return "<unavailable>";
        }
        return String.format(Locale.US, "%.4f", value);
    }

    private static Double extractFirstNumber(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return Double.parseDouble(matcher.group(1));
    }

    private static Set<String> extractAllStrings(Pattern pattern, String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static Double extractMaxNumber(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        Double max = null;
        while (matcher.find()) {
            max = max(max, Double.parseDouble(matcher.group(1)));
        }
        return max;
    }

    private static Double max(Double left, Double right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    private static boolean hasKeyImprovement(PlanSummary defaultPlan, PlanSummary hintedPlan) {
        if (hintedPlan.getKeys().isEmpty()) {
            return false;
        }
        if (defaultPlan.getKeys().isEmpty()) {
            return true;
        }
        if (defaultPlan.getAccessTypes().contains("ALL") && !hintedPlan.getAccessTypes().contains("ALL")) {
            return true;
        }
        return !hintedPlan.getKeys().equals(defaultPlan.getKeys());
    }

    static String buildSkeletonKey(String baseQuery, int tableCount) {
        String upperQuery = baseQuery.toUpperCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        parts.add(extractSelectFlavor(upperQuery));
        parts.add("TABLES_" + tableCount);
        appendIfPresent(parts, upperQuery, "WHERE", "WHERE");
        appendIfPresent(parts, upperQuery, "GROUP BY", "GROUP_BY");
        appendIfPresent(parts, upperQuery, "HAVING", "HAVING");
        appendIfPresent(parts, upperQuery, "ORDER BY", "ORDER_BY");
        appendIfPresent(parts, upperQuery, "LIMIT", "LIMIT");
        appendIfPresent(parts, upperQuery, "STRAIGHT_JOIN", "STRAIGHT_JOIN");
        appendIfPresent(parts, upperQuery, "NATURAL JOIN", "NATURAL_JOIN");
        appendIfPresent(parts, upperQuery, "LEFT JOIN", "LEFT_JOIN");
        appendIfPresent(parts, upperQuery, "RIGHT JOIN", "RIGHT_JOIN");
        appendIfPresent(parts, upperQuery, "CROSS JOIN", "CROSS_JOIN");
        appendIfPresent(parts, upperQuery, " INNER JOIN ", "INNER_JOIN");
        if (sqlancer.common.perfhint.PerformanceSqlLexical.hasCommaFrom(baseQuery)) {
            parts.add("COMMA_JOIN");
        }
        return String.join("|", parts);
    }

    static String buildFamilyKey(PerformanceHintWitness witness) {
        List<String> parts = new ArrayList<>();
        parts.add("MUTATION=" + witness.getCounterfactualCandidate().getMutationIdentity());
        parts.add(normalizeSkeletonFlavor(witness.getSkeletonKey()));
        parts.add("DEFAULT=" + planShapeKey(witness.getDefaultPlan(), true));
        parts.add("COUNTERFACTUAL=" + planShapeKey(witness.getHintedPlan(), false));
        List<String> evidence = witness.getPlanEvidence().stream().filter(item -> !item.startsWith("cost_ratio="))
                .sorted().collect(Collectors.toList());
        if (!evidence.isEmpty()) {
            parts.add("DELTA=" + String.join("+", evidence));
        }
        return String.join("|", parts);
    }

    static String buildPlanSignatureKey(PerformanceHintWitness witness) {
        List<String> parts = new ArrayList<>();
        parts.add("MUTATION=" + witness.getCounterfactualCandidate().getMutationIdentity());
        parts.add(normalizeSkeletonFlavor(witness.getSkeletonKey()));
        parts.add("DEFAULT=" + planShapeKey(witness.getDefaultPlan(), true));
        parts.add("COUNTERFACTUAL=" + planShapeKey(witness.getHintedPlan(), false));
        return String.join("|", parts);
    }

    /*
     * Returns the pre-counterfactual feedback identity for a HINT witness. It is deliberately retained only for state
     * migration; new artifacts and state always use {@link #buildFamilyKey(PerformanceHintWitness)}.
     */
    static String buildLegacyHintFamilyKey(PerformanceHintWitness witness) {
        if (!witness.isHintCounterfactual()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add(witness.getCounterfactualCandidate().getMutationId());
        parts.add(normalizeSkeletonFlavor(witness.getSkeletonKey()));
        parts.add("DEFAULT=" + planShapeKey(witness.getDefaultPlan(), true));
        parts.add("HINTED=" + planShapeKey(witness.getHintedPlan(), false));
        List<String> evidence = witness.getPlanEvidence().stream().filter(item -> !item.startsWith("cost_ratio="))
                .sorted().collect(Collectors.toList());
        if (!evidence.isEmpty()) {
            parts.add("DELTA=" + String.join("+", evidence));
        }
        return String.join("|", parts);
    }

    static String buildLegacyHintPlanSignatureKey(PerformanceHintWitness witness) {
        if (!witness.isHintCounterfactual()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add(witness.getCounterfactualCandidate().getMutationId());
        parts.add(normalizeSkeletonFlavor(witness.getSkeletonKey()));
        parts.add("DEFAULT=" + planShapeKey(witness.getDefaultPlan(), true));
        parts.add("HINTED=" + planShapeKey(witness.getHintedPlan(), false));
        return String.join("|", parts);
    }

    static String detectRootCauseCategory(String baseQuery) {
        return PerformanceHintRootCauseClassifier.detectIntentCategory(baseQuery);
    }

    static String buildRootCauseSignatureKey(PerformanceHintWitness witness) {
        return classifyRootCause(witness).getSignatureKey();
    }

    static RootCauseClassification classifyRootCause(PerformanceHintWitness witness) {
        return PerformanceHintRootCauseClassifier.classify(witness.getBaseQuery(), witness.getCounterfactualCandidate(),
                witness.getDefaultPlan(), witness.getHintedPlan());
    }

    static String buildLegacyHintRootCauseSignatureKey(PerformanceHintWitness witness) {
        if (!witness.isHintCounterfactual()) {
            return null;
        }
        return PerformanceHintRootCauseClassifier.classify(witness.getBaseQuery(),
                witness.getCounterfactualCandidate().getFamily(), witness.getCounterfactualCandidate().getMutationId(),
                witness.getDefaultPlan(), witness.getHintedPlan()).getSignatureKey();
    }

    private static void appendIfPresent(List<String> parts, String query, String needle, String token) {
        if (query.contains(needle)) {
            parts.add(token);
        }
    }

    private static String extractSelectFlavor(String upperQuery) {
        if (upperQuery.startsWith("SELECT DISTINCTROW ")) {
            return "SELECT_DISTINCTROW";
        }
        if (upperQuery.startsWith("SELECT DISTINCT ")) {
            return "SELECT_DISTINCT";
        }
        if (upperQuery.startsWith("SELECT ALL ")) {
            return "SELECT_ALL";
        }
        return "SELECT";
    }

    private static String planShapeKey(PlanSummary plan, boolean defaultPlan) {
        List<String> parts = new ArrayList<>();
        parts.add(defaultPlan ? "D" : "H");
        if (!plan.getOperations().isEmpty()) {
            parts.add("OPS=" + String.join("+", plan.getOperations().stream().sorted().collect(Collectors.toList())));
        }
        if (!plan.getAccessTypes().isEmpty()) {
            parts.add(
                    "ACCESS=" + String.join("+", plan.getAccessTypes().stream().sorted().collect(Collectors.toList())));
        }
        if (plan.isUsingTemporaryTable()) {
            parts.add("TEMP");
        }
        if (plan.isUsingDeduplication()) {
            parts.add("DEDUP");
        }
        if (plan.isUsingFilesort()) {
            parts.add("FILESORT");
        }
        return String.join(",", parts);
    }

    private static String normalizeOperation(String operation) {
        String lower = operation.toLowerCase(Locale.ROOT);
        if (lower.contains("hash join")) {
            return "HASH_JOIN";
        }
        if (lower.contains("nested loop")) {
            return "NESTED_LOOP";
        }
        if (lower.contains("limit")) {
            return "LIMIT_SMALL";
        }
        if (lower.contains("stream results")) {
            return "STREAM_RESULTS";
        }
        if (lower.contains("group")) {
            return "GROUP_NO_AGGREGATES";
        }
        if (lower.contains("filter")) {
            return "FILTER";
        }
        if (lower.contains("temporary table") && lower.contains("dedup")) {
            return "TEMP_DEDUP";
        }
        if (lower.contains("temporary table")) {
            return "TEMP_TABLE";
        }
        if (lower.contains("sort")) {
            return "SORT";
        }
        if (lower.contains("index")) {
            return "INDEX_ACCESS";
        }
        if (lower.contains("table scan")) {
            return "TABLE_SCAN";
        }
        if (lower.contains("rows fetched before execution")) {
            return "ROWS_FETCHED";
        }
        return operation.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static String normalizeSkeletonFlavor(String skeletonKey) {
        return skeletonKey.replace("SELECT_DISTINCTROW", "SELECT_DISTINCT");
    }

    private static boolean containsTrueFlag(String explainJson, String keyName) {
        Pattern pattern = Pattern.compile(String.format(BOOLEAN_PATTERN_TEMPLATE.pattern(), keyName));
        Matcher matcher = pattern.matcher(explainJson);
        while (matcher.find()) {
            if ("true".equalsIgnoreCase(matcher.group(1))) {
                return true;
            }
        }
        return false;
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

        private PlanSummary(String explainJson, Double queryCost, Set<String> keys, Set<String> accessTypes,
                Set<String> operations, boolean usingTemporaryTable, boolean usingDeduplication, boolean usingFilesort,
                Double rowsExaminedPerScan, Double rowsProducedPerJoin) {
            this.explainJson = explainJson;
            this.queryCost = queryCost;
            this.keys = keys;
            this.accessTypes = accessTypes;
            this.operations = operations;
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

    static final class RuntimeSummary {
        private final List<Double> runsMs;
        private final double medianMs;
        private final double meanMs;
        private final double minMs;
        private final double maxMs;
        private final int timeoutCount;

        RuntimeSummary(List<Double> runsMs) {
            this(runsMs, 0);
        }

        RuntimeSummary(List<Double> runsMs, int timeoutCount) {
            this.runsMs = List.copyOf(runsMs);
            this.medianMs = median(runsMs);
            this.meanMs = runsMs.stream().collect(Collectors.averagingDouble(Double::doubleValue));
            this.minMs = runsMs.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            this.maxMs = runsMs.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            this.timeoutCount = timeoutCount;
        }

        List<Double> getRunsMs() {
            return runsMs;
        }

        double getMedianMs() {
            return medianMs;
        }

        double getMeanMs() {
            return meanMs;
        }

        double getMinMs() {
            return minMs;
        }

        double getMaxMs() {
            return maxMs;
        }

        int getTimeoutCount() {
            return timeoutCount;
        }
    }

    static final class RuntimeSample {
        private final int sequence;
        private final String side;
        private final String query;
        private final String explainText;
        private final double elapsedMs;
        private final boolean timedOut;

        RuntimeSample(int sequence, String side, String query, String explainText, double elapsedMs, boolean timedOut) {
            this.sequence = sequence;
            this.side = side;
            this.query = query;
            this.explainText = explainText;
            this.elapsedMs = elapsedMs;
            this.timedOut = timedOut;
        }

        int getSequence() {
            return sequence;
        }

        String getSide() {
            return side;
        }

        String getQuery() {
            return query;
        }

        String getExplainText() {
            return explainText;
        }

        double getElapsedMs() {
            return elapsedMs;
        }

        boolean isTimedOut() {
            return timedOut;
        }
    }

    static final class AnalyzeSummary {
        private final String analyzeText;
        private final Double maxActualTimeMs;
        private final Double maxRows;
        private final Double maxLoops;

        private AnalyzeSummary(String analyzeText, Double maxActualTimeMs, Double maxRows, Double maxLoops) {
            this.analyzeText = analyzeText;
            this.maxActualTimeMs = maxActualTimeMs;
            this.maxRows = maxRows;
            this.maxLoops = maxLoops;
        }

        String getAnalyzeText() {
            return analyzeText;
        }

        Double getMaxActualTimeMs() {
            return maxActualTimeMs;
        }

        Double getMaxRows() {
            return maxRows;
        }

        Double getMaxLoops() {
            return maxLoops;
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
        private String requestedStrategy;
        private String counterfactualFallbackReason;
        private RuntimeSummary defaultRuntime;
        private RuntimeSummary hintedRuntime;
        private AnalyzeSummary defaultAnalyze;
        private AnalyzeSummary hintedAnalyze;
        private List<RuntimeSample> runtimeSamples = List.of();
        private int defaultTimeoutCount;
        private int hintedTimeoutCount;
        private double timeoutCapMs;
        private String skeletonKey;
        private String familyKey;
        private String planSignatureKey;
        private String rootCauseSignatureKey;
        private String rootCauseCategory = "PLAIN_TOPN";
        private String rootCauseIntentCategory = "PLAIN_TOPN";
        private String rootCauseMechanismKey = "UNKNOWN";
        private String rootCauseNoveltyClass = "UNKNOWN";
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
        private String selectionReason = "UNDER_ROOT_CAUSE_CAP";
        private String rootCauseRepresentativeRank;
        private String hintPolicyDecision = "include=<all>;exclude=<none>";
        private String hintFamilyQuotaApplied = "familyQuota=unbounded";
        private PerformanceNineCellCategory nineCellCategory;

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
                    resultEquivalent, counterfactualCandidate.getKind().name(), "NONE");
        }

        PerformanceHintWitness(String baseQuery, String counterfactualQuery,
                CounterfactualCandidate counterfactualCandidate, PlanSummary defaultPlan, PlanSummary hintedPlan,
                List<String> planEvidence, boolean resultEquivalent, String requestedStrategy,
                String counterfactualFallbackReason) {
            this.baseQuery = baseQuery;
            this.counterfactualQuery = counterfactualQuery;
            this.counterfactualCandidate = counterfactualCandidate;
            this.defaultPlan = defaultPlan;
            this.hintedPlan = hintedPlan;
            this.planEvidence = List.copyOf(planEvidence);
            this.resultEquivalent = resultEquivalent;
            this.requestedStrategy = requestedStrategy == null || requestedStrategy.isBlank()
                    ? counterfactualCandidate.getKind().name() : requestedStrategy;
            this.counterfactualFallbackReason = counterfactualFallbackReason == null
                    || counterfactualFallbackReason.isBlank() ? "NONE" : counterfactualFallbackReason;
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

        // Compatibility aliases keep the surrounding legacy HINT pipeline readable;
        // the payload itself is now always the generic counterfactual candidate.
        String getHintedQuery() {
            return getCounterfactualQuery();
        }

        CounterfactualCandidate getCandidate() {
            return getCounterfactualCandidate();
        }

        boolean isHintCounterfactual() {
            return counterfactualCandidate.getKind() == CounterfactualMutationKind.HINT;
        }

        String getCounterfactualRuntimeSide() {
            return isHintCounterfactual() ? "HINTED" : "COUNTERFACTUAL";
        }

        String getRequestedStrategy() {
            return requestedStrategy;
        }

        void setRequestedStrategy(String requestedStrategy) {
            this.requestedStrategy = requestedStrategy == null || requestedStrategy.isBlank()
                    ? counterfactualCandidate.getKind().name() : requestedStrategy;
        }

        String getCounterfactualFallbackReason() {
            return counterfactualFallbackReason;
        }

        void setCounterfactualFallbackReason(String counterfactualFallbackReason) {
            this.counterfactualFallbackReason = counterfactualFallbackReason == null
                    || counterfactualFallbackReason.isBlank() ? "NONE" : counterfactualFallbackReason;
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
            this.defaultTimeoutCount = defaultRuntime.getTimeoutCount();
        }

        RuntimeSummary getHintedRuntime() {
            return hintedRuntime;
        }

        void setHintedRuntime(RuntimeSummary hintedRuntime) {
            this.hintedRuntime = hintedRuntime;
            this.hintedTimeoutCount = hintedRuntime.getTimeoutCount();
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

        List<RuntimeSample> getRuntimeSamples() {
            return runtimeSamples;
        }

        void setRuntimeSamples(List<RuntimeSample> runtimeSamples) {
            this.runtimeSamples = List.copyOf(runtimeSamples);
        }

        boolean hasTimeoutObservation() {
            return defaultTimeoutCount > 0 || hintedTimeoutCount > 0;
        }

        int getDefaultTimeoutCount() {
            return defaultTimeoutCount;
        }

        int getHintedTimeoutCount() {
            return hintedTimeoutCount;
        }

        double getTimeoutCapMs() {
            return timeoutCapMs;
        }

        void setTimeoutCapMs(double timeoutCapMs) {
            this.timeoutCapMs = timeoutCapMs;
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

        String getRootCauseNoveltyClass() {
            return rootCauseNoveltyClass;
        }

        void setRootCauseNoveltyClass(String rootCauseNoveltyClass) {
            this.rootCauseNoveltyClass = rootCauseNoveltyClass;
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

        PerformanceNineCellCategory getNineCellCategory() {
            return nineCellCategory;
        }

        void setNineCellCategory(PerformanceNineCellCategory nineCellCategory) {
            this.nineCellCategory = nineCellCategory;
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

        double getHigherCostRatio() {
            return computePositiveRatio(hintedPlan.getQueryCost(), defaultPlan.getQueryCost());
        }

        double getRuntimeRegressionRatio() {
            if (defaultRuntime == null || hintedRuntime == null) {
                return 0.0;
            }
            return computePositiveRatio(hintedRuntime.getMedianMs(), defaultRuntime.getMedianMs());
        }

        double getAbsoluteRuntimeGapMs() {
            if (defaultRuntime == null || hintedRuntime == null) {
                return 0.0;
            }
            return Math.abs(defaultRuntime.getMedianMs() - hintedRuntime.getMedianMs());
        }

        double getPlanInterestScore() {
            double lowerCostScore = getCostRatio();
            double higherCostScore = getHigherCostRatio();
            return Math.max(1.0, Math.max(lowerCostScore, higherCostScore));
        }
    }

    static final class TimeoutObservation {
        private final String baseQuery;
        private final String counterfactualQuery;
        private final CounterfactualCandidate counterfactualCandidate;
        private final PlanSummary defaultPlan;
        private final PlanSummary hintedPlan;
        private final String phase;
        private final String timedOutSide;
        private final double timeoutCapMs;
        private final double recordedRuntimeMs;
        private final String requestedStrategy;
        private final String counterfactualFallbackReason;

        TimeoutObservation(String baseQuery, String counterfactualQuery,
                CounterfactualCandidate counterfactualCandidate, PlanSummary defaultPlan, PlanSummary hintedPlan,
                String phase, String timedOutSide, double timeoutCapMs, double recordedRuntimeMs) {
            this(baseQuery, counterfactualQuery, counterfactualCandidate, defaultPlan, hintedPlan, phase, timedOutSide,
                    timeoutCapMs, recordedRuntimeMs, counterfactualCandidate.getKind().name(), "NONE");
        }

        TimeoutObservation(String baseQuery, String counterfactualQuery,
                CounterfactualCandidate counterfactualCandidate, PlanSummary defaultPlan, PlanSummary hintedPlan,
                String phase, String timedOutSide, double timeoutCapMs, double recordedRuntimeMs,
                String requestedStrategy, String counterfactualFallbackReason) {
            this.baseQuery = baseQuery;
            this.counterfactualQuery = counterfactualQuery;
            this.counterfactualCandidate = counterfactualCandidate;
            this.defaultPlan = defaultPlan;
            this.hintedPlan = hintedPlan;
            this.phase = phase;
            this.timedOutSide = timedOutSide;
            this.timeoutCapMs = timeoutCapMs;
            this.recordedRuntimeMs = recordedRuntimeMs;
            this.requestedStrategy = requestedStrategy == null || requestedStrategy.isBlank()
                    ? counterfactualCandidate.getKind().name() : requestedStrategy;
            this.counterfactualFallbackReason = counterfactualFallbackReason == null
                    || counterfactualFallbackReason.isBlank() ? "NONE" : counterfactualFallbackReason;
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

        String getHintedQuery() {
            return getCounterfactualQuery();
        }

        CounterfactualCandidate getCandidate() {
            return getCounterfactualCandidate();
        }

        boolean isHintCounterfactual() {
            return counterfactualCandidate.getKind() == CounterfactualMutationKind.HINT;
        }

        String getRequestedStrategy() {
            return requestedStrategy;
        }

        String getCounterfactualFallbackReason() {
            return counterfactualFallbackReason;
        }

        PlanSummary getDefaultPlan() {
            return defaultPlan;
        }

        PlanSummary getHintedPlan() {
            return hintedPlan;
        }

        String getPhase() {
            return phase;
        }

        String getTimedOutSide() {
            return timedOutSide;
        }

        double getTimeoutCapMs() {
            return timeoutCapMs;
        }

        double getRecordedRuntimeMs() {
            return recordedRuntimeMs;
        }
    }
}
