package sqlancer.common.perfhint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.common.perfhint.PerformanceHintDataModel.ColumnDistributionType;
import sqlancer.common.perfhint.PerformanceHintDataModel.FanoutType;
import sqlancer.common.perfhint.PerformanceHintDataModel.RelationshipType;
import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;

public final class PerformanceHintDataPlan {

    private final List<TablePlan> tables;
    private final List<RelationshipPlan> relationships;
    private final PerformanceHintDataAblationProfile ablationProfile;

    public static final class ColumnPlan {
        private final String name;
        private final SqlType sqlType;
        private final ColumnDistributionType distribution;

        public ColumnPlan(String name, SqlType sqlType, ColumnDistributionType distribution) {
            this.name = name;
            this.sqlType = sqlType;
            this.distribution = distribution;
        }

        public String getName() {
            return name;
        }

        public SqlType getSqlType() {
            return sqlType;
        }

        public ColumnDistributionType getDistribution() {
            return distribution;
        }
    }

    public static final class TablePlan {
        private final String name;
        private int rowCount;
        private final List<ColumnPlan> columns;
        private final List<List<Integer>> indexes = new ArrayList<>();

        public TablePlan(String name, int rowCount, List<ColumnPlan> columns) {
            this.name = name;
            this.rowCount = rowCount;
            this.columns = List.copyOf(columns);
        }

        public String getName() {
            return name;
        }

        public int getRowCount() {
            return rowCount;
        }

        public void setRowCount(int rowCount) {
            this.rowCount = rowCount;
        }

        public List<ColumnPlan> getColumns() {
            return columns;
        }

        public List<List<Integer>> getIndexes() {
            return Collections.unmodifiableList(indexes);
        }

        public void addIndex(List<Integer> columnIndexes) {
            List<Integer> copy = List.copyOf(columnIndexes);
            if (!indexes.contains(copy)) {
                indexes.add(copy);
            }
        }
    }

    public static final class RelationshipPlan {
        private final RelationshipType type;
        private final int leftTable;
        private final int leftColumn;
        private final int rightTable;
        private final int rightColumn;
        private final FanoutType fanoutType;
        private final int fanout;

        private RelationshipPlan(RelationshipType type, int leftTable, int leftColumn, int rightTable, int rightColumn,
                FanoutType fanoutType, int fanout) {
            this.type = type;
            this.leftTable = leftTable;
            this.leftColumn = leftColumn;
            this.rightTable = rightTable;
            this.rightColumn = rightColumn;
            this.fanoutType = fanoutType;
            this.fanout = fanout;
        }

        public static RelationshipPlan intraTable(RelationshipType type, int table, int sourceColumn,
                int dependentColumn) {
            return new RelationshipPlan(type, table, sourceColumn, table, dependentColumn, null, 1);
        }

        public static RelationshipPlan interTable(RelationshipType type, int parentTable, int parentColumn,
                int childTable, int childColumn, FanoutType fanoutType, int fanout) {
            return new RelationshipPlan(type, parentTable, parentColumn, childTable, childColumn, fanoutType, fanout);
        }

        public RelationshipType getType() {
            return type;
        }

        public int getLeftTable() {
            return leftTable;
        }

        public int getLeftColumn() {
            return leftColumn;
        }

        public int getRightTable() {
            return rightTable;
        }

        public int getRightColumn() {
            return rightColumn;
        }

        public FanoutType getFanoutType() {
            return fanoutType;
        }

        public int getFanout() {
            return fanout;
        }

        public boolean isIntraTable() {
            return leftTable == rightTable;
        }

        @Override
        public String toString() {
            String suffix = fanoutType == null ? "" : ":" + fanoutType + ":" + fanout;
            return type + "(t" + leftTable + ".c" + leftColumn + "->t" + rightTable + ".c" + rightColumn + suffix + ")";
        }
    }

    public PerformanceHintDataPlan(List<TablePlan> tables, List<RelationshipPlan> relationships) {
        this(tables, relationships, PerformanceHintDataAblationProfile.NONE);
    }

    public PerformanceHintDataPlan(List<TablePlan> tables, List<RelationshipPlan> relationships,
            PerformanceHintDataAblationProfile ablationProfile) {
        this.tables = List.copyOf(tables);
        this.relationships = List.copyOf(relationships);
        this.ablationProfile = ablationProfile;
    }

    public List<TablePlan> getTables() {
        return tables;
    }

    public List<RelationshipPlan> getRelationships() {
        return relationships;
    }

    public PerformanceHintDataAblationProfile getAblationProfile() {
        return ablationProfile;
    }

    public String describe() {
        String tableDescription = tables.stream()
                .map(table -> table.getName() + "=" + table.getRowCount() + "x" + table.getColumns().size())
                .collect(Collectors.joining(","));
        String modelDescription = tables.stream()
                .flatMap(table -> table.getColumns().stream()
                        .map(column -> table.getName() + "." + column.getName() + ":" + column.getDistribution()))
                .collect(Collectors.joining(","));
        String relationshipDescription = relationships.isEmpty() ? "none"
                : relationships.stream().map(RelationshipPlan::toString).collect(Collectors.joining(","));
        String ablation = ablationProfile == PerformanceHintDataAblationProfile.NONE ? ""
                : " ablation=" + ablationProfile.getId();
        return "mode=model-v1" + ablation + " tables=[" + tableDescription + "] models=[" + modelDescription
                + "] relationships=[" + relationshipDescription + "]";
    }
}
