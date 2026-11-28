package sqlancer.common.perfhint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import sqlancer.Randomly;
import sqlancer.common.perfhint.PerformanceHintDataModel.ColumnDistributionType;
import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;
import sqlancer.common.perfhint.PerformanceHintDataPlan.ColumnPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.RelationshipPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.TablePlan;
import sqlancer.common.perfhint.pdg.PdgColumnProducer;
import sqlancer.common.perfhint.pdg.PdgIrMaterializer;
import sqlancer.common.perfhint.pdg.PdgIrPlanner;
import sqlancer.common.perfhint.pdg.PdgIrYamlSerializer;
import sqlancer.common.perfhint.pdg.PdgMaterializedDatabase;
import sqlancer.common.perfhint.pdg.PdgRelationship;
import sqlancer.common.perfhint.pdg.PdgSpecification;

public class TestPerformanceHintDataAblation {

    @Test
    public void testAblationsPreserveSchemaRowCountsIndexesAndRandomStream() {
        for (long seed = 0; seed < 20; seed++) {
            Randomly referenceRandom = new Randomly(seed);
            PerformanceHintDataPlan reference = PerformanceHintDataPlanner
                    .create(config(PerformanceHintDataAblationProfile.NONE), referenceRandom);
            long nextReferenceRandom = referenceRandom.getInteger();
            for (PerformanceHintDataAblationProfile profile : PerformanceHintDataAblationProfile.values()) {
                Randomly random = new Randomly(seed);
                PerformanceHintDataPlan actual = PerformanceHintDataPlanner.create(config(profile), random);
                assertEquals(nextReferenceRandom, random.getInteger(), profile.getId());
                assertEquals(reference.getTables().size(), actual.getTables().size());
                for (int tableIndex = 0; tableIndex < reference.getTables().size(); tableIndex++) {
                    TablePlan expected = reference.getTables().get(tableIndex);
                    TablePlan table = actual.getTables().get(tableIndex);
                    assertEquals(expected.getName(), table.getName());
                    assertEquals(expected.getRowCount(), table.getRowCount());
                    assertEquals(expected.getIndexes(), table.getIndexes());
                    assertEquals(expected.getColumns().size(), table.getColumns().size());
                    for (int columnIndex = 0; columnIndex < expected.getColumns().size(); columnIndex++) {
                        ColumnPlan column = table.getColumns().get(columnIndex);
                        assertEquals(expected.getColumns().get(columnIndex).getName(), column.getName());
                        assertEquals(expected.getColumns().get(columnIndex).getSqlType(), column.getSqlType());
                        assertEquals(
                                profile == PerformanceHintDataAblationProfile.RANDOM ? ColumnDistributionType.UNIFORM
                                        : expected.getColumns().get(columnIndex).getDistribution(),
                                column.getDistribution());
                    }
                }
                assertEquals(profile.includesIntraTableModels(), actual.getRelationships().stream()
                        .anyMatch(PerformanceHintDataPlan.RelationshipPlan::isIntraTable));
                assertEquals(profile.includesInterTableModels(),
                        actual.getRelationships().stream().anyMatch(relationship -> !relationship.isIntraTable()));
            }
        }
    }

    @Test
    public void testEveryProfileMaterializesAndRecordsItsActualRules() {
        assertEveryProfileMaterializesAndRecordsItsActualRules("mysql");
    }

    @Test
    public void testEveryMariaDBProfileMaterializesAndRecordsItsActualRules() {
        assertEveryProfileMaterializesAndRecordsItsActualRules("mariadb");
    }

    private static void assertEveryProfileMaterializesAndRecordsItsActualRules(String dbms) {
        long seed = 40273;
        for (PerformanceHintDataAblationProfile profile : PerformanceHintDataAblationProfile.values()) {
            PerformanceHintDataPlan plan = PerformanceHintDataPlanner.create(config(profile), new Randomly(seed));
            PdgSpecification specification = PdgIrPlanner.resolve(dbms, seed, plan);
            PdgMaterializedDatabase materialized = PdgIrMaterializer.materialize(specification);
            assertEquals(profile, specification.getAblationProfile());
            assertEquals(plan.getTables().size(), materialized.getRows().size());
            for (int tableIndex = 0; tableIndex < plan.getTables().size(); tableIndex++) {
                assertEquals(plan.getTables().get(tableIndex).getRowCount(),
                        materialized.getRows().get(tableIndex).size());
            }
            Map<?, ?> yaml = new Yaml().load(PdgIrYamlSerializer.serialize(specification, materialized.getProfiles()));
            assertEquals(dbms, yaml.get("dbms"));
            if (profile == PerformanceHintDataAblationProfile.NONE) {
                assertFalse(yaml.containsKey("data_ablation_profile"));
            } else {
                assertEquals(profile.getId(), yaml.get("data_ablation_profile"));
                assertTrue(specification.describe().contains("ablation=" + profile.getId()));
            }
            assertEquals(plan.getRelationships().size(), specification.getRelationships().size());
            if (profile == PerformanceHintDataAblationProfile.RANDOM) {
                specification.getTables().forEach(table -> table.getColumns().forEach(column -> {
                    assertEquals("INDEPENDENT", column.getProducer().getKind().name());
                    assertEquals("UNIFORM", column.getProducer().getModel());
                }));
            }
        }
    }

