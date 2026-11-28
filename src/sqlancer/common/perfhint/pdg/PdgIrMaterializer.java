package sqlancer.common.perfhint.pdg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;

/** Materializes final rows directly from their unique producers. */
public final class PdgIrMaterializer {

    private PdgIrMaterializer() {
    }

    public static PdgMaterializedDatabase materialize(PdgSpecification specification) {
        Map<String, PdgRelationship> relationships = new HashMap<>();
        for (PdgRelationship relationship : specification.getRelationships()) {
            relationships.put(relationship.getId(), relationship);
        }
        Map<PdgColumnProducer, double[]> zipfCdfs = zipfCdfs(specification);
        List<List<List<Object>>> allRows = new ArrayList<>();
        List<List<ProfileAccumulator>> profileAccumulators = profileAccumulators(specification);
        for (int tableIndex = 0; tableIndex < specification.getTables().size(); tableIndex++) {
            PdgSpecification.Table table = specification.getTables().get(tableIndex);
            List<List<Object>> rows = new ArrayList<>(table.getBaseRows());
            for (int rowIndex = 0; rowIndex < table.getBaseRows(); rowIndex++) {
                Object[] values = new Object[table.getColumns().size()];
                boolean[] complete = new boolean[values.length];
                boolean[] active = new boolean[values.length];
                for (int columnIndex = 0; columnIndex < values.length; columnIndex++) {
                    materializeCell(specification, allRows, relationships, tableIndex, rowIndex, columnIndex, values,
                            complete, active, zipfCdfs);
                }
                rows.add(new ArrayList<>(Arrays.asList(values)));
            }
            applyRankCoupling(table, rows, relationships);
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                List<Object> row = rows.get(rowIndex);
                for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
                    profileAccumulators.get(tableIndex).get(columnIndex).accept(row.get(columnIndex));
                }
            }
            allRows.add(rows);
        }
        PdgInMemoryIndex inMemoryIndex = PdgInMemoryIndex.build(specification, allRows);
        return new PdgMaterializedDatabase(allRows, profiles(specification, profileAccumulators), inMemoryIndex);
    }

    private static Object materializeCell(PdgSpecification specification, List<List<List<Object>>> allRows,
            Map<String, PdgRelationship> relationships, int tableIndex, int rowIndex, int columnIndex, Object[] values,
            boolean[] complete, boolean[] active, Map<PdgColumnProducer, double[]> zipfCdfs) {
        if (complete[columnIndex]) {
            return values[columnIndex];
        }
        if (active[columnIndex]) {
            throw new IllegalStateException("cyclic PDG column dependency");
        }
        active[columnIndex] = true;
        PdgSpecification.Table table = specification.getTables().get(tableIndex);
        PdgSpecification.Column column = table.getColumns().get(columnIndex);
        PdgColumnProducer producer = column.getProducer();
        Object value;
        switch (producer.getKind()) {
        case INDEPENDENT:
            value = independentValue(producer, column.getSqlType(), tableIndex, columnIndex, rowIndex,
                    table.getBaseRows(), zipfCdfs.get(producer));
            break;
        case INTRA_TABLE_DERIVED:
            value = derivedValue(specification, allRows, relationships, tableIndex, rowIndex, column.getSqlType(),
                    producer, values, complete, active, zipfCdfs);
            break;
        case INTER_TABLE_ENDPOINT:
            value = endpointValue(specification, allRows, relationships, tableIndex, rowIndex, producer, zipfCdfs);
            break;
        default:
            throw new AssertionError(producer.getKind());
        }
        values[columnIndex] = value;
        active[columnIndex] = false;
        complete[columnIndex] = true;
        return value;
    }

    private static Object derivedValue(PdgSpecification specification, List<List<List<Object>>> allRows,
            Map<String, PdgRelationship> relationships, int tableIndex, int rowIndex, SqlType targetType,
            PdgColumnProducer producer, Object[] values, boolean[] complete, boolean[] active,
            Map<PdgColumnProducer, double[]> zipfCdfs) {
        PdgRelationship relationship = relationship(producer, relationships);
        PdgSpecification.Table table = specification.getTables().get(tableIndex);
        int sourceIndex = columnIndex(table, relationship.getLeftColumn());
        Object source = materializeCell(specification, allRows, relationships, tableIndex, rowIndex, sourceIndex,
                values, complete, active, zipfCdfs);
        if (source == null) {
            return null;
        }
        long number = ((Number) source).longValue();
        long derivedLong;
        if ("FUNCTIONAL_DEPENDENCY".equals(relationship.getType()) || "FUNC_MAP".equals(relationship.getType())) {
            String kind = String.valueOf(relationship.getParameters().getOrDefault("mapping_kind", "LINEAR"));
            long multiplier = longParameter(relationship, "multiplier");
            long offset = longParameter(relationship, "offset");
            if ("MODULO".equals(kind)) {
                long modulus = ((Number) relationship.getParameters().getOrDefault("modulus", 16L)).longValue();
                derivedLong = Math.floorMod(number, modulus) * multiplier + offset;
            } else if ("DIVIDE".equals(kind)) {
                long divisor = ((Number) relationship.getParameters().getOrDefault("divisor", 8L)).longValue();
                derivedLong = Math.floorDiv(number, divisor) * multiplier + offset;
            } else {
                derivedLong = number * multiplier + offset;
            }
        } else if ("ANTI_CORRELATION".equals(relationship.getType())) {
            derivedLong = longParameter(relationship, "constant") - number;
        } else if ("COND_PATTERN".equals(relationship.getType())) {
            long mod = ((Number) relationship.getParameters().getOrDefault("trigger_modulus", 10L)).longValue();
            long rem = ((Number) relationship.getParameters().getOrDefault("trigger_remainder", 2L)).longValue();
            long seed = ((Number) relationship.getParameters().get("value_seed")).longValue();
            if (Math.floorMod(number, mod) < rem) {
                long condMin = ((Number) relationship.getParameters().getOrDefault("cond_min", 900000L)).longValue();
                long condMax = ((Number) relationship.getParameters().getOrDefault("cond_max", 900050L)).longValue();
                derivedLong = boundedLong(randomBits(seed, rowIndex, 0), condMin, condMax);
            } else {
                long bgMin = ((Number) relationship.getParameters().getOrDefault("bg_min", -100000L)).longValue();
                long bgMax = ((Number) relationship.getParameters().getOrDefault("bg_max", 100000L)).longValue();
                derivedLong = boundedLong(randomBits(seed, rowIndex, 1), bgMin, bgMax);
            }
        } else if ("RANK_COUPLE".equals(relationship.getType())) {
            long seed = ((Number) relationship.getParameters().get("value_seed")).longValue();
            long min = ((Number) relationship.getParameters().getOrDefault("minimum", -50000L)).longValue();
            long max = ((Number) relationship.getParameters().getOrDefault("maximum", 50000L)).longValue();
            derivedLong = boundedLong(randomBits(seed, rowIndex, 0), min, max);
        } else {
            throw new IllegalStateException("unsupported intra-table relationship: " + relationship.getType());
        }
        if (targetType == SqlType.DOUBLE) {
            return Double.valueOf(derivedLong);
        }
        return Long.valueOf(derivedLong);
    }

    private static void applyRankCoupling(PdgSpecification.Table table, List<List<Object>> rows,
            Map<String, PdgRelationship> relationships) {
        int n = rows.size();
        if (n <= 1) {
            return;
        }
        for (int colIdx = 0; colIdx < table.getColumns().size(); colIdx++) {
            PdgSpecification.Column col = table.getColumns().get(colIdx);
            PdgColumnProducer producer = col.getProducer();
            if (producer.getKind() != PdgColumnProducer.Kind.INTRA_TABLE_DERIVED) {
                continue;
            }
            PdgRelationship rel = relationship(producer, relationships);
            if (!"RANK_COUPLE".equals(rel.getType())) {
                continue;
            }
            int sourceColIdx = columnIndex(table, rel.getLeftColumn());
            double rho = ((Number) rel.getParameters().getOrDefault("rho", 0.8)).doubleValue();
            long seed = ((Number) rel.getParameters().get("value_seed")).longValue();
            long min = ((Number) rel.getParameters().getOrDefault("minimum", -50000L)).longValue();
            long max = ((Number) rel.getParameters().getOrDefault("maximum", 50000L)).longValue();

            long[] marginal = new long[n];
            Integer[] srcOrder = new Integer[n];
            for (int i = 0; i < n; i++) {
                marginal[i] = boundedLong(randomBits(seed, i, 0), min, max);
                srcOrder[i] = i;
            }
            Arrays.sort(marginal);
            Arrays.sort(srcOrder, (a, b) -> {
                Object va = rows.get(a).get(sourceColIdx);
                Object vb = rows.get(b).get(sourceColIdx);
                if (va == null && vb == null) {
                    return Integer.compare(a, b);
                }
                if (va == null) {
                    return -1;
                }
                if (vb == null) {
                    return 1;
                }
                int cmp = Double.compare(((Number) va).doubleValue(), ((Number) vb).doubleValue());
                return cmp != 0 ? cmp : Integer.compare(a, b);
            });
            int[] sourceRank = new int[n];
            for (int r = 0; r < n; r++) {
                sourceRank[srcOrder[r]] = r;
            }
            double noiseWeight = Math.sqrt(Math.max(0.0, 1.0 - rho * rho));
            double denom = Math.max(1, n - 1);
            double[] score = new double[n];
            Integer[] targetOrder = new Integer[n];
            for (int i = 0; i < n; i++) {
                double eps = unitDouble(randomBits(seed, i, 1)) - 0.5;
                score[i] = rho * (sourceRank[i] / denom) + noiseWeight * eps;
                targetOrder[i] = i;
            }
            Arrays.sort(targetOrder, (a, b) -> {
                int cmp = Double.compare(score[a], score[b]);
                return cmp != 0 ? cmp : Integer.compare(a, b);
            });
            for (int r = 0; r < n; r++) {
                int rowIndex = targetOrder[r];
                long coupledVal = marginal[r];
                Object cellVal = col.getSqlType() == SqlType.DOUBLE ? Double.valueOf(coupledVal)
                        : Long.valueOf(coupledVal);
                rows.get(rowIndex).set(colIdx, cellVal);
            }
        }
    }

    private static Object endpointValue(PdgSpecification specification, List<List<List<Object>>> allRows,
            Map<String, PdgRelationship> relationships, int tableIndex, int rowIndex, PdgColumnProducer producer,
            Map<PdgColumnProducer, double[]> zipfCdfs) {
        PdgRelationship relationship = relationship(producer, relationships);
        boolean parent = "parent".equals(producer.getParameters().get("role"));
        if ("REFERENTIAL_CORRELATION".equals(relationship.getType()) || "PARTIAL_REF".equals(relationship.getType())) {
            if (parent) {
                return parentKey(relationship, rowIndex, zipfCdfs.get(producer));
            }
            int period = ((Number) relationship.getParameters().get("match_period")).intValue();
            int matchCount = ((Number) relationship.getParameters().getOrDefault("match_count", period - 1)).intValue();
            if (rowIndex % period >= matchCount) {
                return longParameter(relationship, "private_key_start") + rowIndex;
            }
            return parentValue(specification, allRows, relationship,
                    rowIndex % parentRows(specification, relationship));
        }
        if ("SKEWED_FANOUT".equals(relationship.getType())) {
            int start = ((Number) relationship.getParameters().get("shared_domain_start")).intValue();
            long seed = ((Number) relationship.getParameters().get(parent ? "parent_value_seed" : "child_value_seed"))
                    .longValue();
            double[] cdf = zipfCdfs.get(producer);
            return (long) sampleZipf(unitDouble(randomBits(seed, rowIndex, 0)), start, cdf);
        }
        if ("DISJOINT_RANGE".equals(relationship.getType())) {
            long start = longParameter(relationship, "range_start");
            long width = longParameter(relationship, "range_width");
            long shift = parent ? 0L : longParameter(relationship, "range_shift");
            return start + shift + (rowIndex % width);
        }
        if (!"JOIN_FANOUT".equals(relationship.getType())) {
            throw new IllegalStateException("unsupported inter-table relationship: " + relationship.getType());
        }
        String fanoutType = String.valueOf(relationship.getParameters().get("fanout_type"));
        if ("MANY_TO_MANY".equals(fanoutType)) {
            long start = longParameter(relationship, "shared_domain_start");
            int size = ((Number) relationship.getParameters().get("shared_domain_size")).intValue();
            return start + rowIndex % size;
        }
        if (parent) {
            return parentKey(relationship, rowIndex, zipfCdfs.get(producer));
        }
        int parentIndex = "ONE_TO_MANY".equals(fanoutType)
                ? rowIndex / ((Number) relationship.getParameters().get("fanout")).intValue() : rowIndex;
        if (parentIndex >= parentRows(specification, relationship)) {
            return longParameter(relationship, "private_key_start") + rowIndex;
        }
        return parentValue(specification, allRows, relationship, parentIndex);
    }

    private static long parentKey(PdgRelationship relationship, int rowIndex, double[] zipfCdf) {
        Map<String, Object> parameters = relationship.getParameters();
        Object start = parameters.get("parent_key_start");
        long base = start instanceof Number ? ((Number) start).longValue() : 1000000L;
        String mode = String.valueOf(parameters.getOrDefault("parent_key_mode", "SEQUENTIAL"));
        if ("POINT_SPIKE".equals(mode)) {
            int period = ((Number) parameters.getOrDefault("parent_spike_period", 25)).intValue();
            return rowIndex % period == 0 ? base : base + 1L + rowIndex;
        }
        if ("ZIPF_HOT".equals(mode)) {
            int hotPeriod = ((Number) parameters.getOrDefault("parent_hot_period", 10)).intValue();
            int hotCount = ((Number) parameters.getOrDefault("parent_hot_count", 2)).intValue();
            int hotDomain = ((Number) parameters.getOrDefault("parent_hot_domain_size", 12)).intValue();
            if (rowIndex % hotPeriod < hotCount) {
                long pSeed = ((Number) parameters.get("parent_value_seed")).longValue();
                return base + sampleZipf(unitDouble(randomBits(pSeed, rowIndex, 0)), 0, zipfCdf);
            }
            return base + hotDomain + rowIndex;
        }
        return base + rowIndex;
    }

    private static Object parentValue(PdgSpecification specification, List<List<List<Object>>> allRows,
            PdgRelationship relationship, int rowIndex) {
        int parentTableIndex = tableIndex(specification, relationship.getLeftTable());
        if (parentTableIndex >= allRows.size()) {
            throw new IllegalStateException("PDG parent table must be materialized before child table");
        }
        int parentColumnIndex = columnIndex(specification.getTables().get(parentTableIndex),
                relationship.getLeftColumn());
        return allRows.get(parentTableIndex).get(rowIndex).get(parentColumnIndex);
    }

    private static int parentRows(PdgSpecification specification, PdgRelationship relationship) {
        return specification.table(relationship.getLeftTable()).getBaseRows();
    }

    private static Object independentValue(PdgColumnProducer producer, SqlType type, int tableIndex, int columnIndex,
            int rowIndex, int baseRows, double[] zipfCdf) {
        Map<String, Object> parameters = producer.getParameters();
        long seed = ((Number) parameters.get("value_seed")).longValue();
        if ("NULL_HEAVY".equals(producer.getModel())
                && unitDouble(randomBits(seed, rowIndex, 0)) < ((Number) parameters.get("null_probability"))
                        .doubleValue()) {
            return null;
        }
        double numeric;
        switch (producer.getModel()) {
        case "ZIPF":
            numeric = sampleZipf(unitDouble(randomBits(seed, rowIndex, 0)),
                    ((Number) parameters.get("domain_start")).intValue(), zipfCdf);
            break;
        case "GAUSSIAN":
            numeric = ((Number) parameters.get("mean")).doubleValue()
                    + sampleGaussian(unitDouble(randomBits(seed, rowIndex, 0)),
                            unitDouble(randomBits(seed, rowIndex, 1)))
                            * ((Number) parameters.get("standard_deviation")).doubleValue();
            break;
        case "LOW_NDV":
            numeric = ((Number) parameters.get("domain_start")).longValue()
                    + rowIndex % ((Number) parameters.get("domain_size")).intValue();
            break;
        case "MONOTONIC_ASC":
            numeric = ((Number) parameters.get("start")).longValue()
                    + rowIndex * ((Number) parameters.get("step")).longValue();
            break;
        case "MONOTONIC_DESC":
            numeric = ((Number) parameters.get("start")).longValue()
                    + (baseRows - rowIndex) * ((Number) parameters.get("step")).longValue();
            break;
        case "POINT_SPIKE":
            int spikePeriod = ((Number) parameters.getOrDefault("spike_period", 25)).intValue();
            if (rowIndex % spikePeriod == 0) {
                numeric = ((Number) parameters.get("spike_value")).longValue();
            } else {
                long spikeMin = ((Number) parameters.get("minimum")).longValue();
                long spikeMax = ((Number) parameters.get("maximum")).longValue();
                numeric = boundedLong(randomBits(seed, rowIndex, 0), spikeMin, spikeMax);
            }
            break;
        case "NULL_HEAVY":
        case "UNIFORM":
            long minimum = ((Number) parameters.get("minimum")).longValue();
            long maximum = ((Number) parameters.get("maximum")).longValue();
            int stream = "NULL_HEAVY".equals(producer.getModel()) ? 1 : 0;
            numeric = boundedLong(randomBits(seed, rowIndex, stream), minimum, maximum);
            break;
        default:
            throw new IllegalStateException("unsupported independent producer: " + producer.getModel());
        }
        switch (type) {
        case BIGINT:
            return Math.round(numeric);
        case DOUBLE:
            return numeric;
        case VARCHAR:
            return producer.getModel().toLowerCase() + "_" + tableIndex + "_" + columnIndex + "_" + Math.round(numeric);
        default:
            throw new AssertionError(type);
        }
    }

    private static int sampleZipf(double sample, int start, double[] cdf) {
        int low = 0;
        int high = cdf.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sample <= cdf[middle]) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return start + low;
    }

    private static double sampleGaussian(double first, double second) {
        return Math.sqrt(-2.0 * Math.log(Math.max(first, 1.0e-12))) * Math.cos(2.0 * Math.PI * second);
    }

    private static long randomBits(long seed, int rowIndex, int stream) {
        return mix64(seed ^ (0x9e3779b97f4a7c15L * (rowIndex + 1L)) ^ (0xbf58476d1ce4e5b9L * (stream + 1L)));
    }

    private static long mix64(long value) {
        long v = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
        return v ^ (v >>> 31);
    }

    private static double unitDouble(long bits) {
        return (bits >>> 11) * 0x1.0p-53;
    }

    private static long boundedLong(long bits, long minimum, long maximum) {
        long range = maximum - minimum + 1L;
        return minimum + Math.floorMod(bits, range);
    }

    private static Map<PdgColumnProducer, double[]> zipfCdfs(PdgSpecification specification) {
        Map<PdgColumnProducer, double[]> result = new IdentityHashMap<>();
        for (PdgSpecification.Table table : specification.getTables()) {
            for (PdgSpecification.Column column : table.getColumns()) {
                PdgColumnProducer producer = column.getProducer();
                boolean isZipf = "ZIPF".equals(producer.getModel());
                boolean isSkewedFanout = "SKEWED_FANOUT".equals(producer.getModel());
                boolean isZipfHotParent = producer.getKind() == PdgColumnProducer.Kind.INTER_TABLE_ENDPOINT
                        && "parent".equals(producer.getParameters().get("role"))
                        && "ZIPF_HOT".equals(producer.getParameters().get("parent_key_mode"));
                if (!isZipf && !isSkewedFanout && !isZipfHotParent) {
                    continue;
                }
                String sizeKey = isZipfHotParent ? "parent_hot_domain_size"
                        : isSkewedFanout ? "shared_domain_size" : "domain_size";
                String expKey = isZipfHotParent ? "parent_zipf_exponent" : "exponent";
                int size = ((Number) producer.getParameters().get(sizeKey)).intValue();
                double exponent = ((Number) producer.getParameters().get(expKey)).doubleValue();
                double[] cdf = new double[size];
                double total = 0.0;
                for (int rank = 1; rank <= size; rank++) {
                    total += 1.0 / Math.pow(rank, exponent);
                    cdf[rank - 1] = total;
                }
                for (int index = 0; index < cdf.length; index++) {
                    cdf[index] /= total;
                }
                cdf[cdf.length - 1] = 1.0;
                result.put(producer, cdf);
            }
        }
        return result;
    }

    private static List<List<ProfileAccumulator>> profileAccumulators(PdgSpecification specification) {
        List<List<ProfileAccumulator>> result = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < specification.getTables().size(); tableIndex++) {
            PdgSpecification.Table table = specification.getTables().get(tableIndex);
            List<ProfileAccumulator> tableAccumulators = new ArrayList<>();
            for (int columnIndex = 0; columnIndex < table.getColumns().size(); columnIndex++) {
                PdgSpecification.Column column = table.getColumns().get(columnIndex);
                PdgColumnProducer producer = column.getProducer();
                tableAccumulators.add(new ProfileAccumulator(column.getSqlType() != SqlType.VARCHAR,
                        table.getBaseRows(), expectedDistinct(producer, table.getBaseRows()),
                        isKnownUnique(producer) || derivesNdvFromAnotherColumn(producer)));
            }
            result.add(tableAccumulators);
        }
        linkNdvSources(specification, result);
        return result;
    }

    private static void linkNdvSources(PdgSpecification specification, List<List<ProfileAccumulator>> accumulators) {
        Map<String, PdgRelationship> relationships = new HashMap<>();
        for (PdgRelationship relationship : specification.getRelationships()) {
            relationships.put(relationship.getId(), relationship);
        }
        for (int tableIndex = 0; tableIndex < specification.getTables().size(); tableIndex++) {
            PdgSpecification.Table table = specification.getTables().get(tableIndex);
            for (int columnIndex = 0; columnIndex < table.getColumns().size(); columnIndex++) {
                PdgColumnProducer producer = table.getColumns().get(columnIndex).getProducer();
                if (!derivesNdvFromAnotherColumn(producer)) {
                    continue;
                }
                PdgRelationship relationship = relationship(producer, relationships);
                int sourceTableIndex = tableIndex(specification, relationship.getLeftTable());
                int sourceColumnIndex = columnIndex(specification.getTables().get(sourceTableIndex),
                        relationship.getLeftColumn());
                accumulators.get(tableIndex).get(columnIndex)
                        .shareNdvWith(accumulators.get(sourceTableIndex).get(sourceColumnIndex));
            }
        }
    }

    private static boolean derivesNdvFromAnotherColumn(PdgColumnProducer producer) {
        if (producer.getKind() == PdgColumnProducer.Kind.INTRA_TABLE_DERIVED) {
            if ("COND_PATTERN".equals(producer.getModel()) || "RANK_COUPLE".equals(producer.getModel())) {
                return false;
            }
            if ("FUNCTIONAL_DEPENDENCY".equals(producer.getModel()) || "FUNC_MAP".equals(producer.getModel())) {
                Object kind = producer.getParameters().get("mapping_kind");
                return kind == null || "LINEAR".equals(kind);
            }
            return true;
        }
        Map<String, Object> parameters = producer.getParameters();
        return producer.getKind() == PdgColumnProducer.Kind.INTER_TABLE_ENDPOINT
                && "child".equals(parameters.get("role")) && "JOIN_FANOUT".equals(producer.getModel())
                && "ONE_TO_MANY".equals(parameters.get("fanout_type"));
    }

    private static int expectedDistinct(PdgColumnProducer producer, int rowCount) {
        Map<String, Object> parameters = producer.getParameters();
        if (producer.getKind() == PdgColumnProducer.Kind.INDEPENDENT) {
            if ("LOW_NDV".equals(producer.getModel()) || "ZIPF".equals(producer.getModel())) {
                return Math.min(rowCount, ((Number) parameters.get("domain_size")).intValue());
            }
            if ("NULL_HEAVY".equals(producer.getModel())) {
                double nonNullFraction = 1.0 - ((Number) parameters.get("null_probability")).doubleValue();
                return Math.max(1, (int) Math.ceil(rowCount * nonNullFraction));
            }
            return rowCount;
        }
        if (producer.getKind() == PdgColumnProducer.Kind.INTER_TABLE_ENDPOINT) {
            if ("MANY_TO_MANY".equals(parameters.get("fanout_type")) || "SKEWED_FANOUT".equals(producer.getModel())) {
                return Math.min(rowCount, ((Number) parameters.get("shared_domain_size")).intValue());
            }
            if ("DISJOINT_RANGE".equals(producer.getModel())) {
                return Math.min(rowCount, ((Number) parameters.get("range_width")).intValue());
            }
        }
        return rowCount;
    }

    private static boolean isKnownUnique(PdgColumnProducer producer) {
        if (producer.getKind() == PdgColumnProducer.Kind.INDEPENDENT) {
            return "MONOTONIC_ASC".equals(producer.getModel()) || "MONOTONIC_DESC".equals(producer.getModel());
        }
        if (producer.getKind() != PdgColumnProducer.Kind.INTER_TABLE_ENDPOINT) {
            return false;
        }
        if ("SKEWED_FANOUT".equals(producer.getModel()) || "DISJOINT_RANGE".equals(producer.getModel())) {
            return false;
        }
        Map<String, Object> parameters = producer.getParameters();
        if ("parent".equals(parameters.get("role"))) {
            String mode = String.valueOf(parameters.getOrDefault("parent_key_mode", "SEQUENTIAL"));
            return !"MANY_TO_MANY".equals(parameters.get("fanout_type")) && "SEQUENTIAL".equals(mode);
        }
        return "JOIN_FANOUT".equals(producer.getModel()) && "ONE_TO_ONE".equals(parameters.get("fanout_type"));
    }

    private static List<PdgColumnProfile> profiles(PdgSpecification specification,
            List<List<ProfileAccumulator>> accumulators) {
        List<PdgColumnProfile> result = new ArrayList<>();
        for (int tableIndex = 0; tableIndex < specification.getTables().size(); tableIndex++) {
            PdgSpecification.Table table = specification.getTables().get(tableIndex);
            for (int columnIndex = 0; columnIndex < table.getColumns().size(); columnIndex++) {
                PdgSpecification.Column column = table.getColumns().get(columnIndex);
                result.add(accumulators.get(tableIndex).get(columnIndex).profile(table.getName(), column.getName()));
            }
        }
        return result;
    }

    private static final class ProfileAccumulator {
        private Set<Object> distinct;
        private final boolean numeric;
        private ProfileAccumulator ndvSource;
        private int rowCount;
        private int nullCount;
        private Number minimum;
        private Number maximum;

        ProfileAccumulator(boolean numeric, int rowCount, int expectedDistinct, boolean knownUnique) {
            this.numeric = numeric;
            if (knownUnique) {
                distinct = null;
            } else {
                int bounded = Math.max(1, Math.min(rowCount, expectedDistinct));
                int capacity = Math.max(4, (int) Math.ceil(bounded / 0.75));
                distinct = new HashSet<>(capacity);
            }
        }

        void accept(Object value) {
            rowCount++;
            if (value == null) {
                nullCount++;
                return;
            }
            if (distinct != null) {
                distinct.add(value);
            }
            if (!numeric) {
                return;
            }
            Number number = (Number) value;
            if (minimum == null || Double.compare(number.doubleValue(), minimum.doubleValue()) < 0) {
                minimum = number;
            }
            if (maximum == null || Double.compare(number.doubleValue(), maximum.doubleValue()) > 0) {
                maximum = number;
            }
        }

        void shareNdvWith(ProfileAccumulator source) {
            ndvSource = source;
            distinct = null;
        }

        int ndv() {
            if (ndvSource != null) {
                return ndvSource.ndv();
            }
            return distinct == null ? rowCount - nullCount : distinct.size();
        }

        PdgColumnProfile profile(String table, String column) {
            return new PdgColumnProfile(table, column, rowCount, nullCount, ndv(), minimum, maximum);
        }
    }

    private static PdgRelationship relationship(PdgColumnProducer producer,
            Map<String, PdgRelationship> relationships) {
        String id = String.valueOf(producer.getParameters().get("relationship_id"));
        PdgRelationship relationship = relationships.get(id);
        if (relationship == null) {
            throw new IllegalStateException("unknown PDG relationship: " + id);
        }
        return relationship;
    }

    private static long longParameter(PdgRelationship relationship, String name) {
        return ((Number) relationship.getParameters().get(name)).longValue();
    }

    private static int tableIndex(PdgSpecification specification, String name) {
        for (int index = 0; index < specification.getTables().size(); index++) {
            if (specification.getTables().get(index).getName().equals(name)) {
                return index;
            }
        }
        throw new IllegalArgumentException("unknown table: " + name);
    }

    private static int columnIndex(PdgSpecification.Table table, String name) {
        for (int index = 0; index < table.getColumns().size(); index++) {
            if (table.getColumns().get(index).getName().equals(name)) {
                return index;
            }
        }
        throw new IllegalArgumentException("unknown column: " + table.getName() + "." + name);
    }
}
