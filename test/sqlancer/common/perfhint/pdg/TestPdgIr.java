package sqlancer.common.perfhint.pdg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sqlancer.common.perfhint.DataAwarePredicateBinder;
import sqlancer.common.perfhint.DataAwarePredicateBinder.JoinEdgeSpec;
import sqlancer.common.perfhint.DataAwarePredicateBinder.Operator;
import sqlancer.common.perfhint.DataAwarePredicateBinder.PredicateSlot;
import sqlancer.common.perfhint.PerformanceHintDataModel.ColumnDistributionType;
import sqlancer.common.perfhint.PerformanceHintDataModel.FanoutType;
import sqlancer.common.perfhint.PerformanceHintDataModel.RelationshipType;
import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;
import sqlancer.common.perfhint.PerformanceHintDataPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.ColumnPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.RelationshipPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.TablePlan;
import sqlancer.mysql.MySQLGlobalState;

public class TestPdgIr {

    @Test
    public void testAllDistributionParametersAreResolved() {
        List<ColumnPlan> columns = List.of(column("c0", ColumnDistributionType.UNIFORM),
                column("c1", ColumnDistributionType.ZIPF), column("c2", ColumnDistributionType.GAUSSIAN),
                column("c3", ColumnDistributionType.LOW_NDV), column("c4", ColumnDistributionType.MONOTONIC_ASC),
                column("c5", ColumnDistributionType.MONOTONIC_DESC), column("c6", ColumnDistributionType.NULL_HEAVY),
                column("c7", ColumnDistributionType.POINT_SPIKE));
        PdgSpecification specification = PdgIrPlanner.resolve("mysql", 41L,
                new PerformanceHintDataPlan(List.of(new TablePlan("t0", 100, columns)), List.of()));

        for (PdgSpecification.Column column : specification.getTables().get(0).getColumns()) {
            assertFalse(column.getProducer().getParameters().isEmpty());
            assertNotNull(column.getProducer().getParameters().get("value_seed"));
        }
        double probability = ((Number) specification.getTables().get(0).getColumns().get(6).getProducer()
                .getParameters().get("null_probability")).doubleValue();
        assertTrue(probability >= 0.5 && probability < 0.9);
        assertEquals(32,
                specification.getTables().get(0).getColumns().get(1).getProducer().getParameters().get("domain_size"));
    }

    @Test
    public void testRelationshipsOwnFinalColumnsAndMaterializeDirectly() {
        TablePlan parent = new TablePlan("t0", 10, basicColumns());
        TablePlan child = new TablePlan("t1", 20, basicColumns());
        List<RelationshipPlan> relationships = List.of(
                RelationshipPlan.interTable(RelationshipType.JOIN_FANOUT, 0, 0, 1, 0, FanoutType.ONE_TO_MANY, 2),
                RelationshipPlan.intraTable(RelationshipType.FUNCTIONAL_DEPENDENCY, 0, 0, 1),
                RelationshipPlan.intraTable(RelationshipType.ANTI_CORRELATION, 1, 0, 1));
        PdgSpecification specification = PdgIrPlanner.resolve("mariadb", 43L,
                new PerformanceHintDataPlan(List.of(parent, child), relationships));

        assertEquals(PdgColumnProducer.Kind.INTER_TABLE_ENDPOINT,
                specification.getTables().get(0).getColumns().get(0).getProducer().getKind());
        assertEquals(PdgColumnProducer.Kind.INTRA_TABLE_DERIVED,
                specification.getTables().get(0).getColumns().get(1).getProducer().getKind());
        PdgMaterializedDatabase materialized = PdgIrMaterializer.materialize(specification);
        List<List<Object>> parentRows = materialized.getRows().get(0);
        List<List<Object>> childRows = materialized.getRows().get(1);
        for (int row = 0; row < parentRows.size(); row++) {
            long key = ((Number) parentRows.get(row).get(0)).longValue();
            assertEquals(key * 3L + 7L, parentRows.get(row).get(1));
            assertEquals(parentRows.get(row).get(0), childRows.get(row * 2).get(0));
            assertEquals(parentRows.get(row).get(0), childRows.get(row * 2 + 1).get(0));
        }
        assertEquals(10, materialized.getProfiles().get(0).getNdv());
        assertEquals(10, materialized.getProfiles().get(1).getNdv());
        assertEquals(10, materialized.getProfiles().get(3).getNdv());
        assertEquals(10, materialized.getProfiles().get(4).getNdv());
    }

