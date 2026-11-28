package sqlancer.cockroachdb.oracle;

import java.util.List;
import java.util.function.Consumer;

import sqlancer.cockroachdb.CockroachDBProvider.CockroachDBGlobalState;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBColumn;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBDataType;
import sqlancer.cockroachdb.CockroachDBSchema.CockroachDBTable;
import sqlancer.cockroachdb.CockroachDBVisitor;
import sqlancer.cockroachdb.ast.CockroachDBAggregate;
import sqlancer.cockroachdb.ast.CockroachDBColumnReference;
import sqlancer.cockroachdb.gen.CockroachDBExpressionGenerator;
import sqlancer.common.perfhint.ExpandedPerformanceQuery;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Backend;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.JoinType;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Kind;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Result;
import sqlancer.common.perfhint.PerformanceExpressionPolicy;
import sqlancer.common.perfhint.PerformanceHintTelemetry;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode.Family;
import sqlancer.common.perfhint.pdg.PdgIrContext;

/** Reuses native CockroachDB expression nodes without migrating other SQLancer oracles. */
public final class CockroachDBExpandedQueryGenerator {
    private CockroachDBExpandedQueryGenerator() {
    }

    public static Result<CockroachDBTable, CockroachDBColumn> generate(CockroachDBGlobalState state,
            List<CockroachDBTable> tables, Family family, Consumer<String> rejected) {
        return ExpandedPerformanceQuery.generate(new Adapter(state), tables,
                state.getDbmsSpecificOptions().performanceQueryGeneratorMode, family,
                state.getOptions().getMaxExpressionDepth(), rejected);
    }

    private static final class Adapter implements Backend<CockroachDBTable, CockroachDBColumn> {
        private final CockroachDBGlobalState state;

        Adapter(CockroachDBGlobalState state) {
            this.state = state;
        }

        @Override
        public String tableName(CockroachDBTable table) {
            return table.getName();
        }

        @Override
        public List<CockroachDBColumn> columns(CockroachDBTable table) {
            return table.getColumns();
        }

        @Override
        public String columnSql(CockroachDBColumn column) {
            return column.getFullQualifiedName();
        }

        @Override
        public Kind kind(CockroachDBColumn column) {
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
        public boolean primaryKey(CockroachDBColumn column) {
            return column.isPrimaryKey();
        }

        @Override
        public String expression(List<CockroachDBColumn> columns, boolean predicate,
                PerformanceExpressionPolicy policy) {
            CockroachDBExpressionGenerator generator = new CockroachDBExpressionGenerator(state);
            generator.setColumns(columns);
            generator.setPerformanceHintPolicy(policy);
            return CockroachDBVisitor.asString(generator
                    .generateExpression(predicate ? CockroachDBDataType.BOOL.get() : CockroachDBDataType.INT.get()));
        }

        @Override
        public String aggregate(String function, CockroachDBColumn column, boolean distinct) {
            return CockroachDBVisitor.asString(
                    new CockroachDBAggregate(CockroachDBAggregate.CockroachDBAggregateFunction.valueOf(function),
                            List.of(new CockroachDBColumnReference(column))));
        }

        @Override
        public List<JoinType> joinTypes() {
            return List.of(JoinType.INNER, JoinType.LEFT, JoinType.RIGHT, JoinType.CROSS, JoinType.FULL);
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
            return CockroachDBPerformanceHintTelemetry.getInstance(state);
        }
    }
}
