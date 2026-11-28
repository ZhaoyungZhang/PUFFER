package sqlancer.cockroachdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;
import sqlancer.cockroachdb.CockroachDBOptions.CockroachDBOracleFactory;
import sqlancer.cockroachdb.CockroachDBProvider.CockroachDBGlobalState;

public class TestCockroachDBPerformanceDataAblationOptions {

    @Test
    public void testProfilesAndCostAnomalyThreshold() {
        assertEquals(PerformanceHintDataAblationProfile.NONE, new CockroachDBOptions().performanceDataAblationProfile);
        assertEquals(10.0, new CockroachDBOptions().performanceReportCostAnomalyRatio);
        assertEquals(0.5, new CockroachDBOptions().performancePredicateBindingProbability, 1e-9);
        for (String prob : java.util.List.of("0.0", "0.1", "0.25", "0.5", "0.75", "1.0")) {
            CockroachDBOptions parsed = new CockroachDBOptions();
            JCommander.newBuilder().addObject(parsed).build().parse("--perf-predicate-binding-probability", prob);
            assertEquals(Double.parseDouble(prob), parsed.performancePredicateBindingProbability, 1e-9);
        }
        for (PerformanceHintDataAblationProfile profile : PerformanceHintDataAblationProfile.values()) {
            CockroachDBOptions options = new CockroachDBOptions();
            JCommander.newBuilder().addObject(options).build().parse("--perf-data-ablation-profile", profile.getId());
            assertEquals(profile, options.performanceDataAblationProfile);
        }
        assertThrows(ParameterException.class, () -> JCommander.newBuilder().addObject(new CockroachDBOptions()).build()
                .parse("--perf-data-ablation-profile", "column-typo"));
    }

    @Test
    public void testProfileCannotBeSilentlyIgnoredByLegacyGeneratorOrOtherOracle() {
        CockroachDBOptions options = new CockroachDBOptions();
        options.oracle = CockroachDBOracleFactory.PERFORMANCE_HINT;
        options.performanceDataGeneratorMode = "legacy";
        options.performanceDataAblationProfile = PerformanceHintDataAblationProfile.RANDOM;
        CockroachDBGlobalState state = new CockroachDBGlobalState();
        state.setDbmsSpecificOptions(options);
        assertThrows(IllegalArgumentException.class, () -> new CockroachDBProvider().generateDatabase(state));

        options.performanceDataGeneratorMode = "model-v1";
        options.oracle = CockroachDBOracleFactory.NOREC;
        assertThrows(IllegalArgumentException.class, () -> new CockroachDBProvider().generateDatabase(state));
    }
}
