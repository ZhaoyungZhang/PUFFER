package sqlancer.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.mysql.MySQLSchema.MySQLColumn;
import sqlancer.mysql.MySQLSchema.MySQLIndex;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.MySQLSchema.MySQLTable.MySQLEngine;

public class TestMySQLPerformanceHintSupport {

    @Test
    public void testBuildStatisticsRefreshStatements() {
        MySQLTable t0 = new MySQLTable("t0",
                List.of(new MySQLColumn("c0", null, false, 0), new MySQLColumn("c1", null, false, 0)),
                List.of(MySQLIndex.create("idx0")), MySQLEngine.INNO_DB);
        MySQLTable t1 = new MySQLTable("t1", List.of(new MySQLColumn("c2", null, false, 0)), List.of(),
                MySQLEngine.INNO_DB);

        List<String> statements = MySQLPerformanceHintSupport.buildStatisticsRefreshStatements(List.of(t0, t1, t0));

        assertEquals(List.of("SET SESSION information_schema_stats_expiry = 0", "SET SESSION optimizer_prune_level = 0",
                "ANALYZE TABLE t0, t1", "ANALYZE TABLE t0 UPDATE HISTOGRAM ON c0, c1",
                "ANALYZE TABLE t1 UPDATE HISTOGRAM ON c2"), statements);
    }

    @Test
    public void testBuildStatisticsRefreshStatementsWithHistogramLimit() {
        MySQLTable t0 = new MySQLTable("t0",
                List.of(new MySQLColumn("c0", null, true, 0), new MySQLColumn("c1", null, false, 0),
                        new MySQLColumn("c2", null, false, 0)),
                List.of(MySQLIndex.create("idx0")), MySQLEngine.INNO_DB);
        MySQLTable t1 = new MySQLTable("t1",
                List.of(new MySQLColumn("c3", null, false, 0), new MySQLColumn("c4", null, false, 0)), List.of(),
                MySQLEngine.INNO_DB);

        List<String> statements = MySQLPerformanceHintSupport.buildStatisticsRefreshStatements(List.of(t0, t1), true,
                2);

        assertEquals(List.of("SET SESSION information_schema_stats_expiry = 0", "SET SESSION optimizer_prune_level = 0",
                "ANALYZE TABLE t0, t1", "ANALYZE TABLE t0 UPDATE HISTOGRAM ON c0, c1",
                "ANALYZE TABLE t1 UPDATE HISTOGRAM ON c3, c4"), statements);
    }

    @Test
    public void testBuildStatisticsRefreshStatementsWithoutHistograms() {
        MySQLTable t0 = new MySQLTable("t0",
                List.of(new MySQLColumn("c0", null, false, 0), new MySQLColumn("c1", null, false, 0)),
                List.of(MySQLIndex.create("idx0")), MySQLEngine.INNO_DB);

        List<String> statements = MySQLPerformanceHintSupport.buildStatisticsRefreshStatements(List.of(t0), false, 0);

        assertEquals(List.of("SET SESSION information_schema_stats_expiry = 0", "SET SESSION optimizer_prune_level = 0",
                "ANALYZE TABLE t0"), statements);
    }
}
