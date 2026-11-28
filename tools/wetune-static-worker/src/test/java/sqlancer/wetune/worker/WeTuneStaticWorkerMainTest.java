package sqlancer.wetune.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeTuneStaticWorkerMainTest {

    @Test
    void loadsThePinnedCompleteRuleBank() throws Exception {
        assertEquals(WeTuneStaticWorkerMain.RULE_COUNT, WeTuneStaticWorkerMain.loadRules().size());
    }

    @Test
    void parsesCockroachDbPostgreSqlSchemaAndCommonSelectShapes() {
        String ddl = "CREATE TABLE \"t0\" (\"id\" BIGINT NOT NULL, \"name\" TEXT, PRIMARY KEY (\"id\"));"
                + " CREATE TABLE \"t1\" (\"id\" BIGINT NOT NULL, \"t0_id\" BIGINT, PRIMARY KEY (\"id\"));"
                + " CREATE INDEX \"idx_t1_t0\" ON \"t1\" (\"t0_id\");";
        assertTrue(WeTuneStaticWorkerMain.supportsCockroachPostgreSql(ddl,
                "SELECT DISTINCT t0.name, t1.id FROM t0 INNER JOIN t1 ON t0.id = t1.t0_id "
                        + "WHERE t0.id >= 1 ORDER BY t1.id LIMIT 8"));
    }
}
