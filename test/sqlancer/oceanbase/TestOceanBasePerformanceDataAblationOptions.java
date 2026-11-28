package sqlancer.oceanbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;
import sqlancer.oceanbase.OceanBaseOptions.OceanBaseOracleFactory;

public class TestOceanBasePerformanceDataAblationOptions {

    @Test
    public void testProfilesAndExistingDefault() {
        assertEquals(PerformanceHintDataAblationProfile.NONE, new OceanBaseOptions().performanceDataAblationProfile);
        assertEquals(10.0, new OceanBaseOptions().performanceReportCostAnomalyRatio);
        assertEquals(0.5, new OceanBaseOptions().performancePredicateBindingProbability, 1e-9);
        for (String prob : List.of("0.0", "0.1", "0.25", "0.5", "0.75", "1.0")) {
            OceanBaseOptions parsed = new OceanBaseOptions();
            JCommander.newBuilder().addObject(parsed).build().parse("--perf-predicate-binding-probability", prob);
            assertEquals(Double.parseDouble(prob), parsed.performancePredicateBindingProbability, 1e-9);
        }
        for (PerformanceHintDataAblationProfile profile : PerformanceHintDataAblationProfile.values()) {
            OceanBaseOptions options = new OceanBaseOptions();
            JCommander.newBuilder().addObject(options).build().parse("--perf-data-ablation-profile", profile.getId());
            assertEquals(profile, options.performanceDataAblationProfile);
        }
        assertThrows(ParameterException.class, () -> JCommander.newBuilder().addObject(new OceanBaseOptions()).build()
                .parse("--perf-data-ablation-profile", "typo"));
    }

    @Test
    public void testProfileCannotBeSilentlyIgnored() {
        OceanBaseOptions options = new OceanBaseOptions();
        options.oracles = List.of(OceanBaseOracleFactory.PERFORMANCE_HINT);
        options.performanceDataGeneratorMode = "legacy";
        options.performanceDataAblationProfile = PerformanceHintDataAblationProfile.RANDOM;
        OceanBaseGlobalState state = new OceanBaseGlobalState();
        state.setDbmsSpecificOptions(options);
        assertThrows(IllegalArgumentException.class, () -> new OceanBaseProvider().generateDatabase(state));
        options.performanceDataGeneratorMode = "model-v1";
        options.oracles = List.of(OceanBaseOracleFactory.NoREC);
        assertThrows(IllegalArgumentException.class, () -> new OceanBaseProvider().generateDatabase(state));
    }
}
