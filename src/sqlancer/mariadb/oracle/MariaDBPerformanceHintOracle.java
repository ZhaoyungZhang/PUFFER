package sqlancer.mariadb.oracle;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.perfhint.CostRuntimeObservation;
import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.CounterfactualCandidateScheduler;
import sqlancer.common.perfhint.CounterfactualFallbackReason;
import sqlancer.common.perfhint.CounterfactualMutationKind;
import sqlancer.common.perfhint.CounterfactualStrategySelector;
import sqlancer.common.perfhint.DataAwarePredicateBinder;
import sqlancer.common.perfhint.DataAwarePredicateBinder.JoinEdgeSpec;
import sqlancer.common.perfhint.DataAwarePredicateBinder.Operator;
import sqlancer.common.perfhint.DataAwarePredicateBinder.PredicateSlot;
import sqlancer.common.perfhint.ExpandedPerformanceQuery;
import sqlancer.common.perfhint.PerformanceHintFeedbackState;
import sqlancer.common.perfhint.PerformanceHintHintPolicy;
import sqlancer.common.perfhint.PerformanceHintHintPolicy.PolicyConfig;
import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.common.perfhint.PerformanceHintRootCauseClassifier.RootCauseClassification;
import sqlancer.common.perfhint.PerformanceNineCellCategory;
import sqlancer.common.perfhint.PerformanceNineCellClassifier;
import sqlancer.common.perfhint.PerformanceNineCellOptions;
import sqlancer.common.perfhint.PerformanceNineCellReportPolicy;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode.Family;
import sqlancer.common.perfhint.PerformanceQueryMetadata;
import sqlancer.common.perfhint.PerformanceQueryRewriteProvider;
import sqlancer.common.perfhint.QueryRewriteRequest;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.ColumnSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.IndexSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.TableSummary;
import sqlancer.common.perfhint.RewriteEquivalenceOracle;
import sqlancer.common.perfhint.ScheduledCounterfactualCandidate;
import sqlancer.common.perfhint.WeTuneStaticRewriteEquivalenceOracle;
import sqlancer.common.perfhint.WeTuneStaticRewriteProvider;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.mariadb.MariaDBOptions;
import sqlancer.mariadb.MariaDBProvider.MariaDBGlobalState;
import sqlancer.mariadb.MariaDBSchema.MariaDBColumn;
import sqlancer.mariadb.MariaDBSchema.MariaDBDataType;
import sqlancer.mariadb.MariaDBSchema.MariaDBTable;
import sqlancer.mariadb.gen.MariaDBPerformanceHintGenerator;
import sqlancer.mariadb.gen.MariaDBPerformanceHintGenerator.HintedQueryCandidate;

public class MariaDBPerformanceHintOracle implements TestOracle<MariaDBGlobalState> {

    private static final boolean DEBUG_STDERR = Boolean
            .parseBoolean(System.getenv().getOrDefault("SQLANCER_PERFHINT_DEBUG_STDERR", "false"));

    private final MariaDBGlobalState state;
    private final PerformanceQueryRewriteProvider rewriteProvider;
    private final RewriteEquivalenceOracle rewriteEquivalenceOracle;
    private final Set<PerformanceNineCellCategory> nineCellReportCategories;
    private final ExpectedErrors errors = new ExpectedErrors();
    private String lastQueryString;
    private PerformanceQueryMetadata lastGenerationMetadata;

    public MariaDBPerformanceHintOracle(MariaDBGlobalState state) {
        this(state, MariaDBWeTuneStaticRewriteProvider.fromOptions(state.getDbmsSpecificOptions()),
                new WeTuneStaticRewriteEquivalenceOracle("mariadb"));
    }

    /*
     * The injectable adapters keep the v1 no-op/default-reject behavior explicit while letting a DBMS-specific rewrite
     * implementation be tested without entering the HINT-only default campaign path.
     */
    MariaDBPerformanceHintOracle(MariaDBGlobalState state, PerformanceQueryRewriteProvider rewriteProvider,
            RewriteEquivalenceOracle rewriteEquivalenceOracle) {
        this.state = state;
        this.rewriteProvider = rewriteProvider;
        this.rewriteEquivalenceOracle = rewriteEquivalenceOracle;
        this.nineCellReportCategories = PerformanceNineCellCategory
                .parseReportCells(state.getDbmsSpecificOptions().performanceTaxonomyReportCells);
        errors.add("You have an error in your SQL syntax");
        errors.add("Unknown optimizer hint");
        errors.add("is not supported");
        errors.add("Unknown column");
        errors.add("Column");
    }

    @Override
    public void check() throws Exception {
        MariaDBPerformanceHintTelemetry telemetry = MariaDBPerformanceHintTelemetry.getInstance(state);
        long startedAt = System.nanoTime();
        telemetry.count("checks.started");
        try {
            checkInternal(telemetry);
            telemetry.count("checks.completed");
        } catch (IgnoreMeException e) {
            telemetry.count("checks.ignored");
            if (lastGenerationMetadata != null) {
                lastGenerationMetadata.record(telemetry, "rejected");
            }
            throw e;
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            telemetry.count("checks.failed");
            throw e;
        } finally {
            telemetry.add("checks.wall_clock_nanos", System.nanoTime() - startedAt);
        }
    }

