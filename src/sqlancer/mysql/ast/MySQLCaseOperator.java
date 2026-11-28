package sqlancer.mysql.ast;

import java.util.List;

/** Adapted from SQLancer a513c877 to the local expression hierarchy. */
public class MySQLCaseOperator implements MySQLExpression {
    private final MySQLExpression switchCondition;
    private final List<MySQLExpression> conditions;
    private final List<MySQLExpression> expressions;
    private final MySQLExpression elseExpr;

    public MySQLCaseOperator(MySQLExpression switchCondition, List<MySQLExpression> whenExprs,
            List<MySQLExpression> thenExprs, MySQLExpression elseExpr) {
        if (whenExprs.isEmpty() || whenExprs.size() != thenExprs.size()) {
            throw new IllegalArgumentException("CASE requires matching nonempty WHEN/THEN lists");
        }
        this.switchCondition = switchCondition;
        this.conditions = List.copyOf(whenExprs);
        this.expressions = List.copyOf(thenExprs);
        this.elseExpr = elseExpr;
    }

    public MySQLExpression getSwitchCondition() {
        return switchCondition;
    }

    public List<MySQLExpression> getConditions() {
        return conditions;
    }

    public List<MySQLExpression> getExpressions() {
        return expressions;
    }

    public MySQLExpression getElseExpr() {
        return elseExpr;
    }

    @Override
    public MySQLConstant getExpectedValue() {
        int nrConditions = getConditions().size();

        MySQLExpression switchCondition = getSwitchCondition();
        List<MySQLExpression> whenExprs = getConditions();
        List<MySQLExpression> thenExprs = getExpressions();
        MySQLExpression elseExpr = getElseExpr();

        if (switchCondition != null) {
            MySQLConstant switchValue = switchCondition.getExpectedValue();

            for (int i = 0; i < nrConditions; i++) {
                MySQLConstant whenValue = whenExprs.get(i).getExpectedValue();
                MySQLConstant isConditionMatched = switchValue.isEquals(whenValue);
                if (!isConditionMatched.isNull() && isConditionMatched.asBooleanNotNull()) {
                    return thenExprs.get(i).getExpectedValue();
                }
            }
        } else {
            for (int i = 0; i < nrConditions; i++) {
                MySQLConstant whenValue = whenExprs.get(i).getExpectedValue();
                if (!whenValue.isNull() && whenValue.asBooleanNotNull()) {
                    return thenExprs.get(i).getExpectedValue();
                }
            }
        }

        if (elseExpr != null) {
            return elseExpr.getExpectedValue();
        }

        return MySQLConstant.createNullConstant();
    }
}