    @Test
    public void testReferentialAndManyToManyRules() {
        TablePlan referentialParent = new TablePlan("t0", 10, basicColumns());
        TablePlan referentialChild = new TablePlan("t1", 20, basicColumns());
        PdgSpecification referential = PdgIrPlanner.resolve("mysql", 47L, new PerformanceHintDataPlan(
                List.of(referentialParent, referentialChild),
                List.of(RelationshipPlan.interTable(RelationshipType.REFERENTIAL_CORRELATION, 0, 0, 1, 0, null, 1))));
        List<List<List<Object>>> referenceRows = PdgIrMaterializer.materialize(referential).getRows();
        Set<Object> parentKeys = values(referenceRows.get(0), 0);
        assertEquals(10, parentKeys.size());
        int refPeriod = ((Number) referential.getRelationships().get(0).getParameters().get("match_period")).intValue();
        int refCount = ((Number) referential.getRelationships().get(0).getParameters().get("match_count")).intValue();
        for (int row = 0; row < 20; row++) {
            assertEquals(row % refPeriod < refCount, parentKeys.contains(referenceRows.get(1).get(row).get(0)));
        }

        TablePlan manyParent = new TablePlan("t0", 20, basicColumns());
        TablePlan manyChild = new TablePlan("t1", 30, basicColumns());
        PdgSpecification many = PdgIrPlanner.resolve("mysql", 53L,
                new PerformanceHintDataPlan(List.of(manyParent, manyChild), List.of(RelationshipPlan
                        .interTable(RelationshipType.JOIN_FANOUT, 0, 0, 1, 0, FanoutType.MANY_TO_MANY, 1))));
        List<List<List<Object>>> manyRows = PdgIrMaterializer.materialize(many).getRows();
        assertEquals(values(manyRows.get(0), 0), values(manyRows.get(1), 0));
        assertEquals(8, values(manyRows.get(0), 0).size());

        TablePlan oneParent = new TablePlan("t0", 12, basicColumns());
        TablePlan oneChild = new TablePlan("t1", 12, basicColumns());
        PdgSpecification oneToOne = PdgIrPlanner.resolve("mysql", 57L,
                new PerformanceHintDataPlan(List.of(oneParent, oneChild), List.of(RelationshipPlan
                        .interTable(RelationshipType.JOIN_FANOUT, 0, 0, 1, 0, FanoutType.ONE_TO_ONE, 1))));
        List<List<List<Object>>> oneRows = PdgIrMaterializer.materialize(oneToOne).getRows();
        assertEquals(values(oneRows.get(0), 0), values(oneRows.get(1), 0));
        assertEquals(12, values(oneRows.get(0), 0).size());
    }

    @Test
    public void testProfilesAndLazyStableYaml() {
        PdgSpecification specification = PdgIrPlanner
                .resolve("mysql", 59L,
                        new PerformanceHintDataPlan(
                                List.of(new TablePlan("t special", 100,
                                        List.of(column("c0", ColumnDistributionType.LOW_NDV),
                                                column("c:1", ColumnDistributionType.NULL_HEAVY), new ColumnPlan("text",
                                                        SqlType.VARCHAR, ColumnDistributionType.UNIFORM)))),
                                List.of()));
        PdgMaterializedDatabase materialized = PdgIrMaterializer.materialize(specification);
        PdgColumnProfile lowNdv = materialized.getProfiles().get(0);
        PdgColumnProfile varchar = materialized.getProfiles().get(2);
        assertEquals(100, lowNdv.getRowCount());
        assertTrue(lowNdv.getNdv() <= 12);
        assertNotNull(lowNdv.getMinimum());
        assertEquals(null, varchar.getMinimum());

        PdgIrContext context = new PdgIrContext(specification, materialized.getProfiles());
        assertFalse(context.isSerialized());
        String yaml = context.yaml();
        assertTrue(context.isSerialized());
        assertSame(yaml, context.yaml());
        assertTrue(yaml.contains("pdg_ir_version: pdg-ir/v1"));
        assertTrue(yaml.contains("scale_semantics_version: pdg-scale/v1"));
        assertTrue(yaml.contains("null_probability:"));
        assertTrue(yaml.contains("column_profiles:"));
        assertFalse(yaml.contains("checksum"));
        assertFalse(yaml.contains("quantile"));
        assertFalse(yaml.contains("predicate_candidates"));
    }

