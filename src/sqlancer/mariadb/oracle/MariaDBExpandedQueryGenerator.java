package sqlancer.mariadb.oracle;

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
import sqlancer.mariadb.MariaDBProvider.MariaDBGlobalState;
import sqlancer.mariadb.MariaDBSchema.MariaDBColumn;
import sqlancer.mariadb.MariaDBSchema.MariaDBTable;
import sqlancer.mariadb.ast.MariaDBAggregate;
import sqlancer.mariadb.ast.MariaDBColumnName;
import sqlancer.mariadb.ast.MariaDBStringVisitor;
import sqlancer.mariadb.gen.MariaDBExpressionGenerator;

/** Reuses native MariaDB expression nodes without migrating other SQLancer oracles. */
public final class MariaDBExpandedQueryGenerator {
    private MariaDBExpandedQueryGenerator() {
    }

    public static Result<MariaDBTable, MariaDBColumn> generate(MariaDBGlobalState state, List<MariaDBTable> tables,
            Family family, Consumer<String> rejected) {
        return ExpandedPerformanceQuery.generate(new Adapter(state), tables,
                state.getDbmsSpecificOptions().performanceQueryGeneratorMode, family,
                state.getOptions().getMaxExpressionDepth(), rejected);
    }

    private static final class Adapter implements Backend<MariaDBTable, MariaDBColumn> {
        private final MariaDBGlobalState state;

        Adapter(MariaDBGlobalState state) {
            this.state = state;
        }

        @Override
        public String tableName(MariaDBTable table) {
            return table.getName();
        }

        @Override
        public List<MariaDBColumn> columns(MariaDBTable table) {
            return table.getColumns();
        }

        @Override
        public String columnSql(MariaDBColumn column) {
            return column.getFullQualifiedName();
        }

        @Override
        public Kind kind(MariaDBColumn column) {
            if (column.isExactDecimal()) {
                return Kind.EXACT;
            }
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
        public boolean primaryKey(MariaDBColumn column) {
            return column.isPrimaryKey();
        }

        @Override
        public String expression(List<MariaDBColumn> columns, boolean predicate, PerformanceExpressionPolicy policy) {
            MariaDBExpressionGenerator generator = new MariaDBExpressionGenerator(state.getRandomly());
            generator.setColumns(columns);
            generator.setPerformanceHintPolicy(policy);
            MariaDBStringVisitor visitor = new MariaDBStringVisitor(true);
            visitor.visit(generator.getRandomExpression(0));
            return visitor.getString();
        }

        @Override
        public String aggregate(String function, MariaDBColumn column, boolean distinct) {
            MariaDBStringVisitor visitor = new MariaDBStringVisitor(true);
            visitor.visit(new MariaDBAggregate(new MariaDBColumnName(column),
                    MariaDBAggregate.MariaDBAggregateFunction.valueOf(function)));
            return visitor.getString();
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
            return MariaDBPerformanceHintTelemetry.getInstance(state);
        }
    }
}