    private void checkInternal(MariaDBPerformanceHintTelemetry telemetry) throws Exception {
        lastQueryString = null;
        lastGenerationMetadata = null;
        BaseQuery select;
        try {
            select = generateBaseQuery();
        } catch (IgnoreMeException e) {
            logDebugNote("PH_SKIP base_query_generation_failed");
            telemetry.reject("base_query", "generation_failed");
            throw e;
        }
        List<MariaDBTable> referencedTables = select.getReferencedTables();
        String baseQuery = select.getSql();
        telemetry.count("base_queries.generated");
        lastGenerationMetadata.record(telemetry, "generated");
        if (!isSupportedQuery(baseQuery)) {
            logDebugNote("PH_SKIP unsupported_query");
            telemetry.reject("base_query", "unsupported_query");
            throw new IgnoreMeException();
        }
        lastQueryString = baseQuery;
        String skeletonKey = MariaDBPerformanceHintOracleUtils.buildSkeletonKey(baseQuery, referencedTables.size())
                + "|" + lastGenerationMetadata.getStructuralKey();
        MariaDBPerformanceHintFeedbackManager feedbackManager = MariaDBPerformanceHintFeedbackManager
                .getInstance(state);
        if (!qpgEnabled()) {
            MariaDBPerformanceHintFeedbackManager.SkeletonDecision skeletonDecision = feedbackManager
                    .evaluateSkeleton(skeletonKey, state.getRandomly(), getMariaDBOptions());
            if (skeletonDecision.shouldSkip()) {
                logDebugNote(String.format(Locale.US,
                        "PH_SKIP repeated_skeleton skeletonKey=%s historicalCount=%d skipProbability=%.4f", skeletonKey,
                        skeletonDecision.getHistoricalCount(), skeletonDecision.getSkipProbability()));
                telemetry.reject("base_query", "repeated_skeleton");
                throw new IgnoreMeException();
            }
        }

        MariaDBPerformanceHintOracleUtils.PlanSummary defaultPlan;
        try {
            defaultPlan = explain(baseQuery, true);
            telemetry.count("base_explains.succeeded");
            lastGenerationMetadata.record(telemetry, "explained");
        } catch (IgnoreMeException e) {
            logDebugNote("PH_SKIP default_explain_failed");
            telemetry.reject("base_explain", "unavailable_or_invalid");
            throw e;
        }
        List<HintedQueryCandidate> generatedCandidates = MariaDBPerformanceHintGenerator.generateCandidates(
                referencedTables, state.getRandomly(), MariaDBPerformanceHintOracleUtils.MAX_INDEXES_PER_TABLE,
                MariaDBPerformanceHintOracleUtils.MAX_HINTED_VARIANTS, getMariaDBOptions().performanceAllowBkaHint);
        telemetry.add("hint_candidates.generated", generatedCandidates.size());
        List<HintedQueryCandidate> selectedHintCandidates = PerformanceHintHintPolicy
                .selectCandidates(generatedCandidates, getHintPolicyConfig());
        telemetry.add("hint_candidates.selected", selectedHintCandidates.size());
        List<CounterfactualCandidate> hintCandidates = materializeHintCandidates(baseQuery, selectedHintCandidates);
        telemetry.add("hint_candidates.materialized", hintCandidates.size());

        CounterfactualStrategySelector strategySelector = new CounterfactualStrategySelector(
                getMariaDBOptions().performanceCounterfactualHintProbability);
        CounterfactualMutationKind requestedStrategy = strategySelector.select(state.getRandomly());
        telemetry.count("batches.requested_" + requestedStrategy.name().toLowerCase(Locale.ROOT));
        List<CounterfactualCandidate> rewriteCandidates = requestedStrategy == CounterfactualMutationKind.QUERY_REWRITE
                ? collectRewriteCandidates(baseQuery, referencedTables, telemetry) : List.of();
        List<ScheduledCounterfactualCandidate> candidates;
        boolean strictWeTuneRewriteBatch = requestedStrategy == CounterfactualMutationKind.QUERY_REWRITE
                && "wetune-static".equalsIgnoreCase(getMariaDBOptions().performanceQueryRewriteProvider);
        if (strictWeTuneRewriteBatch && rewriteCandidates.isEmpty()) {
            telemetry.count("rewrite_batches.empty");
            candidates = List.of();
        } else {
            candidates = CounterfactualCandidateScheduler.scheduleBatch(baseQuery,
                    MariaDBPerformanceHintOracleUtils.MAX_HINTED_VARIANTS, requestedStrategy, hintCandidates,
                    rewriteCandidates);
        }
        if (candidates.isEmpty() && requestedStrategy == CounterfactualMutationKind.HINT
                && strategySelector.shouldRequestRewrites()) {
            rewriteCandidates = collectRewriteCandidates(baseQuery, referencedTables, telemetry);
            candidates = CounterfactualCandidateScheduler.scheduleBatch(baseQuery,
                    MariaDBPerformanceHintOracleUtils.MAX_HINTED_VARIANTS, requestedStrategy, hintCandidates,
                    rewriteCandidates);
        }
        telemetry.add("pairs.scheduled", candidates.size());
        for (ScheduledCounterfactualCandidate candidate : candidates) {
            telemetry.count("slots.requested_" + candidate.getRequestedStrategy().name().toLowerCase(Locale.ROOT));
            telemetry.count("pairs.actual_" + candidate.getActualMutationKind().name().toLowerCase(Locale.ROOT));
            if (candidate.getFallbackReason() != CounterfactualFallbackReason.NONE) {
                telemetry.count("pairs.fallbacks");
                telemetry.count("pairs.fallback_" + candidate.getFallbackReason().name().toLowerCase(Locale.ROOT));
            }
        }

        logDebugNote(String.format("PH_BASE cost=%.4f candidates=%d query=%s", defaultPlan.getQueryCost(),
                candidates.size(), baseQuery));
        if (candidates.isEmpty()) {
            logDebugNote("PH_SKIP no_candidates");
            telemetry.reject("scheduling", "no_candidates");
            if (qpgEnabled()) {
                return;
            }
            throw new IgnoreMeException();
        }

        List<MariaDBPerformanceHintOracleUtils.PerformanceHintWitness> planCandidates = new ArrayList<>();
        for (ScheduledCounterfactualCandidate scheduledCandidate : candidates) {
            CounterfactualCandidate candidate = scheduledCandidate.getCandidate();
            String counterfactualQuery = candidate.getAlternativeSql();
            telemetry.count("pairs.checked");
            logDebugNote(String.format(Locale.US,
                    "PH_TRY requestedStrategy=%s actualMutationKind=%s mutationFamily=%s mutationId=%s descriptor=%s fallback=%s",
                    scheduledCandidate.getRequestedStrategy(), candidate.getKind(), candidate.getFamily(),
                    candidate.getMutationId(), candidate.getDescriptor(), scheduledCandidate.getFallbackReason()));
            if (scheduledCandidate.getFallbackReason() != CounterfactualFallbackReason.NONE) {
                logDebugNote(String.format(Locale.US,
                        "PH_FALLBACK requestedStrategy=%s actualMutationKind=%s reason=%s mutationId=%s",
                        scheduledCandidate.getRequestedStrategy(), candidate.getKind(),
                        scheduledCandidate.getFallbackReason(), candidate.getMutationId()));
            }

            MariaDBPerformanceHintOracleUtils.PlanSummary counterfactualPlan = explain(counterfactualQuery, false);
            if (counterfactualPlan == null || counterfactualPlan.getQueryCost() == null
                    || counterfactualPlan.getQueryCost() <= 0) {
                telemetry.reject("counterfactual_explain", "unavailable_or_invalid");
                continue;
            }
            telemetry.count("pairs.counterfactual_explain_succeeded");
            if (!MariaDBPerformanceHintOracleUtils.hasMeaningfulPlanDifference(defaultPlan, counterfactualPlan)) {
                telemetry.count("pairs.plan_same");
                telemetry.reject("plan_difference", "same");
                continue;
            }
            telemetry.count("pairs.plan_different");
            telemetry.count("pairs.presumed_equivalent_plan_different");
            List<String> planEvidence = MariaDBPerformanceHintOracleUtils.buildPlanDifferenceEvidence(defaultPlan,
                    counterfactualPlan);
            if (planEvidence.isEmpty()) {
                telemetry.reject("plan_evidence", "empty");
                continue;
            }
            telemetry.count("pairs.plan_difference_qualified");
            MariaDBPerformanceHintOracleUtils.PerformanceHintWitness witness = new MariaDBPerformanceHintOracleUtils.PerformanceHintWitness(
                    baseQuery, counterfactualQuery, candidate, defaultPlan, counterfactualPlan, planEvidence, false,
                    scheduledCandidate.getRequestedStrategy(), scheduledCandidate.getFallbackReason().name());
            annotateWitnessMetadata(witness, skeletonKey, feedbackManager);
            planCandidates.add(witness);
            telemetry.count("pairs.pre_refresh_qualified");
        }

        if (planCandidates.isEmpty()) {
            telemetry.reject("pre_refresh", "no_qualified_pairs");
            if (qpgEnabled()) {
                return;
            }
            throw new IgnoreMeException();
        }
        List<MariaDBPerformanceHintOracleUtils.PerformanceHintWitness> reportCandidates = validateRuntime(
                planCandidates, telemetry);
        if (reportCandidates.isEmpty()) {
            telemetry.reject("runtime_validation", "no_reportable_a_i_candidate");
            if (qpgEnabled()) {
                return;
            }
            throw new IgnoreMeException();
        }

        MariaDBPerformanceHintOracleUtils.PerformanceHintWitness firstFormalWitness = null;
        Path firstFormalReport = null;
        int confirmedReports = 0;
        for (MariaDBPerformanceHintOracleUtils.PerformanceHintWitness reportCandidate : reportCandidates) {
            if (!validateDeferredResultEquivalence(reportCandidate, telemetry)) {
                continue;
            }
            confirmedReports++;
            telemetry.count("pairs.oracle_qualified");
            if (getMariaDBOptions().enablePerformanceExplainAnalyze) {
                collectExplainAnalyze(reportCandidate);
            }
            boolean formalFailure = reportCandidate.getNineCellCategory().isConfirmedHarmful();
            if (formalFailure && firstFormalWitness == null) {
                firstFormalWitness = reportCandidate;
            }
            Path reportDir = persistReportCandidate(reportCandidate, feedbackManager, telemetry);
            if (formalFailure && reportDir != null && firstFormalReport == null) {
                firstFormalReport = reportDir;
            }
        }
        if (confirmedReports == 0) {
            telemetry.reject("runtime_validation", "no_strictly_equivalent_report_candidate");
            if (qpgEnabled()) {
                return;
            }
            throw new IgnoreMeException();
        }
        if (firstFormalWitness != null) {
            if (getMariaDBOptions().continueAfterPerformanceFormalWitness) {
                telemetry.count("formal_witness.continued");
                return;
            }
            telemetry.count("formal_witness.fail_fast");
            enforceFormalWitnessPolicy(false, firstFormalWitness, firstFormalReport);
        }
    }

