package sqlancer.common.perfhint;

import java.util.Objects;

/**
 * A scheduled candidate together with the strategy originally requested for its slot and any fallback taken by the
 * scheduler.
 */
public final class ScheduledCounterfactualCandidate {

    private final CounterfactualCandidate candidate;
    private final CounterfactualMutationKind requestedStrategy;
    private final CounterfactualFallbackReason fallbackReason;

    public ScheduledCounterfactualCandidate(CounterfactualCandidate candidate,
            CounterfactualMutationKind requestedStrategy, CounterfactualFallbackReason fallbackReason) {
        this.candidate = Objects.requireNonNull(candidate);
        this.requestedStrategy = Objects.requireNonNull(requestedStrategy);
        this.fallbackReason = Objects.requireNonNull(fallbackReason);
    }

    public CounterfactualCandidate getCandidate() {
        return candidate;
    }

    public CounterfactualMutationKind getRequestedStrategy() {
        return requestedStrategy;
    }

    public CounterfactualFallbackReason getFallbackReason() {
        return fallbackReason;
    }

    public CounterfactualMutationKind getActualMutationKind() {
        return candidate.getKind();
    }
}
