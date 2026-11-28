package sqlancer.cockroachdb.gen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBTable;
import sqlancer.common.perfhint.PerformanceHintHintPolicy.CandidateDescriptor;
import sqlancer.common.schema.TableIndex;

public final class CockroachDBPerformanceHintGenerator {

    private static final List<String> INDEX_HINT_TYPES = List.of("INDEX_REFERENCE", "FORCE_INDEX",
            "FORCE_INDEX_NO_FULL_SCAN", "NO_FULL_SCAN");

    private CockroachDBPerformanceHintGenerator() {
    }

    public static List<HintedQueryCandidate> generateCandidates(List<CockroachDBTable> tables, QueryShape queryShape,
            Randomly randomly, int maxIndexesPerTable, int maxVariants) {
        if (maxVariants <= 0) {
            return List.of();
        }

        List<HintedQueryCandidate> candidates = new ArrayList<>();

        for (CockroachDBTable table : tables) {
            List<String> indexNames = getCandidateIndexes(table, maxIndexesPerTable);
            if (indexNames.isEmpty()) {
                continue;
            }
            boolean constrained = queryShape.isTableConstrained(table.getName());
            for (String hintType : INDEX_HINT_TYPES) {
                if (!constrained && (hintType.equals("FORCE_INDEX_NO_FULL_SCAN") || hintType.equals("NO_FULL_SCAN"))) {
                    continue;
                }
                if (hintType.equals("NO_FULL_SCAN")) {
                    candidates.add(new HintedQueryCandidate(String.format("%s@{NO_FULL_SCAN}", table.getName()),
                            "INDEX", hintType, List.of(table.getName()), List.of(), null, null));
                    continue;
                }
                for (String indexName : indexNames) {
                    candidates.add(new HintedQueryCandidate(renderIndexHint(table.getName(), indexName, hintType),
                            "INDEX", hintType, List.of(table.getName()), List.of(indexName), null, null));
                }
            }
        }

        for (JoinSpec joinSpec : queryShape.getJoinSpecs()) {
            List<String> algorithms = new ArrayList<>(List.of("HASH"));
            if (joinSpec.hasEqualityCondition()) {
                algorithms.add("MERGE");
            }
            if ((joinSpec.isInnerJoin() || joinSpec.isLeftJoin()) && joinSpec.isLookupEligible()) {
                algorithms.add("LOOKUP");
            }
            if (joinSpec.isInnerJoin()) {
                algorithms.add("STRAIGHT");
            }
            for (String algorithm : algorithms) {
                String hintType = (joinSpec.isInnerJoin() ? "" : joinSpec.getJoinType().name() + "_") + algorithm
                        + "_JOIN";
                String hintSql = joinSpec.getJoinType().name() + " " + algorithm + " JOIN";
                candidates.add(new HintedQueryCandidate(hintSql, "JOIN", hintType,
                        List.of(joinSpec.getLeftTableName(), joinSpec.getRightTableName()), List.of(),
                        joinSpec.getOrdinal(), joinSpec.getOriginalJoinToken()));
            }
        }

        return new ArrayList<>(new LinkedHashSet<>(candidates));
    }

    private static String renderIndexHint(String tableName, String indexName, String hintType) {
        switch (hintType) {
        case "INDEX_REFERENCE":
            return String.format("%s@%s", tableName, indexName);
        case "FORCE_INDEX":
            return String.format("%s@{FORCE_INDEX=%s}", tableName, indexName);
        case "FORCE_INDEX_NO_FULL_SCAN":
            return String.format("%s@{FORCE_INDEX=%s,NO_FULL_SCAN}", tableName, indexName);
        default:
            throw new AssertionError(hintType);
        }
    }

