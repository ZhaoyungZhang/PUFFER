package sqlancer.mysql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.common.schema.AbstractRelationalTable;
import sqlancer.common.schema.AbstractRowValue;
import sqlancer.common.schema.AbstractSchema;
import sqlancer.common.schema.AbstractTableColumn;
import sqlancer.common.schema.AbstractTables;
import sqlancer.common.schema.TableIndex;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.MySQLSchema.MySQLTable.MySQLEngine;
import sqlancer.mysql.ast.MySQLConstant;

public class MySQLSchema extends AbstractSchema<MySQLGlobalState, MySQLTable> {

    private static final int NR_SCHEMA_READ_TRIES = 10;

    public enum MySQLDataType {
        INT, VARCHAR, FLOAT, DOUBLE, DECIMAL;

        public static MySQLDataType getRandom(MySQLGlobalState globalState) {
            if (globalState.usesPQS()) {
                return Randomly.fromOptions(MySQLDataType.INT, MySQLDataType.VARCHAR);
            } else {
                return Randomly.fromOptions(values());
            }
        }

        public boolean isNumeric() {
            switch (this) {
            case INT:
            case DOUBLE:
            case FLOAT:
            case DECIMAL:
                return true;
            case VARCHAR:
                return false;
            default:
                throw new AssertionError(this);
            }
        }

    }

    public static class MySQLColumn extends AbstractTableColumn<MySQLTable, MySQLDataType> {

        private final boolean isPrimaryKey;
        private final int precision;
        private final String typeDeclaration;
        private final boolean nullable;

        public enum CollateSequence {
            NOCASE, RTRIM, BINARY;

            public static CollateSequence random() {
                return Randomly.fromOptions(values());
            }
        }

        public MySQLColumn(String name, MySQLDataType columnType, boolean isPrimaryKey, int precision) {
            this(name, columnType, isPrimaryKey, precision, columnType == null ? "UNKNOWN" : columnType.name(), true);
        }

        public MySQLColumn(String name, MySQLDataType columnType, boolean isPrimaryKey, int precision,
                String typeDeclaration, boolean nullable) {
            super(name, null, columnType);
            this.isPrimaryKey = isPrimaryKey;
            this.precision = precision;
            this.typeDeclaration = typeDeclaration == null ? "UNKNOWN" : typeDeclaration;
            this.nullable = nullable;
        }

        public int getPrecision() {
            return precision;
        }

        public boolean isPrimaryKey() {
            return isPrimaryKey;
        }

        public String getTypeDeclaration() {
            return typeDeclaration;
        }

        public boolean isNullable() {
            return nullable;
        }

    }

    public static class MySQLTables extends AbstractTables<MySQLTable, MySQLColumn> {

        public MySQLTables(List<MySQLTable> tables) {
            super(tables);
        }

        public MySQLRowValue getRandomRowValue(SQLConnection con) throws SQLException {
            String randomRow = String.format("SELECT %s FROM %s ORDER BY RAND() LIMIT 1", columnNamesAsString(
                    c -> c.getTable().getName() + "." + c.getName() + " AS " + c.getTable().getName() + c.getName()),
                    // columnNamesAsString(c -> "typeof(" + c.getTable().getName() + "." +
                    // c.getName() + ")")
                    tableNamesAsString());
            Map<MySQLColumn, MySQLConstant> values = new HashMap<>();
            try (Statement s = con.createStatement()) {
                ResultSet randomRowValues = s.executeQuery(randomRow);
                if (!randomRowValues.next()) {
                    throw new AssertionError("could not find random row! " + randomRow + "\n");
                }
                for (int i = 0; i < getColumns().size(); i++) {
                    MySQLColumn column = getColumns().get(i);
                    Object value;
                    int columnIndex = randomRowValues.findColumn(column.getTable().getName() + column.getName());
                    assert columnIndex == i + 1;
                    MySQLConstant constant;
                    if (randomRowValues.getString(columnIndex) == null) {
                        constant = MySQLConstant.createNullConstant();
                    } else {
                        switch (column.getType()) {
                        case INT:
                            value = randomRowValues.getLong(columnIndex);
                            constant = MySQLConstant.createIntConstant((long) value);
                            break;
                        case VARCHAR:
                            value = randomRowValues.getString(columnIndex);
                            constant = MySQLConstant.createStringConstant((String) value);
                            break;
                        default:
                            throw new AssertionError(column.getType());
                        }
                    }
                    values.put(column, constant);
                }
                assert !randomRowValues.next();
                return new MySQLRowValue(this, values);
            }

        }

    }

    private static MySQLDataType getColumnType(String typeString) {
        switch (typeString) {
        case "tinyint":
        case "smallint":
        case "mediumint":
        case "int":
        case "bigint":
            return MySQLDataType.INT;
        case "varchar":
        case "tinytext":
        case "mediumtext":
        case "text":
        case "longtext":
            return MySQLDataType.VARCHAR;
        case "double":
            return MySQLDataType.DOUBLE;
        case "float":
            return MySQLDataType.FLOAT;
        case "decimal":
            return MySQLDataType.DECIMAL;
        default:
            throw new AssertionError(typeString);
        }
    }

