package sqlancer.oceanbase.gen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.perfhint.PerformanceHintHintPolicy.CandidateDescriptor;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseIndex;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseTable;

/**
 * Generates only documented OceanBase optimizer hints. Unlike the legacy TLP hint generator, candidates here are
 * deterministic alternatives for the same SELECT and are later checked through EXPLAIN EXTENDED's Used Hint output.
 */
public final class OceanBasePerformanceHintGenerator {

    private OceanBasePerformanceHintGenerator() {
    }

    public static List<HintedQueryCandidate> generateCandidates(List<OceanBaseTable> tables, QueryShape queryShape,
            Randomly randomly, int maxIndexesPerTable, int maxVariants) {
        if (maxVariants <= 0 || tables.isEmpty()) {
            return List.of();
        }
        List<HintedQueryCandidate> candidates = new ArrayList<>();
        for (OceanBaseTable table : tables) {
            List<String> indexes = candidateIndexes(table, maxIndexesPerTable);
            if (queryShape.hasPredicate()) {
                candidates.add(new HintedQueryCandidate("FULL(" + table.getName() + ")", "ACCESS", "FULL",
                        List.of(table.getName()), List.of()));
            }
            for (String index : indexes) {
                candidates.add(new HintedQueryCandidate("INDEX(" + table.getName() + " " + index + ")", "ACCESS",
                        "INDEX", List.of(table.getName()), List.of(index)));
            }
        }

        List<String> tableNames = tables.stream().map(OceanBaseTable::getName).collect(Collectors.toList());
        if (queryShape.hasJoin() && tableNames.size() >= 2) {
            List<String> reversed = new ArrayList<>(tableNames);
            java.util.Collections.reverse(reversed);
            addJoinCandidates(candidates, tableNames);
            if (!reversed.equals(tableNames)) {
                addJoinCandidates(candidates, reversed);
            }
            candidates.add(new HintedQueryCandidate("ORDERED", "JOIN_ORDER", "ORDERED", tableNames, List.of()));
        }
        if (queryShape.hasGroupBy()) {
            candidates.add(new HintedQueryCandidate("USE_HASH_AGGREGATION", "AGGREGATION", "USE_HASH_AGGREGATION",
                    tableNames, List.of()));
            candidates.add(new HintedQueryCandidate("NO_USE_HASH_AGGREGATION", "AGGREGATION", "NO_USE_HASH_AGGREGATION",
                    tableNames, List.of()));
        }

        List<HintedQueryCandidate> deduplicated = candidates.stream().distinct().collect(Collectors.toList());
        if (deduplicated.size() <= maxVariants) {
            return deduplicated;
        }
        return new ArrayList<>(Randomly.extractNrRandomColumns(deduplicated, maxVariants));
    }

    private static void addJoinCandidates(List<HintedQueryCandidate> candidates, List<String> order) {
        String leading = "LEADING(" + String.join(" ", order) + ")";
        String right = order.get(order.size() - 1);
        candidates
                .add(new HintedQueryCandidate(leading + " USE_NL(" + right + ")", "JOIN", "USE_NL", order, List.of()));
        candidates.add(
                new HintedQueryCandidate(leading + " USE_HASH(" + right + ")", "JOIN", "USE_HASH", order, List.of()));
    }

    private static List<String> candidateIndexes(OceanBaseTable table, int maxIndexesPerTable) {
        Set<String> names = table.getIndexes().stream().map(OceanBaseIndex::getIndexName)
                .map(OceanBasePerformanceHintGenerator::normalizeIdentifier).filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> result = new ArrayList<>(names);
        if (result.size() > Math.max(1, maxIndexesPerTable)) {
            return new ArrayList<>(Randomly.extractNrRandomColumns(result, Math.max(1, maxIndexesPerTable)));
        }
        return result;
    }

    private static String normalizeIdentifier(String identifier) {
        return identifier == null ? "" : identifier.replace("`", "").trim();
    }

    public static final class QueryShape {
        private final boolean hasPredicate;
        private final boolean hasJoin;
        private final boolean hasGroupBy;

        public QueryShape(boolean hasPredicate, boolean hasJoin, boolean hasGroupBy) {
            this.hasPredicate = hasPredicate;
            this.hasJoin = hasJoin;
            this.hasGroupBy = hasGroupBy;
        }

        public boolean hasPredicate() {
            return hasPredicate;
        }

        public boolean hasJoin() {
            return hasJoin;
        }

        public boolean hasGroupBy() {
            return hasGroupBy;
        }
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
            return obj instanceof HintedQueryCandidate && hintSql.equals(((HintedQueryCandidate) obj).hintSql);
        }

        @Override
        public int hashCode() {
            return hintSql.hashCode();
        }
    }
}
