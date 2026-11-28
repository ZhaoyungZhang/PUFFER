package sqlancer.common.perfhint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import sqlancer.Randomly;

/**
 * Merges HINT and QUERY_REWRITE candidate pools without letting either source exceed the DBMS' existing per-query
 * candidate budget.
 */
public final class CounterfactualCandidateScheduler {

    private CounterfactualCandidateScheduler() {
    }

    public static List<ScheduledCounterfactualCandidate> schedule(String baseSql, int candidateBudget,
            CounterfactualStrategySelector selector, Randomly randomly, List<CounterfactualCandidate> hintCandidates,
            List<CounterfactualCandidate> rewriteCandidates) {
        Objects.requireNonNull(selector);
        if (candidateBudget <= 0) {
            return List.of();
        }

        String effectiveBaseSql = baseSql == null ? "" : baseSql;
        Deque<CounterfactualCandidate> hints = new ArrayDeque<>(sanitize(hintCandidates, effectiveBaseSql));
        Deque<CounterfactualCandidate> rewrites = new ArrayDeque<>(sanitize(rewriteCandidates, effectiveBaseSql));
        Set<String> emittedSql = new HashSet<>();
        List<ScheduledCounterfactualCandidate> scheduled = new ArrayList<>();

        while (scheduled.size() < candidateBudget) {
            // Do not sample a strategy for a non-existent slot. This matters
            // for reproducibility when a provider admits no candidates.
            if (!hasRemainingUniqueCandidate(hints, rewrites, emittedSql)) {
                break;
            }
            CounterfactualMutationKind requested = selector.select(randomly);
            Deque<CounterfactualCandidate> requestedPool = poolFor(requested, hints, rewrites);
            CounterfactualCandidate candidate = pollUnique(requestedPool, emittedSql, randomly,
                    requested == CounterfactualMutationKind.QUERY_REWRITE);
            CounterfactualFallbackReason fallbackReason = CounterfactualFallbackReason.NONE;

            if (candidate == null) {
                CounterfactualMutationKind fallbackStrategy = otherStrategy(requested);
                candidate = pollUnique(poolFor(fallbackStrategy, hints, rewrites), emittedSql, randomly,
                        fallbackStrategy == CounterfactualMutationKind.QUERY_REWRITE);
                fallbackReason = fallbackReasonFor(requested);
            }
            if (candidate == null) {
                break;
            }

            emittedSql.add(candidate.getAlternativeSql());
            scheduled.add(new ScheduledCounterfactualCandidate(candidate, requested, fallbackReason));
        }
        return List.copyOf(scheduled);
    }

    /**
     * Schedules one complete strategy batch for a base query. Unlike {@link #schedule}, which chooses a strategy
     * independently for every slot, this method retains every unique candidate from the selected pool (up to
     * {@code candidateBudget}) in provider order. It is useful for rewrite providers where a single response contains a
     * bounded, meaningful candidate set that should be measured in full.
     *
     * <p>
     * The other pool is used only when the requested pool is empty. In particular, a short rewrite response is not
     * padded with hints: a rewrite batch remains a rewrite batch.
     */
    public static List<ScheduledCounterfactualCandidate> scheduleBatch(String baseSql, int candidateBudget,
            CounterfactualMutationKind requestedStrategy, List<CounterfactualCandidate> hintCandidates,
            List<CounterfactualCandidate> rewriteCandidates) {
        Objects.requireNonNull(requestedStrategy);
        if (candidateBudget <= 0) {
            return List.of();
        }

        String effectiveBaseSql = baseSql == null ? "" : baseSql;
        List<CounterfactualCandidate> hints = sanitize(hintCandidates, effectiveBaseSql);
        List<CounterfactualCandidate> rewrites = sanitize(rewriteCandidates, effectiveBaseSql);
        List<CounterfactualCandidate> requestedPool = requestedStrategy == CounterfactualMutationKind.HINT ? hints
                : rewrites;
        List<CounterfactualCandidate> fallbackPool = requestedStrategy == CounterfactualMutationKind.HINT ? rewrites
                : hints;
        CounterfactualFallbackReason fallbackReason = CounterfactualFallbackReason.NONE;
        if (requestedPool.isEmpty()) {
            requestedPool = fallbackPool;
            fallbackReason = fallbackReasonFor(requestedStrategy);
        }

        Set<String> emittedSql = new HashSet<>();
        List<ScheduledCounterfactualCandidate> scheduled = new ArrayList<>();
        for (CounterfactualCandidate candidate : requestedPool) {
            if (scheduled.size() >= candidateBudget) {
                break;
            }
            if (emittedSql.add(candidate.getAlternativeSql())) {
                scheduled.add(new ScheduledCounterfactualCandidate(candidate, requestedStrategy, fallbackReason));
            }
        }
        return List.copyOf(scheduled);
    }

