package sqlancer.mysql;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import sqlancer.DBMSSpecificOptions;
import sqlancer.OracleFactory;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.perfhint.CounterfactualHintProbabilityValidator;
import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode;
import sqlancer.common.perfhint.PositiveIntegerValidator;
import sqlancer.mysql.MySQLOptions.MySQLOracleFactory;
import sqlancer.mysql.oracle.MySQLCERTOracle;
import sqlancer.mysql.oracle.MySQLDQPOracle;
import sqlancer.mysql.oracle.MySQLFuzzer;
import sqlancer.mysql.oracle.MySQLPerformanceHintOracle;
import sqlancer.mysql.oracle.MySQLPivotedQuerySynthesisOracle;
import sqlancer.mysql.oracle.MySQLTLPWhereOracle;

@Parameters(separators = "=", commandDescription = "MySQL (default port: " + MySQLOptions.DEFAULT_PORT
        + ", default host: " + MySQLOptions.DEFAULT_HOST + ")")
public class MySQLOptions implements DBMSSpecificOptions<MySQLOracleFactory> {
    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 3306;

    @Parameter(names = "--oracle")
    public List<MySQLOracleFactory> oracles = Arrays.asList(MySQLOracleFactory.TLP_WHERE);

    @Parameter(names = "--perf-query-generator-mode", description = "PERFORMANCE_HINT query generation: structured, expanded, mixed", converter = PerformanceQueryGenerationMode.Converter.class)
    public PerformanceQueryGenerationMode performanceQueryGeneratorMode = PerformanceQueryGenerationMode.MIXED;

    @Parameter(names = "--perf-cost-ratio-threshold", description = "Minimum default/hinted EXPLAIN cost ratio required before a hinted plan is considered a performance candidate")
    public double performanceCostRatioThreshold = 10.0;

    @Parameter(names = "--perf-runtime-ratio-threshold", description = "Minimum default/hinted runtime ratio, computed from the median of repeated runs")
    public double performanceRuntimeRatioThreshold = 10.0;

    @Parameter(names = "--perf-repeat", description = "Number of measured executions per query")
    public int performanceRepeatCount = 5;

    @Parameter(names = "--perf-enable-runtime-validation", description = "Run candidate queries repeatedly and compare wall-clock runtime")
    public boolean enablePerformanceRuntimeValidation;

    @Parameter(names = "--perf-enable-explain-analyze", description = "Collect EXPLAIN ANALYZE after runtime validation succeeds")
    public boolean enablePerformanceExplainAnalyze;

    @Parameter(names = "--perf-timeout-seconds", description = "Per-query timeout cap used during result equivalence checks, runtime validation, and EXPLAIN ANALYZE")
    public int performanceTimeoutSeconds = 20;

    @Parameter(names = "--perf-enable-feedback-dedup", description = "Enable online duplicate suppression and persistent feedback-driven sampling for PERFORMANCE_HINT")
    public boolean enablePerformanceFeedbackDedup;

    @Parameter(names = "--perf-continue-after-formal-witness", arity = 1, description = "Keep testing the current database after a formal PERFORMANCE_HINT witness has been recorded, instead of using SQLancer's fail-fast assertion")
    public boolean continueAfterPerformanceFormalWitness;

    @Parameter(names = "--perf-async-report-writes", arity = 1, description = "Persist PERFORMANCE_HINT witness reports through a bounded asynchronous writer")
    public boolean enablePerformanceAsyncReportWrites;

    @Parameter(names = "--perf-report-queue-capacity", description = "Maximum queued immutable PERFORMANCE_HINT reports before the producer applies backpressure", validateWith = PositiveIntegerValidator.class)
    public int performanceReportQueueCapacity = 256;

    @Parameter(names = "--perf-report-queue-offer-ms", description = "Milliseconds a worker waits for the PERFORMANCE_HINT report queue before writing the report itself", validateWith = PositiveIntegerValidator.class)
    public int performanceReportQueueOfferMillis = 50;

