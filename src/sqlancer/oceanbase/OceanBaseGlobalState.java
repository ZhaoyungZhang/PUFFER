
package sqlancer.oceanbase;

import java.sql.SQLException;

import sqlancer.SQLGlobalState;
import sqlancer.common.perfhint.pdg.PdgIrContext;
import sqlancer.oceanbase.OceanBaseOptions.OceanBaseOracleFactory;

public class OceanBaseGlobalState extends SQLGlobalState<OceanBaseOptions, OceanBaseSchema> {

    private String performanceDataModelDescription;
    private PdgIrContext pdgIrContext;

    public PdgIrContext getPdgIrContext() {
        return pdgIrContext;
    }

    public void setPdgIrContext(PdgIrContext pdgIrContext) {
        this.pdgIrContext = pdgIrContext;
    }

    @Override
    protected OceanBaseSchema readSchema() throws SQLException {
        return OceanBaseSchema.fromConnection(getConnection(), getDatabaseName());
    }

    public boolean usesPQS() {
        return getDbmsSpecificOptions().oracles.stream().anyMatch(o -> o == OceanBaseOracleFactory.PQS);
    }

    public String getPerformanceDataModelDescription() {
        return performanceDataModelDescription;
    }

    public void setPerformanceDataModelDescription(String performanceDataModelDescription) {
        this.performanceDataModelDescription = performanceDataModelDescription;
    }

}
