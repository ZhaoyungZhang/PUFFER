package sqlancer.mysql.oracle;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
import sqlancer.common.perfhint.DataAwarePredicateBinder.BoundPredicate;
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
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.ForeignKeySummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.IndexSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.TableSummary;
import sqlancer.common.perfhint.RewriteEquivalenceOracle;
import sqlancer.common.perfhint.ScheduledCounterfactualCandidate;
import sqlancer.common.perfhint.WeTuneStaticRewriteEquivalenceOracle;
import sqlancer.common.perfhint.WeTuneStaticRewriteProvider;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.mysql.MySQLErrors;
import sqlancer.mysql.MySQLGlobalState;
import sqlancer.mysql.MySQLOptions;
import sqlancer.mysql.MySQLSchema.MySQLColumn;
import sqlancer.mysql.MySQLSchema.MySQLDataType;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.MySQLSchema.MySQLTables;
import sqlancer.mysql.MySQLVisitor;
import sqlancer.mysql.ast.MySQLBinaryComparisonOperation;
import sqlancer.mysql.ast.MySQLBinaryComparisonOperation.BinaryComparisonOperator;
import sqlancer.mysql.ast.MySQLBinaryLogicalOperation;
import sqlancer.mysql.ast.MySQLBinaryLogicalOperation.MySQLBinaryLogicalOperator;
import sqlancer.mysql.ast.MySQLColumnReference;
import sqlancer.mysql.ast.MySQLConstant;
import sqlancer.mysql.ast.MySQLExpression;
import sqlancer.mysql.ast.MySQLJoin;
import sqlancer.mysql.ast.MySQLOrderByTerm;
import sqlancer.mysql.ast.MySQLOrderByTerm.MySQLOrder;
import sqlancer.mysql.ast.MySQLSelect;
import sqlancer.mysql.ast.MySQLTableReference;
import sqlancer.mysql.gen.MySQLExpressionGenerator;
import sqlancer.mysql.gen.MySQLPerformanceHintGenerator;
import sqlancer.mysql.gen.MySQLPerformanceHintGenerator.HintedQueryCandidate;

public class MySQLPerformanceHintOracle implements TestOracle<MySQLGlobalState> {

    private final MySQLGlobalState state;
    private final PerformanceQueryRewriteProvider rewriteProvider;
    private final RewriteEquivalenceOracle rewriteEquivalenceOracle;
    private final Set<PerformanceNineCellCategory> nineCellReportCategories;
    private final ExpectedErrors errors = new ExpectedErrors();
    private String lastQueryString;
    private PerformanceQueryMetadata lastGenerationMetadata;

    public MySQLPerformanceHintOracle(MySQLGlobalState state) {
        this(state, MySQLWeTuneStaticRewriteProvider.fromOptions(state.getDbmsSpecificOptions()),
                new WeTuneStaticRewriteEquivalenceOracle("mysql"));
    }

    MySQLPerformanceHintOracle(MySQLGlobalState state, PerformanceQueryRewriteProvider rewriteProvider,
            RewriteEquivalenceOracle rewriteEquivalenceOracle) {
        this.state = state;
        this.rewriteProvider = rewriteProvider;
        this.rewriteEquivalenceOracle = rewriteEquivalenceOracle;
        this.nineCellReportCategories = PerformanceNineCellCategory
                .parseReportCells(state.getDbmsSpecificOptions().performanceTaxonomyReportCells);
        MySQLErrors.addExpressionErrors(errors);
    }

