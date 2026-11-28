package sqlancer.common.perfhint.pdg;

public final class PdgColumnProfile {

    private final String table;
    private final String column;
    private final int rowCount;
    private final int nullCount;
    private final int ndv;
    private final Number minimum;
    private final Number maximum;

    public PdgColumnProfile(String table, String column, int rowCount, int nullCount, int ndv, Number minimum,
            Number maximum) {
        this.table = table;
        this.column = column;
        this.rowCount = rowCount;
        this.nullCount = nullCount;
        this.ndv = ndv;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public String getTable() {
        return table;
    }

    public String getColumn() {
        return column;
    }

    public int getRowCount() {
        return rowCount;
    }

    public int getNullCount() {
        return nullCount;
    }

    public int getNdv() {
        return ndv;
    }

    public Number getMinimum() {
        return minimum;
    }

    public Number getMaximum() {
        return maximum;
    }
}
