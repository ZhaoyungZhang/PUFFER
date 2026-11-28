package sqlancer.oceanbase;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.ParameterException;

public class TestOceanBasePerformanceInsertBatchSizeValidator {

    private final OceanBasePerformanceInsertBatchSizeValidator validator = new OceanBasePerformanceInsertBatchSizeValidator();

    @Test
    public void testAcceptedRange() {
        for (String value : new String[] { "1", "25", "500", "1000", "2000" }) {
            assertDoesNotThrow(() -> validator.validate("--perf-insert-batch-size", value));
        }
    }

    @Test
    public void testRejectedRange() {
        assertThrows(ParameterException.class, () -> validator.validate("--perf-insert-batch-size", "0"));
        assertThrows(ParameterException.class, () -> validator.validate("--perf-insert-batch-size", "2001"));
        assertThrows(ParameterException.class, () -> validator.validate("--perf-insert-batch-size", "invalid"));
    }
}
