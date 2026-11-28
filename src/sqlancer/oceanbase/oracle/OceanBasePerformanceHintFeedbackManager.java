package sqlancer.oceanbase.oracle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sqlancer.Randomly;
import sqlancer.oceanbase.OceanBaseGlobalState;
import sqlancer.oceanbase.OceanBaseOptions;
import sqlancer.oceanbase.oracle.OceanBasePerformanceHintOracleUtils.PerformanceHintWitness;

/**
 * Lightweight persistent sampling feedback. It keeps performance campaigns from emitting a directory full of the same
 * query skeleton/plan witness while leaving all raw candidate validation intact.
 */
final class OceanBasePerformanceHintFeedbackManager {

    private static final String STATE_FILE = "feedback-state.tsv";
    private static final String EVENT_FILE = "feedback-events.tsv";
    private static final Map<Path, OceanBasePerformanceHintFeedbackManager> INSTANCES = new ConcurrentHashMap<>();

    private final Path stateDirectory;
    private final Map<String, Long> skeletonCounts = new LinkedHashMap<>();
    private final Map<String, Long> familyCounts = new LinkedHashMap<>();
    private final Map<String, Long> signatureObservationCounts = new LinkedHashMap<>();
    private final Map<String, Long> signatureReportCounts = new LinkedHashMap<>();
    private final Map<String, Long> signatureReservedCounts = new LinkedHashMap<>();
    private final Map<String, Long> rootCauseObservationCounts = new LinkedHashMap<>();
    private final Map<String, Long> skeletonRootCauseObservationCounts = new LinkedHashMap<>();
    private final Map<String, Long> rootCauseReportCounts = new LinkedHashMap<>();
    private final Map<String, Long> rootCauseReservedCounts = new LinkedHashMap<>();

    private OceanBasePerformanceHintFeedbackManager(Path stateDirectory) {
        this.stateDirectory = stateDirectory;
        loadState();
    }

    static OceanBasePerformanceHintFeedbackManager getInstance(OceanBaseGlobalState state) {
        return forStateDirectory(resolveStateDirectory(state.getDbmsSpecificOptions()));
    }

    static OceanBasePerformanceHintFeedbackManager forStateDirectory(Path stateDirectory) {
        return INSTANCES.computeIfAbsent(stateDirectory.toAbsolutePath().normalize(),
                OceanBasePerformanceHintFeedbackManager::new);
    }

    static Path resolveStateDirectory(OceanBaseOptions options) {
        if (options.performanceFeedbackStatePath != null && !options.performanceFeedbackStatePath.isBlank()) {
            return Path.of(options.performanceFeedbackStatePath);
        }
        return OceanBasePerformanceHintOracleUtils.resolveReportRoot().resolve(".feedback");
    }

    synchronized SkeletonDecision evaluateSkeleton(String skeletonKey, Randomly randomly, OceanBaseOptions options) {
        long count = skeletonCounts.getOrDefault(skeletonKey, 0L);
        if (!options.enablePerformanceFeedbackDedup
                || count < Math.max(1, options.performanceFeedbackSkeletonThreshold)) {
            return new SkeletonDecision(false, count, 0.0);
        }
        double baseProbability = Math.min(0.9,
                (count - options.performanceFeedbackSkeletonThreshold + 1.0) / (count + 1.0));
        double multiplier = dominantRootCauseMultiplier(skeletonKey, options);
        double probability = Math.min(0.95, baseProbability * multiplier);
        return new SkeletonDecision(Randomly.getPercentage() < probability, count, probability);
    }

    synchronized WitnessDecision recordWitness(PerformanceHintWitness witness, OceanBaseOptions options) {
        String skeletonKey = witness.getSkeletonKey();
        String familyKey = witness.getCounterfactualCandidate().getKind().name() + "|"
                + witness.getCounterfactualCandidate().getFamily();
        String signatureKey = witness.getPlanSignatureKey();
        String rootCauseKey = witness.getRootCauseKey();
        long skeletonCount = increment(skeletonCounts, skeletonKey);
        long familyCount = increment(familyCounts, familyKey);
        long signatureCount = increment(signatureObservationCounts, signatureKey);
        long rootCauseCount = increment(rootCauseObservationCounts, rootCauseKey);
        increment(skeletonRootCauseObservationCounts, scopedRootCauseKey(skeletonKey, rootCauseKey));

        long signatureReports = signatureReportCounts.getOrDefault(signatureKey, 0L);
        long signatureReserved = signatureReservedCounts.getOrDefault(signatureKey, 0L);
        long rootCauseReports = rootCauseReportCounts.getOrDefault(rootCauseKey, 0L);
        long rootCauseReserved = rootCauseReservedCounts.getOrDefault(rootCauseKey, 0L);
        boolean signatureSaturated = signatureReports + signatureReserved >= Math.max(1,
                options.performanceFeedbackSignatureHardCap);
        boolean rootCauseSaturated = options.enablePerformanceFeedbackRootCauseDedup
                && rootCauseReports + rootCauseReserved >= Math.max(1, options.performanceFeedbackRootCauseHardCap);
        boolean shouldWrite = !options.enablePerformanceFeedbackDedup || (!signatureSaturated && !rootCauseSaturated);
        String selectionReason = shouldWrite ? "novel_or_under_cap"
                : rootCauseSaturated ? "root_cause_hard_cap" : "plan_signature_hard_cap";
        ReportReservation reservation = null;
        if (shouldWrite) {
            increment(signatureReservedCounts, signatureKey);
            increment(rootCauseReservedCounts, rootCauseKey);
            reservation = new ReportReservation(skeletonKey, familyKey, signatureKey, rootCauseKey);
        }
        persistQuietly();
        appendEventQuietly("OBSERVE", skeletonKey, familyKey, signatureKey, rootCauseKey, selectionReason);
        return new WitnessDecision(shouldWrite, skeletonCount, familyCount, signatureCount,
                signatureReports + (shouldWrite ? 1L : signatureReserved), rootCauseCount,
                rootCauseReports + (shouldWrite ? 1L : rootCauseReserved), selectionReason, reservation);
    }

