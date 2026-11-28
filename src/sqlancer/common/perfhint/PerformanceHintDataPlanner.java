package sqlancer.common.perfhint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import sqlancer.Randomly;
import sqlancer.common.perfhint.PerformanceHintDataModel.ColumnDistributionType;
import sqlancer.common.perfhint.PerformanceHintDataModel.FanoutType;
import sqlancer.common.perfhint.PerformanceHintDataModel.RelationshipType;
import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;
import sqlancer.common.perfhint.PerformanceHintDataPlan.ColumnPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.RelationshipPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.TablePlan;

public final class PerformanceHintDataPlanner {

    private static final List<SqlType> COLUMN_TYPES = Arrays.asList(SqlType.BIGINT, SqlType.BIGINT, SqlType.DOUBLE,
            SqlType.VARCHAR, SqlType.BIGINT, SqlType.DOUBLE, SqlType.VARCHAR, SqlType.DOUBLE);
    private static final List<ColumnDistributionType> MODELED_DISTRIBUTIONS = Arrays.asList(ColumnDistributionType.ZIPF,
            ColumnDistributionType.GAUSSIAN, ColumnDistributionType.LOW_NDV, ColumnDistributionType.MONOTONIC_ASC,
            ColumnDistributionType.MONOTONIC_DESC, ColumnDistributionType.NULL_HEAVY,
            ColumnDistributionType.POINT_SPIKE);
    private static final List<ColumnDistributionType> KEY_DISTRIBUTIONS = Arrays.asList(ColumnDistributionType.UNIFORM,
            ColumnDistributionType.ZIPF, ColumnDistributionType.LOW_NDV, ColumnDistributionType.MONOTONIC_ASC,
            ColumnDistributionType.MONOTONIC_DESC, ColumnDistributionType.POINT_SPIKE);

    private PerformanceHintDataPlanner() {
    }

    public static PerformanceHintDataPlan create(PerformanceHintDataGenerationConfig config, Randomly randomly) {
        int tableCount = randomly.getInteger(config.getMinTables(), config.getMaxTables() + 1);
        List<TablePlan> tables = createTables(config, randomly, tableCount);
        List<RelationshipPlan> relationships = createRelationships(config, randomly, tables);
        if (config.getInitialScale() > 1) {
            for (TablePlan table : tables) {
                table.setRowCount(Math.multiplyExact(table.getRowCount(), config.getInitialScale()));
            }
        }
        deriveIndexes(config, tables, relationships);
        return applyAblation(new PerformanceHintDataPlan(tables, relationships), config.getAblationProfile());
    }

    private static PerformanceHintDataPlan applyAblation(PerformanceHintDataPlan reference,
            PerformanceHintDataAblationProfile profile) {
        if (profile == PerformanceHintDataAblationProfile.NONE) {
            return reference;
        }
        // Sample the full reference first: disabling a data rule must not also
        // change table sizes, indexes, or the planner's random-number consumption.
        List<TablePlan> tables = reference.getTables();
        if (!profile.includesColumnModels()) {
            tables = new ArrayList<>();
            for (TablePlan table : reference.getTables()) {
                List<ColumnPlan> columns = new ArrayList<>();
                for (ColumnPlan column : table.getColumns()) {
                    columns.add(new ColumnPlan(column.getName(), column.getSqlType(), ColumnDistributionType.UNIFORM));
                }
                TablePlan randomTable = new TablePlan(table.getName(), table.getRowCount(), columns);
                for (List<Integer> index : table.getIndexes()) {
                    randomTable.addIndex(index);
                }
                tables.add(randomTable);
            }
        }
        List<RelationshipPlan> relationships = new ArrayList<>();
        for (RelationshipPlan relationship : reference.getRelationships()) {
            if (relationship.isIntraTable() ? profile.includesIntraTableModels() : profile.includesInterTableModels()) {
                relationships.add(relationship);
            }
        }
        return new PerformanceHintDataPlan(tables, relationships, profile);
    }

