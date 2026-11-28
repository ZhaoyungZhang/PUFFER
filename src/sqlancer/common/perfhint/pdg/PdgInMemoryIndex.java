package sqlancer.common.perfhint.pdg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;
import sqlancer.common.perfhint.pdg.PdgColumnProducer.Kind;

/**
 * Per-database lightweight in-memory column index built once after 1x PDG materialization. Enables zero-query
 * data-aware predicate binding and materialization-free join cardinality estimation in sub-millisecond time.
 */
public final class PdgInMemoryIndex {

    private static final double[] QUANTILE_POINTS = { 0.10, 0.25, 0.50, 0.75, 0.90 };
    private static final int MAX_EXTREME_KEYS = 3;

    private final List<TableIndex> tables;
    private final Map<String, Integer> tableNameToIndex;
    private final List<RelationshipEdge> relationshipEdges;

    private PdgInMemoryIndex(List<TableIndex> tables, List<RelationshipEdge> relationshipEdges) {
        this.tables = Collections.unmodifiableList(tables);
        Map<String, Integer> map = new HashMap<>(tables.size() * 2);
        for (int i = 0; i < tables.size(); i++) {
            map.put(tables.get(i).getTableName(), i);
        }
        this.tableNameToIndex = Collections.unmodifiableMap(map);
        this.relationshipEdges = Collections.unmodifiableList(relationshipEdges);
    }

    public static PdgInMemoryIndex build(PdgSpecification specification, List<List<List<Object>>> allRows) {
        Map<String, PdgRelationship> relById = new HashMap<>();
        for (PdgRelationship rel : specification.getRelationships()) {
            relById.put(rel.getId(), rel);
        }

        List<TableIndex> tableIndexes = new ArrayList<>(specification.getTables().size());
        for (int t = 0; t < specification.getTables().size(); t++) {
            PdgSpecification.Table specTable = specification.getTables().get(t);
            List<List<Object>> rows = allRows.get(t);
            int rowCount = rows.size();
            int colCount = specTable.getColumns().size();
            ColumnIndex[] cols = new ColumnIndex[colCount];
            for (int c = 0; c < colCount; c++) {
                PdgSpecification.Column specCol = specTable.getColumns().get(c);
                cols[c] = buildColumnIndex(specTable, specCol, c, rows, rowCount, relById);
            }
            tableIndexes.add(new TableIndex(t, specTable.getName(), rowCount, cols));
        }

        List<RelationshipEdge> edges = new ArrayList<>();
        for (PdgRelationship rel : specification.getRelationships()) {
            if (rel.getLeftTable().equals(rel.getRightTable())) {
                continue;
            }
            TableIndex leftTable = findTable(tableIndexes, rel.getLeftTable());
            TableIndex rightTable = findTable(tableIndexes, rel.getRightTable());
            if (leftTable != null && rightTable != null) {
                int leftCol = leftTable.findColumnIndex(rel.getLeftColumn());
                int rightCol = rightTable.findColumnIndex(rel.getRightColumn());
                if (leftCol >= 0 && rightCol >= 0) {
                    edges.add(new RelationshipEdge(leftTable.getTableIndex(), leftTable.getTableName(), leftCol,
                            rel.getLeftColumn(), rightTable.getTableIndex(), rightTable.getTableName(), rightCol,
                            rel.getRightColumn(), rel.getType()));
                }
            }
        }
        return new PdgInMemoryIndex(tableIndexes, edges);
    }

    private static TableIndex findTable(List<TableIndex> tables, String name) {
        for (TableIndex table : tables) {
            if (table.getTableName().equalsIgnoreCase(name)) {
                return table;
            }
        }
        return null;
    }