    @Test
    public void testPlanningRejectsInvalidProducerGraphsAndTypes() {
        TablePlan conflictTable = new TablePlan("t0", 10, List.of(column("c0", ColumnDistributionType.UNIFORM),
                column("c1", ColumnDistributionType.UNIFORM), column("c2", ColumnDistributionType.UNIFORM)));
        PerformanceHintDataPlan conflict = new PerformanceHintDataPlan(List.of(conflictTable),
                List.of(RelationshipPlan.intraTable(RelationshipType.FUNCTIONAL_DEPENDENCY, 0, 0, 1),
                        RelationshipPlan.intraTable(RelationshipType.ANTI_CORRELATION, 0, 2, 1)));
        assertThrows(IllegalStateException.class, () -> PdgIrPlanner.resolve("mysql", 61L, conflict));

        TablePlan cycleTable = new TablePlan("t0", 10,
                List.of(column("c0", ColumnDistributionType.UNIFORM), column("c1", ColumnDistributionType.UNIFORM)));
        PerformanceHintDataPlan cycle = new PerformanceHintDataPlan(List.of(cycleTable),
                List.of(RelationshipPlan.intraTable(RelationshipType.FUNCTIONAL_DEPENDENCY, 0, 0, 1),
                        RelationshipPlan.intraTable(RelationshipType.ANTI_CORRELATION, 0, 1, 0)));
        assertThrows(IllegalStateException.class, () -> PdgIrPlanner.resolve("mysql", 67L, cycle));

        TablePlan bigint = new TablePlan("t0", 10,
                List.of(new ColumnPlan("c0", SqlType.BIGINT, ColumnDistributionType.UNIFORM)));
        TablePlan floating = new TablePlan("t1", 10,
                List.of(new ColumnPlan("c0", SqlType.DOUBLE, ColumnDistributionType.UNIFORM)));
        PerformanceHintDataPlan mismatch = new PerformanceHintDataPlan(List.of(bigint, floating),
                List.of(RelationshipPlan.interTable(RelationshipType.REFERENTIAL_CORRELATION, 0, 0, 1, 0, null, 1)));
        assertThrows(IllegalStateException.class, () -> PdgIrPlanner.resolve("mariadb", 71L, mismatch));
    }

    @Test
    public void testRstExampleMaterializesExpectedBaseRows() {
        TablePlan r = new TablePlan("R", 100, basicColumns());
        TablePlan s = new TablePlan("S", 240, basicColumns());
        TablePlan t = new TablePlan("T", 100, basicColumns());
        PerformanceHintDataPlan plan = new PerformanceHintDataPlan(List.of(r, s, t),
                List.of(RelationshipPlan.interTable(RelationshipType.REFERENTIAL_CORRELATION, 0, 0, 1, 0, null, 1),
                        RelationshipPlan.intraTable(RelationshipType.FUNCTIONAL_DEPENDENCY, 0, 0, 1),
                        RelationshipPlan.intraTable(RelationshipType.ANTI_CORRELATION, 1, 0, 1)));
        PdgMaterializedDatabase materialized = PdgIrMaterializer.materialize(PdgIrPlanner.resolve("mysql", 73L, plan));
        assertEquals(100, materialized.getRows().get(0).size());
        assertEquals(240, materialized.getRows().get(1).size());
        assertEquals(100, materialized.getRows().get(2).size());
        Set<Object> parentKeys = values(materialized.getRows().get(0), 0);
        PdgSpecification resolved = PdgIrPlanner.resolve("mysql", 73L, plan);
        int rstPeriod = ((Number) resolved.getRelationships().get(0).getParameters().get("match_period")).intValue();
        int rstCount = ((Number) resolved.getRelationships().get(0).getParameters().get("match_count")).intValue();
        for (int row = 0; row < 240; row++) {
            assertEquals(row % rstPeriod < rstCount,
                    parentKeys.contains(materialized.getRows().get(1).get(row).get(0)));
        }
    }

