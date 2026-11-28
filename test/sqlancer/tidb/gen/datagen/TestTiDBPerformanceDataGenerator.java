package sqlancer.tidb.gen.datagen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.common.perfhint.PerformanceHintDataModel.ColumnDistributionType;
import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;
import sqlancer.common.perfhint.PerformanceHintDataPlan.ColumnPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.TablePlan;

public class TestTiDBPerformanceDataGenerator {

    @Test
    public void testBatchSizeOnlyChangesBoundaries() {
        TablePlan table = new TablePlan("t0", 5,
                List.of(new ColumnPlan("c0", SqlType.BIGINT, ColumnDistributionType.UNIFORM),
                        new ColumnPlan("c1", SqlType.VARCHAR, ColumnDistributionType.UNIFORM)));
        List<List<Object>> rows = List.of(List.of(1L, "a"), List.of(2L, "b"), List.of(3L, "c"), List.of(4L, "d"),
                List.of(5L, "e"));

        assertEquals(renderTuples(table, rows, 1), renderTuples(table, rows, 500));
        assertEquals(renderTuples(table, rows, 1), renderTuples(table, rows, 2000));
        assertEquals(5, statementCount(table, rows, 1));
        assertEquals(1, statementCount(table, rows, 500));
    }

    private static int statementCount(TablePlan table, List<List<Object>> rows, int batchSize) {
        int count = 0;
        for (int start = 0; start < rows.size(); start += batchSize) {
            TiDBPerformanceDataGenerator.buildInsertStatement(table, rows, start,
                    Math.min(rows.size(), start + batchSize));
            count++;
        }
        return count;
    }

    private static List<String> renderTuples(TablePlan table, List<List<Object>> rows, int batchSize) {
        List<String> tuples = new ArrayList<>();
        for (int start = 0; start < rows.size(); start += batchSize) {
            String statement = TiDBPerformanceDataGenerator.buildInsertStatement(table, rows, start,
                    Math.min(rows.size(), start + batchSize));
            String values = statement.substring(statement.indexOf(" VALUES ") + " VALUES ".length());
            String body = values.substring(1, values.length() - 1);
            tuples.addAll(List.of(body.split("\\), \\(")));
        }
        return tuples;
    }
}
