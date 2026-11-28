package sqlancer.cockroachdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sqlancer.common.perfhint.PerformanceHintReportSnapshot;

public class TestCockroachDBPerformanceHintReporting {

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

        CockroachDBPerformanceHintOracleUtils.writeSnapshot(snapshot);

        assertEquals("CREATE TABLE t0(c0 INT);\n",
                Files.readString(reportDirectory.resolve("setup.sql"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(reportDirectory.resolve("unexpected.txt")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getFiles().put("bad", "bad"));
    }
}