    @Test
    public void testDefaultAndFullKeepExistingGeneratedData() {
        long seed = 42;
        PerformanceHintDataGenerationConfig legacyConstructor = new PerformanceHintDataGenerationConfig(2, 4, 40, 120,
                3, 6, 3, 0.8, 1.0, 1.0);
        PerformanceHintDataPlan original = PerformanceHintDataPlanner.create(legacyConstructor, new Randomly(seed));
        PerformanceHintDataPlan full = PerformanceHintDataPlanner
                .create(config(PerformanceHintDataAblationProfile.FULL), new Randomly(seed));
        assertEquals(PerformanceHintDataAblationProfile.NONE, original.getAblationProfile());
        assertEquals(PdgIrMaterializer.materialize(PdgIrPlanner.resolve("mysql", seed, original)).getRows(),
                PdgIrMaterializer.materialize(PdgIrPlanner.resolve("mysql", seed, full)).getRows());
    }

    @Test
    public void testInitialScaleMultipliesRowCountsPreservingRelationshipsAndPrimitives() {
        long seed = 17;
        PerformanceHintDataGenerationConfig baseConfig = new PerformanceHintDataGenerationConfig(2, 4, 20, 60, 3, 6, 3,
                0.8, 1.0, 1.0, PerformanceHintDataAblationProfile.FULL, 1);
        PerformanceHintDataGenerationConfig scaledConfig = new PerformanceHintDataGenerationConfig(2, 4, 20, 60, 3, 6,
                3, 0.8, 1.0, 1.0, PerformanceHintDataAblationProfile.FULL, 10);
        PerformanceHintDataPlan basePlan = PerformanceHintDataPlanner.create(baseConfig, new Randomly(seed));
        PerformanceHintDataPlan scaledPlan = PerformanceHintDataPlanner.create(scaledConfig, new Randomly(seed));
        assertEquals(basePlan.getTables().size(), scaledPlan.getTables().size());
        assertEquals(basePlan.getRelationships().size(), scaledPlan.getRelationships().size());
        for (int i = 0; i < basePlan.getTables().size(); i++) {
            assertEquals(basePlan.getTables().get(i).getRowCount() * 10, scaledPlan.getTables().get(i).getRowCount());
        }
        PdgSpecification scaledSpec = PdgIrPlanner.resolve("mysql", seed, scaledPlan);
        PdgMaterializedDatabase materialized = PdgIrMaterializer.materialize(scaledSpec);
        for (int i = 0; i < scaledPlan.getTables().size(); i++) {
            assertEquals(scaledPlan.getTables().get(i).getRowCount(), materialized.getRows().get(i).size());
        }
        assertFalse(scaledSpec.getTriggeredPrimitives().isEmpty());
    }

