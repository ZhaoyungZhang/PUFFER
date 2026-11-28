package sqlancer.mysql.gen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.perfhint.PerformanceHintHintPolicy.CandidateDescriptor;
import sqlancer.mysql.MySQLSchema.MySQLIndex;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.ast.MySQLJoin;
import sqlancer.mysql.ast.MySQLSelect;

public final class MySQLPerformanceHintGenerator {

    private static final List<String> INDEX_HINT_TYPES = List.of("INDEX", "NO_INDEX", "JOIN_INDEX", "NO_JOIN_INDEX",
            "ORDER_INDEX", "NO_ORDER_INDEX", "GROUP_INDEX", "NO_GROUP_INDEX", "INDEX_MERGE", "NO_INDEX_MERGE",
            "SKIP_SCAN", "NO_SKIP_SCAN", "MRR", "NO_MRR", "NO_ICP", "NO_RANGE_OPTIMIZATION");
    private static final List<String> TABLE_HINT_TYPES = List.of("BKA", "NO_BKA", "BNL", "NO_BNL", "HASH_JOIN",
            "NO_HASH_JOIN", "JOIN_FIXED_ORDER", "JOIN_ORDER", "JOIN_PREFIX", "JOIN_SUFFIX");

    private MySQLPerformanceHintGenerator() {
    }

    public static List<HintedQueryCandidate> generateCandidates(List<MySQLTable> tables, QueryShape queryShape,
            Randomly randomly, int maxIndexesPerTable, int maxVariants) {
        // Under MySQL's default optimizer_switch (batched_key_access=off), BKA
        // enables a disabled-by-default join algorithm; exclude it by default
        // unless explicitly enabled.
        return generateCandidates(tables, queryShape, randomly, maxIndexesPerTable, maxVariants, false);
    }

    public static List<HintedQueryCandidate> generateCandidates(List<MySQLTable> tables, QueryShape queryShape,
            Randomly randomly, int maxIndexesPerTable, int maxVariants, boolean allowBkaHint) {
        if (maxVariants < 0) {
            throw new IllegalArgumentException("maxVariants must be non-negative");
        }
        if (maxVariants == 0) {
            return List.of();
        }
        List<HintedQueryCandidate> allCandidates = new ArrayList<>();
        List<TableTarget> indexedTables = tables.stream()
                .map(table -> new TableTarget(table.getName(), getCandidateIndexes(table, maxIndexesPerTable)))
                .filter(target -> !target.indexNames.isEmpty()).collect(Collectors.toList());

        for (String hintType : INDEX_HINT_TYPES) {
            for (TableTarget tableTarget : indexedTables) {
                if (!shouldGenerateIndexHint(hintType, queryShape, tableTarget)) {
                    continue;
                }
                if (usesMultiIndexPayload(hintType)) {
                    String hintSql = String.format("%s(%s, %s)", hintType, tableTarget.tableName,
                            String.join(", ", tableTarget.indexNames));
                    allCandidates.add(new HintedQueryCandidate(hintSql, "INDEX", hintType,
                            List.of(tableTarget.tableName), tableTarget.indexNames));
                    continue;
                }
                for (String indexName : tableTarget.indexNames) {
                    String hintSql = String.format("%s(%s, %s)", hintType, tableTarget.tableName, indexName);
                    allCandidates.add(new HintedQueryCandidate(hintSql, "INDEX", hintType,
                            List.of(tableTarget.tableName), List.of(indexName)));
                }
            }
        }

        if (!tables.isEmpty()) {
            List<String> originalOrder = tables.stream().map(MySQLTable::getName).collect(Collectors.toList());
            List<String> reversedOrder = new ArrayList<>(originalOrder);
            java.util.Collections.reverse(reversedOrder);
            for (String hintType : TABLE_HINT_TYPES) {
                if (!allowBkaHint && "BKA".equals(hintType)) {
                    continue;
                }
                if (!shouldGenerateTableHint(hintType, queryShape)) {
                    continue;
                }
                if (hintType.equals("JOIN_ORDER") || hintType.equals("JOIN_PREFIX") || hintType.equals("JOIN_SUFFIX")) {
                    addTableHintCandidate(allCandidates, hintType, originalOrder);
                    if (reversedOrder.size() > 1 && !reversedOrder.equals(originalOrder)) {
                        addTableHintCandidate(allCandidates, hintType, reversedOrder);
                    }
                } else {
                    addTableHintCandidate(allCandidates, hintType, originalOrder);
                }
            }
        }

        List<HintedQueryCandidate> deduplicated = allCandidates.stream().distinct().collect(Collectors.toList());
        if (deduplicated.size() <= maxVariants) {
            return deduplicated;
        }
        return new ArrayList<>(Randomly.extractNrRandomColumns(deduplicated, maxVariants));
    }

