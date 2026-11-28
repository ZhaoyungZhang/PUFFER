package sqlancer.oceanbase.oracle;

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
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.perfhint.CostRuntimeObservation;
import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.CounterfactualCandidateScheduler;
import sqlancer.common.perfhint.CounterfactualFallbackReason;
import sqlancer.common.perfhint.CounterfactualMutationKind;
import sqlancer.common.perfhint.CounterfactualStrategySelector;
import sqlancer.common.perfhint.DataAwarePredicateBinder;
import sqlancer.common.perfhint.ExpandedPerformanceQuery;
import sqlancer.common.perfhint.PerformanceHintHintPolicy;
import sqlancer.common.perfhint.PerformanceHintHintPolicy.PolicyConfig;
import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
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
import sqlancer.oceanbase.OceanBaseErrors;
import sqlancer.oceanbase.OceanBaseGlobalState;
import sqlancer.oceanbase.OceanBaseOptions;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseColumn;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseTable;
import sqlancer.oceanbase.gen.OceanBasePerformanceHintGenerator;
import sqlancer.oceanbase.gen.OceanBasePerformanceHintGenerator.HintedQueryCandidate;
import sqlancer.oceanbase.oracle.OceanBasePerformanceHintOracleUtils.PerformanceHintWitness;
import sqlancer.oceanbase.oracle.OceanBasePerformanceHintOracleUtils.PlanSummary;
import sqlancer.oceanbase.oracle.OceanBasePerformanceHintOracleUtils.RuntimeSample;
import sqlancer.oceanbase.oracle.OceanBasePerformanceHintOracleUtils.RuntimeSummary;

/**
 * Performance oracle for OceanBase MySQL mode. It compares optimizer-hint alternatives only after confirming the hint
 * is used, the results are equivalent, and the estimate/plan remains interesting after ANALYZE TABLE.
 */
public final class OceanBasePerformanceHintOracle implements TestOracle<OceanBaseGlobalState> {

    private final OceanBaseGlobalState state;
    private final PerformanceQueryRewriteProvider rewriteProvider;
    private final RewriteEquivalenceOracle rewriteEquivalenceOracle;
    private final Set<PerformanceNineCellCategory> nineCellReportCategories;
    private final ExpectedErrors errors = new ExpectedErrors();
    private Boolean explainAnalyzeSupported;
    private String lastQueryString;
    private PerformanceQueryMetadata lastGenerationMetadata;
    private boolean lastQueryUsableForQPG;

    public OceanBasePerformanceHintOracle(OceanBaseGlobalState state) {
        this(state, OceanBaseWeTuneStaticRewriteProvider.fromOptions(state.getDbmsSpecificOptions()),
                new WeTuneStaticRewriteEquivalenceOracle("oceanbase"));
    }

    OceanBasePerformanceHintOracle(OceanBaseGlobalState state, PerformanceQueryRewriteProvider rewriteProvider,
            RewriteEquivalenceOracle rewriteEquivalenceOracle) {
        this.state = state;
        this.rewriteProvider = rewriteProvider;
        this.rewriteEquivalenceOracle = rewriteEquivalenceOracle;
        this.nineCellReportCategories = PerformanceNineCellCategory
                .parseReportCells(state.getDbmsSpecificOptions().performanceTaxonomyReportCells);
        OceanBaseErrors.addExpressionErrors(errors);
    }