    private static ColumnIndex buildColumnIndex(PdgSpecification.Table specTable, PdgSpecification.Column specCol,
            int colIdx, List<List<Object>> rows, int rowCount, Map<String, PdgRelationship> relById) {
        SqlType sqlType = specCol.getSqlType();
        long[] rawLongs = new long[rowCount];
        double[] rawDoubles = sqlType == SqlType.DOUBLE ? new double[rowCount] : null;
        String[] rawStrings = sqlType == SqlType.VARCHAR ? new String[rowCount] : null;
        boolean[] isNull = new boolean[rowCount];
        long[] tempNonNull = new long[rowCount];
        int nonNullCount = 0;

        for (int r = 0; r < rowCount; r++) {
            Object val = rows.get(r).get(colIdx);
            if (val == null) {
                isNull[r] = true;
                continue;
            }
            long asLong;
            if (val instanceof Number) {
                Number num = (Number) val;
                asLong = Math.round(num.doubleValue());
                if (rawDoubles != null) {
                    rawDoubles[r] = num.doubleValue();
                }
            } else {
                String str = val.toString();
                if (rawStrings != null) {
                    rawStrings[r] = str;
                }
                asLong = parseTrailingLong(str);
            }
            rawLongs[r] = asLong;
            tempNonNull[nonNullCount++] = asLong;
        }

        long[] sortedLongs = Arrays.copyOf(tempNonNull, nonNullCount);
        Arrays.sort(sortedLongs);

        long[] uniqueValues;
        int[] frequencies;
        if (nonNullCount == 0) {
            uniqueValues = new long[0];
            frequencies = new int[0];
        } else {
            int uniqueCount = 1;
            for (int i = 1; i < nonNullCount; i++) {
                if (sortedLongs[i] != sortedLongs[i - 1]) {
                    uniqueCount++;
                }
            }
            uniqueValues = new long[uniqueCount];
            frequencies = new int[uniqueCount];
            int cursor = 0;
            uniqueValues[0] = sortedLongs[0];
            frequencies[0] = 1;
            for (int i = 1; i < nonNullCount; i++) {
                if (sortedLongs[i] == sortedLongs[i - 1]) {
                    frequencies[cursor]++;
                } else {
                    cursor++;
                    uniqueValues[cursor] = sortedLongs[i];
                    frequencies[cursor] = 1;
                }
            }
        }

        long[] quantiles = computeQuantiles(sortedLongs);
        long[] heavyHitters = computeHeavyHitters(uniqueValues, frequencies);
        long[] tailKeys = computeTailKeys(uniqueValues, frequencies, heavyHitters);

        int sourceColIdx = -1;
        PdgRelationship derivedRel = null;
        PdgColumnProducer producer = specCol.getProducer();
        if (producer.getKind() == Kind.INTRA_TABLE_DERIVED) {
            Object relId = producer.getParameters().get("relationship_id");
            if (relId != null) {
                derivedRel = relById.get(relId.toString());
                if (derivedRel != null) {
                    for (int i = 0; i < specTable.getColumns().size(); i++) {
                        if (specTable.getColumns().get(i).getName().equalsIgnoreCase(derivedRel.getLeftColumn())) {
                            sourceColIdx = i;
                            break;
                        }
                    }
                }
            }
        }

        return new ColumnIndex(colIdx, specCol.getName(), sqlType, producer, rawLongs, rawDoubles, rawStrings, isNull,
                nonNullCount, sortedLongs, uniqueValues, frequencies, quantiles, heavyHitters, tailKeys, sourceColIdx,
                derivedRel);
    }

    private static long parseTrailingLong(String str) {
        int lastUnderscore = str.lastIndexOf('_');
        if (lastUnderscore >= 0 && lastUnderscore + 1 < str.length()) {
            try {
                return Long.parseLong(str.substring(lastUnderscore + 1));
            } catch (NumberFormatException ignored) {
                // Fall back to deterministic hash
            }
        }
        return str.hashCode();
    }

    private static long[] computeQuantiles(long[] sorted) {
        if (sorted.length == 0) {
            return new long[0];
        }
        long[] q = new long[QUANTILE_POINTS.length];
        for (int i = 0; i < QUANTILE_POINTS.length; i++) {
            int idx = (int) Math.min(sorted.length - 1,
                    Math.max(0, Math.round(QUANTILE_POINTS[i] * (sorted.length - 1))));
            q[i] = sorted[idx];
        }
        return q;
    }

