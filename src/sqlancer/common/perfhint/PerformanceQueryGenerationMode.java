package sqlancer.common.perfhint;

import java.util.Locale;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.ParameterException;

import sqlancer.Randomly;

/** Query sampling is independent of the data ablation profile. */
public enum PerformanceQueryGenerationMode {
    STRUCTURED, EXPANDED, MIXED;

    public enum Family {
        STRUCTURED, EXPRESSION, AGGREGATE, JOIN
    }

    public Family chooseFamily() {
        // In particular, the legacy mode must not consume a random number here.
        if (this == STRUCTURED || this == MIXED && Randomly.getBoolean()) {
            return Family.STRUCTURED;
        }
        return Randomly.fromOptions(Family.EXPRESSION, Family.AGGREGATE, Family.JOIN);
    }

    public String getId() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static final class Converter implements IStringConverter<PerformanceQueryGenerationMode> {
        @Override
        public PerformanceQueryGenerationMode convert(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ParameterException("--perf-query-generator-mode must be structured, expanded, or mixed", e);
            }
        }
    }
}
