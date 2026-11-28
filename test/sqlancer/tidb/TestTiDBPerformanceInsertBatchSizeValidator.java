package sqlancer.tidb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.ParameterException;

public class TestTiDBPerformanceInsertBatchSizeValidator {

    private final TiDBPerformanceInsertBatchSizeValidator validator = new TiDBPerformanceInsertBatchSizeValidator();

    @Test
    public void testRange() {
        for (String value : new String[] { "1", "25", "500", "1000", "2000" }) {
            assertDoesNotThrow(() -> validator.validate("--perf-insert-batch-size", value));
        }
        assertThrows(ParameterException.class, () -> validator.validate("--perf-insert-batch-size", "0"));
        assertThrows(ParameterException.class, () -> validator.validate("--perf-insert-batch-size", "2001"));
        assertThrows(ParameterException.class, () -> validator.validate("--perf-insert-batch-size", "invalid"));
    }
}
