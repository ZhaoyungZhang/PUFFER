package sqlancer.oceanbase.oracle;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.perfhint.DataAwarePredicateBinder;
import sqlancer.common.perfhint.DataAwarePredicateBinder.JoinEdgeSpec;
import sqlancer.common.perfhint.DataAwarePredicateBinder.Operator;
import sqlancer.common.perfhint.DataAwarePredicateBinder.PredicateSlot;
import sqlancer.oceanbase.OceanBaseGlobalState;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseColumn;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseTable;
import sqlancer.oceanbase.OceanBaseVisitor;
import sqlancer.oceanbase.gen.OceanBaseExpressionGenerator;
import sqlancer.oceanbase.gen.OceanBasePerformanceHintGenerator.QueryShape;
import sqlancer.oceanbase.oracle.OceanBasePerformanceHintOracle.BaseQuery;

/** Structured SELECT coverage matching the MySQL performance oracle. */
final class OceanBasePerformanceQueryGenerator {

    private OceanBasePerformanceQueryGenerator() {
    }

    static BaseQuery generate(OceanBaseGlobalState state, List<OceanBaseTable> schemaTables) {
        List<OceanBaseTable> all = schemaTables.stream().filter(t -> !t.getColumns().isEmpty())
                .collect(Collectors.toList());
        List<OceanBaseTable> indexed = all.stream().filter(t -> !t.getIndexes().isEmpty()).collect(Collectors.toList());
        if (indexed.isEmpty()) {
            throw new IgnoreMeException();
        }
        List<OceanBaseTable> selected = new ArrayList<>();
        selected.add(Randomly.fromList(indexed));
        List<OceanBaseTable> remaining = new ArrayList<>(all);
        remaining.removeAll(selected);
        int target = all.size() >= 2 && Randomly.getBoolean() ? state.getRandomly().getInteger(2,
                Math.min(OceanBasePerformanceHintOracleUtils.MAX_TABLES, all.size()) + 1) : 1;
        double bindingProb = state.getDbmsSpecificOptions() != null
                ? state.getDbmsSpecificOptions().performancePredicateBindingProbability : 0.0;
        boolean useDataAware = bindingProb > 0.0 && state.getPdgIrContext() != null
                && state.getPdgIrContext().getInMemoryIndex() != null
                && (bindingProb >= 1.0 || Randomly.getPercentage() < bindingProb);
        StringBuilder from = new StringBuilder(selected.get(0).getName());
        List<JoinEdgeSpec> joinEdges = new ArrayList<>();
        while (selected.size() < target && !remaining.isEmpty()) {
            OceanBaseTable right = Randomly.fromList(remaining);
            remaining.remove(right);
            ColumnPair pair = chooseJoinPair(state, selected, right, useDataAware);
            if (pair == null) {
                continue;
            }
            joinEdges.add(new JoinEdgeSpec(pair.left.getTable().getName(), pair.left.getName(),
                    pair.right.getTable().getName(), pair.right.getName()));
            from.append(Randomly.getBooleanWithRatherLowProbability() ? " STRAIGHT_JOIN " : " INNER JOIN ")
                    .append(right.getName()).append(" ON ").append(pair.left.getFullQualifiedName()).append(" = ")
                    .append(pair.right.getFullQualifiedName());
            selected.add(right);
        }

        List<OceanBaseColumn> columns = selected.stream().flatMap(t -> t.getColumns().stream())
                .collect(Collectors.toList());
        int count = state.getRandomly().getInteger(1, Math.min(3, columns.size()) + 1);
        List<OceanBaseColumn> fetch = new ArrayList<>(Randomly.nonEmptySubset(columns, count));
        boolean groupBy = Randomly.getBoolean() && Randomly.getBoolean();
        // Keep every projected column grouped so the query is valid even with ONLY_FULL_GROUP_BY.
        if (groupBy && fetch.size() > 2) {
            fetch = new ArrayList<>(Randomly.nonEmptySubset(fetch, 2));
        }
        boolean distinct = Randomly.getBooleanWithSmallProbability() || Randomly.getBooleanWithRatherLowProbability();
        boolean orderBy = Randomly.getBoolean() || groupBy;
        boolean limit = (orderBy || groupBy || distinct) && Randomly.getBoolean();
        // A fixed total order on the projected tuple prevents plan-dependent LIMIT ties.
        orderBy |= limit;
        boolean compoundPredicate = columns.size() > 1 && Randomly.getBooleanWithRatherLowProbability();
        List<String> tableNames = selected.stream().map(OceanBaseTable::getName).collect(Collectors.toList());
        OceanBasePerformanceHintTelemetry telemetry = state.getDbmsSpecificOptions() != null
                ? OceanBasePerformanceHintTelemetry.getInstance(state) : null;
        String predicate;
        boolean hasResultCollapsing = distinct || groupBy || limit;
        if (useDataAware) {
            List<PredicateSlot> slots = new ArrayList<>(2);
            slots.add(chooseBindingSlot(columns));
            if (compoundPredicate) {
                slots.add(chooseBindingSlot(columns));
            }
            DataAwarePredicateBinder.BindingResult binding = DataAwarePredicateBinder.bind(state.getPdgIrContext(),
                    tableNames, joinEdges, slots, true, Randomly.fromOptions(0, 1, 2), hasResultCollapsing, telemetry);
            predicate = !binding.getWhereSql().isEmpty() ? binding.getWhereSql() : predicate(state, columns);
        } else {
            predicate = predicate(state, columns);
            if (compoundPredicate) {
                predicate = "(" + predicate + ") AND (" + predicate(state, columns) + ")";
            }
            DataAwarePredicateBinder.recordUnawareTelemetry(state.getPdgIrContext(), tableNames, joinEdges, predicate,
                    telemetry);
        }
        StringBuilder sql = new StringBuilder(distinct ? "SELECT DISTINCT " : "SELECT ").append(names(fetch))
                .append(" FROM ").append(from).append(" WHERE ").append(predicate);
        if (groupBy) {
            sql.append(" GROUP BY ").append(names(fetch));
        }
        if (orderBy) {
            int orderCount = state.getRandomly().getInteger(1, Math.min(2, fetch.size()) + 1);
            List<OceanBaseColumn> ordering = new ArrayList<>(Randomly.nonEmptySubset(fetch, orderCount));
            if (limit) {
                for (OceanBaseColumn column : fetch) {
                    if (!ordering.contains(column)) {
                        ordering.add(column);
                    }
                }
            }
            // Ordering projected/grouped columns also keeps DISTINCT + ORDER BY legal.
            sql.append(" ORDER BY ")
                    .append(ordering.stream()
                            .map(c -> c.getFullQualifiedName() + (Randomly.getBoolean() ? " ASC" : " DESC"))
                            .collect(Collectors.joining(", ")));
        }
        if (limit) {
            sql.append(" LIMIT ").append(Randomly.fromOptions(1, 2, 3, 5, 8, 12));
        }
        return new BaseQuery(sql.toString(), selected, new QueryShape(true, selected.size() > 1, groupBy), fetch.size(),
                distinct, orderBy, limit, compoundPredicate);
    }

