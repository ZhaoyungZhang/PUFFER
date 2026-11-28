package sqlancer.common.schema;

import java.util.ArrayList;
import java.util.List;

public class TableIndex {

    private final String indexName;
    private final List<String> orderedColumns;

    protected TableIndex(String indexName) {
        this(indexName, List.of());
    }

    protected TableIndex(String indexName, List<String> orderedColumns) {
        this.indexName = indexName;
        if (orderedColumns == null || orderedColumns.isEmpty()) {
            this.orderedColumns = List.of();
        } else {
            List<String> copy = new ArrayList<>();
            for (String column : orderedColumns) {
                if (column != null) {
                    copy.add(column);
                }
            }
            this.orderedColumns = List.copyOf(copy);
        }
    }

    public static TableIndex create(String indexName) {
        return new TableIndex(indexName);
    }

    public static TableIndex create(String indexName, List<String> orderedColumns) {
        return new TableIndex(indexName, orderedColumns);
    }

    public String getIndexName() {
        return indexName;
    }

    /**
     * Key columns in index order when the schema reader can provide them. Existing callers that only require the index
     * name remain unchanged.
     */
    public List<String> getOrderedColumns() {
        return orderedColumns;
    }

    @Override
    public String toString() {
        return indexName;
    }

}
