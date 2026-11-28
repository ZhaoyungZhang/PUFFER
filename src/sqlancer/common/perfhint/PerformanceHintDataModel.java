package sqlancer.common.perfhint;

public final class PerformanceHintDataModel {

    private PerformanceHintDataModel() {
    }

    public enum SqlType {
        BIGINT("BIGINT"), DOUBLE("DOUBLE"), VARCHAR("VARCHAR(64)");

        private final String sql;

        SqlType(String sql) {
            this.sql = sql;
        }

        public String getSql() {
            return sql;
        }
    }

    public enum ColumnDistributionType {
        UNIFORM, ZIPF, GAUSSIAN, LOW_NDV, MONOTONIC_ASC, MONOTONIC_DESC, NULL_HEAVY, POINT_SPIKE
    }

    public enum RelationshipType {
        FUNCTIONAL_DEPENDENCY, ANTI_CORRELATION, RANK_COUPLE, COND_PATTERN, REFERENTIAL_CORRELATION, JOIN_FANOUT,
        SKEWED_FANOUT, DISJOINT_RANGE
    }

    public enum FanoutType {
        ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY
    }
}
