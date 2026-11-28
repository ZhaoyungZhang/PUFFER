package sqlancer.tidb.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

public class TestTiDBPerformanceHintOracleUtils {

    @Test
    public void deferredResultComparisonChecksEveryColumnAndDuplicate() {
        List<List<String>> base = Arrays.asList(Arrays.asList("a", "1"), Arrays.asList("a", "1"),
                Arrays.asList("b", null));
        List<List<String>> reorderedEquivalent = Arrays.asList(Arrays.asList("b", null), Arrays.asList("a", "1"),
                Arrays.asList("a", "1"));
        List<List<String>> missingDuplicate = Arrays.asList(Arrays.asList("a", "1"), Arrays.asList("b", null));
        List<List<String>> differentSecondColumn = Arrays.asList(Arrays.asList("a", "2"), Arrays.asList("a", "1"),
                Arrays.asList("b", null));

        assertTrue(TiDBPerformanceHintOracleUtils.resultRowsMatch(base, reorderedEquivalent));
        assertFalse(TiDBPerformanceHintOracleUtils.resultRowsMatch(base, missingDuplicate));
        assertFalse(TiDBPerformanceHintOracleUtils.resultRowsMatch(base, differentSecondColumn));
    }
}
