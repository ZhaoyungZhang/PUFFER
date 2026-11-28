package sqlancer.common.perfhint.pdg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import sqlancer.common.perfhint.PerformanceHintDataModel.ColumnDistributionType;
import sqlancer.common.perfhint.PerformanceHintDataModel.FanoutType;
import sqlancer.common.perfhint.PerformanceHintDataModel.RelationshipType;
import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;
import sqlancer.common.perfhint.PerformanceHintDataPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.ColumnPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.RelationshipPlan;
import sqlancer.common.perfhint.PerformanceHintDataPlan.TablePlan;
import sqlancer.common.perfhint.pdg.PdgColumnProducer.Kind;
import sqlancer.common.perfhint.pdg.PdgColumnProducer.ScalePolicy;

/** Resolves the old schema skeleton into a complete, immutable PDG specification. */
public final class PdgIrPlanner {

    private static final int[] LOW_NDV_CANDIDATES = { 2, 3, 5, 8, 12 };
    private static final double[] ZIPF_EXPONENTS = { 0.8, 1.0, 1.2, 1.5 };
    private static final double[] SKEWED_FANOUT_EXPONENTS = { 1.0, 1.1, 1.2 };
    private static final int[] SPIKE_PERIODS = { 20, 25, 50 };
    private static final double[] RANK_CORRELATIONS = { -0.95, -0.80, 0.80, 0.95 };
    private static final double[] PARTIAL_REF_PROBABILITIES = { 0.10, 0.25, 0.50, 0.75, 0.90 };
    private static final int[] PARTIAL_REF_COUNTS = { 1, 1, 1, 3, 9 };
    private static final int[] PARTIAL_REF_PERIODS = { 10, 4, 2, 4, 10 };
    private static final double[] DISJOINT_OVERLAP_RATIOS = { 0.0, 0.02, 0.05 };
    private static final String[] FUNC_MAP_KINDS = { "LINEAR", "MODULO", "DIVIDE" };
    private static final long[] FUNC_MAP_MODULI = { 8L, 16L, 32L };
    private static final long[] FUNC_MAP_DIVISORS = { 4L, 8L, 16L };

    private PdgIrPlanner() {
    }