    @Test
    public void testNewDatabaseReplacesGlobalContext() {
        PdgSpecification firstSpecification = PdgIrPlanner.resolve("mysql", 79L,
                new PerformanceHintDataPlan(List.of(new TablePlan("t0", 10, basicColumns())), List.of()));
        PdgSpecification secondSpecification = PdgIrPlanner.resolve("mysql", 83L,
                new PerformanceHintDataPlan(List.of(new TablePlan("t0", 20, basicColumns())), List.of()));
        PdgIrContext first = new PdgIrContext(firstSpecification,
                PdgIrMaterializer.materialize(firstSpecification).getProfiles());
        PdgIrContext second = new PdgIrContext(secondSpecification,
                PdgIrMaterializer.materialize(secondSpecification).getProfiles());
        MySQLGlobalState state = new MySQLGlobalState();

        state.setPdgIrContext(first);
        assertSame(first, state.getPdgIrContext());
        state.setPdgIrContext(second);
        assertSame(second, state.getPdgIrContext());
        assertFalse(first.isSerialized());
        assertFalse(second.isSerialized());
    }

    @Test
    public void testSection4NewPrimitivesAndDataAwarePredicateBinder() {
        TablePlan t0 = new TablePlan("t0", 120, List.of(column("c0", ColumnDistributionType.POINT_SPIKE),
                column("c1", ColumnDistributionType.UNIFORM), column("c2", ColumnDistributionType.MONOTONIC_DESC)));
        TablePlan t1 = new TablePlan("t1", 120, List.of(column("c0", ColumnDistributionType.ZIPF),
                column("c1", ColumnDistributionType.UNIFORM), column("c2", ColumnDistributionType.LOW_NDV)));
        TablePlan t2 = new TablePlan("t2", 120, List.of(column("c0", ColumnDistributionType.UNIFORM),
                column("c1", ColumnDistributionType.UNIFORM), column("c2", ColumnDistributionType.UNIFORM)));

        List<RelationshipPlan> relationships = List.of(
                RelationshipPlan.interTable(RelationshipType.SKEWED_FANOUT, 0, 0, 1, 0, null, 1),
                RelationshipPlan.interTable(RelationshipType.DISJOINT_RANGE, 0, 2, 2, 0, null, 1),
                RelationshipPlan.intraTable(RelationshipType.RANK_COUPLE, 0, 0, 1),
                RelationshipPlan.intraTable(RelationshipType.COND_PATTERN, 1, 0, 1),
                RelationshipPlan.intraTable(RelationshipType.FUNCTIONAL_DEPENDENCY, 2, 0, 1));

        PdgSpecification spec = PdgIrPlanner.resolve("mysql", 101L,
                new PerformanceHintDataPlan(List.of(t0, t1, t2), relationships));
        PdgMaterializedDatabase materialized = PdgIrMaterializer.materialize(spec);
        assertNotNull(materialized.getInMemoryIndex());
        PdgIrContext context = new PdgIrContext(spec, materialized.getProfiles(), materialized.getInMemoryIndex());

        assertNotNull(DataAwarePredicateBinder.findPreferredRelationship(context, "t0", "t1"));
        List<JoinEdgeSpec> joinEdges = List.of(new JoinEdgeSpec("t0", "c0", "t1", "c0"));
        List<PredicateSlot> slots = List.of(new PredicateSlot("t0", "c1", Operator.GTE),
                new PredicateSlot("t1", "c1", Operator.EQ));
        DataAwarePredicateBinder.BindingResult binding = DataAwarePredicateBinder.bind(context, List.of("t0", "t1"),
                joinEdges, slots, true, 1, null);
        assertEquals(2, binding.getBoundPredicates().size());
        assertTrue(binding.getJoinCardinality() > 0L);
        assertTrue(binding.getResultCardinality() > 0L);
        assertFalse(binding.getWhereSql().isEmpty());

        // Plain SELECT (hasResultCollapsing=false) over MANY_TO_MANY fan-out must avoid extreme >15,000-row explosions
        // while preserving OR disjunctions without forcing OR -> AND rewrites.
        TablePlan m0 = new TablePlan("t0", 800,
                List.of(column("c0", ColumnDistributionType.LOW_NDV), column("c1", ColumnDistributionType.UNIFORM)));
        TablePlan m1 = new TablePlan("t1", 800,
                List.of(column("c0", ColumnDistributionType.LOW_NDV), column("c1", ColumnDistributionType.UNIFORM)));
        PdgSpecification manySpec = PdgIrPlanner.resolve("mysql", 404L,
                new PerformanceHintDataPlan(List.of(m0, m1), List.of(RelationshipPlan
                        .interTable(RelationshipType.JOIN_FANOUT, 0, 0, 1, 0, FanoutType.MANY_TO_MANY, 4))));
        PdgMaterializedDatabase manyMat = PdgIrMaterializer.materialize(manySpec);
        PdgIrContext manyCtx = new PdgIrContext(manySpec, manyMat.getProfiles(), manyMat.getInMemoryIndex());
        List<JoinEdgeSpec> manyEdges = List.of(new JoinEdgeSpec("t0", "c0", "t1", "c0"));
        List<PredicateSlot> orRangeSlots = List.of(new PredicateSlot("t0", "c1", Operator.LT),
                new PredicateSlot("t1", "c1", Operator.GT));
        DataAwarePredicateBinder.BindingResult plainBinding = DataAwarePredicateBinder.bind(manyCtx,
                List.of("t0", "t1"), manyEdges, orRangeSlots, false, 2, false, null);
        assertTrue(plainBinding.getResultCardinality() > 0L);
        assertTrue(plainBinding.getResultCardinality() <= 15000L);
        assertFalse(plainBinding.isConjunctive());
        assertTrue(plainBinding.getWhereSql().contains(" OR "));

        DataAwarePredicateBinder.BindingResult collapseBinding = DataAwarePredicateBinder.bind(manyCtx,
                List.of("t0", "t1"), manyEdges, orRangeSlots, true, 2, true, null);
        assertTrue(collapseBinding.getResultCardinality() > 0L);
        assertTrue(DataAwarePredicateBinder.hasResultCollapsingSql("SELECT DISTINCT t0.c0 FROM t0"));
        assertFalse(DataAwarePredicateBinder.hasResultCollapsingSql("SELECT t0.c0 FROM t0 WHERE t0.c1 = 1"));
    }

