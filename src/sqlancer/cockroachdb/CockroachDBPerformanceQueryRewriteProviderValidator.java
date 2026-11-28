package sqlancer.cockroachdb;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.ParameterException;

/** Restricts the optional CockroachDB PERFORMANCE_HINT rewrite backends. */
public final class CockroachDBPerformanceQueryRewriteProviderValidator implements IParameterValidator {

    @Override
    public void validate(String name, String value) throws ParameterException {
        if (!"none".equalsIgnoreCase(value) && !"wetune-static".equalsIgnoreCase(value)) {
            throw new ParameterException(name + " must be one of: none, wetune-static");
        }
    }
}