    private static List<String> getCandidateIndexes(CockroachDBTable table, int maxIndexesPerTable) {
        Set<String> distinctIndexes = table.getIndexes().stream().map(TableIndex::getIndexName)
                .map(CockroachDBPerformanceHintGenerator::normalizeIndexName).filter(indexName -> !indexName.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctIndexes.isEmpty()) {
            return List.of();
        }
        List<String> indexes = new ArrayList<>(distinctIndexes);
        int effectiveLimit = maxIndexesPerTable <= 0 ? indexes.size() : Math.min(indexes.size(), maxIndexesPerTable);
        return List.copyOf(indexes.subList(0, effectiveLimit));
    }

    private static String normalizeIndexName(String indexName) {
        return indexName.replace("`", "");
    }

    public static final class HintedQueryCandidate implements CandidateDescriptor {
        private final String hintSql;
        private final String hintFamily;
        private final String hintType;
        private final List<String> tableNames;
        private final List<String> indexNames;
        private final Integer targetJoinOrdinal;
        private final String originalJoinToken;

        public HintedQueryCandidate(String hintSql, String hintFamily, String hintType, List<String> tableNames,
                List<String> indexNames) {
            this(hintSql, hintFamily, hintType, tableNames, indexNames, null, null);
        }

        public HintedQueryCandidate(String hintSql, String hintFamily, String hintType, List<String> tableNames,
                List<String> indexNames, Integer targetJoinOrdinal, String originalJoinToken) {
            this.hintSql = hintSql;
            this.hintFamily = hintFamily;
            this.hintType = hintType;
            this.tableNames = List.copyOf(tableNames);
            this.indexNames = List.copyOf(indexNames);
            this.targetJoinOrdinal = targetJoinOrdinal;
            this.originalJoinToken = originalJoinToken;
        }

        public String getHintSql() {
            return hintSql;
        }

        @Override
        public String getHintFamily() {
            return hintFamily;
        }

        @Override
        public String getHintType() {
            return hintType;
        }

        public List<String> getTableNames() {
            return tableNames;
        }

        public List<String> getIndexNames() {
            return indexNames;
        }

        public Integer getTargetJoinOrdinal() {
            return targetJoinOrdinal;
        }