    @Test
    public void testSkewedParentKeyMaterializationForReferentialAndOneToMany() {
        List<ColumnPlan> zipfParentCols = List.of(column("c0", ColumnDistributionType.ZIPF),
                column("c1", ColumnDistributionType.UNIFORM),
                new ColumnPlan("c2", SqlType.DOUBLE, ColumnDistributionType.GAUSSIAN));
        List<ColumnPlan> spikeParentCols = List.of(column("c0", ColumnDistributionType.POINT_SPIKE),
                column("c1", ColumnDistributionType.UNIFORM),
                new ColumnPlan("c2", SqlType.DOUBLE, ColumnDistributionType.GAUSSIAN));
        for (String dbms : List.of("mysql", "mariadb", "tidb", "cockroachdb", "oceanbase")) {
            TablePlan refParent = new TablePlan("t0", 120, zipfParentCols);
            TablePlan refChild = new TablePlan("t1", 120, basicColumns());
            PdgSpecification refSpec = PdgIrPlanner.resolve(dbms, 501L, new PerformanceHintDataPlan(
                    List.of(refParent, refChild),
                    List.of(RelationshipPlan.interTable(RelationshipType.REFERENTIAL_CORRELATION, 0, 0, 1, 0, null,
                            1))));
            assertEquals("ZIPF_HOT", refSpec.getRelationships().get(0).getParameters().get("parent_key_mode"));
            PdgMaterializedDatabase refMat = PdgIrMaterializer.materialize(refSpec);
            assertTrue(refMat.getProfiles().get(0).getNdv() < 120);
            Set<Object> parentKeys = values(refMat.getRows().get(0), 0);
            int period = ((Number) refSpec.getRelationships().get(0).getParameters().get("match_period")).intValue();
            int count = ((Number) refSpec.getRelationships().get(0).getParameters().get("match_count")).intValue();
            for (int r = 0; r < 120; r++) {
                assertEquals(r % period < count, parentKeys.contains(refMat.getRows().get(1).get(r).get(0)));
            }

            TablePlan otmParent = new TablePlan("t0", 100, spikeParentCols);
            TablePlan otmChild = new TablePlan("t1", 200, basicColumns());
            PdgSpecification otmSpec = PdgIrPlanner.resolve(dbms, 503L, new PerformanceHintDataPlan(
                    List.of(otmParent, otmChild),
                    List.of(RelationshipPlan.interTable(RelationshipType.JOIN_FANOUT, 0, 0, 1, 0,
                            FanoutType.ONE_TO_MANY, 2))));
            assertEquals("POINT_SPIKE", otmSpec.getRelationships().get(0).getParameters().get("parent_key_mode"));
            PdgMaterializedDatabase otmMat = PdgIrMaterializer.materialize(otmSpec);
            assertTrue(otmMat.getProfiles().get(0).getNdv() < 100);
            for (int r = 0; r < 100; r++) {
                Object pk = otmMat.getRows().get(0).get(r).get(0);
                assertEquals(pk, otmMat.getRows().get(1).get(r * 2).get(0));
                assertEquals(pk, otmMat.getRows().get(1).get(r * 2 + 1).get(0));
            }
        }
    }

