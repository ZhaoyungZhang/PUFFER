package sqlancer.tidb.oracle;

import java.util.List;
import java.util.function.Consumer;

import sqlancer.common.perfhint.ExpandedPerformanceQuery;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Backend;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.JoinType;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Kind;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Result;
import sqlancer.common.perfhint.PerformanceExpressionPolicy;
import sqlancer.common.perfhint.PerformanceHintTelemetry;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode.Family;
import sqlancer.common.perfhint.pdg.PdgIrContext;
import sqlancer.tidb.TiDBExpressionGenerator;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.ast.TiDBAggregate;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.visitor.TiDBVisitor;

/** Reuses native TiDB expression nodes without migrating other SQLancer oracles. */
public final class TiDBExpandedQueryGenerator {
    private TiDBExpandedQueryGenerator() {
    }

    public static Result<TiDBTable, TiDBColumn> generate(TiDBGlobalState state, List<TiDBTable> tables, Family family,
            Consumer<String> rejected) {
        return ExpandedPerformanceQuery.generate(new Adapter(state), tables,
                state.getDbmsSpecificOptions().performanceQueryGeneratorMode, family,
                state.getOptions().getMaxExpressionDepth(), rejected);
    }

    private static final class Adapter implements Backend<TiDBTable, TiDBColumn> {
        private final TiDBGlobalState state;

        Adapter(TiDBGlobalState state) {
            this.state = state;
        }

        @Override
        public String tableName(TiDBTable table) {
            return table.getName();
        }

        @Override
        public List<TiDBColumn> columns(TiDBTable table) {
            return table.getColumns();
        }

        @Override
        public String columnSql(TiDBColumn column) {
            return column.getFullQualifiedName();
        }

        @Override
        public Kind kind(TiDBColumn column) {
            switch (column.getType().getPrimitiveDataType().name()) {
            case "INT":
            case "SERIAL":
            case "DECIMAL":
            case "NUMERIC":
                return Kind.EXACT;
            case "VARCHAR":
            case "STRING":
            case "TEXT":
            case "CHAR":
                return Kind.STRING;
            case "BOOLEAN":
            case "BOOL":
                return Kind.BOOL;
            case "REAL":
            case "DOUBLE":
            case "FLOAT":
            case "FLOATING":
                return Kind.FLOAT;
            default:
                return Kind.OTHER;
            }
        }

        @Override
        public boolean primaryKey(TiDBColumn column) {
            return column.isPrimaryKey();
        }

        @Override
        public String expression(List<TiDBColumn> columns, boolean predicate, PerformanceExpressionPolicy policy) {
            TiDBExpressionGenerator generator = new TiDBExpressionGenerator(state);
            generator.setColumns(columns);
            generator.setPerformanceHintPolicy(policy);
            return TiDBVisitor.asString(generator.generateExpression());
        }

        @Override
        public String aggregate(String function, TiDBColumn column, boolean distinct) {
            return TiDBVisitor.asString(new TiDBAggregate(List.of(new TiDBColumnReference(column)),
                    TiDBAggregate.TiDBAggregateFunction.valueOf(function)));
        }

        @Override
        public List<JoinType> joinTypes() {
            return List.of(JoinType.INNER, JoinType.LEFT, JoinType.RIGHT, JoinType.CROSS, JoinType.STRAIGHT);
        }

        @Override
        public PdgIrContext pdgIrContext() {
            return state.getPdgIrContext();
        }

        @Override
        public double predicateBindingProbability() {
            return state.getDbmsSpecificOptions() != null
                    ? state.getDbmsSpecificOptions().performancePredicateBindingProbability : 0.0;
        }

        @Override
        public PerformanceHintTelemetry telemetry() {
            return TiDBPerformanceHintTelemetry.getInstance(state);
        }
    }
}
