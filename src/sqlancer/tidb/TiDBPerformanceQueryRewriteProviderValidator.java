package sqlancer.tidb;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.ParameterException;

/** Restricts the optional TiDB PERFORMANCE_HINT rewrite backends. */
public final class TiDBPerformanceQueryRewriteProviderValidator implements IParameterValidator {

    @Override
    public void validate(String name, String value) throws ParameterException {
        if (!"none".equalsIgnoreCase(value) && !"wetune-static".equalsIgnoreCase(value)) {
            throw new ParameterException(name + " must be one of: none, wetune-static");
        }
    }
}