    synchronized void completeReservation(ReportReservation reservation, Path reportDirectory) {
        release(signatureReservedCounts, reservation.signatureKey);
        release(rootCauseReservedCounts, reservation.rootCauseKey);
        increment(signatureReportCounts, reservation.signatureKey);
        increment(rootCauseReportCounts, reservation.rootCauseKey);
        persistQuietly();
        appendEventQuietly("WRITE", reservation.skeletonKey, reservation.familyKey, reservation.signatureKey,
                reservation.rootCauseKey, reportDirectory.toString());
    }

    synchronized void failReservation(ReportReservation reservation, Exception failure) {
        release(signatureReservedCounts, reservation.signatureKey);
        release(rootCauseReservedCounts, reservation.rootCauseKey);
        persistQuietly();
        appendEventQuietly("WRITE_FAILED", reservation.skeletonKey, reservation.familyKey, reservation.signatureKey,
                reservation.rootCauseKey, failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    synchronized void checkpointAfterReportDrain() {
        persistQuietly();
    }

    private double dominantRootCauseMultiplier(String skeletonKey, OceanBaseOptions options) {
        if (!options.enablePerformanceFeedbackRootCauseDedup) {
            return 1.0;
        }
        long total = skeletonCounts.getOrDefault(skeletonKey, 0L);
        if (total == 0L) {
            return 1.0;
        }
        long max = 0L;
        String prefix = skeletonKey + '\u0000';
        for (Map.Entry<String, Long> entry : skeletonRootCauseObservationCounts.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                max = Math.max(max, entry.getValue());
            }
        }
        double share = (double) max / total;
        return share >= options.performanceFeedbackRootCauseSkeletonShareThreshold
                ? Math.max(1.0, options.performanceFeedbackRootCauseSkipMultiplier) : 1.0;
    }

    private static long increment(Map<String, Long> counts, String key) {
        long value = counts.getOrDefault(key, 0L) + 1L;
        counts.put(key, value);
        return value;
    }

    private static void release(Map<String, Long> counts, String key) {
        long value = counts.getOrDefault(key, 0L);
        if (value <= 1L) {
            counts.remove(key);
        } else {
            counts.put(key, value - 1L);
        }
    }

    private static String scopedRootCauseKey(String skeletonKey, String rootCauseKey) {
        return skeletonKey + '\u0000' + rootCauseKey;
    }

    private void loadState() {
        Path path = stateDirectory.resolve(STATE_FILE);
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] fields = line.split("\\t", 3);
                if (fields.length != 3) {
                    continue;
                }
                Map<String, Long> target = mapForType(fields[0]);
                if (target != null) {
                    target.put(migrateLegacyKey(fields[0], decode(fields[1])), Long.parseLong(fields[2]));
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // A partially written feedback file must not stop fuzzing; the next write replaces it.
        }
    }

