package sqlancer.cockroachdb.oracle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.cockroachdb.CockroachDBOptions;
import sqlancer.cockroachdb.CockroachDBProvider.CockroachDBGlobalState;

final class CockroachDBPerformanceHintFeedbackManager {

    private static final Map<Path, CockroachDBPerformanceHintFeedbackManager> INSTANCES = new ConcurrentHashMap<>();
    private static final String STATE_FILE_NAME = "perfhint_feedback_state.tsv";
    private static final String EVENTS_FILE_NAME = "perfhint_feedback_events.jsonl";
    private static final String SUMMARY_FILE_NAME = "perfhint_feedback_summary.json";
    private static final int SKELETON_ROOT_CAUSE_HISTORY = 12;
    private static final int SKELETON_NEW_ROOT_CAUSE_WINDOW = 5;

    private final Path stateDir;
    private final Path stateFile;
    private final Path eventsFile;
    private final Path summaryFile;
    private final Map<String, Long> skeletonCounts = new LinkedHashMap<>();
    private final Map<String, SkeletonStats> skeletonStats = new LinkedHashMap<>();
    private final Map<String, Long> familyCounts = new LinkedHashMap<>();
    private final Map<String, SignatureStats> signatureCounts = new LinkedHashMap<>();
    private final Map<String, Long> rootCauseCounts = new LinkedHashMap<>();
    private final Map<String, RootCauseStats> rootCauseStats = new LinkedHashMap<>();
    private long totalWitnessesSeen;
    private long totalWitnessesWritten;
    private long totalWitnessesReserved;
    private long totalWitnessesWriteFailed;
    private long totalWitnessesSuppressed;
    private long totalRootCauseSuppressions;
    private long totalSkeletonSkips;
    private final ScheduledExecutorService checkpointWriter;
    private boolean checkpointDirty;

