package sqlancer.oceanbase.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sqlancer.common.perfhint.PerformanceHintReportSnapshot;
import sqlancer.Randomly;
import sqlancer.StateToReproduce;
import sqlancer.common.perfhint.CounterfactualCandidate;
import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;
import sqlancer.common.perfhint.PerformanceHintDataGenerationConfig;
import sqlancer.common.perfhint.PerformanceHintDataPlanner;
import sqlancer.common.perfhint.pdg.PdgIrContext;
import sqlancer.common.perfhint.pdg.PdgIrMaterializer;
import sqlancer.common.perfhint.pdg.PdgIrPlanner;
import sqlancer.common.perfhint.pdg.PdgMaterializedDatabase;
import sqlancer.common.perfhint.pdg.PdgSpecification;
import sqlancer.oceanbase.OceanBaseGlobalState;
import sqlancer.oceanbase.OceanBaseOptions;
import sqlancer.oceanbase.OceanBaseProvider;

public class TestOceanBasePerformanceHintReporting {

    @TempDir
    Path temporaryDirectory;

    @Test
    public void testAblationSnapshotSatisfiesOfflineContract() {
        OceanBaseGlobalState state = new OceanBaseGlobalState();
        OceanBaseOptions options = new OceanBaseOptions();
        options.performanceDataAblationProfile = PerformanceHintDataAblationProfile.COLUMN_INTER;
        state.setDbmsSpecificOptions(options);
        state.setState(new StateToReproduce("test_database", new OceanBaseProvider()));
        state.getState().databaseVersion = "5.7.25-OceanBase_CE-v4.5.0.0";
        PerformanceHintDataGenerationConfig config = new PerformanceHintDataGenerationConfig(2, 2, 10, 10, 3, 3, 1, 0.8,
                0.6, 0.8, options.performanceDataAblationProfile);
        PdgSpecification specification = PdgIrPlanner.resolve("oceanbase", 7,
                PerformanceHintDataPlanner.create(config, new Randomly(7)));
        PdgMaterializedDatabase data = PdgIrMaterializer.materialize(specification);
        state.setPdgIrContext(new PdgIrContext(specification, data.getProfiles()));
        String query = "SELECT c0 FROM t0";
        CounterfactualCandidate candidate = CounterfactualCandidate.hint(query, "TABLE", "FULL", "test", List.of("t0"),
                List.of());
        OceanBasePerformanceHintOracleUtils.PlanSummary plan = OceanBasePerformanceHintOracleUtils
                .parsePlan("{\"OPERATOR\":\"TABLE FULL SCAN\",\"NAME\":\"t0\",\"EST.TIME(us)\":10}");
        OceanBasePerformanceHintOracleUtils.PerformanceHintWitness witness = new OceanBasePerformanceHintOracleUtils.PerformanceHintWitness(
                query, query, candidate, plan, plan, List.of());
        witness.setRuntime(new OceanBasePerformanceHintOracleUtils.RuntimeSummary(List.of(10.0), 0),
                new OceanBasePerformanceHintOracleUtils.RuntimeSummary(List.of(2.0), 0), List.of());
        Map<String, String> files = OceanBasePerformanceHintOracleUtils.snapshotWitness(state, witness).getFiles();
        assertTrue(files.get("pdg_ir.yaml").contains("data_ablation_profile: column-inter"));
        String metrics = files.get("metrics.json");
        assertEquals(metrics, files.get("metadata.json"));
        assertTrue(metrics.contains("\"dbms\": \"oceanbase\""));
        assertTrue(metrics.contains("\"databaseVersion\": \"5.7.25-OceanBase_CE-v4.5.0.0\""));
        assertTrue(metrics.contains("\"dataAblationProfile\": \"column-inter\""));
        assertTrue(metrics.contains("\"runtimeRatio\": 5.0000"));
        assertTrue(metrics.contains("\"absoluteRuntimeGapMs\": 8.0000"));
        assertTrue(metrics.contains("\"defaultRuntimeMedianMs\": 10.0000"));
    }

    @Test
    public void testSnapshotIsImmutableAndPublishesAtomically() throws Exception {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("setup.sql", "CREATE TABLE t0(c0 INT);\n");
        Path reportDirectory = temporaryDirectory.resolve("reports").resolve("case_immutable");
        PerformanceHintReportSnapshot snapshot = new PerformanceHintReportSnapshot(reportDirectory, source);
        source.put("setup.sql", "MUTATED\n");
        source.put("unexpected.txt", "bad");

        OceanBasePerformanceHintOracleUtils.writeSnapshot(snapshot);

        assertEquals("CREATE TABLE t0(c0 INT);\n",
                Files.readString(reportDirectory.resolve("setup.sql"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(reportDirectory.resolve("unexpected.txt")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getFiles().put("bad", "bad"));
    }

    @Test
    public void testBoundedWriterDrainsReports() throws Exception {
        Path feedbackDirectory = temporaryDirectory.resolve("feedback");
        OceanBasePerformanceHintFeedbackManager feedback = OceanBasePerformanceHintFeedbackManager
                .forStateDirectory(feedbackDirectory);
        OceanBasePerformanceHintTelemetry telemetry = OceanBasePerformanceHintTelemetry
                .forStateDirectory(feedbackDirectory);
        OceanBasePerformanceHintReportWriter writer = OceanBasePerformanceHintReportWriter.createForTests(1,
                OceanBasePerformanceHintOracleUtils::writeSnapshot);
        try {
            for (int i = 0; i < 8; i++) {
                Path reportDirectory = temporaryDirectory.resolve("written").resolve("case_" + i);
                writer.submit(
                        new PerformanceHintReportSnapshot(reportDirectory,
                                Map.of("setup.sql", "CREATE TABLE t" + i + "(c INT);\n")),
                        new OceanBasePerformanceHintFeedbackManager.ReportReservation("s" + i, "f" + i, "p" + i,
                                "r" + i),
                        feedback, telemetry, 1, false);
            }
        } finally {
            writer.closeForTests();
        }
        for (int i = 0; i < 8; i++) {
            assertTrue(Files.exists(temporaryDirectory.resolve("written/case_" + i + "/setup.sql")));
        }
    }
}
