package sqlancer.oceanbase;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.oceanbase.OceanBaseSchema.OceanBaseIndex;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseTable;

public class TestOceanBasePerformanceHintSupport {

    @Test
    public void testStatisticsRefreshUsesAnalyzeOnly() {
        OceanBaseTable t0 = new OceanBaseTable("t0", List.of(), List.of(OceanBaseIndex.create("i0")));
        OceanBaseTable t1 = new OceanBaseTable("t1", List.of(), List.of());

        assertEquals(List.of("ANALYZE TABLE t0, t1"),
                OceanBasePerformanceHintSupport.buildStatisticsRefreshStatements(List.of(t0, t1, t0)));
    }

    @Test
    public void testAnalyzeRequiresAtLeastOneTable() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OceanBasePerformanceHintSupport.buildAnalyzeTableStatement(List.of()));
    }
}
