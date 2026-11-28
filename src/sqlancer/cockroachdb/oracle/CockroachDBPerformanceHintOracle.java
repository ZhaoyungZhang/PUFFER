package sqlancer.cockroachdb.oracle;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.cockroachdb.CockroachDBOptions;
import sqlancer.cockroachdb.CockroachDBProvider.CockroachDBGlobalState;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBColumn;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBCompositeDataType;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBDataType;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBTable;
import sqlancer.cockroachdb.gen.CockroachDBPerformanceHintGenerator;
import sqlancer.cockroachdb.gen.CockroachDBPerformanceHintGenerator.HintedQueryCandidate;
import sqlancer.cockroachdb.gen.CockroachDBPerformanceHintGenerator.JoinSpec;
import sqlancer.cockroachdb.gen.CockroachDBPerformanceHintGenerator.JoinType;
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
import sqlancer.common.schema.TableIndex;

public class CockroachDBPerformanceHintOracle implements TestOracle<CockroachDBGlobalState> {

    private static final int MAX_COUNTERFACTUAL_BATCH = 16;

    private final CockroachDBGlobalState state;
    private final PerformanceQueryRewriteProvider rewriteProvider;
    private final RewriteEquivalenceOracle rewriteEquivalenceOracle;
    private final Set<PerformanceNineCellCategory> nineCellReportCategories;
    private String lastQueryString;
    private PerformanceQueryMetadata lastGenerationMetadata;

    public CockroachDBPerformanceHintOracle(CockroachDBGlobalState state) {
        this(state, CockroachDBWeTuneStaticRewriteProvider.fromOptions(state.getDbmsSpecificOptions()),
                new WeTuneStaticRewriteEquivalenceOracle("cockroachdb"));
    }

    CockroachDBPerformanceHintOracle(CockroachDBGlobalState state, PerformanceQueryRewriteProvider rewriteProvider,
            RewriteEquivalenceOracle rewriteEquivalenceOracle) {
        this.state = state;
        this.rewriteProvider = rewriteProvider;
        this.rewriteEquivalenceOracle = rewriteEquivalenceOracle;
        this.nineCellReportCategories = PerformanceNineCellCategory
                .parseReportCells(state.getDbmsSpecificOptions().performanceTaxonomyReportCells);
    }

