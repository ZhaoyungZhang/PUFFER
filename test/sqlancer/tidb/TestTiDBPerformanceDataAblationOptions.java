package sqlancer.tidb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;
import sqlancer.tidb.TiDBOptions.TiDBOracleFactory;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;

public class TestTiDBPerformanceDataAblationOptions {

    @Test
    public void testProfilesAndCostAnomalyThreshold() {
        assertEquals(PerformanceHintDataAblationProfile.NONE, new TiDBOptions().performanceDataAblationProfile);
        assertEquals(5.0, new TiDBOptions().performanceReportCostAnomalyRatio);
        assertEquals(0.5, new TiDBOptions().performancePredicateBindingProbability, 1e-9);
        for (String prob : List.of("0.0", "0.1", "0.25", "0.5", "0.75", "1.0")) {
            TiDBOptions parsed = new TiDBOptions();
            JCommander.newBuilder().addObject(parsed).build().parse("--perf-predicate-binding-probability", prob);
            assertEquals(Double.parseDouble(prob), parsed.performancePredicateBindingProbability, 1e-9);
        }
        for (PerformanceHintDataAblationProfile profile : PerformanceHintDataAblationProfile.values()) {
            TiDBOptions options = new TiDBOptions();
            JCommander.newBuilder().addObject(options).build().parse("--perf-data-ablation-profile", profile.getId());
            assertEquals(profile, options.performanceDataAblationProfile);
        }
        assertThrows(ParameterException.class, () -> JCommander.newBuilder().addObject(new TiDBOptions()).build()
                .parse("--perf-data-ablation-profile", "column-typo"));
    }

    @Test
    public void testProfileCannotBeSilentlyIgnoredByLegacyGeneratorOrOtherOracle() {
        TiDBOptions options = new TiDBOptions();
        options.oracle = List.of(TiDBOracleFactory.PERFORMANCE_HINT);
        options.performanceDataAblationProfile = PerformanceHintDataAblationProfile.RANDOM;
        TiDBGlobalState state = new TiDBGlobalState();
        state.setDbmsSpecificOptions(options);
        assertThrows(IllegalArgumentException.class, () -> new TiDBProvider().generateDatabase(state));

        options.performanceDataGeneratorMode = "model-v1";
        options.oracle = List.of(TiDBOracleFactory.WHERE);
        assertThrows(IllegalArgumentException.class, () -> new TiDBProvider().generateDatabase(state));
    }
}