    /*
     * Keeps the traditional SQLancer failure mode explicit and independently testable. A performance campaign can opt
     * out only after the fully validated witness has been frozen and submitted for reporting.
     */
    static void enforceFormalWitnessPolicy(boolean continueAfterFormalWitness,
            MariaDBPerformanceHintOracleUtils.PerformanceHintWitness witness, Path reportDirectory) {
        if (!continueAfterFormalWitness) {
            throw new AssertionError(
                    MariaDBPerformanceHintOracleUtils.createAssertionMessage(witness, reportDirectory));
        }
    }

    @Override
    public String getLastQueryString() {
        return lastQueryString;
    }

    private boolean qpgEnabled() {
        return state.getOptions().enableQPG();
    }

    private List<CounterfactualCandidate> materializeHintCandidates(String baseQuery,
            List<HintedQueryCandidate> selectedHintCandidates) {
        List<CounterfactualCandidate> candidates = new ArrayList<>();
        for (HintedQueryCandidate hint : selectedHintCandidates) {
            // Keep MariaDB's established SELECT /*+ ... */ materialization exactly on
            // the HINT path. Rewrites arrive as complete SQL from their provider.
            candidates.add(CounterfactualCandidate.hint(
                    MariaDBPerformanceHintOracleUtils.injectHint(baseQuery, hint.getHintSql()), hint.getHintFamily(),
                    hint.getHintType(), hint.getHintSql(), hint.getTableNames(), hint.getIndexNames()));
        }
        return candidates;
    }

    private List<CounterfactualCandidate> collectRewriteCandidates(String baseQuery,
            List<MariaDBTable> referencedTables, MariaDBPerformanceHintTelemetry telemetry) {
        telemetry.count("rewrite_provider.requests");
        List<String> targetTables = referencedTables.stream().map(MariaDBTable::getName).collect(Collectors.toList());
        List<String> targetIndexes = referencedTables.stream().flatMap(table -> table.getIndexes().stream())
                .map(index -> index.getIndexName()).collect(Collectors.toList());
        QueryRewriteRequest request = new QueryRewriteRequest(baseQuery, "MariaDB",
                MariaDBPerformanceHintOracleUtils.MAX_HINTED_VARIANTS, buildSchemaSummary(referencedTables));
        List<CounterfactualCandidate> candidates = CounterfactualCandidateScheduler.collectValidatedRewriteCandidates(
                request, rewriteProvider, rewriteEquivalenceOracle, targetTables, targetIndexes);
        telemetry.add("rewrite_candidates.admitted", candidates.size());
        if (rewriteProvider instanceof WeTuneStaticRewriteProvider) {
            logDebugNote(((WeTuneStaticRewriteProvider) rewriteProvider).getLastDiagnostic());
        }
        return candidates;
    }

    private static QueryRewriteSchemaSummary buildSchemaSummary(List<MariaDBTable> tables) {
        List<TableSummary> summaries = new ArrayList<>();
        for (MariaDBTable table : tables) {
            List<ColumnSummary> columns = table.getColumns().stream()
                    .map(column -> new ColumnSummary(column.getName(),
                            column.getType() == null ? "UNKNOWN" : column.getType().name()))
                    .collect(Collectors.toList());
            List<IndexSummary> indexes = table.getIndexes().stream()
                    .map(index -> new IndexSummary(index.getIndexName(), index.getOrderedColumns()))
                    .collect(Collectors.toList());
            summaries.add(new TableSummary(table.getName(), columns, indexes));
        }
        return new QueryRewriteSchemaSummary(summaries);
    }

    private BaseQuery generateBaseQuery() {
        Family family = state.getDbmsSpecificOptions().performanceQueryGeneratorMode.chooseFamily();
        if (family != Family.STRUCTURED) {
            ExpandedPerformanceQuery.Result<MariaDBTable, MariaDBColumn> expanded = MariaDBExpandedQueryGenerator
                    .generate(state, state.getSchema().getDatabaseTables(), family,
                            reason -> MariaDBPerformanceHintTelemetry.getInstance(state)
                                    .count("query_generation." + reason));
            lastGenerationMetadata = expanded.metadata;
            return new BaseQuery(expanded.sql, expanded.tables);
        }
        BaseQuery base = generateStructuredBaseQuery();
        lastGenerationMetadata = PerformanceQueryMetadata
                .structured(state.getDbmsSpecificOptions().performanceQueryGeneratorMode, base.getSql());
        return base;
    }