    private CockroachDBPerformanceHintFeedbackManager(Path stateDir) {
        this.stateDir = stateDir;
        this.stateFile = stateDir.resolve(STATE_FILE_NAME);
        this.eventsFile = stateDir.resolve(EVENTS_FILE_NAME);
        this.summaryFile = stateDir.resolve(SUMMARY_FILE_NAME);
        loadState();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "cockroachdb-perfhint-feedback-checkpoint");
            thread.setDaemon(true);
            return thread;
        };
        checkpointWriter = Executors.newSingleThreadScheduledExecutor(threadFactory);
        checkpointWriter.scheduleAtFixedRate(this::checkpointSafely, 2L, 2L, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "cockroachdb-perfhint-feedback-shutdown"));
    }

    static CockroachDBPerformanceHintFeedbackManager getInstance(CockroachDBGlobalState state) {
        Path configuredPath = resolveStateDirectory(state.getDbmsSpecificOptions());
        return INSTANCES.computeIfAbsent(configuredPath.toAbsolutePath().normalize(),
                CockroachDBPerformanceHintFeedbackManager::new);
    }

    static CockroachDBPerformanceHintFeedbackManager forStateDirectory(Path stateDir) {
        return INSTANCES.computeIfAbsent(stateDir.toAbsolutePath().normalize(),
                CockroachDBPerformanceHintFeedbackManager::new);
    }

    static Path resolveStateDirectory(CockroachDBOptions options) {
        if (options.performanceFeedbackStatePath != null && !options.performanceFeedbackStatePath.isBlank()) {
            return Path.of(options.performanceFeedbackStatePath);
        }
        return CockroachDBPerformanceHintOracleUtils.resolveReportRoot().resolve("_feedback");
    }

    synchronized SkeletonDecision evaluateSkeleton(String skeletonKey, Randomly randomly, CockroachDBOptions options)
            throws IOException {
        long currentCount = skeletonCounts.getOrDefault(skeletonKey, 0L);
        if (!options.enablePerformanceFeedbackDedup || options.performanceFeedbackSkeletonThreshold <= 0
                || currentCount < options.performanceFeedbackSkeletonThreshold) {
            return new SkeletonDecision(false, 0.0, currentCount);
        }
        double skipProbability = computeSkipProbability(currentCount, options.performanceFeedbackSkeletonThreshold);
        if (options.enablePerformanceFeedbackRootCauseDedup) {
            SkeletonStats stats = skeletonStats.get(skeletonKey);
            if (stats != null) {
                DominantRootCause dominant = stats.computeDominantRootCause();
                if (dominant != null && dominant.share >= options.performanceFeedbackRootCauseSkeletonShareThreshold
                        && isSaturatedRootCause(dominant.rootCauseKey, options)) {
                    skipProbability *= options.performanceFeedbackRootCauseSkipMultiplier;
                }
                if (stats.recentlySawNewRootCause()) {
                    skipProbability *= options.performanceFeedbackRootCauseNewnessBoost;
                }
            }
        }
        skipProbability = Math.min(0.98, Math.max(0.0, skipProbability));
        boolean skip = randomly.getDouble() < skipProbability;
        if (skip) {
            totalSkeletonSkips++;
            appendEvent("skeleton_skip", skeletonKey, null, null, null, currentCount + 1L, 0L, 0.0, 0.0,
                    Map.of("skipProbability", formatDouble(skipProbability)));
            markCheckpointDirty();
        }
        return new SkeletonDecision(skip, skipProbability, currentCount);
    }

    synchronized NoveltySnapshot estimateNoveltySnapshot(
            CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness, CockroachDBOptions options) {
        migrateLegacyHintIdentity(witness);
        if (!options.enablePerformanceFeedbackDedup) {
            return new NoveltySnapshot(1.0, 1.0, 1.0, 1.0);
        }
        long rootCauseCount = rootCauseCounts.getOrDefault(scopedRootCauseKey(witness), 0L);
        long familyCount = familyCounts.getOrDefault(scopedFamilyKey(witness), 0L);
        SignatureStats signatureStats = signatureCounts.get(scopedPlanSignatureKey(witness));
        long signatureCount = signatureStats == null ? 0L : signatureStats.totalObservations;
        double rootCauseNovelty = 1.0 / (1.0 + rootCauseCount);
        double familyNovelty = 1.0 / (1.0 + familyCount);
        double signatureNovelty = 1.0 / (1.0 + signatureCount);
        double overall = (0.55 * rootCauseNovelty) + (0.25 * familyNovelty) + (0.20 * signatureNovelty);
        return new NoveltySnapshot(overall, rootCauseNovelty, familyNovelty, signatureNovelty);
    }

    synchronized RootCauseScarcitySnapshot getRootCauseScarcitySnapshot(String rootCauseKey,
            CockroachDBOptions options) {
        RootCauseStats stats = rootCauseStats.get(rootCauseKey);
        if (stats == null || stats.totalObservations == 0L) {
            return new RootCauseScarcitySnapshot(true, false, 1.25);
        }
        if (!options.enablePerformanceFeedbackRootCauseDedup) {
            return new RootCauseScarcitySnapshot(false, false, 1.0);
        }
        boolean saturated = stats.writtenReports + stats.reservedReports >= Math.max(1,
                options.performanceFeedbackRootCauseHardCap);
        double boost = saturated ? 0.4 : 1.0;
        return new RootCauseScarcitySnapshot(false, saturated, boost);
    }

    synchronized WitnessDecision recordWitness(CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            CockroachDBOptions options) throws IOException {
        migrateLegacyHintIdentity(witness);
        String scopedFamilyKey = scopedFamilyKey(witness);
        String scopedPlanSignatureKey = scopedPlanSignatureKey(witness);
        String scopedRootCauseKey = scopedRootCauseKey(witness);
        totalWitnessesSeen++;
        long skeletonCount = incrementCount(skeletonCounts, witness.getSkeletonKey());
        long familyCount = incrementCount(familyCounts, scopedFamilyKey);
        SignatureStats signatureStats = signatureCounts.computeIfAbsent(scopedPlanSignatureKey,
                key -> new SignatureStats());
        signatureStats.totalObservations++;
        signatureStats.bestRuntimeSpeedup = Math.max(signatureStats.bestRuntimeSpeedup, witness.getRuntimeSpeedup());
        signatureStats.bestCostRatio = Math.max(signatureStats.bestCostRatio, witness.getCostRatio());

        RootCauseStats rootStats = rootCauseStats.computeIfAbsent(scopedRootCauseKey, key -> new RootCauseStats());
        RootCauseStats previousRootStats = rootStats.copy();
        long priorRootCauseObservations = rootStats.totalObservations;
        long priorRootCauseWrittenReports = rootStats.writtenReports + rootStats.reservedReports;
        rootStats.totalObservations++;
        long rootCauseCount = incrementCount(rootCauseCounts, scopedRootCauseKey);

        updateSkeletonStats(witness.getSkeletonKey(), scopedRootCauseKey, priorRootCauseObservations == 0L);

        RootCauseSelection rootSelection = chooseRootCauseSelection(witness, previousRootStats,
                priorRootCauseObservations, priorRootCauseWrittenReports, options);
        rootStats.bestRuntimeSpeedup = Math.max(rootStats.bestRuntimeSpeedup, witness.getRuntimeSpeedup());
        rootStats.bestAbsoluteRuntimeGapMs = Math.max(rootStats.bestAbsoluteRuntimeGapMs,
                computeAbsoluteRuntimeGapMs(witness));
        rootStats.bestCostRatio = Math.max(rootStats.bestCostRatio, witness.getCostRatio());
        boolean signatureCapReached = signatureStats.writtenReports + signatureStats.reservedReports >= Math.max(1,
                options.performanceFeedbackSignatureHardCap);
        boolean shouldWrite = rootSelection.shouldWrite && !signatureCapReached;
        ReportReservation reservation = null;
        if (shouldWrite) {
            signatureStats.reservedReports++;
            rootStats.reservedReports++;
            totalWitnessesReserved++;
            reservation = new ReportReservation(witness.getSkeletonKey(), scopedFamilyKey, scopedPlanSignatureKey,
                    scopedRootCauseKey, witness.getRuntimeSpeedup(), witness.getCostRatio(),
                    computeAbsoluteRuntimeGapMs(witness), computeSimplicityScore(witness),
                    rootSelection.joinRepresentativeRoles(), rootSelection.selectionReason.name(),
                    witness.getNineCellCategory().getCode());
        } else {
            totalWitnessesSuppressed++;
            if (rootSelection.selectionReason == SelectionReason.SUPPRESSED_ROOT_CAUSE_DUPLICATE
                    || (rootSelection.selectionReason == SelectionReason.BETTER_REPRESENTATIVE
                            && signatureCapReached)) {
                totalRootCauseSuppressions++;
            }
        }

        markCheckpointDirty();

        String selectionReason = shouldWrite ? rootSelection.selectionReason.name()
                : SelectionReason.SUPPRESSED_ROOT_CAUSE_DUPLICATE.name();
        appendEvent("witness_recorded", witness.getSkeletonKey(), scopedFamilyKey, scopedPlanSignatureKey,
                scopedRootCauseKey, signatureStats.totalObservations, signatureStats.writtenReports,
                witness.getRuntimeSpeedup(), witness.getCostRatio(),
                Map.of("shouldWrite", Boolean.toString(shouldWrite), "selectionReason", selectionReason,
                        "skeletonCount", Long.toString(skeletonCount), "familyCount", Long.toString(familyCount),
                        "rootCauseCount", Long.toString(rootCauseCount), "rootCauseWrittenReports",
                        Long.toString(rootStats.writtenReports), "rootCauseReservedReports",
                        Long.toString(rootStats.reservedReports), "signatureCapReached",
                        Boolean.toString(signatureCapReached), "nineCellCategory",
                        witness.getNineCellCategory().getCode()));
        appendEvent("root_cause_recorded", witness.getSkeletonKey(), scopedFamilyKey, scopedPlanSignatureKey,
                scopedRootCauseKey, rootStats.totalObservations, rootStats.writtenReports, witness.getRuntimeSpeedup(),
                witness.getCostRatio(), Map.of("selectionReason", selectionReason, "nineCellCategory",
                        witness.getNineCellCategory().getCode()));
        if (!shouldWrite) {
            appendEvent("root_cause_suppressed", witness.getSkeletonKey(), scopedFamilyKey, scopedPlanSignatureKey,
                    scopedRootCauseKey, rootStats.totalObservations, rootStats.writtenReports,
                    witness.getRuntimeSpeedup(), witness.getCostRatio(), Map.of("selectionReason", selectionReason,
                            "nineCellCategory", witness.getNineCellCategory().getCode()));
        }

        return new WitnessDecision(shouldWrite, signatureStats.totalObservations,
                signatureStats.writtenReports + signatureStats.reservedReports, skeletonCount, familyCount,
                rootStats.totalObservations, rootStats.writtenReports + rootStats.reservedReports,
                totalWitnessesSuppressed, totalRootCauseSuppressions, selectionReason,
                rootSelection.joinRepresentativeRoles(), reservation);
    }

    synchronized void completeReservation(ReportReservation reservation, Path reportDir) throws IOException {
        SignatureStats signatureStats = signatureCounts.get(reservation.getPlanSignatureKey());
        if (signatureStats != null) {
            releaseReservation(signatureStats);
            signatureStats.writtenReports++;
            signatureStats.representativeCases.add(reportDir.getFileName().toString());
            while (signatureStats.representativeCases.size() > 3) {
                signatureStats.representativeCases.remove(0);
            }
        }
        RootCauseStats rootStats = rootCauseStats.get(reservation.getRootCauseKey());
        if (rootStats != null) {
            releaseReservation(rootStats);
            rootStats.writtenReports++;
            assignRepresentativeRoles(rootStats, reservation, reportDir.getFileName().toString());
        }
        if (totalWitnessesReserved > 0L) {
            totalWitnessesReserved--;
        }
        totalWitnessesWritten++;
        markCheckpointDirty();
        appendEvent("report_written", reservation.getSkeletonKey(), reservation.getFamilyKey(),
                reservation.getPlanSignatureKey(), reservation.getRootCauseKey(),
                signatureStats == null ? 0L : signatureStats.totalObservations,
                signatureStats == null ? 0L : signatureStats.writtenReports, reservation.getRuntimeSpeedup(),
                reservation.getCostRatio(),
                Map.of("reportDir", reportDir.getFileName().toString(), "selectionReason",
                        reservation.getSelectionReason(), "representativeRole", reservation.getRepresentativeRoles(),
                        "nineCellCategory", reservation.getNineCellCategory()));
    }

    synchronized void failReservation(ReportReservation reservation, Exception failure) throws IOException {
        SignatureStats signatureStats = signatureCounts.get(reservation.getPlanSignatureKey());
        if (signatureStats != null) {
            releaseReservation(signatureStats);
        }
        RootCauseStats rootStats = rootCauseStats.get(reservation.getRootCauseKey());
        if (rootStats != null) {
            releaseReservation(rootStats);
        }
        if (totalWitnessesReserved > 0L) {
            totalWitnessesReserved--;
        }
        totalWitnessesWriteFailed++;
        markCheckpointDirty();
        appendEvent("report_write_failed", reservation.getSkeletonKey(), reservation.getFamilyKey(),
                reservation.getPlanSignatureKey(), reservation.getRootCauseKey(),
                signatureStats == null ? 0L : signatureStats.totalObservations,
                signatureStats == null ? 0L : signatureStats.writtenReports, reservation.getRuntimeSpeedup(),
                reservation.getCostRatio(),
                Map.of("selectionReason", reservation.getSelectionReason(), "nineCellCategory",
                        reservation.getNineCellCategory(), "failure",
                        Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName())));
    }

    private RootCauseSelection chooseRootCauseSelection(
            CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness, RootCauseStats comparisonStats,
            long priorRootCauseObservations, long priorRootCauseWrittenReports, CockroachDBOptions options) {
        if (!options.enablePerformanceFeedbackDedup || !options.enablePerformanceFeedbackRootCauseDedup) {
            return new RootCauseSelection(true, SelectionReason.UNDER_ROOT_CAUSE_CAP,
                    computeRepresentativeRoles(witness, comparisonStats, true));
        }
        if (priorRootCauseObservations == 0L) {
            return new RootCauseSelection(true, SelectionReason.NEW_ROOT_CAUSE,
                    computeRepresentativeRoles(witness, comparisonStats, true));
        }
        if (priorRootCauseWrittenReports < Math.max(1, options.performanceFeedbackRootCauseHardCap)) {
            return new RootCauseSelection(true, SelectionReason.UNDER_ROOT_CAUSE_CAP,
                    computeRepresentativeRoles(witness, comparisonStats, false));
        }
        if (!options.enablePerformanceFeedbackRepresentativeReplacement) {
            return new RootCauseSelection(false, SelectionReason.SUPPRESSED_ROOT_CAUSE_DUPLICATE, List.of());
        }
        List<String> replacementRoles = computeRepresentativeRoles(witness, comparisonStats, false);
        if (!replacementRoles.isEmpty()) {
            return new RootCauseSelection(true, SelectionReason.BETTER_REPRESENTATIVE, replacementRoles);
        }
        return new RootCauseSelection(false, SelectionReason.SUPPRESSED_ROOT_CAUSE_DUPLICATE, List.of());
    }

    private List<String> computeRepresentativeRoles(
            CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness, RootCauseStats comparisonStats,
            boolean treatAsEmpty) {
        List<String> roles = new ArrayList<>();
        double runtimeSpeedup = witness.getRuntimeSpeedup();
        double runtimeGapMs = computeAbsoluteRuntimeGapMs(witness);
        double simplicityScore = computeSimplicityScore(witness);
        if (treatAsEmpty || runtimeSpeedup > comparisonStats.bestRuntimeSpeedup) {
            roles.add("best_runtime_speedup");
        }
        if (treatAsEmpty || runtimeGapMs > comparisonStats.bestAbsoluteRuntimeGapMs) {
            roles.add("best_absolute_runtime_gap");
        }
        if (treatAsEmpty || simplicityScore < comparisonStats.simplestComplexityScore) {
            roles.add("simplest_signature");
        }
        return roles;
    }

    private void assignRepresentativeRoles(RootCauseStats rootStats, ReportReservation reservation,
            String reportDirName) throws IOException {
        for (String role : splitRepresentativeRoles(reservation.getRepresentativeRoles())) {
            String previousCase = null;
            switch (role) {
            case "best_runtime_speedup":
                previousCase = rootStats.bestRuntimeSpeedupCase;
                rootStats.bestRuntimeSpeedupCase = reportDirName;
                rootStats.bestRuntimeSpeedup = Math.max(rootStats.bestRuntimeSpeedup, reservation.getRuntimeSpeedup());
                break;
            case "best_absolute_runtime_gap":
                previousCase = rootStats.bestAbsoluteRuntimeGapCase;
                rootStats.bestAbsoluteRuntimeGapCase = reportDirName;
                rootStats.bestAbsoluteRuntimeGapMs = Math.max(rootStats.bestAbsoluteRuntimeGapMs,
                        reservation.getAbsoluteRuntimeGapMs());
                break;
            case "simplest_signature":
                previousCase = rootStats.simplestSignatureCase;
                rootStats.simplestSignatureCase = reportDirName;
                rootStats.simplestComplexityScore = reservation.getSimplicityScore();
                break;
            default:
                break;
            }
            if (previousCase != null && !previousCase.equals(reportDirName)) {
                appendEvent("root_cause_representative_replaced", reservation.getSkeletonKey(),
                        reservation.getFamilyKey(), reservation.getPlanSignatureKey(), reservation.getRootCauseKey(),
                        rootStats.totalObservations, rootStats.writtenReports, reservation.getRuntimeSpeedup(),
                        reservation.getCostRatio(), Map.of("representativeRole", role, "previousCase", previousCase,
                                "newCase", reportDirName, "nineCellCategory", reservation.getNineCellCategory()));
            }
        }
    }

    private static double computeAbsoluteRuntimeGapMs(
            CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        if (witness.getDefaultRuntime() == null || witness.getHintedRuntime() == null) {
            return 0.0;
        }
        return Math.abs(witness.getDefaultRuntime().getMedianMs() - witness.getHintedRuntime().getMedianMs());
    }

    private static double computeSimplicityScore(CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        int tableCount = extractTableCount(witness.getSkeletonKey());
        int operationCount = witness.getDefaultPlan().getOperations().size()
                + witness.getHintedPlan().getOperations().size();
        double score = tableCount * 100.0 + operationCount * 10.0;
        if (witness.getSkeletonKey() != null && witness.getSkeletonKey().contains("WHERE")) {
            score += 4.0;
        }
        if (witness.getDefaultPlan().isUsingFilesort()) {
            score += 2.0;
        }
        if (witness.getDefaultPlan().isUsingTemporaryTable()) {
            score += 2.0;
        }
        if (witness.getDefaultPlan().isUsingDeduplication()) {
            score += 1.0;
        }
        return score;
    }

    private static int extractTableCount(String skeletonKey) {
        if (skeletonKey == null) {
            return 0;
        }
        if (skeletonKey.startsWith("tables=")) {
            int delimiter = skeletonKey.indexOf('|');
            String count = delimiter == -1 ? skeletonKey.substring("tables=".length())
                    : skeletonKey.substring("tables=".length(), delimiter);
            try {
                return Integer.parseInt(count);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static double computeSkipProbability(long currentCount, int threshold) {
        return Math.min(0.95, Math.max(0.0, 1.0 - ((double) threshold / (double) (currentCount + 1L))));
    }

    private static List<String> splitRepresentativeRoles(String representativeRank) {
        if (representativeRank == null || representativeRank.isBlank()) {
            return List.of();
        }
        return List.of(representativeRank.split(","));
    }

    private boolean isSaturatedRootCause(String rootCauseKey, CockroachDBOptions options) {
        RootCauseStats stats = rootCauseStats.get(rootCauseKey);
        return stats != null && stats.writtenReports + stats.reservedReports >= Math.max(1,
                options.performanceFeedbackRootCauseHardCap);
    }

    private static void releaseReservation(SignatureStats stats) {
        if (stats.reservedReports > 0L) {
            stats.reservedReports--;
        }
    }

    private static void releaseReservation(RootCauseStats stats) {
        if (stats.reservedReports > 0L) {
            stats.reservedReports--;
        }
    }

    private void updateSkeletonStats(String skeletonKey, String rootCauseKey, boolean isNewRootCauseForGlobalStats) {
        SkeletonStats stats = skeletonStats.computeIfAbsent(skeletonKey, key -> new SkeletonStats());
        stats.totalObservations++;
        stats.rootCauseCounts.merge(rootCauseKey, 1L, Long::sum);
        stats.recentRootCauses.add(0, rootCauseKey);
        if (stats.recentRootCauses.size() > SKELETON_ROOT_CAUSE_HISTORY) {
            stats.recentRootCauses.remove(stats.recentRootCauses.size() - 1);
        }
        if (isNewRootCauseForGlobalStats) {
            stats.recentlyNewRootCauseObservations.add(Instant.now().getEpochSecond());
            while (stats.recentlyNewRootCauseObservations.size() > SKELETON_NEW_ROOT_CAUSE_WINDOW) {
                stats.recentlyNewRootCauseObservations.remove(0);
            }
        }
    }

    private static long incrementCount(Map<String, Long> counts, String key) {
        long next = counts.getOrDefault(key, 0L) + 1L;
        counts.put(key, next);
        return next;
    }

    private String scopedFamilyKey(CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        return scopeKey(witness.getFamilyKey(), witness);
    }

    private String scopedPlanSignatureKey(CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        return scopeKey(witness.getPlanSignatureKey(), witness);
    }

    private String scopedRootCauseKey(CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        return scopeKey(witness.getRootCauseSignatureKey(), witness);
    }

    private static String scopeKey(String rawKey,
            CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        if (rawKey == null || rawKey.isBlank()) {
            return rawKey;
        }
        if (witness.getNineCellCategory() == null) {
            return rawKey;
        }
        return rawKey + "|nineCell=" + witness.getNineCellCategory().getCode();
    }

    /**
     * Historical feedback files contain HINT-only keys. When a matching HINT is encountered, merge its legacy aliases
     * into the generic identity and remove the aliases. Subsequent persistence therefore writes only the new
     * kind/family/mutation-id representation without losing prior scarcity and duplicate-suppression history.
     */
    private void migrateLegacyHintIdentity(CockroachDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        if (!witness.isHintCounterfactual()) {
            return;
        }
        String legacyFamily = CockroachDBPerformanceHintOracleUtils.buildLegacyHintFamilyKey(witness);
        String legacySignature = CockroachDBPerformanceHintOracleUtils.buildLegacyHintPlanSignatureKey(witness);
        String legacyRootCause = CockroachDBPerformanceHintOracleUtils.buildLegacyHintRootCauseKey(witness);
        if (legacyFamily == null || legacySignature == null || legacyRootCause == null) {
            return;
        }

        String newFamily = scopedFamilyKey(witness);
        String newSignature = scopedPlanSignatureKey(witness);
        mergeLongEntry(familyCounts, legacyFamily, newFamily);
        mergeLongEntry(familyCounts, scopeKey(legacyFamily, witness), newFamily);
        mergeSignatureEntry(legacySignature, newSignature);
        mergeSignatureEntry(scopeKey(legacySignature, witness), newSignature);
        String newRootCause = scopedRootCauseKey(witness);
        mergeRootCauseAlias(legacyRootCause, newRootCause);
        mergeRootCauseAlias(scopeKey(legacyRootCause, witness), newRootCause);
    }

    private void mergeRootCauseAlias(String oldRootCause, String newRootCause) {
        mergeRootCauseEntry(oldRootCause, newRootCause);
        mergeLongEntry(rootCauseCounts, oldRootCause, newRootCause);
        for (SkeletonStats stats : skeletonStats.values()) {
            mergeLongEntry(stats.rootCauseCounts, oldRootCause, newRootCause);
            for (int i = 0; i < stats.recentRootCauses.size(); i++) {
                if (oldRootCause.equals(stats.recentRootCauses.get(i))) {
                    stats.recentRootCauses.set(i, newRootCause);
                }
            }
        }
    }

    private static void mergeLongEntry(Map<String, Long> entries, String oldKey, String newKey) {
        if (oldKey == null || newKey == null || oldKey.equals(newKey)) {
            return;
        }
        Long oldValue = entries.remove(oldKey);
        if (oldValue != null) {
            entries.merge(newKey, oldValue, Long::sum);
        }
    }

    private void mergeSignatureEntry(String oldKey, String newKey) {
        if (oldKey == null || newKey == null || oldKey.equals(newKey)) {
            return;
        }
        SignatureStats oldStats = signatureCounts.remove(oldKey);
        if (oldStats == null) {
            return;
        }
        SignatureStats newStats = signatureCounts.computeIfAbsent(newKey, key -> new SignatureStats());
        newStats.totalObservations += oldStats.totalObservations;
        newStats.writtenReports += oldStats.writtenReports;
        newStats.reservedReports += oldStats.reservedReports;
        newStats.bestRuntimeSpeedup = Math.max(newStats.bestRuntimeSpeedup, oldStats.bestRuntimeSpeedup);
        newStats.bestCostRatio = Math.max(newStats.bestCostRatio, oldStats.bestCostRatio);
        for (String representativeCase : oldStats.representativeCases) {
            if (!newStats.representativeCases.contains(representativeCase)) {
                newStats.representativeCases.add(representativeCase);
            }
        }
        while (newStats.representativeCases.size() > 3) {
            newStats.representativeCases.remove(0);
        }
    }

    private void mergeRootCauseEntry(String oldKey, String newKey) {
        if (oldKey == null || newKey == null || oldKey.equals(newKey)) {
            return;
        }
        RootCauseStats oldStats = rootCauseStats.remove(oldKey);
        if (oldStats == null) {
            return;
        }
        RootCauseStats newStats = rootCauseStats.computeIfAbsent(newKey, key -> new RootCauseStats());
        newStats.totalObservations += oldStats.totalObservations;
        newStats.writtenReports += oldStats.writtenReports;
        newStats.reservedReports += oldStats.reservedReports;
        newStats.bestRuntimeSpeedup = Math.max(newStats.bestRuntimeSpeedup, oldStats.bestRuntimeSpeedup);
        newStats.bestAbsoluteRuntimeGapMs = Math.max(newStats.bestAbsoluteRuntimeGapMs,
                oldStats.bestAbsoluteRuntimeGapMs);
        newStats.bestCostRatio = Math.max(newStats.bestCostRatio, oldStats.bestCostRatio);
        if (newStats.bestRuntimeSpeedupCase == null) {
            newStats.bestRuntimeSpeedupCase = oldStats.bestRuntimeSpeedupCase;
        }
        if (newStats.bestAbsoluteRuntimeGapCase == null) {
            newStats.bestAbsoluteRuntimeGapCase = oldStats.bestAbsoluteRuntimeGapCase;
        }
        if (newStats.simplestSignatureCase == null) {
            newStats.simplestSignatureCase = oldStats.simplestSignatureCase;
            newStats.simplestComplexityScore = oldStats.simplestComplexityScore;
        } else {
            newStats.simplestComplexityScore = Math.min(newStats.simplestComplexityScore,
                    oldStats.simplestComplexityScore);
        }
    }

    private void loadState() {
        try {
            Files.createDirectories(stateDir);
            if (!Files.exists(stateFile)) {
                return;
            }
            for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 3) {
                    continue;
                }
                try {
                    switch (parts[0]) {
                    case "meta":
                        loadMeta(parts);
                        break;
                    case "skeleton":
                        skeletonCounts.put(decode(parts[1]), Long.parseLong(parts[2]));
                        break;
                    case "family":
                        familyCounts.put(decode(parts[1]), Long.parseLong(parts[2]));
                        break;
                    case "signature":
                        loadSignature(parts);
                        break;
                    case "root":
                        loadRoot(parts);
                        break;
                    case "skeleton_stats":
                        loadSkeletonStats(parts);
                        break;
                    default:
                        break;
                    }
                } catch (RuntimeException ignored) {
                    // Ignore malformed historical entries and continue loading the remaining state.
                }
            }
        } catch (IOException ignored) {
            // Ignore malformed historical state and rebuild from new observations.
        }
    }

    private void loadMeta(String[] parts) {
        if (parts.length < 3) {
            return;
        }
        switch (parts[1]) {
        case "totalWitnessesSeen":
            totalWitnessesSeen = Long.parseLong(parts[2]);
            break;
        case "totalWitnessesWritten":
            totalWitnessesWritten = Long.parseLong(parts[2]);
            break;
        case "totalWitnessesWriteFailed":
            totalWitnessesWriteFailed = Long.parseLong(parts[2]);
            break;
        case "totalWitnessesSuppressed":
            totalWitnessesSuppressed = Long.parseLong(parts[2]);
            break;
        case "totalRootCauseSuppressions":
            totalRootCauseSuppressions = Long.parseLong(parts[2]);
            break;
        case "totalSkeletonSkips":
            totalSkeletonSkips = Long.parseLong(parts[2]);
            break;
        default:
            break;
        }
    }

    private void loadSignature(String[] parts) {
        if (parts.length < 5) {
            return;
        }
        SignatureStats stats = new SignatureStats();
        stats.totalObservations = Long.parseLong(parts[2]);
        stats.writtenReports = Long.parseLong(parts[3]);
        stats.bestRuntimeSpeedup = Double.parseDouble(parts[4]);
        stats.bestCostRatio = parts.length > 5 ? Double.parseDouble(parts[5]) : 0.0;
        if (parts.length > 6 && !parts[6].isBlank()) {
            stats.representativeCases.addAll(splitDecodedList(parts[6]));
        }
        signatureCounts.put(decode(parts[1]), stats);
    }

    private void loadRoot(String[] parts) {
        if (parts.length < 7) {
            return;
        }
        RootCauseStats stats = new RootCauseStats();
        stats.totalObservations = Long.parseLong(parts[2]);
        stats.writtenReports = Long.parseLong(parts[3]);
        stats.bestRuntimeSpeedup = Double.parseDouble(parts[4]);
        stats.bestAbsoluteRuntimeGapMs = Double.parseDouble(parts[5]);
        stats.bestCostRatio = Double.parseDouble(parts[6]);
        if (parts.length > 7) {
            stats.bestRuntimeSpeedupCase = decodeNullable(parts[7]);
        }
        if (parts.length > 8) {
            stats.bestAbsoluteRuntimeGapCase = decodeNullable(parts[8]);
        }
        if (parts.length > 9) {
            stats.simplestSignatureCase = decodeNullable(parts[9]);
        }
        if (parts.length > 10 && !parts[10].isBlank()) {
            stats.simplestComplexityScore = Double.parseDouble(parts[10]);
        }
        rootCauseStats.put(decode(parts[1]), stats);
        rootCauseCounts.put(decode(parts[1]), stats.totalObservations);
    }

    private void loadSkeletonStats(String[] parts) {
        if (parts.length < 5) {
            return;
        }
        SkeletonStats stats = new SkeletonStats();
        stats.totalObservations = Long.parseLong(parts[2]);
        stats.rootCauseCounts.putAll(parseRootCauseCounts(parts[3]));
        stats.recentRootCauses.addAll(splitDecodedList(parts[4]));
        if (parts.length > 5) {
            for (String ts : splitDecodedList(parts[5])) {
                if (!ts.isBlank()) {
                    stats.recentlyNewRootCauseObservations.add(Long.parseLong(ts));
                }
            }
        }
        skeletonStats.put(decode(parts[1]), stats);
    }

    private synchronized void markCheckpointDirty() {
        checkpointDirty = true;
    }

    private void checkpointSafely() {
        try {
            checkpointIfDirty();
        } catch (IOException ignored) {
            // In-memory dedup state remains authoritative for this process.
        }
    }

    private synchronized void checkpointIfDirty() throws IOException {
        if (!checkpointDirty) {
            return;
        }
        persistState();
        checkpointDirty = false;
    }

    void checkpointAfterReportDrain() throws IOException {
        checkpointIfDirty();
    }

    private void close() {
        checkpointWriter.shutdownNow();
        checkpointSafely();
    }

    private void persistState() throws IOException {
        Files.createDirectories(stateDir);
        List<String> lines = new ArrayList<>();
        lines.add(metaLine("totalWitnessesSeen", totalWitnessesSeen));
        lines.add(metaLine("totalWitnessesWritten", totalWitnessesWritten));
        lines.add(metaLine("totalWitnessesWriteFailed", totalWitnessesWriteFailed));
        lines.add(metaLine("totalWitnessesSuppressed", totalWitnessesSuppressed));
        lines.add(metaLine("totalRootCauseSuppressions", totalRootCauseSuppressions));
        lines.add(metaLine("totalSkeletonSkips", totalSkeletonSkips));
        skeletonCounts.forEach((key, value) -> lines.add("skeleton\t" + encode(key) + "\t" + value));
        familyCounts.forEach((key, value) -> lines.add("family\t" + encode(key) + "\t" + value));
        signatureCounts.forEach((key,
                stats) -> lines.add(String.join("\t", "signature", encode(key), Long.toString(stats.totalObservations),
                        Long.toString(stats.writtenReports), formatDouble(stats.bestRuntimeSpeedup),
                        formatDouble(stats.bestCostRatio), encode(joinList(stats.representativeCases)))));
        rootCauseStats.forEach((key,
                stats) -> lines.add(String.join("\t", "root", encode(key), Long.toString(stats.totalObservations),
                        Long.toString(stats.writtenReports), formatDouble(stats.bestRuntimeSpeedup),
                        formatDouble(stats.bestAbsoluteRuntimeGapMs), formatDouble(stats.bestCostRatio),
                        encodeNullable(stats.bestRuntimeSpeedupCase), encodeNullable(stats.bestAbsoluteRuntimeGapCase),
                        encodeNullable(stats.simplestSignatureCase), formatDouble(stats.simplestComplexityScore))));
        skeletonStats.forEach((key,
                stats) -> lines.add(String.join("\t", "skeleton_stats", encode(key),
                        Long.toString(stats.totalObservations), encode(encodeRootCauseCounts(stats.rootCauseCounts)),
                        encode(joinList(stats.recentRootCauses)), encode(joinList(stats.recentlyNewRootCauseObservations
                                .stream().map(String::valueOf).collect(Collectors.toList()))))));

        Files.write(stateFile, lines, StandardCharsets.UTF_8);
        Files.writeString(summaryFile, buildSummaryJson(), StandardCharsets.UTF_8);
    }

    private void appendEvent(String type, String skeletonKey, String familyKey, String signatureKey,
            String rootCauseKey, long observationCount, long writtenReports, double runtimeSpeedup, double costRatio,
            Map<String, String> additionalFields) throws IOException {
        Files.createDirectories(stateDir);
        List<String> fields = new ArrayList<>();
        fields.add(jsonField("timestamp", Instant.now().toString()));
        fields.add(jsonField("type", type));
        if (skeletonKey != null) {
            fields.add(jsonField("skeletonKey", skeletonKey));
        }
        if (familyKey != null) {
            fields.add(jsonField("familyKey", familyKey));
        }
        if (signatureKey != null) {
            fields.add(jsonField("planSignatureKey", signatureKey));
        }
        if (rootCauseKey != null) {
            fields.add(jsonField("rootCauseKey", rootCauseKey));
        }
        fields.add(jsonField("observationCount", observationCount));
        fields.add(jsonField("writtenReports", writtenReports));
        fields.add(jsonField("runtimeSpeedup", runtimeSpeedup));
        fields.add(jsonField("costRatio", costRatio));
        additionalFields.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> fields.add(jsonField(entry.getKey(), entry.getValue())));
        Files.writeString(eventsFile, "{ " + String.join(", ", fields) + " }\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private String buildSummaryJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"totalWitnessesSeen\": ").append(totalWitnessesSeen).append(",\n");
        sb.append("  \"totalWitnessesWritten\": ").append(totalWitnessesWritten).append(",\n");
        sb.append("  \"totalWitnessesReserved\": ").append(totalWitnessesReserved).append(",\n");
        sb.append("  \"totalWitnessesWriteFailed\": ").append(totalWitnessesWriteFailed).append(",\n");
        sb.append("  \"totalWitnessesSuppressed\": ").append(totalWitnessesSuppressed).append(",\n");
        sb.append("  \"totalRootCauseSuppressions\": ").append(totalRootCauseSuppressions).append(",\n");
        sb.append("  \"totalSkeletonSkips\": ").append(totalSkeletonSkips).append(",\n");
        sb.append("  \"uniqueSkeletonCount\": ").append(skeletonCounts.size()).append(",\n");
        sb.append("  \"uniqueFamilyCount\": ").append(familyCounts.size()).append(",\n");
        sb.append("  \"uniquePlanSignatureCount\": ").append(signatureCounts.size()).append(",\n");
        sb.append("  \"uniqueRootCauseCount\": ").append(rootCauseStats.size()).append(",\n");
        sb.append("  \"updatedAt\": \"").append(escapeJson(Instant.now().toString())).append("\",\n");
        sb.append("  \"topFamilies\": [\n");
        appendTopFamilies(sb);
        sb.append("  ],\n");
        sb.append("  \"topPlanSignatures\": [\n");
        appendTopPlanSignatures(sb);
        sb.append("  ],\n");
        sb.append("  \"topRootCauses\": [\n");
        appendTopRootCauses(sb);
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private void appendTopFamilies(StringBuilder sb) {
        List<Map.Entry<String, Long>> topFamilies = familyCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long> comparingByValue(Comparator.reverseOrder())).limit(10)
                .collect(Collectors.toList());
        for (int i = 0; i < topFamilies.size(); i++) {
            Map.Entry<String, Long> entry = topFamilies.get(i);
            sb.append("    {\"familyKey\": \"").append(escapeJson(entry.getKey())).append("\", \"count\": ")
                    .append(entry.getValue()).append("}");
            if (i + 1 < topFamilies.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
    }

    private void appendTopPlanSignatures(StringBuilder sb) {
        List<Map.Entry<String, SignatureStats>> topSignatures = signatureCounts.entrySet().stream().sorted(
                (left, right) -> Long.compare(right.getValue().totalObservations, left.getValue().totalObservations))
                .limit(10).collect(Collectors.toList());
        for (int i = 0; i < topSignatures.size(); i++) {
            Map.Entry<String, SignatureStats> entry = topSignatures.get(i);
            SignatureStats stats = entry.getValue();
            sb.append("    {\"planSignatureKey\": \"").append(escapeJson(entry.getKey())).append("\", \"count\": ")
                    .append(stats.totalObservations).append(", \"writtenReports\": ").append(stats.writtenReports)
                    .append(", \"bestRuntimeSpeedup\": ").append(formatDouble(stats.bestRuntimeSpeedup)).append("}");
            if (i + 1 < topSignatures.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
    }

    private void appendTopRootCauses(StringBuilder sb) {
        List<Map.Entry<String, RootCauseStats>> topRootCauses = rootCauseStats.entrySet().stream().sorted(
                (left, right) -> Long.compare(right.getValue().totalObservations, left.getValue().totalObservations))
                .limit(10).collect(Collectors.toList());
        for (int i = 0; i < topRootCauses.size(); i++) {
            Map.Entry<String, RootCauseStats> entry = topRootCauses.get(i);
            RootCauseStats stats = entry.getValue();
            sb.append("    {\"rootCauseKey\": \"").append(escapeJson(entry.getKey())).append("\", \"count\": ")
                    .append(stats.totalObservations).append(", \"writtenReports\": ").append(stats.writtenReports)
                    .append(", \"representativeCases\": [");
            List<String> representatives = stats.currentRepresentativeCases();
            for (int j = 0; j < representatives.size(); j++) {
                sb.append("\"").append(escapeJson(representatives.get(j))).append("\"");
                if (j + 1 < representatives.size()) {
                    sb.append(", ");
                }
            }
            sb.append("]}");
            if (i + 1 < topRootCauses.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
    }

    private static String metaLine(String key, long value) {
        return "meta\t" + key + "\t" + value;
    }

    private static String encodeRootCauseCounts(Map<String, Long> counts) {
        return counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + ":" + entry.getValue()).collect(Collectors.joining(","));
    }

    static Map<String, Long> parseRootCauseCounts(String encoded) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String entry : splitDecodedList(encoded)) {
            int delimiter = entry.indexOf(':');
            if (delimiter > 0) {
                String encodedKey = entry.substring(0, delimiter);
                String countValue = entry.substring(delimiter + 1);
                counts.put(decode(encodedKey), Long.parseLong(countValue));
                continue;
            }

            // Backward compatibility: older state files used rawKey=count, but raw keys contain '='.
            int legacyDelimiter = entry.lastIndexOf('=');
            if (legacyDelimiter > 0 && legacyDelimiter + 1 < entry.length()) {
                String rawKey = entry.substring(0, legacyDelimiter);
                String countValue = entry.substring(legacyDelimiter + 1);
                counts.put(rawKey, Long.parseLong(countValue));
            }
        }
        return counts;
    }

    private static String joinList(List<String> values) {
        return String.join(",", values);
    }

    private static List<String> splitDecodedList(String encoded) {
        String decoded = decode(encoded);
        if (decoded.isBlank()) {
            return List.of();
        }
        String[] values = decoded.split(",");
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static String encodeNullable(String raw) {
        return raw == null ? "" : encode(raw);
    }

    private static String decodeNullable(String encoded) {
        return encoded == null || encoded.isBlank() ? null : decode(encoded);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.US, "%.12f", value);
    }

    private static String jsonField(String key, Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return "\"" + escapeJson(key) + "\": " + value;
        }
        return "\"" + escapeJson(key) + "\": \"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static final class SkeletonDecision {
        private final boolean shouldSkip;
        private final double skipProbability;
        private final long historicalCount;

        SkeletonDecision(boolean shouldSkip, double skipProbability, long historicalCount) {
            this.shouldSkip = shouldSkip;
            this.skipProbability = skipProbability;
            this.historicalCount = historicalCount;
        }

        boolean shouldSkip() {
            return shouldSkip;
        }

        double getSkipProbability() {
            return skipProbability;
        }

        long getHistoricalCount() {
            return historicalCount;
        }
    }

    static final class NoveltySnapshot {
        private final double overallNovelty;
        private final double rootCauseNovelty;
        private final double familyNovelty;
        private final double signatureNovelty;

        NoveltySnapshot(double overallNovelty, double rootCauseNovelty, double familyNovelty, double signatureNovelty) {
            this.overallNovelty = overallNovelty;
            this.rootCauseNovelty = rootCauseNovelty;
            this.familyNovelty = familyNovelty;
            this.signatureNovelty = signatureNovelty;
        }

        double getOverallNovelty() {
            return overallNovelty;
        }

        double getRootCauseNovelty() {
            return rootCauseNovelty;
        }

        double getFamilyNovelty() {
            return familyNovelty;
        }

        double getSignatureNovelty() {
            return signatureNovelty;
        }
    }

    static final class RootCauseScarcitySnapshot {
        private final boolean unseen;
        private final boolean saturated;
        private final double boost;

        RootCauseScarcitySnapshot(boolean unseen, boolean saturated, double boost) {
            this.unseen = unseen;
            this.saturated = saturated;
            this.boost = boost;
        }

        boolean isUnseen() {
            return unseen;
        }

        boolean isSaturated() {
            return saturated;
        }

        double getBoost() {
            return boost;
        }
    }

    static final class WitnessDecision {
        private final boolean shouldWriteReport;
        private final long signatureObservationCount;
        private final long signatureWrittenReports;
        private final long skeletonObservationCount;
        private final long familyObservationCount;
        private final long rootCauseObservationCount;
        private final long rootCauseWrittenReports;
        private final long totalSuppressedReports;
        private final long totalRootCauseSuppressedReports;
        private final String selectionReason;
        private final String representativeRoles;
        private final ReportReservation reservation;

        WitnessDecision(boolean shouldWriteReport, long signatureObservationCount, long signatureWrittenReports,
                long skeletonObservationCount, long familyObservationCount, long rootCauseObservationCount,
                long rootCauseWrittenReports, long totalSuppressedReports, long totalRootCauseSuppressedReports,
                String selectionReason, String representativeRoles, ReportReservation reservation) {
            this.shouldWriteReport = shouldWriteReport;
            this.signatureObservationCount = signatureObservationCount;
            this.signatureWrittenReports = signatureWrittenReports;
            this.skeletonObservationCount = skeletonObservationCount;
            this.familyObservationCount = familyObservationCount;
            this.rootCauseObservationCount = rootCauseObservationCount;
            this.rootCauseWrittenReports = rootCauseWrittenReports;
            this.totalSuppressedReports = totalSuppressedReports;
            this.totalRootCauseSuppressedReports = totalRootCauseSuppressedReports;
            this.selectionReason = selectionReason;
            this.representativeRoles = representativeRoles;
            this.reservation = reservation;
        }

        boolean shouldWriteReport() {
            return shouldWriteReport;
        }

        long getSignatureObservationCount() {
            return signatureObservationCount;
        }

        long getSignatureWrittenReports() {
            return signatureWrittenReports;
        }

        long getSkeletonObservationCount() {
            return skeletonObservationCount;
        }

        long getFamilyObservationCount() {
            return familyObservationCount;
        }

        long getRootCauseObservationCount() {
            return rootCauseObservationCount;
        }

        long getRootCauseWrittenReports() {
            return rootCauseWrittenReports;
        }

        long getTotalSuppressedReports() {
            return totalSuppressedReports;
        }

        long getTotalRootCauseSuppressedReports() {
            return totalRootCauseSuppressedReports;
        }

        String getSelectionReason() {
            return selectionReason;
        }

        String getRepresentativeRoles() {
            return representativeRoles;
        }

        ReportReservation getReservation() {
            return reservation;
        }
    }

    /** Immutable quota reservation held while a selected report is persisted. */
    static final class ReportReservation {
        private final String skeletonKey;
        private final String familyKey;
        private final String planSignatureKey;
        private final String rootCauseKey;
        private final double runtimeSpeedup;
        private final double costRatio;
        private final double absoluteRuntimeGapMs;
        private final double simplicityScore;
        private final String representativeRoles;
        private final String selectionReason;
        private final String nineCellCategory;

        ReportReservation(String skeletonKey, String familyKey, String planSignatureKey, String rootCauseKey,
                double runtimeSpeedup, double costRatio, double absoluteRuntimeGapMs, double simplicityScore,
                String representativeRoles, String selectionReason, String nineCellCategory) {
            this.skeletonKey = skeletonKey;
            this.familyKey = familyKey;
            this.planSignatureKey = planSignatureKey;
            this.rootCauseKey = rootCauseKey;
            this.runtimeSpeedup = runtimeSpeedup;
            this.costRatio = costRatio;
            this.absoluteRuntimeGapMs = absoluteRuntimeGapMs;
            this.simplicityScore = simplicityScore;
            this.representativeRoles = representativeRoles;
            this.selectionReason = selectionReason;
            this.nineCellCategory = nineCellCategory;
        }

        String getSkeletonKey() {
            return skeletonKey;
        }

        String getFamilyKey() {
            return familyKey;
        }

        String getPlanSignatureKey() {
            return planSignatureKey;
        }

        String getRootCauseKey() {
            return rootCauseKey;
        }

        double getRuntimeSpeedup() {
            return runtimeSpeedup;
        }

        double getCostRatio() {
            return costRatio;
        }

        double getAbsoluteRuntimeGapMs() {
            return absoluteRuntimeGapMs;
        }

        double getSimplicityScore() {
            return simplicityScore;
        }

        String getRepresentativeRoles() {
            return representativeRoles;
        }

        String getSelectionReason() {
            return selectionReason;
        }

        String getNineCellCategory() {
            return nineCellCategory;
        }
    }

    private static final class SkeletonStats {
        private long totalObservations;
        private final Map<String, Long> rootCauseCounts = new LinkedHashMap<>();
        private final List<String> recentRootCauses = new ArrayList<>();
        private final List<Long> recentlyNewRootCauseObservations = new ArrayList<>();

        private DominantRootCause computeDominantRootCause() {
            return rootCauseCounts.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(entry -> new DominantRootCause(entry.getKey(),
                            totalObservations == 0 ? 0.0 : (double) entry.getValue() / (double) totalObservations))
                    .orElse(null);
        }

        private boolean recentlySawNewRootCause() {
            if (recentlyNewRootCauseObservations.isEmpty()) {
                return false;
            }
            long windowStart = Instant.now().minusSeconds(600).getEpochSecond();
            return recentlyNewRootCauseObservations.stream().anyMatch(ts -> ts >= windowStart);
        }
    }

    private static final class DominantRootCause {
        private final String rootCauseKey;
        private final double share;

        private DominantRootCause(String rootCauseKey, double share) {
            this.rootCauseKey = rootCauseKey;
            this.share = share;
        }
    }

    private static final class SignatureStats {
        private long totalObservations;
        private long writtenReports;
        private long reservedReports;
        private double bestRuntimeSpeedup;
        private double bestCostRatio;
        private final List<String> representativeCases = new ArrayList<>();
    }

    private static final class RootCauseStats {
        private long totalObservations;
        private long writtenReports;
        private long reservedReports;
        private double bestRuntimeSpeedup;
        private double bestAbsoluteRuntimeGapMs;
        private double bestCostRatio;
        private String bestRuntimeSpeedupCase;
        private String bestAbsoluteRuntimeGapCase;
        private String simplestSignatureCase;
        private double simplestComplexityScore = Double.MAX_VALUE;

        private RootCauseStats copy() {
            RootCauseStats copy = new RootCauseStats();
            copy.totalObservations = totalObservations;
            copy.writtenReports = writtenReports;
            copy.reservedReports = reservedReports;
            copy.bestRuntimeSpeedup = bestRuntimeSpeedup;
            copy.bestAbsoluteRuntimeGapMs = bestAbsoluteRuntimeGapMs;
            copy.bestCostRatio = bestCostRatio;
            copy.bestRuntimeSpeedupCase = bestRuntimeSpeedupCase;
            copy.bestAbsoluteRuntimeGapCase = bestAbsoluteRuntimeGapCase;
            copy.simplestSignatureCase = simplestSignatureCase;
            copy.simplestComplexityScore = simplestComplexityScore;
            return copy;
        }

        private List<String> currentRepresentativeCases() {
            List<String> representatives = new ArrayList<>();
            if (bestRuntimeSpeedupCase != null && !bestRuntimeSpeedupCase.isBlank()) {
                representatives.add(bestRuntimeSpeedupCase);
            }
            if (bestAbsoluteRuntimeGapCase != null && !bestAbsoluteRuntimeGapCase.isBlank()) {
                representatives.add(bestAbsoluteRuntimeGapCase);
            }
            if (simplestSignatureCase != null && !simplestSignatureCase.isBlank()) {
                representatives.add(simplestSignatureCase);
            }
            return representatives.stream().distinct().collect(Collectors.toList());
        }
    }

    private static final class RootCauseSelection {
        private final boolean shouldWrite;
        private final SelectionReason selectionReason;
        private final List<String> representativeRoles;

        private RootCauseSelection(boolean shouldWrite, SelectionReason selectionReason,
                List<String> representativeRoles) {
            this.shouldWrite = shouldWrite;
            this.selectionReason = selectionReason;
            this.representativeRoles = List.copyOf(representativeRoles);
        }

        private String joinRepresentativeRoles() {
            return String.join(",", representativeRoles);
        }
    }

    private enum SelectionReason {
        NEW_ROOT_CAUSE, UNDER_ROOT_CAUSE_CAP, BETTER_REPRESENTATIVE, SUPPRESSED_ROOT_CAUSE_DUPLICATE
    }
}
