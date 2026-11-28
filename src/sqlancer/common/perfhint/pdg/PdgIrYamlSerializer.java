package sqlancer.common.perfhint.pdg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import sqlancer.common.perfhint.PerformanceHintDataAblationProfile;

public final class PdgIrYamlSerializer {

    private PdgIrYamlSerializer() {
    }

    public static String serialize(PdgSpecification specification, List<PdgColumnProfile> profiles) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        options.setSplitLines(false);
        options.setWidth(160);
        return new Yaml(options).dump(document(specification, profiles));
    }

    private static Map<String, Object> document(PdgSpecification specification, List<PdgColumnProfile> profiles) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pdg_ir_version", PdgSpecification.IR_VERSION);
        root.put("scale_semantics_version", PdgSpecification.SCALE_SEMANTICS_VERSION);
        root.put("dbms", specification.getDbms());
        root.put("run_seed", specification.getRunSeed());
        root.put("current_scale", specification.getCurrentScale());
        if (specification.getAblationProfile() != PerformanceHintDataAblationProfile.NONE) {
            root.put("data_ablation_profile", specification.getAblationProfile().getId());
        }
        List<Object> tables = new ArrayList<>();
        for (PdgSpecification.Table table : specification.getTables()) {
            Map<String, Object> tableMap = new LinkedHashMap<>();
            tableMap.put("name", table.getName());
            tableMap.put("base_rows", table.getBaseRows());
            List<Object> columns = new ArrayList<>();
            for (PdgSpecification.Column column : table.getColumns()) {
                Map<String, Object> columnMap = new LinkedHashMap<>();
                columnMap.put("name", column.getName());
                columnMap.put("sql_type", column.getSqlType().getSql());
                PdgColumnProducer producer = column.getProducer();
                Map<String, Object> producerMap = new LinkedHashMap<>();
                producerMap.put("kind", producer.getKind().name());
                producerMap.put("model", producer.getModel());
                producerMap.put("scale_policy", producer.getScalePolicy().name());
                producerMap.put("parameters", new LinkedHashMap<>(producer.getParameters()));
                columnMap.put("producer", producerMap);
                columns.add(columnMap);
            }
            tableMap.put("columns", columns);
            tableMap.put("indexes", table.getIndexes());
            tables.add(tableMap);
        }
        root.put("tables", tables);
        List<Object> relationships = new ArrayList<>();
        for (PdgRelationship relationship : specification.getRelationships()) {
            Map<String, Object> relationshipMap = new LinkedHashMap<>();
            relationshipMap.put("id", relationship.getId());
            relationshipMap.put("type", relationship.getType());
            relationshipMap.put("left", relationship.getLeftTable() + "." + relationship.getLeftColumn());
            relationshipMap.put("right", relationship.getRightTable() + "." + relationship.getRightColumn());
            relationshipMap.put("parameters", new LinkedHashMap<>(relationship.getParameters()));
            relationships.add(relationshipMap);
        }
        root.put("relationships", relationships);
        List<Object> profileMaps = new ArrayList<>();
        for (PdgColumnProfile profile : profiles) {
            Map<String, Object> profileMap = new LinkedHashMap<>();
            profileMap.put("table", profile.getTable());
            profileMap.put("column", profile.getColumn());
            profileMap.put("row_count", profile.getRowCount());
            profileMap.put("null_count", profile.getNullCount());
            profileMap.put("ndv", profile.getNdv());
            if (profile.getMinimum() != null) {
                profileMap.put("minimum", profile.getMinimum());
                profileMap.put("maximum", profile.getMaximum());
            }
            profileMaps.add(profileMap);
        }
        root.put("column_profiles", profileMaps);
        return root;
    }
}
