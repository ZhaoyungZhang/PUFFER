package sqlancer.common.perfhint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An already materialized, executable counterfactual query together with the metadata needed to describe it in reports
 * and feedback state.
 */
public final class CounterfactualCandidate {

    private final CounterfactualMutationKind kind;
    private final String alternativeSql;
    private final String family;
    private final String mutationId;
    private final String descriptor;
    private final List<String> targetTables;
    private final List<String> targetIndexes;

    public CounterfactualCandidate(CounterfactualMutationKind kind, String alternativeSql, String family,
            String mutationId, String descriptor, List<String> targetTables, List<String> targetIndexes) {
        this.kind = Objects.requireNonNull(kind);
        this.alternativeSql = normalize(alternativeSql);
        this.family = normalize(family);
        this.mutationId = normalize(mutationId);
        this.descriptor = normalize(descriptor);
        this.targetTables = copyStrings(targetTables);
        this.targetIndexes = copyStrings(targetIndexes);
    }

    public static CounterfactualCandidate hint(String materializedHintSql, String hintFamily, String hintType,
            String hintDescriptor, List<String> targetTables, List<String> targetIndexes) {
        return new CounterfactualCandidate(CounterfactualMutationKind.HINT, materializedHintSql, hintFamily, hintType,
                hintDescriptor, targetTables, targetIndexes);
    }

    public static CounterfactualCandidate queryRewrite(RewrittenQuery rewrittenQuery, List<String> targetTables,
            List<String> targetIndexes) {
        Objects.requireNonNull(rewrittenQuery);
        return new CounterfactualCandidate(CounterfactualMutationKind.QUERY_REWRITE, rewrittenQuery.getRewrittenSql(),
                rewrittenQuery.getRewriteFamily(), rewrittenQuery.getRuleId(), rewrittenQuery.getDescription(),
                targetTables, targetIndexes);
    }

    public CounterfactualMutationKind getKind() {
        return kind;
    }

    public String getAlternativeSql() {
        return alternativeSql;
    }

    public String getFamily() {
        return family;
    }

    public String getMutationId() {
        return mutationId;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public List<String> getTargetTables() {
        return targetTables;
    }

    public List<String> getTargetIndexes() {
        return targetIndexes;
    }

    /**
     * A stable mutation identity suitable for feedback and issue signatures.
     */
    public String getMutationIdentity() {
        return kind.name() + "|" + family + "|" + mutationId;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    private static List<String> copyStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>();
        for (String value : values) {
            copy.add(normalize(value));
        }
        return List.copyOf(copy);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CounterfactualCandidate)) {
            return false;
        }
        CounterfactualCandidate that = (CounterfactualCandidate) other;
        return kind == that.kind && alternativeSql.equals(that.alternativeSql) && family.equals(that.family)
                && mutationId.equals(that.mutationId) && descriptor.equals(that.descriptor)
                && targetTables.equals(that.targetTables) && targetIndexes.equals(that.targetIndexes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, alternativeSql, family, mutationId, descriptor, targetTables, targetIndexes);
    }
}
