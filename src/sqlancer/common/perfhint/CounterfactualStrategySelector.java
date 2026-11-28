package sqlancer.common.perfhint;

import java.util.Objects;

import sqlancer.Randomly;

/**
 * Selects the requested mutation strategy for each candidate slot.
 */
public final class CounterfactualStrategySelector {

    private static final int PROBABILITY_SCALE = 1_000_000;

    private final double hintProbability;

    public CounterfactualStrategySelector(double hintProbability) {
        if (!isValidHintProbability(hintProbability)) {
            throw new IllegalArgumentException("--perf-counterfactual-hint-probability must be in [0.0, 1.0]");
        }
        this.hintProbability = hintProbability;
    }

    public static boolean isValidHintProbability(double hintProbability) {
        return Double.isFinite(hintProbability) && hintProbability >= 0.0 && hintProbability <= 1.0;
    }

    public double getHintProbability() {
        return hintProbability;
    }

    /**
     * At p=1 there is no reason to invoke a rewrite provider. In particular, this keeps an existing HINT-only
     * campaign's seeded behavior unchanged.
     */
    public boolean shouldRequestRewrites() {
        return hintProbability < 1.0;
    }

    public CounterfactualMutationKind select(Randomly randomly) {
        if (hintProbability == 1.0) {
            return CounterfactualMutationKind.HINT;
        }
        if (hintProbability == 0.0) {
            return CounterfactualMutationKind.QUERY_REWRITE;
        }
        Objects.requireNonNull(randomly);
        int sample = randomly.getInteger(0, PROBABILITY_SCALE);
        return sample < hintProbability * PROBABILITY_SCALE ? CounterfactualMutationKind.HINT
                : CounterfactualMutationKind.QUERY_REWRITE;
    }
}