    @Override
    public void check() throws Exception {
        OceanBasePerformanceHintTelemetry telemetry = OceanBasePerformanceHintTelemetry.getInstance(state);
        long startedAt = System.nanoTime();
        telemetry.count("checks.started");
        try {
            checkPerformance(telemetry);
            telemetry.count("checks.completed");
        } catch (IgnoreMeException e) {
            telemetry.count("checks.ignored");
            if (lastGenerationMetadata != null) {
                lastGenerationMetadata.record(telemetry, "rejected");
            }
            // QPG needs a valid base SELECT even when it is not an interesting performance witness.
            if (qpgEnabled() && lastQueryUsableForQPG) {
                return;
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

    private void checkPerformance(OceanBasePerformanceHintTelemetry telemetry) throws Exception {
        lastQueryString = null;
        lastGenerationMetadata = null;
        lastQueryUsableForQPG = false;
        BaseQuery base;
        try {
            base = generateBaseQuery();
        } catch (IgnoreMeException e) {
            telemetry.reject("base_query", "generation_failed");
            throw e;
        }
        lastQueryString = base.sql;
        telemetry.count("base_queries.generated");
        lastGenerationMetadata.record(telemetry, "generated");
        telemetry.count("query_shape.projected_columns." + base.projectionCount);
        if (base.distinct) {
            telemetry.count("query_shape.distinct");
        }
        if (base.orderBy) {
            telemetry.count("query_shape.order_by");
        }
        if (base.limit) {
            telemetry.count("query_shape.limit");
        }
        if (base.compoundPredicate) {
            telemetry.count("query_shape.compound_predicate");
        }
        if (base.shape.hasJoin()) {
            telemetry.count("query_shape.join");
        }
        if (base.shape.hasGroupBy()) {
            telemetry.count("query_shape.group_by");
        }
        OceanBasePerformanceHintFeedbackManager feedback = OceanBasePerformanceHintFeedbackManager.getInstance(state);
        String skeletonKey = OceanBasePerformanceHintOracleUtils.buildSkeletonKey(base.sql, base.tables.size()) + "|"
                + lastGenerationMetadata.getStructuralKey();
        if (!qpgEnabled()) {
            OceanBasePerformanceHintFeedbackManager.SkeletonDecision decision = feedback.evaluateSkeleton(skeletonKey,
                    state.getRandomly(), options());
            if (decision.shouldSkip()) {
                logDebug("PH_SKIP repeated_skeleton count=" + decision.getHistoricalCount());
                telemetry.reject("base_query", "repeated_skeleton");
                throw new IgnoreMeException();
            }
        }

        PlanSummary defaultPlan = explain(base.sql, true);
        lastQueryUsableForQPG = defaultPlan != null;
        telemetry.count("base_explains.succeeded");
        lastGenerationMetadata.record(telemetry, "explained");
        List<HintedQueryCandidate> generated = OceanBasePerformanceHintGenerator.generateCandidates(base.tables,
                base.shape, state.getRandomly(), OceanBasePerformanceHintOracleUtils.MAX_INDEXES_PER_TABLE,
                OceanBasePerformanceHintOracleUtils.MAX_HINTED_VARIANTS);
        telemetry.add("hint_candidates.generated", generated.size());
        List<HintedQueryCandidate> selectedHints = PerformanceHintHintPolicy.selectCandidates(generated,
                policyConfig());
        telemetry.add("hint_candidates.selected", selectedHints.size());
        List<CounterfactualCandidate> hintCandidates = materializeHintCandidates(base.sql, selectedHints);
        telemetry.add("hint_candidates.materialized", hintCandidates.size());

        CounterfactualStrategySelector strategySelector = new CounterfactualStrategySelector(
                options().performanceCounterfactualHintProbability);
        CounterfactualMutationKind requestedStrategy = strategySelector.select(state.getRandomly());
        telemetry.count("batches.requested_" + requestedStrategy.name().toLowerCase(Locale.ROOT));
        List<CounterfactualCandidate> rewriteCandidates = requestedStrategy == CounterfactualMutationKind.QUERY_REWRITE
                ? collectRewriteCandidates(base, telemetry) : List.of();
        List<ScheduledCounterfactualCandidate> candidates;
        boolean strictWeTuneRewriteBatch = requestedStrategy == CounterfactualMutationKind.QUERY_REWRITE
                && "wetune-static".equalsIgnoreCase(options().performanceQueryRewriteProvider);
        if (strictWeTuneRewriteBatch && rewriteCandidates.isEmpty()) {
            telemetry.count("rewrite_batches.empty");
            candidates = List.of();
        } else {
            candidates = CounterfactualCandidateScheduler.scheduleBatch(base.sql,
                    OceanBasePerformanceHintOracleUtils.MAX_HINTED_VARIANTS, requestedStrategy, hintCandidates,
                    rewriteCandidates);
        }
        if (candidates.isEmpty() && requestedStrategy == CounterfactualMutationKind.HINT
                && strategySelector.shouldRequestRewrites()) {
            rewriteCandidates = collectRewriteCandidates(base, telemetry);
            candidates = CounterfactualCandidateScheduler.scheduleBatch(base.sql,
                    OceanBasePerformanceHintOracleUtils.MAX_HINTED_VARIANTS, requestedStrategy, hintCandidates,
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
        logDebug("PH_CANDIDATES generated=" + generated.size() + " selectedHints=" + selectedHints.size()
                + " scheduled=" + candidates.size());
        if (candidates.isEmpty()) {
            telemetry.reject("scheduling", "no_candidates");
            throw new IgnoreMeException();
        }

        List<PerformanceHintWitness> candidatesBeforeStats = new ArrayList<>();
        for (ScheduledCounterfactualCandidate scheduledCandidate : candidates) {
            CounterfactualCandidate candidate = scheduledCandidate.getCandidate();
            String counterfactualQuery = candidate.getAlternativeSql();
            telemetry.count("pairs.checked");
            logDebug("PH_TRY requestedStrategy=" + scheduledCandidate.getRequestedStrategy() + " actualMutationKind="
                    + candidate.getKind() + " mutationFamily=" + candidate.getFamily() + " mutationId="
                    + candidate.getMutationId() + " fallback=" + scheduledCandidate.getFallbackReason());
            PlanSummary counterfactualPlan = explain(counterfactualQuery, false);
            if (counterfactualPlan == null || !counterfactualPlan.hasPositiveCost()) {
                telemetry.reject("counterfactual_explain", "unavailable_or_invalid");
                continue;
            }
            telemetry.count("pairs.counterfactual_explain_succeeded");
            String counterfactualHintDetails = "";
            if (candidate.getKind() == CounterfactualMutationKind.HINT) {
                counterfactualHintDetails = explainExtended(counterfactualQuery);
                if (!isHintAccepted(counterfactualHintDetails, candidate.getMutationId())) {
                    telemetry.reject("hint_validation", "not_used");
                    continue;
                }
            }
            if (!OceanBasePerformanceHintOracleUtils.hasMeaningfulPlanDifference(defaultPlan, counterfactualPlan)) {
                telemetry.count("pairs.plan_same");
                telemetry.reject("plan_difference", "same");
                continue;
            }
            telemetry.count("pairs.plan_different");
            List<String> evidence = OceanBasePerformanceHintOracleUtils.buildPlanDifferenceEvidence(defaultPlan,
                    counterfactualPlan);
            if (evidence.isEmpty()) {
                telemetry.reject("plan_evidence", "empty");
                continue;
            }
            telemetry.count("pairs.plan_difference_qualified");
            PerformanceHintWitness witness = new PerformanceHintWitness(base.sql, counterfactualQuery, candidate,
                    defaultPlan, counterfactualPlan, evidence, scheduledCandidate.getRequestedStrategy(),
                    scheduledCandidate.getFallbackReason().name());
            if (candidate.getKind() == CounterfactualMutationKind.HINT) {
                witness.setHintDetails(explainExtended(base.sql), counterfactualHintDetails);
            }
            annotateFeedbackKeys(witness, skeletonKey);
            candidatesBeforeStats.add(witness);
            telemetry.count("pairs.pre_refresh_qualified");
        }
        if (candidatesBeforeStats.isEmpty()) {
            telemetry.reject("pre_refresh", "no_qualified_pairs");
            throw new IgnoreMeException();
        }

        // Dataset construction and successful provider mutations already refreshed statistics.
        List<PerformanceHintWitness> reportCandidates = validateRuntime(candidatesBeforeStats, telemetry);
        if (reportCandidates.isEmpty()) {
            telemetry.reject("runtime_validation", "no_reportable_a_i_candidate");
            throw new IgnoreMeException();
        }

        PerformanceHintWitness firstFormalWitness = null;
        Path firstFormalReport = null;
        int confirmedReports = 0;
        for (PerformanceHintWitness reportCandidate : reportCandidates) {
            if (!validateDeferredResultEquivalence(reportCandidate, telemetry)) {
                continue;
            }
            confirmedReports++;
            telemetry.count("pairs.oracle_qualified");
            if (options().enablePerformanceExplainAnalyze) {
                collectPlanDetails(reportCandidate);
            }
            boolean formalFailure = reportCandidate.getNineCellCategory().isConfirmedHarmful();
            if (formalFailure && firstFormalWitness == null) {
                firstFormalWitness = reportCandidate;
            }
            Path reportDirectory = persistReportCandidate(reportCandidate, feedback, telemetry);
            if (formalFailure && firstFormalReport == null) {
                firstFormalReport = reportDirectory;
            }
        }
        if (confirmedReports == 0) {
            telemetry.reject("runtime_validation", "no_strictly_equivalent_report_candidate");
            throw new IgnoreMeException();
        }
        if (firstFormalWitness != null) {
            if (options().continueAfterPerformanceFormalWitness) {
                telemetry.count("formal_witness.continued");
                return;
            }
            telemetry.count("formal_witness.fail_fast");
            enforceFormalWitnessPolicy(false, firstFormalWitness, firstFormalReport);
        }
    }

    static void enforceFormalWitnessPolicy(boolean continueAfterFormalWitness, PerformanceHintWitness witness,
            Path reportDirectory) {
        if (!continueAfterFormalWitness) {
            throw new AssertionError(
                    OceanBasePerformanceHintOracleUtils.createAssertionMessage(witness, reportDirectory));
        }
    }

    private Path persistReportCandidate(PerformanceHintWitness witness,
            OceanBasePerformanceHintFeedbackManager feedback, OceanBasePerformanceHintTelemetry telemetry)
            throws IOException {
        OceanBasePerformanceHintFeedbackManager.WitnessDecision decision = feedback.recordWitness(witness, options());
        if (!decision.shouldWriteReport()) {
            telemetry.count("reports.suppressed");
            return null;
        }
        OceanBasePerformanceHintFeedbackManager.ReportReservation reservation = decision.getReservation();
        if (reservation == null) {
            throw new IOException("accepted OceanBase PERFORMANCE_HINT report has no feedback reservation");
        }
        PerformanceHintReportSnapshot snapshot;
        try {
            snapshot = OceanBasePerformanceHintOracleUtils.snapshotWitness(state, witness);
            telemetry.count("reports.snapshot_created");
        } catch (RuntimeException exception) {
            telemetry.count("reports.snapshot_failed");
            feedback.failReservation(reservation, exception);
            throw exception;
        }
        boolean formalFailure = witness.getNineCellCategory().isConfirmedHarmful();
        if (options().enablePerformanceAsyncReportWrites) {
            OceanBasePerformanceHintReportWriter.getInstance(options().performanceReportQueueCapacity).submit(snapshot,
                    reservation, feedback, telemetry, options().performanceReportQueueOfferMillis, formalFailure);
            return snapshot.getFinalReportDirectory();
        }
        telemetry.count("reports.selected");
        try {
            Path reportDirectory = OceanBasePerformanceHintOracleUtils.writeSnapshot(snapshot);
            feedback.completeReservation(reservation, reportDirectory);
            telemetry.count("reports.written");
            telemetry.count(formalFailure ? "reports.formal_written" : "reports.observation_written");
            state.getState().getLocalState().log(witness.getBaseQuery());
            state.getState().getLocalState().log(witness.getCounterfactualQuery());
            return reportDirectory;
        } catch (IOException exception) {
            telemetry.count("reports.write_failed");
            feedback.failReservation(reservation, exception);
            throw exception;
        }
    }

    @Override
    public String getLastQueryString() {
        return lastQueryString;
    }

    private List<PerformanceHintWitness> validateRuntime(List<PerformanceHintWitness> witnesses,
            OceanBasePerformanceHintTelemetry telemetry) throws Exception {
        List<PerformanceHintWitness> ordered = new ArrayList<>(witnesses);
        ordered.sort(Comparator.comparingDouble(this::estimateInterest).reversed());
        if (ordered.isEmpty()) {
            return List.of();
        }

        PerformanceHintWitness baselineSource = ordered.get(0);
        RuntimeSeries baseRuntime;
        try {
            QueryExecution warmup = executeAndConsume(baselineSource.getBaseQuery());
            telemetry.count("runtime.warmup_base_samples");
            telemetry.add("runtime.warmup_samples", 1L);
            telemetry.add("runtime.timeout_samples", warmup.timedOut ? 1L : 0L);
            baseRuntime = measureRuntimeSeries(baselineSource.getBaseQuery(), "BASE", options().performanceRepeatCount,
                    1, true, telemetry);
            telemetry.add("runtime.timeout_samples", baseRuntime.summary.getTimeoutCount());
        } catch (Exception | AssertionError e) {
            telemetry.reject("runtime_validation", "base_execution_failed_or_timeout");
            return List.of();
        }

        List<PerformanceHintWitness> reportCandidates = new ArrayList<>();
        for (PerformanceHintWitness witness : ordered) {
            try {
                telemetry.count("pairs.runtime_attempted");
                QueryExecution warmup = executeAndConsume(witness.getCounterfactualQuery());
                telemetry.count("runtime.warmup_counterfactual_samples");
                telemetry.add("runtime.warmup_samples", 1L);
                telemetry.add("runtime.timeout_samples", warmup.timedOut ? 1L : 0L);
                RuntimeSeries counterfactualRuntime = measureRuntimeSeries(witness.getCounterfactualQuery(),
                        witness.getCounterfactualRuntimeSide(), options().performanceRepeatCount,
                        baseRuntime.samples.size() + 1, false, telemetry);
                witness.setRuntime(baseRuntime.summary, counterfactualRuntime.summary,
                        combineRuntimeSamples(baseRuntime, counterfactualRuntime));
                telemetry.add("runtime.timeout_samples", counterfactualRuntime.summary.getTimeoutCount());
                telemetry.count("pairs.runtime_measured");
                lastGenerationMetadata.record(telemetry, "measured");

                CostRuntimeObservation observation = OceanBasePerformanceHintOracleUtils
                        .buildRuntimeObservation(witness);
                PerformanceNineCellCategory nineCell = PerformanceNineCellClassifier
                        .classify(getPerformanceNineCellOptions(), observation);
                witness.setNineCellCategory(nineCell);
                telemetry.count("pairs.nine_cell_classified");
                telemetry.count("nine_cell." + nineCell.getCode().toLowerCase(Locale.ROOT));
                if (!shouldReportNineCellCandidate(nineCell, observation)) {
                    telemetry.reject("nine_cell_report_policy",
                            "cell_" + nineCell.getCode().toLowerCase(Locale.ROOT) + "_not_reportable");
                    continue;
                }
                telemetry.count("pairs.nine_cell_reportable");
                telemetry.count("nine_cell.reportable." + nineCell.getCode().toLowerCase(Locale.ROOT));
                reportCandidates.add(witness);
            } catch (Exception | AssertionError e) {
                telemetry.reject("runtime_validation", "execution_failed_or_timeout");
            }
        }
        return reportCandidates;
    }

    private RuntimeSeries measureRuntimeSeries(String query, String side, int repeatCount, int firstSampleSequence,
            boolean baseSide, OceanBasePerformanceHintTelemetry telemetry) throws Exception {
        List<Double> samplesMs = new ArrayList<>();
        List<RuntimeSample> samples = new ArrayList<>();
        int timeoutCount = 0;
        for (int index = 0; index < Math.max(1, repeatCount); index++) {
            QueryExecution execution = executeAndConsume(query);
            telemetry.count(
                    baseSide ? "runtime.measurement_base_samples" : "runtime.measurement_counterfactual_samples");
            if (baseSide && index == 0 && !execution.timedOut) {
                DataAwarePredicateBinder.recordRuntimeBaseRowCount(telemetry, execution.rowCount, query);
            }
            samplesMs.add(execution.elapsedMs);
            if (execution.timedOut) {
                timeoutCount++;
            }
            samples.add(new RuntimeSample(firstSampleSequence + index, side, execution.elapsedMs, execution.timedOut));
        }
        return new RuntimeSeries(new RuntimeSummary(samplesMs, timeoutCount), samples);
    }

    private static List<RuntimeSample> combineRuntimeSamples(RuntimeSeries baseRuntime,
            RuntimeSeries counterfactualRuntime) {
        List<RuntimeSample> samples = new ArrayList<>(baseRuntime.samples);
        samples.addAll(counterfactualRuntime.samples);
        return samples;
    }

    private boolean shouldReportNineCellCandidate(PerformanceNineCellCategory nineCell,
            CostRuntimeObservation observation) {
        return PerformanceNineCellReportPolicy.shouldReport(options().performanceTaxonomyEnable,
                nineCellReportCategories, nineCell, observation, options().performanceReportHarmfulRuntimeRatio,
                options().performanceReportCostAnomalyRatio);
    }

    private QueryExecution executeAndConsume(String query) throws Exception {
        long start = System.nanoTime();
        SQLQueryAdapter adapter = new SQLQueryAdapter(query, errors);
        try (SQLancerResultSet rs = adapter.executeAndGet(state, timeoutSeconds())) {
            if (rs == null) {
                throw new IgnoreMeException();
            }
            long rowCount = 0L;
            while (rs.next()) {
                rowCount++;
            }
            return QueryExecution.completed(elapsedMs(start), rowCount);
        } catch (SQLTimeoutException e) {
            return QueryExecution.timedOut(elapsedMs(start));
        }
    }

    private PlanSummary explain(String query, boolean required) throws SQLException {
        SQLQueryAdapter adapter = new SQLQueryAdapter("EXPLAIN FORMAT=JSON " + query, errors);
        logQuery(adapter.getQueryString());
        try (SQLancerResultSet rs = adapter.executeAndGet(state, timeoutSeconds())) {
            if (rs == null) {
                if (required) {
                    throw new IgnoreMeException();
                }
                return null;
            }
            String explainJson = collectFirstColumn(rs);
            PlanSummary plan = OceanBasePerformanceHintOracleUtils.parsePlan(explainJson);
            if (!plan.hasPositiveCost() && required) {
                logDebug("PH_SKIP_EXPLAIN missing_est_time");
                throw new IgnoreMeException();
            }
            return plan.hasPositiveCost() ? plan : null;
        } catch (Exception | AssertionError e) {
            logDebug("PH_SKIP_EXPLAIN " + e.getClass().getSimpleName());
            if (required) {
                throw new IgnoreMeException();
            }
            return null;
        }
    }

    private static String collectFirstColumn(SQLancerResultSet rs) throws SQLException {
        StringBuilder sb = new StringBuilder();
        while (rs.next()) {
            if (sb.length() > 0) {
                sb.append(System.lineSeparator());
            }
            sb.append(rs.getString(1));
        }
        return sb.toString();
    }

    private String explainExtended(String query) {
        return collectExplainText("EXPLAIN EXTENDED " + query);
    }

    private String collectExplainText(String query) {
        SQLQueryAdapter adapter = new SQLQueryAdapter(query, errors);
        try (SQLancerResultSet rs = adapter.executeAndGet(state, timeoutSeconds())) {
            if (rs == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                if (sb.length() > 0) {
                    sb.append(System.lineSeparator());
                }
                sb.append(rs.getString(1));
            }
            return sb.toString();
        } catch (Exception | AssertionError ignored) {
            return "";
        }
    }

    private void collectPlanDetails(PerformanceHintWitness witness) {
        if (explainAnalyzeSupported == null || explainAnalyzeSupported) {
            String defaultDetails = collectExplainText("EXPLAIN ANALYZE " + witness.getBaseQuery());
            String hintedDetails = collectExplainText("EXPLAIN ANALYZE " + witness.getCounterfactualQuery());
            if (!defaultDetails.isEmpty() && !hintedDetails.isEmpty()) {
                explainAnalyzeSupported = true;
                witness.setPlanDetails("EXPLAIN_ANALYZE", defaultDetails, hintedDetails);
                return;
            }
            explainAnalyzeSupported = false;
        }
        // EXPLAIN EXTENDED is a HINT acceptance diagnostic. Rewrites are
        // already admitted by their equivalence oracle and must not be passed
        // through OceanBase's Used Hint machinery.
        if (witness.isHintCounterfactual()) {
            witness.setPlanDetails("EXPLAIN_EXTENDED", explainExtended(witness.getBaseQuery()),
                    explainExtended(witness.getCounterfactualQuery()));
        } else {
            witness.setPlanDetails("UNAVAILABLE", "", "");
        }
    }

    private boolean isHintAccepted(String extendedPlan, String hintType) {
        return OceanBasePerformanceHintOracleUtils.isHintAccepted(extendedPlan, hintType);
    }

    private boolean validateDeferredResultEquivalence(PerformanceHintWitness witness,
            OceanBasePerformanceHintTelemetry telemetry) {
        try {
            telemetry.count("result_equivalence.deferred_attempted");
            List<List<String>> baseRows = fetchResultRows(witness.getBaseQuery(), telemetry, "BASE");
            List<List<String>> counterfactualRows = fetchResultRows(witness.getCounterfactualQuery(), telemetry,
                    witness.getCounterfactualRuntimeSide());
            boolean equivalent = OceanBasePerformanceHintOracleUtils.resultRowsMatch(baseRows, counterfactualRows);
            if (equivalent) {
                witness.setResultEquivalent(true);
                telemetry.count("result_equivalence.deferred_confirmed");
                telemetry.count("pairs.equivalent_plan_different_confirmed");
            } else {
                telemetry.count("result_equivalence.deferred_rejected");
                telemetry.reject("result_equivalence_deferred", "result_mismatch");
            }
            return equivalent;
        } catch (Exception | AssertionError e) {
            telemetry.count("result_equivalence.deferred_rejected");
            telemetry.reject("result_equivalence_deferred", "execution_failed_or_timeout");
            return false;
        }
    }

    private List<List<String>> fetchResultRows(String query, OceanBasePerformanceHintTelemetry telemetry, String side)
            throws SQLException {
        SQLQueryAdapter adapter = new SQLQueryAdapter(query, errors);
        try (SQLancerResultSet rs = adapter.executeAndGet(state, timeoutSeconds())) {
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
            telemetry.count("result_equivalence.executions");
            telemetry.count("BASE".equals(side) ? "result_equivalence.base_executions"
                    : "result_equivalence.counterfactual_executions");
            telemetry.count("result_equivalence.execution_succeeded");
            return result;
        }
    }

    private List<CounterfactualCandidate> materializeHintCandidates(String baseSql,
            List<HintedQueryCandidate> selectedHints) {
        List<CounterfactualCandidate> materialized = new ArrayList<>();
        for (HintedQueryCandidate hint : selectedHints) {
            String hintedSql = OceanBasePerformanceHintOracleUtils.injectHint(baseSql, hint.getHintSql());
            materialized.add(CounterfactualCandidate.hint(hintedSql, hint.getHintFamily(), hint.getHintType(),
                    hint.getHintSql(), hint.getTableNames(), hint.getIndexNames()));
        }
        return materialized;
    }

    private List<CounterfactualCandidate> collectRewriteCandidates(BaseQuery base,
            OceanBasePerformanceHintTelemetry telemetry) {
        telemetry.count("rewrite_provider.requests");
        List<String> targetTables = base.tables.stream().map(OceanBaseTable::getName).collect(Collectors.toList());
        List<String> targetIndexes = base.tables.stream().flatMap(table -> table.getIndexes().stream())
                .map(index -> index.getIndexName()).collect(Collectors.toList());
        QueryRewriteRequest request = new QueryRewriteRequest(base.sql, "oceanbase",
                OceanBasePerformanceHintOracleUtils.MAX_HINTED_VARIANTS, summarizeSchema(base.tables));
        List<CounterfactualCandidate> candidates = CounterfactualCandidateScheduler.collectValidatedRewriteCandidates(
                request, rewriteProvider, rewriteEquivalenceOracle, targetTables, targetIndexes);
        telemetry.add("rewrite_candidates.admitted", candidates.size());
        if (rewriteProvider instanceof WeTuneStaticRewriteProvider) {
            logDebug(((WeTuneStaticRewriteProvider) rewriteProvider).getLastDiagnostic());
        }
        return candidates;
    }

    private static QueryRewriteSchemaSummary summarizeSchema(List<OceanBaseTable> tables) {
        List<TableSummary> summaries = new ArrayList<>();
        for (OceanBaseTable table : tables) {
            List<ColumnSummary> columns = table.getColumns().stream()
                    .map(column -> new ColumnSummary(column.getName(), String.valueOf(column.getType())))
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
            ExpandedPerformanceQuery.Result<OceanBaseTable, OceanBaseColumn> expanded = OceanBaseExpandedQueryGenerator
                    .generate(state, state.getSchema().getDatabaseTables(), family,
                            reason -> OceanBasePerformanceHintTelemetry.getInstance(state)
                                    .count("query_generation." + reason));
            lastGenerationMetadata = expanded.metadata;
            return new BaseQuery(expanded.sql, expanded.tables,
                    new OceanBasePerformanceHintGenerator.QueryShape(expanded.where, !expanded.joins.isEmpty(),
                            expanded.groupBy),
                    expanded.projectionCount, expanded.distinct, expanded.orderBy, expanded.limit,
                    expanded.metadata.getFeatures().contains("COMPOUND_PREDICATE"));
        }
        BaseQuery base = generateStructuredBaseQuery();
        lastGenerationMetadata = PerformanceQueryMetadata
                .structured(state.getDbmsSpecificOptions().performanceQueryGeneratorMode, base.sql);
        return base;
    }

    private BaseQuery generateStructuredBaseQuery() {
        return OceanBasePerformanceQueryGenerator.generate(state, state.getSchema().getDatabaseTables());
    }

    private void annotateFeedbackKeys(PerformanceHintWitness witness, String skeletonKey) {
        witness.setQueryGenerationMetadata(lastGenerationMetadata);
        witness.setFeedbackKeys(skeletonKey, OceanBasePerformanceHintOracleUtils.buildPlanSignatureKey(witness),
                OceanBasePerformanceHintOracleUtils.buildRootCauseKey(witness));
    }

    private double estimateInterest(PerformanceHintWitness witness) {
        double lower = OceanBasePerformanceHintOracleUtils.positiveRatio(witness.getDefaultPlan().getEstimatedTimeUs(),
                witness.getHintedPlan().getEstimatedTimeUs());
        double higher = OceanBasePerformanceHintOracleUtils.positiveRatio(witness.getHintedPlan().getEstimatedTimeUs(),
                witness.getDefaultPlan().getEstimatedTimeUs());
        return Math.max(lower, higher) * (1.0 + witness.getPlanEvidence().size());
    }

    private PerformanceNineCellOptions getPerformanceNineCellOptions() {
        OceanBaseOptions options = options();
        return new PerformanceNineCellOptions(options.performanceTaxonomyEnable,
                options.performanceTaxonomyCostRatioLowerBound, options.performanceTaxonomyCostRatioUpperBound,
                options.performanceTaxonomyRuntimeRatioLowerBound, options.performanceTaxonomyRuntimeRatioUpperBound,
                options.performanceTaxonomyMinRuntimeMedianMs, options.performanceTaxonomyMinRuntimeAbsGapMs);
    }

    private PolicyConfig policyConfig() {
        OceanBaseOptions options = options();
        return PolicyConfig.fromOptionStrings(options.performanceHintIncludeTypes, options.performanceHintExcludeTypes,
                options.performanceHintFamilyQuota, options.performanceHintFamilyWeightMode,
                options.performanceHintMaxCandidatesPerFamily, OceanBasePerformanceHintOracleUtils.MAX_HINTED_VARIANTS);
    }

    private int timeoutSeconds() {
        return Math.max(1, options().performanceTimeoutSeconds);
    }

    private boolean qpgEnabled() {
        return state.getOptions().enableQPG();
    }

    private OceanBaseOptions options() {
        return state.getDbmsSpecificOptions();
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private void logQuery(String query) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(query);
        }
    }

    private void logDebug(String message) {
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent("-- " + message + System.lineSeparator());
        }
    }

    static final class BaseQuery {
        final String sql;
        final List<OceanBaseTable> tables;
        final OceanBasePerformanceHintGenerator.QueryShape shape;

        final int projectionCount;
        final boolean distinct;
        final boolean orderBy;
        final boolean limit;
        final boolean compoundPredicate;

        BaseQuery(String sql, List<OceanBaseTable> tables, OceanBasePerformanceHintGenerator.QueryShape shape,
                int projectionCount, boolean distinct, boolean orderBy, boolean limit, boolean compoundPredicate) {
            this.sql = sql;
            this.tables = List.copyOf(tables);
            this.shape = shape;
            this.projectionCount = projectionCount;
            this.distinct = distinct;
            this.orderBy = orderBy;
            this.limit = limit;
            this.compoundPredicate = compoundPredicate;
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

        static QueryExecution completed(double elapsedMs, long rowCount) {
            return new QueryExecution(elapsedMs, false, rowCount);
        }

        static QueryExecution timedOut(double elapsedMs) {
            return new QueryExecution(elapsedMs, true, 0L);
        }
    }

    private static final class RuntimeSeries {
        private final RuntimeSummary summary;
        private final List<RuntimeSample> samples;

        RuntimeSeries(RuntimeSummary summary, List<RuntimeSample> samples) {
            this.summary = summary;
            this.samples = List.copyOf(samples);
        }
    }
}