    private BaseQuery generateStructuredBaseQuery() {
        List<MariaDBTable> selectedTables = getSelectedTables();
        if (selectedTables.isEmpty() || selectedTables.stream().noneMatch(table -> !table.getIndexes().isEmpty())) {
            throw new IgnoreMeException();
        }
        List<QualifiedColumn> qualifiedColumns = collectQualifiedColumns(selectedTables);
        if (qualifiedColumns.isEmpty()) {
            throw new IgnoreMeException();
        }

        List<QualifiedColumn> fetchColumns = pickQualifiedColumns(qualifiedColumns, 1, 3);
        QualifiedColumn fetchColumn = fetchColumns.get(0);
        boolean distinct = Randomly.getBoolean();
        boolean groupBy = !distinct && Randomly.getBoolean();
        boolean orderBy = distinct || groupBy || Randomly.getBoolean();
        boolean limit = orderBy || distinct || groupBy || Randomly.getBoolean();

        StringBuilder sb = new StringBuilder("SELECT ");
        if (distinct) {
            sb.append("DISTINCT ");
        }
        sb.append(fetchColumn.sql).append(" AS ref0");
        for (int i = 1; i < fetchColumns.size(); i++) {
            sb.append(", ").append(fetchColumns.get(i).sql);
        }
        sb.append(" FROM ");
        List<String> tableNames = selectedTables.stream().map(MariaDBTable::getName).collect(Collectors.toList());
        MariaDBPerformanceHintTelemetry telemetry = MariaDBPerformanceHintTelemetry.getInstance(state);
        double bindingProb = getMariaDBOptions().performancePredicateBindingProbability;
        boolean useDataAware = bindingProb > 0.0 && state.getPdgIrContext() != null
                && state.getPdgIrContext().getInMemoryIndex() != null
                && (bindingProb >= 1.0 || Randomly.getPercentage() < bindingProb);
        List<JoinEdgeSpec> joinEdges = new ArrayList<>();
        sb.append(buildFromClause(selectedTables, joinEdges, useDataAware));

        if (useDataAware) {
            List<PredicateSlot> slots = buildBindingSlots(qualifiedColumns);
            boolean conjunctive = slots.size() <= 1 || Randomly.getBoolean();
            boolean hasResultCollapsing = distinct || groupBy || limit;
            DataAwarePredicateBinder.BindingResult binding = DataAwarePredicateBinder.bind(state.getPdgIrContext(),
                    tableNames, joinEdges, slots, conjunctive, Randomly.fromOptions(0, 1, 2), hasResultCollapsing,
                    telemetry);
            if (!binding.getWhereSql().isEmpty()) {
                sb.append(" WHERE ").append(binding.getWhereSql());
            }
        } else {
            String predicate = "";
            if (Randomly.getBoolean()) {
                predicate = buildPredicate(qualifiedColumns);
                if (!predicate.isEmpty()) {
                    sb.append(" WHERE ").append(predicate);
                }
            }
            DataAwarePredicateBinder.recordUnawareTelemetry(state.getPdgIrContext(), tableNames, joinEdges, predicate,
                    telemetry);
        }
        if (groupBy) {
            sb.append(" GROUP BY ")
                    .append(pickQualifiedColumns(qualifiedColumns, 1, Math.min(2, qualifiedColumns.size())).stream()
                            .map(column -> column.sql).collect(Collectors.joining(", ")));
        }
        if (orderBy) {
            sb.append(" ORDER BY ").append(buildOrderByClause(qualifiedColumns));
        }
        if (limit) {
            sb.append(" LIMIT ").append(chooseLimitValue());
        }
        return new BaseQuery(sb.toString(), selectedTables);
    }

    private List<MariaDBTable> getSelectedTables() {
        List<MariaDBTable> allTables = state.getSchema().getDatabaseTables();
        if (allTables.isEmpty()) {
            return List.of();
        }
        List<MariaDBTable> indexedTables = allTables.stream().filter(table -> !table.getIndexes().isEmpty())
                .collect(Collectors.toList());
        if (indexedTables.isEmpty()) {
            return List.of();
        }
        int maxTables = Math.min(MariaDBPerformanceHintOracleUtils.MAX_TABLES, allTables.size());
        int minTables = Math.min(2, allTables.size());
        int nrTables = state.getRandomly().getInteger(minTables, maxTables + 1);
        List<MariaDBTable> selected = new ArrayList<>();
        selected.add(Randomly.fromList(indexedTables));
        List<MariaDBTable> remaining = new ArrayList<>(allTables);
        remaining.removeAll(selected);
        while (selected.size() < nrTables && !remaining.isEmpty()) {
            MariaDBTable next = Randomly.fromList(remaining);
            selected.add(next);
            remaining.remove(next);
        }
        return selected;
    }

    private boolean isSupportedQuery(String query) {
        return query.startsWith("SELECT ") && !query.contains("RAND(") && !query.contains("UUID(")
                && !query.contains("CURRENT_TIMESTAMP");
    }

    private List<QualifiedColumn> collectQualifiedColumns(List<MariaDBTable> tables) {
        List<QualifiedColumn> columns = new ArrayList<>();
        for (MariaDBTable table : tables) {
            for (MariaDBColumn column : table.getColumns()) {
                columns.add(new QualifiedColumn(table.getName() + "." + column.getName(), column.getType()));
            }
        }
        return columns;
    }

    private String buildPredicate(List<QualifiedColumn> columns) {
        String first = buildSimplePredicate(Randomly.fromList(columns));
        if (!Randomly.getBoolean() || columns.size() == 1) {
            return first;
        }
        String second = buildSimplePredicate(Randomly.fromList(columns));
        return "(" + first + ")" + (Randomly.getBoolean() ? " AND " : " OR ") + "(" + second + ")";
    }

