package sqlancer.mysql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.ParameterException;

public class TestMySQLPerformanceInsertBatchSizeValidator {

    private final MySQLPerformanceInsertBatchSizeValidator validator = new MySQLPerformanceInsertBatchSizeValidator();

    @Test
    public void testAcceptsSupportedBatchSizes() {
        for (String value : new String[] { "1", "25", "100", "200", "500", "1000", "2000" }) {
            assertDoesNotThrow(() -> validator.validate("--perf-insert-batch-size", value));
        }
    }

    @Test
    public void testRejectsOutOfRangeBatchSizes() {
        assertThrows(ParameterException.class, () -> validator.validate("--perf-insert-batch-size", "0"));
        assertThrows(ParameterException.class, () -> validator.validate("--perf-insert-batch-size", "2001"));
        assertThrows(ParameterException.class, () -> validator.validate("--perf-insert-batch-size", "invalid"));
    }
}