    private static String names(List<OceanBaseColumn> columns) {
        return columns.stream().map(OceanBaseColumn::getFullQualifiedName).collect(Collectors.joining(", "));
    }

    private static String predicate(OceanBaseGlobalState state, List<OceanBaseColumn> columns) {
        List<OceanBaseColumn> preferred = columns.stream().filter(OceanBaseColumn::isPrimaryKey)
                .collect(Collectors.toList());
        if (preferred.isEmpty()) {
            preferred = columns.stream().filter(c -> c.getType().isNumeric()).collect(Collectors.toList());
        }
        OceanBaseColumn column = Randomly.fromList(preferred.isEmpty() ? columns : preferred);
        String constant = OceanBaseVisitor.asString(new OceanBaseExpressionGenerator(state).generateConstant(column));
        String operator = column.getType().isNumeric() ? Randomly.fromOptions("=", "<", "<=", ">", ">=")
                : Randomly.fromOptions("=", "!=");
        return column.getFullQualifiedName() + " " + operator + " " + constant;
    }

    private static PredicateSlot chooseBindingSlot(List<OceanBaseColumn> columns) {
        List<OceanBaseColumn> preferred = columns.stream().filter(OceanBaseColumn::isPrimaryKey)
                .collect(Collectors.toList());
        if (preferred.isEmpty()) {
            preferred = columns.stream().filter(c -> c.getType().isNumeric()).collect(Collectors.toList());
        }
        OceanBaseColumn column = Randomly.fromList(preferred.isEmpty() ? columns : preferred);
        Operator op = column.getType().isNumeric()
                ? Randomly.fromOptions(Operator.EQ, Operator.LT, Operator.LTE, Operator.GT, Operator.GTE)
                : Randomly.fromOptions(Operator.EQ, Operator.NEQ);
        return new PredicateSlot(column.getTable().getName(), column.getName(), op);
    }

    static ColumnPair chooseJoinPair(List<OceanBaseTable> leftTables, OceanBaseTable right) {
        return chooseJoinPair(null, leftTables, right, false);
    }

    static ColumnPair chooseJoinPair(OceanBaseGlobalState state, List<OceanBaseTable> leftTables, OceanBaseTable right,
            boolean useDataAware) {
        List<ColumnPair> relationshipPairs = new ArrayList<>();
        List<ColumnPair> compatible = new ArrayList<>();
        List<ColumnPair> preferred = new ArrayList<>();
        for (OceanBaseTable leftTable : leftTables) {
            JoinEdgeSpec relEdge = useDataAware && state != null ? DataAwarePredicateBinder
                    .findPreferredRelationship(state.getPdgIrContext(), leftTable.getName(), right.getName()) : null;
            String relLeft = relEdge != null ? relEdge.getLeftColumn() : null;
            String relRight = relEdge != null ? relEdge.getRightColumn() : null;
            for (OceanBaseColumn left : leftTable.getColumns()) {
                for (OceanBaseColumn rightColumn : right.getColumns()) {
                    if (left.getType() == rightColumn.getType()) {
                        ColumnPair pair = new ColumnPair(left, rightColumn);
                        compatible.add(pair);
                        if (left.isPrimaryKey() || rightColumn.isPrimaryKey()) {
                            preferred.add(pair);
                        }
                        if (relLeft != null && relRight != null && left.getName().equalsIgnoreCase(relLeft)
                                && rightColumn.getName().equalsIgnoreCase(relRight)) {
                            relationshipPairs.add(pair);
                        }
                    }
                }
            }
        }
        if (compatible.isEmpty()) {
            return null;
        }
        if (!relationshipPairs.isEmpty()) {
            return Randomly.fromList(relationshipPairs);
        }
        return Randomly.fromList(preferred.isEmpty() ? compatible : preferred);
    }

    static final class ColumnPair {
        final OceanBaseColumn left;
        final OceanBaseColumn right;

        ColumnPair(OceanBaseColumn left, OceanBaseColumn right) {
            this.left = left;
            this.right = right;
        }
    }
}