    @Parameter(names = "--perf-feedback-state-path", description = "Directory used to persist PERFORMANCE_HINT feedback state across campaigns")
    public String performanceFeedbackStatePath;

    @Parameter(names = "--perf-feedback-skeleton-threshold", description = "Skeleton witness count after which PERFORMANCE_HINT starts probabilistically skipping repeated query shapes")
    public int performanceFeedbackSkeletonThreshold = 8;

    @Parameter(names = "--perf-feedback-signature-hard-cap", description = "Maximum number of persisted reports for the same PERFORMANCE_HINT plan signature")
    public int performanceFeedbackSignatureHardCap = 2;

    @Parameter(names = "--perf-feedback-enable-root-cause-dedup", description = "Enable root-cause-level duplicate suppression for PERFORMANCE_HINT")
    public boolean enablePerformanceFeedbackRootCauseDedup = true;

    @Parameter(names = "--perf-feedback-root-cause-hard-cap", description = "Maximum number of persisted reports for the same PERFORMANCE_HINT root cause")
    public int performanceFeedbackRootCauseHardCap = 4;

    @Parameter(names = "--perf-feedback-root-cause-skeleton-share-threshold", description = "Dominant root-cause share threshold used to boost skeleton skipping for saturated shapes")
    public double performanceFeedbackRootCauseSkeletonShareThreshold = 0.7;

    @Parameter(names = "--perf-feedback-root-cause-skip-multiplier", description = "Multiplier applied to skeleton skip probability when a dominant root cause is already saturated")
    public double performanceFeedbackRootCauseSkipMultiplier = 1.5;

    @Parameter(names = "--perf-feedback-root-cause-newness-boost", description = "Multiplier applied to skeleton skip probability when a skeleton recently discovered a new root cause")
    public double performanceFeedbackRootCauseNewnessBoost = 0.5;

    @Parameter(names = "--perf-feedback-enable-representative-replacement", description = "Allow better representatives to replace existing root-cause exemplars")
    public boolean enablePerformanceFeedbackRepresentativeReplacement = true;

    @Parameter(names = "--perf-data-generator-mode", description = "Data generator used by PERFORMANCE_HINT: legacy or model-v1")
    public String performanceDataGeneratorMode = "legacy";

    @Parameter(names = "--perf-data-ablation-profile", description = "MySQL model-v1 data ablation with matched table sizes and indexes: none, random, column, column-intra, column-inter, full", converter = PerformanceHintDataAblationProfile.Converter.class)
    public PerformanceHintDataAblationProfile performanceDataAblationProfile = PerformanceHintDataAblationProfile.NONE;

    @Parameter(names = "--perf-min-tables", description = "Minimum number of tables generated by the PERFORMANCE_HINT model-v1 data generator")
    public int performanceMinTables = 1;

    @Parameter(names = "--perf-max-tables", description = "Maximum number of tables generated by the PERFORMANCE_HINT model-v1 data generator")
    public int performanceMaxTables = 5;

    @Parameter(names = "--perf-min-rows-per-table", description = "Minimum rows per table generated by the PERFORMANCE_HINT model-v1 data generator")
    public int performanceMinRowsPerTable = 10;

    @Parameter(names = "--perf-max-rows-per-table", description = "Maximum rows per table generated by the PERFORMANCE_HINT model-v1 data generator")
    public int performanceMaxRowsPerTable = 1000;

    @Parameter(names = "--perf-initial-scale", description = "Initial PDG scale multiplier s_0 applied to sampled model-v1 table row counts (default 1; set 10 for Large-only)", validateWith = PositiveIntegerValidator.class)
    public int performanceInitialScale = 1;

    @Parameter(names = "--perf-min-columns", description = "Minimum columns per table generated by the PERFORMANCE_HINT model-v1 data generator")
    public int performanceMinColumns = 3;

    @Parameter(names = "--perf-max-columns", description = "Maximum columns per table generated by the PERFORMANCE_HINT model-v1 data generator")
    public int performanceMaxColumns = 6;

