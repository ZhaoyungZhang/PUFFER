package sqlancer.common.perfhint.pdg;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;
import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;

public final class PdgSpecification {

    public static final String IR_VERSION = "pdg-ir/v1";
    public static final String SCALE_SEMANTICS_VERSION = "pdg-scale/v1";

    public static final class Column {
        private final String name;
        private final SqlType sqlType;
        private final PdgColumnProducer producer;

        public Column(String name, SqlType sqlType, PdgColumnProducer producer) {
            this.name = name;
            this.sqlType = sqlType;
            this.producer = producer;
        }

        public String getName() {
            return name;
        }

        public SqlType getSqlType() {
            return sqlType;
        }

        public PdgColumnProducer getProducer() {
            return producer;
        }
    }

    public static final class Table {
        private final String name;
        private final int baseRows;
        private final List<Column> columns;
        private final List<List<String>> indexes;

        public Table(String name, int baseRows, List<Column> columns, List<List<String>> indexes) {
            this.name = name;
            this.baseRows = baseRows;
            this.columns = List.copyOf(columns);
            this.indexes = indexes.stream().map(List::copyOf).collect(Collectors.toUnmodifiableList());
        }

        public String getName() {
            return name;
        }

        public int getBaseRows() {
            return baseRows;
        }

        public List<Column> getColumns() {
            return columns;
        }

        public List<List<String>> getIndexes() {
            return indexes;
        }
    }

    private final String dbms;
    private final long runSeed;
    private final int currentScale;
    private final List<Table> tables;
    private final List<PdgRelationship> relationships;
    private final PerformanceHintDataAblationProfile ablationProfile;

    public PdgSpecification(String dbms, long runSeed, List<Table> tables, List<PdgRelationship> relationships) {
        this(dbms, runSeed, tables, relationships, PerformanceHintDataAblationProfile.NONE);
    }

    public PdgSpecification(String dbms, long runSeed, List<Table> tables, List<PdgRelationship> relationships,
            PerformanceHintDataAblationProfile ablationProfile) {
        this.dbms = dbms;
        this.runSeed = runSeed;
        this.currentScale = 1;
        this.tables = List.copyOf(tables);
        this.relationships = List.copyOf(relationships);
        this.ablationProfile = ablationProfile;
    }

    public String getDbms() {
        return dbms;
    }

    public long getRunSeed() {
        return runSeed;
    }

    public int getCurrentScale() {
        return currentScale;
    }

    public List<Table> getTables() {
        return tables;
    }

    public List<PdgRelationship> getRelationships() {
        return relationships;
    }

    public PerformanceHintDataAblationProfile getAblationProfile() {
        return ablationProfile;
    }

    public String describe() {
        String tableDescription = tables.stream()
                .map(t -> t.getName() + "=" + t.getBaseRows() + "x" + t.getColumns().size())
                .collect(Collectors.joining(","));
        String producerDescription = tables.stream()
                .flatMap(t -> t.getColumns().stream()
                        .map(c -> t.getName() + "." + c.getName() + ":" + c.getProducer().getModel()))
                .collect(Collectors.joining(","));
        String relationshipDescription = relationships
                .isEmpty()
                        ? "none"
                        : relationships.stream()
                                .map(r -> r.getType() + "(" + r.getLeftTable() + "." + r.getLeftColumn() + "->"
                                        + r.getRightTable() + "." + r.getRightColumn() + ")")
                                .collect(Collectors.joining(","));
        String ablation = ablationProfile == PerformanceHintDataAblationProfile.NONE ? ""
                : " ablation=" + ablationProfile.getId();
        return "mode=model-v1" + ablation + " pdg=" + IR_VERSION + " tables=[" + tableDescription + "] producers=["
                + producerDescription + "] relationships=[" + relationshipDescription + "]";
    }

    public Table table(String name) {
        return tables.stream().filter(table -> table.getName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown PDG table: " + name));
    }

    public List<String> getTriggeredPrimitives() {
        Set<String> primitives = new TreeSet<>();
        for (Table table : tables) {
            for (Column column : table.getColumns()) {
                PdgColumnProducer producer = column.getProducer();
                if (producer.getKind() == PdgColumnProducer.Kind.INDEPENDENT && producer.getModel() != null
                        && !"UNIFORM".equalsIgnoreCase(producer.getModel())) {
                    primitives.add(producer.getModel());
                }
            }
        }
        for (PdgRelationship relationship : relationships) {
            if (relationship.getType() != null && !relationship.getType().isEmpty()) {
                Object fanoutType = relationship.getParameters().get("fanout_type");
                if ("JOIN_FANOUT".equals(relationship.getType()) && fanoutType != null) {
                    primitives.add(relationship.getType() + ":" + fanoutType);
                } else {
                    primitives.add(relationship.getType());
                }
            }
        }
        if (primitives.isEmpty()) {
            primitives.add("UNIFORM");
        }
        return List.copyOf(primitives);
    }

    public List<Table> immutableTables() {
        return Collections.unmodifiableList(tables);
    }
}
