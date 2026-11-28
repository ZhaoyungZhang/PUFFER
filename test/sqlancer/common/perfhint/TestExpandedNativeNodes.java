package sqlancer.common.perfhint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.cockroachdb.CockroachDBVisitor;
import sqlancer.cockroachdb.ast.CockroachDBAggregate;
import sqlancer.cockroachdb.ast.CockroachDBConstant;
import sqlancer.mariadb.ast.MariaDBAggregate;
import sqlancer.mariadb.ast.MariaDBConstant;
import sqlancer.mariadb.ast.MariaDBVisitor;
import sqlancer.mysql.MySQLVisitor;
import sqlancer.mysql.ast.MySQLAggregate;
import sqlancer.mysql.ast.MySQLCaseOperator;
import sqlancer.mysql.ast.MySQLConstant;
import sqlancer.oceanbase.OceanBaseVisitor;
import sqlancer.oceanbase.ast.OceanBaseAggregate;
import sqlancer.oceanbase.ast.OceanBaseConstant;
import sqlancer.tidb.ast.TiDBAggregate;
import sqlancer.tidb.ast.TiDBConstant;
import sqlancer.tidb.visitor.TiDBVisitor;

public class TestExpandedNativeNodes {
    @Test
    public void forceAllAggregateNodesAndMysqlDistinctVariants() {
        for (String name : List.of("COUNT", "SUM", "MIN", "MAX")) {
            String expected = name + "(1)";
            assertEquals(expected, MySQLVisitor.asString(new MySQLAggregate(List.of(MySQLConstant.createIntConstant(1)),
                    MySQLAggregate.MySQLAggregateFunction.valueOf(name))));
            assertEquals(expected, MariaDBVisitor.asString(new MariaDBAggregate(MariaDBConstant.createIntConstant(1),
                    MariaDBAggregate.MariaDBAggregateFunction.valueOf(name))));
            assertEquals(expected, TiDBVisitor.asString(new TiDBAggregate(List.of(TiDBConstant.createIntConstant(1)),
                    TiDBAggregate.TiDBAggregateFunction.valueOf(name))));
            assertEquals(expected,
                    CockroachDBVisitor.asString(
                            new CockroachDBAggregate(CockroachDBAggregate.CockroachDBAggregateFunction.valueOf(name),
                                    List.of(CockroachDBConstant.createIntConstant(1)))));
            assertEquals(name + "(7)",
                    OceanBaseVisitor.asString(new OceanBaseAggregate(OceanBaseConstant.createIntConstant(7),
                            OceanBaseAggregate.OceanBaseAggregateFunction.valueOf(name))));
            MySQLAggregate distinct = new MySQLAggregate(List.of(MySQLConstant.createIntConstant(1)),
                    MySQLAggregate.MySQLAggregateFunction.valueOf(name + "_DISTINCT"));
            assertEquals(name + "(DISTINCT 1)", MySQLVisitor.asString(distinct));
            assertTrue(MySQLVisitor.asExpectedValues(distinct).contains("1"));
        }
    }

    @Test
    public void casePreservesNullAndFallbackSemanticsAndBothVisitors() {
        MySQLConstant one = MySQLConstant.createIntConstant(1);
        MySQLConstant two = MySQLConstant.createIntConstant(2);
        MySQLConstant nullValue = MySQLConstant.createNullConstant();
        MySQLCaseOperator searched = new MySQLCaseOperator(null, List.of(nullValue, one), List.of(one, two), one);
        assertEquals(2, searched.getExpectedValue().getInt());
        assertTrue(MySQLVisitor.asString(searched).contains("WHEN NULL THEN 1"));
        assertTrue(MySQLVisitor.asExpectedValues(searched).contains("CASE"));
        MySQLCaseOperator simple = new MySQLCaseOperator(two, List.of(one, two), List.of(two, one), null);
        assertEquals(1, simple.getExpectedValue().getInt());
        assertTrue(new MySQLCaseOperator(null, List.of(nullValue), List.of(one), null).getExpectedValue().isNull());
        assertEquals(2, new MySQLCaseOperator(null, List.of(nullValue), List.of(one), two).getExpectedValue().getInt());
        assertThrows(IllegalArgumentException.class, () -> new MySQLCaseOperator(null, List.of(), List.of(one), two));
    }
}
