package sqlancer.common.perfhint;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.ParameterException;

/** Validates positive integer command-line options. */
public final class PositiveIntegerValidator implements IParameterValidator {

    @Override
    public void validate(String name, String value) throws ParameterException {
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new ParameterException(name + " must be a positive integer", exception);
        }
        if (parsed <= 0) {
            throw new ParameterException(name + " must be a positive integer");
        }
    }
}