    @Parameter(names = "--perf-max-indexes-per-table", description = "Maximum model-derived indexes per table for PERFORMANCE_HINT model-v1")
    public int performanceMaxIndexesPerTable = 3;

    @Parameter(names = "--perf-insert-batch-size", description = "Rows per multi-row INSERT used by the MySQL PERFORMANCE_HINT model-v1 data generator (1-2000)", validateWith = MySQLPerformanceInsertBatchSizeValidator.class)
    public int performanceInsertBatchSize = 25;

    @Parameter(names = "--perf-enable-histogram-refresh", description = "Refresh MySQL histograms during PERFORMANCE_HINT statistics maintenance")
    public boolean performanceEnableHistogramRefresh = true;

    @Parameter(names = "--perf-disable-histogram-refresh", description = "Disable MySQL histogram refresh during PERFORMANCE_HINT statistics maintenance")
    public boolean performanceDisableHistogramRefresh;

    @Parameter(names = "--perf-max-histogram-columns-per-table", description = "Maximum number of columns per table included in MySQL histogram refresh statements")
    public int performanceMaxHistogramColumnsPerTable = 2;

    @Parameter(names = "--perf-model-single-column-probability", description = "Probability that each non-key column receives a non-uniform data model")
    public double performanceSingleColumnModelProbability = 0.8;

    @Parameter(names = "--perf-model-multi-column-probability", description = "Probability that each table receives an intra-table correlation model")
    public double performanceMultiColumnModelProbability = 0.6;

    @Parameter(names = "--perf-model-inter-table-probability", description = "Probability that a multi-table database receives an inter-table correlation model")
    public double performanceInterTableModelProbability = 0.8;

    @Parameter(names = "--perf-hint-include-types", description = "Comma-separated PERFORMANCE_HINT hint types to include")
    public String performanceHintIncludeTypes = "";

    @Parameter(names = "--perf-hint-exclude-types", description = "Comma-separated PERFORMANCE_HINT hint types to exclude")
    public String performanceHintExcludeTypes = "";

    @Parameter(names = "--perf-allow-bka-hint", arity = 1, description = "Allow BKA optimizer hint in MySQL PERFORMANCE_HINT (disabled by default under batched_key_access=off)")
    public boolean performanceAllowBkaHint;

    @Parameter(names = "--perf-hint-family-quota", description = "Maximum number of selected hinted candidates per hint family for each base query")
    public int performanceHintFamilyQuota = 8;

    @Parameter(names = "--perf-hint-family-weight-mode", description = "Hint family balancing mode: uniform or feedback")
    public String performanceHintFamilyWeightMode = "uniform";

    @Parameter(names = "--perf-hint-max-candidates-per-family", description = "Maximum number of selected hinted candidates per hint family after filtering")
    public int performanceHintMaxCandidatesPerFamily = 8;

    @Parameter(names = "--perf-counterfactual-hint-probability", description = "Probability of selecting the HINT candidate batch, rather than the QUERY_REWRITE batch, for each PERFORMANCE_HINT base query (0.0 to 1.0)", validateWith = CounterfactualHintProbabilityValidator.class)
    public double performanceCounterfactualHintProbability = 0.5;

    @Parameter(names = "--perf-predicate-binding-probability", description = "Probability of applying data-aware predicate binding to each PERFORMANCE_HINT base query (0.0 to 1.0)", validateWith = CounterfactualHintProbabilityValidator.class)
    public double performancePredicateBindingProbability = 0.5;

    @Parameter(names = "--perf-query-rewrite-provider", description = "PERFORMANCE_HINT query rewrite provider: none or wetune-static", validateWith = MySQLPerformanceQueryRewriteProviderValidator.class)
    public String performanceQueryRewriteProvider = "none";

    @Parameter(names = "--perf-wetune-static-worker", description = "Path to the optional wetune-static-worker executable")
    public String performanceWetuneStaticWorker = "";