        public String getOriginalJoinToken() {
            return originalJoinToken;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof HintedQueryCandidate)) {
                return false;
            }
            HintedQueryCandidate other = (HintedQueryCandidate) obj;
            return hintSql.equals(other.hintSql) && hintFamily.equals(other.hintFamily)
                    && hintType.equals(other.hintType) && tableNames.equals(other.tableNames)
                    && indexNames.equals(other.indexNames) && Objects.equals(targetJoinOrdinal, other.targetJoinOrdinal)
                    && Objects.equals(originalJoinToken, other.originalJoinToken);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hintSql, hintFamily, hintType, tableNames, indexNames, targetJoinOrdinal,
                    originalJoinToken);
        }
    }

    public static final class QueryShape {
        private final int tableCount;
        private final boolean hasWhereClause;
        private final boolean hasGroupBy;
        private final boolean hasOrderBy;
        private final boolean hasLimit;
        private final List<JoinSpec> joinSpecs;
        private final boolean hasInnerJoin;
        private final boolean hasLeftJoin;
        private final Set<String> constrainedTables;

        public QueryShape(int tableCount, boolean hasJoinPredicate, boolean hasWhereClause, boolean hasOrderBy,
                boolean hasLimit) {
            this(tableCount, hasWhereClause, false, hasOrderBy, hasLimit, hasJoinPredicate
                    ? List.of(new JoinSpec(0, JoinType.INNER, "t0", "t1", true, "INNER JOIN")) : List.of(),
                    hasJoinPredicate, false, hasJoinPredicate ? Set.of("t0", "t1") : Set.of());
        }

        public QueryShape(int tableCount, boolean hasWhereClause, boolean hasGroupBy, boolean hasOrderBy,
                boolean hasLimit, List<JoinSpec> joinSpecs, boolean hasInnerJoin, boolean hasLeftJoin,
                Set<String> constrainedTables) {
            this.tableCount = tableCount;
            this.hasWhereClause = hasWhereClause;
            this.hasGroupBy = hasGroupBy;
            this.hasOrderBy = hasOrderBy;
            this.hasLimit = hasLimit;
            this.joinSpecs = List.copyOf(joinSpecs);
            this.hasInnerJoin = hasInnerJoin;
            this.hasLeftJoin = hasLeftJoin;
            this.constrainedTables = Set.copyOf(constrainedTables);
        }

        public int getTableCount() {
            return tableCount;
        }

        public boolean hasJoinPredicate() {
            return !joinSpecs.isEmpty();
        }

        public boolean hasPredicateContext() {
            return hasWhereClause || hasJoinPredicate();
        }

        public boolean hasWhereClause() {
            return hasWhereClause;
        }

        public boolean hasGroupBy() {
            return hasGroupBy;
        }

        public boolean hasOrderBy() {
            return hasOrderBy;
        }

        public boolean hasLimit() {
            return hasLimit;
        }

        public List<JoinSpec> getJoinSpecs() {
            return joinSpecs;
        }

        public boolean hasInnerJoin() {
            return hasInnerJoin;
        }

        public boolean hasLeftJoin() {
            return hasLeftJoin;
        }

        public boolean isTableConstrained(String tableName) {
            return constrainedTables.contains(tableName);
        }
    }

    public enum JoinType {
        INNER, LEFT, RIGHT, FULL, CROSS
    }

    public static final class JoinSpec {
        private final int ordinal;
        private final JoinType joinType;
        private final String leftTableName;
        private final String rightTableName;
        private final boolean rightTableHasSecondaryIndex;
        private final String originalJoinToken;
        private final boolean equalityCondition;
        private final boolean rightKeyPrimary;
        private final String leftKey;
        private final String rightKey;

        public JoinSpec(int ordinal, JoinType joinType, String leftTableName, String rightTableName,
                boolean rightTableHasSecondaryIndex, String originalJoinToken) {
            this(ordinal, joinType, leftTableName, rightTableName, rightTableHasSecondaryIndex, originalJoinToken,
                    joinType != JoinType.CROSS, false, null, null);
        }

        // Extends the existing public JoinSpec constructor without breaking old callers.
        public JoinSpec(int ordinal, JoinType joinType, String leftTableName, String rightTableName, // NOPMD -
                                                                                                     // compatibility
                                                                                                     // overload
                boolean rightTableHasSecondaryIndex, String originalJoinToken, boolean equalityCondition,
                boolean rightKeyPrimary, String leftKey, String rightKey) {
            this.equalityCondition = equalityCondition;
            this.rightKeyPrimary = rightKeyPrimary;
            this.leftKey = leftKey;
            this.rightKey = rightKey;
            this.ordinal = ordinal;
            this.joinType = joinType;
            this.leftTableName = leftTableName;
            this.rightTableName = rightTableName;
            this.rightTableHasSecondaryIndex = rightTableHasSecondaryIndex;
            this.originalJoinToken = originalJoinToken;
        }

        public int getOrdinal() {
            return ordinal;
        }

        public JoinType getJoinType() {
            return joinType;
        }

        public boolean hasEqualityCondition() {
            return equalityCondition;
        }

        public boolean isLookupEligible() {
            return equalityCondition && rightKeyPrimary;
        }

        public String getLeftKey() {
            return leftKey;
        }

        public String getRightKey() {
            return rightKey;
        }

        public boolean isInnerJoin() {
            return joinType == JoinType.INNER;
        }

        public boolean isLeftJoin() {
            return joinType == JoinType.LEFT;
        }

        public String getLeftTableName() {
            return leftTableName;
        }

        public String getRightTableName() {
            return rightTableName;
        }

        public boolean isRightTableHasSecondaryIndex() {
            return rightTableHasSecondaryIndex;
        }

        public String getOriginalJoinToken() {
            return originalJoinToken;
        }
    }
}
