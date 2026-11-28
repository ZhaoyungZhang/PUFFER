
package sqlancer.mysql;

import java.sql.SQLException;

import sqlancer.SQLGlobalState;
import sqlancer.common.perfhint.pdg.PdgIrContext;
import sqlancer.mysql.MySQLOptions.MySQLOracleFactory;

public class MySQLGlobalState extends SQLGlobalState<MySQLOptions, MySQLSchema> {

    private String performanceDataModelDescription;
    private PdgIrContext pdgIrContext;

    @Override
    protected MySQLSchema readSchema() throws SQLException {
        return MySQLSchema.fromConnection(getConnection(), getDatabaseName());
    }

    public boolean usesPQS() {
        return getDbmsSpecificOptions().oracles.stream().anyMatch(o -> o == MySQLOracleFactory.PQS);
    }

    public String getPerformanceDataModelDescription() {
        return performanceDataModelDescription;
    }

    public void setPerformanceDataModelDescription(String performanceDataModelDescription) {
        this.performanceDataModelDescription = performanceDataModelDescription;
    }

    public PdgIrContext getPdgIrContext() {
        return pdgIrContext;
    }

    public void setPdgIrContext(PdgIrContext pdgIrContext) {
        this.pdgIrContext = pdgIrContext;
    }

}