    /**
     * Invokes a provider and retains only rewrites explicitly accepted by the equivalence oracle. Providers and
     * validators are intentionally not used by {@link #schedule}; adapters can skip this call altogether at p=1.
     */
    public static List<CounterfactualCandidate> collectValidatedRewriteCandidates(QueryRewriteRequest request,
            PerformanceQueryRewriteProvider provider, RewriteEquivalenceOracle equivalenceOracle,
            List<String> targetTables, List<String> targetIndexes) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(equivalenceOracle);
        List<RewrittenQuery> rewrites = provider.generateRewrites(request);
        if (rewrites == null || rewrites.isEmpty()) {
            return List.of();
        }

        List<CounterfactualCandidate> candidates = new ArrayList<>();
        Set<String> seenSql = new HashSet<>();
        for (RewrittenQuery rewrittenQuery : rewrites) {
            if (rewrittenQuery == null) {
                continue;
            }
            String rewrittenSql = rewrittenQuery.getRewrittenSql();
            if (rewrittenSql.trim().isEmpty() || rewrittenSql.equals(request.getBaseSql())
                    || !seenSql.add(rewrittenSql)) {
                continue;
            }
            if (equivalenceOracle.verify(request, rewrittenQuery) == RewriteEquivalenceOracle.Verdict.ACCEPT) {
                candidates.add(CounterfactualCandidate.queryRewrite(rewrittenQuery, targetTables, targetIndexes));
            }
        }
        return List.copyOf(candidates);
    }

    private static List<CounterfactualCandidate> sanitize(List<CounterfactualCandidate> candidates, String baseSql) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<CounterfactualCandidate> sanitized = new ArrayList<>();
        Set<String> knownAlternatives = new HashSet<>();
        for (CounterfactualCandidate candidate : candidates) {
            if (candidate == null || candidate.getAlternativeSql().trim().isEmpty()
                    || candidate.getAlternativeSql().equals(baseSql)
                    || !knownAlternatives.add(candidate.getAlternativeSql())) {
                continue;
            }
            sanitized.add(candidate);
        }
        return sanitized;
    }

    private static Deque<CounterfactualCandidate> poolFor(CounterfactualMutationKind strategy,
            Deque<CounterfactualCandidate> hints, Deque<CounterfactualCandidate> rewrites) {
        return strategy == CounterfactualMutationKind.HINT ? hints : rewrites;
    }

    private static CounterfactualCandidate pollUnique(Deque<CounterfactualCandidate> candidates, Set<String> emittedSql,
            Randomly randomly, boolean randomlySelect) {
        if (randomlySelect) {
            List<CounterfactualCandidate> available = new ArrayList<>();
            for (CounterfactualCandidate candidate : candidates) {
                if (!emittedSql.contains(candidate.getAlternativeSql())) {
                    available.add(candidate);
                }
            }
            if (available.isEmpty()) {
                return null;
            }
            CounterfactualCandidate selected = available.get(randomly.getInteger(0, available.size()));
            candidates.remove(selected);
            return selected;
        }
        while (!candidates.isEmpty()) {
            CounterfactualCandidate candidate = candidates.removeFirst();
            if (!emittedSql.contains(candidate.getAlternativeSql())) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean hasRemainingUniqueCandidate(Deque<CounterfactualCandidate> hints,
            Deque<CounterfactualCandidate> rewrites, Set<String> emittedSql) {
        return containsUnemitted(hints, emittedSql) || containsUnemitted(rewrites, emittedSql);
    }

    private static boolean containsUnemitted(Deque<CounterfactualCandidate> candidates, Set<String> emittedSql) {
        for (CounterfactualCandidate candidate : candidates) {
            if (!emittedSql.contains(candidate.getAlternativeSql())) {
                return true;
            }
        }
        return false;
    }

    private static CounterfactualMutationKind otherStrategy(CounterfactualMutationKind strategy) {
        return strategy == CounterfactualMutationKind.HINT ? CounterfactualMutationKind.QUERY_REWRITE
                : CounterfactualMutationKind.HINT;
    }

    private static CounterfactualFallbackReason fallbackReasonFor(CounterfactualMutationKind strategy) {
        return strategy == CounterfactualMutationKind.HINT ? CounterfactualFallbackReason.HINT_POOL_EMPTY
                : CounterfactualFallbackReason.QUERY_REWRITE_POOL_EMPTY;
    }
}
