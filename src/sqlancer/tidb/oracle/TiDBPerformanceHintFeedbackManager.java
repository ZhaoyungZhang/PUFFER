package sqlancer.tidb.oracle;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.tidb.TiDBOptions;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;

final class TiDBPerformanceHintFeedbackManager {

    private static final Map<Path, TiDBPerformanceHintFeedbackManager> INSTANCES = new ConcurrentHashMap<>();
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

    private TiDBPerformanceHintFeedbackManager(Path stateDir) {
        this.stateDir = stateDir;
        this.stateFile = stateDir.resolve(STATE_FILE_NAME);
        this.eventsFile = stateDir.resolve(EVENTS_FILE_NAME);
        this.summaryFile = stateDir.resolve(SUMMARY_FILE_NAME);
        loadState();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "tidb-perfhint-feedback-checkpoint");
            thread.setDaemon(true);
            return thread;
        };
        checkpointWriter = Executors.newSingleThreadScheduledExecutor(threadFactory);
        checkpointWriter.scheduleAtFixedRate(this::checkpointSafely, 2L, 2L, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "tidb-perfhint-feedback-shutdown"));
    }

    static TiDBPerformanceHintFeedbackManager getInstance(TiDBGlobalState state) {
        Path configuredPath = resolveStateDirectory(state.getDbmsSpecificOptions());
        return INSTANCES.computeIfAbsent(configuredPath.toAbsolutePath().normalize(),
                TiDBPerformanceHintFeedbackManager::new);
    }

    static TiDBPerformanceHintFeedbackManager forStateDirectory(Path stateDir) {
        return INSTANCES.computeIfAbsent(stateDir.toAbsolutePath().normalize(),
                TiDBPerformanceHintFeedbackManager::new);
    }

    static Path resolveStateDirectory(TiDBOptions options) {
        if (options.performanceFeedbackStatePath != null && !options.performanceFeedbackStatePath.isBlank()) {
            return Path.of(options.performanceFeedbackStatePath);
        }
        return TiDBPerformanceHintOracleUtils.resolveReportRoot().resolve("_feedback");
    }

    synchronized SkeletonDecision evaluateSkeleton(String skeletonKey, Randomly randomly, TiDBOptions options)
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

    synchronized NoveltySnapshot estimateNoveltySnapshot(TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            TiDBOptions options) {
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

    synchronized RootCauseScarcitySnapshot getRootCauseScarcitySnapshot(String rootCauseKey, TiDBOptions options) {
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

    synchronized WitnessDecision recordWitness(TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            TiDBOptions options) throws IOException {
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
        long priorRootCauseReservedOrWrittenReports = rootStats.writtenReports + rootStats.reservedReports;
        rootStats.totalObservations++;
        long rootCauseCount = incrementCount(rootCauseCounts, scopedRootCauseKey);

        updateSkeletonStats(witness.getSkeletonKey(), scopedRootCauseKey, priorRootCauseObservations == 0L);

        RootCauseSelection rootSelection = chooseRootCauseSelection(witness, previousRootStats,
                priorRootCauseObservations, priorRootCauseReservedOrWrittenReports, options);
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
        if (totalWitnessesReserved > 0) {
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
        if (totalWitnessesReserved > 0) {
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

    private RootCauseSelection chooseRootCauseSelection(TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            RootCauseStats comparisonStats, long priorRootCauseObservations, long priorRootCauseWrittenReports,
            TiDBOptions options) {
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

    private List<String> computeRepresentativeRoles(TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness,
            RootCauseStats rootStats, boolean allowEmptyStatsAsRepresentative) {
        List<String> roles = new ArrayList<>();
        double runtimeSpeedup = witness.getRuntimeSpeedup();
        double runtimeGapMs = computeAbsoluteRuntimeGapMs(witness);
        double simplicityScore = computeSimplicityScore(witness);
        if (allowEmptyStatsAsRepresentative || runtimeSpeedup > rootStats.bestRuntimeSpeedup) {
            roles.add("best_runtime_speedup");
        }
        if (allowEmptyStatsAsRepresentative || runtimeGapMs > rootStats.bestAbsoluteRuntimeGapMs) {
            roles.add("best_absolute_runtime_gap");
        }
        if (allowEmptyStatsAsRepresentative || simplicityScore < rootStats.simplestSignatureScore) {
            roles.add("simplest_signature");
        }
        return roles;
    }

    private void assignRepresentativeRoles(RootCauseStats rootStats, ReportReservation reservation,
            String reportDirName) throws IOException {
        List<String> roles = splitRepresentativeRoles(reservation.getRepresentativeRoles());
        for (String role : roles) {
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
                rootStats.simplestSignatureScore = reservation.getSimplicityScore();
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

    private void updateSkeletonStats(String skeletonKey, String rootCauseKey, boolean newRootCause) {
        SkeletonStats stats = skeletonStats.computeIfAbsent(skeletonKey, key -> new SkeletonStats());
        stats.record(rootCauseKey, newRootCause);
    }

    private boolean isSaturatedRootCause(String rootCauseKey, TiDBOptions options) {
        RootCauseStats stats = rootCauseStats.get(rootCauseKey);
        return stats != null && stats.writtenReports + stats.reservedReports >= Math.max(1,
                options.performanceFeedbackRootCauseHardCap);
    }

    private static void releaseReservation(SignatureStats stats) {
        if (stats.reservedReports > 0) {
            stats.reservedReports--;
        }
    }

    private static void releaseReservation(RootCauseStats stats) {
        if (stats.reservedReports > 0) {
            stats.reservedReports--;
        }
    }

    private static String scopedFamilyKey(TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        return scopeKey(witness.getFamilyKey(), witness);
    }

    private static String scopedPlanSignatureKey(TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        return scopeKey(witness.getPlanSignatureKey(), witness);
    }

    private static String scopedRootCauseKey(TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        return scopeKey(witness.getRootCauseSignatureKey(), witness);
    }

    private static String scopeKey(String rawKey, TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        if (rawKey == null || rawKey.isBlank()) {
            return rawKey;
        }
        if (witness.getNineCellCategory() == null) {
            return rawKey;
        }
        return rawKey + "|nineCell=" + witness.getNineCellCategory().getCode();
    }

    private static long incrementCount(Map<String, Long> counts, String key) {
        long updated = counts.getOrDefault(key, 0L) + 1L;
        counts.put(key, updated);
        return updated;
    }

    private static double computeSkipProbability(long count, int threshold) {
        return Math.min(0.95, Math.max(0.0, 1.0 - ((double) threshold / (double) (count + 1L))));
    }

    private static double computeAbsoluteRuntimeGapMs(TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
        if (witness.getDefaultRuntime() == null || witness.getHintedRuntime() == null) {
            return 0.0;
        }
        return Math.max(0.0, witness.getDefaultRuntime().getMedianMs() - witness.getHintedRuntime().getMedianMs());
    }

    private static double computeSimplicityScore(TiDBPerformanceHintOracleUtils.PerformanceHintWitness witness) {
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
        for (String part : skeletonKey.split("\\|")) {
            if (part.startsWith("TABLES_")) {
                try {
                    return Integer.parseInt(part.substring("TABLES_".length()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static List<String> splitRepresentativeRoles(String representativeRank) {
        if (representativeRank == null || representativeRank.isBlank()) {
            return List.of();
        }
        return List.of(representativeRank.split(","));
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
                switch (parts[0]) {
                case "META":
                    totalWitnessesSeen = parseLongPart(parts, 1);
                    totalWitnessesWritten = parseLongPart(parts, 2);
                    totalWitnessesSuppressed = parseLongPart(parts, 3);
                    totalSkeletonSkips = parseLongPart(parts, 4);
                    totalRootCauseSuppressions = parts.length > 5 ? parseLongPart(parts, 5) : 0L;
                    totalWitnessesWriteFailed = parts.length > 6 ? parseLongPart(parts, 6) : 0L;
                    break;
                case "S":
                    skeletonCounts.put(decode(parts[1]), Long.parseLong(parts[2]));
                    break;
                case "F":
                    mergeLongCount(familyCounts, migrateLegacyFamilyOrPlanKey(decode(parts[1])),
                            Long.parseLong(parts[2]));
                    break;
                case "P":
                    SignatureStats signature = new SignatureStats();
                    signature.totalObservations = Long.parseLong(parts[2]);
                    signature.writtenReports = Long.parseLong(parts[3]);
                    signature.bestRuntimeSpeedup = Double.parseDouble(parts[4]);
                    signature.bestCostRatio = Double.parseDouble(parts[5]);
                    if (parts.length > 6 && !parts[6].isBlank()) {
                        for (String encodedCase : parts[6].split(",")) {
                            if (!encodedCase.isBlank()) {
                                signature.representativeCases.add(decode(encodedCase));
                            }
                        }
                    }
                    mergeSignatureStats(signatureCounts, migrateLegacyFamilyOrPlanKey(decode(parts[1])), signature);
                    break;
                case "R":
                    RootCauseStats rootStats = new RootCauseStats();
                    rootStats.totalObservations = Long.parseLong(parts[2]);
                    rootStats.writtenReports = Long.parseLong(parts[3]);
                    rootStats.bestRuntimeSpeedup = Double.parseDouble(parts[4]);
                    rootStats.bestAbsoluteRuntimeGapMs = Double.parseDouble(parts[5]);
                    rootStats.bestCostRatio = Double.parseDouble(parts[6]);
                    rootStats.bestRuntimeSpeedupCase = decodeNullable(parts, 7);
                    rootStats.bestAbsoluteRuntimeGapCase = decodeNullable(parts, 8);
                    rootStats.simplestSignatureCase = decodeNullable(parts, 9);
                    rootStats.simplestSignatureScore = parts.length > 10 && !parts[10].isBlank()
                            ? Double.parseDouble(parts[10]) : Double.POSITIVE_INFINITY;
                    String migratedRootCauseKey = migrateLegacyRootCauseKey(decode(parts[1]));
                    mergeRootCauseStats(rootCauseStats, migratedRootCauseKey, rootStats);
                    rootCauseCounts.put(migratedRootCauseKey,
                            rootCauseStats.get(migratedRootCauseKey).totalObservations);
                    break;
                case "K":
                    SkeletonStats skeleton = new SkeletonStats();
                    if (parts.length > 2 && !parts[2].isBlank()) {
                        for (String encodedRootCause : parts[2].split(",")) {
                            if (!encodedRootCause.isBlank()) {
                                skeleton.recentRootCauses.add(migrateLegacyRootCauseKey(decode(encodedRootCause)));
                            }
                        }
                    }
                    if (parts.length > 3 && !parts[3].isBlank()) {
                        for (String marker : parts[3].split(",")) {
                            if (!marker.isBlank()) {
                                skeleton.recentNewRootCauseFlags.add("1".equals(marker));
                            }
                        }
                    }
                    mergeSkeletonStats(skeletonStats, decode(parts[1]), skeleton);
                    break;
                default:
                    break;
                }
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Feedback state written before counterfactual support used a bare HINT type as the first key component, for
     * example {@code INDEX|SELECT|...|HINTED=...}. Normalize that representation while loading so the next persistence
     * writes only the new mutation identity.
     */
    static String migrateLegacyFamilyOrPlanKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank() || rawKey.startsWith("mutation=")) {
            return rawKey;
        }
        int firstSeparator = rawKey.indexOf('|');
        if (firstSeparator <= 0) {
            return rawKey;
        }
        String hintType = rawKey.substring(0, firstSeparator);
        if (hintType.startsWith("HINT_") || hintType.contains("=")) {
            return rawKey;
        }
        String hintFamily = inferLegacyHintFamily(hintType);
        String remainder = rawKey.substring(firstSeparator + 1).replace("|HINTED=", "|COUNTERFACTUAL=");
        return "mutation=HINT|" + hintFamily + "|" + hintType + "|" + remainder;
    }

    /** Compatibility entry point retained for existing feedback migration tests and callers. */
    static String migrateLegacyKey(String type, String rawKey) {
        if ("R".equals(type)) {
            return migrateLegacyRootCauseKey(rawKey);
        }
        return migrateLegacyFamilyOrPlanKey(rawKey);
    }

    /**
     * Legacy root-cause signatures encoded only HINT_FAMILY/HINT_TYPE inside their mechanism portion. The generic
     * classifier prefixes the explicit {@code HINT|family|type} identity and namespaces the mechanism family as
     * {@code HINT_<family>}; reproduce that shape for state migration.
     */
    static String migrateLegacyRootCauseKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return rawKey;
        }
        String[] parts = rawKey.split("\\|", -1);
        if (parts.length < 3 || "HINT".equals(parts[1])) {
            return rawKey;
        }
        String hintFamily = extractKeyPart(parts, "HINT_FAMILY=");
        String hintType = extractKeyPart(parts, "HINT_TYPE=");
        if (hintType == null || hintType.isBlank()) {
            return rawKey;
        }
        if (hintFamily == null || hintFamily.isBlank()) {
            hintFamily = inferLegacyHintFamily(hintType);
        }
        String normalizedMechanism = rawKey.replace("HINT_FAMILY=" + hintFamily, "HINT_FAMILY=HINT_" + hintFamily);
        int firstSeparator = normalizedMechanism.indexOf('|');
        return normalizedMechanism.substring(0, firstSeparator) + "|HINT|" + hintFamily + "|" + hintType + "|"
                + normalizedMechanism.substring(firstSeparator + 1);
    }

    private static String extractKeyPart(String[] parts, String prefix) {
        for (String part : parts) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return null;
    }

    private static String inferLegacyHintFamily(String hintType) {
        switch (hintType) {
        case "MERGE":
        case "NO_MERGE":
        case "JOIN_ORDER":
        case "JOIN_PREFIX":
        case "JOIN_SUFFIX":
        case "JOIN_FIXED_ORDER":
        case "HASH_JOIN":
        case "INL_JOIN":
        case "INL_HASH_JOIN":
        case "MERGE_JOIN":
        case "BKA":
        case "NO_BKA":
        case "BNL":
        case "NO_BNL":
            return "TABLE";
        default:
            return "INDEX";
        }
    }

    private static void mergeLongCount(Map<String, Long> counts, String key, long count) {
        counts.merge(key, count, Long::sum);
    }

    private static void mergeSignatureStats(Map<String, SignatureStats> statsByKey, String key,
            SignatureStats incoming) {
        SignatureStats existing = statsByKey.get(key);
        if (existing == null) {
            statsByKey.put(key, incoming);
            return;
        }
        existing.totalObservations += incoming.totalObservations;
        existing.writtenReports += incoming.writtenReports;
        existing.bestRuntimeSpeedup = Math.max(existing.bestRuntimeSpeedup, incoming.bestRuntimeSpeedup);
        existing.bestCostRatio = Math.max(existing.bestCostRatio, incoming.bestCostRatio);
        for (String representative : incoming.representativeCases) {
            if (!existing.representativeCases.contains(representative)) {
                existing.representativeCases.add(representative);
            }
        }
        while (existing.representativeCases.size() > 3) {
            existing.representativeCases.remove(0);
        }
    }

    private static void mergeRootCauseStats(Map<String, RootCauseStats> statsByKey, String key,
            RootCauseStats incoming) {
        RootCauseStats existing = statsByKey.get(key);
        if (existing == null) {
            statsByKey.put(key, incoming);
            return;
        }
        existing.totalObservations += incoming.totalObservations;
        existing.writtenReports += incoming.writtenReports;
        if (incoming.bestRuntimeSpeedup > existing.bestRuntimeSpeedup) {
            existing.bestRuntimeSpeedup = incoming.bestRuntimeSpeedup;
            existing.bestRuntimeSpeedupCase = incoming.bestRuntimeSpeedupCase;
        }
        if (incoming.bestAbsoluteRuntimeGapMs > existing.bestAbsoluteRuntimeGapMs) {
            existing.bestAbsoluteRuntimeGapMs = incoming.bestAbsoluteRuntimeGapMs;
            existing.bestAbsoluteRuntimeGapCase = incoming.bestAbsoluteRuntimeGapCase;
        }
        if (incoming.bestCostRatio > existing.bestCostRatio) {
            existing.bestCostRatio = incoming.bestCostRatio;
        }
        if (incoming.simplestSignatureScore < existing.simplestSignatureScore) {
            existing.simplestSignatureScore = incoming.simplestSignatureScore;
            existing.simplestSignatureCase = incoming.simplestSignatureCase;
        }
    }

    private static void mergeSkeletonStats(Map<String, SkeletonStats> statsByKey, String key, SkeletonStats incoming) {
        SkeletonStats existing = statsByKey.get(key);
        if (existing == null) {
            statsByKey.put(key, incoming);
            return;
        }
        for (String rootCause : incoming.recentRootCauses) {
            existing.recentRootCauses.add(rootCause);
        }
        while (existing.recentRootCauses.size() > SKELETON_ROOT_CAUSE_HISTORY) {
            existing.recentRootCauses.remove(0);
        }
        for (Boolean flag : incoming.recentNewRootCauseFlags) {
            existing.recentNewRootCauseFlags.add(flag);
        }
        while (existing.recentNewRootCauseFlags.size() > SKELETON_NEW_ROOT_CAUSE_WINDOW) {
            existing.recentNewRootCauseFlags.remove(0);
        }
    }

    private synchronized void markCheckpointDirty() {
        checkpointDirty = true;
    }

    private void checkpointSafely() {
        try {
            checkpointIfDirty();
        } catch (IOException ignored) {
            // Feedback persistence is best-effort during a fuzzing campaign. The
            // in-memory dedup state remains authoritative for the current JVM.
        }
    }

    private synchronized void checkpointIfDirty() throws IOException {
        if (!checkpointDirty) {
            return;
        }
        persistState();
        checkpointDirty = false;
    }

    /**
     * The asynchronous report writer calls this after draining its queue. JVM shutdown hooks have no ordering
     * guarantee, so relying only on this manager's own hook could otherwise persist the feedback state before the final
     * reservations transition to written/failed.
     */
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
        lines.add(String.format(Locale.US, "META\t%d\t%d\t%d\t%d\t%d\t%d", totalWitnessesSeen, totalWitnessesWritten,
                totalWitnessesSuppressed, totalSkeletonSkips, totalRootCauseSuppressions, totalWitnessesWriteFailed));
        for (Map.Entry<String, Long> entry : skeletonCounts.entrySet()) {
            lines.add("S\t" + encode(entry.getKey()) + "\t" + entry.getValue());
        }
        for (Map.Entry<String, SkeletonStats> entry : skeletonStats.entrySet()) {
            SkeletonStats stats = entry.getValue();
            String recentRootCauses = stats.recentRootCauses.stream().map(TiDBPerformanceHintFeedbackManager::encode)
                    .collect(Collectors.joining(","));
            String recentNovelty = stats.recentNewRootCauseFlags.stream().map(flag -> flag ? "1" : "0")
                    .collect(Collectors.joining(","));
            lines.add("K\t" + encode(entry.getKey()) + "\t" + recentRootCauses + "\t" + recentNovelty);
        }
        for (Map.Entry<String, Long> entry : familyCounts.entrySet()) {
            lines.add("F\t" + encode(entry.getKey()) + "\t" + entry.getValue());
        }
        for (Map.Entry<String, SignatureStats> entry : signatureCounts.entrySet()) {
            SignatureStats stats = entry.getValue();
            String representatives = stats.representativeCases.stream().map(TiDBPerformanceHintFeedbackManager::encode)
                    .collect(Collectors.joining(","));
            lines.add(String.format(Locale.US, "P\t%s\t%d\t%d\t%.6f\t%.6f\t%s", encode(entry.getKey()),
                    stats.totalObservations, stats.writtenReports, stats.bestRuntimeSpeedup, stats.bestCostRatio,
                    representatives));
        }
        for (Map.Entry<String, RootCauseStats> entry : rootCauseStats.entrySet()) {
            RootCauseStats stats = entry.getValue();
            lines.add(String.format(Locale.US, "R\t%s\t%d\t%d\t%.6f\t%.6f\t%.6f\t%s\t%s\t%s\t%.6f",
                    encode(entry.getKey()), stats.totalObservations, stats.writtenReports, stats.bestRuntimeSpeedup,
                    stats.bestAbsoluteRuntimeGapMs, stats.bestCostRatio, encodeNullable(stats.bestRuntimeSpeedupCase),
                    encodeNullable(stats.bestAbsoluteRuntimeGapCase), encodeNullable(stats.simplestSignatureCase),
                    stats.simplestSignatureScore));
        }
        Files.write(stateFile, lines, StandardCharsets.UTF_8);
        Files.writeString(summaryFile, buildSummaryJson(), StandardCharsets.UTF_8);
    }

    private void appendEvent(String type, String skeletonKey, String familyKey, String signatureKey,
            String rootCauseKey, long count, long writtenReports, double runtimeSpeedup, double costRatio,
            Map<String, String> extraFields) {
        try {
            Files.createDirectories(stateDir);
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            appendJsonField(sb, "timestamp", Instant.now().toString());
            appendJsonField(sb, "type", type);
            appendJsonField(sb, "skeletonKey", skeletonKey);
            appendJsonField(sb, "familyKey", familyKey);
            appendJsonField(sb, "planSignatureKey", signatureKey);
            appendJsonField(sb, "rootCauseSignatureKey", rootCauseKey);
            appendJsonField(sb, "count", Long.toString(count));
            appendJsonField(sb, "writtenReports", Long.toString(writtenReports));
            appendJsonField(sb, "runtimeSpeedup", formatDouble(runtimeSpeedup));
            appendJsonField(sb, "costRatio", formatDouble(costRatio));
            for (Map.Entry<String, String> entry : extraFields.entrySet()) {
                appendJsonField(sb, entry.getKey(), entry.getValue());
            }
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
            sb.append("}").append(System.lineSeparator());
            Files.writeString(eventsFile, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // A feedback event cannot be allowed to terminate the fuzzer. The
            // in-memory reservation and later checkpoint still preserve the
            // campaign's current dedup behaviour.
        }
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
            sb.append("    {\"rootCauseSignatureKey\": \"").append(escapeJson(entry.getKey())).append("\", \"count\": ")
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

    private static void appendJsonField(StringBuilder sb, String key, String value) {
        if (value == null) {
            return;
        }
        sb.append("\"").append(escapeJson(key)).append("\": \"").append(escapeJson(value)).append("\",");
    }

    private static String formatDouble(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeNullable(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return encode(value);
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String decodeNullable(String[] parts, int index) {
        if (parts.length <= index || parts[index].isBlank()) {
            return null;
        }
        return decode(parts[index]);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t",
                "\\t");
    }

    private static long parseLongPart(String[] parts, int index) {
        return parts.length > index ? Long.parseLong(parts[index]) : 0L;
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
        private final double boostMultiplier;

        RootCauseScarcitySnapshot(boolean unseen, boolean saturated, double boostMultiplier) {
            this.unseen = unseen;
            this.saturated = saturated;
            this.boostMultiplier = boostMultiplier;
        }

        boolean isUnseen() {
            return unseen;
        }

        boolean isSaturated() {
            return saturated;
        }

        double getBoostMultiplier() {
            return boostMultiplier;
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

    /** Immutable reservation that keeps dedup quotas correct while a report is written asynchronously. */
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

    private enum SelectionReason {
        NEW_ROOT_CAUSE, UNDER_ROOT_CAUSE_CAP, BETTER_REPRESENTATIVE, SUPPRESSED_ROOT_CAUSE_DUPLICATE
    }

    private static final class RootCauseSelection {
        private final boolean shouldWrite;
        private final SelectionReason selectionReason;
        private final List<String> representativeRoles;

        RootCauseSelection(boolean shouldWrite, SelectionReason selectionReason, List<String> representativeRoles) {
            this.shouldWrite = shouldWrite;
            this.selectionReason = selectionReason;
            this.representativeRoles = representativeRoles;
        }

        String joinRepresentativeRoles() {
            return String.join(",", representativeRoles);
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
        private double simplestSignatureScore = Double.POSITIVE_INFINITY;

        private RootCauseStats copy() {
            RootCauseStats copy = new RootCauseStats();
            copy.totalObservations = totalObservations;
            copy.writtenReports = writtenReports;
            copy.bestRuntimeSpeedup = bestRuntimeSpeedup;
            copy.bestAbsoluteRuntimeGapMs = bestAbsoluteRuntimeGapMs;
            copy.bestCostRatio = bestCostRatio;
            copy.bestRuntimeSpeedupCase = bestRuntimeSpeedupCase;
            copy.bestAbsoluteRuntimeGapCase = bestAbsoluteRuntimeGapCase;
            copy.simplestSignatureCase = simplestSignatureCase;
            copy.simplestSignatureScore = simplestSignatureScore;
            return copy;
        }

        private List<String> currentRepresentativeCases() {
            Set<String> representatives = new LinkedHashSet<>();
            if (bestRuntimeSpeedupCase != null && !bestRuntimeSpeedupCase.isBlank()) {
                representatives.add(bestRuntimeSpeedupCase);
            }
            if (bestAbsoluteRuntimeGapCase != null && !bestAbsoluteRuntimeGapCase.isBlank()) {
                representatives.add(bestAbsoluteRuntimeGapCase);
            }
            if (simplestSignatureCase != null && !simplestSignatureCase.isBlank()) {
                representatives.add(simplestSignatureCase);
            }
            return new ArrayList<>(representatives);
        }
    }

    private static final class SkeletonStats {
        private final List<String> recentRootCauses = new ArrayList<>();
        private final List<Boolean> recentNewRootCauseFlags = new ArrayList<>();

        private void record(String rootCauseKey, boolean newRootCause) {
            recentRootCauses.add(rootCauseKey);
            if (recentRootCauses.size() > SKELETON_ROOT_CAUSE_HISTORY) {
                recentRootCauses.remove(0);
            }
            recentNewRootCauseFlags.add(newRootCause);
            if (recentNewRootCauseFlags.size() > SKELETON_NEW_ROOT_CAUSE_WINDOW) {
                recentNewRootCauseFlags.remove(0);
            }
        }

        private boolean recentlySawNewRootCause() {
            for (Boolean flag : recentNewRootCauseFlags) {
                if (Boolean.TRUE.equals(flag)) {
                    return true;
                }
            }
            return false;
        }

        private DominantRootCause computeDominantRootCause() {
            if (recentRootCauses.isEmpty()) {
                return null;
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String rootCause : recentRootCauses) {
                counts.put(rootCause, counts.getOrDefault(rootCause, 0) + 1);
            }
            Map.Entry<String, Integer> dominant = counts.entrySet().stream().max(Map.Entry.comparingByValue())
                    .orElse(null);
            if (dominant == null) {
                return null;
            }
            return new DominantRootCause(dominant.getKey(),
                    ((double) dominant.getValue()) / ((double) recentRootCauses.size()));
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
}
