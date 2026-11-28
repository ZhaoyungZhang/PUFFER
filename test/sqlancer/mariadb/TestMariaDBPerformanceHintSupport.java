package sqlancer.mariadb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.mariadb.MariaDBSchema.MariaDBColumn;
import sqlancer.mariadb.MariaDBSchema.MariaDBDataType;
import sqlancer.mariadb.MariaDBSchema.MariaDBTable;
import sqlancer.mariadb.MariaDBSchema.MariaDBTable.MariaDBEngine;

public class TestMariaDBPerformanceHintSupport {

    @Test
    public void testBuildStatisticsRefreshStatements() {
        MariaDBTable t0 = new MariaDBTable("t0", List.of(new MariaDBColumn("c0", MariaDBDataType.INT, false, 0),
                new MariaDBColumn("c1", MariaDBDataType.VARCHAR, false, 0)), List.of(), MariaDBEngine.INNO_DB);
        MariaDBTable t1 = new MariaDBTable("t1", List.of(new MariaDBColumn("c2", MariaDBDataType.REAL, false, 0)),
                List.of(), MariaDBEngine.INNO_DB);

        List<String> statements = MariaDBPerformanceHintSupport.buildStatisticsRefreshStatements(List.of(t0, t1, t0));

        assertEquals(List.of("SET SESSION optimizer_prune_level = 0", "ANALYZE TABLE t0 PERSISTENT FOR ALL",
                "ANALYZE TABLE t1 PERSISTENT FOR ALL"), statements);
    }
}