    @Override
    public void check() throws Exception {
        MySQLPerformanceHintTelemetry telemetry = MySQLPerformanceHintTelemetry.getInstance(state);
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
            // A formal witness is deliberately surfaced as an AssertionError below.
            throw e;
        } catch (Exception e) {
            telemetry.count("checks.failed");
            throw e;
        } finally {
            telemetry.add("checks.wall_clock_nanos", System.nanoTime() - startedAt);
        }
    }

    private void checkInternal(MySQLPerformanceHintTelemetry telemetry) throws Exception {
        lastQueryString = null;
        lastGenerationMetadata = null;
        Family family = state.getDbmsSpecificOptions().performanceQueryGeneratorMode.chooseFamily();
        List<MySQLTable> referencedTables;
        MySQLPerformanceHintGenerator.QueryShape queryShape;
        String baseQuery;
        if (family == Family.STRUCTURED) {
            MySQLSelect select = generateBaseSelect();
            referencedTables = getReferencedTables(select);
            queryShape = MySQLPerformanceHintGenerator.analyzeQueryShape(select, referencedTables);
            baseQuery = MySQLVisitor.asString(select);
            lastGenerationMetadata = PerformanceQueryMetadata
                    .structured(state.getDbmsSpecificOptions().performanceQueryGeneratorMode, baseQuery);
        } else {
            ExpandedPerformanceQuery.Result<MySQLTable, MySQLColumn> expanded = MySQLExpandedQueryGenerator.generate(
                    state, state.getSchema().getDatabaseTables(), family,
                    reason -> telemetry.count("query_generation." + reason));
            referencedTables = expanded.tables;
            baseQuery = expanded.sql;
            queryShape = new MySQLPerformanceHintGenerator.QueryShape(referencedTables.size(),
                    expanded.joins.stream().anyMatch(join -> join.type != ExpandedPerformanceQuery.JoinType.CROSS),
                    expanded.where, expanded.orderBy, expanded.groupBy);
            lastGenerationMetadata = expanded.metadata;
        }
        telemetry.count("base_queries.generated");
        lastGenerationMetadata.record(telemetry, "generated");
        if (!isSupportedQuery(baseQuery)) {
            logDebugNote("PH_SKIP unsupported_query");
            telemetry.reject("base_query", "unsupported_query");
            throw new IgnoreMeException();
        }
        lastQueryString = baseQuery;
        String skeletonKey = MySQLPerformanceHintOracleUtils.buildSkeletonKey(baseQuery, referencedTables.size()) + "|"
                + lastGenerationMetadata.getStructuralKey();
        MySQLPerformanceHintFeedbackManager feedbackManager = MySQLPerformanceHintFeedbackManager.getInstance(state);
        if (!qpgEnabled()) {
            MySQLPerformanceHintFeedbackManager.SkeletonDecision skeletonDecision = feedbackManager
                    .evaluateSkeleton(skeletonKey, state.getRandomly(), getMySQLOptions());
            if (skeletonDecision.shouldSkip()) {
                logDebugNote(String.format(Locale.US,
                        "PH_SKIP repeated_skeleton skeletonKey=%s historicalCount=%d skipProbability=%.4f", skeletonKey,
                        skeletonDecision.getHistoricalCount(), skeletonDecision.getSkipProbability()));
                telemetry.reject("base_query", "repeated_skeleton");
                throw new IgnoreMeException();
            }
        }

        MySQLPerformanceHintOracleUtils.PlanSummary defaultPlan;
        try {
            defaultPlan = explain(baseQuery, true);
            telemetry.count("base_explains.succeeded");
            lastGenerationMetadata.record(telemetry, "explained");
        } catch (IgnoreMeException e) {
            telemetry.reject("base_explain", "unavailable_or_invalid");
            throw e;
        }
        List<HintedQueryCandidate> generatedCandidates = MySQLPerformanceHintGenerator.generateCandidates(
                referencedTables, queryShape, state.getRandomly(),
                MySQLPerformanceHintOracleUtils.MAX_INDEXES_PER_TABLE,
                MySQLPerformanceHintOracleUtils.MAX_HINTED_VARIANTS, getMySQLOptions().performanceAllowBkaHint);
        telemetry.add("hint_candidates.generated", generatedCandidates.size());
        List<HintedQueryCandidate> selectedHintCandidates = PerformanceHintHintPolicy
                .selectCandidates(generatedCandidates, getHintPolicyConfig());
        telemetry.add("hint_candidates.selected", selectedHintCandidates.size());
        List<CounterfactualCandidate> hintCandidates = materializeHintCandidates(baseQuery, selectedHintCandidates);
        telemetry.add("hint_candidates.materialized", hintCandidates.size());
        CounterfactualStrategySelector strategySelector = new CounterfactualStrategySelector(
                getMySQLOptions().performanceCounterfactualHintProbability);
        // Choose HINT or QUERY_REWRITE once for this base query. A selected rewrite batch retains every
        // WeTune response (up to the existing 16-candidate budget) rather than randomly sampling a subset.
        CounterfactualMutationKind requestedStrategy = strategySelector.select(state.getRandomly());
        telemetry.count("batches.requested_" + requestedStrategy.name().toLowerCase(Locale.ROOT));
        List<CounterfactualCandidate> rewriteCandidates = requestedStrategy == CounterfactualMutationKind.QUERY_REWRITE
                ? collectRewriteCandidates(baseQuery, referencedTables, telemetry) : List.of();
        // A WeTune rewrite request is strict: an empty/invalid provider response
        // skips this base query instead of being relabelled as a HINT fallback.
        // Consequently every candidate in a requested rewrite batch is a real
        // WETUNE_STATIC counterfactual, and all admitted candidates (up to 16)
        // proceed through the same plan, runtime, and equivalence checks.
        List<ScheduledCounterfactualCandidate> candidates;
        boolean strictWeTuneRewriteBatch = requestedStrategy == CounterfactualMutationKind.QUERY_REWRITE
                && "wetune-static".equalsIgnoreCase(getMySQLOptions().performanceQueryRewriteProvider);
        if (strictWeTuneRewriteBatch && rewriteCandidates.isEmpty()) {
            telemetry.count("rewrite_batches.empty");
            candidates = List.of();
        } else {
            candidates = CounterfactualCandidateScheduler.scheduleBatch(baseQuery,
                    MySQLPerformanceHintOracleUtils.MAX_HINTED_VARIANTS, requestedStrategy, hintCandidates,
                    rewriteCandidates);
        }
        // HINT generation is normally non-empty. Preserve the old graceful fallback for a degenerate HINT
        // batch, while retaining p=1.0's guarantee that no rewrite provider is invoked.
        if (candidates.isEmpty() && requestedStrategy == CounterfactualMutationKind.HINT
                && strategySelector.shouldRequestRewrites()) {
            rewriteCandidates = collectRewriteCandidates(baseQuery, referencedTables, telemetry);
            candidates = CounterfactualCandidateScheduler.scheduleBatch(baseQuery,
                    MySQLPerformanceHintOracleUtils.MAX_HINTED_VARIANTS, requestedStrategy, hintCandidates,
                    rewriteCandidates);
        }
        telemetry.add("pairs.scheduled", candidates.size());
        for (ScheduledCounterfactualCandidate candidate : candidates) {
            telemetry.count("slots.requested_" + candidate.getRequestedStrategy().name().toLowerCase());
            telemetry.count("pairs.actual_" + candidate.getActualMutationKind().name().toLowerCase());
            if (candidate.getFallbackReason() != CounterfactualFallbackReason.NONE) {
                telemetry.count("pairs.fallbacks");
                telemetry.count("pairs.fallback_" + candidate.getFallbackReason().name().toLowerCase());
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

        List<MySQLPerformanceHintOracleUtils.PerformanceHintWitness> planCandidates = new ArrayList<>();
        for (ScheduledCounterfactualCandidate scheduledCandidate : candidates) {
            CounterfactualCandidate candidate = scheduledCandidate.getCandidate();
            String hintedQuery = candidate.getAlternativeSql();
            telemetry.count("pairs.checked");
            logDebugNote(String.format(
                    "PH_TRY requestedStrategy=%s actualMutationKind=%s mutationFamily=%s mutationId=%s descriptor=%s fallback=%s",
                    scheduledCandidate.getRequestedStrategy(), candidate.getKind(), candidate.getFamily(),
                    candidate.getMutationId(), candidate.getDescriptor(), scheduledCandidate.getFallbackReason()));
            if (scheduledCandidate.getFallbackReason() != CounterfactualFallbackReason.NONE) {
                logDebugNote(
                        String.format("PH_FALLBACK requestedStrategy=%s actualMutationKind=%s reason=%s mutationId=%s",
                                scheduledCandidate.getRequestedStrategy(), candidate.getKind(),
                                scheduledCandidate.getFallbackReason(), candidate.getMutationId()));
            }
            MySQLPerformanceHintOracleUtils.PlanSummary hintedPlan = explain(hintedQuery, false);
            if (hintedPlan == null || hintedPlan.getQueryCost() == null || hintedPlan.getQueryCost() <= 0) {
                logDebugNote(String.format("PH_SKIP_COUNTERFACTUAL mutationId=%s reason=no_counterfactual_plan",
                        candidate.getMutationId()));
                telemetry.reject("counterfactual_explain", "unavailable_or_invalid");
                continue;
            }
            telemetry.count("pairs.counterfactual_explain_succeeded");
            if (!MySQLPerformanceHintOracleUtils.hasMeaningfulPlanDifference(defaultPlan, hintedPlan)) {
                telemetry.count("pairs.plan_same");
                telemetry.reject("plan_difference", "same");
                continue;
            }
            telemetry.count("pairs.plan_different");
            // Hints and static rewrites are presumed equivalent while candidates
            // are screened. Result materialization is intentionally deferred until
            // a cost/runtime category is reportable, keeping the common path cheap.
            telemetry.count("pairs.presumed_equivalent_plan_different");
            List<String> planEvidence = MySQLPerformanceHintOracleUtils.buildPlanDifferenceEvidence(defaultPlan,
                    hintedPlan);
            if (planEvidence.isEmpty()) {
                logDebugNote(String.format("PH_SKIP_COUNTERFACTUAL mutationId=%s reason=plan_evidence_empty",
                        candidate.getMutationId()));
                telemetry.reject("plan_evidence", "empty");
                continue;
            }
            telemetry.count("pairs.plan_difference_qualified");
            MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness = new MySQLPerformanceHintOracleUtils.PerformanceHintWitness(
                    baseQuery, hintedQuery, candidate, defaultPlan, hintedPlan, planEvidence, false);
            witness.setRequestedStrategy(scheduledCandidate.getRequestedStrategy().name());
            witness.setCounterfactualFallbackReason(scheduledCandidate.getFallbackReason().name());
            annotateWitnessMetadata(witness, skeletonKey, feedbackManager);
            planCandidates.add(witness);
            telemetry.count("pairs.pre_refresh_qualified");
            logDebugNote(String.format("PH_PLAN_CANDIDATE mutationId=%s evidence=%s", candidate.getMutationId(),
                    String.join("|", planEvidence)));
        }

        if (planCandidates.isEmpty()) {
            logDebugNote("PH_SKIP no_plan_candidates");
            telemetry.reject("pre_refresh", "no_qualified_pairs");
            if (qpgEnabled()) {
                return;
            }
            throw new IgnoreMeException();
        }

        // Providers refresh after dataset construction and their own successful mutations.
        // These SELECT/EXPLAIN pairs cannot change data or indexes, so reuse the plans above.
        List<MySQLPerformanceHintOracleUtils.PerformanceHintWitness> reportCandidates = validateRuntime(planCandidates,
                telemetry);
        if (reportCandidates.isEmpty()) {
            telemetry.reject("runtime_validation", "no_reportable_a_i_candidate");
            if (qpgEnabled()) {
                return;
            }
            throw new IgnoreMeException();
        }
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness firstFormalWitness = null;
        Path firstFormalReport = null;
        int confirmedReports = 0;
        for (MySQLPerformanceHintOracleUtils.PerformanceHintWitness reportCandidate : reportCandidates) {
            if (!validateDeferredResultEquivalence(reportCandidate, telemetry)) {
                logDebugNote(
                        String.format("PH_SKIP_COUNTERFACTUAL mutationId=%s reason=deferred_result_equivalence_failed",
                                reportCandidate.getCandidate().getMutationId()));
                continue;
            }
            confirmedReports++;
            telemetry.count("pairs.oracle_qualified");
            if (getMySQLOptions().enablePerformanceExplainAnalyze) {
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
            if (getMySQLOptions().continueAfterPerformanceFormalWitness) {
                telemetry.count("formal_witness.continued");
                return;
            }
            telemetry.count("formal_witness.fail_fast");
            enforceFormalWitnessPolicy(false, firstFormalWitness, firstFormalReport);
        }
    }

    static void enforceFormalWitnessPolicy(boolean continueAfterFormalWitness,
            MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness, Path reportDirectory) {
        if (!continueAfterFormalWitness) {
            throw new AssertionError(MySQLPerformanceHintOracleUtils.createAssertionMessage(witness, reportDirectory));
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
            candidates.add(CounterfactualCandidate.hint(
                    MySQLPerformanceHintOracleUtils.injectHint(baseQuery, hint.getHintSql()), hint.getHintFamily(),
                    hint.getHintType(), hint.getHintSql(), hint.getTableNames(), hint.getIndexNames()));
        }
        return candidates;
    }

    private List<CounterfactualCandidate> collectRewriteCandidates(String baseQuery, List<MySQLTable> referencedTables,
            MySQLPerformanceHintTelemetry telemetry) {
        telemetry.count("rewrite_provider.requests");
        List<String> targetTables = referencedTables.stream().map(MySQLTable::getName).collect(Collectors.toList());
        List<String> targetIndexes = referencedTables.stream().flatMap(table -> table.getIndexes().stream())
                .map(index -> index.getIndexName()).collect(Collectors.toList());
        QueryRewriteRequest request = new QueryRewriteRequest(baseQuery, "MySQL",
                MySQLPerformanceHintOracleUtils.MAX_HINTED_VARIANTS, buildSchemaSummary());
        List<CounterfactualCandidate> candidates = CounterfactualCandidateScheduler.collectValidatedRewriteCandidates(
                request, rewriteProvider, rewriteEquivalenceOracle, targetTables, targetIndexes);
        telemetry.add("rewrite_candidates.admitted", candidates.size());
        if (rewriteProvider instanceof WeTuneStaticRewriteProvider) {
            logDebugNote(((WeTuneStaticRewriteProvider) rewriteProvider).getLastDiagnostic());
        }
        return candidates;
    }

    private QueryRewriteSchemaSummary buildSchemaSummary() {
        List<TableSummary> tableSummaries = new ArrayList<>();
        for (MySQLTable table : state.getSchema().getDatabaseTables()) {
            List<ColumnSummary> columns = table.getColumns().stream()
                    .map(column -> new ColumnSummary(column.getName(),
                            column.getType() == null ? "UNKNOWN" : column.getType().name(), column.getTypeDeclaration(),
                            column.isNullable(), column.isPrimaryKey()))
                    .collect(Collectors.toList());
            List<IndexSummary> indexes = table
                    .getIndexes().stream().map(index -> new IndexSummary(index.getIndexName(),
                            index.getOrderedColumns(), index.isUnique(), index.isPrimaryKey()))
                    .collect(Collectors.toList());
            List<ForeignKeySummary> foreignKeys = table.getForeignKeys().stream()
                    .map(foreignKey -> new ForeignKeySummary(foreignKey.getName(), foreignKey.getOrderedColumns(),
                            foreignKey.getReferencedTable(), foreignKey.getReferencedColumns()))
                    .collect(Collectors.toList());
            tableSummaries.add(new TableSummary(table.getName(), columns, indexes, foreignKeys));
        }
        return new QueryRewriteSchemaSummary(tableSummaries);
    }

    private MySQLSelect generateBaseSelect() {
        List<MySQLTable> selectedTables = getSelectedTables();
        if (selectedTables.stream().noneMatch(table -> !table.getIndexes().isEmpty())) {
            throw new IgnoreMeException();
        }

        MySQLTables tables = new MySQLTables(selectedTables);
        MySQLExpressionGenerator expressionGenerator = new MySQLExpressionGenerator(state)
                .setColumns(tables.getColumns());
        MySQLSelect select = new MySQLSelect();
        List<MySQLExpression> fetchColumns = buildFetchColumns(tables);
        MySQLExpression primaryFetchColumn = fetchColumns.get(0);
        select.setFetchColumns(fetchColumns);
        select.setSelectType(chooseSelectType());

        double bindingProb = getMySQLOptions().performancePredicateBindingProbability;
        boolean useDataAware = bindingProb > 0.0 && state.getPdgIrContext() != null
                && state.getPdgIrContext().getInMemoryIndex() != null
                && (bindingProb >= 1.0 || Randomly.getPercentage() < bindingProb);
        List<JoinEdgeSpec> joinEdgeSpecs = new ArrayList<>();
        List<MySQLJoin> joinExpressions = buildStructuredJoinClauses(selectedTables, joinEdgeSpecs, useDataAware);
        select.setJoinList(joinExpressions.stream().map(join -> (MySQLExpression) join).collect(Collectors.toList()));
        select.setFromList(List.of(new MySQLTableReference(selectedTables.get(0))));

        boolean distinct = select.getFromOptions() != MySQLSelect.SelectType.ALL;
        boolean groupBy = Randomly.getBoolean() && Randomly.getBoolean();
        boolean orderBy = Randomly.getBoolean() || groupBy;
        boolean limit = (orderBy || groupBy || distinct) && Randomly.getBoolean();
        boolean hasResultCollapsing = distinct || groupBy || limit;
        select.setWhereClause(buildWhereClause(selectedTables, joinEdgeSpecs, expressionGenerator, useDataAware,
                hasResultCollapsing));
        if (groupBy) {
            select.setGroupByExpressions(buildGroupByExpressions(fetchColumns, primaryFetchColumn));
        }
        if (orderBy) {
            select.setOrderByExpressions(buildOrderByExpressions(
                    groupBy ? select.getGroupByExpressions() : fetchColumns, primaryFetchColumn));
        }
        if (limit) {
            select.setLimitClause(MySQLConstant.createIntConstant(chooseLimitValue()));
        }
        return select;
    }

    private List<MySQLTable> getSelectedTables() {
        List<MySQLTable> allTables = state.getSchema().getDatabaseTables();
        int maxTables = Math.min(MySQLPerformanceHintOracleUtils.MAX_TABLES, allTables.size());
        int minTables = Math.min(2, maxTables);
        int nrTables = state.getRandomly().getInteger(minTables, maxTables + 1);
        return Randomly.nonEmptySubset(allTables, nrTables);
    }

    private List<MySQLTable> getReferencedTables(MySQLSelect select) {
        LinkedHashSet<MySQLTable> referencedTables = new LinkedHashSet<>();
        select.getFromList().stream().map(expr -> ((MySQLTableReference) expr).getTable())
                .forEach(referencedTables::add);
        select.getJoinList().stream().filter(MySQLJoin.class::isInstance).map(MySQLJoin.class::cast)
                .map(MySQLJoin::getTable).forEach(referencedTables::add);
        return new ArrayList<>(referencedTables);
    }

    private boolean isSupportedQuery(String query) {
        return query.startsWith("SELECT ") && !query.contains("RAND(") && !query.contains("UUID(")
                && !query.contains("CURRENT_TIMESTAMP");
    }

    private MySQLPerformanceHintOracleUtils.PlanSummary explain(String query, boolean required) throws SQLException {
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
            MySQLPerformanceHintOracleUtils.PlanSummary plan = MySQLPerformanceHintOracleUtils.parsePlan(explainJson);
            if (plan.getQueryCost() == null || plan.getQueryCost() <= 0) {
                if (required) {
                    throw new IgnoreMeException();
                }
                return null;
            }
            return plan;
        } catch (AssertionError | Exception e) {
            if (required) {
                throw new IgnoreMeException();
            }
            return null;
        }
    }

    private boolean validateDeferredResultEquivalence(MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness,
            MySQLPerformanceHintTelemetry telemetry) {
        CounterfactualCandidate candidate = witness.getCandidate();
        try {
            telemetry.count("result_equivalence.deferred_attempted");
            String counterfactualSide = counterfactualSide(candidate);
            List<List<String>> baseResult = fetchResultRowsWithTimeout(witness.getBaseQuery(),
                    witness.getCounterfactualQuery(), candidate, witness.getDefaultPlan(), witness.getHintedPlan(),
                    "RESULT_EQUIV_DEFERRED_BASE", "BASE", witness.getRequestedStrategy(),
                    witness.getCounterfactualFallbackReason(), telemetry);
            List<List<String>> counterfactualResult = fetchResultRowsWithTimeout(witness.getBaseQuery(),
                    witness.getCounterfactualQuery(), candidate, witness.getDefaultPlan(), witness.getHintedPlan(),
                    "RESULT_EQUIV_DEFERRED_" + counterfactualSide, counterfactualSide, witness.getRequestedStrategy(),
                    witness.getCounterfactualFallbackReason(), telemetry);
            boolean equivalent = MySQLPerformanceHintOracleUtils.resultRowsMatch(baseResult, counterfactualResult);
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

    private Path persistReportCandidate(MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness,
            MySQLPerformanceHintFeedbackManager feedbackManager, MySQLPerformanceHintTelemetry telemetry)
            throws IOException {
        MySQLPerformanceHintFeedbackManager.WitnessDecision writeDecision = feedbackManager.recordWitness(witness,
                getMySQLOptions());
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
                        getMySQLOptions().performanceFeedbackRootCauseHardCap).name());
        if (!writeDecision.shouldWriteReport()) {
            logDebugNote(String.format(
                    "PH_SKIP duplicate_root_cause rootCause=%s selectionReason=%s observation=%d suppressed=%d",
                    witness.getRootCauseSignatureKey(), writeDecision.getSelectionReason(),
                    writeDecision.getRootCauseObservationCount(), writeDecision.getTotalSuppressedReports()));
            telemetry.count("reports.suppressed");
            return null;
        }
        boolean formalFailure = witness.getNineCellCategory().isConfirmedHarmful();
        MySQLPerformanceHintFeedbackManager.ReportReservation reservation = writeDecision.getReservation();
        if (reservation == null) {
            throw new IOException("accepted PERFORMANCE_HINT report is missing its feedback reservation");
        }
        PerformanceHintReportSnapshot snapshot;
        try {
            snapshot = MySQLPerformanceHintOracleUtils.snapshotWitness(state, witness);
            telemetry.count("reports.snapshot_created");
        } catch (RuntimeException exception) {
            telemetry.count("reports.snapshot_failed");
            try {
                feedbackManager.failReservation(reservation, exception);
            } catch (IOException feedbackFailure) {
                telemetry.count("reports.feedback_failure_after_snapshot_failure");
            }
            throw exception;
        }
        if (getMySQLOptions().enablePerformanceAsyncReportWrites) {
            MySQLPerformanceHintReportWriter.getInstance(getMySQLOptions().performanceReportQueueCapacity).submit(
                    snapshot, reservation, feedbackManager, telemetry,
                    getMySQLOptions().performanceReportQueueOfferMillis, formalFailure);
            return snapshot.getFinalReportDirectory();
        }
        telemetry.count("reports.selected");
        final Path reportDir;
        try {
            reportDir = MySQLPerformanceHintOracleUtils.writeSnapshot(snapshot);
            feedbackManager.completeReservation(reservation, reportDir);
            telemetry.count("reports.written");
            telemetry.count(formalFailure ? "reports.formal_written" : "reports.observation_written");
        } catch (IOException exception) {
            telemetry.count("reports.write_failed");
            try {
                feedbackManager.failReservation(reservation, exception);
            } catch (IOException feedbackFailure) {
                telemetry.count("reports.feedback_failure_after_write_failure");
            }
            throw exception;
        }
        state.getState().getLocalState().log(witness.getBaseQuery());
        state.getState().getLocalState().log(witness.getCounterfactualQuery());
        if (!formalFailure) {
            logDebugNote(String.format("PH_NINE_CELL_OBSERVATION cell=%s report=%s", witness.getNineCellCategory(),
                    reportDir.getFileName()));
        }
        return reportDir;
    }

    private List<MySQLPerformanceHintOracleUtils.PerformanceHintWitness> validateRuntime(
            List<MySQLPerformanceHintOracleUtils.PerformanceHintWitness> planCandidates,
            MySQLPerformanceHintTelemetry telemetry) throws Exception {
        List<MySQLPerformanceHintOracleUtils.PerformanceHintWitness> sortedCandidates = new ArrayList<>(planCandidates);
        sortedCandidates.sort(Comparator.comparingDouble(candidate -> -candidate.getPlanInterestScore()));
        if (sortedCandidates.isEmpty()) {
            return List.of();
        }
        MySQLPerformanceHintOracleUtils.PerformanceHintWitness baselineSource = sortedCandidates.get(0);
        RuntimeSeries baseRuntime;
        try {
            QueryExecution baseWarmup = executeAndConsumeWithTimeout(baselineSource.getBaseQuery());
            telemetry.count("runtime.warmup_base_samples");
            telemetry.add("runtime.warmup_samples", 1L);
            telemetry.add("runtime.timeout_samples", baseWarmup.isTimedOut() ? 1L : 0L);
            maybeRecordTimeout(baselineSource, baseWarmup, "RUNTIME_WARMUP_BASE");
            baseRuntime = measureRuntimeSeries(baselineSource.getBaseQuery(), "BASE", baselineSource.getDefaultPlan(),
                    getMySQLOptions().performanceRepeatCount, 1, true, telemetry);
            telemetry.add("runtime.timeout_samples", baseRuntime.getSummary().getTimeoutCount());
        } catch (AssertionError | Exception e) {
            telemetry.reject("runtime_validation", "base_execution_failed_or_timeout");
            return List.of();
        }
        List<MySQLPerformanceHintOracleUtils.PerformanceHintWitness> reportCandidates = new ArrayList<>();
        for (MySQLPerformanceHintOracleUtils.PerformanceHintWitness candidate : sortedCandidates) {
            try {
                telemetry.count("pairs.runtime_attempted");
                QueryExecution hintedWarmup = executeAndConsumeWithTimeout(candidate.getCounterfactualQuery());
                telemetry.count("runtime.warmup_counterfactual_samples");
                telemetry.add("runtime.warmup_samples", 1L);
                telemetry.add("runtime.timeout_samples", hintedWarmup.isTimedOut() ? 1L : 0L);
                maybeRecordTimeout(candidate, hintedWarmup,
                        "RUNTIME_WARMUP_" + candidate.getCounterfactualRuntimeSide());
                RuntimeSeries counterfactualRuntime = measureRuntimeSeries(candidate.getCounterfactualQuery(),
                        candidate.getCounterfactualRuntimeSide(), candidate.getHintedPlan(),
                        getMySQLOptions().performanceRepeatCount, baseRuntime.getSamples().size() + 1, false,
                        telemetry);
                MySQLPerformanceHintOracleUtils.RuntimeSummary defaultRuntime = baseRuntime.getSummary();
                MySQLPerformanceHintOracleUtils.RuntimeSummary hintedRuntime = counterfactualRuntime.getSummary();
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
                    telemetry.reject("nine_cell_report_policy", "cell_" + nineCell.getCode() + "_not_reportable");
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

    private boolean shouldReportNineCellCandidate(PerformanceNineCellCategory nineCell,
            CostRuntimeObservation observation) {
        if (!nineCell.isConfirmedHarmful() && nineCell != PerformanceNineCellCategory.B_CHEAPER_SIMILAR
                && nineCell != PerformanceNineCellCategory.C_CHEAPER_SLOWER) {
            return false;
        }
        return PerformanceNineCellReportPolicy.shouldReport(getMySQLOptions().performanceTaxonomyEnable,
                nineCellReportCategories, nineCell, observation, getMySQLOptions().performanceReportHarmfulRuntimeRatio,
                getMySQLOptions().performanceReportCostAnomalyRatio);
    }

    private RuntimeSeries measureRuntimeSeries(String query, String side,
            MySQLPerformanceHintOracleUtils.PlanSummary plan, int repeatCount, int firstSampleSequence,
            boolean baseSide, MySQLPerformanceHintTelemetry telemetry) throws Exception {
        List<Double> runsMs = new ArrayList<>();
        List<MySQLPerformanceHintOracleUtils.RuntimeSample> runtimeSamples = new ArrayList<>();
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
            runtimeSamples.add(new MySQLPerformanceHintOracleUtils.RuntimeSample(firstSampleSequence + i, side, query,
                    plan.getExplainJson(), execution.getElapsedMs(), execution.isTimedOut()));
        }
        return new RuntimeSeries(new MySQLPerformanceHintOracleUtils.RuntimeSummary(runsMs, timeoutCount),
                runtimeSamples);
    }

    private static List<MySQLPerformanceHintOracleUtils.RuntimeSample> combineRuntimeSamples(RuntimeSeries baseRuntime,
            RuntimeSeries counterfactualRuntime) {
        List<MySQLPerformanceHintOracleUtils.RuntimeSample> samples = new ArrayList<>(baseRuntime.getSamples());
        samples.addAll(counterfactualRuntime.getSamples());
        return samples;
    }

    private static CostRuntimeObservation buildRuntimeObservation(
            MySQLPerformanceHintOracleUtils.PerformanceHintWitness candidate) {
        return new CostRuntimeObservation(candidate.getDefaultPlan().getQueryCost(),
                candidate.getHintedPlan().getQueryCost(), candidate.getDefaultRuntime().getMedianMs(),
                candidate.getHintedRuntime().getMedianMs(), candidate.getDefaultRuntime().getMeanMs(),
                candidate.getHintedRuntime().getMeanMs(), candidate.getDefaultRuntime().getMinMs(),
                candidate.getHintedRuntime().getMinMs(), candidate.getDefaultRuntime().getMaxMs(),
                candidate.getHintedRuntime().getMaxMs(), candidate.getDefaultTimeoutCount(),
                candidate.getHintedTimeoutCount(), candidate.getDefaultRuntime().getRunsMs(),
                candidate.getHintedRuntime().getRunsMs());
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

    private void collectExplainAnalyze(MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness)
            throws Exception {
        MySQLPerformanceHintOracleUtils.AnalyzeSummary defaultAnalyze = explainAnalyze(witness.getBaseQuery());
        MySQLPerformanceHintOracleUtils.AnalyzeSummary hintedAnalyze = explainAnalyze(witness.getCounterfactualQuery());
        if (defaultAnalyze != null && hintedAnalyze != null) {
            witness.setDefaultAnalyze(defaultAnalyze);
            witness.setHintedAnalyze(hintedAnalyze);
        }
    }

    private MySQLPerformanceHintOracleUtils.AnalyzeSummary explainAnalyze(String query) throws Exception {
        String explainAnalyzeQuery = "EXPLAIN ANALYZE " + query;
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
            return MySQLPerformanceHintOracleUtils.parseAnalyzeSummary(sb.toString());
        } catch (SQLTimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<List<String>> fetchResultRowsWithTimeout(String baseQuery, String hintedQuery,
            CounterfactualCandidate candidate, MySQLPerformanceHintOracleUtils.PlanSummary defaultPlan,
            MySQLPerformanceHintOracleUtils.PlanSummary hintedPlan, String phase, String timedOutSide,
            String requestedStrategy, String fallbackReason, MySQLPerformanceHintTelemetry telemetry) throws Exception {
        telemetry.count("result_equivalence.executions");
        telemetry.count("BASE".equals(timedOutSide) ? "result_equivalence.base_executions"
                : "result_equivalence.counterfactual_executions");
        String query = "BASE".equals(timedOutSide) ? baseQuery : hintedQuery;
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
                // List.copyOf rejects SQL NULL values. Preserve them because a
                // strict result multiset must distinguish NULL from every string.
                result.add(row);
            }
            telemetry.count("result_equivalence.execution_succeeded");
            return result;
        } catch (SQLTimeoutException e) {
            if (candidate != null) {
                telemetry.count("timeout_artifacts.written");
                MySQLPerformanceHintOracleUtils.writeTimeoutObservation(state,
                        new MySQLPerformanceHintOracleUtils.TimeoutObservation(baseQuery, hintedQuery, candidate,
                                defaultPlan, hintedPlan, phase, timedOutSide, getRuntimeTimeoutMs(),
                                getRuntimeTimeoutMs(), requestedStrategy, fallbackReason));
            }
            throw new IgnoreMeException();
        }
    }

    private void maybeRecordTimeout(MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness,
            QueryExecution execution, String phase) throws IOException {
        if (!execution.isTimedOut()) {
            return;
        }
        String timedOutSide = phase.endsWith("BASE") ? "BASE" : witness.getCounterfactualRuntimeSide();
        MySQLPerformanceHintTelemetry.getInstance(state).count("timeout_artifacts.written");
        MySQLPerformanceHintOracleUtils.writeTimeoutObservation(state,
                new MySQLPerformanceHintOracleUtils.TimeoutObservation(witness.getBaseQuery(),
                        witness.getCounterfactualQuery(), witness.getCounterfactualCandidate(),
                        witness.getDefaultPlan(), witness.getHintedPlan(), phase, timedOutSide, getRuntimeTimeoutMs(),
                        execution.getElapsedMs(), witness.getRequestedStrategy(),
                        witness.getCounterfactualFallbackReason()));
    }

    private void maybeRecordMeasuredTimeouts(MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness,
            MySQLPerformanceHintOracleUtils.RuntimeSummary defaultRuntime,
            MySQLPerformanceHintOracleUtils.RuntimeSummary hintedRuntime, boolean recordBaseTimeout)
            throws IOException {
        if (recordBaseTimeout && defaultRuntime.getTimeoutCount() > 0) {
            MySQLPerformanceHintTelemetry.getInstance(state).count("timeout_artifacts.written");
            MySQLPerformanceHintOracleUtils.writeTimeoutObservation(state,
                    new MySQLPerformanceHintOracleUtils.TimeoutObservation(witness.getBaseQuery(),
                            witness.getCounterfactualQuery(), witness.getCounterfactualCandidate(),
                            witness.getDefaultPlan(), witness.getHintedPlan(), "RUNTIME_MEASURE_BASE", "BASE",
                            getRuntimeTimeoutMs(), getRuntimeTimeoutMs(), witness.getRequestedStrategy(),
                            witness.getCounterfactualFallbackReason()));
        }
        if (hintedRuntime.getTimeoutCount() > 0) {
            MySQLPerformanceHintTelemetry.getInstance(state).count("timeout_artifacts.written");
            MySQLPerformanceHintOracleUtils.writeTimeoutObservation(state,
                    new MySQLPerformanceHintOracleUtils.TimeoutObservation(witness.getBaseQuery(),
                            witness.getCounterfactualQuery(), witness.getCounterfactualCandidate(),
                            witness.getDefaultPlan(), witness.getHintedPlan(),
                            "RUNTIME_MEASURE_" + witness.getCounterfactualRuntimeSide(),
                            witness.getCounterfactualRuntimeSide(), getRuntimeTimeoutMs(), getRuntimeTimeoutMs(),
                            witness.getRequestedStrategy(), witness.getCounterfactualFallbackReason()));
        }
    }

    private static String counterfactualSide(CounterfactualCandidate candidate) {
        return candidate.getKind() == CounterfactualMutationKind.HINT ? "HINTED" : "COUNTERFACTUAL";
    }

    private void annotateWitnessMetadata(MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness,
            String skeletonKey, MySQLPerformanceHintFeedbackManager feedbackManager) {
        witness.setQueryGenerationMetadata(lastGenerationMetadata);
        witness.setSkeletonKey(skeletonKey);
        RootCauseClassification rootCause = MySQLPerformanceHintOracleUtils.classifyRootCause(witness);
        witness.setRootCauseCategory(rootCause.getIntentCategory());
        witness.setRootCauseIntentCategory(rootCause.getIntentCategory());
        witness.setRootCauseMechanismKey(rootCause.getMechanismKey());
        witness.setFamilyKey(MySQLPerformanceHintOracleUtils.buildFamilyKey(witness));
        witness.setPlanSignatureKey(MySQLPerformanceHintOracleUtils.buildPlanSignatureKey(witness));
        witness.setRootCauseSignatureKey(rootCause.getSignatureKey());
        if (witness.isHintCounterfactual()) {
            CounterfactualCandidate candidate = witness.getCounterfactualCandidate();
            PerformanceHintHintPolicy.CandidateDescriptor descriptor = new PerformanceHintHintPolicy.CandidateDescriptor() {
                @Override
                public String getHintFamily() {
                    return candidate.getFamily();
                }

                @Override
                public String getHintType() {
                    return candidate.getMutationId();
                }
            };
            witness.setHintPolicyDecision(PerformanceHintHintPolicy.describePolicyDecision(getHintPolicyConfig()));
            witness.setHintFamilyQuotaApplied(
                    PerformanceHintHintPolicy.describeAppliedQuota(getHintPolicyConfig(), List.of(descriptor)));
        } else {
            witness.setHintPolicyDecision("not_applicable");
            witness.setHintFamilyQuotaApplied("not_applicable");
        }
        MySQLPerformanceHintFeedbackManager.NoveltySnapshot noveltySnapshot = feedbackManager
                .estimateNoveltySnapshot(witness, getMySQLOptions());
        witness.setNoveltyScore(noveltySnapshot.getOverallNovelty());
        witness.setRootCauseNoveltyScore(noveltySnapshot.getRootCauseNovelty());
    }

    private List<MySQLExpression> buildFetchColumns(MySQLTables tables) {
        List<MySQLExpression> allColumns = tables.getColumns().stream()
                .map(column -> new MySQLColumnReference(column, null)).collect(Collectors.toList());
        int maxFetchColumns = Math.min(3, allColumns.size());
        int fetchCount = state.getRandomly().getInteger(1, maxFetchColumns + 1);
        return new ArrayList<>(Randomly.nonEmptySubset(allColumns, fetchCount));
    }

    private List<MySQLJoin> buildStructuredJoinClauses(List<MySQLTable> selectedTables,
            List<JoinEdgeSpec> joinEdgeSpecs, boolean useDataAware) {
        if (selectedTables.size() <= 1) {
            return List.of();
        }
        List<MySQLJoin> joins = new ArrayList<>();
        List<MySQLTable> joinedTables = new ArrayList<>();
        joinedTables.add(selectedTables.get(0));
        for (int i = 1; i < selectedTables.size(); i++) {
            MySQLTable table = selectedTables.get(i);
            joins.add(new MySQLJoin(table, buildJoinPredicate(joinedTables, table, joinEdgeSpecs, useDataAware),
                    chooseJoinType()));
            joinedTables.add(table);
        }
        return joins;
    }

    private MySQLJoin.JoinType chooseJoinType() {
        if (Randomly.getBooleanWithRatherLowProbability()) {
            return MySQLJoin.JoinType.STRAIGHT;
        }
        return MySQLJoin.JoinType.INNER;
    }

    private MySQLExpression buildJoinPredicate(List<MySQLTable> joinedTables, MySQLTable nextTable,
            List<JoinEdgeSpec> joinEdgeSpecs, boolean useDataAware) {
        List<JoinColumnPair> relationshipPairs = new ArrayList<>();
        List<JoinColumnPair> preferredPairs = new ArrayList<>();
        List<JoinColumnPair> compatiblePairs = new ArrayList<>();
        for (MySQLTable joinedTable : joinedTables) {
            JoinEdgeSpec relEdge = useDataAware ? DataAwarePredicateBinder.findPreferredRelationship(
                    state.getPdgIrContext(), joinedTable.getName(), nextTable.getName()) : null;
            String relLeftCol = relEdge != null ? relEdge.getLeftColumn() : null;
            String relRightCol = relEdge != null ? relEdge.getRightColumn() : null;
            for (MySQLColumn leftColumn : joinedTable.getColumns()) {
                for (MySQLColumn rightColumn : nextTable.getColumns()) {
                    if (!areComparable(leftColumn, rightColumn)) {
                        continue;
                    }
                    JoinColumnPair pair = new JoinColumnPair(leftColumn, rightColumn);
                    compatiblePairs.add(pair);
                    if (leftColumn.isPrimaryKey() || rightColumn.isPrimaryKey()) {
                        preferredPairs.add(pair);
                    }
                    if (relLeftCol != null && relRightCol != null && leftColumn.getName().equalsIgnoreCase(relLeftCol)
                            && rightColumn.getName().equalsIgnoreCase(relRightCol)) {
                        relationshipPairs.add(pair);
                    }
                }
            }
        }
        List<JoinColumnPair> chosenPairs = !relationshipPairs.isEmpty() ? relationshipPairs
                : preferredPairs.isEmpty() ? compatiblePairs : preferredPairs;
        if (chosenPairs.isEmpty()) {
            MySQLColumn leftColumn = Randomly.fromList(
                    joinedTables.stream().flatMap(table -> table.getColumns().stream()).collect(Collectors.toList()));
            MySQLColumn rightColumn = Randomly.fromList(nextTable.getColumns());
            joinEdgeSpecs.add(new JoinEdgeSpec(leftColumn.getTable().getName(), leftColumn.getName(),
                    rightColumn.getTable().getName(), rightColumn.getName()));
            return new MySQLBinaryComparisonOperation(new MySQLColumnReference(leftColumn, null),
                    new MySQLColumnReference(rightColumn, null), BinaryComparisonOperator.EQUALS);
        }
        JoinColumnPair pair = Randomly.fromList(chosenPairs);
        joinEdgeSpecs.add(new JoinEdgeSpec(pair.leftColumn.getTable().getName(), pair.leftColumn.getName(),
                pair.rightColumn.getTable().getName(), pair.rightColumn.getName()));
        return new MySQLBinaryComparisonOperation(new MySQLColumnReference(pair.leftColumn, null),
                new MySQLColumnReference(pair.rightColumn, null), BinaryComparisonOperator.EQUALS);
    }

    private MySQLExpression buildWhereClause(List<MySQLTable> selectedTables, List<JoinEdgeSpec> joinEdgeSpecs,
            MySQLExpressionGenerator expressionGenerator, boolean useDataAware, boolean hasResultCollapsing) {
        List<MySQLColumn> columns = selectedTables.stream().flatMap(table -> table.getColumns().stream())
                .collect(Collectors.toList());
        if (columns.isEmpty()) {
            return null;
        }
        List<String> tableNames = selectedTables.stream().map(MySQLTable::getName).collect(Collectors.toList());
        MySQLPerformanceHintTelemetry telemetry = MySQLPerformanceHintTelemetry.getInstance(state);
        if (useDataAware) {
            List<MySQLColumn> slotColumns = new ArrayList<>(2);
            List<PredicateSlot> slots = new ArrayList<>(2);
            MySQLColumn firstCol = chooseFilterColumn(columns);
            slotColumns.add(firstCol);
            slots.add(new PredicateSlot(firstCol.getTable().getName(), firstCol.getName(),
                    chooseBindingOperator(firstCol)));
            if (columns.size() > 1 && Randomly.getBooleanWithRatherLowProbability()) {
                MySQLColumn secondCol = chooseFilterColumn(columns);
                slotColumns.add(secondCol);
                slots.add(new PredicateSlot(secondCol.getTable().getName(), secondCol.getName(),
                        chooseBindingOperator(secondCol)));
            }
            DataAwarePredicateBinder.BindingResult binding = DataAwarePredicateBinder.bind(state.getPdgIrContext(),
                    tableNames, joinEdgeSpecs, slots, true, Randomly.fromOptions(0, 1, 2), hasResultCollapsing,
                    telemetry);
            if (!binding.getBoundPredicates().isEmpty()) {
                MySQLExpression boundExpr = toBoundMySQLExpression(slotColumns.get(0),
                        binding.getBoundPredicates().get(0));
                for (int i = 1; i < binding.getBoundPredicates().size(); i++) {
                    MySQLExpression nextExpr = toBoundMySQLExpression(slotColumns.get(i),
                            binding.getBoundPredicates().get(i));
                    boundExpr = new MySQLBinaryLogicalOperation(boundExpr, nextExpr, MySQLBinaryLogicalOperator.AND);
                }
                return boundExpr;
            }
        }
        MySQLExpression predicate = buildSimplePredicate(chooseFilterColumn(columns), expressionGenerator);
        if (columns.size() > 1 && Randomly.getBooleanWithRatherLowProbability()) {
            MySQLExpression secondPredicate = buildSimplePredicate(chooseFilterColumn(columns), expressionGenerator);
            predicate = new MySQLBinaryLogicalOperation(predicate, secondPredicate, MySQLBinaryLogicalOperator.AND);
        }
        DataAwarePredicateBinder.recordUnawareTelemetry(state.getPdgIrContext(), tableNames, joinEdgeSpecs,
                MySQLVisitor.asString(predicate), telemetry);
        return predicate;
    }

    private Operator chooseBindingOperator(MySQLColumn column) {
        if (resolvePredicateType(column) == MySQLDataType.VARCHAR) {
            return Randomly.fromOptions(Operator.EQ, Operator.NEQ);
        }
        return Randomly.fromOptions(Operator.EQ, Operator.LT, Operator.LTE, Operator.GT, Operator.GTE);
    }

    private MySQLExpression toBoundMySQLExpression(MySQLColumn column, BoundPredicate bound) {
        MySQLExpression left = new MySQLColumnReference(column, null);
        MySQLExpression right;
        switch (bound.getSqlType()) {
        case VARCHAR:
            right = MySQLConstant.createStringConstant(bound.getBoundString() != null ? bound.getBoundString() : "");
            break;
        case DOUBLE:
            right = new MySQLConstant.MySQLDoubleConstant(bound.getBoundDouble());
            break;
        default:
            right = MySQLConstant.createIntConstant(bound.getBoundLong());
            break;
        }
        BinaryComparisonOperator op;
        switch (bound.getSlot().getOperator()) {
        case EQ:
            op = BinaryComparisonOperator.EQUALS;
            break;
        case NEQ:
            op = BinaryComparisonOperator.NOT_EQUALS;
            break;
        case LT:
            op = BinaryComparisonOperator.LESS;
            break;
        case LTE:
            op = BinaryComparisonOperator.LESS_EQUALS;
            break;
        case GT:
            op = BinaryComparisonOperator.GREATER;
            break;
        case GTE:
            op = BinaryComparisonOperator.GREATER_EQUALS;
            break;
        default:
            op = BinaryComparisonOperator.EQUALS;
            break;
        }
        return new MySQLBinaryComparisonOperation(left, right, op);
    }

    private MySQLColumn chooseFilterColumn(List<MySQLColumn> columns) {
        List<MySQLColumn> primaryKeyColumns = columns.stream().filter(MySQLColumn::isPrimaryKey)
                .collect(Collectors.toList());
        if (!primaryKeyColumns.isEmpty()) {
            return Randomly.fromList(primaryKeyColumns);
        }
        List<MySQLColumn> numericColumns = columns.stream()
                .filter(column -> column.getType() == null || column.getType().isNumeric())
                .collect(Collectors.toList());
        if (!numericColumns.isEmpty()) {
            return Randomly.fromList(numericColumns);
        }
        return Randomly.fromList(columns);
    }

    private MySQLExpression buildSimplePredicate(MySQLColumn column, MySQLExpressionGenerator expressionGenerator) {
        MySQLExpression left = new MySQLColumnReference(column, null);
        MySQLExpression right = expressionGenerator.generateConstant(resolvePredicateType(column));
        if (resolvePredicateType(column) == MySQLDataType.VARCHAR) {
            return new MySQLBinaryComparisonOperation(left, right,
                    Randomly.fromOptions(BinaryComparisonOperator.EQUALS, BinaryComparisonOperator.NOT_EQUALS));
        }
        return new MySQLBinaryComparisonOperation(left, right,
                Randomly.fromOptions(BinaryComparisonOperator.EQUALS, BinaryComparisonOperator.LESS,
                        BinaryComparisonOperator.LESS_EQUALS, BinaryComparisonOperator.GREATER,
                        BinaryComparisonOperator.GREATER_EQUALS));
    }

    private MySQLDataType resolvePredicateType(MySQLColumn column) {
        if (column.getType() == null) {
            return MySQLDataType.INT;
        }
        return column.getType();
    }

    private boolean areComparable(MySQLColumn leftColumn, MySQLColumn rightColumn) {
        if (leftColumn.getType() == null || rightColumn.getType() == null) {
            return true;
        }
        return leftColumn.getType() == rightColumn.getType();
    }

    private MySQLSelect.SelectType chooseSelectType() {
        if (Randomly.getBooleanWithSmallProbability()) {
            return MySQLSelect.SelectType.DISTINCTROW;
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            return MySQLSelect.SelectType.DISTINCT;
        }
        return MySQLSelect.SelectType.ALL;
    }

    private List<MySQLExpression> buildGroupByExpressions(List<MySQLExpression> fetchColumns,
            MySQLExpression primaryFetchColumn) {
        List<MySQLExpression> groupableColumns = new ArrayList<>(new LinkedHashSet<>(fetchColumns));
        if (groupableColumns.isEmpty()) {
            return List.of(primaryFetchColumn);
        }
        int maxGroupColumns = Math.min(2, groupableColumns.size());
        int count = state.getRandomly().getInteger(1, maxGroupColumns + 1);
        return new ArrayList<>(Randomly.nonEmptySubset(groupableColumns, count));
    }

    private List<MySQLExpression> buildOrderByExpressions(List<MySQLExpression> preferredColumns,
            MySQLExpression primaryFetchColumn) {
        List<MySQLExpression> orderableColumns = new ArrayList<>(new LinkedHashSet<>(preferredColumns));
        if (orderableColumns.isEmpty()) {
            return List.of(new MySQLOrderByTerm(primaryFetchColumn, MySQLOrder.getRandomOrder()));
        }
        int maxOrderColumns = Math.min(2, orderableColumns.size());
        int count = state.getRandomly().getInteger(1, maxOrderColumns + 1);
        List<MySQLExpression> selected = new ArrayList<>(Randomly.nonEmptySubset(orderableColumns, count));
        return selected.stream()
                .map(expression -> (MySQLExpression) new MySQLOrderByTerm(expression, MySQLOrder.getRandomOrder()))
                .collect(Collectors.toList());
    }

    private long chooseLimitValue() {
        return Randomly.fromOptions(1L, 2L, 3L, 5L, 8L, 12L);
    }

    private static final class JoinColumnPair {
        private final MySQLColumn leftColumn;
        private final MySQLColumn rightColumn;

        private JoinColumnPair(MySQLColumn leftColumn, MySQLColumn rightColumn) {
            this.leftColumn = leftColumn;
            this.rightColumn = rightColumn;
        }
    }

    private PolicyConfig getHintPolicyConfig() {
        return PolicyConfig.fromOptionStrings(getMySQLOptions().performanceHintIncludeTypes,
                getMySQLOptions().performanceHintExcludeTypes, getMySQLOptions().performanceHintFamilyQuota,
                getMySQLOptions().performanceHintFamilyWeightMode,
                getMySQLOptions().performanceHintMaxCandidatesPerFamily,
                MySQLPerformanceHintOracleUtils.MAX_HINTED_VARIANTS);
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
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent("-- " + note + System.lineSeparator());
            try {
                state.getLogger().getCurrentFileWriter().flush();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    private MySQLOptions getMySQLOptions() {
        return state.getDbmsSpecificOptions();
    }

    private int getRuntimeTimeoutSeconds() {
        return Math.max(1, getMySQLOptions().performanceTimeoutSeconds);
    }

    private double getRuntimeTimeoutMs() {
        return getRuntimeTimeoutSeconds() * 1000.0;
    }

    private PerformanceNineCellOptions getPerformanceNineCellOptions() {
        return new PerformanceNineCellOptions(getMySQLOptions().performanceTaxonomyEnable,
                getMySQLOptions().performanceTaxonomyCostRatioLowerBound,
                getMySQLOptions().performanceTaxonomyCostRatioUpperBound,
                getMySQLOptions().performanceTaxonomyRuntimeRatioLowerBound,
                getMySQLOptions().performanceTaxonomyRuntimeRatioUpperBound,
                getMySQLOptions().performanceTaxonomyMinRuntimeMedianMs,
                getMySQLOptions().performanceTaxonomyMinRuntimeAbsGapMs);
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

    /** A measured side together with the artifact samples that produced it. */
    private static final class RuntimeSeries {
        private final MySQLPerformanceHintOracleUtils.RuntimeSummary summary;
        private final List<MySQLPerformanceHintOracleUtils.RuntimeSample> samples;

        private RuntimeSeries(MySQLPerformanceHintOracleUtils.RuntimeSummary summary,
                List<MySQLPerformanceHintOracleUtils.RuntimeSample> samples) {
            this.summary = summary;
            this.samples = List.copyOf(samples);
        }

        private MySQLPerformanceHintOracleUtils.RuntimeSummary getSummary() {
            return summary;
        }

        private List<MySQLPerformanceHintOracleUtils.RuntimeSample> getSamples() {
            return samples;
        }
    }

}
