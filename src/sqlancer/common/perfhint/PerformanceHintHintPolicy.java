package sqlancer.common.perfhint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class PerformanceHintHintPolicy {

    private PerformanceHintHintPolicy() {
    }

    public interface CandidateDescriptor {
        String getHintFamily();

        String getHintType();
    }

    public enum FamilyWeightMode {
        UNIFORM, FEEDBACK;

        public static FamilyWeightMode fromOption(String rawValue) {
            if (rawValue == null || rawValue.trim().isEmpty()) {
                return UNIFORM;
            }
            String normalized = rawValue.trim().toUpperCase().replace('-', '_');
            return FamilyWeightMode.valueOf(normalized);
        }
    }

    public static final class PolicyConfig {
        private final Set<String> includedHintTypes;
        private final Set<String> excludedHintTypes;
        private final int familyQuota;
        private final FamilyWeightMode weightMode;
        private final int maxCandidatesPerFamily;
        private final int maxTotalCandidates;

        public PolicyConfig(Collection<String> includedHintTypes, Collection<String> excludedHintTypes, int familyQuota,
                FamilyWeightMode weightMode, int maxCandidatesPerFamily, int maxTotalCandidates) {
            this.includedHintTypes = normalizeTypes(includedHintTypes);
            this.excludedHintTypes = normalizeTypes(excludedHintTypes);
            this.familyQuota = familyQuota;
            this.weightMode = weightMode;
            this.maxCandidatesPerFamily = maxCandidatesPerFamily;
            this.maxTotalCandidates = maxTotalCandidates;
        }

        public static PolicyConfig fromOptionStrings(String includeCsv, String excludeCsv, int familyQuota,
                String weightMode, int maxCandidatesPerFamily, int maxTotalCandidates) {
            return new PolicyConfig(splitCsv(includeCsv), splitCsv(excludeCsv), familyQuota,
                    FamilyWeightMode.fromOption(weightMode), maxCandidatesPerFamily, maxTotalCandidates);
        }

        public Set<String> getIncludedHintTypes() {
            return includedHintTypes;
        }

        public Set<String> getExcludedHintTypes() {
            return excludedHintTypes;
        }

        public int getFamilyQuota() {
            return familyQuota;
        }

        public FamilyWeightMode getWeightMode() {
            return weightMode;
        }

        public int getMaxCandidatesPerFamily() {
            return maxCandidatesPerFamily;
        }

        public int getMaxTotalCandidates() {
            return maxTotalCandidates;
        }
    }

    public static <T extends CandidateDescriptor> List<T> selectCandidates(List<T> candidates, PolicyConfig config) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<T> filtered = candidates.stream().filter(candidate -> isCandidateAllowed(candidate, config))
                .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            return List.of();
        }

        Map<String, List<T>> byHintType = new LinkedHashMap<>();
        for (T candidate : filtered) {
            byHintType.computeIfAbsent(candidate.getHintType(), key -> new ArrayList<>()).add(candidate);
        }

        Map<String, Integer> familyCounts = new LinkedHashMap<>();
        List<String> typeOrder = new ArrayList<>(byHintType.keySet());
        if (config.getWeightMode() == FamilyWeightMode.FEEDBACK) {
            Collections.sort(typeOrder);
        }

        List<T> selected = new ArrayList<>();
        int maxTotal = config.getMaxTotalCandidates() <= 0 ? Integer.MAX_VALUE : config.getMaxTotalCandidates();
        int maxPerFamily = config.getMaxCandidatesPerFamily() <= 0 ? Integer.MAX_VALUE
                : config.getMaxCandidatesPerFamily();
        int familyQuota = config.getFamilyQuota() <= 0 ? Integer.MAX_VALUE : config.getFamilyQuota();

        boolean progress = true;
        while (progress && selected.size() < maxTotal) {
            progress = false;
            for (String hintType : typeOrder) {
                List<T> typeCandidates = byHintType.get(hintType);
                if (typeCandidates.isEmpty()) {
                    continue;
                }
                T next = typeCandidates.get(0);
                String family = next.getHintFamily();
                int familyCount = familyCounts.getOrDefault(family, 0);
                if (familyCount >= familyQuota || familyCount >= maxPerFamily) {
                    typeCandidates.clear();
                    continue;
                }
                selected.add(typeCandidates.remove(0));
                familyCounts.put(family, familyCount + 1);
                progress = true;
                if (selected.size() >= maxTotal) {
                    break;
                }
            }
        }
        return selected;
    }

    public static String describePolicyDecision(PolicyConfig config) {
        String include = config.getIncludedHintTypes().isEmpty() ? "<all>"
                : String.join(",", config.getIncludedHintTypes());
        String exclude = config.getExcludedHintTypes().isEmpty() ? "<none>"
                : String.join(",", config.getExcludedHintTypes());
        return "include=" + include + ";exclude=" + exclude + ";familyWeight=" + config.getWeightMode().name()
                + ";familyQuota=" + config.getFamilyQuota() + ";maxPerFamily=" + config.getMaxCandidatesPerFamily()
                + ";maxTotal=" + config.getMaxTotalCandidates();
    }

    public static String describeAppliedQuota(PolicyConfig config, List<? extends CandidateDescriptor> candidates) {
        Map<String, Long> byFamily = candidates.stream().collect(
                Collectors.groupingBy(CandidateDescriptor::getHintFamily, LinkedHashMap::new, Collectors.counting()));
        return "familyQuota=" + config.getFamilyQuota() + ";maxPerFamily=" + config.getMaxCandidatesPerFamily()
                + ";selectedByFamily=" + byFamily;
    }

    private static boolean isCandidateAllowed(CandidateDescriptor candidate, PolicyConfig config) {
        String hintType = normalizeType(candidate.getHintType());
        if (!config.getIncludedHintTypes().isEmpty() && !config.getIncludedHintTypes().contains(hintType)) {
            return false;
        }
        return !config.getExcludedHintTypes().contains(hintType);
    }

    private static Set<String> normalizeTypes(Collection<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String type = normalizeType(value);
            if (!type.isEmpty()) {
                normalized.add(type);
            }
        }
        return normalized;
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return List.of();
        }
        String[] parts = csv.split(",");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }
}