    private MariaDBPerformanceHintOracleUtils.PlanSummary explain(String query, boolean required) throws SQLException {
        String explainQuery = "EXPLAIN FORMAT=JSON " + query;
        logQuery(explainQuery);
        SQLQueryAdapter adapter = new SQLQueryAdapter(explainQuery, errors);
        try (SQLancerResultSet rs = adapter.executeAndGet(state)) {
            if (rs == null || !rs.next()) {
                if (required) {
                    throw new IgnoreMeException();
                }
                return null;
            }
            String explainJson = rs.getString(1);
            MariaDBPerformanceHintOracleUtils.PlanSummary plan = MariaDBPerformanceHintOracleUtils
                    .parsePlan(explainJson);
            if (plan.getQueryCost() == null || plan.getQueryCost() <= 0) {
                if (required) {
                    throw new IgnoreMeException();
                }
                return null;
            }
            return plan;
        } catch (AssertionError | Exception e) {
            if (DEBUG_STDERR) {
                System.err.println("[MariaDB-PERFHINT] EXPLAIN_FAILED required=" + required + " query=" + query);
                System.err.println(
                        "[MariaDB-PERFHINT] EXPLAIN_EXCEPTION " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (required) {
                throw new IgnoreMeException();
            }
            return null;
        }
    }

    private boolean validateDeferredResultEquivalence(MariaDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            MariaDBPerformanceHintTelemetry telemetry) {
        CounterfactualCandidate candidate = witness.getCounterfactualCandidate();
        try {
            telemetry.count("result_equivalence.deferred_attempted");
            String counterfactualSide = counterfactualSide(candidate);
            List<List<String>> baseRows = fetchResultRowsWithTimeout(witness.getBaseQuery(),
                    witness.getCounterfactualQuery(), candidate, witness.getDefaultPlan(), witness.getHintedPlan(),
                    "RESULT_EQUIV_DEFERRED_BASE", "BASE", witness.getRequestedStrategy(), witness.getFallbackReason(),
                    telemetry);
            List<List<String>> counterfactualRows = fetchResultRowsWithTimeout(witness.getBaseQuery(),
                    witness.getCounterfactualQuery(), candidate, witness.getDefaultPlan(), witness.getHintedPlan(),
                    "RESULT_EQUIV_DEFERRED_" + counterfactualSide, counterfactualSide, witness.getRequestedStrategy(),
                    witness.getFallbackReason(), telemetry);
            boolean equivalent = MariaDBPerformanceHintOracleUtils.resultRowsMatch(baseRows, counterfactualRows);
            if (equivalent) {
                witness.setResultEquivalent(true);
                telemetry.count("result_equivalence.deferred_confirmed");
                telemetry.count("pairs.equivalent_plan_different_confirmed");
            } else {
                telemetry.count("result_equivalence.deferred_rejected");
                telemetry.reject("result_equivalence_deferred", "result_mismatch");
            }
            return equivalent;
        } catch (AssertionError | Exception e) {
            telemetry.count("result_equivalence.deferred_rejected");
            telemetry.reject("result_equivalence_deferred", "execution_failed_or_timeout");
            return false;
        }
    }

    private Path persistReportCandidate(MariaDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            MariaDBPerformanceHintFeedbackManager feedbackManager, MariaDBPerformanceHintTelemetry telemetry)
            throws IOException {
        MariaDBPerformanceHintFeedbackManager.WitnessDecision writeDecision = feedbackManager.recordWitness(witness,
                getMariaDBOptions());
        witness.setSignatureObservationCount(writeDecision.getSignatureObservationCount());
        witness.setSignatureWrittenReports(writeDecision.getSignatureWrittenReports());
        witness.setSkeletonObservationCount(writeDecision.getSkeletonObservationCount());
        witness.setFamilyObservationCount(writeDecision.getFamilyObservationCount());
        witness.setRootCauseObservationCount(writeDecision.getRootCauseObservationCount());
        witness.setRootCauseWrittenReports(writeDecision.getRootCauseWrittenReports());
        witness.setSuppressedDuplicateCount(writeDecision.getTotalSuppressedReports());
        witness.setRootCauseSuppressedCount(writeDecision.getTotalRootCauseSuppressedReports());
        witness.setSelectionReason(writeDecision.getSelectionReason());
        witness.setRootCauseRepresentativeRank(writeDecision.getRepresentativeRoles());
        witness.setRootCauseNoveltyClass(
                PerformanceHintFeedbackState.classifyRootCauseNovelty(writeDecision.getRootCauseObservationCount(),
                        getMariaDBOptions().performanceFeedbackRootCauseHardCap).name());
        if (!writeDecision.shouldWriteReport()) {
            telemetry.count("reports.suppressed");
            return null;
        }
        boolean formalFailure = witness.getNineCellCategory().isConfirmedHarmful();
        MariaDBPerformanceHintFeedbackManager.ReportReservation reservation = writeDecision.getReservation();
        if (reservation == null) {
            throw new IOException("accepted PERFORMANCE_HINT report is missing its feedback reservation");
        }
        PerformanceHintReportSnapshot snapshot;
        try {
            snapshot = MariaDBPerformanceHintOracleUtils.snapshotWitness(state, witness,
                    !witness.getNineCellCategory().isConfirmedHarmful());
            telemetry.count("reports.snapshot_created");
        } catch (RuntimeException exception) {
            telemetry.count("reports.snapshot_failed");
            try {
                feedbackManager.failReservation(reservation, exception);
            } catch (IOException ignored) {
                telemetry.count("reports.feedback_failure_after_snapshot_failure");
            }
            throw exception;
        }
        if (getMariaDBOptions().enablePerformanceAsyncReportWrites) {
            MariaDBPerformanceHintReportWriter.getInstance(getMariaDBOptions().performanceReportQueueCapacity).submit(
                    snapshot, reservation, feedbackManager, telemetry,
                    getMariaDBOptions().performanceReportQueueOfferMillis, formalFailure);
            return snapshot.getFinalReportDirectory();
        }
        telemetry.count("reports.selected");
        try {
            Path reportDir = MariaDBPerformanceHintOracleUtils.writeSnapshot(snapshot);
            feedbackManager.completeReservation(reservation, reportDir);
            telemetry.count("reports.written");
            telemetry.count(formalFailure ? "reports.formal_written" : "reports.observation_written");
            state.getState().getLocalState().log(witness.getBaseQuery());
            state.getState().getLocalState().log(witness.getCounterfactualQuery());
            return reportDir;
        } catch (IOException exception) {
            telemetry.count("reports.write_failed");
            try {
                feedbackManager.failReservation(reservation, exception);
            } catch (IOException ignored) {
                telemetry.count("reports.feedback_failure_after_write_failure");
            }
            throw exception;
        }
    }

    private List<MariaDBPerformanceHintOracleUtils.PerformanceHintWitness> validateRuntime(
            List<MariaDBPerformanceHintOracleUtils.PerformanceHintWitness> planCandidates,
            MariaDBPerformanceHintTelemetry telemetry) throws Exception {
        List<MariaDBPerformanceHintOracleUtils.PerformanceHintWitness> sortedCandidates = new ArrayList<>(
                planCandidates);
        sortedCandidates.sort(Comparator.comparingDouble(candidate -> -candidate.getPlanInterestScore()));
        if (sortedCandidates.isEmpty()) {
            return List.of();
        }

        MariaDBPerformanceHintOracleUtils.PerformanceHintWitness baselineSource = sortedCandidates.get(0);
        RuntimeSeries baseRuntime;
        try {
            QueryExecution baseWarmup = executeAndConsumeWithTimeout(baselineSource.getBaseQuery());
            telemetry.count("runtime.warmup_base_samples");
            telemetry.add("runtime.warmup_samples", 1L);
            telemetry.add("runtime.timeout_samples", baseWarmup.isTimedOut() ? 1L : 0L);
            maybeRecordTimeout(baselineSource, baseWarmup, "RUNTIME_WARMUP_BASE");
            baseRuntime = measureRuntimeSeries(baselineSource.getBaseQuery(), "BASE", baselineSource.getDefaultPlan(),
                    getMariaDBOptions().performanceRepeatCount, 1, true, telemetry);
            telemetry.add("runtime.timeout_samples", baseRuntime.getSummary().getTimeoutCount());
        } catch (AssertionError | Exception e) {
            telemetry.reject("runtime_validation", "base_execution_failed_or_timeout");
            return List.of();
        }

        List<MariaDBPerformanceHintOracleUtils.PerformanceHintWitness> reportCandidates = new ArrayList<>();
        for (MariaDBPerformanceHintOracleUtils.PerformanceHintWitness candidate : sortedCandidates) {
            try {
                telemetry.count("pairs.runtime_attempted");
                QueryExecution counterfactualWarmup = executeAndConsumeWithTimeout(candidate.getCounterfactualQuery());
                telemetry.count("runtime.warmup_counterfactual_samples");
                telemetry.add("runtime.warmup_samples", 1L);
                telemetry.add("runtime.timeout_samples", counterfactualWarmup.isTimedOut() ? 1L : 0L);
                maybeRecordTimeout(candidate, counterfactualWarmup,
                        "RUNTIME_WARMUP_" + candidate.getCounterfactualRuntimeSide());
                RuntimeSeries counterfactualRuntime = measureRuntimeSeries(candidate.getCounterfactualQuery(),
                        candidate.getCounterfactualRuntimeSide(), candidate.getHintedPlan(),
                        getMariaDBOptions().performanceRepeatCount, baseRuntime.getSamples().size() + 1, false,
                        telemetry);
                MariaDBPerformanceHintOracleUtils.RuntimeSummary defaultRuntime = baseRuntime.getSummary();
                MariaDBPerformanceHintOracleUtils.RuntimeSummary hintedRuntime = counterfactualRuntime.getSummary();
                candidate.setTimeoutCapMs(getRuntimeTimeoutMs());
                maybeRecordMeasuredTimeouts(candidate, defaultRuntime, hintedRuntime, candidate == baselineSource);
                candidate.setDefaultRuntime(defaultRuntime);
                candidate.setHintedRuntime(hintedRuntime);
                candidate.setRuntimeSamples(combineRuntimeSamples(baseRuntime, counterfactualRuntime));
                telemetry.add("runtime.timeout_samples", hintedRuntime.getTimeoutCount());
                telemetry.count("pairs.runtime_measured");
                lastGenerationMetadata.record(telemetry, "measured");

                CostRuntimeObservation observation = buildRuntimeObservation(candidate);
                PerformanceNineCellCategory nineCell = PerformanceNineCellClassifier
                        .classify(getPerformanceNineCellOptions(), observation);
                candidate.setNineCellCategory(nineCell);
                telemetry.count("pairs.nine_cell_classified");
                telemetry.count("nine_cell." + nineCell.getCode().toLowerCase(Locale.ROOT));
                if (!shouldReportNineCellCandidate(nineCell, observation)) {
                    telemetry.reject("nine_cell_report_policy",
                            "cell_" + nineCell.getCode().toLowerCase(Locale.ROOT) + "_not_reportable");
                    continue;
                }
                telemetry.count("pairs.nine_cell_reportable");
                telemetry.count("nine_cell.reportable." + nineCell.getCode().toLowerCase(Locale.ROOT));
                reportCandidates.add(candidate);
            } catch (AssertionError | Exception e) {
                telemetry.reject("runtime_validation", "execution_failed_or_timeout");
            }
        }
        return reportCandidates;
    }

    private RuntimeSeries measureRuntimeSeries(String query, String side,
            MariaDBPerformanceHintOracleUtils.PlanSummary plan, int repeatCount, int firstSampleSequence,
            boolean baseSide, MariaDBPerformanceHintTelemetry telemetry) throws Exception {
        List<Double> runsMs = new ArrayList<>();
        List<MariaDBPerformanceHintOracleUtils.RuntimeSample> runtimeSamples = new ArrayList<>();
        int timeoutCount = 0;
        for (int i = 0; i < repeatCount; i++) {
            QueryExecution execution = executeAndConsumeWithTimeout(query);
            telemetry.count(
                    baseSide ? "runtime.measurement_base_samples" : "runtime.measurement_counterfactual_samples");
            if (baseSide && i == 0 && !execution.isTimedOut()) {
                DataAwarePredicateBinder.recordRuntimeBaseRowCount(telemetry, execution.getRowCount(), query);
            }
            runsMs.add(execution.getElapsedMs());
            if (execution.isTimedOut()) {
                timeoutCount++;
            }
            runtimeSamples.add(new MariaDBPerformanceHintOracleUtils.RuntimeSample(firstSampleSequence + i, side, query,
                    plan.getExplainJson(), execution.getElapsedMs(), execution.isTimedOut()));
        }
        return new RuntimeSeries(new MariaDBPerformanceHintOracleUtils.RuntimeSummary(runsMs, timeoutCount),
                runtimeSamples);
    }

    private static List<MariaDBPerformanceHintOracleUtils.RuntimeSample> combineRuntimeSamples(
            RuntimeSeries baseRuntime, RuntimeSeries counterfactualRuntime) {
        List<MariaDBPerformanceHintOracleUtils.RuntimeSample> samples = new ArrayList<>(baseRuntime.getSamples());
        samples.addAll(counterfactualRuntime.getSamples());
        return samples;
    }

    private static CostRuntimeObservation buildRuntimeObservation(
            MariaDBPerformanceHintOracleUtils.PerformanceHintWitness candidate) {
        return new CostRuntimeObservation(candidate.getDefaultPlan().getQueryCost(),
                candidate.getHintedPlan().getQueryCost(), candidate.getDefaultRuntime().getMedianMs(),
                candidate.getHintedRuntime().getMedianMs(), candidate.getDefaultRuntime().getMeanMs(),
                candidate.getHintedRuntime().getMeanMs(), candidate.getDefaultRuntime().getMinMs(),
                candidate.getHintedRuntime().getMinMs(), candidate.getDefaultRuntime().getMaxMs(),
                candidate.getHintedRuntime().getMaxMs(), candidate.getDefaultTimeoutCount(),
                candidate.getHintedTimeoutCount(), candidate.getDefaultRuntime().getRunsMs(),
                candidate.getHintedRuntime().getRunsMs());
    }

    private boolean shouldReportNineCellCandidate(PerformanceNineCellCategory nineCell,
            CostRuntimeObservation observation) {
        return PerformanceNineCellReportPolicy.shouldReport(getMariaDBOptions().performanceTaxonomyEnable,
                nineCellReportCategories, nineCell, observation,
                getMariaDBOptions().performanceReportHarmfulRuntimeRatio,
                getMariaDBOptions().performanceReportCostAnomalyRatio);
    }

    private QueryExecution executeAndConsumeWithTimeout(String query) throws Exception {
        logQuery(query);
        SQLQueryAdapter adapter = new SQLQueryAdapter(query, errors);
        long start = System.nanoTime();
        try (SQLancerResultSet rs = adapter.executeAndGet(state, getRuntimeTimeoutSeconds())) {
            if (rs == null) {
                throw new IgnoreMeException();
            }
            long rowCount = 0L;
            while (rs.next()) {
                rowCount++;
            }
            long end = System.nanoTime();
            return QueryExecution.completed((end - start) / 1_000_000.0, rowCount);
        } catch (SQLTimeoutException e) {
            return QueryExecution.timedOut(getRuntimeTimeoutMs());
        }
    }

    private void collectExplainAnalyze(MariaDBPerformanceHintOracleUtils.PerformanceHintWitness witness)
            throws Exception {
        MariaDBPerformanceHintOracleUtils.AnalyzeSummary defaultAnalyze = explainAnalyze(witness.getBaseQuery());
        MariaDBPerformanceHintOracleUtils.AnalyzeSummary hintedAnalyze = explainAnalyze(witness.getHintedQuery());
        if (defaultAnalyze != null && hintedAnalyze != null) {
            witness.setDefaultAnalyze(defaultAnalyze);
            witness.setHintedAnalyze(hintedAnalyze);
        }
    }

    private MariaDBPerformanceHintOracleUtils.AnalyzeSummary explainAnalyze(String query) throws Exception {
        String explainAnalyzeQuery = "ANALYZE FORMAT=JSON " + query;
        logQuery(explainAnalyzeQuery);
        SQLQueryAdapter adapter = new SQLQueryAdapter(explainAnalyzeQuery, errors);
        try (SQLancerResultSet rs = adapter.executeAndGet(state, getRuntimeTimeoutSeconds())) {
            if (rs == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                if (sb.length() > 0) {
                    sb.append(System.lineSeparator());
                }
                sb.append(rs.getString(1));
            }
            if (sb.length() == 0) {
                return null;
            }
            return MariaDBPerformanceHintOracleUtils.parseAnalyzeSummary(sb.toString());
        } catch (SQLTimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<List<String>> fetchResultRowsWithTimeout(String baseQuery, String counterfactualQuery,
            CounterfactualCandidate candidate, MariaDBPerformanceHintOracleUtils.PlanSummary defaultPlan,
            MariaDBPerformanceHintOracleUtils.PlanSummary counterfactualPlan, String phase, String timedOutSide,
            CounterfactualMutationKind requestedStrategy, String fallbackReason,
            MariaDBPerformanceHintTelemetry telemetry) throws Exception {
        telemetry.count("result_equivalence.executions");
        telemetry.count("BASE".equals(timedOutSide) ? "result_equivalence.base_executions"
                : "result_equivalence.counterfactual_executions");
        String query = "BASE".equals(timedOutSide) ? baseQuery : counterfactualQuery;
        logQuery(query);
        SQLQueryAdapter adapter = new SQLQueryAdapter(query, errors);
        try (SQLancerResultSet rs = adapter.executeAndGet(state, getRuntimeTimeoutSeconds())) {
            if (rs == null) {
                throw new IgnoreMeException();
            }
            int columnCount = rs.getColumnCount();
            List<List<String>> result = new ArrayList<>();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int column = 1; column <= columnCount; column++) {
                    row.add(rs.getString(column));
                }
                result.add(row);
            }
            telemetry.count("result_equivalence.execution_succeeded");
            return result;
        } catch (SQLTimeoutException e) {
            if (candidate != null) {
                telemetry.count("timeout_artifacts.written");
                MariaDBPerformanceHintOracleUtils.writeTimeoutObservation(state,
                        new MariaDBPerformanceHintOracleUtils.TimeoutObservation(baseQuery, counterfactualQuery,
                                candidate, defaultPlan, counterfactualPlan, phase, timedOutSide, getRuntimeTimeoutMs(),
                                getRuntimeTimeoutMs(), requestedStrategy, fallbackReason));
            }
            throw new IgnoreMeException();
        }
    }

    private void maybeRecordTimeout(MariaDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            QueryExecution execution, String phase) throws IOException {
        if (!execution.isTimedOut()) {
            return;
        }
        String timedOutSide = phase.endsWith("BASE") ? "BASE" : witness.getCounterfactualRuntimeSide();
        MariaDBPerformanceHintTelemetry.getInstance(state).count("timeout_artifacts.written");
        MariaDBPerformanceHintOracleUtils.writeTimeoutObservation(state,
                new MariaDBPerformanceHintOracleUtils.TimeoutObservation(witness.getBaseQuery(),
                        witness.getCounterfactualQuery(), witness.getCounterfactualCandidate(),
                        witness.getDefaultPlan(), witness.getHintedPlan(), phase, timedOutSide, getRuntimeTimeoutMs(),
                        execution.getElapsedMs(), witness.getRequestedStrategy(), witness.getFallbackReason()));
    }

    private void maybeRecordMeasuredTimeouts(MariaDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            MariaDBPerformanceHintOracleUtils.RuntimeSummary defaultRuntime,
            MariaDBPerformanceHintOracleUtils.RuntimeSummary hintedRuntime, boolean recordBaseTimeout)
            throws IOException {
        if (recordBaseTimeout && defaultRuntime.getTimeoutCount() > 0) {
            MariaDBPerformanceHintTelemetry.getInstance(state).count("timeout_artifacts.written");
            MariaDBPerformanceHintOracleUtils.writeTimeoutObservation(state,
                    new MariaDBPerformanceHintOracleUtils.TimeoutObservation(witness.getBaseQuery(),
                            witness.getCounterfactualQuery(), witness.getCounterfactualCandidate(),
                            witness.getDefaultPlan(), witness.getHintedPlan(), "RUNTIME_MEASURE_BASE", "BASE",
                            getRuntimeTimeoutMs(), getRuntimeTimeoutMs(), witness.getRequestedStrategy(),
                            witness.getFallbackReason()));
        }
        if (hintedRuntime.getTimeoutCount() > 0) {
            MariaDBPerformanceHintTelemetry.getInstance(state).count("timeout_artifacts.written");
            MariaDBPerformanceHintOracleUtils.writeTimeoutObservation(state,
                    new MariaDBPerformanceHintOracleUtils.TimeoutObservation(witness.getBaseQuery(),
                            witness.getCounterfactualQuery(), witness.getCounterfactualCandidate(),
                            witness.getDefaultPlan(), witness.getHintedPlan(),
                            "RUNTIME_MEASURE_" + witness.getCounterfactualRuntimeSide(),
                            witness.getCounterfactualRuntimeSide(), getRuntimeTimeoutMs(), getRuntimeTimeoutMs(),
                            witness.getRequestedStrategy(), witness.getFallbackReason()));
        }
    }

    private static String counterfactualSide(CounterfactualCandidate candidate) {
        return candidate.getKind() == CounterfactualMutationKind.HINT ? "HINTED" : "COUNTERFACTUAL";
    }

    private void annotateWitnessMetadata(MariaDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            String skeletonKey, MariaDBPerformanceHintFeedbackManager feedbackManager) {
        witness.setQueryGenerationMetadata(lastGenerationMetadata);
        witness.setSkeletonKey(skeletonKey);
        RootCauseClassification rootCause = MariaDBPerformanceHintOracleUtils.classifyRootCause(witness);
        witness.setRootCauseCategory(rootCause.getIntentCategory());
        witness.setRootCauseIntentCategory(rootCause.getIntentCategory());
        witness.setRootCauseMechanismKey(rootCause.getMechanismKey());
        witness.setFamilyKey(MariaDBPerformanceHintOracleUtils.buildFamilyKey(witness));
        witness.setPlanSignatureKey(MariaDBPerformanceHintOracleUtils.buildPlanSignatureKey(witness));
        witness.setRootCauseSignatureKey(rootCause.getSignatureKey());
        if (witness.isHintCounterfactual()) {
            witness.setHintPolicyDecision(PerformanceHintHintPolicy.describePolicyDecision(getHintPolicyConfig()));
            witness.setHintFamilyQuotaApplied(PerformanceHintHintPolicy.describeAppliedQuota(getHintPolicyConfig(),
                    List.of(new GenericHintDescriptor(witness.getCounterfactualCandidate()))));
        } else {
            witness.setHintPolicyDecision("not_applicable_to_query_rewrite");
            witness.setHintFamilyQuotaApplied("not_applicable_to_query_rewrite");
        }
        MariaDBPerformanceHintFeedbackManager.NoveltySnapshot noveltySnapshot = feedbackManager
                .estimateNoveltySnapshot(witness, getMariaDBOptions());
        witness.setNoveltyScore(noveltySnapshot.getOverallNovelty());
        witness.setRootCauseNoveltyScore(noveltySnapshot.getRootCauseNovelty());
    }

    private static final class GenericHintDescriptor implements PerformanceHintHintPolicy.CandidateDescriptor {
        private final CounterfactualCandidate candidate;

        private GenericHintDescriptor(CounterfactualCandidate candidate) {
            this.candidate = candidate;
        }

        @Override
        public String getHintFamily() {
            return candidate.getFamily();
        }

        @Override
        public String getHintType() {
            return candidate.getMutationId();
        }
    }

    private String buildSimplePredicate(QualifiedColumn column) {
        switch (column.type) {
        case INT:
        case REAL:
            return Randomly.fromOptions(column.sql + " IS NULL", column.sql + " IS NOT NULL", column.sql + " > 0",
                    column.sql + " < 0", column.sql + " = 0");
        case VARCHAR:
            return Randomly.fromOptions(column.sql + " IS NULL", column.sql + " IS NOT NULL", column.sql + " = ''",
                    column.sql + " <> ''");
        case BOOLEAN:
            return Randomly.fromOptions(column.sql + " IS TRUE", column.sql + " IS FALSE", column.sql + " IS NULL");
        default:
            throw new AssertionError(column.type);
        }
    }

    private List<QualifiedColumn> pickQualifiedColumns(List<QualifiedColumn> columns, int minCount, int maxCount) {
        int boundedMax = Math.min(maxCount, columns.size());
        int count = state.getRandomly().getInteger(minCount, boundedMax + 1);
        return new ArrayList<>(Randomly.nonEmptySubset(columns, count));
    }

    private List<PredicateSlot> buildBindingSlots(List<QualifiedColumn> columns) {
        List<PredicateSlot> slots = new ArrayList<>(2);
        slots.add(toBindingSlot(Randomly.fromList(columns)));
        if (columns.size() > 1 && Randomly.getBoolean()) {
            slots.add(toBindingSlot(Randomly.fromList(columns)));
        }
        return slots;
    }

    private PredicateSlot toBindingSlot(QualifiedColumn column) {
        int dot = column.sql.indexOf('.');
        String tableName = dot >= 0 ? column.sql.substring(0, dot) : "";
        String colName = dot >= 0 ? column.sql.substring(dot + 1) : column.sql;
        Operator op = column.type == MariaDBDataType.VARCHAR ? Randomly.fromOptions(Operator.EQ, Operator.NEQ)
                : Randomly.fromOptions(Operator.EQ, Operator.LT, Operator.LTE, Operator.GT, Operator.GTE);
        return new PredicateSlot(tableName, colName, op);
    }

    private String buildFromClause(List<MariaDBTable> selectedTables, List<JoinEdgeSpec> joinEdges,
            boolean useDataAware) {
        if (selectedTables.size() <= 1) {
            return selectedTables.stream().map(MariaDBTable::getName).collect(Collectors.joining(", "));
        }
        StringBuilder sb = new StringBuilder(selectedTables.get(0).getName());
        for (int i = 1; i < selectedTables.size(); i++) {
            MariaDBTable current = selectedTables.get(i);
            MariaDBTable previous = selectedTables.get(i - 1);
            if (previous.getColumns().isEmpty() || current.getColumns().isEmpty()) {
                sb.append(", ").append(current.getName());
                continue;
            }
            MariaDBTable matchedLeft = null;
            String leftColName = null;
            String rightColName = null;
            if (useDataAware) {
                for (int j = 0; j < i; j++) {
                    MariaDBTable candidateLeft = selectedTables.get(j);
                    JoinEdgeSpec edge = DataAwarePredicateBinder.findPreferredRelationship(state.getPdgIrContext(),
                            candidateLeft.getName(), current.getName());
                    if (edge != null) {
                        matchedLeft = candidateLeft;
                        leftColName = edge.getLeftColumn();
                        rightColName = edge.getRightColumn();
                        break;
                    }
                }
            }
            if (matchedLeft != null && leftColName != null && rightColName != null) {
                joinEdges.add(new JoinEdgeSpec(matchedLeft.getName(), leftColName, current.getName(), rightColName));
                sb.append(" INNER JOIN ").append(current.getName()).append(" ON ").append(matchedLeft.getName())
                        .append('.').append(leftColName).append(" = ").append(current.getName()).append('.')
                        .append(rightColName);
            } else {
                String previousColumn = getPreferredJoinColumnSql(previous);
                String currentColumn = getPreferredJoinColumnSql(current);
                String prevColOnly = previousColumn.substring(previousColumn.indexOf('.') + 1);
                String currColOnly = currentColumn.substring(currentColumn.indexOf('.') + 1);
                joinEdges.add(new JoinEdgeSpec(previous.getName(), prevColOnly, current.getName(), currColOnly));
                sb.append(" INNER JOIN ").append(current.getName()).append(" ON ").append(previousColumn).append(" = ")
                        .append(currentColumn);
            }
        }
        return sb.toString();
    }

    private String getPreferredJoinColumnSql(MariaDBTable table) {
        MariaDBColumn primaryKeyColumn = table.getColumns().stream().filter(MariaDBColumn::isPrimaryKey).findFirst()
                .orElse(null);
        if (primaryKeyColumn != null) {
            return table.getName() + "." + primaryKeyColumn.getName();
        }
        return table.getName() + "." + table.getColumns().get(0).getName();
    }

    private String buildOrderByClause(List<QualifiedColumn> qualifiedColumns) {
        List<QualifiedColumn> selected = pickQualifiedColumns(qualifiedColumns, 1,
                Math.min(2, qualifiedColumns.size()));
        return selected.stream().map(column -> column.sql + (Randomly.getBoolean() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }

    private long chooseLimitValue() {
        return Randomly.fromOptions(1L, 2L, 3L, 5L, 8L, 12L);
    }

    private PolicyConfig getHintPolicyConfig() {
        return PolicyConfig.fromOptionStrings(getMariaDBOptions().performanceHintIncludeTypes,
                getMariaDBOptions().performanceHintExcludeTypes, getMariaDBOptions().performanceHintFamilyQuota,
                getMariaDBOptions().performanceHintFamilyWeightMode,
                getMariaDBOptions().performanceHintMaxCandidatesPerFamily,
                MariaDBPerformanceHintOracleUtils.MAX_HINTED_VARIANTS);
    }

    private void logQuery(String query) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(query);
            try {
                state.getLogger().getCurrentFileWriter().flush();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    private void logDebugNote(String note) {
        if (DEBUG_STDERR) {
            System.err.println("[MariaDB-PERFHINT] " + note);
        }
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent("-- " + note + System.lineSeparator());
            try {
                state.getLogger().getCurrentFileWriter().flush();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    private MariaDBOptions getMariaDBOptions() {
        return state.getDbmsSpecificOptions();
    }

    private int getRuntimeTimeoutSeconds() {
        return Math.max(1, getMariaDBOptions().performanceTimeoutSeconds);
    }

    private double getRuntimeTimeoutMs() {
        return getRuntimeTimeoutSeconds() * 1000.0;
    }

    private PerformanceNineCellOptions getPerformanceNineCellOptions() {
        return new PerformanceNineCellOptions(getMariaDBOptions().performanceTaxonomyEnable,
                getMariaDBOptions().performanceTaxonomyCostRatioLowerBound,
                getMariaDBOptions().performanceTaxonomyCostRatioUpperBound,
                getMariaDBOptions().performanceTaxonomyRuntimeRatioLowerBound,
                getMariaDBOptions().performanceTaxonomyRuntimeRatioUpperBound,
                getMariaDBOptions().performanceTaxonomyMinRuntimeMedianMs,
                getMariaDBOptions().performanceTaxonomyMinRuntimeAbsGapMs);
    }

    private static final class QueryExecution {
        private final double elapsedMs;
        private final boolean timedOut;
        private final long rowCount;

        private QueryExecution(double elapsedMs, boolean timedOut, long rowCount) {
            this.elapsedMs = elapsedMs;
            this.timedOut = timedOut;
            this.rowCount = rowCount;
        }

        static QueryExecution completed(double elapsedMs, long rowCount) {
            return new QueryExecution(elapsedMs, false, rowCount);
        }

        static QueryExecution timedOut(double elapsedMs) {
            return new QueryExecution(elapsedMs, true, 0L);
        }

        double getElapsedMs() {
            return elapsedMs;
        }

        boolean isTimedOut() {
            return timedOut;
        }

        long getRowCount() {
            return rowCount;
        }
    }

    /** A measured query side together with the samples that produced it. */
    private static final class RuntimeSeries {
        private final MariaDBPerformanceHintOracleUtils.RuntimeSummary summary;
        private final List<MariaDBPerformanceHintOracleUtils.RuntimeSample> samples;

        private RuntimeSeries(MariaDBPerformanceHintOracleUtils.RuntimeSummary summary,
                List<MariaDBPerformanceHintOracleUtils.RuntimeSample> samples) {
            this.summary = summary;
            this.samples = List.copyOf(samples);
        }

        private MariaDBPerformanceHintOracleUtils.RuntimeSummary getSummary() {
            return summary;
        }

        private List<MariaDBPerformanceHintOracleUtils.RuntimeSample> getSamples() {
            return samples;
        }
    }

    private static final class QualifiedColumn {
        private final String sql;
        private final MariaDBDataType type;

        private QualifiedColumn(String sql, MariaDBDataType type) {
            this.sql = sql;
            this.type = type;
        }
    }

    private static final class BaseQuery {
        private final String sql;
        private final List<MariaDBTable> referencedTables;

        private BaseQuery(String sql, List<MariaDBTable> referencedTables) {
            this.sql = sql;
            this.referencedTables = List.copyOf(referencedTables);
        }

        private String getSql() {
            return sql;
        }

        private List<MariaDBTable> getReferencedTables() {
            return referencedTables;
        }
    }
}
