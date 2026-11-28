package sqlancer.common.perfhint;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.perfhint.DataAwarePredicateBinder.JoinEdgeSpec;
import sqlancer.common.perfhint.DataAwarePredicateBinder.Operator;
import sqlancer.common.perfhint.DataAwarePredicateBinder.PredicateSlot;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode.Family;
import sqlancer.common.perfhint.pdg.PdgIrContext;

/**
 * Bounded SELECT composition. Scalar expressions and aggregates are rendered by the backend's own AST; this class owns
 * only clause legality, scope, and the sampling budget.
 */
public final class ExpandedPerformanceQuery {
    public enum Kind {
        EXACT, STRING, BOOL, FLOAT, OTHER
    }

    public enum JoinType {
        INNER, LEFT, RIGHT, FULL, CROSS, STRAIGHT;

        public String token() {
            return this == STRAIGHT ? "STRAIGHT_JOIN" : name() + " JOIN";
        }
    }

    public interface Backend<T, C> {
        String tableName(T table);

        List<C> columns(T table);

        String columnSql(C column);

        Kind kind(C column);

        boolean primaryKey(C column);

        String expression(List<C> columns, boolean predicate, PerformanceExpressionPolicy policy);

        String aggregate(String function, C column, boolean distinct);

        List<JoinType> joinTypes();

        default boolean distinctAggregates() {
            return false;
        }

        default PdgIrContext pdgIrContext() {
            return null;
        }

        default double predicateBindingProbability() {
            return 0.0;
        }

        default PerformanceHintTelemetry telemetry() {
            return null;
        }
    }

    public static final class Join<T, C> {
        public final int ordinal;
        public final JoinType type;
        public final T leftTable;
        public final T rightTable;
        public final C leftColumn;
        public final C rightColumn;
        public final boolean equality;

        private Join(int ordinal, JoinType type, T leftTable, T rightTable, C leftColumn, C rightColumn,
                boolean equality) {
            this.ordinal = ordinal;
            this.type = type;
            this.leftTable = leftTable;
            this.rightTable = rightTable;
            this.leftColumn = leftColumn;
            this.rightColumn = rightColumn;
            this.equality = equality;
        }
    }

    public static final class Result<T, C> {
        public final String sql;
        public final List<T> tables;
        public final List<Join<T, C>> joins;
        public final PerformanceQueryMetadata metadata;
        public final int projectionCount;
        public final boolean distinct;
        public final boolean groupBy;
        public final boolean orderBy;
        public final boolean limit;
        public final boolean where;

        // Immutable output keeps all clause facts together for backend adapters.
        private Result(String sql, List<T> tables, List<Join<T, C>> joins, PerformanceQueryMetadata metadata, // NOPMD -
                                                                                                              // immutable
                                                                                                              // backend
                                                                                                              // output
                int projectionCount, boolean distinct, boolean groupBy, boolean orderBy, boolean limit, boolean where) {
            this.sql = sql;
            this.tables = List.copyOf(tables);
            this.joins = List.copyOf(joins);
            this.metadata = metadata;
            this.projectionCount = projectionCount;
            this.distinct = distinct;
            this.groupBy = groupBy;
            this.orderBy = orderBy;
            this.limit = limit;
            this.where = where;
        }
    }

    private ExpandedPerformanceQuery() {
    }

