package sqlancer.common.perfhint;

import java.util.ArrayList;
import java.util.List;

import sqlancer.Randomly;
import sqlancer.common.perfhint.PerformanceHintDataModel.ColumnDistributionType;
import sqlancer.common.perfhint.PerformanceHintDataModel.FanoutType;
import sqlancer.common.perfhint.PerformanceHintDataModel.RelationshipType;
import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;
import sqlancer.common.perfhint.PerformanceHintDataPlan.ColumnPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.RelationshipPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.TablePlan;

public final class PerformanceHintRowGenerator {

    private static final double NULL_PROBABILITY = 0.7;

    private PerformanceHintRowGenerator() {
    }

    public static List<List<List<Object>>> generate(PerformanceHintDataPlan plan, Randomly randomly) {
        List<List<List<Object>>> allRows = generateBaseRows(plan, randomly);
        applyInterTableRelationships(plan, allRows);
        applyIntraTableRelationships(plan, allRows);
        return allRows;
    }

    private static List<List<List<Object>>> generateBaseRows(PerformanceHintDataPlan plan, Randomly randomly) {
        List<List<List<Object>>> allRows = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < plan.getTables().size(); tableIndex++) {
            TablePlan table = plan.getTables().get(tableIndex);
            List<List<Object>> tableRows = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < table.getRowCount(); rowIndex++) {
                List<Object> row = new ArrayList<>();
                for (int columnIndex = 0; columnIndex < table.getColumns().size(); columnIndex++) {
                    ColumnPlan column = table.getColumns().get(columnIndex);
                    row.add(generateValue(randomly, column, tableIndex, columnIndex, rowIndex, table.getRowCount()));
                }
                tableRows.add(row);
            }
            allRows.add(tableRows);
        }
        return allRows;
    }

    private static Object generateValue(Randomly randomly, ColumnPlan column, int tableIndex, int columnIndex,
            int rowIndex, int rowCount) {
        ColumnDistributionType distribution = column.getDistribution();
        if (distribution == ColumnDistributionType.NULL_HEAVY && Randomly.getPercentage() < NULL_PROBABILITY) {
            return null;
        }
        double numericValue;
        switch (distribution) {
        case ZIPF:
            numericValue = sampleZipf(Math.max(4, Math.min(32, rowCount / 2)));
            break;
        case GAUSSIAN:
            numericValue = tableIndex * 1000.0 + columnIndex * 100.0 + sampleGaussian() * 20.0;
            break;
        case LOW_NDV:
            numericValue = rowIndex % Math.max(2, Math.min(5, Math.max(2, rowCount / 4)));
            break;
        case MONOTONIC_ASC:
            numericValue = tableIndex * 10000.0 + rowIndex * (columnIndex + 1L);
            break;
        case MONOTONIC_DESC:
            numericValue = tableIndex * 100000.0 + (rowCount - rowIndex) * (columnIndex + 1L);
            break;
        case POINT_SPIKE:
            numericValue = rowIndex % 25 == 0 ? 1000.0 * (tableIndex + 1L) + columnIndex * 100.0 + 42.0
                    : randomly.getLong(-100000, 100001);
            break;
        case NULL_HEAVY:
        case UNIFORM:
            numericValue = randomly.getLong(-100000, 100001);
            break;
        default:
            throw new AssertionError(distribution);
        }
        return convertValue(column.getSqlType(), distribution, numericValue, tableIndex, columnIndex);
    }

    private static Object convertValue(SqlType type, ColumnDistributionType distribution, double numericValue,
            int tableIndex, int columnIndex) {
        switch (type) {
        case BIGINT:
            return Math.round(numericValue);
        case DOUBLE:
            return numericValue;
        case VARCHAR:
            return distribution.name().toLowerCase() + "_" + tableIndex + "_" + columnIndex + "_"
                    + Math.round(numericValue);
        default:
            throw new AssertionError(type);
        }
    }

    static int sampleZipf(int domainSize) {
        double totalWeight = 0.0;
        for (int rank = 1; rank <= domainSize; rank++) {
            totalWeight += 1.0 / rank;
        }
        double target = Randomly.getPercentage() * totalWeight;
        double cumulative = 0.0;
        for (int rank = 1; rank <= domainSize; rank++) {
            cumulative += 1.0 / rank;
            if (cumulative >= target) {
                return rank;
            }
        }
        return domainSize;
    }

    private static double sampleGaussian() {
        double first = Math.max(Randomly.getPercentage(), 1.0e-12);
        double second = Randomly.getPercentage();
        return Math.sqrt(-2.0 * Math.log(first)) * Math.cos(2.0 * Math.PI * second);
    }

    private static void applyInterTableRelationships(PerformanceHintDataPlan plan, List<List<List<Object>>> allRows) {
        for (RelationshipPlan relationship : plan.getRelationships()) {
            if (relationship.isIntraTable()) {
                continue;
            }
            if (relationship.getType() == RelationshipType.REFERENTIAL_CORRELATION) {
                applyReferentialCorrelation(relationship, allRows);
            } else if (relationship.getType() == RelationshipType.JOIN_FANOUT) {
                applyJoinFanout(relationship, allRows);
            } else if (relationship.getType() == RelationshipType.SKEWED_FANOUT
                    || relationship.getType() == RelationshipType.DISJOINT_RANGE) {
                applyReferentialCorrelation(relationship, allRows);
            } else {
                throw new AssertionError(relationship.getType());
            }
        }
    }

    private static void applyReferentialCorrelation(RelationshipPlan relationship, List<List<List<Object>>> allRows) {
        List<List<Object>> parentRows = allRows.get(relationship.getLeftTable());
        List<List<Object>> childRows = allRows.get(relationship.getRightTable());
        assignUniqueParentKeys(relationship, parentRows);
        for (int rowIndex = 0; rowIndex < childRows.size(); rowIndex++) {
            if (rowIndex % 10 != 9) {
                Object parentValue = parentRows.get(rowIndex % parentRows.size()).get(relationship.getLeftColumn());
                childRows.get(rowIndex).set(relationship.getRightColumn(), parentValue);
            } else {
                childRows.get(rowIndex).set(relationship.getRightColumn(), Long.MIN_VALUE + rowIndex);
            }
        }
    }

    private static void applyJoinFanout(RelationshipPlan relationship, List<List<List<Object>>> allRows) {
        List<List<Object>> parentRows = allRows.get(relationship.getLeftTable());
        List<List<Object>> childRows = allRows.get(relationship.getRightTable());
        FanoutType fanoutType = relationship.getFanoutType();
        if (fanoutType == FanoutType.MANY_TO_MANY) {
            int sharedDomain = Math.max(2, Math.min(8, Math.min(parentRows.size(), childRows.size()) / 2));
            for (int rowIndex = 0; rowIndex < parentRows.size(); rowIndex++) {
                parentRows.get(rowIndex).set(relationship.getLeftColumn(), (long) (rowIndex % sharedDomain));
            }
            for (int rowIndex = 0; rowIndex < childRows.size(); rowIndex++) {
                childRows.get(rowIndex).set(relationship.getRightColumn(), (long) (rowIndex % sharedDomain));
            }
            return;
        }
        assignUniqueParentKeys(relationship, parentRows);
        for (int rowIndex = 0; rowIndex < childRows.size(); rowIndex++) {
            int parentIndex;
            if (fanoutType == FanoutType.ONE_TO_MANY) {
                parentIndex = rowIndex / relationship.getFanout();
            } else {
                if (rowIndex >= parentRows.size()) {
                    childRows.get(rowIndex).set(relationship.getRightColumn(), Long.MIN_VALUE + rowIndex);
                    continue;
                }
                parentIndex = rowIndex;
            }
            Object parentValue = parentRows.get(parentIndex).get(relationship.getLeftColumn());
            childRows.get(rowIndex).set(relationship.getRightColumn(), parentValue);
        }
    }

    private static void assignUniqueParentKeys(RelationshipPlan relationship, List<List<Object>> parentRows) {
        long base = (relationship.getLeftTable() + 1L) * 1000000L;
        for (int rowIndex = 0; rowIndex < parentRows.size(); rowIndex++) {
            parentRows.get(rowIndex).set(relationship.getLeftColumn(), base + rowIndex);
        }
    }

    private static void applyIntraTableRelationships(PerformanceHintDataPlan plan, List<List<List<Object>>> allRows) {
        for (RelationshipPlan relationship : plan.getRelationships()) {
            if (!relationship.isIntraTable()) {
                continue;
            }
            List<List<Object>> rows = allRows.get(relationship.getLeftTable());
            for (List<Object> row : rows) {
                Object source = row.get(relationship.getLeftColumn());
                if (source == null) {
                    row.set(relationship.getRightColumn(), null);
                    continue;
                }
                long sourceValue = ((Number) source).longValue();
                if (relationship.getType() == RelationshipType.FUNCTIONAL_DEPENDENCY) {
                    row.set(relationship.getRightColumn(), sourceValue * 3L + 7L);
                } else if (relationship.getType() == RelationshipType.ANTI_CORRELATION
                        || relationship.getType() == RelationshipType.RANK_COUPLE) {
                    row.set(relationship.getRightColumn(), 1000000L - sourceValue);
                } else if (relationship.getType() == RelationshipType.COND_PATTERN) {
                    row.set(relationship.getRightColumn(),
                            Math.floorMod(sourceValue, 10L) < 2L ? 900000L : sourceValue);
                } else {
                    throw new AssertionError(relationship.getType());
                }
            }
        }
    }
}
