package sqlancer.common.perfhint;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** The paper-facing 3x3 cost/runtime outcome matrix shared by every backend. */
public enum PerformanceNineCellCategory {
    A_CHEAPER_FASTER("A", "missed_cheaper_and_faster_plan"), B_CHEAPER_SIMILAR("B", "latent_search_cost_inconsistency"),
    C_CHEAPER_SLOWER("C", "cost_misranking_benign_default"), D_SIMILAR_COST_FASTER("D", "harmful_cost_blind_spot"),
    E_SIMILAR_COST_SIMILAR_RUNTIME("E", "consistent_healthy"),
    F_SIMILAR_COST_SLOWER("F", "benign_cost_blind_spot_or_instability"),
    G_COSTLIER_FASTER("G", "harmful_cost_ranking_inversion"),
    H_COSTLIER_SIMILAR("H", "benign_over_pessimistic_costing"), I_COSTLIER_SLOWER("I", "consistent_healthy");

    private final String code;
    private final String summary;

    PerformanceNineCellCategory(String code, String summary) {
        this.code = code;
        this.summary = summary;
    }

    public String getCode() {
        return code;
    }

    public String getSummary() {
        return summary;
    }

    public boolean isConfirmedHarmful() {
        return this == A_CHEAPER_FASTER || this == D_SIMILAR_COST_FASTER || this == G_COSTLIER_FASTER;
    }

    public static Set<PerformanceNineCellCategory> parseReportCells(String configuredCells) {
        EnumSet<PerformanceNineCellCategory> result = EnumSet.noneOf(PerformanceNineCellCategory.class);
        if (configuredCells == null || configuredCells.isBlank()) {
            return result;
        }
        for (String rawCell : configuredCells.split(",")) {
            String cell = rawCell.trim().toUpperCase(Locale.ROOT);
            if (cell.isEmpty()) {
                continue;
            }
            PerformanceNineCellCategory category = fromCode(cell);
            if (category == null) {
                throw new IllegalArgumentException("Unknown PERFORMANCE_HINT nine-cell report category: " + rawCell);
            }
            result.add(category);
        }
        return result;
    }

    private static PerformanceNineCellCategory fromCode(String code) {
        for (PerformanceNineCellCategory category : values()) {
            if (category.code.equals(code)) {
                return category;
            }
        }
        return null;
    }
}