    public static QueryShape analyzeQueryShape(MySQLSelect select, List<MySQLTable> referencedTables) {
        boolean hasJoinPredicate = select.getJoinList().stream().filter(MySQLJoin.class::isInstance)
                .map(MySQLJoin.class::cast).anyMatch(join -> join.getOnClause() != null
                        && join.getType() != MySQLJoin.JoinType.CROSS && join.getType() != MySQLJoin.JoinType.NATURAL);
        return new QueryShape(referencedTables.size(), hasJoinPredicate, select.getWhereClause() != null,
                !select.getOrderByExpressions().isEmpty(), !select.getGroupByExpressions().isEmpty());
    }

    private static boolean shouldGenerateIndexHint(String hintType, QueryShape queryShape, TableTarget tableTarget) {
        switch (hintType) {
        case "JOIN_INDEX":
        case "NO_JOIN_INDEX":
            return queryShape.hasJoinPredicate();
        case "ORDER_INDEX":
        case "NO_ORDER_INDEX":
            return queryShape.hasOrderBy();
        case "GROUP_INDEX":
        case "NO_GROUP_INDEX":
            return queryShape.hasGroupBy();
        case "INDEX_MERGE":
        case "NO_INDEX_MERGE":
            return queryShape.hasPredicateContext() && tableTarget.indexNames.size() > 1;
        case "MRR":
        case "NO_MRR":
        case "NO_ICP":
        case "NO_RANGE_OPTIMIZATION":
        case "SKIP_SCAN":
        case "NO_SKIP_SCAN":
        case "INDEX":
        case "NO_INDEX":
            return queryShape.hasPredicateContext();
        default:
            return true;
        }
    }

    private static boolean usesMultiIndexPayload(String hintType) {
        return hintType.equals("INDEX_MERGE") || hintType.equals("NO_INDEX_MERGE");
    }

    private static boolean shouldGenerateTableHint(String hintType, QueryShape queryShape) {
        switch (hintType) {
        case "BKA":
        case "NO_BKA":
        case "BNL":
        case "NO_BNL":
        case "HASH_JOIN":
        case "NO_HASH_JOIN":
            return queryShape.hasJoinPredicate();
        case "JOIN_FIXED_ORDER":
            return queryShape.getTableCount() >= 2;
        case "JOIN_ORDER":
        case "JOIN_PREFIX":
        case "JOIN_SUFFIX":
            return queryShape.getTableCount() >= 3;
        default:
            return true;
        }
    }

    private static void addTableHintCandidate(List<HintedQueryCandidate> candidates, String hintType,
            List<String> tableNames) {
        String hintSql = String.format("%s(%s)", hintType, String.join(", ", tableNames));
        candidates.add(new HintedQueryCandidate(hintSql, "TABLE", hintType, tableNames, List.of()));
    }

    private static List<String> getCandidateIndexes(MySQLTable table, int maxIndexesPerTable) {
        Set<String> distinctIndexes = table.getIndexes().stream().map(MySQLIndex::getIndexName)
                .map(MySQLPerformanceHintGenerator::normalizeIndexName).filter(indexName -> !indexName.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctIndexes.isEmpty()) {
            return List.of();
        }
        List<String> indexes = new ArrayList<>(distinctIndexes);
        if (indexes.size() > maxIndexesPerTable) {
            return Randomly.nonEmptySubset(indexes, maxIndexesPerTable);
        }
        return indexes;
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

        public HintedQueryCandidate(String hintSql, String hintFamily, String hintType, List<String> tableNames,
                List<String> indexNames) {
            this.hintSql = hintSql;
            this.hintFamily = hintFamily;
            this.hintType = hintType;
            this.tableNames = List.copyOf(tableNames);
            this.indexNames = List.copyOf(indexNames);
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

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof HintedQueryCandidate)) {
                return false;
            }
            HintedQueryCandidate other = (HintedQueryCandidate) obj;
            return hintSql.equals(other.hintSql);
        }

        @Override
        public int hashCode() {
            return hintSql.hashCode();
        }
    }

    public static final class QueryShape {
        private final int tableCount;
        private final boolean hasJoinPredicate;
        private final boolean hasWhereClause;
        private final boolean hasOrderBy;
        private final boolean hasGroupBy;

        public QueryShape(int tableCount, boolean hasJoinPredicate, boolean hasWhereClause, boolean hasOrderBy,
                boolean hasGroupBy) {
            this.tableCount = tableCount;
            this.hasJoinPredicate = hasJoinPredicate;
            this.hasWhereClause = hasWhereClause;
            this.hasOrderBy = hasOrderBy;
            this.hasGroupBy = hasGroupBy;
        }

        public int getTableCount() {
            return tableCount;
        }

        public boolean hasJoinPredicate() {
            return hasJoinPredicate;
        }

        public boolean hasPredicateContext() {
            return hasWhereClause || hasJoinPredicate;
        }

        public boolean hasOrderBy() {
            return hasOrderBy;
        }

        public boolean hasGroupBy() {
            return hasGroupBy;
        }
    }

    private static final class TableTarget {
        private final String tableName;
        private final List<String> indexNames;

        private TableTarget(String tableName, List<String> indexNames) {
            this.tableName = tableName;
            this.indexNames = indexNames;
        }
    }
}
