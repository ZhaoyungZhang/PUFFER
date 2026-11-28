package sqlancer.common.perfhint;

/**
 * Explains why a scheduled candidate did not use the strategy requested for a candidate slot.
 */
public enum CounterfactualFallbackReason {
    NONE, HINT_POOL_EMPTY, QUERY_REWRITE_POOL_EMPTY
}
