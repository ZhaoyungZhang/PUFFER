package sqlancer.common.perfhint;

import java.util.ArrayList;
import java.util.List;

/**
 * A DBMS-neutral schema view supplied to a query rewrite provider. It only contains metadata safe and useful for
 * rewrite rule matching.
 */
public final class QueryRewriteSchemaSummary {

    public static final class ColumnSummary {
        private final String name;
        private final String type;
        private final String typeDeclaration;
        private final boolean nullable;
        private final boolean primaryKey;

        public ColumnSummary(String name, String type) {
            this(name, type, type, true, false);
        }

        public ColumnSummary(String name, String type, String typeDeclaration, boolean nullable, boolean primaryKey) {
            this.name = normalize(name);
            this.type = normalize(type);
            this.typeDeclaration = normalize(typeDeclaration).isEmpty() ? this.type : normalize(typeDeclaration);
            this.nullable = nullable;
            this.primaryKey = primaryKey;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        /**
         * The DBMS-native type spelling used when rendering a semantic schema for an external rewriter.
         */
        public String getTypeDeclaration() {
            return typeDeclaration;
        }

        public boolean isNullable() {
            return nullable;
        }

        public boolean isPrimaryKey() {
            return primaryKey;
        }
    }

    public static final class IndexSummary {
        private final String name;
        private final List<String> orderedColumns;
        private final boolean unique;
        private final boolean primaryKey;

        public IndexSummary(String name, List<String> orderedColumns) {
            this(name, orderedColumns, false, false);
        }

        public IndexSummary(String name, List<String> orderedColumns, boolean unique, boolean primaryKey) {
            this.name = normalize(name);
            this.orderedColumns = copyStrings(orderedColumns);
            this.unique = unique || primaryKey;
            this.primaryKey = primaryKey;
        }

        public String getName() {
            return name;
        }

        public List<String> getOrderedColumns() {
            return orderedColumns;
        }

        public boolean isUnique() {
            return unique;
        }

        public boolean isPrimaryKey() {
            return primaryKey;
        }
    }

    public static final class ForeignKeySummary {
        private final String name;
        private final List<String> orderedColumns;
        private final String referencedTable;
        private final List<String> referencedColumns;

        public ForeignKeySummary(String name, List<String> orderedColumns, String referencedTable,
                List<String> referencedColumns) {
            this.name = normalize(name);
            this.orderedColumns = copyStrings(orderedColumns);
            this.referencedTable = normalize(referencedTable);
            this.referencedColumns = copyStrings(referencedColumns);
        }

        public String getName() {
            return name;
        }

        public List<String> getOrderedColumns() {
            return orderedColumns;
        }

        public String getReferencedTable() {
            return referencedTable;
        }

        public List<String> getReferencedColumns() {
            return referencedColumns;
        }
    }

    public static final class TableSummary {
        private final String name;
        private final List<ColumnSummary> columns;
        private final List<IndexSummary> indexes;
        private final List<ForeignKeySummary> foreignKeys;

        public TableSummary(String name, List<ColumnSummary> columns, List<IndexSummary> indexes) {
            this(name, columns, indexes, List.of());
        }

        public TableSummary(String name, List<ColumnSummary> columns, List<IndexSummary> indexes,
                List<ForeignKeySummary> foreignKeys) {
            this.name = normalize(name);
            this.columns = copyValues(columns);
            this.indexes = copyValues(indexes);
            this.foreignKeys = copyValues(foreignKeys);
        }

        public String getName() {
            return name;
        }

        public List<ColumnSummary> getColumns() {
            return columns;
        }

        public List<IndexSummary> getIndexes() {
            return indexes;
        }

        public List<ForeignKeySummary> getForeignKeys() {
            return foreignKeys;
        }
    }

    private final List<TableSummary> tables;

    public QueryRewriteSchemaSummary(List<TableSummary> tables) {
        this.tables = copyValues(tables);
    }

    public static QueryRewriteSchemaSummary empty() {
        return new QueryRewriteSchemaSummary(List.of());
    }

    public List<TableSummary> getTables() {
        return tables;
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

    private static <T> List<T> copyValues(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> copy = new ArrayList<>();
        for (T value : values) {
            if (value != null) {
                copy.add(value);
            }
        }
        return List.copyOf(copy);
    }
}
