package sqlancer.cockroachdb;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.ParameterException;

/** Restricts a CockroachDB PERFORMANCE_HINT INSERT batch to one generated table. */
public final class CockroachDBPerformanceInsertBatchSizeValidator implements IParameterValidator {

    public static final int MAX_BATCH_SIZE = 2000;

    @Override
    public void validate(String name, String value) throws ParameterException {
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new ParameterException(name + " must be an integer in [1, " + MAX_BATCH_SIZE + "]", exception);
        }
        if (parsed < 1 || parsed > MAX_BATCH_SIZE) {
            throw new ParameterException(name + " must be an integer in [1, " + MAX_BATCH_SIZE + "]");
        }
    }
}