    @Override
    public void check() throws Exception {
        CockroachDBPerformanceHintTelemetry telemetry = CockroachDBPerformanceHintTelemetry.getInstance(state);
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
            telemetry.count("checks.formal_result");
            throw e;
        } catch (Exception e) {
            telemetry.count("checks.failed");
            throw e;
        } finally {
            telemetry.add("checks.wall_clock_nanos", System.nanoTime() - startedAt);
        }
    }

    private void checkInternal(CockroachDBPerformanceHintTelemetry telemetry) throws Exception {
        lastQueryString = null;
        lastGenerationMetadata = null;
        BaseQuery base = generateBaseQuery();
        String baseQuery = base.getSql();
        lastQueryString = baseQuery;
        telemetry.count("base_queries.generated");
        lastGenerationMetadata.record(telemetry, "generated");

        CockroachDBPerformanceHintFeedbackManager feedbackManager = CockroachDBPerformanceHintFeedbackManager
                .getInstance(state);
        String skeletonKey = CockroachDBPerformanceHintOracleUtils.buildSkeletonKey(baseQuery,
                base.getReferencedTables().size()) + "|" + lastGenerationMetadata.getStructuralKey();
        if (!qpgEnabled()) {
            CockroachDBPerformanceHintFeedbackManager.SkeletonDecision skeletonDecision = feedbackManager
                    .evaluateSkeleton(skeletonKey, state.getRandomly(), getOptions());
            if (skeletonDecision.shouldSkip()) {
                telemetry.reject("feedback", "skeleton");
                throw new IgnoreMeException();
            }
        }

        CockroachDBPerformanceHintOracleUtils.PlanSummary defaultPlan = explain(baseQuery, true);
        telemetry.count("base_explains.succeeded");
        lastGenerationMetadata.record(telemetry, "explained");
        List<HintedQueryCandidate> generatedCandidates = CockroachDBPerformanceHintGenerator.generateCandidates(
                base.getReferencedTables(), base.getQueryShape(), state.getRandomly(),
                CockroachDBPerformanceHintOracleUtils.MAX_INDEXES_PER_TABLE,
                CockroachDBPerformanceHintOracleUtils.MAX_HINTED_VARIANTS);
        List<HintedQueryCandidate> selectedHints = PerformanceHintHintPolicy.selectCandidates(generatedCandidates,
                getHintPolicyConfig());
        List<CounterfactualCandidate> hintCandidates = materializeHintCandidates(baseQuery, selectedHints);

        CounterfactualStrategySelector strategySelector = new CounterfactualStrategySelector(
                getOptions().performanceCounterfactualHintProbability);
        CounterfactualMutationKind requestedStrategy = strategySelector.select(state.getRandomly());
        telemetry.count("strategy." + requestedStrategy.name().toLowerCase(Locale.ROOT));
        List<CounterfactualCandidate> rewriteCandidates = requestedStrategy == CounterfactualMutationKind.QUERY_REWRITE
                ? collectRewriteCandidates(base) : List.of();
        if (requestedStrategy == CounterfactualMutationKind.QUERY_REWRITE) {
            telemetry.count("rewrite_provider.requests");
            telemetry.add("rewrite_candidates.admitted", rewriteCandidates.size());
        }

        /*
         * When a QUERY_REWRITE batch is empty, count rewrite_batches.empty and fall back to HINT candidates via
         * CounterfactualCandidateScheduler.scheduleBatch so CockroachDB does not discard the base query.
         */
        if (requestedStrategy == CounterfactualMutationKind.QUERY_REWRITE && rewriteCandidates.isEmpty()) {
            telemetry.count("rewrite_batches.empty");
        }
        List<ScheduledCounterfactualCandidate> candidates = CounterfactualCandidateScheduler.scheduleBatch(baseQuery,
                MAX_COUNTERFACTUAL_BATCH, requestedStrategy, hintCandidates, rewriteCandidates);
        if (candidates.isEmpty() && requestedStrategy == CounterfactualMutationKind.HINT
                && strategySelector.shouldRequestRewrites()
                && "wetune-static".equalsIgnoreCase(getOptions().performanceQueryRewriteProvider)) {
            rewriteCandidates = collectRewriteCandidates(base);
            candidates = CounterfactualCandidateScheduler.scheduleBatch(baseQuery, MAX_COUNTERFACTUAL_BATCH,
                    requestedStrategy, hintCandidates, rewriteCandidates);
        }
        if (candidates.isEmpty()) {
            telemetry.reject("candidate_batch",
                    requestedStrategy == CounterfactualMutationKind.QUERY_REWRITE ? "rewrite_empty" : "empty");
            noCandidateOutcome();
            return;
        }
        telemetry.add("candidates.scheduled", candidates.size());
        telemetry.add("pairs.scheduled", candidates.size());

        List<CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness> initial = new ArrayList<>();
        for (ScheduledCounterfactualCandidate scheduledCandidate : candidates) {
            CounterfactualCandidate candidate = scheduledCandidate.getCandidate();
            telemetry.count("pairs.checked");
            logScheduledCandidate(scheduledCandidate);
            CockroachDBPerformanceHintOracleUtils.PlanSummary counterfactualPlan = explain(
                    candidate.getAlternativeSql(), false);
            if (!hasPositivePlan(counterfactualPlan)) {
                telemetry.reject("initial_plan", "missing_or_nonpositive_cost");
                continue;
            }
            telemetry.count("pairs.counterfactual_explain_succeeded");
            if (!CockroachDBPerformanceHintOracleUtils.isHintAcceptedByPlan(candidate, counterfactualPlan)) {
                telemetry.reject("initial_plan", "hint_not_effective");
                continue;
            }
            List<String> planEvidence = CockroachDBPerformanceHintOracleUtils.buildPlanDifferenceEvidence(defaultPlan,
                    counterfactualPlan);
            if (planEvidence.isEmpty()) {
                telemetry.reject("initial_plan", "unchanged");
                continue;
            }
            telemetry.count("pairs.plan_different");
            CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness = new CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness(
                    baseQuery, candidate.getAlternativeSql(), candidate, defaultPlan, counterfactualPlan, planEvidence,
                    false, scheduledCandidate.getRequestedStrategy(), scheduledCandidate.getFallbackReason().name());
            annotateWitnessMetadata(witness, skeletonKey, feedbackManager);
            initial.add(witness);
        }
        telemetry.add("candidates.initial_plan_retained", initial.size());
        if (initial.isEmpty()) {
            noCandidateOutcome();
            return;
        }

        /*
         * Statistics are refreshed at database construction and after successful PERFORMANCE_HINT QPG data/metadata
         * mutations. Nothing between the initial EXPLAINs above and runtime validation changes the database, so
         * re-running ANALYZE and every EXPLAIN here would only duplicate work and alter no plan semantics.
         */
        List<CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness> measured = validateRuntime(initial,
                telemetry);
        if (measured.isEmpty()) {
            noCandidateOutcome();
            return;
        }

        List<List<String>> baseRows;
        try {
            baseRows = fetchResultRows(baseQuery);
        } catch (SQLException e) {
            telemetry.reject("result_equivalence", "base_execution");
            noCandidateOutcome();
            return;
        }

        Path firstFormalReport = null;
        CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness firstFormalWitness = null;
        for (CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness : measured) {
            if (!shouldReportNineCellCandidate(witness)) {
                telemetry.count("category." + witness.getNineCellCategory().getCode() + ".counted_only");
                continue;
            }
            List<List<String>> counterfactualRows;
            try {
                counterfactualRows = fetchResultRows(witness.getCounterfactualQuery());
            } catch (SQLException e) {
                telemetry.reject("result_equivalence", "counterfactual_execution");
                continue;
            }
            witness.setResultEquivalent(
                    CockroachDBPerformanceHintOracleUtils.resultRowsMatch(baseRows, counterfactualRows));
            if (!witness.isResultEquivalent()) {
                telemetry.reject("result_equivalence", "rows_differ");
                telemetry.count("result_equivalence.deferred_rejected");
                continue;
            }
            telemetry.count("result_equivalence.matched");
            telemetry.count("result_equivalence.deferred_confirmed");

            if (getOptions().enablePerformanceExplainAnalyze) {
                witness.setDefaultAnalyze(explainAnalyze(witness.getBaseQuery()));
                witness.setHintedAnalyze(explainAnalyze(witness.getCounterfactualQuery()));
            }
            telemetry.count("pairs.nine_cell_reportable");
            Path reportDir = persistReportCandidate(witness, feedbackManager, telemetry);
            if (reportDir == null) {
                continue;
            }
            PerformanceNineCellCategory category = witness.getNineCellCategory();
            telemetry.count("category." + category.getCode() + ".reported");
            if (category.isConfirmedHarmful() && firstFormalReport == null) {
                firstFormalReport = reportDir;
                firstFormalWitness = witness;
            }
        }
        if (firstFormalReport != null) {
            if (getOptions().continueAfterPerformanceFormalWitness) {
                telemetry.count("formal_witness.continued");
                return;
            }
            telemetry.count("formal_witness.fail_fast");
            enforceFormalWitnessPolicy(false, firstFormalWitness, firstFormalReport);
        }
    }

    /* Default compatibility: only a formal witness selected for report persistence reaches this policy. */
    static void enforceFormalWitnessPolicy(boolean continueAfterFormalWitness,
            CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness, Path reportDirectory) {
        if (!continueAfterFormalWitness) {
            throw new AssertionError(
                    CockroachDBPerformanceHintOracleUtils.createAssertionMessage(witness, reportDirectory));
        }
    }

    private void noCandidateOutcome() {
        if (!qpgEnabled()) {
            throw new IgnoreMeException();
        }
    }

    private static boolean hasPositivePlan(CockroachDBPerformanceHintOracleUtils.PlanSummary plan) {
        return plan != null && plan.getQueryCost() != null && plan.getQueryCost() > 0;
    }

    private void logScheduledCandidate(ScheduledCounterfactualCandidate scheduledCandidate) {
        CounterfactualCandidate candidate = scheduledCandidate.getCandidate();
        logDebugNote(String.format(
                "PH_TRY requestedStrategy=%s actualMutationKind=%s mutationFamily=%s mutationId=%s descriptor=%s fallback=%s",
                scheduledCandidate.getRequestedStrategy(), candidate.getKind(), candidate.getFamily(),
                candidate.getMutationId(), candidate.getDescriptor(), scheduledCandidate.getFallbackReason()));
        if (scheduledCandidate.getFallbackReason() != CounterfactualFallbackReason.NONE) {
            logDebugNote(String.format("PH_FALLBACK requestedStrategy=%s actualMutationKind=%s reason=%s mutationId=%s",
                    scheduledCandidate.getRequestedStrategy(), candidate.getKind(),
                    scheduledCandidate.getFallbackReason(), candidate.getMutationId()));
        }
    }

    @Override
    public String getLastQueryString() {
        return lastQueryString;
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

    private boolean qpgEnabled() {
        return state.getOptions().enableQPG();
    }

    private List<CounterfactualCandidate> materializeHintCandidates(String baseQuery,
            List<HintedQueryCandidate> selectedHints) {
        List<CounterfactualCandidate> materialized = new ArrayList<>();
        for (HintedQueryCandidate hint : selectedHints) {
            // This is deliberately the only place Cockroach's FROM/JOIN token materializer is called. Query rewrites
            // arrive as complete SQL statements and must not be interpreted as hints.
            String hintedSql = CockroachDBPerformanceHintOracleUtils.applyCandidate(baseQuery, hint);
            if (hintedSql.equals(baseQuery)) {
                CockroachDBPerformanceHintTelemetry.getInstance(state).reject("hint_materialization", "unchanged_sql");
                continue;
            }
            materialized.add(CounterfactualCandidate.hint(hintedSql, hint.getHintFamily(), hint.getHintType(),
                    hint.getHintSql(), hint.getTableNames(), hint.getIndexNames()));
        }
        return materialized;
    }

    private List<CounterfactualCandidate> collectRewriteCandidates(BaseQuery base) {
        List<String> targetTables = base.getReferencedTables().stream().map(CockroachDBTable::getName)
                .collect(Collectors.toList());
        List<String> targetIndexes = base.getReferencedTables().stream().flatMap(table -> table.getIndexes().stream())
                .map(TableIndex::getIndexName).collect(Collectors.toList());
        QueryRewriteRequest request = new QueryRewriteRequest(base.getSql(), "cockroachdb", MAX_COUNTERFACTUAL_BATCH,
                summarizeSchema(base.getReferencedTables()));
        return CounterfactualCandidateScheduler.collectValidatedRewriteCandidates(request, rewriteProvider,
                rewriteEquivalenceOracle, targetTables, targetIndexes);
    }

    private static QueryRewriteSchemaSummary summarizeSchema(List<CockroachDBTable> tables) {
        List<TableSummary> summaries = new ArrayList<>();
        for (CockroachDBTable table : tables) {
            List<ColumnSummary> columns = table.getColumns().stream()
                    .map(column -> new ColumnSummary(column.getName(), String.valueOf(column.getType())))
                    .collect(Collectors.toList());
            List<IndexSummary> indexes = table.getIndexes().stream()
                    .map(index -> new IndexSummary(index.getIndexName(), index.getOrderedColumns(),
                            "primary".equalsIgnoreCase(index.getIndexName()),
                            "primary".equalsIgnoreCase(index.getIndexName())))
                    .collect(Collectors.toList());
            summaries.add(new TableSummary(table.getName(), columns, indexes));
        }
        return new QueryRewriteSchemaSummary(summaries);
    }

    private BaseQuery generateBaseQuery() {
        Family family = state.getDbmsSpecificOptions().performanceQueryGeneratorMode.chooseFamily();
        if (family != Family.STRUCTURED) {
            ExpandedPerformanceQuery.Result<CockroachDBTable, CockroachDBColumn> expanded = CockroachDBExpandedQueryGenerator
                    .generate(state, state.getSchema().getDatabaseTables(), family,
                            reason -> CockroachDBPerformanceHintTelemetry.getInstance(state)
                                    .count("query_generation." + reason));
            lastGenerationMetadata = expanded.metadata;
            List<JoinSpec> joins = new ArrayList<>();
            for (ExpandedPerformanceQuery.Join<CockroachDBTable, CockroachDBColumn> join : expanded.joins) {
                joins.add(new JoinSpec(join.ordinal, JoinType.valueOf(join.type.name()), join.leftTable.getName(),
                        join.rightTable.getName(), !join.rightTable.getIndexes().isEmpty(), join.type.token(),
                        join.equality, join.rightColumn != null && join.rightColumn.isPrimaryKey(),
                        join.leftColumn == null ? null : join.leftColumn.getFullQualifiedName(),
                        join.rightColumn == null ? null : join.rightColumn.getFullQualifiedName()));
            }
            CockroachDBPerformanceHintGenerator.QueryShape shape = new CockroachDBPerformanceHintGenerator.QueryShape(
                    expanded.tables.size(), expanded.where, expanded.groupBy, expanded.orderBy, expanded.limit, joins,
                    joins.stream().anyMatch(JoinSpec::isInnerJoin), joins.stream().anyMatch(JoinSpec::isLeftJoin),
                    Set.of());
            return new BaseQuery(expanded.sql, expanded.tables, shape, joins, collectQualifiedColumns(expanded.tables));
        }
        BaseQuery base = generateStructuredBaseQuery();
        lastGenerationMetadata = PerformanceQueryMetadata
                .structured(state.getDbmsSpecificOptions().performanceQueryGeneratorMode, base.getSql());
        return base;
    }

    private BaseQuery generateStructuredBaseQuery() {
        for (int attempt = 0; attempt < 30; attempt++) {
            List<CockroachDBTable> selectedTables = getSelectedTables();
            List<QualifiedColumn> qualifiedColumns = collectQualifiedColumns(selectedTables);
            if (qualifiedColumns.isEmpty()) {
                continue;
            }

            boolean distinct = Randomly.getBoolean();
            boolean groupBy = !distinct && Randomly.getBoolean();
            boolean orderBy = distinct || groupBy || Randomly.getBoolean();
            boolean limit = distinct || groupBy || orderBy || Randomly.getBoolean();

            List<QualifiedColumn> groupByColumns = groupBy
                    ? pickQualifiedColumns(qualifiedColumns, 1, Math.min(3, qualifiedColumns.size())) : List.of();
            List<QualifiedColumn> fetchColumns = groupBy
                    ? pickQualifiedColumns(groupByColumns, 1, Math.min(4, groupByColumns.size()))
                    : pickQualifiedColumns(qualifiedColumns, 1, Math.min(4, qualifiedColumns.size()));

            double bindingProb = getOptions().performancePredicateBindingProbability;
            boolean useDataAware = bindingProb > 0.0 && state.getPdgIrContext() != null
                    && state.getPdgIrContext().getInMemoryIndex() != null
                    && (bindingProb >= 1.0 || Randomly.getPercentage() < bindingProb);
            FromClause fromClause = buildFromClause(selectedTables, useDataAware);
            if (fromClause == null) {
                continue;
            }

            List<String> tableNames = selectedTables.stream().map(CockroachDBTable::getName)
                    .collect(Collectors.toList());
            List<JoinEdgeSpec> joinEdgeSpecs = toJoinEdgeSpecs(fromClause.joinSpecs);
            CockroachDBPerformanceHintTelemetry telemetry = CockroachDBPerformanceHintTelemetry.getInstance(state);
            PredicateBundle predicates;
            if (useDataAware) {
                predicates = buildDataAwarePredicateBundle(qualifiedColumns, tableNames, joinEdgeSpecs,
                        distinct || groupBy || limit, telemetry);
            } else {
                predicates = Randomly.getBoolean() ? buildPredicate(qualifiedColumns) : PredicateBundle.empty();
                DataAwarePredicateBinder.recordUnawareTelemetry(state.getPdgIrContext(), tableNames, joinEdgeSpecs,
                        predicates.sql(), telemetry);
            }
            List<QualifiedColumn> orderByColumns = orderBy
                    ? pickQualifiedColumns(distinct || groupBy ? fetchColumns : qualifiedColumns, 1,
                            Math.min(2, distinct || groupBy ? fetchColumns.size() : qualifiedColumns.size()))
                    : List.of();

            StringBuilder sql = new StringBuilder("SELECT ");
            if (distinct) {
                sql.append("DISTINCT ");
            }
            sql.append(fetchColumns.get(0).sql).append(" AS ref0");
            for (int i = 1; i < fetchColumns.size(); i++) {
                sql.append(", ").append(fetchColumns.get(i).sql);
            }
            sql.append(" FROM ").append(fromClause.sql);
            if (predicates.hasWhereClause()) {
                sql.append(" WHERE ").append(predicates.sql());
            }
            if (groupBy) {
                sql.append(" GROUP BY ")
                        .append(groupByColumns.stream().map(column -> column.sql).collect(Collectors.joining(", ")));
            }
            if (orderBy) {
                sql.append(" ORDER BY ").append(buildOrderByClause(orderByColumns));
            }
            if (limit) {
                sql.append(" LIMIT ").append(chooseLimitValue());
            }

            Set<String> constrainedTables = new LinkedHashSet<>(fromClause.constrainedTables);
            constrainedTables.addAll(predicates.constrainedTables());
            CockroachDBPerformanceHintGenerator.QueryShape queryShape = new CockroachDBPerformanceHintGenerator.QueryShape(
                    selectedTables.size(), predicates.hasWhereClause(), groupBy, orderBy, limit, fromClause.joinSpecs,
                    fromClause.hasInnerJoin, fromClause.hasLeftJoin, constrainedTables);
            return new BaseQuery(sql.toString(), selectedTables, queryShape, fromClause.joinSpecs, qualifiedColumns);
        }
        throw new IgnoreMeException();
    }

    private List<CockroachDBTable> getSelectedTables() {
        List<CockroachDBTable> allTables = state.getSchema().getDatabaseTables().stream().filter(t -> !t.isView())
                .filter(t -> !t.getColumns().isEmpty()).collect(Collectors.toList());
        if (allTables.size() < 2) {
            throw new IgnoreMeException();
        }
        List<CockroachDBTable> indexedTables = allTables.stream().filter(table -> !table.getIndexes().isEmpty())
                .collect(Collectors.toList());
        if (indexedTables.isEmpty()) {
            throw new IgnoreMeException();
        }

        int maxTables = Math.min(CockroachDBPerformanceHintOracleUtils.MAX_TABLES, allTables.size());
        int nrTables = state.getRandomly().getInteger(2, maxTables + 1);
        List<CockroachDBTable> selected = new ArrayList<>();
        selected.add(Randomly.fromList(indexedTables));
        List<CockroachDBTable> remaining = new ArrayList<>(allTables);
        remaining.removeAll(selected);
        while (selected.size() < nrTables && !remaining.isEmpty()) {
            CockroachDBTable next = Randomly.fromList(remaining);
            selected.add(next);
            remaining.remove(next);
        }
        if (selected.size() < 2) {
            throw new IgnoreMeException();
        }
        return selected;
    }

    private List<QualifiedColumn> collectQualifiedColumns(List<CockroachDBTable> tables) {
        List<QualifiedColumn> columns = new ArrayList<>();
        for (CockroachDBTable table : tables) {
            for (CockroachDBColumn column : table.getColumns()) {
                if (!isSupportedScalarColumn(column)) {
                    continue;
                }
                columns.add(new QualifiedColumn(table, column));
            }
        }
        return columns;
    }

    private List<QualifiedColumn> pickQualifiedColumns(List<QualifiedColumn> columns, int minColumns, int maxColumns) {
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }
        int upperBound = Math.min(maxColumns, columns.size());
        if (upperBound < minColumns) {
            throw new IgnoreMeException();
        }
        int targetSize = state.getRandomly().getInteger(minColumns, upperBound + 1);
        return Randomly.nonEmptySubset(columns, targetSize);
    }

    private PredicateBundle buildPredicate(List<QualifiedColumn> columns) {
        int predicateCount = state.getRandomly().getInteger(1, Math.min(3, columns.size()) + 1);
        List<String> fragments = new ArrayList<>();
        Set<String> constrainedTables = new LinkedHashSet<>();
        for (QualifiedColumn column : Randomly.nonEmptySubset(columns, predicateCount)) {
            fragments.add(buildSimplePredicate(column));
            constrainedTables.add(column.table.getName());
        }
        if (fragments.size() == 1) {
            return new PredicateBundle(fragments.get(0), constrainedTables);
        }
        if (fragments.size() == 2) {
            return new PredicateBundle(
                    "(" + fragments.get(0) + ") " + randomBooleanConnector() + " (" + fragments.get(1) + ")",
                    constrainedTables);
        }
        return new PredicateBundle("((" + fragments.get(0) + ") " + randomBooleanConnector() + " (" + fragments.get(1)
                + ")) " + randomBooleanConnector() + " (" + fragments.get(2) + ")", constrainedTables);
    }

    private String buildSimplePredicate(QualifiedColumn column) {
        String qualified = column.sql;
        CockroachDBCompositeDataType type = column.column.getType();
        switch (type.getPrimitiveDataType()) {
        case INT:
        case SERIAL:
            return qualified + (Randomly.getBoolean() ? " >= " + Randomly.fromOptions(0, 1, 2, 4, 8)
                    : " = " + Randomly.fromOptions(0, 1, 2, 4));
        case STRING:
            return qualified + (Randomly.getBoolean() ? " >= 'a'" : " = '" + randomUniformStringLiteral() + "'");
        case BOOL:
            return qualified + " = " + (Randomly.getBoolean() ? "TRUE" : "FALSE");
        case FLOAT:
        case DECIMAL:
            return qualified + (Randomly.getBoolean() ? " >= 0"
                    : " < " + String.format(Locale.US, "%.1f", Randomly.fromOptions(0.5, 1.5, 3.5, 8.0)));
        default:
            throw new IgnoreMeException();
        }
    }

    private String randomBooleanConnector() {
        return Randomly.getBoolean() ? "AND" : "OR";
    }

    private String randomUniformStringLiteral() {
        return Randomly.fromOptions("uniform_0", "uniform_1", "uniform_2", "uniform_3");
    }

    private String buildOrderByClause(List<QualifiedColumn> columns) {
        return columns.stream().map(column -> column.sql + (Randomly.getBoolean() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }

    private int chooseLimitValue() {
        return Randomly.fromOptions(1, 2, 3, 5, 8, 12);
    }

    private FromClause buildFromClause(List<CockroachDBTable> selectedTables, boolean useDataAware) {
        List<CockroachDBTable> joinedTables = new ArrayList<>();
        List<JoinSpec> joinSpecs = new ArrayList<>();
        Set<String> constrainedTables = new LinkedHashSet<>();
        boolean hasInnerJoin = false;
        boolean hasLeftJoin = false;

        StringBuilder sql = new StringBuilder(selectedTables.get(0).getName());
        joinedTables.add(selectedTables.get(0));
        for (int ordinal = 0; ordinal < selectedTables.size() - 1; ordinal++) {
            CockroachDBTable rightTable = selectedTables.get(ordinal + 1);
            List<JoinEdge> joinEdges = new ArrayList<>();
            for (CockroachDBTable leftTable : joinedTables) {
                for (ColumnPair pair : findJoinableColumns(leftTable, rightTable, useDataAware)) {
                    joinEdges.add(new JoinEdge(leftTable, pair));
                }
            }
            if (joinEdges.isEmpty()) {
                return null;
            }
            JoinEdge joinEdge = Randomly.fromList(joinEdges);
            JoinType joinType = Randomly.getPercentage() < 0.15 ? JoinType.LEFT : JoinType.INNER;
            String joinToken = joinType == JoinType.LEFT ? "LEFT JOIN" : "INNER JOIN";
            sql.append(" ").append(joinToken).append(" ").append(rightTable.getName()).append(" ON ")
                    .append(joinEdge.leftTable.getName()).append(".").append(joinEdge.joinColumns.left.getName())
                    .append(" = ").append(rightTable.getName()).append(".")
                    .append(joinEdge.joinColumns.right.getName());
            joinSpecs.add(new JoinSpec(ordinal, joinType, joinEdge.leftTable.getName(), rightTable.getName(),
                    !rightTable.getIndexes().isEmpty(), joinToken, true, joinEdge.joinColumns.right.isPrimaryKey(),
                    joinEdge.joinColumns.left.getFullQualifiedName(),
                    joinEdge.joinColumns.right.getFullQualifiedName()));
            constrainedTables.add(joinEdge.leftTable.getName());
            constrainedTables.add(rightTable.getName());
            hasInnerJoin |= joinType == JoinType.INNER;
            hasLeftJoin |= joinType == JoinType.LEFT;
            joinedTables.add(rightTable);
        }
        return new FromClause(sql.toString(), joinSpecs, constrainedTables, hasInnerJoin, hasLeftJoin);
    }

    private List<JoinEdgeSpec> toJoinEdgeSpecs(List<JoinSpec> joinSpecs) {
        List<JoinEdgeSpec> specs = new ArrayList<>(joinSpecs.size());
        for (JoinSpec js : joinSpecs) {
            String leftCol = js.getLeftKey();
            String rightCol = js.getRightKey();
            if (leftCol != null && rightCol != null) {
                int lDot = leftCol.indexOf('.');
                int rDot = rightCol.indexOf('.');
                specs.add(new JoinEdgeSpec(js.getLeftTableName(), lDot >= 0 ? leftCol.substring(lDot + 1) : leftCol,
                        js.getRightTableName(), rDot >= 0 ? rightCol.substring(rDot + 1) : rightCol));
            }
        }
        return specs;
    }

    private PredicateBundle buildDataAwarePredicateBundle(List<QualifiedColumn> columns, List<String> tableNames,
            List<JoinEdgeSpec> joinEdgeSpecs, boolean hasResultCollapsing,
            CockroachDBPerformanceHintTelemetry telemetry) {
        int predicateCount = state.getRandomly().getInteger(1, Math.min(3, columns.size()) + 1);
        List<PredicateSlot> slots = new ArrayList<>(predicateCount);
        Set<String> constrainedTables = new LinkedHashSet<>();
        for (QualifiedColumn col : Randomly.nonEmptySubset(columns, predicateCount)) {
            CockroachDBDataType pType = col.column.getType().getPrimitiveDataType();
            Operator op = pType == CockroachDBDataType.STRING || pType == CockroachDBDataType.BOOL
                    ? Randomly.fromOptions(Operator.EQ, Operator.NEQ)
                    : Randomly.fromOptions(Operator.EQ, Operator.LT, Operator.LTE, Operator.GT, Operator.GTE);
            slots.add(new PredicateSlot(col.table.getName(), col.column.getName(), op));
            constrainedTables.add(col.table.getName());
        }
        boolean conjunctive = slots.size() <= 1 || Randomly.getBoolean();
        DataAwarePredicateBinder.BindingResult binding = DataAwarePredicateBinder.bind(state.getPdgIrContext(),
                tableNames, joinEdgeSpecs, slots, conjunctive, Randomly.fromOptions(0, 1, 2), hasResultCollapsing,
                telemetry);
        if (binding.getWhereSql().isEmpty()) {
            return PredicateBundle.empty();
        }
        return new PredicateBundle(binding.getWhereSql(), constrainedTables);
    }

    private List<ColumnPair> findJoinableColumns(CockroachDBTable left, CockroachDBTable right, boolean useDataAware) {
        JoinEdgeSpec relEdge = useDataAware ? DataAwarePredicateBinder
                .findPreferredRelationship(state.getPdgIrContext(), left.getName(), right.getName()) : null;
        if (relEdge != null) {
            String relLeft = relEdge.getLeftColumn();
            String relRight = relEdge.getRightColumn();
            List<ColumnPair> relPairs = new ArrayList<>(1);
            for (CockroachDBColumn lc : left.getColumns()) {
                for (CockroachDBColumn rc : right.getColumns()) {
                    if (lc.getName().equalsIgnoreCase(relLeft) && rc.getName().equalsIgnoreCase(relRight)
                            && lc.getType().getPrimitiveDataType() == rc.getType().getPrimitiveDataType()) {
                        relPairs.add(new ColumnPair(lc, rc));
                    }
                }
            }
            if (!relPairs.isEmpty()) {
                return relPairs;
            }
        }
        List<ColumnPair> pairs = new ArrayList<>();
        for (CockroachDBColumn leftColumn : getJoinCandidateColumns(left)) {
            for (CockroachDBColumn rightColumn : getJoinCandidateColumns(right)) {
                if (leftColumn.getType().getPrimitiveDataType() == rightColumn.getType().getPrimitiveDataType()) {
                    pairs.add(new ColumnPair(leftColumn, rightColumn));
                }
            }
        }
        return pairs;
    }

    private List<CockroachDBColumn> getJoinCandidateColumns(CockroachDBTable table) {
        List<CockroachDBColumn> primaryKeyColumns = table.getColumns().stream()
                .filter(column -> column.isPrimaryKey() && isSupportedScalarColumn(column))
                .collect(Collectors.toList());
        if (!primaryKeyColumns.isEmpty()) {
            return primaryKeyColumns;
        }
        return table.getColumns().stream().filter(this::isSupportedScalarColumn).collect(Collectors.toList());
    }

    private boolean isSupportedScalarColumn(CockroachDBColumn column) {
        CockroachDBDataType type = column.getType().getPrimitiveDataType();
        return type == CockroachDBDataType.INT || type == CockroachDBDataType.SERIAL
                || type == CockroachDBDataType.STRING || type == CockroachDBDataType.BOOL
                || type == CockroachDBDataType.FLOAT || type == CockroachDBDataType.DECIMAL;
    }

    private CockroachDBPerformanceHintOracleUtils.PlanSummary explain(String query, boolean required)
            throws SQLException {
        String explainQuery = "EXPLAIN (OPT, VERBOSE) " + query;
        try (Statement statement = state.getConnection().createStatement();
                ResultSet rs = statement.executeQuery(explainQuery)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getString(1)).append(System.lineSeparator());
            }
            CockroachDBPerformanceHintOracleUtils.PlanSummary plan = CockroachDBPerformanceHintOracleUtils
                    .parsePlan(sb.toString());
            if (required && (plan.getQueryCost() == null || plan.getQueryCost() <= 0)) {
                throw new IgnoreMeException();
            }
            return plan;
        } catch (SQLException e) {
            if (required) {
                throw e;
            }
            return null;
        }
    }

    private CockroachDBPerformanceHintOracleUtils.AnalyzeSummary explainAnalyze(String query) throws SQLException {
        String explainQuery = "EXPLAIN ANALYZE " + query;
        try (Statement statement = state.getConnection().createStatement();
                ResultSet rs = statement.executeQuery(explainQuery)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getString(1)).append(System.lineSeparator());
            }
            return CockroachDBPerformanceHintOracleUtils.parseAnalyzeSummary(sb.toString());
        }
    }

    private List<List<String>> fetchResultRows(String query) throws SQLException {
        int timeoutSeconds = Math.max(1, getOptions().performanceTimeoutSeconds);
        try (Statement statement = state.getConnection().createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = statement.executeQuery(query)) {
                ResultSetMetaData metadata = rs.getMetaData();
                int columnCount = metadata.getColumnCount();
                List<List<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>(columnCount);
                    for (int column = 1; column <= columnCount; column++) {
                        String value = rs.getString(column);
                        row.add(value == null ? null : ComparatorHelper.canonicalizeResultValue(value));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private List<CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness> validateRuntime(
            List<CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness> candidates,
            CockroachDBPerformanceHintTelemetry telemetry) throws Exception {
        List<CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness> sorted = new ArrayList<>(candidates);
        CockroachDBPerformanceHintFeedbackManager feedbackManager = CockroachDBPerformanceHintFeedbackManager
                .getInstance(state);
        sorted.sort(Comparator.comparingDouble(candidate -> -runtimePriorityScore(candidate, feedbackManager)));

        RuntimeSeries baseline;
        try {
            baseline = measureRuntimeSeries(sorted.get(0).getBaseQuery(), "BASE");
        } catch (SQLException e) {
            telemetry.reject("runtime", "base_execution");
            return List.of();
        }

        List<CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness> measured = new ArrayList<>();
        for (CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness candidate : sorted) {
            try {
                RuntimeSeries counterfactual = measureRuntimeSeries(candidate.getCounterfactualQuery(),
                        candidate.getCounterfactualRuntimeSide());
                candidate.setDefaultRuntime(baseline.getSummary());
                candidate.setHintedRuntime(counterfactual.getSummary());
                List<CockroachDBPerformanceHintOracleUtils.RuntimeSample> samples = new ArrayList<>(
                        baseline.getSamples());
                samples.addAll(counterfactual.getSamples());
                candidate.setRuntimeSamples(samples);

                CostRuntimeObservation observation = buildObservation(candidate);
                PerformanceNineCellCategory category = PerformanceNineCellClassifier
                        .classify(getPerformanceNineCellOptions(), observation);
                candidate.setNineCellCategory(category);
                candidate.setDiagnosticOnly(!category.isConfirmedHarmful());
                telemetry.count("candidates.measured");
                telemetry.count("pairs.runtime_measured");
                lastGenerationMetadata.record(telemetry, "measured");
                telemetry.count("pairs.nine_cell_classified");
                telemetry.count("category." + category.getCode() + ".measured");
                telemetry.count("nine_cell." + category.getCode().toLowerCase(Locale.ROOT));
                measured.add(candidate);
            } catch (SQLException e) {
                telemetry.reject("runtime", "counterfactual_execution");
            }
        }
        return measured;
    }

    private double runtimePriorityScore(CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness candidate,
            CockroachDBPerformanceHintFeedbackManager feedbackManager) {
        CockroachDBPerformanceHintFeedbackManager.NoveltySnapshot novelty = feedbackManager
                .estimateNoveltySnapshot(candidate, getOptions());
        CockroachDBPerformanceHintFeedbackManager.RootCauseScarcitySnapshot scarcity = feedbackManager
                .getRootCauseScarcitySnapshot(candidate.getRootCauseSignatureKey(), getOptions());
        double costRatio = CockroachDBPerformanceHintOracleUtils.computePositiveRatio(
                candidate.getDefaultPlan().getQueryCost(), candidate.getHintedPlan().getQueryCost());
        return (costRatio + novelty.getOverallNovelty()) * scarcity.getBoost();
    }

    private RuntimeSeries measureRuntimeSeries(String query, String side) throws SQLException {
        int repeatCount = Math.max(1, getOptions().performanceRepeatCount);
        int timeoutSeconds = Math.max(1, getOptions().performanceTimeoutSeconds);
        List<Double> runsMs = new ArrayList<>();
        List<CockroachDBPerformanceHintOracleUtils.RuntimeSample> samples = new ArrayList<>();
        int timeoutCount = 0;
        for (int run = 0; run < repeatCount; run++) {
            QueryExecution execution = executeTimed(query, timeoutSeconds);
            if ("BASE".equals(side) && run == 0 && !execution.timedOut) {
                DataAwarePredicateBinder.recordRuntimeBaseRowCount(
                        CockroachDBPerformanceHintTelemetry.getInstance(state), execution.rowCount, query);
            }
            runsMs.add(execution.elapsedMs);
            if (execution.timedOut) {
                timeoutCount++;
            }
            samples.add(new CockroachDBPerformanceHintOracleUtils.RuntimeSample(run, side, execution.elapsedMs,
                    execution.timedOut, query));
        }
        return new RuntimeSeries(new CockroachDBPerformanceHintOracleUtils.RuntimeSummary(runsMs, timeoutCount),
                samples);
    }

    private QueryExecution executeTimed(String query, int timeoutSeconds) throws SQLException {
        long start = System.nanoTime();
        boolean timedOut = false;
        long rowCount = 0L;
        try (Statement statement = state.getConnection().createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            boolean hasResultSet = statement.execute(query);
            while (hasResultSet) {
                try (ResultSet rs = statement.getResultSet()) {
                    if (rs != null) {
                        while (rs.next()) {
                            rowCount++;
                        }
                    }
                }
                hasResultSet = statement.getMoreResults();
            }
        } catch (SQLTimeoutException e) {
            timedOut = true;
        }
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        return new QueryExecution(elapsedMs, timedOut, rowCount);
    }

    private Path persistReportCandidate(CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            CockroachDBPerformanceHintFeedbackManager feedbackManager, CockroachDBPerformanceHintTelemetry telemetry)
            throws IOException {
        CockroachDBPerformanceHintFeedbackManager.WitnessDecision writeDecision = feedbackManager.recordWitness(witness,
                getOptions());
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
        if (!writeDecision.shouldWriteReport()) {
            telemetry.count("reports.suppressed");
            return null;
        }
        CockroachDBPerformanceHintFeedbackManager.ReportReservation reservation = writeDecision.getReservation();
        if (reservation == null) {
            throw new IOException("accepted PERFORMANCE_HINT report is missing its feedback reservation");
        }
        boolean formalFailure = witness.getNineCellCategory().isConfirmedHarmful();
        PerformanceHintReportSnapshot snapshot;
        try {
            snapshot = CockroachDBPerformanceHintOracleUtils.snapshotWitness(state, witness,
                    witness.isDiagnosticOnly());
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
        if (getOptions().enablePerformanceAsyncReportWrites) {
            CockroachDBPerformanceHintReportWriter.getInstance(getOptions().performanceReportQueueCapacity).submit(
                    snapshot, reservation, feedbackManager, telemetry, getOptions().performanceReportQueueOfferMillis,
                    formalFailure);
            return snapshot.getFinalReportDirectory();
        }
        telemetry.count("reports.selected");
        try {
            Path reportDir = CockroachDBPerformanceHintOracleUtils.writeSnapshot(snapshot);
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

    private CostRuntimeObservation buildObservation(
            CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness candidate) {
        CockroachDBPerformanceHintOracleUtils.RuntimeSummary defaultRuntime = candidate.getDefaultRuntime();
        CockroachDBPerformanceHintOracleUtils.RuntimeSummary hintedRuntime = candidate.getHintedRuntime();
        return new CostRuntimeObservation(candidate.getDefaultPlan().getQueryCost(),
                candidate.getHintedPlan().getQueryCost(), defaultRuntime.getMedianMs(), hintedRuntime.getMedianMs(),
                defaultRuntime.getMeanMs(), hintedRuntime.getMeanMs(), defaultRuntime.getMinMs(),
                hintedRuntime.getMinMs(), defaultRuntime.getMaxMs(), hintedRuntime.getMaxMs(),
                defaultRuntime.getTimeoutCount(), hintedRuntime.getTimeoutCount(), defaultRuntime.getRunsMs(),
                hintedRuntime.getRunsMs());
    }

    private void annotateWitnessMetadata(CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            String skeletonKey, CockroachDBPerformanceHintFeedbackManager feedbackManager) {
        witness.setQueryGenerationMetadata(lastGenerationMetadata);
        witness.setSkeletonKey(skeletonKey);
        RootCauseClassification rootCause = CockroachDBPerformanceHintOracleUtils.classifyRootCause(witness);
        witness.setRootCauseIntentCategory(rootCause.getIntentCategory());
        witness.setRootCauseMechanismKey(rootCause.getMechanismKey());
        witness.setRootCauseSignatureKey(rootCause.getSignatureKey());
        witness.setRootCauseCategory(rootCause.getMechanismKey());
        witness.setFamilyKey(CockroachDBPerformanceHintOracleUtils.buildFamilyKey(witness));
        witness.setPlanSignatureKey(CockroachDBPerformanceHintOracleUtils.buildPlanSignatureKey(witness));
        CockroachDBPerformanceHintFeedbackManager.NoveltySnapshot noveltySnapshot = feedbackManager
                .estimateNoveltySnapshot(witness, getOptions());
        witness.setNoveltyScore(noveltySnapshot.getOverallNovelty());
        witness.setRootCauseNoveltyScore(noveltySnapshot.getRootCauseNovelty());
        if (witness.isHintCounterfactual()) {
            witness.setHintPolicyDecision(PerformanceHintHintPolicy.describePolicyDecision(getHintPolicyConfig()));
            witness.setHintFamilyQuotaApplied(PerformanceHintHintPolicy.describeAppliedQuota(getHintPolicyConfig(),
                    List.of(new GenericHintDescriptor(witness.getCounterfactualCandidate()))));
        } else {
            witness.setHintPolicyDecision("not_applicable_to_query_rewrite");
            witness.setHintFamilyQuotaApplied("not_applicable_to_query_rewrite");
        }
    }

    private CockroachDBOptions getOptions() {
        return state.getDbmsSpecificOptions();
    }

    private PolicyConfig getHintPolicyConfig() {
        return PolicyConfig.fromOptionStrings(getOptions().performanceHintIncludeTypes,
                getOptions().performanceHintExcludeTypes, getOptions().performanceHintFamilyQuota,
                getOptions().performanceHintFamilyWeightMode, getOptions().performanceHintMaxCandidatesPerFamily,
                CockroachDBPerformanceHintOracleUtils.MAX_HINTED_VARIANTS);
    }

    private PerformanceNineCellOptions getPerformanceNineCellOptions() {
        return new PerformanceNineCellOptions(getOptions().performanceTaxonomyEnable,
                getOptions().performanceTaxonomyCostRatioLowerBound,
                getOptions().performanceTaxonomyCostRatioUpperBound,
                getOptions().performanceTaxonomyRuntimeRatioLowerBound,
                getOptions().performanceTaxonomyRuntimeRatioUpperBound,
                getOptions().performanceTaxonomyMinRuntimeMedianMs, getOptions().performanceTaxonomyMinRuntimeAbsGapMs);
    }

    private boolean shouldReportNineCellCandidate(
            CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        return PerformanceNineCellReportPolicy.shouldReport(getOptions().performanceTaxonomyEnable,
                nineCellReportCategories, witness.getNineCellCategory(), buildObservation(witness),
                getOptions().performanceReportHarmfulRuntimeRatio, getOptions().performanceReportCostAnomalyRatio);
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

    private static final class BaseQuery {
        private final String sql;
        private final List<CockroachDBTable> referencedTables;
        private final CockroachDBPerformanceHintGenerator.QueryShape queryShape;
        private final List<JoinSpec> joinSpecs;
        private final List<QualifiedColumn> qualifiedColumns;

        private BaseQuery(String sql, List<CockroachDBTable> referencedTables,
                CockroachDBPerformanceHintGenerator.QueryShape queryShape, List<JoinSpec> joinSpecs,
                List<QualifiedColumn> qualifiedColumns) {
            this.sql = sql;
            this.referencedTables = List.copyOf(referencedTables);
            this.queryShape = queryShape;
            this.joinSpecs = List.copyOf(joinSpecs);
            this.qualifiedColumns = List.copyOf(qualifiedColumns);
        }

        private String getSql() {
            return sql;
        }

        private List<CockroachDBTable> getReferencedTables() {
            return referencedTables;
        }

        private CockroachDBPerformanceHintGenerator.QueryShape getQueryShape() {
            return queryShape;
        }

        @SuppressWarnings("unused")
        private List<JoinSpec> getJoinSpecs() {
            return joinSpecs;
        }

        @SuppressWarnings("unused")
        private List<QualifiedColumn> getQualifiedColumns() {
            return qualifiedColumns;
        }
    }

    private static final class QualifiedColumn {
        private final CockroachDBTable table;
        private final CockroachDBColumn column;
        private final String sql;

        private QualifiedColumn(CockroachDBTable table, CockroachDBColumn column) {
            this.table = table;
            this.column = column;
            this.sql = table.getName() + "." + column.getName();
        }
    }

    private static final class PredicateBundle {
        private final String sql;
        private final Set<String> constrainedTables;

        private PredicateBundle(String sql, Set<String> constrainedTables) {
            this.sql = sql;
            this.constrainedTables = Set.copyOf(constrainedTables);
        }

        private static PredicateBundle empty() {
            return new PredicateBundle("", Set.of());
        }

        private boolean hasWhereClause() {
            return !sql.isEmpty();
        }

        private String sql() {
            return sql;
        }

        private Set<String> constrainedTables() {
            return constrainedTables;
        }
    }

    private static final class FromClause {
        private final String sql;
        private final List<JoinSpec> joinSpecs;
        private final Set<String> constrainedTables;
        private final boolean hasInnerJoin;
        private final boolean hasLeftJoin;

        private FromClause(String sql, List<JoinSpec> joinSpecs, Set<String> constrainedTables, boolean hasInnerJoin,
                boolean hasLeftJoin) {
            this.sql = sql;
            this.joinSpecs = List.copyOf(joinSpecs);
            this.constrainedTables = Set.copyOf(constrainedTables);
            this.hasInnerJoin = hasInnerJoin;
            this.hasLeftJoin = hasLeftJoin;
        }
    }

    private static final class JoinEdge {
        private final CockroachDBTable leftTable;
        private final ColumnPair joinColumns;

        private JoinEdge(CockroachDBTable leftTable, ColumnPair joinColumns) {
            this.leftTable = leftTable;
            this.joinColumns = joinColumns;
        }
    }

    private static final class ColumnPair {
        private final CockroachDBColumn left;
        private final CockroachDBColumn right;

        private ColumnPair(CockroachDBColumn left, CockroachDBColumn right) {
            this.left = left;
            this.right = right;
        }
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
    }

    private static final class RuntimeSeries {
        private final CockroachDBPerformanceHintOracleUtils.RuntimeSummary summary;
        private final List<CockroachDBPerformanceHintOracleUtils.RuntimeSample> samples;

        private RuntimeSeries(CockroachDBPerformanceHintOracleUtils.RuntimeSummary summary,
                List<CockroachDBPerformanceHintOracleUtils.RuntimeSample> samples) {
            this.summary = summary;
            this.samples = List.copyOf(samples);
        }

        private CockroachDBPerformanceHintOracleUtils.RuntimeSummary getSummary() {
            return summary;
        }

        private List<CockroachDBPerformanceHintOracleUtils.RuntimeSample> getSamples() {
            return samples;
        }
    }
}
