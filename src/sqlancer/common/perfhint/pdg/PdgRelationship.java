package sqlancer.common.perfhint.pdg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PdgRelationship {

    private final String id;
    private final String type;
    private final String leftTable;
    private final String leftColumn;
    private final String rightTable;
    private final String rightColumn;
    private final Map<String, Object> parameters;

    public PdgRelationship(String id, String type, String leftTable, String leftColumn, String rightTable,
            String rightColumn, Map<String, Object> parameters) {
        this.id = id;
        this.type = type;
        this.leftTable = leftTable;
        this.leftColumn = leftColumn;
        this.rightTable = rightTable;
        this.rightColumn = rightColumn;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getLeftTable() {
        return leftTable;
    }

    public String getLeftColumn() {
        return leftColumn;
    }

    public String getRightTable() {
        return rightTable;
    }

    public String getRightColumn() {
        return rightColumn;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }
}
