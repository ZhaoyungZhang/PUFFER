package sqlancer.mysql.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sqlancer.StateToReproduce;
import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.common.perfhint.PerformanceNineCellCategory;
import sqlancer.mysql.MySQLGlobalState;
import sqlancer.mysql.MySQLOptions;
import sqlancer.mysql.MySQLProvider;

public class TestMySQLPerformanceHintReporting {

    @TempDir
    Path temporaryDirectory;

    @Test
    public void testSnapshotDoesNotObserveSourceMutation() throws Exception {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("setup.sql", "CREATE TABLE t0(c0 INT);\n");
        Path reportDirectory = temporaryDirectory.resolve("reports/case_immutable");
        PerformanceHintReportSnapshot snapshot = new PerformanceHintReportSnapshot(reportDirectory, source);
        source.put("setup.sql", "MUTATED\n");
        source.put("unexpected.txt", "bad");

        MySQLPerformanceHintOracleUtils.writeSnapshot(snapshot);

        assertEquals("CREATE TABLE t0(c0 INT);\n",
                Files.readString(reportDirectory.resolve("setup.sql"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(reportDirectory.resolve("unexpected.txt")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getFiles().put("bad", "bad"));
    }

    @Test
    public void testHarmfulAndLatentCandidatesHaveTheSameDirectoryLayout() {
        MySQLGlobalState state = new MySQLGlobalState();
        state.setDbmsSpecificOptions(new MySQLOptions());
        state.setState(new StateToReproduce("test_database", new MySQLProvider()));
        String query = "SELECT t0.c0 FROM t0";
        CounterfactualCandidate candidate = CounterfactualCandidate.hint(query, "TABLE", "NO_BNL", "test",
                List.of("t0"), List.of());
        MySQLPerformanceHintOracleUtils.PlanSummary plan = MySQLPerformanceHintOracleUtils
                .parsePlan("{\"query_block\":{\"cost_info\":{\"query_cost\":\"10\"}}}");
        for (PerformanceNineCellCategory category : List.of(PerformanceNineCellCategory.A_CHEAPER_FASTER,
                PerformanceNineCellCategory.B_CHEAPER_SIMILAR, PerformanceNineCellCategory.C_CHEAPER_SLOWER,
                PerformanceNineCellCategory.D_SIMILAR_COST_FASTER, PerformanceNineCellCategory.G_COSTLIER_FASTER)) {
            MySQLPerformanceHintOracleUtils.PerformanceHintWitness witness = new MySQLPerformanceHintOracleUtils.PerformanceHintWitness(
                    query, query, candidate, plan, plan, List.of(), true);
            witness.setNineCellCategory(category);
            Path directory = MySQLPerformanceHintOracleUtils.snapshotWitness(state, witness).getFinalReportDirectory();
            assertEquals(category.getCode(), directory.getParent().getFileName().toString());
            assertFalse(directory.toString().contains("diagnostic_cases"));
        }
    }
}
