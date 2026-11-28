package sqlancer.mariadb.gen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.perfhint.PerformanceHintHintPolicy.CandidateDescriptor;
import sqlancer.mariadb.MariaDBSchema.MariaDBIndex;
import sqlancer.mariadb.MariaDBSchema.MariaDBTable;

public final class MariaDBPerformanceHintGenerator {

    private static final List<String> INDEX_HINT_TYPES = List.of("INDEX", "NO_INDEX", "JOIN_INDEX", "NO_JOIN_INDEX",
            "ORDER_INDEX", "NO_ORDER_INDEX", "GROUP_INDEX", "NO_GROUP_INDEX", "INDEX_MERGE", "NO_INDEX_MERGE", "MRR",
            "NO_MRR", "NO_ICP", "NO_RANGE_OPTIMIZATION");
    // MariaDB currently validates only a small capped subset of generated hints.
    // Put the more optimizer-shaping table hints earlier so they are not
    // systematically starved by earlier candidates under the global cap.
    private static final List<String> TABLE_HINT_TYPES = List.of("MERGE", "NO_MERGE", "JOIN_ORDER", "JOIN_PREFIX",
            "JOIN_SUFFIX", "JOIN_FIXED_ORDER", "BKA", "NO_BKA", "BNL", "NO_BNL");

    private MariaDBPerformanceHintGenerator() {
    }

    public static List<HintedQueryCandidate> generateCandidates(List<MariaDBTable> tables, Randomly randomly,
            int maxIndexesPerTable, int maxVariants) {
        // Under MariaDB's default join_cache_level=2, BNLH/BKAH are disabled;
        // exclude BKA by default so alternative plans stay within the default
        // optimizer search space unless explicitly re-enabled.
        return generateCandidates(tables, randomly, maxIndexesPerTable, maxVariants, false);
    }

    public static List<HintedQueryCandidate> generateCandidates(List<MariaDBTable> tables, Randomly randomly,
            int maxIndexesPerTable, int maxVariants, boolean allowBkaHint) {
        if (maxVariants < 0) {
            throw new IllegalArgumentException("maxVariants must be non-negative");
        }
        List<HintedQueryCandidate> allCandidates = new ArrayList<>();
        List<TableTarget> indexedTables = tables.stream()
                .map(table -> new TableTarget(table.getName(), getCandidateIndexes(table, maxIndexesPerTable)))
                .filter(target -> !target.indexNames.isEmpty()).collect(Collectors.toList());

        for (String hintType : INDEX_HINT_TYPES) {
            for (TableTarget tableTarget : indexedTables) {
                for (String indexName : tableTarget.indexNames) {
                    String hintSql = String.format("%s(%s, %s)", hintType, tableTarget.tableName, indexName);
                    allCandidates.add(new HintedQueryCandidate(hintSql, "INDEX", hintType,
                            List.of(tableTarget.tableName), List.of(indexName)));
                }
            }
        }

        if (!tables.isEmpty()) {
            List<String> originalOrder = tables.stream().map(MariaDBTable::getName).collect(Collectors.toList());
            List<String> reversedOrder = new ArrayList<>(originalOrder);
            java.util.Collections.reverse(reversedOrder);
            for (String hintType : TABLE_HINT_TYPES) {
                if (!allowBkaHint && "BKA".equals(hintType)) {
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
        return deduplicated;
    }

    private static void addTableHintCandidate(List<HintedQueryCandidate> candidates, String hintType,
            List<String> tableNames) {
        String hintSql = String.format("%s(%s)", hintType, String.join(", ", tableNames));
        candidates.add(new HintedQueryCandidate(hintSql, "TABLE", hintType, tableNames, List.of()));
    }

    private static List<String> getCandidateIndexes(MariaDBTable table, int maxIndexesPerTable) {
        Set<String> distinctIndexes = table.getIndexes().stream().map(MariaDBIndex::getIndexName)
                .map(MariaDBPerformanceHintGenerator::normalizeIndexName).filter(indexName -> !indexName.isEmpty())
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

    private static final class TableTarget {
        private final String tableName;
        private final List<String> indexNames;

        private TableTarget(String tableName, List<String> indexNames) {
            this.tableName = tableName;
            this.indexNames = indexNames;
        }
    }
}
