package sqlancer.tidb.oracle;

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
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;

final class TiDBPerformanceHintOracleUtils {

    static final int MAX_TABLES = 3;
    static final int MAX_INDEXES_PER_TABLE = 2;
    static final int MAX_HINTED_VARIANTS = 16;

    private static final String SELECT_PREFIX = "SELECT ";
    private static final Pattern INDEX_NAME_PATTERN = Pattern.compile("(?:index|idx)[:= ]+([A-Za-z0-9_]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANALYZE_TIME_PATTERN = Pattern.compile("time:([0-9.]+)\\s*(ns|us|µs|ms|s)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANALYZE_ROWS_PATTERN = Pattern.compile("rows:([0-9.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANALYZE_LOOPS_PATTERN = Pattern.compile("loops:([0-9.]+)", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter CASE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String BUG_REPORT_DIR_PROPERTY = "sqlancer.bugReportDir";
    private static final String BUG_REPORT_DIR_ENV = "SQLANCER_BUG_REPORT_DIR";
    private static final String DIAGNOSTIC_REPORT_DIR_NAME = "diagnostic_cases";
    private static final String TIMEOUT_REPORT_DIR_NAME = "timeout_cases";
    private static final AtomicLong CASE_SEQUENCE = new AtomicLong();

    private TiDBPerformanceHintOracleUtils() {
    }

    static String injectHint(String query, String hintSql) {
        if (!query.startsWith(SELECT_PREFIX)) {
            throw new AssertionError(query);
        }
        return SELECT_PREFIX + "/*+ " + hintSql + " */ " + query.substring(SELECT_PREFIX.length());
    }

    static PlanSummary parsePlan(String explainText) {
        List<String[]> rows = splitExplainRows(explainText);
        if (rows.isEmpty()) {
            return new PlanSummary(explainText, null, Set.of(), Set.of(), Set.of(), false, false, false, null, null);
        }
        String[] header = rows.get(0);
        int idIndex = findColumn(header, "id");
        int estRowsIndex = findColumn(header, "estrows");
        int estCostIndex = findColumn(header, "estcost");
        int taskIndex = findColumn(header, "task");
        int accessObjectIndex = findColumn(header, "access object");
        int operatorInfoIndex = findColumn(header, "operator info");

        Double queryCost = null;
        Double rowsExamined = null;
        Double rowsProduced = null;
        Set<String> keys = new LinkedHashSet<>();
        Set<String> accessTypes = new LinkedHashSet<>();
        Set<String> operations = new LinkedHashSet<>();
        boolean usingTemporaryTable = false;
        boolean usingDeduplication = false;
        boolean usingFilesort = false;

        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String id = getCell(row, idIndex);
            String task = getCell(row, taskIndex);
            String accessObject = getCell(row, accessObjectIndex);
            String operatorInfo = getCell(row, operatorInfoIndex);
            Double estCost = parseNumber(getCell(row, estCostIndex));
            Double estRows = parseNumber(getCell(row, estRowsIndex));
            if (queryCost == null && estCost != null) {
                queryCost = estCost;
            }
            rowsExamined = max(rowsExamined, estRows);
            rowsProduced = max(rowsProduced, estRows);

            String normalizedOperation = normalizeOperation(id);
            if (!normalizedOperation.isEmpty()) {
                operations.add(normalizedOperation);
            }
            String accessType = normalizeAccessType(id, task, accessObject, operatorInfo);
            if (!accessType.isEmpty()) {
                accessTypes.add(accessType);
            }
            keys.addAll(extractAllStrings(INDEX_NAME_PATTERN, accessObject + " " + operatorInfo));

            String lower = (id + " " + task + " " + accessObject + " " + operatorInfo).toLowerCase(Locale.ROOT);
            if (lower.contains("hashagg") || lower.contains("streamagg")) {
                usingDeduplication = true;
            }
            if (lower.contains("sort") || lower.contains("topn")) {
                usingFilesort = true;
            }
            if (lower.contains("temporary") || lower.contains("cop[tikv]")) {
                usingTemporaryTable = usingTemporaryTable || lower.contains("temporary");
            }
        }

        return new PlanSummary(explainText, queryCost, keys, accessTypes, operations, usingTemporaryTable,
                usingDeduplication, usingFilesort, rowsExamined, rowsProduced);
    }

    static AnalyzeSummary parseAnalyzeSummary(String analyzeText) {
        Double maxEndTimeMs = null;
        Double maxRows = null;
        Double maxLoops = null;
        Matcher timeMatcher = ANALYZE_TIME_PATTERN.matcher(analyzeText);
        while (timeMatcher.find()) {
            maxEndTimeMs = max(maxEndTimeMs,
                    convertToMilliseconds(Double.parseDouble(timeMatcher.group(1)), timeMatcher.group(2)));
        }
        Matcher rowsMatcher = ANALYZE_ROWS_PATTERN.matcher(analyzeText);
        while (rowsMatcher.find()) {
            maxRows = max(maxRows, Double.parseDouble(rowsMatcher.group(1)));
        }
        Matcher loopsMatcher = ANALYZE_LOOPS_PATTERN.matcher(analyzeText);
        while (loopsMatcher.find()) {
            maxLoops = max(maxLoops, Double.parseDouble(loopsMatcher.group(1)));
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

    /*
     * Plan-only screening used before runtime measurement. Cost is recorded but is not a gate: it is one axis of the
     * final A-I outcome.
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
        if (defaultPlan.isUsingTemporaryTable() != hintedPlan.isUsingTemporaryTable()) {
            evidence.add(defaultPlan.isUsingTemporaryTable() ? "hinted_plan_avoids_temporary_table"
                    : "hinted_plan_adds_temporary_table");
        }
        if (defaultPlan.isUsingFilesort() != hintedPlan.isUsingFilesort()) {
            evidence.add(defaultPlan.isUsingFilesort() ? "hinted_plan_avoids_filesort" : "hinted_plan_adds_filesort");
        }
        if (defaultPlan.isUsingDeduplication() != hintedPlan.isUsingDeduplication()) {
            evidence.add("plan_deduplication_changed");
        }
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
        sb.append("Potential PERFORMANCE_HINT counterfactual issue").append(System.lineSeparator());
        sb.append("Report: ").append(reportDir).append(System.lineSeparator());
        sb.append("Base SQL: ").append(witness.getBaseQuery()).append(System.lineSeparator());
        sb.append("Counterfactual SQL: ").append(witness.getCounterfactualQuery()).append(System.lineSeparator());
        sb.append("Mutation: ").append(witness.getCounterfactualCandidate().getMutationIdentity())
                .append(System.lineSeparator());
        sb.append("Requested strategy: ").append(witness.getRequestedStrategy()).append(System.lineSeparator());
        sb.append("Fallback reason: ").append(witness.getCounterfactualFallbackReason()).append(System.lineSeparator());
        sb.append(String.format(Locale.US, "Default cost: %.4f", witness.getDefaultPlan().getQueryCost()))
                .append(System.lineSeparator());
        sb.append(String.format(Locale.US, "Counterfactual cost: %.4f", witness.getHintedPlan().getQueryCost()))
                .append(System.lineSeparator());
        sb.append("Default keys: ").append(joinOrNone(witness.getDefaultPlan().getKeys()))
                .append(System.lineSeparator());
        sb.append("Counterfactual keys: ").append(joinOrNone(witness.getHintedPlan().getKeys()))
                .append(System.lineSeparator());
        if (witness.hasTimeoutObservation()) {
            sb.append("Timeout cap (ms): ").append(String.format(Locale.US, "%.4f", witness.getTimeoutCapMs()))
                    .append(System.lineSeparator());
            sb.append("Default timeout count: ").append(witness.getDefaultTimeoutCount())
                    .append(System.lineSeparator());
            sb.append("Counterfactual timeout count: ").append(witness.getHintedTimeoutCount())
                    .append(System.lineSeparator());
        }
        if (witness.getDefaultRuntime() != null && witness.getHintedRuntime() != null) {
            sb.append(String.format(Locale.US, "Default median runtime (ms): %.4f",
                    witness.getDefaultRuntime().getMedianMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "Counterfactual median runtime (ms): %.4f",
                    witness.getHintedRuntime().getMedianMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "Runtime speedup: %.4f", computePositiveRatio(
                    witness.getDefaultRuntime().getMedianMs(), witness.getHintedRuntime().getMedianMs())))
                    .append(System.lineSeparator());
            sb.append(String.format(Locale.US, "Default average runtime (ms): %.4f",
                    witness.getDefaultRuntime().getMeanMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "Counterfactual average runtime (ms): %.4f",
                    witness.getHintedRuntime().getMeanMs())).append(System.lineSeparator());
        }
        if (witness.getNineCellCategory() != null) {
            sb.append("Nine-cell category: ").append(witness.getNineCellCategory().getCode())
                    .append(System.lineSeparator());
            sb.append("Nine-cell summary: ").append(witness.getNineCellCategory().getSummary())
                    .append(System.lineSeparator());
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
        sb.append("Default EXPLAIN FORMAT='verbose': ").append(System.lineSeparator())
                .append(witness.getDefaultPlan().getExplainJson()).append(System.lineSeparator());
        sb.append("Counterfactual EXPLAIN FORMAT='verbose': ").append(System.lineSeparator())
                .append(witness.getHintedPlan().getExplainJson());
        return sb.toString();
    }

    static Path writeWitness(TiDBGlobalState state, PerformanceHintWitness witness) throws IOException {
        return writeWitness(state, witness, !witness.getNineCellCategory().isConfirmedHarmful());
    }

    static Path writeWitness(TiDBGlobalState state, PerformanceHintWitness witness, boolean diagnosticOnly)
            throws IOException {
        return writeSnapshot(snapshotWitness(state, witness, diagnosticOnly));
    }

    static PerformanceHintReportSnapshot snapshotWitness(TiDBGlobalState state, PerformanceHintWitness witness,
            boolean diagnosticOnly) {
        String caseId = "case_" + CASE_TIME_FORMAT.format(LocalDateTime.now()) + "_" + state.getState().getSeedValue()
                + "_" + CASE_SEQUENCE.incrementAndGet();
        Path reportRoot = diagnosticOnly ? resolveReportRoot().resolve(DIAGNOSTIC_REPORT_DIR_NAME)
                : resolveReportRoot();
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
        files.put("default_explain.txt", witness.getDefaultPlan().getExplainJson());
        files.put("counterfactual_explain.txt", witness.getHintedPlan().getExplainJson());
        if (witness.isHintCounterfactual()) {
            // Preserve the legacy HINT artifact names used by existing repro tooling.
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

    static Path writeTimeoutObservation(TiDBGlobalState state, TimeoutObservation observation) throws IOException {
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
            writeFile(reportDir.resolve("default_explain.txt"), observation.getDefaultPlan().getExplainJson());
        }
        if (observation.getHintedPlan() != null) {
            writeFile(reportDir.resolve("counterfactual_explain.txt"), observation.getHintedPlan().getExplainJson());
        }
        if (observation.isHintCounterfactual()) {
            writeFile(reportDir.resolve("hinted_query.sql"),
                    observation.getCounterfactualQuery() + System.lineSeparator());
            if (observation.getHintedPlan() != null) {
                writeFile(reportDir.resolve("hinted_explain.txt"), observation.getHintedPlan().getExplainJson());
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

    private static String buildMetricsJson(TiDBGlobalState state, PerformanceHintWitness witness) {
        List<String> fields = new ArrayList<>();
        CounterfactualCandidate candidate = witness.getCounterfactualCandidate();
        fields.add(jsonField("dbms", "tidb"));
        fields.add(jsonField("databaseVersion", state.getState().getDatabaseVersion()));
        fields.add(jsonField("seed", state.getState().getSeedValue()));
        addDataModelFields(fields, state);
        fields.add(jsonField("baseQuery", witness.getBaseQuery()));
        fields.add(jsonField("counterfactualQuery", witness.getCounterfactualQuery()));
        fields.add(jsonField("mutationKind", candidate.getKind().name()));
        fields.add(jsonField("actualMutationKind", candidate.getKind().name()));
        fields.add(jsonField("mutationFamily", candidate.getFamily()));
        fields.add(jsonField("mutationId", candidate.getMutationId()));
        fields.add(jsonField("mutationDescriptor", candidate.getDescriptor()));
        fields.add(jsonArrayField("targetTables", candidate.getTargetTables()));
        fields.add(jsonArrayField("targetIndexes", candidate.getTargetIndexes()));
        fields.add(jsonField("requestedStrategy", witness.getRequestedStrategy()));
        fields.add(jsonField("fallbackReason", witness.getCounterfactualFallbackReason()));
        fields.add(jsonField("defaultEstimatedCost", witness.getDefaultPlan().getQueryCost()));
        fields.add(jsonField("counterfactualEstimatedCost", witness.getHintedPlan().getQueryCost()));
        fields.add(jsonField("defaultKey", joinOrNone(witness.getDefaultPlan().getKeys())));
        fields.add(jsonField("counterfactualKey", joinOrNone(witness.getHintedPlan().getKeys())));
        fields.add(jsonField("resultEquivalent", witness.isResultEquivalent()));
        fields.add(jsonField("queryTimeoutSeconds", witness.getTimeoutCapMs() / 1000.0));
        fields.add(jsonField("timeoutObserved", witness.hasTimeoutObservation()));
        fields.add(jsonField("defaultTimeoutCount", (long) witness.getDefaultTimeoutCount()));
        fields.add(jsonField("counterfactualTimeoutCount", (long) witness.getHintedTimeoutCount()));
        fields.add(jsonField("runtimeValidated",
                witness.getDefaultRuntime() != null && witness.getHintedRuntime() != null));
        if (witness.getDefaultRuntime() != null && witness.getHintedRuntime() != null) {
            fields.add(jsonField("defaultRuntimeMedianMs", witness.getDefaultRuntime().getMedianMs()));
            fields.add(jsonField("counterfactualRuntimeMedianMs", witness.getHintedRuntime().getMedianMs()));
            fields.add(jsonField("defaultRuntimeAverageMs", witness.getDefaultRuntime().getMeanMs()));
            fields.add(jsonField("counterfactualRuntimeAverageMs", witness.getHintedRuntime().getMeanMs()));
            fields.add(jsonField("runtimeSpeedup", computePositiveRatio(witness.getDefaultRuntime().getMedianMs(),
                    witness.getHintedRuntime().getMedianMs())));
        }
        if (witness.isHintCounterfactual()) {
            // Legacy fields remain HINT-only. In particular, a rewrite must never be presented as a hint type.
            fields.add(jsonField("hintedQuery", witness.getCounterfactualQuery()));
            fields.add(jsonField("hintFamily", candidate.getFamily()));
            fields.add(jsonField("hintType", candidate.getMutationId()));
            fields.add(jsonArrayField("tableNames", candidate.getTargetTables()));
            fields.add(jsonArrayField("indexNames", candidate.getTargetIndexes()));
            fields.add(jsonField("hintedEstimatedCost", witness.getHintedPlan().getQueryCost()));
            fields.add(jsonField("hintedKey", joinOrNone(witness.getHintedPlan().getKeys())));
            fields.add(jsonField("hintedTimeoutCount", (long) witness.getHintedTimeoutCount()));
            if (witness.getHintedRuntime() != null) {
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
        fields.add(jsonField("hintPolicyDecision", witness.getHintPolicyDecision()));
        fields.add(jsonField("hintFamilyQuotaApplied", witness.getHintFamilyQuotaApplied()));
        return "{\n  " + String.join(",\n  ", fields) + "\n}\n";
    }

    private static String buildSignatureJson(PerformanceHintWitness witness) {
        List<String> fields = new ArrayList<>();
        CounterfactualCandidate candidate = witness.getCounterfactualCandidate();
        fields.add(jsonField("skeletonKey", witness.getSkeletonKey()));
        fields.add("\"queryGeneration\":" + witness.queryGenerationJson());
        fields.add(jsonField("familyKey", witness.getFamilyKey()));
        fields.add(jsonField("planSignatureKey", witness.getPlanSignatureKey()));
        fields.add(jsonField("rootCauseSignatureKey", witness.getRootCauseSignatureKey()));
        fields.add(jsonField("rootCauseCategory", witness.getRootCauseCategory()));
        fields.add(jsonField("rootCauseIntentCategory", witness.getRootCauseIntentCategory()));
        fields.add(jsonField("rootCauseMechanismKey", witness.getRootCauseMechanismKey()));
        fields.add(jsonField("rootCauseNoveltyClass", witness.getRootCauseNoveltyClass()));
        fields.add(jsonField("mutationKind", candidate.getKind().name()));
        fields.add(jsonField("mutationFamily", candidate.getFamily()));
        fields.add(jsonField("mutationId", candidate.getMutationId()));
        fields.add(jsonField("mutationDescriptor", candidate.getDescriptor()));
        fields.add(jsonField("counterfactualQuery", witness.getCounterfactualQuery()));
        fields.add(jsonField("requestedStrategy", witness.getRequestedStrategy()));
        fields.add(jsonField("fallbackReason", witness.getCounterfactualFallbackReason()));
        if (witness.isHintCounterfactual()) {
            fields.add(jsonField("hintType", candidate.getMutationId()));
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
        fields.add(jsonField("hintPolicyDecision", witness.getHintPolicyDecision()));
        fields.add(jsonField("hintFamilyQuotaApplied", witness.getHintFamilyQuotaApplied()));
        return "{\n  " + String.join(",\n  ", fields) + "\n}\n";
    }

    private static String buildTimeoutMetricsJson(TiDBGlobalState state, TimeoutObservation observation) {
        List<String> fields = new ArrayList<>();
        CounterfactualCandidate candidate = observation.getCounterfactualCandidate();
        fields.add(jsonField("dbms", "tidb"));
        fields.add(jsonField("databaseVersion", state.getState().getDatabaseVersion()));
        fields.add(jsonField("seed", state.getState().getSeedValue()));
        addDataModelFields(fields, state);
        fields.add(jsonField("baseQuery", observation.getBaseQuery()));
        fields.add(jsonField("counterfactualQuery", observation.getCounterfactualQuery()));
        fields.add(jsonField("mutationKind", candidate.getKind().name()));
        fields.add(jsonField("mutationFamily", candidate.getFamily()));
        fields.add(jsonField("mutationId", candidate.getMutationId()));
        fields.add(jsonField("mutationDescriptor", candidate.getDescriptor()));
        fields.add(jsonField("requestedStrategy", observation.getRequestedStrategy()));
        fields.add(jsonField("fallbackReason", observation.getCounterfactualFallbackReason()));
        fields.add(jsonField("phase", observation.getPhase()));
        fields.add(jsonField("timedOutSide", observation.getTimedOutSide()));
        fields.add(jsonField("queryTimeoutSeconds", observation.getTimeoutCapMs() / 1000.0));
        fields.add(jsonField("recordedRuntimeMs", observation.getRecordedRuntimeMs()));
        if (observation.getDefaultPlan() != null) {
            fields.add(jsonField("defaultEstimatedCost", observation.getDefaultPlan().getQueryCost()));
        }
        if (observation.getHintedPlan() != null) {
            fields.add(jsonField("counterfactualEstimatedCost", observation.getHintedPlan().getQueryCost()));
        }
        if (observation.isHintCounterfactual()) {
            fields.add(jsonField("hintedQuery", observation.getCounterfactualQuery()));
            fields.add(jsonField("hintFamily", candidate.getFamily()));
            fields.add(jsonField("hintType", candidate.getMutationId()));
            if (observation.getHintedPlan() != null) {
                fields.add(jsonField("hintedEstimatedCost", observation.getHintedPlan().getQueryCost()));
            }
        }
        return "{\n  " + String.join(",\n  ", fields) + "\n}\n";
    }

    private static String buildReportMarkdown(TiDBGlobalState state, PerformanceHintWitness witness) {
        StringBuilder sb = new StringBuilder();
        sb.append("# PERFORMANCE_HINT Witness").append(System.lineSeparator()).append(System.lineSeparator());
        sb.append("- DBMS: tidb ").append(state.getState().getDatabaseVersion()).append(System.lineSeparator());
        sb.append("- Seed: ").append(state.getState().getSeedValue()).append(System.lineSeparator());
        appendDataModelMarkdown(sb, state);
        appendCounterfactualMarkdown(sb, witness);
        if (witness.isHintCounterfactual()) {
            sb.append("- Hint family/type: ").append(witness.getCounterfactualCandidate().getFamily()).append("/")
                    .append(witness.getCounterfactualCandidate().getMutationId()).append(System.lineSeparator());
        }
        sb.append("- Root cause category: ").append(witness.getRootCauseCategory()).append(System.lineSeparator());
        sb.append("- Root cause intent category: ").append(witness.getRootCauseIntentCategory())
                .append(System.lineSeparator());
        sb.append("- Root cause mechanism key: ").append(witness.getRootCauseMechanismKey())
                .append(System.lineSeparator());
        sb.append("- Root cause novelty class: ").append(witness.getRootCauseNoveltyClass())
                .append(System.lineSeparator());
        if (witness.getNineCellCategory() != null) {
            sb.append("- Nine-cell category: ").append(witness.getNineCellCategory().getCode())
                    .append(System.lineSeparator());
            sb.append("- Nine-cell summary: ").append(witness.getNineCellCategory().getSummary())
                    .append(System.lineSeparator());
        }
        sb.append("- Selection reason: ").append(witness.getSelectionReason()).append(System.lineSeparator())
                .append("- Hint policy decision: ").append(witness.getHintPolicyDecision())
                .append(System.lineSeparator()).append("- Hint family quota applied: ")
                .append(witness.getHintFamilyQuotaApplied()).append(System.lineSeparator())
                .append(System.lineSeparator());
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
            sb.append("- Counterfactual timeout count: ").append(witness.getHintedTimeoutCount())
                    .append(System.lineSeparator()).append(System.lineSeparator());
        }
        sb.append("## Original Query").append(System.lineSeparator()).append("```sql").append(System.lineSeparator())
                .append(witness.getBaseQuery()).append(System.lineSeparator()).append("```")
                .append(System.lineSeparator()).append(System.lineSeparator());
        sb.append("## Counterfactual Query").append(System.lineSeparator()).append("```sql")
                .append(System.lineSeparator()).append(witness.getCounterfactualQuery()).append(System.lineSeparator())
                .append("```").append(System.lineSeparator()).append(System.lineSeparator());
        sb.append("## Cost Comparison").append(System.lineSeparator());
        sb.append(String.format(Locale.US, "- Default cost: %.4f", witness.getDefaultPlan().getQueryCost()))
                .append(System.lineSeparator());
        sb.append(String.format(Locale.US, "- Counterfactual cost: %.4f", witness.getHintedPlan().getQueryCost()))
                .append(System.lineSeparator());
        sb.append(String.format(Locale.US, "- Default/counterfactual cost ratio: %.4f", witness.getCostRatio()))
                .append(System.lineSeparator());
        sb.append(String.format(Locale.US, "- Counterfactual/default cost ratio: %.4f", witness.getHigherCostRatio()))
                .append(System.lineSeparator());
        sb.append("- Plan evidence: ").append(String.join(", ", witness.getPlanEvidence()))
                .append(System.lineSeparator()).append(System.lineSeparator());
        if (witness.getDefaultRuntime() != null && witness.getHintedRuntime() != null) {
            sb.append("## Runtime Comparison").append(System.lineSeparator());
            sb.append(String.format(Locale.US, "- Default median runtime (ms): %.4f",
                    witness.getDefaultRuntime().getMedianMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "- Counterfactual median runtime (ms): %.4f",
                    witness.getHintedRuntime().getMedianMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "- Runtime speedup: %.4f", computePositiveRatio(
                    witness.getDefaultRuntime().getMedianMs(), witness.getHintedRuntime().getMedianMs())))
                    .append(System.lineSeparator());
            sb.append(String.format(Locale.US, "- Default average runtime (ms): %.4f",
                    witness.getDefaultRuntime().getMeanMs())).append(System.lineSeparator());
            sb.append(String.format(Locale.US, "- Counterfactual average runtime (ms): %.4f",
                    witness.getHintedRuntime().getMeanMs())).append(System.lineSeparator())
                    .append(String.format(Locale.US, "- Counterfactual/default runtime ratio: %.4f",
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
            sb.append("- Counterfactual max actual time (ms): ")
                    .append(formatNullableDouble(witness.getHintedAnalyze().getMaxActualTimeMs()))
                    .append(System.lineSeparator()).append(System.lineSeparator());
        }
        sb.append("## Reproduction").append(System.lineSeparator());
        sb.append("1. Re-run SQLancer with `tidb --oracle PERFORMANCE_HINT`.").append(System.lineSeparator());
        sb.append("2. Execute `original_query.sql` and `counterfactual_query.sql` on the generated database.")
                .append(System.lineSeparator());
        sb.append("3. Compare `default_explain.txt` and `counterfactual_explain.txt`.").append(System.lineSeparator());
        if (witness.isHintCounterfactual()) {
            sb.append("   The legacy `hinted_query.sql` and `hinted_explain.txt` aliases are also available.")
                    .append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static String buildTimeoutReportMarkdown(TiDBGlobalState state, TimeoutObservation observation) {
        StringBuilder sb = new StringBuilder();
        sb.append("# PERFORMANCE_HINT Timeout Observation").append(System.lineSeparator())
                .append(System.lineSeparator());
        sb.append("- DBMS: tidb ").append(state.getState().getDatabaseVersion()).append(System.lineSeparator());
        sb.append("- Seed: ").append(state.getState().getSeedValue()).append(System.lineSeparator());
        appendDataModelMarkdown(sb, state);
        appendCounterfactualMarkdown(sb, observation);
        if (observation.isHintCounterfactual()) {
            sb.append("- Hint family/type: ").append(observation.getCounterfactualCandidate().getFamily()).append("/")
                    .append(observation.getCounterfactualCandidate().getMutationId()).append(System.lineSeparator());
        }
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
        sb.append("## Counterfactual Query").append(System.lineSeparator()).append("```sql")
                .append(System.lineSeparator()).append(observation.getCounterfactualQuery())
                .append(System.lineSeparator()).append("```").append(System.lineSeparator());
        return sb.toString();
    }

    private static void appendCounterfactualMarkdown(StringBuilder sb, PerformanceHintWitness witness) {
        appendCounterfactualMarkdown(sb, witness.getCounterfactualCandidate(), witness.getRequestedStrategy(),
                witness.getCounterfactualFallbackReason());
    }

    private static void appendCounterfactualMarkdown(StringBuilder sb, TimeoutObservation observation) {
        appendCounterfactualMarkdown(sb, observation.getCounterfactualCandidate(), observation.getRequestedStrategy(),
                observation.getCounterfactualFallbackReason());
    }

    private static void appendCounterfactualMarkdown(StringBuilder sb, CounterfactualCandidate candidate,
            String requestedStrategy, String fallbackReason) {
        sb.append("- Mutation kind: ").append(candidate.getKind().name()).append(System.lineSeparator());
        sb.append("- Mutation family: ").append(candidate.getFamily()).append(System.lineSeparator());
        sb.append("- Mutation ID: ").append(candidate.getMutationId()).append(System.lineSeparator());
        sb.append("- Mutation descriptor: ").append(candidate.getDescriptor()).append(System.lineSeparator());
        sb.append("- Requested strategy: ").append(requestedStrategy).append(System.lineSeparator());
        sb.append("- Fallback reason: ").append(fallbackReason).append(System.lineSeparator());
    }

    private static void addDataModelFields(List<String> fields, TiDBGlobalState state) {
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

    private static void appendDataModelMarkdown(StringBuilder sb, TiDBGlobalState state) {
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

    private static Set<String> extractAllStrings(Pattern pattern, String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
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
        if (defaultPlan.getAccessTypes().contains("FULL_SCAN") && !hintedPlan.getAccessTypes().contains("FULL_SCAN")) {
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
        parts.add("mutation=" + witness.getCounterfactualCandidate().getMutationIdentity());
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
        parts.add("mutation=" + witness.getCounterfactualCandidate().getMutationIdentity());
        parts.add(normalizeSkeletonFlavor(witness.getSkeletonKey()));
        parts.add("DEFAULT=" + planShapeKey(witness.getDefaultPlan(), true));
        parts.add("COUNTERFACTUAL=" + planShapeKey(witness.getHintedPlan(), false));
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
        if (lower.contains("hashjoin") || lower.contains("hash join")) {
            return "HASH_JOIN";
        }
        if (lower.contains("mergejoin") || lower.contains("merge join")) {
            return "MERGE_JOIN";
        }
        if (lower.contains("indexjoin") || lower.contains("index join") || lower.contains("inl_join")
                || lower.contains("inl_hash_join") || lower.contains("inl_merge_join")
                || lower.contains("nested loop")) {
            return "NESTED_LOOP";
        }
        if (lower.contains("topn")) {
            return "TOPN";
        }
        if (lower.contains("limit")) {
            return "LIMIT_SMALL";
        }
        if (lower.contains("streamagg") || lower.contains("stream agg")) {
            return "STREAM_AGG";
        }
        if (lower.contains("hashagg") || lower.contains("hash agg")) {
            return "HASH_AGG";
        }
        if (lower.contains("stream results")) {
            return "STREAM_RESULTS";
        }
        if (lower.contains("filter")) {
            return "FILTER";
        }
        if (lower.contains("sort")) {
            return "SORT";
        }
        if (lower.contains("selection")) {
            return "FILTER";
        }
        if (lower.contains("index")) {
            return "INDEX_ACCESS";
        }
        if (lower.contains("tablescan") || lower.contains("table scan")) {
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

    private static String normalizeAccessType(String id, String task, String accessObject, String operatorInfo) {
        String lower = (id + " " + task + " " + accessObject + " " + operatorInfo).toLowerCase(Locale.ROOT);
        if (lower.contains("tablefullscan") || lower.contains("tablescan")) {
            return "FULL_SCAN";
        }
        if (lower.contains("indexrangescan") || lower.contains("indexfullscan") || lower.contains("indexscan")
                || lower.contains("indexreader") || lower.contains("indexlookup")) {
            return "INDEX_ACCESS";
        }
        if (lower.contains("selection")) {
            return "FILTER";
        }
        return "";
    }

    private static List<String[]> splitExplainRows(String explainText) {
        if (explainText == null || explainText.isBlank()) {
            return List.of();
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : explainText.split("\\R")) {
            rows.add(line.split("\\t", -1));
        }
        return rows;
    }

    private static int findColumn(String[] header, String target) {
        String normalizedTarget = target.toLowerCase(Locale.ROOT);
        for (int i = 0; i < header.length; i++) {
            if (header[i].toLowerCase(Locale.ROOT).equals(normalizedTarget)) {
                return i;
            }
        }
        return -1;
    }

    private static String getCell(String[] row, int index) {
        if (index < 0 || index >= row.length) {
            return "";
        }
        return row[index];
    }

    private static Double parseNumber(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double convertToMilliseconds(double value, String unit) {
        String normalized = unit.toLowerCase(Locale.ROOT);
        switch (normalized) {
        case "s":
            return value * 1000.0;
        case "ms":
            return value;
        case "us":
        case "µs":
            return value / 1000.0;
        case "ns":
            return value / 1_000_000.0;
        default:
            return value;
        }
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
        private String requestedStrategy;
        private String counterfactualFallbackReason = "NONE";
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
            this.baseQuery = baseQuery;
            this.counterfactualQuery = counterfactualQuery;
            this.counterfactualCandidate = counterfactualCandidate;
            this.defaultPlan = defaultPlan;
            this.hintedPlan = hintedPlan;
            this.planEvidence = List.copyOf(planEvidence);
            this.resultEquivalent = resultEquivalent;
            this.requestedStrategy = counterfactualCandidate.getKind().name();
        }

        PerformanceHintWitness(String baseQuery, String counterfactualQuery,
                CounterfactualCandidate counterfactualCandidate, PlanSummary defaultPlan, PlanSummary hintedPlan,
                List<String> planEvidence, boolean resultEquivalent, CounterfactualMutationKind requestedStrategy,
                String fallbackReason) {
            this(baseQuery, counterfactualQuery, counterfactualCandidate, defaultPlan, hintedPlan, planEvidence,
                    resultEquivalent);
            setRequestedStrategy(requestedStrategy == null ? null : requestedStrategy.name());
            setCounterfactualFallbackReason(fallbackReason);
        }

        PerformanceHintWitness(String baseQuery, String counterfactualQuery,
                CounterfactualCandidate counterfactualCandidate, PlanSummary defaultPlan, PlanSummary hintedPlan,
                List<String> planEvidence, boolean resultEquivalent, String requestedStrategy, String fallbackReason) {
            this(baseQuery, counterfactualQuery, counterfactualCandidate, defaultPlan, hintedPlan, planEvidence,
                    resultEquivalent);
            setRequestedStrategy(requestedStrategy);
            setCounterfactualFallbackReason(fallbackReason);
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

        // Compatibility aliases keep existing internal terminology available without retaining the old DBMS-specific
        // candidate payload.
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

        String getFallbackReason() {
            return getCounterfactualFallbackReason();
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
                CounterfactualMutationKind requestedStrategy, String fallbackReason) {
            this(baseQuery, counterfactualQuery, counterfactualCandidate, defaultPlan, hintedPlan, phase, timedOutSide,
                    timeoutCapMs, recordedRuntimeMs, requestedStrategy == null ? null : requestedStrategy.name(),
                    fallbackReason);
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