    public static class MySQLRowValue extends AbstractRowValue<MySQLTables, MySQLColumn, MySQLConstant> {

        MySQLRowValue(MySQLTables tables, Map<MySQLColumn, MySQLConstant> values) {
            super(tables, values);
        }

    }

    public static class MySQLTable extends AbstractRelationalTable<MySQLColumn, MySQLIndex, MySQLGlobalState> {

        public enum MySQLEngine {
            INNO_DB("InnoDB"), MY_ISAM("MyISAM"), MEMORY("MEMORY"), HEAP("HEAP"), CSV("CSV"), MERGE("MERGE"),
            ARCHIVE("ARCHIVE"), FEDERATED("FEDERATED");

            private String s;

            MySQLEngine(String s) {
                this.s = s;
            }

            public static MySQLEngine get(String val) {
                return Stream.of(values()).filter(engine -> engine.s.equalsIgnoreCase(val)).findFirst().get();
            }

        }

        private final MySQLEngine engine;
        private final List<MySQLForeignKey> foreignKeys;

        public MySQLTable(String tableName, List<MySQLColumn> columns, List<MySQLIndex> indexes, MySQLEngine engine) {
            this(tableName, columns, indexes, List.of(), engine);
        }

        public MySQLTable(String tableName, List<MySQLColumn> columns, List<MySQLIndex> indexes,
                List<MySQLForeignKey> foreignKeys, MySQLEngine engine) {
            super(tableName, columns, indexes, false /* TODO: support views */);
            this.engine = engine;
            this.foreignKeys = foreignKeys == null || foreignKeys.isEmpty() ? List.of() : List.copyOf(foreignKeys);
        }

        public MySQLEngine getEngine() {
            return engine;
        }

        public boolean hasPrimaryKey() {
            return getColumns().stream().anyMatch(c -> c.isPrimaryKey());
        }

        public List<MySQLForeignKey> getForeignKeys() {
            return foreignKeys;
        }

    }

    public static final class MySQLForeignKey {

        private final String name;
        private final List<String> orderedColumns;
        private final String referencedTable;
        private final List<String> referencedColumns;

        private MySQLForeignKey(String name, List<String> orderedColumns, String referencedTable,
                List<String> referencedColumns) {
            this.name = name;
            this.orderedColumns = orderedColumns == null || orderedColumns.isEmpty() ? List.of()
                    : List.copyOf(orderedColumns);
            this.referencedTable = referencedTable;
            this.referencedColumns = referencedColumns == null || referencedColumns.isEmpty() ? List.of()
                    : List.copyOf(referencedColumns);
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

    public static final class MySQLIndex extends TableIndex {

        private MySQLIndex(String indexName) {
            this(indexName, List.of(), false, false);
        }

        private MySQLIndex(String indexName, List<String> orderedColumns) {
            this(indexName, orderedColumns, false, false);
        }

        private MySQLIndex(String indexName, List<String> orderedColumns, boolean unique, boolean primaryKey) {
            super(indexName, orderedColumns);
            this.unique = unique || primaryKey;
            this.primaryKey = primaryKey;
        }

        private final boolean unique;
        private final boolean primaryKey;

        public static MySQLIndex create(String indexName) {
            return new MySQLIndex(indexName);
        }

        public static MySQLIndex create(String indexName, List<String> orderedColumns) {
            return new MySQLIndex(indexName, orderedColumns);
        }

        public static MySQLIndex create(String indexName, List<String> orderedColumns, boolean unique,
                boolean primaryKey) {
            return new MySQLIndex(indexName, orderedColumns, unique, primaryKey);
        }

        public boolean isUnique() {
            return unique;
        }

        public boolean isPrimaryKey() {
            return primaryKey;
        }

        @Override
        public String getIndexName() {
            if (super.getIndexName().contentEquals("PRIMARY")) {
                return "`PRIMARY`";
            } else {
                return super.getIndexName();
            }
        }

    }

    public static MySQLSchema fromConnection(SQLConnection con, String databaseName) throws SQLException {
        Exception ex = null;
        /* the loop is a workaround for https://bugs.mysql.com/bug.php?id=95929 */
        for (int i = 0; i < NR_SCHEMA_READ_TRIES; i++) {
            try {
                List<MySQLTable> databaseTables = new ArrayList<>();
                try (Statement s = con.createStatement()) {
                    try (ResultSet rs = s.executeQuery(
                            "select TABLE_NAME, ENGINE from information_schema.TABLES where table_schema = '"
                                    + databaseName + "';")) {
                        while (rs.next()) {
                            String tableName = rs.getString("TABLE_NAME");
                            String tableEngineStr = rs.getString("ENGINE");
                            MySQLEngine engine = MySQLEngine.get(tableEngineStr);
                            List<MySQLColumn> databaseColumns = getTableColumns(con, tableName, databaseName);
                            List<MySQLIndex> indexes = getIndexes(con, tableName, databaseName);
                            List<MySQLForeignKey> foreignKeys = getForeignKeys(con, tableName, databaseName);
                            MySQLTable t = new MySQLTable(tableName, databaseColumns, indexes, foreignKeys, engine);
                            for (MySQLColumn c : databaseColumns) {
                                c.setTable(t);
                            }
                            databaseTables.add(t);
                        }
                    }
                }
                return new MySQLSchema(databaseTables);
            } catch (SQLIntegrityConstraintViolationException e) {
                ex = e;
            }
        }
        throw new AssertionError(ex);
    }

    private static List<MySQLIndex> getIndexes(SQLConnection con, String tableName, String databaseName)
            throws SQLException {
        List<MySQLIndex> indexes = new ArrayList<>();
        Set<String> indexNames = new LinkedHashSet<>();
        Map<String, Map<Integer, String>> columnsByIndex = new LinkedHashMap<>();
        Map<String, Boolean> uniqueByIndex = new LinkedHashMap<>();
        try (Statement s = con.createStatement()) {
            try (ResultSet rs = s.executeQuery(String.format(
                    "SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE FROM INFORMATION_SCHEMA.STATISTICS "
                            + "WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME='%s';",
                    databaseName, tableName))) {
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    indexNames.add(indexName);
                    uniqueByIndex.merge(indexName, rs.getInt("NON_UNIQUE") == 0, Boolean::logicalAnd);
                    String columnName = rs.getString("COLUMN_NAME");
                    if (columnName != null) {
                        columnsByIndex.computeIfAbsent(indexName, ignored -> new TreeMap<>())
                                .put(rs.getInt("SEQ_IN_INDEX"), columnName);
                    }
                }
            }
        }
        for (String indexName : indexNames) {
            Map<Integer, String> orderedColumns = columnsByIndex.get(indexName);
            indexes.add(MySQLIndex.create(indexName,
                    orderedColumns == null ? List.of() : new ArrayList<>(orderedColumns.values()),
                    uniqueByIndex.getOrDefault(indexName, false), "PRIMARY".equalsIgnoreCase(indexName)));
        }
        return indexes;
    }