    public static <T, C> Result<T, C> generate(Backend<T, C> backend, List<T> schemaTables,
            PerformanceQueryGenerationMode mode, Family requestedFamily, int maxDepth, Consumer<String> rejected) {
        if (requestedFamily == Family.STRUCTURED) {
            throw new IllegalArgumentException("structured queries use the legacy backend generator");
        }
        Family family = requestedFamily;
        if (family == Family.JOIN && schemaTables.size() == 1) {
            rejected.accept("fallback_single_table");
            family = Family.EXPRESSION;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return generateOnce(backend, schemaTables, mode, family, maxDepth);
            } catch (IgnoreMeException e) {
                rejected.accept(attempt == 2 ? "failed" : "retry");
            }
        }
        throw new IgnoreMeException();
    }

    private static <T, C> Result<T, C> generateOnce(Backend<T, C> backend, List<T> schemaTables,
            PerformanceQueryGenerationMode mode, Family family, int maxDepth) {
        if (schemaTables.isEmpty() || family == Family.EXPRESSION && maxDepth < 1) {
            throw new IgnoreMeException();
        }
        PerformanceQueryMetadata.Builder metadata = new PerformanceQueryMetadata.Builder(mode, family);
        PerformanceExpressionPolicy policy = new PerformanceExpressionPolicy(maxDepth, metadata);
        JoinType joinType = family == Family.JOIN ? Randomly.fromList(backend.joinTypes()) : JoinType.INNER;
        int onKind = family == Family.JOIN && joinType != JoinType.CROSS ? Randomly.fromOptions(0, 1, 2) : 0;
        int maxTables = Math.min(schemaTables.size(), joinType == JoinType.CROSS || onKind == 2 ? 2 : 3);
        int minTables = family == Family.JOIN ? 2 : 1;
        if (maxTables < minTables) {
            throw new IgnoreMeException();
        }
        int tableCount = (int) Randomly.getNotCachedInteger(minTables, maxTables + 1);
        List<T> tables = Randomly.nonEmptySubset(new ArrayList<>(schemaTables), tableCount);
        List<C> columns = tables.stream().flatMap(t -> backend.columns(t).stream())
                .filter(c -> backend.kind(c) != Kind.OTHER).collect(Collectors.toList());
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }
        metadata.node("TABLES:" + tableCount);
        PdgIrContext pdgContext = backend.pdgIrContext();
        double bindingProb = backend.predicateBindingProbability();
        boolean useDataAware = bindingProb > 0.0 && pdgContext != null && pdgContext.getInMemoryIndex() != null
                && (bindingProb >= 1.0 || Randomly.getPercentage() < bindingProb);
        List<JoinEdgeSpec> joinEdgeSpecs = new ArrayList<>();
        List<Join<T, C>> joins = new ArrayList<>();
        StringBuilder from = new StringBuilder(backend.tableName(tables.get(0)));
        for (int i = 1; i < tables.size(); i++) {
            T right = tables.get(i);
            T leftTable = tables.get(i - 1);
            C leftColumn = null;
            C rightColumn = null;
            from.append(' ').append(joinType.token()).append(' ').append(backend.tableName(right));
            metadata.feature("JOIN_" + joinType.name());
            metadata.node("JOIN:" + joinType.name());
            if (joinType != JoinType.CROSS) {
                List<Join<T, C>> pairs = new ArrayList<>();
                List<Join<T, C>> preferredPairs = new ArrayList<>();
                for (int j = 0; j < i; j++) {
                    T previous = tables.get(j);
                    JoinEdgeSpec prefEdge = useDataAware ? DataAwarePredicateBinder.findPreferredRelationship(
                            pdgContext, backend.tableName(previous), backend.tableName(right)) : null;
                    for (C l : backend.columns(previous)) {
                        for (C r : backend.columns(right)) {
                            if (backend.kind(l) != Kind.OTHER && backend.kind(l) == backend.kind(r)
                                    && (onKind != 2 || backend.kind(l) != Kind.BOOL)) {
                                Join<T, C> candidateJoin = new Join<>(i - 1, joinType, previous, right, l, r,
                                        onKind != 2);
                                pairs.add(candidateJoin);
                                if (prefEdge != null
                                        && extractColumnName(backend.columnSql(l))
                                                .equalsIgnoreCase(prefEdge.getLeftColumn())
                                        && extractColumnName(backend.columnSql(r))
                                                .equalsIgnoreCase(prefEdge.getRightColumn())) {
                                    preferredPairs.add(candidateJoin);
                                }
                            }
                        }
                    }
                }
                if (pairs.isEmpty()) {
                    throw new IgnoreMeException();
                }
                Join<T, C> pair = Randomly.fromList(!preferredPairs.isEmpty() ? preferredPairs : pairs);
                leftTable = pair.leftTable;
                leftColumn = pair.leftColumn;
                rightColumn = pair.rightColumn;
                String operator = onKind == 2 ? Randomly.fromOptions("<", "<=", ">", ">=", "!=") : "=";
                from.append(" ON (").append(backend.columnSql(leftColumn)).append(' ').append(operator).append(' ')
                        .append(backend.columnSql(rightColumn)).append(')');
                if (onKind != 2) {
                    joinEdgeSpecs.add(new JoinEdgeSpec(backend.tableName(leftTable),
                            extractColumnName(backend.columnSql(leftColumn)), backend.tableName(right),
                            extractColumnName(backend.columnSql(rightColumn))));
                }
                metadata.node("ON:" + operator);
                metadata.feature(onKind == 2 ? "NON_EQUI_JOIN" : "EQUI_JOIN");
                if (onKind == 1) {
                    List<C> scope = tables.subList(0, i + 1).stream().flatMap(t -> backend.columns(t).stream())
                            .filter(c -> backend.kind(c) != Kind.OTHER).collect(Collectors.toList());
                    metadata.node("ON_EXPRESSION");
                    from.append(" AND (").append(backend.expression(scope, true, policy)).append(')');
                    metadata.feature("COMPOUND_ON");
                }
            }
            joins.add(new Join<>(i - 1, joinType, leftTable, right, leftColumn, rightColumn,
                    onKind != 2 && joinType != JoinType.CROSS));
        }

        List<String> projections = new ArrayList<>();
        List<String> groupColumns = new ArrayList<>();
        List<String> aggregates = new ArrayList<>();
        boolean exactOutput = true;
        boolean groupBy = family == Family.AGGREGATE && Randomly.getBoolean();
        if (groupBy) {
            List<C> grouping = Randomly.nonEmptySubset(columns,
                    Math.min(columns.size(), (int) Randomly.getNotCachedInteger(1, 3)));
            for (C column : grouping) {
                String sql = backend.columnSql(column);
                groupColumns.add(sql);
                projections.add(sql);
                exactOutput &= backend.kind(column) == Kind.EXACT || backend.kind(column) == Kind.BOOL;
            }
            metadata.feature("GROUP_BY");
            metadata.node("GROUP_COLUMNS:" + groupColumns.size());
        }
        if (family == Family.AGGREGATE) {
            int count = (int) Randomly.getNotCachedInteger(1, 3);
            for (int i = 0; i < count; i++) {
                String function = Randomly.fromOptions("COUNT", "SUM", "MIN", "MAX");
                List<C> suitable = function.equals("COUNT") ? columns
                        : columns.stream().filter(c -> backend.kind(c) == Kind.EXACT).collect(Collectors.toList());
                if (suitable.isEmpty()) {
                    function = "COUNT";
                    suitable = columns;
                }
                boolean distinctAggregate = backend.distinctAggregates() && Randomly.getBoolean();
                String aggregate = backend.aggregate(function, Randomly.fromList(suitable), distinctAggregate);
                aggregates.add(aggregate);
                projections.add(aggregate);
                metadata.feature("AGGREGATE_" + function);
                metadata.node("AGGREGATE:" + function + ":" + distinctAggregate);
                if (distinctAggregate) {
                    metadata.feature("DISTINCT_AGGREGATE");
                }
            }
        } else {
            int count = Math.min(columns.size(), (int) Randomly.getNotCachedInteger(1, 4));
            for (C column : Randomly.nonEmptySubset(columns, count)) {
                projections.add(backend.columnSql(column));
                exactOutput &= backend.kind(column) == Kind.EXACT || backend.kind(column) == Kind.BOOL;
            }
            if (family == Family.EXPRESSION) {
                metadata.node("PROJECTION_EXPRESSION");
                projections.add(backend.expression(columns, false, policy));
                exactOutput = false; // Native untyped expressions do not promise an exact result type.
                metadata.feature("EXPRESSION_PROJECTION");
            }
        }
        metadata.node("PROJECTIONS:" + projections.size());
        boolean distinct = family != Family.AGGREGATE && Randomly.getBoolean() && Randomly.getBoolean();
        boolean orderBy = false;
        boolean limit = false;
        if (useDataAware) {
            orderBy = Randomly.getBoolean();
            limit = exactOutput && Randomly.getBoolean();
        }
        boolean hasResultCollapsing = distinct || groupBy || limit || family == Family.AGGREGATE;
        StringBuilder sql = new StringBuilder("SELECT ");
        if (distinct) {
            sql.append("DISTINCT ");
            metadata.feature("DISTINCT");
        }
        for (int i = 0; i < projections.size(); i++) {
            if (i != 0) {
                sql.append(", ");
            }
            sql.append(projections.get(i)).append(" AS ref").append(i);
        }
        sql.append(" FROM ").append(from);
        boolean where;
        if (useDataAware) {
            List<String> tableNames = tables.stream().map(backend::tableName).collect(Collectors.toList());
            int slotCount = columns.size() > 1 && Randomly.getBoolean() ? 2 : 1;
            List<PredicateSlot> slots = new ArrayList<>(slotCount);
            for (C col : Randomly.nonEmptySubset(columns, slotCount)) {
                String colSql = backend.columnSql(col);
                int dot = colSql.indexOf('.');
                String tName = dot >= 0 ? colSql.substring(0, dot) : tableNames.get(0);
                String cName = dot >= 0 ? colSql.substring(dot + 1) : colSql;
                Kind k = backend.kind(col);
                Operator op = k == Kind.STRING || k == Kind.BOOL ? Randomly.fromOptions(Operator.EQ, Operator.NEQ)
                        : Randomly.fromOptions(Operator.EQ, Operator.LT, Operator.LTE, Operator.GT, Operator.GTE);
                slots.add(new PredicateSlot(tName, cName, op));
            }
            boolean conjunctive = slots.size() <= 1 || Randomly.getBoolean();
            DataAwarePredicateBinder.BindingResult binding = DataAwarePredicateBinder.bind(pdgContext, tableNames,
                    joinEdgeSpecs, slots, conjunctive, Randomly.fromOptions(0, 1, 2), hasResultCollapsing,
                    backend.telemetry());
            where = !binding.getWhereSql().isEmpty();
            if (where) {
                metadata.node("WHERE_EXPRESSION");
                sql.append(" WHERE (").append(binding.getWhereSql()).append(')');
                metadata.feature("WHERE");
            }
        } else {
            where = Randomly.getBoolean();
            String whereExpr = "";
            if (where) {
                metadata.node("WHERE_EXPRESSION");
                whereExpr = backend.expression(columns, true, policy);
                sql.append(" WHERE (").append(whereExpr).append(')');
                metadata.feature("WHERE");
            }
            if (pdgContext != null && backend.telemetry() != null) {
                List<String> tableNames = tables.stream().map(backend::tableName).collect(Collectors.toList());
                DataAwarePredicateBinder.recordUnawareTelemetry(pdgContext, tableNames, joinEdgeSpecs, whereExpr,
                        backend.telemetry());
            }
        }
        if (groupBy) {
            sql.append(" GROUP BY ").append(String.join(", ", groupColumns));
        }
        if (!aggregates.isEmpty() && Randomly.getBoolean()) {
            String chosenAgg = Randomly.fromList(aggregates);
            String operator = useDataAware && chosenAgg.startsWith("COUNT(") ? Randomly.fromOptions(">=", ">", "!=")
                    : Randomly.fromOptions(">=", ">", "<", "<=", "!=");
            sql.append(" HAVING (").append(chosenAgg).append(' ').append(operator).append(" 0)");
            metadata.feature("HAVING");
            metadata.node("HAVING:" + operator);
        }
        if (!useDataAware) {
            orderBy = Randomly.getBoolean();
            limit = exactOutput && Randomly.getBoolean();
        }
        if (orderBy || limit) {
            sql.append(" ORDER BY ").append(IntStream.rangeClosed(1, projections.size()).mapToObj(Integer::toString)
                    .collect(Collectors.joining(", ")));
            metadata.feature("ORDER_BY");
            orderBy = true;
        }
        if (limit) {
            sql.append(" LIMIT ").append(Randomly.fromOptions(1, 2, 3, 5, 8, 12));
            metadata.feature("LIMIT");
        }
        return new Result<>(sql.toString(), tables, joins, metadata.build(), projections.size(), distinct, groupBy,
                orderBy, limit, where);
    }

    private static String extractColumnName(String columnSql) {
        int dot = columnSql.indexOf('.');
        return dot >= 0 ? columnSql.substring(dot + 1) : columnSql;
    }
}
