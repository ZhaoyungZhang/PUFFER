package sqlancer.tidb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.common.schema.TableIndex;
import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBCompositeDataType;
import sqlancer.tidb.TiDBSchema.TiDBDataType;
import sqlancer.tidb.TiDBSchema.TiDBTable;

public class TestTiDBPerformanceHintSupport {

    @Test
    public void testBuildStatisticsRefreshStatements() {
        TiDBTable t0 = new TiDBTable("t0",
                List.of(new TiDBColumn("c0", new TiDBCompositeDataType(TiDBDataType.INT), false, true, false),
                        new TiDBColumn("c1", new TiDBCompositeDataType(TiDBDataType.TEXT), false, true, false)),
                List.of(TableIndex.create("i0")), false);
        TiDBTable t1 = new TiDBTable("t1",
                List.of(new TiDBColumn("c2", new TiDBCompositeDataType(TiDBDataType.FLOATING), false, true, false)),
                List.of(), false);

        List<String> statements = TiDBPerformanceHintSupport.buildStatisticsRefreshStatements(List.of(t0, t1, t0));

        assertEquals(List.of("SET SESSION tidb_enable_prepared_plan_cache = OFF",
                "SET SESSION tidb_enable_non_prepared_plan_cache = OFF", "SET SESSION tidb_analyze_version = 2",
                "ANALYZE TABLE t0, t1 ALL COLUMNS"), statements);
    }

    @Test
    public void testBuildStablePerformanceSchemaStatements() {
        String create = TiDBPerformanceHintSupport.buildCreateTableStatement(2);
        String insert = TiDBPerformanceHintSupport.buildInsertStatement(1, 3);

        assertTrue(create.startsWith("CREATE TABLE t2("));
        assertTrue(create.contains("PRIMARY KEY (c0)"));
        assertTrue(create.contains("KEY i2_c1 (c1)"));
        assertTrue(insert.startsWith("INSERT INTO t1(c0, c1, c2, c3, c4) VALUES "));
        assertTrue(insert.contains("(1001, NULL, NULL, NULL, TRUE)"));
        assertTrue(insert.contains("(1002, 1, 'grp_1', 100.5, FALSE)"));
    }
}