    @Parameter(names = "--perf-wetune-static-timeout-ms", description = "Maximum time in milliseconds for a WeTune static rewrite request", validateWith = PositiveIntegerValidator.class)
    public int performanceWetuneStaticTimeoutMs = 100;

    @Parameter(names = "--perf-taxonomy-enable", arity = 1, description = "Enable cost-runtime ranking taxonomy classification for PERFORMANCE_HINT")
    public boolean performanceTaxonomyEnable = true;

    @Parameter(names = "--perf-taxonomy-min-runtime-median-ms", description = "Below this median runtime, the nine-cell classifier treats the runtime difference as similar")
    public double performanceTaxonomyMinRuntimeMedianMs = 0.2;

    @Parameter(names = "--perf-taxonomy-min-runtime-abs-gap-ms", description = "Below this absolute median-runtime gap, the nine-cell classifier treats the runtime difference as similar")
    public double performanceTaxonomyMinRuntimeAbsGapMs = 0.5;

    @Parameter(names = "--perf-taxonomy-runtime-ratio-lower-bound", description = "Lower bound of default/counterfactual runtime ratio for the similar-runtime band")
    public double performanceTaxonomyRuntimeRatioLowerBound = 0.9;

    @Parameter(names = "--perf-taxonomy-runtime-ratio-upper-bound", description = "Upper bound of default/counterfactual runtime ratio for the similar-runtime band")
    public double performanceTaxonomyRuntimeRatioUpperBound = 1.3;

    @Parameter(names = "--perf-report-harmful-runtime-ratio", description = "Minimum default/counterfactual runtime ratio required to persist A, D, or G")
    public double performanceReportHarmfulRuntimeRatio = 5.0;

    @Parameter(names = "--perf-report-cost-anomaly-ratio", description = "Minimum default/counterfactual cost ratio required to persist B or C")
    public double performanceReportCostAnomalyRatio = 5.0;

    @Parameter(names = "--perf-taxonomy-report-cells", description = "Comma-separated nine-cell categories eligible for case artifacts; report-ratio gates are applied separately")
    public String performanceTaxonomyReportCells = "A,B,C,D,G";

    @Parameter(names = "--perf-taxonomy-cost-ratio-lower-bound", description = "Lower bound of default/hinted cost ratio for the similar-cost band")
    public double performanceTaxonomyCostRatioLowerBound = 0.9;

    @Parameter(names = "--perf-taxonomy-cost-ratio-upper-bound", description = "Upper bound of default/hinted cost ratio for the similar-cost band")
    public double performanceTaxonomyCostRatioUpperBound = 1.1;

    public enum MySQLOracleFactory implements OracleFactory<MySQLGlobalState> {

        TLP_WHERE {

            @Override
            public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws SQLException {
                return new MySQLTLPWhereOracle(globalState);
            }

        },
        PQS {

            @Override
            public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws SQLException {
                return new MySQLPivotedQuerySynthesisOracle(globalState);
            }

            @Override
            public boolean requiresAllTablesToContainRows() {
                return true;
            }

        },
        CERT {
            @Override
            public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws SQLException {
                return new MySQLCERTOracle(globalState);
            }

            @Override
            public boolean requiresAllTablesToContainRows() {
                return true;
            }
        },
        FUZZER {
            @Override
            public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws Exception {
                return new MySQLFuzzer(globalState);
            }

        },
        DQP {
            @Override
            public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws SQLException {
                return new MySQLDQPOracle(globalState);
            }
        },
        PERFORMANCE_HINT {
            @Override
            public TestOracle<MySQLGlobalState> create(MySQLGlobalState globalState) throws SQLException {
                return new MySQLPerformanceHintOracle(globalState);
            }

            @Override
            public boolean requiresAllTablesToContainRows() {
                return true;
            }
        };
    }

    @Override
    public List<MySQLOracleFactory> getTestOracleFactory() {
        return oracles;
    }

}