    private void persistQuietly() {
        try {
            Files.createDirectories(stateDirectory);
            StringBuilder sb = new StringBuilder();
            appendMap(sb, "S", skeletonCounts);
            appendMap(sb, "F", familyCounts);
            appendMap(sb, "SO", signatureObservationCounts);
            appendMap(sb, "SR", signatureReportCounts);
            appendMap(sb, "RO", rootCauseObservationCounts);
            appendMap(sb, "SRO", skeletonRootCauseObservationCounts);
            appendMap(sb, "RR", rootCauseReportCounts);
            Files.writeString(stateDirectory.resolve(STATE_FILE), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Feedback is best effort. Candidate correctness/performance validation remains independent of persistence.
        }
    }

    private void appendEventQuietly(String type, String skeletonKey, String familyKey, String signatureKey,
            String rootCauseKey, String detail) {
        try {
            Files.createDirectories(stateDirectory);
            String line = type + "\t" + encode(skeletonKey) + "\t" + encode(familyKey) + "\t" + encode(signatureKey)
                    + "\t" + encode(rootCauseKey) + "\t" + encode(detail) + "\n";
            Files.writeString(stateDirectory.resolve(EVENT_FILE), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best effort only.
        }
    }

    private static void appendMap(StringBuilder sb, String type, Map<String, Long> values) {
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            sb.append(type).append('\t').append(encode(entry.getKey())).append('\t').append(entry.getValue())
                    .append('\n');
        }
    }

    private Map<String, Long> mapForType(String type) {
        switch (type) {
        case "S":
            return skeletonCounts;
        case "F":
            return familyCounts;
        case "SO":
            return signatureObservationCounts;
        case "SR":
            return signatureReportCounts;
        case "RO":
            return rootCauseObservationCounts;
        case "SRO":
            return skeletonRootCauseObservationCounts;
        case "RR":
            return rootCauseReportCounts;
        default:
            return null;
        }
    }

    /**
     * Feedback state predates generic counterfactual identities. Old entries are necessarily HINT entries, so rewrite
     * them while loading; the next persistence pass writes only the versioned identity.
     */
    private static String migrateLegacyKey(String type, String key) {
        if (key == null || key.isBlank() || "S".equals(type)) {
            return key;
        }
        if ("F".equals(type)) {
            return key.startsWith("HINT|") || key.startsWith("QUERY_REWRITE|") ? key : "HINT|" + key;
        }
        if ("SRO".equals(type)) {
            int delimiter = key.indexOf('\u0000');
            if (delimiter < 0) {
                return key;
            }
            return key.substring(0, delimiter + 1) + migrateLegacyMutationIdentity(key.substring(delimiter + 1));
        }
        return migrateLegacyMutationIdentity(key);
    }

    private static String migrateLegacyMutationIdentity(String key) {
        if (key.startsWith("HINT|") || key.startsWith("QUERY_REWRITE|")) {
            return key;
        }
        int segmentEnd = key.indexOf(';');
        String identity = segmentEnd < 0 ? key : key.substring(0, segmentEnd);
        int separator = identity.indexOf(':');
        if (separator > 0 && separator < identity.length() - 1) {
            return "HINT|" + identity.substring(0, separator) + "|" + identity.substring(separator + 1)
                    + (segmentEnd < 0 ? "" : key.substring(segmentEnd));
        }
        return "HINT|" + key;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    static final class SkeletonDecision {
        private final boolean shouldSkip;
        private final long historicalCount;
        private final double skipProbability;

        SkeletonDecision(boolean shouldSkip, long historicalCount, double skipProbability) {
            this.shouldSkip = shouldSkip;
            this.historicalCount = historicalCount;
            this.skipProbability = skipProbability;
        }

        boolean shouldSkip() {
            return shouldSkip;
        }

        long getHistoricalCount() {
            return historicalCount;
        }

        double getSkipProbability() {
            return skipProbability;
        }
    }

    static final class WitnessDecision {
        private final boolean shouldWriteReport;
        private final long skeletonObservations;
        private final long familyObservations;
        private final long signatureObservations;
        private final long signatureReports;
        private final long rootCauseObservations;
        private final long rootCauseReports;
        private final String selectionReason;
        private final ReportReservation reservation;

        WitnessDecision(boolean shouldWriteReport, long skeletonObservations, long familyObservations,
                long signatureObservations, long signatureReports, long rootCauseObservations, long rootCauseReports,
                String selectionReason, ReportReservation reservation) {
            this.shouldWriteReport = shouldWriteReport;
            this.skeletonObservations = skeletonObservations;
            this.familyObservations = familyObservations;
            this.signatureObservations = signatureObservations;
            this.signatureReports = signatureReports;
            this.rootCauseObservations = rootCauseObservations;
            this.rootCauseReports = rootCauseReports;
            this.selectionReason = selectionReason;
            this.reservation = reservation;
        }

        boolean shouldWriteReport() {
            return shouldWriteReport;
        }

        String getSelectionReason() {
            return selectionReason;
        }

        long getSkeletonObservations() {
            return skeletonObservations;
        }

        long getFamilyObservations() {
            return familyObservations;
        }

        long getSignatureObservations() {
            return signatureObservations;
        }

        long getSignatureReports() {
            return signatureReports;
        }

        long getRootCauseObservations() {
            return rootCauseObservations;
        }

        long getRootCauseReports() {
            return rootCauseReports;
        }

        ReportReservation getReservation() {
            return reservation;
        }
    }

    static final class ReportReservation {
        private final String skeletonKey;
        private final String familyKey;
        private final String signatureKey;
        private final String rootCauseKey;

        ReportReservation(String skeletonKey, String familyKey, String signatureKey, String rootCauseKey) {
            this.skeletonKey = skeletonKey;
            this.familyKey = familyKey;
            this.signatureKey = signatureKey;
            this.rootCauseKey = rootCauseKey;
        }
    }
}
