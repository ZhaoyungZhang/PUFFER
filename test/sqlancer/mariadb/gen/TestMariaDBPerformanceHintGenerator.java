package sqlancer.mariadb.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.JCommander;

import sqlancer.Randomly;
import sqlancer.cockroachdb.CockroachDBOptions;
import sqlancer.mariadb.MariaDBOptions;
import sqlancer.mariadb.MariaDBSchema.MariaDBColumn;
import sqlancer.mariadb.MariaDBSchema.MariaDBDataType;
import sqlancer.mariadb.MariaDBSchema.MariaDBIndex;
import sqlancer.mariadb.MariaDBSchema.MariaDBTable;
import sqlancer.mariadb.MariaDBSchema.MariaDBTable.MariaDBEngine;
import sqlancer.mariadb.gen.MariaDBPerformanceHintGenerator.HintedQueryCandidate;
import sqlancer.mysql.MySQLOptions;
import sqlancer.oceanbase.OceanBaseOptions;
import sqlancer.tidb.TiDBOptions;

public class TestMariaDBPerformanceHintGenerator {

    @Test
    public void testExcludesBkaByDefaultAndAllowsOptIn() {
        MariaDBColumn col = new MariaDBColumn("c0", MariaDBDataType.INT, false, 4);
        MariaDBTable t0 = new MariaDBTable("t0", List.of(col), List.of(MariaDBIndex.create("i0", List.of("c0"))),
                MariaDBEngine.INNO_DB);
        MariaDBTable t1 = new MariaDBTable("t1", List.of(col), List.of(MariaDBIndex.create("i1", List.of("c0"))),
                MariaDBEngine.INNO_DB);
        List<MariaDBTable> tables = List.of(t0, t1);

        List<HintedQueryCandidate> defaultCandidates = MariaDBPerformanceHintGenerator.generateCandidates(tables,
                new Randomly(1L), 3, 16);
        assertFalse(defaultCandidates.stream().anyMatch(c -> "BKA".equals(c.getHintType())),
                "MariaDB default join_cache_level=2 must exclude BKA hint");
        assertTrue(defaultCandidates.stream().anyMatch(c -> "BNL".equals(c.getHintType())));

        List<HintedQueryCandidate> optInCandidates = MariaDBPerformanceHintGenerator.generateCandidates(tables,
                new Randomly(1L), 3, 16, true);
        assertTrue(optInCandidates.stream().anyMatch(c -> "BKA".equals(c.getHintType())),
                "MariaDB BKA hint should be available when explicitly enabled");
    }

    @Test
    public void testOptionsParseInitialScaleAndMariaDBAllowBka() {
        MariaDBOptions maria = new MariaDBOptions();
        assertFalse(maria.performanceAllowBkaHint);
        assertEquals(1, maria.performanceInitialScale);
        JCommander.newBuilder().addObject(maria).build().parse("--perf-allow-bka-hint", "true", "--perf-initial-scale",
                "10");
        assertTrue(maria.performanceAllowBkaHint);
        assertEquals(10, maria.performanceInitialScale);

        MySQLOptions mysql = new MySQLOptions();
        JCommander.newBuilder().addObject(mysql).build().parse("--perf-initial-scale", "10");
        assertEquals(10, mysql.performanceInitialScale);

        TiDBOptions tidb = new TiDBOptions();
        JCommander.newBuilder().addObject(tidb).build().parse("--perf-initial-scale", "10");
        assertEquals(10, tidb.performanceInitialScale);

        CockroachDBOptions crdb = new CockroachDBOptions();
        JCommander.newBuilder().addObject(crdb).build().parse("--perf-initial-scale", "10");
        assertEquals(10, crdb.performanceInitialScale);

        OceanBaseOptions ob = new OceanBaseOptions();
        JCommander.newBuilder().addObject(ob).build().parse("--perf-initial-scale", "10");
        assertEquals(10, ob.performanceInitialScale);
    }
}
