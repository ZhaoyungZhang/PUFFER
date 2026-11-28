package sqlancer.oceanbase.oracle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import sqlancer.common.perfhint.QueryRewriteSchemaSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.ColumnSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.ForeignKeySummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.IndexSummary;
import sqlancer.common.perfhint.QueryRewriteSchemaSummary.TableSummary;

/** Renders the semantic portion of a OceanBase schema as WeTune-compatible DDL. */
final class OceanBaseWeTuneSchemaDdlRenderer {

    private OceanBaseWeTuneSchemaDdlRenderer() {
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
        for (IndexSummary index : table.getIndexes()) {
            if (index.getOrderedColumns().isEmpty()) {
                continue;
            }
            String columns = columns(index.getOrderedColumns());
            if (index.isPrimaryKey()) {
                definitions.add("PRIMARY KEY (" + columns + ")");
            } else if (index.isUnique()) {
                definitions.add("UNIQUE KEY " + quote(index.getName()) + " (" + columns + ")");
            } else {
                definitions.add("KEY " + quote(index.getName()) + " (" + columns + ")");
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
    }

    private static String normalizeType(String rawType) {
        String type = rawType == null ? "" : rawType.trim();
        if ("VARCHAR".equalsIgnoreCase(type)) {
            return "VARCHAR(255)";
        }
        if ("REAL".equalsIgnoreCase(type)) {
            return "DOUBLE";
        }
        if ("BOOLEAN".equalsIgnoreCase(type)) {
            return "BOOLEAN";
        }
        return type;
    }

    private static String columns(List<String> columns) {
        List<String> quoted = new ArrayList<>();
        for (String column : columns) {
            quoted.add(quote(column));
        }
        return String.join(", ", quoted);
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