    private static long[] computeHeavyHitters(long[] uniqueValues, int[] frequencies) {
        int n = uniqueValues.length;
        if (n == 0) {
            return new long[0];
        }
        int k = Math.min(MAX_EXTREME_KEYS, n);
        long[] result = new long[k];
        boolean[] used = new boolean[n];
        for (int pick = 0; pick < k; pick++) {
            int bestIdx = -1;
            int bestFreq = -1;
            for (int i = 0; i < n; i++) {
                if (!used[i] && frequencies[i] > bestFreq) {
                    bestFreq = frequencies[i];
                    bestIdx = i;
                }
            }
            used[bestIdx] = true;
            result[pick] = uniqueValues[bestIdx];
        }
        return result;
    }

    private static long[] computeTailKeys(long[] uniqueValues, int[] frequencies, long[] heavyHitters) {
        int n = uniqueValues.length;
        if (n == 0) {
            return new long[0];
        }
        int k = Math.min(MAX_EXTREME_KEYS, n);
        long[] temp = new long[k];
        int count = 0;
        boolean[] used = new boolean[n];
        for (long hh : heavyHitters) {
            int idx = Arrays.binarySearch(uniqueValues, hh);
            if (idx >= 0) {
                used[idx] = true;
            }
        }
        for (int pick = 0; pick < k; pick++) {
            int bestIdx = -1;
            int bestFreq = Integer.MAX_VALUE;
            for (int i = n - 1; i >= 0; i--) {
                if (!used[i] && frequencies[i] < bestFreq) {
                    bestFreq = frequencies[i];
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) {
                break;
            }
            used[bestIdx] = true;
            temp[count++] = uniqueValues[bestIdx];
        }
        return Arrays.copyOf(temp, count);
    }

    public List<TableIndex> getTables() {
        return tables;
    }

    public TableIndex getTable(int tableIdx) {
        return tables.get(tableIdx);
    }

    public TableIndex findTable(String tableName) {
        if (tableName == null) {
            return null;
        }
        Integer idx = tableNameToIndex.get(tableName);
        if (idx != null) {
            return tables.get(idx);
        }
        for (TableIndex table : tables) {
            if (table.getTableName().equalsIgnoreCase(tableName)) {
                return table;
            }
        }
        return null;
    }

    public List<RelationshipEdge> getRelationshipEdges() {
        return relationshipEdges;
    }

    public RelationshipEdge findRelationshipBetween(String tableA, String tableB) {
        if (tableA == null || tableB == null) {
            return null;
        }
        for (RelationshipEdge edge : relationshipEdges) {
            if (edge.getLeftTableName().equalsIgnoreCase(tableA) && edge.getRightTableName().equalsIgnoreCase(tableB)) {
                return edge;
            }
            if (edge.getLeftTableName().equalsIgnoreCase(tableB) && edge.getRightTableName().equalsIgnoreCase(tableA)) {
                return edge;
            }
        }
        return null;
    }

    public static final class RelationshipEdge {
        private final int leftTableIndex;
        private final String leftTableName;
        private final int leftColumnIndex;
        private final String leftColumnName;
        private final int rightTableIndex;
        private final String rightTableName;
        private final int rightColumnIndex;
        private final String rightColumnName;
        private final String relationshipType;

        public RelationshipEdge(int leftTableIndex, String leftTableName, int leftColumnIndex, String leftColumnName,
                int rightTableIndex, String rightTableName, int rightColumnIndex, String rightColumnName,
                String relationshipType) {
            this.leftTableIndex = leftTableIndex;
            this.leftTableName = leftTableName;
            this.leftColumnIndex = leftColumnIndex;
            this.leftColumnName = leftColumnName;
            this.rightTableIndex = rightTableIndex;
            this.rightTableName = rightTableName;
            this.rightColumnIndex = rightColumnIndex;
            this.rightColumnName = rightColumnName;
            this.relationshipType = relationshipType;
        }

        public int getLeftTableIndex() {
            return leftTableIndex;
        }

        public String getLeftTableName() {
            return leftTableName;
        }

        public int getLeftColumnIndex() {
            return leftColumnIndex;
        }

        public String getLeftColumnName() {
            return leftColumnName;
        }

        public int getRightTableIndex() {
            return rightTableIndex;
        }

        public String getRightTableName() {
            return rightTableName;
        }

        public int getRightColumnIndex() {
            return rightColumnIndex;
        }

        public String getRightColumnName() {
            return rightColumnName;
        }

        public String getRelationshipType() {
            return relationshipType;
        }

        public String getColumnForTable(String tableName) {
            if (leftTableName.equalsIgnoreCase(tableName)) {
                return leftColumnName;
            }
            if (rightTableName.equalsIgnoreCase(tableName)) {
                return rightColumnName;
            }
            return null;
        }
    }

    public static final class TableIndex {
        private final int tableIndex;
        private final String tableName;
        private final int rowCount;
        private final ColumnIndex[] columns;

        TableIndex(int tableIndex, String tableName, int rowCount, ColumnIndex[] columns) {
            this.tableIndex = tableIndex;
            this.tableName = tableName;
            this.rowCount = rowCount;
            this.columns = columns;
        }

        public int getTableIndex() {
            return tableIndex;
        }

        public String getTableName() {
            return tableName;
        }

        public int getRowCount() {
            return rowCount;
        }

        public int getColumnCount() {
            return columns.length;
        }

        public ColumnIndex getColumn(int colIdx) {
            return columns[colIdx];
        }

        public int findColumnIndex(String colName) {
            if (colName == null) {
                return -1;
            }
            for (int i = 0; i < columns.length; i++) {
                if (columns[i].getColumnName().equalsIgnoreCase(colName)) {
                    return i;
                }
            }
            return -1;
        }

        public ColumnIndex findColumn(String colName) {
            int idx = findColumnIndex(colName);
            return idx >= 0 ? columns[idx] : null;
        }
    }

    public static final class ColumnIndex {
        private final int columnIndex;
        private final String columnName;
        private final SqlType sqlType;
        private final PdgColumnProducer producer;
        private final long[] rawLongs;
        private final double[] rawDoubles;
        private final String[] rawStrings;
        private final boolean[] isNull;
        private final int nonNullCount;
        private final long[] sortedLongs;
        private final long[] uniqueValues;
        private final int[] frequencies;
        private final long[] quantiles;
        private final long[] heavyHitters;
        private final long[] tailKeys;
        private final int sourceColumnIndex;
        private final PdgRelationship derivedRelationship;

        ColumnIndex(int columnIndex, String columnName, SqlType sqlType, PdgColumnProducer producer, long[] rawLongs,
                double[] rawDoubles, String[] rawStrings, boolean[] isNull, int nonNullCount, long[] sortedLongs,
                long[] uniqueValues, int[] frequencies, long[] quantiles, long[] heavyHitters, long[] tailKeys,
                int sourceColumnIndex, PdgRelationship derivedRelationship) {
            this.columnIndex = columnIndex;
            this.columnName = columnName;
            this.sqlType = sqlType;
            this.producer = producer;
            this.rawLongs = rawLongs;
            this.rawDoubles = rawDoubles;
            this.rawStrings = rawStrings;
            this.isNull = isNull;
            this.nonNullCount = nonNullCount;
            this.sortedLongs = sortedLongs;
            this.uniqueValues = uniqueValues;
            this.frequencies = frequencies;
            this.quantiles = quantiles;
            this.heavyHitters = heavyHitters;
            this.tailKeys = tailKeys;
            this.sourceColumnIndex = sourceColumnIndex;
            this.derivedRelationship = derivedRelationship;
        }

        public int getColumnIndex() {
            return columnIndex;
        }

        public String getColumnName() {
            return columnName;
        }

        public SqlType getSqlType() {
            return sqlType;
        }

        public PdgColumnProducer getProducer() {
            return producer;
        }

        public int getRowCount() {
            return rawLongs.length;
        }

        public int getNonNullCount() {
            return nonNullCount;
        }

        public boolean isNullAt(int rowIdx) {
            return isNull[rowIdx];
        }

        public long getLongAt(int rowIdx) {
            return rawLongs[rowIdx];
        }

        public double getDoubleAt(int rowIdx) {
            return rawDoubles != null ? rawDoubles[rowIdx] : rawLongs[rowIdx];
        }

        public String getStringAt(int rowIdx) {
            return rawStrings != null ? rawStrings[rowIdx] : Long.toString(rawLongs[rowIdx]);
        }

        public long[] getSortedLongs() {
            return sortedLongs;
        }

        public long[] getUniqueValues() {
            return uniqueValues;
        }

        public int[] getFrequencies() {
            return frequencies;
        }

        public long[] getQuantiles() {
            return quantiles;
        }

        public long[] getHeavyHitters() {
            return heavyHitters;
        }

        public long[] getTailKeys() {
            return tailKeys;
        }

        public boolean isIntraTableDerived() {
            return sourceColumnIndex >= 0 && derivedRelationship != null;
        }

        public int getSourceColumnIndex() {
            return sourceColumnIndex;
        }

        public PdgRelationship getDerivedRelationship() {
            return derivedRelationship;
        }

        public long projectFromSourceValue(long sourceValue) {
            if (derivedRelationship == null) {
                return sourceValue;
            }
            String type = derivedRelationship.getType();
            Map<String, Object> params = derivedRelationship.getParameters();
            if ("FUNCTIONAL_DEPENDENCY".equals(type) || "FUNC_MAP".equals(type)) {
                String kind = String.valueOf(params.getOrDefault("mapping_kind", "LINEAR"));
                long multiplier = ((Number) params.getOrDefault("multiplier", 3L)).longValue();
                long offset = ((Number) params.getOrDefault("offset", 7L)).longValue();
                if ("MODULO".equals(kind)) {
                    long modulus = ((Number) params.getOrDefault("modulus", 16L)).longValue();
                    return Math.floorMod(sourceValue, modulus) * multiplier + offset;
                }
                if ("DIVIDE".equals(kind)) {
                    long divisor = ((Number) params.getOrDefault("divisor", 8L)).longValue();
                    return Math.floorDiv(sourceValue, divisor) * multiplier + offset;
                }
                return sourceValue * multiplier + offset;
            }
            if ("ANTI_CORRELATION".equals(type)) {
                long constant = ((Number) params.getOrDefault("constant", 1000000L)).longValue();
                return constant - sourceValue;
            }
            if ("COND_PATTERN".equals(type)) {
                long mod = ((Number) params.getOrDefault("trigger_modulus", 10L)).longValue();
                long rem = ((Number) params.getOrDefault("trigger_remainder", 2L)).longValue();
                if (Math.floorMod(sourceValue, mod) < rem) {
                    long cMin = ((Number) params.getOrDefault("cond_min", 900000L)).longValue();
                    long cMax = ((Number) params.getOrDefault("cond_max", 900050L)).longValue();
                    return (cMin + cMax) / 2L;
                }
                return quantiles.length > 0 ? quantiles[quantiles.length / 2] : 0L;
            }
            if ("RANK_COUPLE".equals(type) && sortedLongs.length > 0) {
                double rho = ((Number) params.getOrDefault("rho", 0.8)).doubleValue();
                int idx = Math.floorMod(Long.hashCode(sourceValue), sortedLongs.length);
                return rho >= 0.0 ? sortedLongs[idx] : sortedLongs[sortedLongs.length - 1 - idx];
            }
            return sourceValue;
        }
    }
}
