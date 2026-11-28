package sqlancer.common.perfhint;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.ParameterException;

/**
 * Rejects invalid counterfactual strategy probabilities while command-line options are parsed, rather than waiting
 * until an oracle happens to run.
 */
public final class CounterfactualHintProbabilityValidator implements IParameterValidator {

    @Override
    public void validate(String name, String value) throws ParameterException {
        final double probability;
        try {
            probability = Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new ParameterException(name + " must be a finite number in [0.0, 1.0]", exception);
        }
        if (!CounterfactualStrategySelector.isValidHintProbability(probability)) {
            throw new ParameterException(name + " must be in [0.0, 1.0]");
        }
    }
}
