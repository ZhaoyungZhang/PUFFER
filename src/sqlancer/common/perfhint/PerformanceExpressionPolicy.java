package sqlancer.common.perfhint;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import sqlancer.Randomly;

/** Opt-in restrictions on native generators, with construction-time coverage recording. */
public final class PerformanceExpressionPolicy {
    private final int maxDepth;
    private final PerformanceQueryMetadata.Builder metadata;

    public PerformanceExpressionPolicy(int maxDepth, PerformanceQueryMetadata.Builder metadata) {
        this.maxDepth = Math.max(0, maxDepth);
        this.metadata = metadata;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public <E extends Enum<E>> E choose(E[] values, Predicate<E> allowed, int depth) {
        List<E> candidates = Arrays.stream(values).filter(allowed)
                .filter(value -> depth != 0 || maxDepth == 0
                        || !List.of("COLUMN", "LITERAL", "CONSTANT").contains(value.name()))
                .collect(Collectors.toList());
        E value = Randomly.fromList(candidates);
        node(value.name(), depth);
        return value;
    }

    public void node(String kind, int depth) {
        metadata.node(kind + "@" + depth);
        if (kind.contains("CASE")) {
            metadata.feature("CASE");
        } else if (kind.contains("CAST")) {
            metadata.feature("CAST");
        } else if (kind.equals("IN") || kind.equals("IN_OPERATION")) {
            metadata.feature("IN");
        } else if (kind.contains("FUNCTION")) {
            metadata.feature("FUNCTION");
        } else if (kind.contains("BETWEEN")) {
            metadata.feature("BETWEEN");
        } else if (kind.contains("BIT")) {
            metadata.feature("BIT_OPERATION");
        } else if (kind.contains("LOGICAL") || kind.equals("AND_OR_CHAIN")) {
            metadata.feature("COMPOUND_PREDICATE");
        }
    }

    public void function(String name) {
        metadata.feature("FUNCTION");
        metadata.node("FUNCTION:" + name);
    }
}