    private static List<MySQLColumn> getTableColumns(SQLConnection con, String tableName, String databaseName)
            throws SQLException {
        List<MySQLColumn> columns = new ArrayList<>();
        try (Statement s = con.createStatement()) {
            try (ResultSet rs = s.executeQuery("select * from information_schema.columns where table_schema = '"
                    + databaseName + "' AND TABLE_NAME='" + tableName + "'")) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    String dataType = rs.getString("DATA_TYPE");
                    int precision = rs.getInt("NUMERIC_PRECISION");
                    boolean isPrimaryKey = "PRI".equals(rs.getString("COLUMN_KEY"));
                    boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                    String typeDeclaration = rs.getString("COLUMN_TYPE");
                    MySQLColumn c = new MySQLColumn(columnName, getColumnType(dataType), isPrimaryKey, precision,
                            typeDeclaration, nullable);
                    columns.add(c);
                }
            }
        }
        return columns;
    }

    private static List<MySQLForeignKey> getForeignKeys(SQLConnection con, String tableName, String databaseName)
            throws SQLException {
        List<MySQLForeignKey> foreignKeys = new ArrayList<>();
        try (Statement s = con.createStatement()) {
            try (ResultSet rs = s.executeQuery(String.format(
                    "SELECT CONSTRAINT_NAME, ORDINAL_POSITION, COLUMN_NAME, REFERENCED_TABLE_NAME, "
                            + "REFERENCED_COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                            + "WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s' "
                            + "AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION;",
                    databaseName, tableName))) {
                String currentName = null;
                String referencedTable = null;
                List<String> columns = new ArrayList<>();
                List<String> referencedColumns = new ArrayList<>();
                while (rs.next()) {
                    String constraintName = rs.getString("CONSTRAINT_NAME");
                    if (currentName != null && !currentName.equals(constraintName)) {
                        foreignKeys.add(new MySQLForeignKey(currentName, columns, referencedTable, referencedColumns));
                        columns = new ArrayList<>();
                        referencedColumns = new ArrayList<>();
                    }
                    currentName = constraintName;
                    referencedTable = rs.getString("REFERENCED_TABLE_NAME");
                    columns.add(rs.getString("COLUMN_NAME"));
                    referencedColumns.add(rs.getString("REFERENCED_COLUMN_NAME"));
                }
                if (currentName != null) {
                    foreignKeys.add(new MySQLForeignKey(currentName, columns, referencedTable, referencedColumns));
                }
            }
        }
        return foreignKeys;
    }

    public MySQLSchema(List<MySQLTable> databaseTables) {
        super(databaseTables);
    }

    public MySQLTables getRandomTableNonEmptyTables() {
        return new MySQLTables(Randomly.nonEmptySubset(getDatabaseTables()));
    }

}
