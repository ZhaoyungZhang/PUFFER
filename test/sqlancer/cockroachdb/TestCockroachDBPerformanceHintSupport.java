package sqlancer.cockroachdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.common.schema.TableIndex;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBColumn;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBCompositeDataType;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBDataType;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBTable;

public class TestCockroachDBPerformanceHintSupport {

    @Test
    public void testBuildStatisticsRefreshStatements() {
        CockroachDBTable t0 = new CockroachDBTable("t0", List.of(
                new CockroachDBColumn("c0", new CockroachDBCompositeDataType(CockroachDBDataType.INT), true, false)),
                List.of(TableIndex.create("idx0")), false);
        CockroachDBTable t1 = new CockroachDBTable("t1", List.of(
                new CockroachDBColumn("c1", new CockroachDBCompositeDataType(CockroachDBDataType.STRING), false, true)),
                List.of(), false);

        List<String> statements = CockroachDBPerformanceHintSupport
                .buildStatisticsRefreshStatements(List.of(t0, t1, t0));

        assertEquals(List.of("SET CLUSTER SETTING sql.stats.automatic_collection.enabled = 'off'", "ANALYZE t0",
                "ANALYZE t1"), statements);
    }

    @Test
    public void testBuildStablePerformanceSchemaStatements() {
        String create = CockroachDBPerformanceHintSupport.buildCreateTableStatement(2);
        String insert = CockroachDBPerformanceHintSupport.buildInsertStatement(1, 3);

        assertTrue(create.startsWith("CREATE TABLE t2("));
        assertTrue(create.contains("c0 INT8 PRIMARY KEY"));
        assertTrue(create.contains("INDEX i2_c1 (c1)"));
        assertTrue(insert.startsWith("INSERT INTO t1(c0, c1, c2, c3, c4) VALUES "));
        assertTrue(insert.contains("(1001, NULL, NULL, NULL, TRUE)"));
        assertTrue(insert.contains("(1002, 1, 'grp_1', 100.5, FALSE)"));
    }
}
