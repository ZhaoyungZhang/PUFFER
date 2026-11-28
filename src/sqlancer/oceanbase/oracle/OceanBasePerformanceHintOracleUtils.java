package sqlancer.oceanbase.oracle;

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

import sqlancer.common.perfhint.CostRuntimeMetrics;
import sqlancer.common.perfhint.CostRuntimeObservation;
import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.CounterfactualMutationKind;
import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;
import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.common.perfhint.PerformanceNineCellCategory;
import sqlancer.common.perfhint.PerformanceNineCellClassifier;
import sqlancer.common.perfhint.PerformanceNineCellOptions;
import sqlancer.oceanbase.OceanBaseGlobalState;

final class OceanBasePerformanceHintOracleUtils {

    static final int MAX_TABLES = 3;
    static final int MAX_INDEXES_PER_TABLE = 2;
    static final int MAX_HINTED_VARIANTS = 16;

    private static final String SELECT_PREFIX = "SELECT ";
    private static final Pattern EST_TIME_PATTERN = Pattern
            .compile("\\\"EST\\.TIME\\(us\\)\\\"\\s*:\\s*\\\"?([-+0-9.eE]+)\\\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern EST_ROWS_PATTERN = Pattern
            .compile("\\\"EST\\.ROWS\\\"\\s*:\\s*\\\"?([-+0-9.eE]+)\\\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPERATOR_PATTERN = Pattern
            .compile("\\\"OPERATOR\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_PATTERN = Pattern.compile("\\\"NAME\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_LITERAL = Pattern.compile("(?<![A-Za-z_])[+-]?\\d+(?:\\.\\d+)?");
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final DateTimeFormatter CASE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final AtomicLong CASE_SEQUENCE = new AtomicLong();

    private OceanBasePerformanceHintOracleUtils() {
    }

    static String injectHint(String query, String hintSql) {
        if (!query.regionMatches(true, 0, SELECT_PREFIX, 0, SELECT_PREFIX.length())) {
            throw new IllegalArgumentException("OceanBase PERFORMANCE_HINT only supports SELECT: " + query);
        }
        return query.substring(0, SELECT_PREFIX.length()) + "/*+ " + hintSql + " */ "
                + query.substring(SELECT_PREFIX.length());
    }

    /*
     * EXPLAIN EXTENDED explicitly reports only hints accepted by OceanBase in its {@code Used Hint} section. Limit
     * validation to that section so a rejected hint cannot be mistaken for an accepted one merely because its text
     * appears in a different diagnostic section.
     */
    static boolean isHintAccepted(String explainExtended, String hintType) {
        if (explainExtended == null || hintType == null || hintType.isBlank()) {
            return false;
        }
        String upper = explainExtended.toUpperCase(Locale.ROOT);
        int start = upper.indexOf("USED HINT:");
        if (start < 0) {
            return false;
        }
        int end = upper.length();
        for (String nextSection : List.of("QB NAME TRACE:", "OUTLINE DATA:", "OPTIMIZATION INFO:", "PLAN TYPE:")) {
            int next = upper.indexOf(nextSection, start + "USED HINT:".length());
            if (next >= 0) {
                end = Math.min(end, next);
            }
        }
        String usedHintSection = upper.substring(start, end);
        Pattern exactHint = Pattern
                .compile("(?<![A-Z0-9_])" + Pattern.quote(hintType.toUpperCase(Locale.ROOT)) + "(?![A-Z0-9_])");
        return exactHint.matcher(usedHintSection).find();
    }

    static PlanSummary parsePlan(String explainJson) {
        Double estimatedTimeUs = extractMaxNumber(EST_TIME_PATTERN, explainJson);
        Double estimatedRows = extractMaxNumber(EST_ROWS_PATTERN, explainJson);
        Set<String> operators = extractAllStrings(OPERATOR_PATTERN, explainJson);
        Set<String> names = extractAllStrings(NAME_PATTERN, explainJson);
        return new PlanSummary(explainJson, estimatedTimeUs, estimatedRows, operators, names);
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0 : sorted.get(middle);
    }

    static boolean isPotentialPlanCandidate(PlanSummary defaultPlan, PlanSummary hintedPlan,
            PerformanceNineCellOptions options) {
        return defaultPlan.hasPositiveCost() && hintedPlan.hasPositiveCost()
                && PerformanceNineCellClassifier.isPotentialCostCandidate(defaultPlan.getEstimatedTimeUs(),
                        hintedPlan.getEstimatedTimeUs(), options)
                && hasMeaningfulPlanDifference(defaultPlan, hintedPlan);
    }

    static List<String> buildPlanEvidence(PlanSummary defaultPlan, PlanSummary hintedPlan,
            PerformanceNineCellOptions options) {
        if (!isPotentialPlanCandidate(defaultPlan, hintedPlan, options)) {
            return List.of();
        }
        List<String> evidence = new ArrayList<>();
        double lowerCostRatio = positiveRatio(defaultPlan.getEstimatedTimeUs(), hintedPlan.getEstimatedTimeUs());
        double higherCostRatio = positiveRatio(hintedPlan.getEstimatedTimeUs(), defaultPlan.getEstimatedTimeUs());
        if (lowerCostRatio > options.getCostRatioUpperBound()) {
            evidence.add(String.format(Locale.US, "est_time_ratio=%.4f", lowerCostRatio));
        }
        if (lowerCostRatio < options.getCostRatioLowerBound()) {
            evidence.add(String.format(Locale.US, "higher_est_time_ratio=%.4f", higherCostRatio));
        }
        if (lowerCostRatio >= options.getCostRatioLowerBound() && lowerCostRatio <= options.getCostRatioUpperBound()) {
            evidence.add(String.format(Locale.US, "est_time_similarity_ratio=%.4f", lowerCostRatio));
        }
        if (!defaultPlan.getOperators().equals(hintedPlan.getOperators())) {
            evidence.add("operators_changed");
        }
        if (!defaultPlan.getNames().equals(hintedPlan.getNames())) {
            evidence.add("access_names_changed");
        }
        if (!sameNumber(defaultPlan.getEstimatedRows(), hintedPlan.getEstimatedRows())) {
            evidence.add("estimated_rows_changed");
        }
        return evidence;
    }

    /* Plan-only screening: estimated time is evidence, never a pre-runtime gate. */
    static List<String> buildPlanDifferenceEvidence(PlanSummary defaultPlan, PlanSummary hintedPlan) {
        if (!hasMeaningfulPlanDifference(defaultPlan, hintedPlan)) {
            return List.of();
        }
        List<String> evidence = new ArrayList<>();
        if (defaultPlan.hasPositiveCost() && hintedPlan.hasPositiveCost()) {
            evidence.add(String.format(Locale.US, "est_time_ratio=%.4f",
                    positiveRatio(defaultPlan.getEstimatedTimeUs(), hintedPlan.getEstimatedTimeUs())));
        }
        if (!defaultPlan.getOperators().equals(hintedPlan.getOperators())) {
            evidence.add("operators_changed");
        }
        if (!defaultPlan.getNames().equals(hintedPlan.getNames())) {
            evidence.add("access_names_changed");
        }
        if (!sameNumber(defaultPlan.getEstimatedRows(), hintedPlan.getEstimatedRows())) {
            evidence.add("estimated_rows_changed");
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

    static CostRuntimeObservation buildRuntimeObservation(PerformanceHintWitness witness) {
        return new CostRuntimeObservation(witness.getDefaultPlan().getEstimatedTimeUs(),
                witness.getHintedPlan().getEstimatedTimeUs(), witness.getDefaultRuntime().getMedianMs(),
                witness.getHintedRuntime().getMedianMs(), witness.getDefaultRuntime().getMeanMs(),
                witness.getHintedRuntime().getMeanMs(), witness.getDefaultRuntime().getMinMs(),
                witness.getHintedRuntime().getMinMs(), witness.getDefaultRuntime().getMaxMs(),
                witness.getHintedRuntime().getMaxMs(), witness.getDefaultRuntime().getTimeoutCount(),
                witness.getHintedRuntime().getTimeoutCount(), witness.getDefaultRuntime().getSamplesMs(),
                witness.getHintedRuntime().getSamplesMs());
    }

    static String buildSkeletonKey(String query, int tableCount) {
        String normalized = STRING_LITERAL.matcher(query).replaceAll("?");
        normalized = NUMBER_LITERAL.matcher(normalized).replaceAll("?");
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim().toUpperCase(Locale.ROOT);
        return "tables=" + tableCount + ";" + normalized;
    }

    static String buildPlanSignatureKey(PerformanceHintWitness witness) {
        return witness.getCounterfactualCandidate().getMutationIdentity() + ";default="
                + witness.getDefaultPlan().getShapeKey() + ";hinted=" + witness.getHintedPlan().getShapeKey();
    }

    static String buildRootCauseKey(PerformanceHintWitness witness) {
        String shape = witness.getDefaultPlan().getShapeKey() + "->" + witness.getHintedPlan().getShapeKey();
        return witness.getCounterfactualCandidate().getMutationIdentity() + ";" + shape;
    }

    static Path writeWitness(OceanBaseGlobalState state, PerformanceHintWitness witness) throws IOException {
        return writeSnapshot(snapshotWitness(state, witness));
    }

    static PerformanceHintReportSnapshot snapshotWitness(OceanBaseGlobalState state, PerformanceHintWitness witness) {
        Path reportRoot = resolveReportRoot();
        if (witness.getNineCellCategory() != null) {
            reportRoot = reportRoot.resolve(witness.getNineCellCategory().getCode());
        }
        Path reportDir = reportRoot.resolve("case_" + CASE_TIME_FORMAT.format(LocalDateTime.now()) + "_"
                + state.getState().getSeedValue() + "_" + CASE_SEQUENCE.incrementAndGet());
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
            // Preserve existing reproduction scripts and report consumers for legacy HINT witnesses.
            files.put("hinted_query.sql", witness.getCounterfactualQuery() + System.lineSeparator());
            files.put("hinted_explain.json", witness.getHintedPlan().getExplainJson());
            files.put("hint_validation.txt", "Default:\n" + witness.getDefaultHintDetails() + "\n\nHinted:\n"
                    + witness.getHintedHintDetails() + "\n");
        }
        if (witness.getDefaultPlanDetails() != null) {
            files.put("default_plan_details.txt", witness.getDefaultPlanDetails());
        }
        if (witness.getHintedPlanDetails() != null) {
            files.put("counterfactual_plan_details.txt", witness.getHintedPlanDetails());
            if (witness.isHintCounterfactual()) {
                files.put("hinted_plan_details.txt", witness.getHintedPlanDetails());
            }
        }
        files.put("runtime_samples.csv", witness.runtimeSamplesAsCsv());
        String metrics = witness.toJson(state);
        files.put("metadata.json", metrics);
        files.put("metrics.json", metrics);
        files.put("report.md", witness.toMarkdown(state));
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

    static Path resolveReportRoot() {
        String property = System.getProperty("sqlancer.bugReportDir");
        String environment = System.getenv("SQLANCER_BUG_REPORT_DIR");
        Path base = property != null && !property.isBlank() ? Path.of(property)
                : environment != null && !environment.isBlank() ? Path.of(environment) : Path.of("evaluation");
        return base.resolve("oceanbase");
    }

    static String createAssertionMessage(PerformanceHintWitness witness, Path reportDir) {
        return "Potential OceanBase PERFORMANCE_HINT counterfactual issue\nReport: " + reportDir + "\nBase SQL: "
                + witness.getBaseQuery() + "\nCounterfactual SQL: " + witness.getCounterfactualQuery() + "\nMutation: "
                + witness.getCounterfactualCandidate().getMutationIdentity() + "\nDescriptor: "
                + witness.getCounterfactualCandidate().getDescriptor() + "\nEST.TIME(us): "
                + witness.getDefaultPlan().getEstimatedTimeUs() + " -> " + witness.getHintedPlan().getEstimatedTimeUs()
                + "\nRuntime median (ms): " + witness.getDefaultRuntime().getMedianMs() + " -> "
                + witness.getHintedRuntime().getMedianMs() + "\nNine-cell category: "
                + (witness.getNineCellCategory() == null ? "UNCLASSIFIED" : witness.getNineCellCategory().getCode());
    }

    static boolean hasMeaningfulPlanDifference(PlanSummary defaultPlan, PlanSummary hintedPlan) {
        return !defaultPlan.getOperators().equals(hintedPlan.getOperators())
                || !defaultPlan.getNames().equals(hintedPlan.getNames())
                || !sameNumber(defaultPlan.getEstimatedRows(), hintedPlan.getEstimatedRows());
    }

    private static boolean sameNumber(Double first, Double second) {
        return first == null ? second == null : second != null && Double.compare(first, second) == 0;
    }

    static double positiveRatio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null) {
            return 0.0;
        }
        return CostRuntimeMetrics.computePositiveRatio(numerator, denominator);
    }

    private static Double extractMaxNumber(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        Double result = null;
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                if (Double.isFinite(value) && (result == null || value > result)) {
                    result = value;
                }
            } catch (NumberFormatException ignored) {
                // Ignore a malformed explain field and let the caller skip the candidate.
            }
        }
        return result;
    }

    private static Set<String> extractAllStrings(Pattern pattern, String text) {
        Set<String> values = new LinkedHashSet<>();
        if (text == null) {
            return values;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            values.add(unescapeJson(matcher.group(1)).trim().toUpperCase(Locale.ROOT));
        }
        return values;
    }

    private static String unescapeJson(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/");
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    static final class PlanSummary {
        private final String explainJson;
        private final Double estimatedTimeUs;
        private final Double estimatedRows;
        private final Set<String> operators;
        private final Set<String> names;

        PlanSummary(String explainJson, Double estimatedTimeUs, Double estimatedRows, Set<String> operators,
                Set<String> names) {
            this.explainJson = explainJson;
            this.estimatedTimeUs = estimatedTimeUs;
            this.estimatedRows = estimatedRows;
            this.operators = Collections.unmodifiableSet(new LinkedHashSet<>(operators));
            this.names = Collections.unmodifiableSet(new LinkedHashSet<>(names));
        }

        String getExplainJson() {
            return explainJson;
        }

        Double getEstimatedTimeUs() {
            return estimatedTimeUs;
        }

        Double getEstimatedRows() {
            return estimatedRows;
        }

        Set<String> getOperators() {
            return operators;
        }

        Set<String> getNames() {
            return names;
        }

        boolean hasPositiveCost() {
            return estimatedTimeUs != null && estimatedTimeUs > 0.0;
        }

        String getShapeKey() {
            return "op=" + String.join(",", operators) + ";name=" + String.join(",", names);
        }
    }

    static final class RuntimeSummary {
        private final List<Double> samplesMs;
        private final int timeoutCount;

        RuntimeSummary(List<Double> samplesMs, int timeoutCount) {
            this.samplesMs = List.copyOf(samplesMs);
            this.timeoutCount = timeoutCount;
        }

        List<Double> getSamplesMs() {
            return samplesMs;
        }

        int getTimeoutCount() {
            return timeoutCount;
        }

        double getMedianMs() {
            return median(samplesMs);
        }

        double getMeanMs() {
            return samplesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }

        double getMinMs() {
            return samplesMs.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        }

        double getMaxMs() {
            return samplesMs.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        }
    }

    static final class RuntimeSample {
        private final int round;
        private final String side;
        private final double elapsedMs;
        private final boolean timedOut;

        RuntimeSample(int round, String side, double elapsedMs, boolean timedOut) {
            this.round = round;
            this.side = side;
            this.elapsedMs = elapsedMs;
            this.timedOut = timedOut;
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
        private final CounterfactualMutationKind requestedStrategy;
        private final String fallbackReason;
        private boolean resultEquivalent;
        private String defaultHintDetails = "";
        private String hintedHintDetails = "";
        private RuntimeSummary defaultRuntime;
        private RuntimeSummary hintedRuntime;
        private List<RuntimeSample> runtimeSamples = List.of();
        private String skeletonKey;
        private String planSignatureKey;
        private String rootCauseKey;
        private String defaultPlanDetails;
        private String hintedPlanDetails;
        private String planDetailMode;
        private PerformanceNineCellCategory nineCellCategory;

        void setQueryGenerationMetadata(sqlancer.common.perfhint.PerformanceQueryMetadata metadata) {
            queryGenerationMetadata = metadata;
        }

        String queryGenerationJson() {
            return queryGenerationMetadata == null ? "null" : queryGenerationMetadata.toJson();
        }

        PerformanceHintWitness(String baseQuery, String counterfactualQuery,
                CounterfactualCandidate counterfactualCandidate, PlanSummary defaultPlan, PlanSummary hintedPlan,
                List<String> planEvidence) {
            this(baseQuery, counterfactualQuery, counterfactualCandidate, defaultPlan, hintedPlan, planEvidence,
                    counterfactualCandidate.getKind(), "NONE");
        }

        PerformanceHintWitness(String baseQuery, String counterfactualQuery,
                CounterfactualCandidate counterfactualCandidate, PlanSummary defaultPlan, PlanSummary hintedPlan,
                List<String> planEvidence, CounterfactualMutationKind requestedStrategy, String fallbackReason) {
            this.baseQuery = baseQuery;
            this.counterfactualQuery = counterfactualQuery;
            this.counterfactualCandidate = counterfactualCandidate;
            this.defaultPlan = defaultPlan;
            this.hintedPlan = hintedPlan;
            this.planEvidence = List.copyOf(planEvidence);
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

        // Compatibility aliases keep the legacy HINT-only pipeline readable while the payload is now generic.
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

        RuntimeSummary getDefaultRuntime() {
            return defaultRuntime;
        }

        RuntimeSummary getHintedRuntime() {
            return hintedRuntime;
        }

        String getDefaultHintDetails() {
            return defaultHintDetails;
        }

        String getHintedHintDetails() {
            return hintedHintDetails;
        }

        String getDefaultPlanDetails() {
            return defaultPlanDetails;
        }

        String getHintedPlanDetails() {
            return hintedPlanDetails;
        }

        String getSkeletonKey() {
            return skeletonKey;
        }

        String getPlanSignatureKey() {
            return planSignatureKey;
        }

        String getRootCauseKey() {
            return rootCauseKey;
        }

        PerformanceNineCellCategory getNineCellCategory() {
            return nineCellCategory;
        }

        void setHintDetails(String defaultHintDetails, String hintedHintDetails) {
            this.defaultHintDetails = defaultHintDetails == null ? "" : defaultHintDetails;
            this.hintedHintDetails = hintedHintDetails == null ? "" : hintedHintDetails;
        }

        void setRuntime(RuntimeSummary defaultRuntime, RuntimeSummary hintedRuntime, List<RuntimeSample> samples) {
            this.defaultRuntime = defaultRuntime;
            this.hintedRuntime = hintedRuntime;
            this.runtimeSamples = List.copyOf(samples);
        }

        void setFeedbackKeys(String skeletonKey, String planSignatureKey, String rootCauseKey) {
            this.skeletonKey = skeletonKey;
            this.planSignatureKey = planSignatureKey;
            this.rootCauseKey = rootCauseKey;
        }

        void setPlanDetails(String mode, String defaultPlanDetails, String hintedPlanDetails) {
            this.planDetailMode = mode;
            this.defaultPlanDetails = defaultPlanDetails;
            this.hintedPlanDetails = hintedPlanDetails;
        }

        void setResultEquivalent(boolean resultEquivalent) {
            this.resultEquivalent = resultEquivalent;
        }

        void setNineCellCategory(PerformanceNineCellCategory nineCellCategory) {
            this.nineCellCategory = nineCellCategory;
        }

        String runtimeSamplesAsCsv() {
            StringBuilder sb = new StringBuilder("round,side,elapsed_ms,timed_out\n");
            for (RuntimeSample sample : runtimeSamples) {
                sb.append(sample.round).append(',').append(sample.side).append(',')
                        .append(String.format(Locale.US, "%.4f", sample.elapsedMs)).append(',').append(sample.timedOut)
                        .append('\n');
            }
            return sb.toString();
        }

        String toJson(OceanBaseGlobalState state) {
            List<String> fields = new ArrayList<>();
            fields.add(json("engine", "OceanBase"));
            fields.add(json("dbms", "oceanbase"));
            fields.add(json("databaseVersion", state.getState().getDatabaseVersion()));
            fields.add(
                    json("dataAblationProfile", state.getDbmsSpecificOptions().performanceDataAblationProfile.getId()));
            fields.add("\"initialScale\": " + state.getDbmsSpecificOptions().performanceInitialScale);
            if (state.getPdgIrContext() != null) {
                fields.add(jsonArray("pdgPrimitives",
                        state.getPdgIrContext().getSpecification().getTriggeredPrimitives()));
            }
            fields.add(json("baseQuery", baseQuery));
            fields.add("\"seed\": " + state.getState().getSeedValue());
            fields.add(json("planCachePolicy", state
                    .getDbmsSpecificOptions().performanceDataAblationProfile == PerformanceHintDataAblationProfile.NONE
                            ? "SERVER_DEFAULT" : "DISABLED"));
            fields.add(json("dataModel", state.getPerformanceDataModelDescription()));
            fields.add(json("mutationKind", counterfactualCandidate.getKind().name()));
            fields.add(json("actualMutationKind", counterfactualCandidate.getKind().name()));
            fields.add(json("mutationFamily", counterfactualCandidate.getFamily()));
            fields.add(json("mutationId", counterfactualCandidate.getMutationId()));
            fields.add(json("mutationDescriptor", counterfactualCandidate.getDescriptor()));
            fields.add(json("counterfactualQuery", counterfactualQuery));
            fields.add(json("requestedStrategy", requestedStrategy.name()));
            fields.add(json("fallbackReason", fallbackReason));
            fields.add("\"resultEquivalent\": " + resultEquivalent);
            if (isHintCounterfactual()) {
                fields.add(json("hint", counterfactualCandidate.getDescriptor()));
                fields.add(json("hintFamily", counterfactualCandidate.getFamily()));
                fields.add(json("hintType", counterfactualCandidate.getMutationId()));
            }
            fields.add(json("skeletonKey", skeletonKey));
            fields.add(json("planSignature", planSignatureKey));
            fields.add(json("rootCause", rootCauseKey));
            fields.add(json("planDetailMode", planDetailMode));
            fields.add("\"defaultEstTimeUs\": " + number(defaultPlan.getEstimatedTimeUs()));
            fields.add("\"counterfactualEstTimeUs\": " + number(hintedPlan.getEstimatedTimeUs()));
            fields.add("\"defaultMedianMs\": " + number(defaultRuntime.getMedianMs()));
            fields.add("\"counterfactualMedianMs\": " + number(hintedRuntime.getMedianMs()));
            fields.add("\"defaultEstimatedCost\": " + number(defaultPlan.getEstimatedTimeUs()));
            fields.add("\"counterfactualEstimatedCost\": " + number(hintedPlan.getEstimatedTimeUs()));
            fields.add("\"defaultRuntimeMedianMs\": " + number(defaultRuntime.getMedianMs()));
            fields.add("\"counterfactualRuntimeMedianMs\": " + number(hintedRuntime.getMedianMs()));
            fields.add("\"costRatio\": " + number(defaultPlan.getEstimatedTimeUs() / hintedPlan.getEstimatedTimeUs()));
            fields.add("\"runtimeRatio\": " + number(defaultRuntime.getMedianMs() / hintedRuntime.getMedianMs()));
            fields.add("\"absoluteRuntimeGapMs\": "
                    + number(Math.abs(defaultRuntime.getMedianMs() - hintedRuntime.getMedianMs())));
            if (nineCellCategory != null) {
                fields.add(json("nineCellCategory", nineCellCategory.getCode()));
                fields.add(json("nineCellSummary", nineCellCategory.getSummary()));
            }
            return "{\"queryGeneration\":" + queryGenerationJson() + ",\n" + String.join(",\n", fields) + "\n}\n";
        }

        String toMarkdown(OceanBaseGlobalState state) {
            StringBuilder sb = new StringBuilder("# OceanBase PERFORMANCE_HINT counterfactual witness\n\n");
            sb.append("- Mutation: `").append(counterfactualCandidate.getMutationIdentity()).append("`\n");
            sb.append("- Descriptor: `").append(counterfactualCandidate.getDescriptor()).append("`\n");
            sb.append("- Requested strategy: ").append(requestedStrategy).append("\n");
            sb.append("- Fallback: ").append(fallbackReason).append("\n");
            sb.append("- Cost (EST.TIME(us)): ").append(defaultPlan.getEstimatedTimeUs()).append(" → ")
                    .append(hintedPlan.getEstimatedTimeUs()).append("\n");
            sb.append("- Runtime median (ms): ").append(format(defaultRuntime.getMedianMs())).append(" → ")
                    .append(format(hintedRuntime.getMedianMs())).append("\n");
            sb.append("- Evidence: ").append(String.join(", ", planEvidence)).append("\n");
            sb.append("- Data model: ").append(state.getPerformanceDataModelDescription()).append("\n");
            if (nineCellCategory != null) {
                sb.append("- Nine-cell category: ").append(nineCellCategory.getCode()).append(" — ")
                        .append(nineCellCategory.getSummary()).append("\n");
            }
            return sb.toString();
        }

        private static String json(String key, String value) {
            return "\"" + key + "\": \""
                    + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
                    + "\"";
        }

        private static String jsonArray(String key, List<String> values) {
            StringBuilder sb = new StringBuilder("\"").append(key).append("\": [");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                String v = values.get(i);
                sb.append("\"")
                        .append(v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
                        .append("\"");
            }
            return sb.append("]").toString();
        }

        private static String number(Double value) {
            return value == null || !Double.isFinite(value) ? "null" : String.format(Locale.US, "%.4f", value);
        }

        private static String format(double value) {
            return String.format(Locale.US, "%.4f", value);
        }
    }
}
