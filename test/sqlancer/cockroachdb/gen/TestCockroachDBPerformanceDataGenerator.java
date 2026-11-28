package sqlancer.cockroachdb.gen.datagen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.common.perfhint.PerformanceHintDataModel.ColumnDistributionType;
import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;
import sqlancer.common.perfhint.PerformanceHintDataPlan.ColumnPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.TablePlan;

public class TestCockroachDBPerformanceDataGenerator {

    @Test
    public void testBatchSizeOnlyChangesInsertBoundaries() {
        TablePlan table = new TablePlan("t0", 7,
                List.of(new ColumnPlan("c0", SqlType.BIGINT, ColumnDistributionType.UNIFORM),
                        new ColumnPlan("c1", SqlType.VARCHAR, ColumnDistributionType.UNIFORM),
                        new ColumnPlan("c2", SqlType.DOUBLE, ColumnDistributionType.UNIFORM)));
        List<List<Object>> rows = List.of(List.of(1L, "alpha", 1.5), List.of(2L, "beta", 2.5),
                List.of(3L, "gamma", 3.5), List.of(4L, "delta", 4.5), List.of(5L, "epsilon", 5.5),
                List.of(6L, "zeta", 6.5), List.of(7L, "eta", 7.5));

        List<String> singleRowTuples = renderTuples(table, rows, 1);
        assertEquals(singleRowTuples, renderTuples(table, rows, 1000));
        assertEquals(singleRowTuples, renderTuples(table, rows, 2000));
        assertEquals(7, statementCount(table, rows, 1));
        assertEquals(1, statementCount(table, rows, 1000));
    }

    private static int statementCount(TablePlan table, List<List<Object>> rows, int batchSize) {
        int count = 0;
        for (int start = 0; start < rows.size(); start += batchSize) {
            CockroachDBPerformanceDataGenerator.buildInsertStatement(table, rows, start,
                    Math.min(rows.size(), start + batchSize));
            count++;
        }
        return count;
    }

    private static List<String> renderTuples(TablePlan table, List<List<Object>> rows, int batchSize) {
        List<String> tuples = new ArrayList<>();
        for (int start = 0; start < rows.size(); start += batchSize) {
            String statement = CockroachDBPerformanceDataGenerator.buildInsertStatement(table, rows, start,
                    Math.min(rows.size(), start + batchSize));
            String values = statement.substring(statement.indexOf(" VALUES ") + " VALUES ".length());
            String body = values.substring(1, values.length() - 1);
            tuples.addAll(List.of(body.split("\\), \\(")));
        }
        return tuples;
    }
}
