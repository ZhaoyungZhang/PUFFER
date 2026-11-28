package sqlancer.oceanbase.oracle;

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
import sqlancer.oceanbase.OceanBaseGlobalState;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseColumn;
import sqlancer.oceanbase.OceanBaseSchema.OceanBaseTable;
import sqlancer.oceanbase.OceanBaseVisitor;
import sqlancer.oceanbase.ast.OceanBaseAggregate;
import sqlancer.oceanbase.ast.OceanBaseColumnReference;
import sqlancer.oceanbase.gen.OceanBaseExpressionGenerator;

/** Reuses native OceanBase expression nodes without migrating other SQLancer oracles. */
public final class OceanBaseExpandedQueryGenerator {
    private OceanBaseExpandedQueryGenerator() {
    }

    public static Result<OceanBaseTable, OceanBaseColumn> generate(OceanBaseGlobalState state,
            List<OceanBaseTable> tables, Family family, Consumer<String> rejected) {
        return ExpandedPerformanceQuery.generate(new Adapter(state), tables,
                state.getDbmsSpecificOptions().performanceQueryGeneratorMode, family,
                state.getOptions().getMaxExpressionDepth(), rejected);
    }

    private static final class Adapter implements Backend<OceanBaseTable, OceanBaseColumn> {
        private final OceanBaseGlobalState state;

        Adapter(OceanBaseGlobalState state) {
            this.state = state;
        }

        @Override
        public String tableName(OceanBaseTable table) {
            return table.getName();
        }

        @Override
        public List<OceanBaseColumn> columns(OceanBaseTable table) {
            return table.getColumns();
        }

        @Override
        public String columnSql(OceanBaseColumn column) {
            return column.getFullQualifiedName();
        }

        @Override
        public Kind kind(OceanBaseColumn column) {
            switch (column.getType().name()) {
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
        public boolean primaryKey(OceanBaseColumn column) {
            return column.isPrimaryKey();
        }

        @Override
        public String expression(List<OceanBaseColumn> columns, boolean predicate, PerformanceExpressionPolicy policy) {
            OceanBaseExpressionGenerator generator = new OceanBaseExpressionGenerator(state);
            generator.setColumns(columns);
            generator.setPerformanceHintPolicy(policy);
            return OceanBaseVisitor.asString(generator.generateExpression());
        }

        @Override
        public String aggregate(String function, OceanBaseColumn column, boolean distinct) {
            return OceanBaseVisitor.asString(new OceanBaseAggregate(new OceanBaseColumnReference(column, null),
                    OceanBaseAggregate.OceanBaseAggregateFunction.valueOf(function)));
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
            return OceanBasePerformanceHintTelemetry.getInstance(state);
        }
    }
}
