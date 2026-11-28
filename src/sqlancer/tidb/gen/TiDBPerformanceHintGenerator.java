package sqlancer.tidb.gen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.perfhint.PerformanceHintHintPolicy.CandidateDescriptor;
import sqlancer.common.schema.TableIndex;
import sqlancer.tidb.TiDBSchema.TiDBTable;

public final class TiDBPerformanceHintGenerator {

    private static final List<String> INDEX_HINT_TYPES = List.of("USE_INDEX", "IGNORE_INDEX");
    private static final List<String> TABLE_HINT_TYPES = List.of("LEADING", "HASH_JOIN", "MERGE_JOIN", "INL_JOIN",
            "HASH_JOIN_BUILD", "HASH_JOIN_PROBE");
    private static final String STRAIGHT_JOIN = "STRAIGHT_JOIN()";

    private TiDBPerformanceHintGenerator() {
    }

    public static List<HintedQueryCandidate> generateCandidates(List<TiDBTable> tables, Randomly randomly,
            int maxIndexesPerTable, int maxVariants) {
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
            List<String> originalOrder = tables.stream().map(TiDBTable::getName).collect(Collectors.toList());
            List<String> reversedOrder = new ArrayList<>(originalOrder);
            java.util.Collections.reverse(reversedOrder);
            for (String hintType : TABLE_HINT_TYPES) {
                if (hintType.equals("LEADING")) {
                    addTableHintCandidate(allCandidates, hintType, originalOrder);
                    if (reversedOrder.size() > 1 && !reversedOrder.equals(originalOrder)) {
                        addTableHintCandidate(allCandidates, hintType, reversedOrder);
                    }
                } else {
                    addTableHintCandidate(allCandidates, hintType, originalOrder);
                }
            }
            allCandidates.add(new HintedQueryCandidate(STRAIGHT_JOIN, "TABLE", "STRAIGHT_JOIN", List.of(), List.of()));
        }

        List<HintedQueryCandidate> deduplicated = allCandidates.stream().distinct().collect(Collectors.toList());
        return deduplicated;
    }

    private static void addTableHintCandidate(List<HintedQueryCandidate> candidates, String hintType,
            List<String> tableNames) {
        String hintSql = String.format("%s(%s)", hintType, String.join(", ", tableNames));
        candidates.add(new HintedQueryCandidate(hintSql, "TABLE", hintType, tableNames, List.of()));
    }

    private static List<String> getCandidateIndexes(TiDBTable table, int maxIndexesPerTable) {
        Set<String> distinctIndexes = table.getIndexes().stream().map(TableIndex::getIndexName)
                .map(TiDBPerformanceHintGenerator::normalizeIndexName).filter(indexName -> !indexName.isEmpty())
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
