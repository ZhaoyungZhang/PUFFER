package sqlancer.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;
import sqlancer.mysql.MySQLOptions.MySQLOracleFactory;

public class TestMySQLPerformanceDataAblationOptions {

    @Test
    public void testProfileArgumentsAndUnchangedDefault() {
        assertEquals(PerformanceHintDataAblationProfile.NONE, new MySQLOptions().performanceDataAblationProfile);
        assertEquals(3, new MySQLOptions().performanceMaxIndexesPerTable);
        assertEquals(5.0, new MySQLOptions().performanceReportCostAnomalyRatio);
        assertEquals(0.5, new MySQLOptions().performancePredicateBindingProbability, 1e-9);
        for (String prob : List.of("0.0", "0.1", "0.25", "0.5", "0.75", "1.0")) {
            MySQLOptions parsed = new MySQLOptions();
            JCommander.newBuilder().addObject(parsed).build().parse("--perf-predicate-binding-probability", prob);
            assertEquals(Double.parseDouble(prob), parsed.performancePredicateBindingProbability, 1e-9);
        }
        for (PerformanceHintDataAblationProfile profile : PerformanceHintDataAblationProfile.values()) {
            MySQLOptions options = new MySQLOptions();
            JCommander.newBuilder().addObject(options).build().parse("--perf-data-ablation-profile", profile.getId());
            assertEquals(profile, options.performanceDataAblationProfile);
        }
        assertThrows(ParameterException.class, () -> JCommander.newBuilder().addObject(new MySQLOptions()).build()
                .parse("--perf-data-ablation-profile", "column-typo"));
    }

    @Test
    public void testProfileCannotBeSilentlyIgnoredByLegacyGeneratorOrOtherOracle() {
        MySQLOptions options = new MySQLOptions();
        options.oracles = List.of(MySQLOracleFactory.PERFORMANCE_HINT);
        options.performanceDataAblationProfile = PerformanceHintDataAblationProfile.RANDOM;
        MySQLGlobalState state = new MySQLGlobalState();
        state.setDbmsSpecificOptions(options);
        assertThrows(IllegalArgumentException.class, () -> new MySQLProvider().generateDatabase(state));

        options.performanceDataGeneratorMode = "model-v1";
        options.oracles = List.of(MySQLOracleFactory.TLP_WHERE);
        assertThrows(IllegalArgumentException.class, () -> new MySQLProvider().generateDatabase(state));
    }
}
