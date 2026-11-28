package sqlancer.mariadb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;
import sqlancer.mariadb.MariaDBOptions.MariaDBOracleFactory;
import sqlancer.mariadb.MariaDBProvider.MariaDBGlobalState;

public class TestMariaDBPerformanceDataAblationOptions {

    @Test
    public void testProfilesAndCostAnomalyThreshold() {
        assertEquals(PerformanceHintDataAblationProfile.NONE, new MariaDBOptions().performanceDataAblationProfile);
        assertEquals(5.0, new MariaDBOptions().performanceReportCostAnomalyRatio);
        assertEquals(0.5, new MariaDBOptions().performancePredicateBindingProbability, 1e-9);
        for (String prob : List.of("0.0", "0.1", "0.25", "0.5", "0.75", "1.0")) {
            MariaDBOptions parsed = new MariaDBOptions();
            JCommander.newBuilder().addObject(parsed).build().parse("--perf-predicate-binding-probability", prob);
            assertEquals(Double.parseDouble(prob), parsed.performancePredicateBindingProbability, 1e-9);
        }
        for (PerformanceHintDataAblationProfile profile : PerformanceHintDataAblationProfile.values()) {
            MariaDBOptions options = new MariaDBOptions();
            JCommander.newBuilder().addObject(options).build().parse("--perf-data-ablation-profile", profile.getId());
            assertEquals(profile, options.performanceDataAblationProfile);
        }
        assertThrows(ParameterException.class, () -> JCommander.newBuilder().addObject(new MariaDBOptions()).build()
                .parse("--perf-data-ablation-profile", "column-typo"));
    }

    @Test
    public void testProfileCannotBeSilentlyIgnoredByLegacyGeneratorOrOtherOracle() {
        MariaDBOptions options = new MariaDBOptions();
        options.oracles = List.of(MariaDBOracleFactory.PERFORMANCE_HINT);
        options.performanceDataAblationProfile = PerformanceHintDataAblationProfile.RANDOM;
        MariaDBGlobalState state = new MariaDBGlobalState();
        state.setDbmsSpecificOptions(options);
        assertThrows(IllegalArgumentException.class, () -> new MariaDBProvider().generateDatabase(state));

        options.performanceDataGeneratorMode = "model-v1";
        options.oracles = List.of(MariaDBOracleFactory.NOREC);
        assertThrows(IllegalArgumentException.class, () -> new MariaDBProvider().generateDatabase(state));
    }
}