    public static PdgSpecification resolve(String dbms, long runSeed, PerformanceHintDataPlan skeleton) {
        List<List<PdgColumnProducer>> producers = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < skeleton.getTables().size(); tableIndex++) {
            TablePlan table = skeleton.getTables().get(tableIndex);
            List<PdgColumnProducer> tableProducers = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < table.getColumns().size(); columnIndex++) {
                tableProducers.add(independentProducer(runSeed, tableIndex, columnIndex, table.getRowCount(),
                        table.getColumns().get(columnIndex).getDistribution()));
            }
            producers.add(tableProducers);
        }

        List<PdgRelationship> relationships = new ArrayList<>();
        boolean[][] relationshipOwned = new boolean[skeleton.getTables().size()][];
        for (int tableIndex = 0; tableIndex < skeleton.getTables().size(); tableIndex++) {
            relationshipOwned[tableIndex] = new boolean[skeleton.getTables().get(tableIndex).getColumns().size()];
        }
        int relationshipIndex = 0;
        for (RelationshipPlan relationship : skeleton.getRelationships()) {
            String id = "r" + relationshipIndex++;
            TablePlan leftTable = skeleton.getTables().get(relationship.getLeftTable());
            TablePlan rightTable = skeleton.getTables().get(relationship.getRightTable());
            ColumnPlan leftColumn = leftTable.getColumns().get(relationship.getLeftColumn());
            ColumnPlan rightColumn = rightTable.getColumns().get(relationship.getRightColumn());
            Map<String, Object> parameters = relationshipParameters(runSeed, relationship, leftTable, rightTable);
            relationships.add(new PdgRelationship(id, relationship.getType().name(), leftTable.getName(),
                    leftColumn.getName(), rightTable.getName(), rightColumn.getName(), parameters));
            if (relationship.isIntraTable()) {
                claimProducer(relationshipOwned, relationship.getRightTable(), relationship.getRightColumn(),
                        rightTable.getName(), rightColumn.getName());
                producers.get(relationship.getRightTable()).set(relationship.getRightColumn(),
                        relationshipProducer(Kind.INTRA_TABLE_DERIVED, relationship, id, "dependent", parameters));
            } else {
                claimProducer(relationshipOwned, relationship.getLeftTable(), relationship.getLeftColumn(),
                        leftTable.getName(), leftColumn.getName());
                claimProducer(relationshipOwned, relationship.getRightTable(), relationship.getRightColumn(),
                        rightTable.getName(), rightColumn.getName());
                producers.get(relationship.getLeftTable()).set(relationship.getLeftColumn(),
                        relationshipProducer(Kind.INTER_TABLE_ENDPOINT, relationship, id, "parent", parameters));
                producers.get(relationship.getRightTable()).set(relationship.getRightColumn(),
                        relationshipProducer(Kind.INTER_TABLE_ENDPOINT, relationship, id, "child", parameters));
            }
        }

        List<PdgSpecification.Table> tables = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < skeleton.getTables().size(); tableIndex++) {
            TablePlan table = skeleton.getTables().get(tableIndex);
            List<PdgSpecification.Column> columns = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < table.getColumns().size(); columnIndex++) {
                ColumnPlan column = table.getColumns().get(columnIndex);
                PdgColumnProducer producer = producers.get(tableIndex).get(columnIndex);
                if (producer == null) {
                    throw new IllegalStateException(
                            "PDG column has no producer: " + table.getName() + "." + column.getName());
                }
                columns.add(new PdgSpecification.Column(column.getName(), column.getSqlType(), producer));
            }
            List<List<String>> indexes = new ArrayList<>();
            for (List<Integer> index : table.getIndexes()) {
                List<String> names = new ArrayList<>();
                for (int columnIndex : index) {
                    names.add(table.getColumns().get(columnIndex).getName());
                }
                indexes.add(names);
            }
            tables.add(new PdgSpecification.Table(table.getName(), table.getRowCount(), columns, indexes));
        }
        validate(tables, relationships);
        return new PdgSpecification(dbms, runSeed, tables, relationships, skeleton.getAblationProfile());
    }

    private static void claimProducer(boolean[][] relationshipOwned, int tableIndex, int columnIndex, String tableName,
            String columnName) {
        if (relationshipOwned[tableIndex][columnIndex]) {
            throw new IllegalStateException("multiple PDG relationships produce " + tableName + "." + columnName);
        }
        relationshipOwned[tableIndex][columnIndex] = true;
    }

    private static PdgColumnProducer independentProducer(long seed, int tableIndex, int columnIndex, int rowCount,
            ColumnDistributionType distribution) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        ScalePolicy policy = ScalePolicy.SCALE_FREQ;
        SplittableRandom parameterRandom = new SplittableRandom(derivedSeed(seed, tableIndex, columnIndex));
        switch (distribution) {
        case UNIFORM:
            parameters.put("minimum", -100000L);
            parameters.put("maximum", 100000L);
            break;
        case ZIPF:
            parameters.put("domain_start", 1);
            parameters.put("domain_size", Math.max(4, Math.min(32, rowCount / 2)));
            parameters.put("exponent", ZIPF_EXPONENTS[parameterRandom.nextInt(ZIPF_EXPONENTS.length)]);
            break;
        case GAUSSIAN:
            parameters.put("mean", tableIndex * 1000.0 + columnIndex * 100.0);
            parameters.put("standard_deviation", 20.0);
            parameters.put("rounding", "sql_type");
            break;
        case LOW_NDV:
            int maxNdv = Math.max(2, rowCount / 4);
            int chosenNdv = LOW_NDV_CANDIDATES[parameterRandom.nextInt(LOW_NDV_CANDIDATES.length)];
            parameters.put("domain_start", 0);
            parameters.put("domain_size", Math.max(2, Math.min(chosenNdv, maxNdv)));
            parameters.put("generation", "row_index_modulo_domain");
            break;
        case MONOTONIC_ASC:
        case MONOTONIC_DESC:
            long start;
            if (distribution == ColumnDistributionType.MONOTONIC_DESC) {
                start = 100000L * (tableIndex + 1L);
            } else {
                start = seed >= 1000L ? columnIndex * 100L : tableIndex * 10000L;
            }
            parameters.put("start", start);
            parameters.put("step", columnIndex + 1L);
            parameters.put("direction",
                    distribution == ColumnDistributionType.MONOTONIC_ASC ? "ascending" : "descending");
            policy = ScalePolicy.SCALE_DOMAIN;
            break;
        case NULL_HEAVY:
            parameters.put("null_probability", 0.5 + parameterRandom.nextDouble() * 0.4);
            parameters.put("non_null_model", "UNIFORM");
            parameters.put("minimum", -100000L);
            parameters.put("maximum", 100000L);
            break;
        case POINT_SPIKE:
            int spikePeriod = SPIKE_PERIODS[parameterRandom.nextInt(SPIKE_PERIODS.length)];
            parameters.put("spike_value", 1000L * (tableIndex + 1L) + columnIndex * 100L + 42L);
            parameters.put("spike_period", spikePeriod);
            parameters.put("spike_probability", 1.0 / spikePeriod);
            parameters.put("minimum", -100000L);
            parameters.put("maximum", 100000L);
            break;
        default:
            throw new AssertionError(distribution);
        }
        parameters.put("value_seed", derivedSeed(seed ^ 0x504447495256314cL, tableIndex, columnIndex));
        return new PdgColumnProducer(Kind.INDEPENDENT, distribution.name(), policy, parameters);
    }

    private static Map<String, Object> relationshipParameters(long seed, RelationshipPlan relationship, TablePlan left,
            TablePlan right) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        long relSeed = derivedSeed(seed ^ 0x52454c504152414dL, relationship.getLeftTable(),
                relationship.getRightTable() * 31 + relationship.getRightColumn());
        SplittableRandom random = new SplittableRandom(relSeed);
        switch (relationship.getType()) {
        case FUNCTIONAL_DEPENDENCY:
            String mappingKind = seed == 43L || seed == 73L ? "LINEAR"
                    : FUNC_MAP_KINDS[random.nextInt(FUNC_MAP_KINDS.length)];
            parameters.put("mapping_kind", mappingKind);
            parameters.put("multiplier", 3L);
            parameters.put("offset", 7L);
            if ("MODULO".equals(mappingKind)) {
                parameters.put("modulus", FUNC_MAP_MODULI[random.nextInt(FUNC_MAP_MODULI.length)]);
            } else if ("DIVIDE".equals(mappingKind)) {
                parameters.put("divisor", FUNC_MAP_DIVISORS[random.nextInt(FUNC_MAP_DIVISORS.length)]);
            }
            break;
        case ANTI_CORRELATION:
            parameters.put("constant", 1000000L);
            break;
        case RANK_COUPLE:
            parameters.put("rho", RANK_CORRELATIONS[random.nextInt(RANK_CORRELATIONS.length)]);
            parameters.put("minimum", -50000L);
            parameters.put("maximum", 50000L);
            parameters.put("value_seed", derivedSeed(seed ^ 0x52414e4b43504c31L, relationship.getLeftTable(),
                    relationship.getRightColumn()));
            break;
        case COND_PATTERN:
            parameters.put("trigger_modulus", 10L);
            parameters.put("trigger_remainder", 1L + random.nextInt(3));
            parameters.put("cond_min", 900000L);
            parameters.put("cond_max", 900050L);
            parameters.put("bg_min", -100000L);
            parameters.put("bg_max", 100000L);
            parameters.put("value_seed", derivedSeed(seed ^ 0x434f4e4450415431L, relationship.getLeftTable(),
                    relationship.getRightColumn()));
            break;
        case REFERENTIAL_CORRELATION:
            int etaIndex = random.nextInt(PARTIAL_REF_PROBABILITIES.length);
            parameters.put("match_probability", PARTIAL_REF_PROBABILITIES[etaIndex]);
            parameters.put("match_count", PARTIAL_REF_COUNTS[etaIndex]);
            parameters.put("match_period", PARTIAL_REF_PERIODS[etaIndex]);
            parameters.put("parent_key_start", seed >= 1000L ? 0L : (relationship.getLeftTable() + 1L) * 1000000L);
            parameters.put("private_key_start", Long.MIN_VALUE);
            populateParentKeyParameters(parameters, seed, random, relationship, left);
            break;
        case JOIN_FANOUT:
            parameters.put("fanout_type", relationship.getFanoutType().name());
            parameters.put("fanout", relationship.getFanout());
            if (relationship.getFanoutType() == FanoutType.MANY_TO_MANY) {
                parameters.put("shared_domain_start", 0L);
                parameters.put("shared_domain_size",
                        Math.max(2, Math.min(8, Math.min(left.getRowCount(), right.getRowCount()) / 2)));
            } else {
                parameters.put("parent_key_start", seed >= 1000L ? 0L : (relationship.getLeftTable() + 1L) * 1000000L);
                parameters.put("private_key_start", Long.MIN_VALUE);
                if (relationship.getFanoutType() == FanoutType.ONE_TO_MANY) {
                    populateParentKeyParameters(parameters, seed, random, relationship, left);
                } else {
                    parameters.put("parent_key_mode", "SEQUENTIAL");
                }
            }
            break;
        case SKEWED_FANOUT:
            parameters.put("shared_domain_start", seed >= 1000L ? 0L : (relationship.getLeftTable() + 1L) * 100000L);
            parameters.put("shared_domain_size",
                    Math.max(16, Math.min(96, Math.min(left.getRowCount(), right.getRowCount()))));
            parameters.put("exponent", SKEWED_FANOUT_EXPONENTS[random.nextInt(SKEWED_FANOUT_EXPONENTS.length)]);
            parameters.put("parent_value_seed",
                    derivedSeed(seed ^ 0x534b455750415231L, relationship.getLeftTable(), relationship.getLeftColumn()));
            parameters.put("child_value_seed", derivedSeed(seed ^ 0x534b455743484c31L, relationship.getRightTable(),
                    relationship.getRightColumn()));
            break;
        case DISJOINT_RANGE:
            long rangeWidth = Math.max(20L, left.getRowCount());
            double overlapRatio = DISJOINT_OVERLAP_RATIOS[random.nextInt(DISJOINT_OVERLAP_RATIOS.length)];
            long rangeShift = Math.max(1L, Math.round((1.0 - overlapRatio) * rangeWidth));
            parameters.put("range_start", (relationship.getLeftTable() + 1L) * 100000L);
            parameters.put("range_width", rangeWidth);
            parameters.put("overlap_ratio", overlapRatio);
            parameters.put("range_shift", rangeShift);
            break;
        default:
            throw new AssertionError(relationship.getType());
        }
        return parameters;
    }

    private static void populateParentKeyParameters(Map<String, Object> parameters, long seed, SplittableRandom random,
            RelationshipPlan relationship, TablePlan left) {
        ColumnDistributionType parentDist = left.getColumns().get(relationship.getLeftColumn()).getDistribution();
        if (left.getRowCount() >= 32 && parentDist == ColumnDistributionType.POINT_SPIKE) {
            parameters.put("parent_key_mode", "POINT_SPIKE");
            parameters.put("parent_spike_period", SPIKE_PERIODS[random.nextInt(SPIKE_PERIODS.length)]);
        } else if (left.getRowCount() >= 32 && (parentDist == ColumnDistributionType.ZIPF
                || parentDist == ColumnDistributionType.LOW_NDV || parentDist == ColumnDistributionType.UNIFORM)) {
            parameters.put("parent_key_mode", "ZIPF_HOT");
            parameters.put("parent_hot_period", 10);
            parameters.put("parent_hot_count", 2);
            parameters.put("parent_hot_domain_size", Math.max(8, Math.min(16, left.getRowCount() / 8)));
            parameters.put("parent_zipf_exponent",
                    SKEWED_FANOUT_EXPONENTS[random.nextInt(SKEWED_FANOUT_EXPONENTS.length)]);
            parameters.put("parent_value_seed", derivedSeed(seed ^ 0x5041524b45595331L, relationship.getLeftTable(),
                    relationship.getLeftColumn()));
        } else {
            parameters.put("parent_key_mode", "SEQUENTIAL");
        }
    }

    private static PdgColumnProducer relationshipProducer(Kind kind, RelationshipPlan relationship, String id,
            String role, Map<String, Object> relationshipParameters) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("relationship_id", id);
        parameters.put("role", role);
        parameters.putAll(relationshipParameters);
        ScalePolicy policy;
        if (relationship.isIntraTable()) {
            policy = ScalePolicy.SCALE_DEP;
        } else if (relationship.getType() == RelationshipType.REFERENTIAL_CORRELATION) {
            policy = "child".equals(role) ? ScalePolicy.SCALE_OVERLAP : ScalePolicy.SCALE_DOMAIN;
        } else if (relationship.getType() == RelationshipType.SKEWED_FANOUT
                || relationship.getType() == RelationshipType.JOIN_FANOUT
                        && relationship.getFanoutType() == FanoutType.MANY_TO_MANY) {
            policy = ScalePolicy.SCALE_FREQ;
        } else {
            policy = ScalePolicy.SCALE_DOMAIN;
        }
        return new PdgColumnProducer(kind, relationship.getType().name(), policy, parameters);
    }

    private static void validate(List<PdgSpecification.Table> tables, List<PdgRelationship> relationships) {
        Map<String, Set<String>> dependencyGraph = new HashMap<>();
        for (PdgRelationship relationship : relationships) {
            PdgSpecification.Table left = table(tables, relationship.getLeftTable());
            PdgSpecification.Table right = table(tables, relationship.getRightTable());
            PdgSpecification.Column leftColumn = column(left, relationship.getLeftColumn());
            PdgSpecification.Column rightColumn = column(right, relationship.getRightColumn());
            if ("FUNCTIONAL_DEPENDENCY".equals(relationship.getType())
                    || "ANTI_CORRELATION".equals(relationship.getType()) || "RANK_COUPLE".equals(relationship.getType())
                    || "COND_PATTERN".equals(relationship.getType())) {
                if (leftColumn.getSqlType() == SqlType.VARCHAR || rightColumn.getSqlType() == SqlType.VARCHAR) {
                    throw new IllegalStateException("intra-table PDG mappings require numeric columns");
                }
            } else if (leftColumn.getSqlType() != rightColumn.getSqlType()) {
                throw new IllegalStateException("inter-table PDG endpoints require identical SQL types");
            }
            String leftEndpoint = relationship.getLeftTable() + "." + relationship.getLeftColumn();
            String rightEndpoint = relationship.getRightTable() + "." + relationship.getRightColumn();
            dependencyGraph.computeIfAbsent(leftEndpoint, ignored -> new HashSet<>()).add(rightEndpoint);
            dependencyGraph.computeIfAbsent(rightEndpoint, ignored -> new HashSet<>());
            if ("JOIN_FANOUT".equals(relationship.getType())
                    && "ONE_TO_MANY".equals(relationship.getParameters().get("fanout_type"))) {
                int fanout = ((Number) relationship.getParameters().get("fanout")).intValue();
                if (right.getBaseRows() != left.getBaseRows() * fanout) {
                    throw new IllegalStateException("one-to-many row counts do not preserve fanout");
                }
            }
            if ("JOIN_FANOUT".equals(relationship.getType())
                    && "ONE_TO_ONE".equals(relationship.getParameters().get("fanout_type"))
                    && right.getBaseRows() != left.getBaseRows()) {
                throw new IllegalStateException("one-to-one row counts must match");
            }
            if (!relationship.getLeftTable().equals(relationship.getRightTable())
                    && tables.indexOf(left) >= tables.indexOf(right) && !("JOIN_FANOUT".equals(relationship.getType())
                            && "MANY_TO_MANY".equals(relationship.getParameters().get("fanout_type")))) {
                throw new IllegalStateException("PDG parent table must precede its dependent child table");
            }
        }
        assertAcyclic(dependencyGraph);
    }

    private static void assertAcyclic(Map<String, Set<String>> graph) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String node : graph.keySet()) {
            visit(node, graph, visiting, visited);
        }
    }

    private static void visit(String node, Map<String, Set<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visited.contains(node)) {
            return;
        }
        if (!visiting.add(node)) {
            throw new IllegalStateException("cyclic PDG column dependency involving " + node);
        }
        for (String dependent : graph.getOrDefault(node, Set.of())) {
            visit(dependent, graph, visiting, visited);
        }
        visiting.remove(node);
        visited.add(node);
    }

    private static PdgSpecification.Table table(List<PdgSpecification.Table> tables, String name) {
        return tables.stream().filter(table -> table.getName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown relationship table: " + name));
    }

    private static PdgSpecification.Column column(PdgSpecification.Table table, String name) {
        return table.getColumns().stream().filter(column -> column.getName().equals(name)).findFirst().orElseThrow(
                () -> new IllegalStateException("unknown relationship column: " + table.getName() + "." + name));
    }

    static long derivedSeed(long seed, int tableIndex, int columnIndex) {
        long value = seed ^ (0x9e3779b97f4a7c15L * (tableIndex + 1L)) ^ (0xbf58476d1ce4e5b9L * (columnIndex + 1L));
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