    private static List<TablePlan> createTables(PerformanceHintDataGenerationConfig config, Randomly randomly,
            int tableCount) {
        List<TablePlan> tables = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < tableCount; tableIndex++) {
            int columnCount = randomly.getInteger(config.getMinColumns(), config.getMaxColumns() + 1);
            int rowCount = randomly.getInteger(config.getMinRowsPerTable(), config.getMaxRowsPerTable() + 1);
            List<ColumnPlan> columns = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                ColumnDistributionType distribution = chooseDistribution(config, columnIndex);
                columns.add(new ColumnPlan("c" + columnIndex, COLUMN_TYPES.get(columnIndex), distribution));
            }
            tables.add(new TablePlan("t" + tableIndex, rowCount, columns));
        }
        return tables;
    }

    private static ColumnDistributionType chooseDistribution(PerformanceHintDataGenerationConfig config,
            int columnIndex) {
        if (columnIndex == 0) {
            return Randomly.fromList(KEY_DISTRIBUTIONS);
        }
        if (Randomly.getPercentage() >= config.getSingleColumnModelProbability()) {
            return ColumnDistributionType.UNIFORM;
        }
        return Randomly.fromList(MODELED_DISTRIBUTIONS);
    }

    private static List<RelationshipPlan> createRelationships(PerformanceHintDataGenerationConfig config,
            Randomly randomly, List<TablePlan> tables) {
        List<RelationshipPlan> relationships = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            int colCount = tables.get(tableIndex).getColumns().size();
            if (Randomly.getPercentage() < config.getMultiColumnModelProbability() && colCount > 1) {
                RelationshipType type = Randomly.fromOptions(RelationshipType.FUNCTIONAL_DEPENDENCY,
                        RelationshipType.ANTI_CORRELATION, RelationshipType.RANK_COUPLE, RelationshipType.COND_PATTERN);
                int sourceCol;
                int targetCol;
                if (colCount > 4) {
                    sourceCol = 1;
                    targetCol = 4;
                } else if (colCount > 2) {
                    sourceCol = 1;
                    targetCol = 2;
                } else {
                    sourceCol = 0;
                    targetCol = 1;
                }
                relationships.add(RelationshipPlan.intraTable(type, tableIndex, sourceCol, targetCol));
            }
        }
        if (tables.size() > 1 && Randomly.getPercentage() < config.getInterTableModelProbability()) {
            int childTable = randomly.getInteger(1, tables.size());
            RelationshipType interType = Randomly.fromOptions(RelationshipType.REFERENTIAL_CORRELATION,
                    RelationshipType.REFERENTIAL_CORRELATION, RelationshipType.JOIN_FANOUT, RelationshipType.JOIN_FANOUT,
                    RelationshipType.JOIN_FANOUT, RelationshipType.JOIN_FANOUT, RelationshipType.SKEWED_FANOUT,
                    RelationshipType.SKEWED_FANOUT, RelationshipType.SKEWED_FANOUT, RelationshipType.DISJOINT_RANGE);
            if (interType != RelationshipType.JOIN_FANOUT) {
                if (interType == RelationshipType.SKEWED_FANOUT) {
                    alignSkewedFanoutRowCounts(config, tables.get(0), tables.get(childTable));
                }
                relationships.add(RelationshipPlan.interTable(interType, 0, 0, childTable, 0, null, 1));
            } else {
                FanoutType fanoutType = chooseFanoutType(config);
                int maxFanout = Math.max(1, config.getMaxRowsPerTable() / config.getMinRowsPerTable());
                int fanout = fanoutType == FanoutType.ONE_TO_MANY ? randomly.getInteger(2, Math.min(5, maxFanout) + 1)
                        : 1;
                alignFanoutRowCounts(config, tables.get(0), tables.get(childTable), fanoutType, fanout);
                relationships.add(RelationshipPlan.interTable(RelationshipType.JOIN_FANOUT, 0, 0, childTable, 0,
                        fanoutType, fanout));
            }
        }
        return relationships;
    }

    private static FanoutType chooseFanoutType(PerformanceHintDataGenerationConfig config) {
        if (config.getMaxRowsPerTable() >= config.getMinRowsPerTable() * 2) {
            return Randomly.fromOptions(FanoutType.ONE_TO_ONE, FanoutType.ONE_TO_MANY, FanoutType.ONE_TO_MANY,
                    FanoutType.ONE_TO_MANY, FanoutType.MANY_TO_MANY, FanoutType.MANY_TO_MANY);
        }
        return Randomly.fromOptions(FanoutType.ONE_TO_ONE, FanoutType.MANY_TO_MANY, FanoutType.MANY_TO_MANY);
    }

    private static void alignFanoutRowCounts(PerformanceHintDataGenerationConfig config, TablePlan parent,
            TablePlan child, FanoutType fanoutType, int fanout) {
        if (fanoutType == FanoutType.ONE_TO_ONE) {
            child.setRowCount(parent.getRowCount());
        } else if (fanoutType == FanoutType.ONE_TO_MANY) {
            int maxParentRows = Math.max(1, config.getMaxRowsPerTable() / fanout);
            parent.setRowCount(Math.min(parent.getRowCount(), maxParentRows));
            child.setRowCount(parent.getRowCount() * fanout);
        }
    }

    private static void alignSkewedFanoutRowCounts(PerformanceHintDataGenerationConfig config, TablePlan parent,
            TablePlan child) {
        int cap = Math.max(config.getMinRowsPerTable(), Math.min(config.getMaxRowsPerTable(), 240));
        parent.setRowCount(Math.min(parent.getRowCount(), cap));
        child.setRowCount(Math.min(child.getRowCount(), cap));
    }

    private static void deriveIndexes(PerformanceHintDataGenerationConfig config, List<TablePlan> tables,
            List<RelationshipPlan> relationships) {
        List<Set<List<Integer>>> candidates = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            Set<List<Integer>> tableCandidates = new LinkedHashSet<>();
            tableCandidates.add(List.of(0));
            candidates.add(tableCandidates);
        }
        for (RelationshipPlan relationship : relationships) {
            if (relationship.isIntraTable()) {
                candidates.get(relationship.getLeftTable())
                        .add(List.of(relationship.getLeftColumn(), relationship.getRightColumn()));
            } else {
                candidates.get(relationship.getLeftTable()).add(List.of(relationship.getLeftColumn()));
                candidates.get(relationship.getRightTable()).add(List.of(relationship.getRightColumn()));
            }
        }
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            TablePlan table = tables.get(tableIndex);
            Set<List<Integer>> tableCandidates = candidates.get(tableIndex);
            for (int columnIndex = 0; columnIndex < table.getColumns().size(); columnIndex++) {
                ColumnDistributionType distribution = table.getColumns().get(columnIndex).getDistribution();
                switch (distribution) {
                case LOW_NDV:
                    tableCandidates.add(columnIndex == 0 ? List.of(0) : List.of(columnIndex, 0));
                    break;
                case MONOTONIC_ASC:
                case MONOTONIC_DESC:
                case NULL_HEAVY:
                case POINT_SPIKE:
                    tableCandidates.add(List.of(columnIndex));
                    break;
                default:
                    break;
                }
            }
        }
        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            int count = 0;
            for (List<Integer> index : candidates.get(tableIndex)) {
                if (count++ >= config.getMaxIndexesPerTable()) {
                    break;
                }
                tables.get(tableIndex).addIndex(index);
            }
        }
    }
}
