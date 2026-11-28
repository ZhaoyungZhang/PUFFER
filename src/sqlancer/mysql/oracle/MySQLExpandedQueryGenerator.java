package sqlancer.mysql.oracle;

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
import sqlancer.mysql.MySQLGlobalState;
import sqlancer.mysql.MySQLSchema.MySQLColumn;
import sqlancer.mysql.MySQLSchema.MySQLTable;
import sqlancer.mysql.MySQLVisitor;
import sqlancer.mysql.ast.MySQLAggregate;
import sqlancer.mysql.ast.MySQLColumnReference;
import sqlancer.mysql.gen.MySQLExpressionGenerator;

/** Reuses native MySQL expression nodes without migrating other SQLancer oracles. */
public final class MySQLExpandedQueryGenerator {
    private MySQLExpandedQueryGenerator() {
    }

    public static Result<MySQLTable, MySQLColumn> generate(MySQLGlobalState state, List<MySQLTable> tables,
            Family family, Consumer<String> rejected) {
        return ExpandedPerformanceQuery.generate(new Adapter(state), tables,
                state.getDbmsSpecificOptions().performanceQueryGeneratorMode, family,
                state.getOptions().getMaxExpressionDepth(), rejected);
    }

    private static final class Adapter implements Backend<MySQLTable, MySQLColumn> {
        private final MySQLGlobalState state;

        Adapter(MySQLGlobalState state) {
            this.state = state;
        }

        @Override
        public String tableName(MySQLTable table) {
            return table.getName();
        }

        @Override
        public List<MySQLColumn> columns(MySQLTable table) {
            return table.getColumns();
        }

        @Override
        public String columnSql(MySQLColumn column) {
            return column.getFullQualifiedName();
        }

        @Override
        public Kind kind(MySQLColumn column) {
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
        public boolean primaryKey(MySQLColumn column) {
            return column.isPrimaryKey();
        }

        @Override
        public String expression(List<MySQLColumn> columns, boolean predicate, PerformanceExpressionPolicy policy) {
            MySQLExpressionGenerator generator = new MySQLExpressionGenerator(state);
            generator.setColumns(columns);
            generator.setPerformanceHintPolicy(policy);
            return MySQLVisitor.asString(generator.generateExpression());
        }

        @Override
        public String aggregate(String function, MySQLColumn column, boolean distinct) {
            return MySQLVisitor.asString(new MySQLAggregate(List.of(new MySQLColumnReference(column, null)),
                    MySQLAggregate.MySQLAggregateFunction.valueOf(function + (distinct ? "_DISTINCT" : ""))));
        }

        @Override
        public List<JoinType> joinTypes() {
            return List.of(JoinType.INNER, JoinType.LEFT, JoinType.RIGHT, JoinType.CROSS, JoinType.STRAIGHT);
        }

        @Override
        public boolean distinctAggregates() {
            return true;
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
            return MySQLPerformanceHintTelemetry.getInstance(state);
        }
    }
}
