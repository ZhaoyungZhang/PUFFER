package sqlancer.mysql;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.ParameterException;

/** Restricts the optional MySQL PERFORMANCE_HINT rewrite backends. */
public final class MySQLPerformanceQueryRewriteProviderValidator implements IParameterValidator {

    @Override
    public void validate(String name, String value) throws ParameterException {
        if (!"none".equalsIgnoreCase(value) && !"wetune-static".equalsIgnoreCase(value)) {
            throw new ParameterException(name + " must be one of: none, wetune-static");
        }
    }
}
