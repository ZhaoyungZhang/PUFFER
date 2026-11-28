package sqlancer.common.qpg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

public class TestQPGPlanCanonicalizer {

    @Test
    public void testNormalizeMySQLLikeJsonPlanIgnoresObjectNamesAndPredicates() {
        String planA = "{" + "\"query\":\"select * from db1.t1 join db1.t0\"," + "\"query_plan\":{\"inputs\":["
                + "{\"operation\":\"Table scan on t1\",\"table_name\":\"t1\"},"
                + "{\"operation\":\"Covering index scan on t0 using i1\",\"table_name\":\"t0\",\"index_name\":\"i1\"},"
                + "{\"operation\":\"Filter: (t0.c1 > 10)\",\"condition\":\"(t0.c1 > 10)\"},"
                + "{\"operation\":\"Inner hash join (no condition)\",\"join_algorithm\":\"hash\"},"
                + "{\"operation\":\"Sort: t0.c2 DESC\",\"sort_fields\":[\"t0.c2 DESC\"]}" + "]}}";
        String planB = "{" + "\"query\":\"select * from db9.orders join db9.customer\","
                + "\"query_plan\":{\"inputs\":[" + "{\"operation\":\"Table scan on orders\",\"table_name\":\"orders\"},"
                + "{\"operation\":\"Covering index scan on customer using idx_customer_c1\",\"table_name\":\"customer\",\"index_name\":\"idx_customer_c1\"},"
                + "{\"operation\":\"Filter: (customer.c7 < 0)\",\"condition\":\"(customer.c7 < 0)\"},"
                + "{\"operation\":\"Inner hash join (eq_ref)\",\"join_algorithm\":\"hash\"},"
                + "{\"operation\":\"Sort: customer.c3 ASC\",\"sort_fields\":[\"customer.c3 ASC\"]}" + "]}}";

        assertEquals(QPGPlanCanonicalizer.normalizeMySQLLikeJsonPlan(planA),
                QPGPlanCanonicalizer.normalizeMySQLLikeJsonPlan(planB));
    }

    @Test
    public void testNormalizeMySQLLikeJsonPlanKeepsPhysicalOperatorDifferences() {
        String hashJoinPlan = "{" + "\"query_plan\":{\"inputs\":[" + "{\"operation\":\"Table scan on t1\"},"
                + "{\"operation\":\"Inner hash join (no condition)\"}" + "]}}";
        String nestedLoopPlan = "{" + "\"query_plan\":{\"inputs\":[" + "{\"operation\":\"Table scan on t1\"},"
                + "{\"operation\":\"Nested loop inner join\"}" + "]}}";

        assertNotEquals(QPGPlanCanonicalizer.normalizeMySQLLikeJsonPlan(hashJoinPlan),
                QPGPlanCanonicalizer.normalizeMySQLLikeJsonPlan(nestedLoopPlan));
    }

    @Test
    public void testNormalizeOceanBaseJsonPlanIgnoresVolatileAddresses() {
        String first = "{\"ID\":0,\"OPERATOR\":\"HASH JOIN\",\"NAME\":\"\",\"EST.ROWS\":4,"
                + "\"EST.TIME(us)\":6,\"CHILD\":[{\"ID\":1,\"OPERATOR\":\"TABLE RANGE SCAN\","
                + "\"NAME\":\"t0(i_c1)@0xabc\",\"EST.ROWS\":2,\"EST.TIME(us)\":3}]}";
        String second = "{\"ID\":9,\"OPERATOR\":\"HASH JOIN\",\"NAME\":\"\",\"EST.ROWS\":400,"
                + "\"EST.TIME(us)\":600,\"CHILD\":[{\"ID\":10,\"OPERATOR\":\"TABLE RANGE SCAN\","
                + "\"NAME\":\"t0(i_c1)@0xdef\",\"EST.ROWS\":200,\"EST.TIME(us)\":300}]}";

        assertEquals(QPGPlanCanonicalizer.normalizeOceanBaseJsonPlan(first),
                QPGPlanCanonicalizer.normalizeOceanBaseJsonPlan(second));
    }

    @Test
    public void testNormalizeOceanBaseJsonPlanKeepsOperatorDifferences() {
        String hashJoin = "{\"OPERATOR\":\"HASH JOIN\",\"NAME\":\"\"}";
        String nestedLoop = "{\"OPERATOR\":\"NESTED-LOOP JOIN\",\"NAME\":\"\"}";

        assertNotEquals(QPGPlanCanonicalizer.normalizeOceanBaseJsonPlan(hashJoin),
                QPGPlanCanonicalizer.normalizeOceanBaseJsonPlan(nestedLoop));
    }

}
