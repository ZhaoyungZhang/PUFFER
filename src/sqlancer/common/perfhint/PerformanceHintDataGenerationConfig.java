package sqlancer.common.perfhint;

import java.util.Objects;

public final class PerformanceHintDataGenerationConfig {

    public static final String LEGACY_MODE = "legacy";
    public static final String MODEL_V1_MODE = "model-v1";

    private final int minTables;
    private final int maxTables;
    private final int minRowsPerTable;
    private final int maxRowsPerTable;
    private final int minColumns;
    private final int maxColumns;
    private final int maxIndexesPerTable;
    private final double singleColumnModelProbability;
    private final double multiColumnModelProbability;
    private final double interTableModelProbability;
    private final PerformanceHintDataAblationProfile ablationProfile;
    private final int initialScale;

    public PerformanceHintDataGenerationConfig(int minTables, int maxTables, int minRowsPerTable, int maxRowsPerTable,
            int minColumns, int maxColumns, int maxIndexesPerTable, double singleColumnModelProbability,
            double multiColumnModelProbability, double interTableModelProbability) {
        this(minTables, maxTables, minRowsPerTable, maxRowsPerTable, minColumns, maxColumns, maxIndexesPerTable,
                singleColumnModelProbability, multiColumnModelProbability, interTableModelProbability,
                PerformanceHintDataAblationProfile.NONE, 1);
    }

    public PerformanceHintDataGenerationConfig(int minTables, int maxTables, int minRowsPerTable, int maxRowsPerTable,
            int minColumns, int maxColumns, int maxIndexesPerTable, double singleColumnModelProbability,
            double multiColumnModelProbability, double interTableModelProbability,
            PerformanceHintDataAblationProfile ablationProfile) {
        this(minTables, maxTables, minRowsPerTable, maxRowsPerTable, minColumns, maxColumns, maxIndexesPerTable,
                singleColumnModelProbability, multiColumnModelProbability, interTableModelProbability, ablationProfile,
                1);
    }

    public PerformanceHintDataGenerationConfig(int minTables, int maxTables, int minRowsPerTable, int maxRowsPerTable,
            int minColumns, int maxColumns, int maxIndexesPerTable, double singleColumnModelProbability,
            double multiColumnModelProbability, double interTableModelProbability,
            PerformanceHintDataAblationProfile ablationProfile, int initialScale) {
        this.minTables = requirePositive("--perf-min-tables", minTables);
        this.maxTables = requireAtLeast("--perf-max-tables", maxTables, this.minTables);
        this.minRowsPerTable = requirePositive("--perf-min-rows-per-table", minRowsPerTable);
        this.maxRowsPerTable = requireAtLeast("--perf-max-rows-per-table", maxRowsPerTable, this.minRowsPerTable);
        this.minColumns = requireRange("--perf-min-columns", minColumns, 3, 8);
        this.maxColumns = requireRange("--perf-max-columns", maxColumns, this.minColumns, 8);
        this.maxIndexesPerTable = requireRange("--perf-max-indexes-per-table", maxIndexesPerTable, 1, 8);
        this.singleColumnModelProbability = requireProbability("--perf-model-single-column-probability",
                singleColumnModelProbability);
        this.multiColumnModelProbability = requireProbability("--perf-model-multi-column-probability",
                multiColumnModelProbability);
        this.interTableModelProbability = requireProbability("--perf-model-inter-table-probability",
                interTableModelProbability);
        this.ablationProfile = Objects.requireNonNull(ablationProfile);
        this.initialScale = requirePositive("--perf-initial-scale", initialScale);
    }

    public static boolean isModelV1(String mode) {
        String normalizedMode = mode == null ? LEGACY_MODE : mode.trim().toLowerCase();
        if (!LEGACY_MODE.equals(normalizedMode) && !MODEL_V1_MODE.equals(normalizedMode)) {
            throw new IllegalArgumentException(
                    "unsupported --perf-data-generator-mode: " + mode + " (expected legacy or model-v1)");
        }
        return MODEL_V1_MODE.equals(normalizedMode);
    }

    public int getMinTables() {
        return minTables;
    }

    public int getMaxTables() {
        return maxTables;
    }

    public int getMinRowsPerTable() {
        return minRowsPerTable;
    }

    public int getMaxRowsPerTable() {
        return maxRowsPerTable;
    }

    public int getMinColumns() {
        return minColumns;
    }

    public int getMaxColumns() {
        return maxColumns;
    }

    public int getMaxIndexesPerTable() {
        return maxIndexesPerTable;
    }

    public double getSingleColumnModelProbability() {
        return singleColumnModelProbability;
    }

    public double getMultiColumnModelProbability() {
        return multiColumnModelProbability;
    }

    public double getInterTableModelProbability() {
        return interTableModelProbability;
    }

    public PerformanceHintDataAblationProfile getAblationProfile() {
        return ablationProfile;
    }

    public int getInitialScale() {
        return initialScale;
    }

    private static int requirePositive(String option, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(option + " must be at least 1");
        }
        return value;
    }

    private static int requireAtLeast(String option, int value, int minimum) {
        if (value < minimum) {
            throw new IllegalArgumentException(option + " must be at least " + minimum);
        }
        return value;
    }

    private static int requireRange(String option, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(option + " must be in [" + minimum + ", " + maximum + "]");
        }
        return value;
    }

    private static double requireProbability(String option, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(option + " must be in [0.0, 1.0]");
        }
        return value;
    }
}