    @Test
    public void testIntraTableDoubleTargetColumnMaterialization() {
        for (RelationshipType relType : List.of(RelationshipType.FUNCTIONAL_DEPENDENCY,
                RelationshipType.ANTI_CORRELATION, RelationshipType.RANK_COUPLE, RelationshipType.COND_PATTERN)) {
            TablePlan table = new TablePlan("t0", 60,
                    List.of(column("c0", ColumnDistributionType.ZIPF), column("c1", ColumnDistributionType.LOW_NDV),
                            new ColumnPlan("c2", SqlType.DOUBLE, ColumnDistributionType.UNIFORM),
                            new ColumnPlan("c3", SqlType.VARCHAR, ColumnDistributionType.UNIFORM)));
            PdgSpecification spec = PdgIrPlanner.resolve("mysql", 709L, new PerformanceHintDataPlan(List.of(table),
                    List.of(RelationshipPlan.intraTable(relType, 0, 1, 2))));
            PdgMaterializedDatabase mat = PdgIrMaterializer.materialize(spec);
            assertEquals(60, mat.getRows().get(0).size());
            for (List<Object> row : mat.getRows().get(0)) {
                assertTrue(row.get(2) instanceof Double);
            }
        }
    }

    private static ColumnPlan column(String name, ColumnDistributionType distribution) {
        return new ColumnPlan(name, SqlType.BIGINT, distribution);
    }

    private static List<ColumnPlan> basicColumns() {
        return List.of(column("c0", ColumnDistributionType.LOW_NDV), column("c1", ColumnDistributionType.UNIFORM),
                new ColumnPlan("c2", SqlType.DOUBLE, ColumnDistributionType.GAUSSIAN));
    }

    private static Set<Object> values(List<List<Object>> rows, int column) {
        Set<Object> result = new HashSet<>();
        for (List<Object> row : rows) {
            result.add(row.get(column));
        }
        return result;
    }
}
