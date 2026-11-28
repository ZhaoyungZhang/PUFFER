package sqlancer.common.perfhint;

import java.util.Locale;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.ParameterException;

/** Data-only ablations of one shared model-v1 schema and index plan. */
public enum PerformanceHintDataAblationProfile {
    NONE("none", true, true, true), RANDOM("random", false, false, false), COLUMN("column", true, false, false),
    COLUMN_INTRA("column-intra", true, true, false), COLUMN_INTER("column-inter", true, false, true),
    FULL("full", true, true, true);

    private final String id;
    private final boolean columnModels;
    private final boolean intraTableModels;
    private final boolean interTableModels;

    PerformanceHintDataAblationProfile(String id, boolean columnModels, boolean intraTableModels,
            boolean interTableModels) {
        this.id = id;
        this.columnModels = columnModels;
        this.intraTableModels = intraTableModels;
        this.interTableModels = interTableModels;
    }

    public String getId() {
        return id;
    }

    public boolean includesColumnModels() {
        return columnModels;
    }

    public boolean includesIntraTableModels() {
        return intraTableModels;
    }

    public boolean includesInterTableModels() {
        return interTableModels;
    }

    public static final class Converter implements IStringConverter<PerformanceHintDataAblationProfile> {
        @Override
        public PerformanceHintDataAblationProfile convert(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (PerformanceHintDataAblationProfile profile : values()) {
                if (profile.id.equals(normalized)) {
                    return profile;
                }
            }
            throw new ParameterException("--perf-data-ablation-profile must be one of: "
                    + "none, random, column, column-intra, column-inter, full");
        }
    }
}
