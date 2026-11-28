package sqlancer.cockroachdb.oracle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import sqlancer.common.perfhint.QueryRewriteSchemaSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.ColumnSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.ForeignKeySummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.IndexSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.TableSummary;

/**
 * Renders the semantic portion of a CockroachDB schema as PostgreSQL-style DDL understood by WeTune's PostgreSQL
 * parser. CockroachDB accepts this subset too, so the renderer deliberately avoids MySQL-only KEY syntax.
 */
final class CockroachDBWeTuneSchemaDdlRenderer {

    private CockroachDBWeTuneSchemaDdlRenderer() {
    }

    static String render(QueryRewriteSchemaSummary schema) {
        StringBuilder ddl = new StringBuilder();
        List<TableSummary> tables = new ArrayList<>(schema.getTables());
        tables.sort(Comparator.comparing(TableSummary::getName));
        for (TableSummary table : tables) {
            appendTable(ddl, table);
        }
        return ddl.toString();
    }

    private static void appendTable(StringBuilder ddl, TableSummary table) {
        List<String> definitions = new ArrayList<>();
        for (ColumnSummary column : table.getColumns()) {
            String type = normalizeType(column.getTypeDeclaration());
            if (type.isEmpty() || "UNKNOWN".equalsIgnoreCase(type)) {
                type = normalizeType(column.getType());
            }
            if (type.isEmpty() || "UNKNOWN".equalsIgnoreCase(type)) {
                continue;
            }
            StringBuilder definition = new StringBuilder(quote(column.getName())).append(' ').append(type);
            if (!column.isNullable() || column.isPrimaryKey()) {
                definition.append(" NOT NULL");
            }
            definitions.add(definition.toString());
        }
        List<String> deferredIndexes = new ArrayList<>();
        for (IndexSummary index : table.getIndexes()) {
            if (index.getOrderedColumns().isEmpty()) {
                continue;
            }
            String columns = columns(index.getOrderedColumns());
            if (index.isPrimaryKey()) {
                definitions.add("PRIMARY KEY (" + columns + ")");
            } else if (index.isUnique()) {
                definitions.add("CONSTRAINT " + quote(index.getName()) + " UNIQUE (" + columns + ")");
            } else {
                deferredIndexes.add("CREATE INDEX " + quote(index.getName()) + " ON " + quote(table.getName()) + " ("
                        + columns + ");");
            }
        }
        for (ForeignKeySummary foreignKey : table.getForeignKeys()) {
            if (foreignKey.getOrderedColumns().isEmpty() || foreignKey.getReferencedColumns().isEmpty()
                    || foreignKey.getOrderedColumns().size() != foreignKey.getReferencedColumns().size()
                    || foreignKey.getReferencedTable().isEmpty()) {
                continue;
            }
            String prefix = foreignKey.getName().isEmpty() ? "FOREIGN KEY"
                    : "CONSTRAINT " + quote(foreignKey.getName()) + " FOREIGN KEY";
            definitions.add(prefix + " (" + columns(foreignKey.getOrderedColumns()) + ") REFERENCES "
                    + quote(foreignKey.getReferencedTable()) + " (" + columns(foreignKey.getReferencedColumns()) + ")");
        }
        if (definitions.isEmpty()) {
            return;
        }
        ddl.append("CREATE TABLE ").append(quote(table.getName())).append(" (\n  ")
                .append(String.join(",\n  ", definitions)).append("\n);\n");
        for (String index : deferredIndexes) {
            ddl.append(index).append('\n');
        }
    }

    private static String normalizeType(String rawType) {
        String type = rawType == null ? "" : rawType.trim();
        String upper = type.toUpperCase(java.util.Locale.ROOT);
        if (upper.endsWith("[]")) {
            return normalizeType(type.substring(0, type.length() - 2)) + "[]";
        }
        if (upper.equals("STRING") || upper.startsWith("VARCHAR") || upper.equals("TEXT")) {
            return "TEXT";
        }
        if (upper.equals("INT") || upper.equals("INT4") || upper.equals("INTEGER") || upper.equals("SERIAL4")) {
            return "INTEGER";
        }
        if (upper.equals("SERIAL") || upper.equals("BIGSERIAL") || upper.equals("INT8") || upper.equals("INT64")
                || upper.equals("BIGINT") || upper.equals("SERIAL8")) {
            return "BIGINT";
        }
        if (upper.equals("INT2") || upper.equals("SMALLINT") || upper.equals("SERIAL2")
                || upper.equals("SMALLSERIAL")) {
            return "SMALLINT";
        }
        if (upper.equals("FLOAT") || upper.equals("FLOAT8") || upper.equals("DOUBLE") || upper.equals("REAL")) {
            return "DOUBLE PRECISION";
        }
        if (upper.equals("BOOL") || upper.equals("BOOLEAN")) {
            return "BOOLEAN";
        }
        if (upper.equals("DECIMAL") || upper.equals("NUMERIC")) {
            return "NUMERIC";
        }
        // These columns are not selected by the CockroachDB PERFORMANCE_HINT
        // generator. TEXT keeps their DDL parser-compatible without claiming
        // a PostgreSQL type spelling that WeTune does not model.
        return "TEXT";
    }

    private static String columns(List<String> columns) {
        List<String> quoted = new ArrayList<>();
        for (String column : columns) {
            quoted.add(quote(column));
        }
        return String.join(", ", quoted);
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