    @Test
    public void testFullDecouplesIntraFromInterAndPreservesParentKeySkew() {
        int interDbs = 0;
        int skewedParentInterDbs = 0;
        int zipfHotOrSpikeParents = 0;
        for (long seed = 1; seed <= 200; seed++) {
            PerformanceHintDataGenerationConfig fullCfg = new PerformanceHintDataGenerationConfig(2, 4, 100, 1000, 3,
                    6, 3, 0.8, 0.6, 0.8, PerformanceHintDataAblationProfile.FULL, 1);
            PerformanceHintDataGenerationConfig intraCfg = new PerformanceHintDataGenerationConfig(2, 4, 100, 1000, 3,
                    6, 3, 0.8, 0.6, 0.8, PerformanceHintDataAblationProfile.COLUMN_INTRA, 1);
            PerformanceHintDataPlan fullPlan = PerformanceHintDataPlanner.create(fullCfg, new Randomly(seed));
            PerformanceHintDataPlan intraPlan = PerformanceHintDataPlanner.create(intraCfg, new Randomly(seed));

            List<RelationshipPlan> fullIntras = fullPlan.getRelationships().stream()
                    .filter(RelationshipPlan::isIntraTable).toList();
            List<RelationshipPlan> intraOnly = intraPlan.getRelationships();
            assertEquals(intraOnly.size(), fullIntras.size());
            for (int i = 0; i < intraOnly.size(); i++) {
                RelationshipPlan a = intraOnly.get(i);
                RelationshipPlan b = fullIntras.get(i);
                assertEquals(a.getLeftTable(), b.getLeftTable());
                assertEquals(a.getLeftColumn(), b.getLeftColumn());
                assertEquals(a.getRightColumn(), b.getRightColumn());
                assertTrue(a.getLeftColumn() != 0 && a.getRightColumn() != 0);
            }

            PdgSpecification fullSpec = PdgIrPlanner.resolve("mariadb", seed, fullPlan);
            Set<String> interEndpoints = new HashSet<>();
            PdgRelationship interRel = null;
            for (PdgRelationship rel : fullSpec.getRelationships()) {
                if (!rel.getLeftTable().equals(rel.getRightTable())) {
                    interEndpoints.add(rel.getLeftTable() + "." + rel.getLeftColumn());
                    interEndpoints.add(rel.getRightTable() + "." + rel.getRightColumn());
                    interRel = rel;
                }
            }
            for (PdgRelationship rel : fullSpec.getRelationships()) {
                if (rel.getLeftTable().equals(rel.getRightTable())) {
                    assertFalse(interEndpoints.contains(rel.getLeftTable() + "." + rel.getLeftColumn()),
                            "intra-table source collided with inter-table endpoint at seed=" + seed);
                    assertFalse(interEndpoints.contains(rel.getRightTable() + "." + rel.getRightColumn()),
                            "intra-table target collided with inter-table endpoint at seed=" + seed);
                }
            }
            if (interRel != null) {
                interDbs++;
                PdgSpecification.Table parent = fullSpec.table(interRel.getLeftTable());
                PdgColumnProducer parentProd = parent.getColumns().get(0).getProducer();
                Object mode = interRel.getParameters().get("parent_key_mode");
                Object fanoutType = interRel.getParameters().get("fanout_type");
                boolean skewed = "SKEWED_FANOUT".equals(interRel.getType()) || "MANY_TO_MANY".equals(fanoutType)
                        || "ZIPF_HOT".equals(mode) || "POINT_SPIKE".equals(mode);
                if (skewed) {
                    skewedParentInterDbs++;
                }
                if ("ZIPF_HOT".equals(mode) || "POINT_SPIKE".equals(mode)) {
                    zipfHotOrSpikeParents++;
                    PdgMaterializedDatabase mat = PdgIrMaterializer.materialize(fullSpec);
                    assertTrue(mat.getProfiles().get(0).getNdv() < parent.getBaseRows(),
                            "skewed parent key mode " + mode + " should reduce NDV below rowCount");
                }
                assertEquals(PdgColumnProducer.Kind.INTER_TABLE_ENDPOINT, parentProd.getKind());
            }
            if (seed <= 20) {
                PerformanceHintDataPlan randomPlan = PerformanceHintDataPlanner.create(
                        new PerformanceHintDataGenerationConfig(2, 4, 100, 1000, 3, 6, 3, 0.8, 0.6, 0.8,
                                PerformanceHintDataAblationProfile.RANDOM, 1),
                        new Randomly(seed));
                assertEquals(SqlType.BIGINT, randomPlan.getTables().get(0).getColumns().get(0).getSqlType());
                PdgMaterializedDatabase randMat = PdgIrMaterializer
                        .materialize(PdgIrPlanner.resolve("cockroachdb", seed, randomPlan));
                assertEquals(fullPlan.getTables().get(0).getRowCount(), randMat.getRows().get(0).size());
            }
        }
        assertTrue(interDbs >= 120);
        assertTrue(skewedParentInterDbs * 1.0 / interDbs >= 0.55,
                "expected majority skewed inter parents, got " + skewedParentInterDbs + "/" + interDbs);
        assertTrue(zipfHotOrSpikeParents > 0);
    }

    private static PerformanceHintDataGenerationConfig config(PerformanceHintDataAblationProfile profile) {
        return new PerformanceHintDataGenerationConfig(2, 4, 40, 120, 3, 6, 3, 0.8, 1.0, 1.0, profile);
    }
}
