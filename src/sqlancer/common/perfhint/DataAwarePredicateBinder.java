package sqlancer.common.perfhint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sqlancer.common.perfhint.PerformanceHintDataModel.SqlType;
import sqlancer.common.perfhint.pdg.PdgInMemoryIndex;
import sqlancer.common.perfhint.pdg.PdgInMemoryIndex.ColumnIndex;
import sqlancer.common.perfhint.pdg.PdgInMemoryIndex.RelationshipEdge;
import sqlancer.common.perfhint.pdg.PdgInMemoryIndex.TableIndex;
import sqlancer.common.perfhint.pdg.PdgIrContext;

/**
 * Algorithm 3: In-memory Data-Aware Predicate Binder with materialization-free post-filter join cardinality estimation
 * (Eq. 10) and bounded coordinate search (|Theta_j| <= 6, max 2 passes). Zero database queries are issued.
 */
public final class DataAwarePredicateBinder {

    private static final int MAX_CANDIDATES_PER_SLOT = 6;
    private static final int MAX_COORDINATE_PASSES = 2;
    private static final Pattern ATOMIC_PREDICATE_PATTERN = Pattern.compile(
            "\\(?([A-Za-z0-9_]+)\\.([A-Za-z0-9_]+)\\)?\\s*(IS\\s+NOT\\s+NULL|IS\\s+NULL|IS\\s+TRUE|IS\\s+FALSE|<=|>=|<>|!=|=|<|>)\\s*\\(?('[^']*'|[-+]?[0-9]+(?:\\.[0-9]+)?)?\\)?",
            Pattern.CASE_INSENSITIVE);

    public enum Operator {
        EQ("="), NEQ("!="), LT("<"), LTE("<="), GT(">"), GTE(">=");

        private final String sql;

        Operator(String sql) {
            this.sql = sql;
        }

        public String getSql() {
            return sql;
        }

        public boolean isEqualityLike() {
            return this == EQ || this == NEQ;
        }

        public boolean evaluate(long cellValue, long constant) {
            switch (this) {
            case EQ:
                return cellValue == constant;
            case NEQ:
                return cellValue != constant;
            case LT:
                return cellValue < constant;
            case LTE:
                return cellValue <= constant;
            case GT:
                return cellValue > constant;
            case GTE:
                return cellValue >= constant;
            default:
                throw new AssertionError(this);
            }
        }
    }

    public static final class JoinEdgeSpec {
        private final String leftTable;
        private final String leftColumn;
        private final String rightTable;
        private final String rightColumn;

        public JoinEdgeSpec(String leftTable, String leftColumn, String rightTable, String rightColumn) {
            this.leftTable = leftTable;
            this.leftColumn = leftColumn;
            this.rightTable = rightTable;
            this.rightColumn = rightColumn;
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
    }

    public static final class PredicateSlot {
        private final String tableName;
        private final String columnName;
        private final Operator operator;

        public PredicateSlot(String tableName, String columnName, Operator operator) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.operator = operator;
        }

        public String getTableName() {
            return tableName;
        }

        public String getColumnName() {
            return columnName;
        }

        public Operator getOperator() {
            return operator;
        }
    }

    public static final class BoundPredicate {
        private final PredicateSlot slot;
        private final SqlType sqlType;
        private final long boundLong;
        private final double boundDouble;
        private final String boundString;

        BoundPredicate(PredicateSlot slot, SqlType sqlType, long boundLong, double boundDouble, String boundString) {
            this.slot = slot;
            this.sqlType = sqlType;
            this.boundLong = boundLong;
            this.boundDouble = boundDouble;
            this.boundString = boundString;
        }

        public PredicateSlot getSlot() {
            return slot;
        }

        public SqlType getSqlType() {
            return sqlType;
        }

        public long getBoundLong() {
            return boundLong;
        }

        public double getBoundDouble() {
            return boundDouble;
        }

        public String getBoundString() {
            return boundString;
        }

        public String toLiteralSql() {
            if (sqlType == SqlType.VARCHAR) {
                String safe = boundString != null ? boundString.replace("'", "''") : "";
                return "'" + safe + "'";
            }
            if (sqlType == SqlType.DOUBLE) {
                return String.format(Locale.US, "%.2f", boundDouble);
            }
            return Long.toString(boundLong);
        }

        public String toPredicateSql() {
            return slot.getTableName() + "." + slot.getColumnName() + " " + slot.getOperator().getSql() + " "
                    + toLiteralSql();
        }
    }

    public static final class BindingResult {
        private final List<BoundPredicate> boundPredicates;
        private final String whereSql;
        private final long joinCardinality;
        private final long resultCardinality;
        private final boolean conjunctive;
        private final long elapsedNanos;

        BindingResult(List<BoundPredicate> boundPredicates, String whereSql, long joinCardinality,
                long resultCardinality, boolean conjunctive, long elapsedNanos) {
            this.boundPredicates = Collections.unmodifiableList(boundPredicates);
            this.whereSql = whereSql;
            this.joinCardinality = joinCardinality;
            this.resultCardinality = resultCardinality;
            this.conjunctive = conjunctive;
            this.elapsedNanos = elapsedNanos;
        }

        public List<BoundPredicate> getBoundPredicates() {
            return boundPredicates;
        }

        public String getWhereSql() {
            return whereSql;
        }

        public long getJoinCardinality() {
            return joinCardinality;
        }

        public long getResultCardinality() {
            return resultCardinality;
        }

        public boolean isConjunctive() {
            return conjunctive;
        }

        public long getElapsedNanos() {
            return elapsedNanos;
        }
    }

    private DataAwarePredicateBinder() {
    }

    public static JoinEdgeSpec findPreferredRelationship(PdgIrContext context, String leftTable, String rightTable) {
        if (context == null || context.getInMemoryIndex() == null) {
            return null;
        }
        PdgInMemoryIndex index = context.getInMemoryIndex();
        TableIndex lt = index.findTable(leftTable);
        TableIndex rt = index.findTable(rightTable);
        RelationshipEdge edge = index.findRelationshipBetween(leftTable, rightTable);
        if (edge != null) {
            String leftCol = edge.getColumnForTable(leftTable);
            String rightCol = edge.getColumnForTable(rightTable);
            if (leftCol != null && rightCol != null) {
                if (lt == null || rt == null) {
                    return new JoinEdgeSpec(leftTable, leftCol, rightTable, rightCol);
                }
                ColumnIndex lc = lt.findColumn(leftCol);
                ColumnIndex rc = rt.findColumn(rightCol);
                if (lc != null && rc != null && estimatePairJoinCardinality(lc, rc) > 0L) {
                    return new JoinEdgeSpec(leftTable, leftCol, rightTable, rightCol);
                }
            }
        }
        if (lt != null && rt != null) {
            if (lt.getColumnCount() > 0 && rt.getColumnCount() > 0) {
                ColumnIndex lc0 = lt.getColumn(0);
                ColumnIndex rc0 = rt.getColumn(0);
                if (lc0.getSqlType() == rc0.getSqlType()) {
                    long card0 = estimatePairJoinCardinality(lc0, rc0);
                    if (card0 > 0L && card0 <= 2000L) {
                        return new JoinEdgeSpec(leftTable, lc0.getColumnName(), rightTable, rc0.getColumnName());
                    }
                }
            }
            ColumnIndex bestLeft = null;
            ColumnIndex bestRight = null;
            long bestScore = 0L;
            for (int i = 0; i < lt.getColumnCount(); i++) {
                ColumnIndex lc = lt.getColumn(i);
                for (int j = 0; j < rt.getColumnCount(); j++) {
                    ColumnIndex rc = rt.getColumn(j);
                    if (lc.getSqlType() != rc.getSqlType()) {
                        continue;
                    }
                    long pairCard = estimatePairJoinCardinality(lc, rc);
                    if (pairCard > 0L && pairCard <= 1500L) {
                        long score = 2000L - Math.abs(pairCard - 200L);
                        if (i == 0 || j == 0) {
                            score += 1000L;
                        }
                        if (lc.getSqlType() == SqlType.BIGINT) {
                            score += 500L;
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            bestLeft = lc;
                            bestRight = rc;
                        }
                    }
                }
            }
            if (bestLeft != null && bestRight != null) {
                return new JoinEdgeSpec(leftTable, bestLeft.getColumnName(), rightTable, bestRight.getColumnName());
            }
        }
        if (edge != null) {
            String leftCol = edge.getColumnForTable(leftTable);
            String rightCol = edge.getColumnForTable(rightTable);
            if (leftCol != null && rightCol != null) {
                return new JoinEdgeSpec(leftTable, leftCol, rightTable, rightCol);
            }
        }
        return null;
    }

    private static long estimatePairJoinCardinality(ColumnIndex lc, ColumnIndex rc) {
        long[] uL = lc.getUniqueValues();
        int[] fL = lc.getFrequencies();
        long[] uR = rc.getUniqueValues();
        int[] fR = rc.getFrequencies();
        int i = 0;
        int j = 0;
        long total = 0L;
        while (i < uL.length && j < uR.length) {
            long va = uL[i];
            long vb = uR[j];
            if (va == vb) {
                total += (long) fL[i] * fR[j];
                i++;
                j++;
            } else if (va < vb) {
                i++;
            } else {
                j++;
            }
        }
        return total;
    }

    public static BindingResult bind(PdgIrContext context, List<String> queryTableNames, List<JoinEdgeSpec> joinEdges,
            List<PredicateSlot> slots, boolean conjunctive, int targetBand, PerformanceHintTelemetry telemetry) {
        return bind(context, queryTableNames, joinEdges, slots, conjunctive, targetBand, false, telemetry);
    }

    public static BindingResult bind(PdgIrContext context, List<String> queryTableNames, List<JoinEdgeSpec> joinEdges,
            List<PredicateSlot> slots, boolean conjunctive, int targetBand, boolean hasResultCollapsing,
            PerformanceHintTelemetry telemetry) {
        long startNanos = System.nanoTime();
        PdgInMemoryIndex index = context != null ? context.getInMemoryIndex() : null;
        if (index == null || queryTableNames.isEmpty() || slots.isEmpty()) {
            return new BindingResult(List.of(), "", 0L, 0L, conjunctive, 0L);
        }

        int numTables = queryTableNames.size();
        TableIndex[] queryTables = new TableIndex[numTables];
        for (int i = 0; i < numTables; i++) {
            queryTables[i] = index.findTable(queryTableNames.get(i));
            if (queryTables[i] == null) {
                return new BindingResult(List.of(), "", 0L, 0L, conjunctive, 0L);
            }
        }

        ResolvedJoin[] resolvedJoins = resolveJoins(queryTables, joinEdges);
        PreparedJoin[] preparedJoins = prepareJoins(queryTables, resolvedJoins);
        long[][] rowWeights = allocateRowWeightBuffers(queryTables);
        boolean[][] joinActiveRows = computeJoinActiveRowMasks(queryTables, preparedJoins);
        long unfilteredJoinCard = estimatePreparedJoinCardinality(queryTables, preparedJoins, null, null, true,
                rowWeights);

        int numSlots = slots.size();
        int[] slotTableOrd = new int[numSlots];
        ColumnIndex[] slotCols = new ColumnIndex[numSlots];
        long[][] candidatePools = new long[numSlots][];
        long[] currentTheta = new long[numSlots];

        for (int j = 0; j < numSlots; j++) {
            PredicateSlot slot = slots.get(j);
            int tOrd = findQueryTableOrdinal(queryTables, slot.getTableName());
            if (tOrd < 0) {
                tOrd = 0;
            }
            slotTableOrd[j] = tOrd;
            TableIndex tIdx = queryTables[tOrd];
            ColumnIndex cIdx = tIdx.findColumn(slot.getColumnName());
            if (cIdx == null) {
                cIdx = tIdx.getColumn(0);
            }
            slotCols[j] = cIdx;
            long[] pool = buildCandidatePool(tIdx, cIdx, slot.getOperator(), joinActiveRows[tOrd]);
            candidatePools[j] = pool;
            currentTheta[j] = pool[pool.length / 2];
        }

        int baseRowScale = Math.max(1, queryTables[0].getRowCount());
        long targetLow;
        long targetHigh;
        if (hasResultCollapsing) {
            // Queries with DISTINCT, GROUP BY, aggregates, or LIMIT collapse rows before client transfer, so high
            // intermediate join cardinalities remain fast while stress-testing optimizer join/aggregation choices.
            if (targetBand <= 0) {
                targetLow = 10L;
                targetHigh = Math.max(200L, baseRowScale);
            } else if (targetBand == 1) {
                targetLow = Math.max(200L, baseRowScale);
                targetHigh = Math.max(2000L, baseRowScale * 8L);
            } else {
                targetLow = Math.max(2000L, baseRowScale * 8L);
                targetHigh = 40000L;
            }
        } else {
            // Plain multi-table SELECTs stream surviving rows over JDBC; cover low/medium/high non-empty outputs
            // without compressing medians too aggressively, while still steering clear of extreme row explosions.
            if (targetBand <= 0) {
                targetLow = 10L;
                targetHigh = Math.max(100L, baseRowScale / 4L);
            } else if (targetBand == 1) {
                targetLow = Math.max(100L, baseRowScale / 4L);
                targetHigh = Math.max(800L, baseRowScale * 2L);
            } else {
                targetLow = Math.max(800L, baseRowScale * 2L);
                targetHigh = Math.max(3000L, Math.min(8000L, baseRowScale * 8L));
            }
        }

        SlotFilterState filterState = new SlotFilterState(numSlots, slotTableOrd, slotCols, slots);
        long currentCard = runCoordinateSearch(queryTables, preparedJoins, filterState, candidatePools, currentTheta,
                conjunctive, targetLow, targetHigh, hasResultCollapsing, rowWeights);

        List<BoundPredicate> boundList = new ArrayList<>(numSlots);
        StringBuilder whereBuilder = new StringBuilder();
        String connector = conjunctive ? " AND " : " OR ";
        for (int j = 0; j < numSlots; j++) {
            BoundPredicate bp = materializeBoundPredicate(slots.get(j), slotCols[j], currentTheta[j]);
            boundList.add(bp);
            if (j > 0) {
                whereBuilder.append(connector);
            }
            if (numSlots > 1) {
                whereBuilder.append('(').append(bp.toPredicateSql()).append(')');
            } else {
                whereBuilder.append(bp.toPredicateSql());
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        if (telemetry != null) {
            recordGenerationTelemetry(telemetry, true, elapsedNanos, unfilteredJoinCard, currentCard, numTables > 1,
                    hasResultCollapsing);
        }
        return new BindingResult(boundList, whereBuilder.toString(), unfilteredJoinCard, currentCard, conjunctive,
                elapsedNanos);
    }

    private static long runCoordinateSearch(TableIndex[] queryTables, PreparedJoin[] preparedJoins,
            SlotFilterState filterState, long[][] candidatePools, long[] currentTheta, boolean conjunctive,
            long targetLow, long targetHigh, boolean hasResultCollapsing, long[][] rowWeights) {
        int numSlots = candidatePools.length;
        long currentCard = estimatePreparedJoinCardinality(queryTables, preparedJoins, filterState, currentTheta,
                conjunctive, rowWeights);
        long bestDistance = intervalDistance(currentCard, targetLow, targetHigh, hasResultCollapsing);

        for (int pass = 0; pass < MAX_COORDINATE_PASSES; pass++) {
            boolean improved = false;
            for (int j = 0; j < numSlots; j++) {
                long[] pool = candidatePools[j];
                long bestVal = currentTheta[j];
                for (int c = 0; c < pool.length; c++) {
                    long cand = pool[c];
                    if (cand == bestVal && pass > 0) {
                        continue;
                    }
                    currentTheta[j] = cand;
                    long card = estimatePreparedJoinCardinality(queryTables, preparedJoins, filterState, currentTheta,
                            conjunctive, rowWeights);
                    long dist = intervalDistance(card, targetLow, targetHigh, hasResultCollapsing);
                    if (isBetterCardinality(card, dist, currentCard, bestDistance)) {
                        bestVal = cand;
                        currentCard = card;
                        bestDistance = dist;
                        improved = true;
                    }
                }
                currentTheta[j] = bestVal;
                if (currentCard > 0 && bestDistance == 0L) {
                    break;
                }
            }
            if (!improved || currentCard > 0 && bestDistance == 0L) {
                break;
            }
        }
        return currentCard;
    }

    public static void recordUnawareTelemetry(PdgIrContext context, List<String> queryTableNames,
            List<JoinEdgeSpec> joinEdges, String whereSql, PerformanceHintTelemetry telemetry) {
        if (telemetry == null) {
            return;
        }
        telemetry.count("base_queries.predicate_binding_unaware");
        PdgInMemoryIndex index = context != null ? context.getInMemoryIndex() : null;
        if (index == null || queryTableNames.isEmpty()) {
            return;
        }
        int numTables = queryTableNames.size();
        TableIndex[] queryTables = new TableIndex[numTables];
        for (int i = 0; i < numTables; i++) {
            queryTables[i] = index.findTable(queryTableNames.get(i));
            if (queryTables[i] == null) {
                return;
            }
        }
        ResolvedJoin[] resolvedJoins = resolveJoins(queryTables, joinEdges);
        long joinCard = estimateJoinCardinality(queryTables, resolvedJoins, null, null, true);
        if (numTables > 1) {
            telemetry.count(joinCard > 0 ? "base_queries.non_empty_join" : "base_queries.empty_join");
        }
        long resultCard = evaluateUnawareWhereCardinality(queryTables, resolvedJoins, whereSql);
        telemetry.count(resultCard > 0 ? "base_queries.non_empty_result" : "base_queries.empty_result");
    }

    public static void recordGenerationTelemetry(PerformanceHintTelemetry telemetry, boolean aware, long elapsedNanos,
            long joinCardinality, long resultCardinality, boolean hasJoin) {
        recordGenerationTelemetry(telemetry, aware, elapsedNanos, joinCardinality, resultCardinality, hasJoin, false);
    }

    public static void recordGenerationTelemetry(PerformanceHintTelemetry telemetry, boolean aware, long elapsedNanos,
            long joinCardinality, long resultCardinality, boolean hasJoin, boolean hasResultCollapsing) {
        if (telemetry == null) {
            return;
        }
        if (aware) {
            telemetry.count("base_queries.predicate_binding_aware");
            telemetry.add("base_queries.predicate_binding_nanos", elapsedNanos);
            telemetry.count(hasResultCollapsing ? "binder.shape.collapsing" : "binder.shape.plain_select");
            telemetry.add("binder.est_rows.total", resultCardinality);
            telemetry.recordMaximum("binder.est_rows.max", resultCardinality);
            if (resultCardinality <= 0L) {
                telemetry.count("binder.est_rows.eq_0");
            } else if (resultCardinality <= 100L) {
                telemetry.count("binder.est_rows.le_100");
            } else if (resultCardinality <= 1000L) {
                telemetry.count("binder.est_rows.le_1000");
            } else if (resultCardinality <= 5000L) {
                telemetry.count("binder.est_rows.le_5000");
            } else if (resultCardinality <= 10000L) {
                telemetry.count("binder.est_rows.le_10000");
            } else {
                telemetry.count("binder.est_rows.gt_10000");
            }
        } else {
            telemetry.count("base_queries.predicate_binding_unaware");
        }
        if (hasJoin) {
            telemetry.count(joinCardinality > 0 ? "base_queries.non_empty_join" : "base_queries.empty_join");
        }
        telemetry.count(resultCardinality > 0 ? "base_queries.non_empty_result" : "base_queries.empty_result");
    }

    public static boolean hasResultCollapsingSql(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        return upper.contains(" DISTINCT ") || upper.contains("DISTINCTROW ") || upper.contains(" GROUP BY ")
                || upper.contains(" LIMIT ") || upper.contains("COUNT(") || upper.contains("SUM(")
                || upper.contains("MIN(") || upper.contains("MAX(") || upper.contains("AVG(");
    }

    public static void recordRuntimeBaseRowCount(PerformanceHintTelemetry telemetry, long rowCount, String sql) {
        recordRuntimeBaseRowCount(telemetry, rowCount, hasResultCollapsingSql(sql));
    }

    public static void recordRuntimeBaseRowCount(PerformanceHintTelemetry telemetry, long rowCount,
            boolean hasResultCollapsing) {
        if (telemetry == null) {
            return;
        }
        telemetry.count(rowCount > 0L ? "runtime.base_query_non_empty" : "runtime.base_query_empty");
        telemetry.add("runtime.base_query_total_rows", rowCount);
        telemetry.recordMaximum("runtime.base_query_max_rows", rowCount);
        if (rowCount <= 0L) {
            telemetry.count("runtime.base_rows.eq_0");
        } else if (rowCount <= 100L) {
            telemetry.count("runtime.base_rows.le_100");
        } else if (rowCount <= 1000L) {
            telemetry.count("runtime.base_rows.le_1000");
        } else if (rowCount <= 5000L) {
            telemetry.count("runtime.base_rows.le_5000");
        } else if (rowCount <= 10000L) {
            telemetry.count("runtime.base_rows.le_10000");
        } else {
            telemetry.count("runtime.base_rows.gt_10000");
            telemetry.count(
                    hasResultCollapsing ? "runtime.gt_10000.collapsing_select" : "runtime.gt_10000.plain_select");
        }
    }

    private static BoundPredicate materializeBoundPredicate(PredicateSlot slot, ColumnIndex col, long targetLong) {
        SqlType sqlType = col.getSqlType();
        if (sqlType == SqlType.VARCHAR) {
            String matched = null;
            int n = col.getRowCount();
            for (int r = 0; r < n; r++) {
                if (!col.isNullAt(r) && col.getLongAt(r) == targetLong) {
                    matched = col.getStringAt(r);
                    break;
                }
            }
            if (matched == null) {
                matched = col.getProducer().getModel().toLowerCase(Locale.ROOT) + "_0_" + col.getColumnIndex() + "_"
                        + targetLong;
            }
            return new BoundPredicate(slot, sqlType, targetLong, targetLong, matched);
        }
        if (sqlType == SqlType.DOUBLE) {
            double matched = targetLong;
            int n = col.getRowCount();
            for (int r = 0; r < n; r++) {
                if (!col.isNullAt(r) && col.getLongAt(r) == targetLong) {
                    matched = col.getDoubleAt(r);
                    break;
                }
            }
            return new BoundPredicate(slot, sqlType, targetLong, matched, null);
        }
        return new BoundPredicate(slot, SqlType.BIGINT, targetLong, targetLong, null);
    }

    private static long[] buildCandidatePool(TableIndex table, ColumnIndex col, Operator op, boolean[] joinActiveMask) {
        long[] temp = new long[MAX_CANDIDATES_PER_SLOT];
        int count = 0;

        if (col.isIntraTableDerived()) {
            ColumnIndex sourceCol = table.getColumn(col.getSourceColumnIndex());
            int rowCount = table.getRowCount();
            long[] survivingSource = new long[rowCount];
            int survivingCount = 0;
            for (int r = 0; r < rowCount; r++) {
                if ((joinActiveMask == null || joinActiveMask[r]) && !sourceCol.isNullAt(r) && !col.isNullAt(r)) {
                    survivingSource[survivingCount++] = sourceCol.getLongAt(r);
                    if (count < 2) {
                        count = addUniqueCandidate(temp, count, col.getLongAt(r));
                    }
                }
            }
            if (survivingCount > 0) {
                Arrays.sort(survivingSource, 0, survivingCount);
                int steps = Math.min(4, survivingCount);
                for (int s = 0; s < steps && count < MAX_CANDIDATES_PER_SLOT; s++) {
                    int idx = (int) Math.min(survivingCount - 1, Math.round((s + 0.5) * (survivingCount - 1) / steps));
                    long projected = col.projectFromSourceValue(survivingSource[idx]);
                    count = addUniqueCandidate(temp, count, adjustForOperator(projected, op));
                }
            }
        }

        if (joinActiveMask != null && count < MAX_CANDIDATES_PER_SLOT) {
            int rowCount = table.getRowCount();
            int stride = Math.max(1, rowCount / 2);
            for (int r = 0; r < rowCount && count < 2; r += stride) {
                if (joinActiveMask[r] && !col.isNullAt(r)) {
                    count = addUniqueCandidate(temp, count, adjustForOperator(col.getLongAt(r), op));
                }
            }
        }

        if (op.isEqualityLike()) {
            for (long hh : col.getHeavyHitters()) {
                if (count >= MAX_CANDIDATES_PER_SLOT) {
                    break;
                }
                count = addUniqueCandidate(temp, count, hh);
            }
            for (long tk : col.getTailKeys()) {
                if (count >= MAX_CANDIDATES_PER_SLOT) {
                    break;
                }
                count = addUniqueCandidate(temp, count, tk);
            }
        } else {
            long[] uniqueVals = col.getUniqueValues();
            if (uniqueVals.length > 0 && count < MAX_CANDIDATES_PER_SLOT) {
                if (op == Operator.LT || op == Operator.LTE) {
                    count = addUniqueCandidate(temp, count, adjustForOperator(uniqueVals[0], op));
                } else if (op == Operator.GT || op == Operator.GTE) {
                    count = addUniqueCandidate(temp, count, adjustForOperator(uniqueVals[uniqueVals.length - 1], op));
                }
            }
            for (long q : col.getQuantiles()) {
                if (count >= MAX_CANDIDATES_PER_SLOT) {
                    break;
                }
                count = addUniqueCandidate(temp, count, adjustForOperator(q, op));
            }
            for (long hh : col.getHeavyHitters()) {
                if (count >= MAX_CANDIDATES_PER_SLOT) {
                    break;
                }
                count = addUniqueCandidate(temp, count, adjustForOperator(hh, op));
            }
        }

        if (count == 0) {
            long[] quantiles = col.getQuantiles();
            if (quantiles.length > 0) {
                count = addUniqueCandidate(temp, count, quantiles[quantiles.length / 2]);
            } else {
                count = addUniqueCandidate(temp, count, 0L);
            }
        }
        return Arrays.copyOf(temp, count);
    }

    private static long adjustForOperator(long val, Operator op) {
        if (op == Operator.LT) {
            return val < Long.MAX_VALUE ? val + 1L : val;
        }
        if (op == Operator.GT) {
            return val > Long.MIN_VALUE ? val - 1L : val;
        }
        return val;
    }

    private static int addUniqueCandidate(long[] buffer, int count, long candidate) {
        if (count >= buffer.length) {
            return count;
        }
        for (int i = 0; i < count; i++) {
            if (buffer[i] == candidate) {
                return count;
            }
        }
        buffer[count] = candidate;
        return count + 1;
    }

    private static boolean isBetterCardinality(long candCard, long candDist, long bestCard, long bestDist) {
        if (bestCard <= 0 && candCard > 0) {
            return true;
        }
        if (bestCard > 0 && candCard <= 0) {
            return false;
        }
        return candDist < bestDist;
    }

    private static long intervalDistance(long value, long low, long high, boolean hasResultCollapsing) {
        if (value < low) {
            return low - value;
        }
        if (!hasResultCollapsing && value > 15000L) {
            long penalty = (value - 15000L) * 4L;
            if (value > 30000L) {
                penalty += (value - 30000L) * 16L;
            }
            return Math.max(value - high, 0L) + penalty;
        }
        if (value > high) {
            return value - high;
        }
        return 0L;
    }

    private static int findQueryTableOrdinal(TableIndex[] queryTables, String tableName) {
        for (int i = 0; i < queryTables.length; i++) {
            if (queryTables[i].getTableName().equalsIgnoreCase(tableName)) {
                return i;
            }
        }
        return -1;
    }

    private static ResolvedJoin[] resolveJoins(TableIndex[] queryTables, List<JoinEdgeSpec> joinEdges) {
        if (joinEdges == null || joinEdges.isEmpty() || queryTables.length <= 1) {
            return new ResolvedJoin[0];
        }
        List<ResolvedJoin> list = new ArrayList<>(joinEdges.size());
        for (JoinEdgeSpec spec : joinEdges) {
            int leftOrd = findQueryTableOrdinal(queryTables, spec.getLeftTable());
            int rightOrd = findQueryTableOrdinal(queryTables, spec.getRightTable());
            if (leftOrd < 0 || rightOrd < 0) {
                continue;
            }
            int leftCol = queryTables[leftOrd].findColumnIndex(spec.getLeftColumn());
            int rightCol = queryTables[rightOrd].findColumnIndex(spec.getRightColumn());
            if (leftCol < 0 || rightCol < 0) {
                continue;
            }
            list.add(new ResolvedJoin(leftOrd, leftCol, rightOrd, rightCol));
        }
        return list.toArray(new ResolvedJoin[0]);
    }

    private static boolean[][] computeJoinActiveRowMasks(TableIndex[] queryTables, PreparedJoin[] joins) {
        boolean[][] masks = new boolean[queryTables.length][];
        for (int t = 0; t < queryTables.length; t++) {
            masks[t] = new boolean[queryTables[t].getRowCount()];
            Arrays.fill(masks[t], true);
        }
        for (PreparedJoin join : joins) {
            boolean[] leftMask = masks[join.leftTableOrd];
            for (int r = 0; r < leftMask.length; r++) {
                if (leftMask[r] && join.leftRowKeyIdx[r] < 0) {
                    leftMask[r] = false;
                }
            }
            boolean[] rightMask = masks[join.rightTableOrd];
            for (int r = 0; r < rightMask.length; r++) {
                if (rightMask[r] && join.rightRowKeyIdx[r] < 0) {
                    rightMask[r] = false;
                }
            }
        }
        return masks;
    }

    private static long[][] allocateRowWeightBuffers(TableIndex[] queryTables) {
        long[][] buffers = new long[queryTables.length][];
        for (int t = 0; t < queryTables.length; t++) {
            buffers[t] = new long[queryTables[t].getRowCount()];
        }
        return buffers;
    }

    private static PreparedJoin[] prepareJoins(TableIndex[] queryTables, ResolvedJoin[] joins) {
        PreparedJoin[] prepared = new PreparedJoin[joins.length];
        for (int i = 0; i < joins.length; i++) {
            ResolvedJoin join = joins[i];
            ColumnIndex leftCol = queryTables[join.leftTableOrd].getColumn(join.leftColIdx);
            ColumnIndex rightCol = queryTables[join.rightTableOrd].getColumn(join.rightColIdx);
            long[] sharedKeys = intersectSortedUnique(leftCol.getUniqueValues(), rightCol.getUniqueValues());
            int leftRows = queryTables[join.leftTableOrd].getRowCount();
            int[] leftKeyIdx = new int[leftRows];
            if (sharedKeys.length == 0) {
                Arrays.fill(leftKeyIdx, -1);
            } else {
                for (int r = 0; r < leftRows; r++) {
                    leftKeyIdx[r] = leftCol.isNullAt(r) ? -1 : Arrays.binarySearch(sharedKeys, leftCol.getLongAt(r));
                }
            }
            int rightRows = queryTables[join.rightTableOrd].getRowCount();
            int[] rightKeyIdx = new int[rightRows];
            if (sharedKeys.length == 0) {
                Arrays.fill(rightKeyIdx, -1);
            } else {
                for (int r = 0; r < rightRows; r++) {
                    rightKeyIdx[r] = rightCol.isNullAt(r) ? -1 : Arrays.binarySearch(sharedKeys, rightCol.getLongAt(r));
                }
            }
            prepared[i] = new PreparedJoin(join.leftTableOrd, join.rightTableOrd, sharedKeys.length, leftKeyIdx,
                    rightKeyIdx);
        }
        return prepared;
    }

    private static long estimatePreparedJoinCardinality(TableIndex[] queryTables, PreparedJoin[] joins,
            SlotFilterState filterState, long[] theta, boolean conjunctive, long[][] rowWeights) {
        if (queryTables.length == 1 || joins.length == 0) {
            TableIndex table = queryTables[0];
            int rows = table.getRowCount();
            long surviving = 0L;
            for (int r = 0; r < rows; r++) {
                if (filterState == null || filterState.rowMatches(0, r, theta, conjunctive)) {
                    surviving++;
                }
            }
            return surviving;
        }
        for (int j = 0; j < joins.length; j++) {
            if (joins[j].sharedKeyCount == 0) {
                return 0L;
            }
        }
        for (int t = 0; t < queryTables.length; t++) {
            long[] w = rowWeights[t];
            int n = w.length;
            if (filterState == null || !filterState.hasSlotOnTable(t)) {
                Arrays.fill(w, 1L);
            } else {
                for (int r = 0; r < n; r++) {
                    w[r] = filterState.rowMatches(t, r, theta, conjunctive) ? 1L : 0L;
                }
            }
        }
        for (int j = 0; j < joins.length; j++) {
            PreparedJoin join = joins[j];
            long[] leftFreq = join.leftFreqBuffer;
            Arrays.fill(leftFreq, 0L);
            long[] leftW = rowWeights[join.leftTableOrd];
            int[] leftKeyIdx = join.leftRowKeyIdx;
            for (int r = 0; r < leftW.length; r++) {
                long w = leftW[r];
                if (w > 0L) {
                    int kIdx = leftKeyIdx[r];
                    if (kIdx >= 0) {
                        leftFreq[kIdx] += w;
                    }
                }
            }
            long[] rightW = rowWeights[join.rightTableOrd];
            int[] rightKeyIdx = join.rightRowKeyIdx;
            for (int r = 0; r < rightW.length; r++) {
                long w = rightW[r];
                if (w > 0L) {
                    int kIdx = rightKeyIdx[r];
                    rightW[r] = kIdx >= 0 ? w * leftFreq[kIdx] : 0L;
                }
            }
        }
        long[] finalWeights = rowWeights[joins[joins.length - 1].rightTableOrd];
        long total = 0L;
        for (long w : finalWeights) {
            total += w;
        }
        return total;
    }

    private static long[] intersectSortedUnique(long[] a, long[] b) {
        long[] temp = new long[Math.min(a.length, b.length)];
        int i = 0;
        int j = 0;
        int k = 0;
        while (i < a.length && j < b.length) {
            long va = a[i];
            long vb = b[j];
            if (va == vb) {
                temp[k++] = va;
                i++;
                j++;
            } else if (va < vb) {
                i++;
            } else {
                j++;
            }
        }
        return Arrays.copyOf(temp, k);
    }

    private static long estimateJoinCardinality(TableIndex[] queryTables, ResolvedJoin[] joins,
            SlotFilterState filterState, long[] theta, boolean conjunctive) {
        if (queryTables.length == 1 || joins.length == 0) {
            TableIndex table = queryTables[0];
            int rows = table.getRowCount();
            long surviving = 0L;
            for (int r = 0; r < rows; r++) {
                if (filterState == null || filterState.rowMatches(0, r, theta, conjunctive)) {
                    surviving++;
                }
            }
            return surviving;
        }

        // Propagate tuple multiplicities along the join tree without materializing joined tuples (Eq. 10)
        long[][] rowWeights = new long[queryTables.length][];
        for (int t = 0; t < queryTables.length; t++) {
            int n = queryTables[t].getRowCount();
            long[] w = new long[n];
            for (int r = 0; r < n; r++) {
                w[r] = filterState == null || filterState.rowMatches(t, r, theta, conjunctive) ? 1L : 0L;
            }
            rowWeights[t] = w;
        }

        for (ResolvedJoin join : joins) {
            ColumnIndex leftCol = queryTables[join.leftTableOrd].getColumn(join.leftColIdx);
            ColumnIndex rightCol = queryTables[join.rightTableOrd].getColumn(join.rightColIdx);
            long[] sharedKeys = intersectSortedUnique(leftCol.getUniqueValues(), rightCol.getUniqueValues());
            if (sharedKeys.length == 0) {
                return 0L;
            }
            long[] leftFreq = new long[sharedKeys.length];
            long[] leftW = rowWeights[join.leftTableOrd];
            for (int r = 0; r < leftW.length; r++) {
                long w = leftW[r];
                if (w > 0 && !leftCol.isNullAt(r)) {
                    int kIdx = Arrays.binarySearch(sharedKeys, leftCol.getLongAt(r));
                    if (kIdx >= 0) {
                        leftFreq[kIdx] += w;
                    }
                }
            }
            long[] rightW = rowWeights[join.rightTableOrd];
            for (int r = 0; r < rightW.length; r++) {
                long w = rightW[r];
                if (w <= 0 || rightCol.isNullAt(r)) {
                    rightW[r] = 0L;
                    continue;
                }
                int kIdx = Arrays.binarySearch(sharedKeys, rightCol.getLongAt(r));
                rightW[r] = kIdx >= 0 ? w * leftFreq[kIdx] : 0L;
            }
        }

        long[] finalWeights = rowWeights[joins[joins.length - 1].rightTableOrd];
        long total = 0L;
        for (long w : finalWeights) {
            total += w;
        }
        return total;
    }

    private static long evaluateUnawareWhereCardinality(TableIndex[] queryTables, ResolvedJoin[] joins,
            String whereSql) {
        if (whereSql == null || whereSql.isBlank()) {
            return estimateJoinCardinality(queryTables, joins, null, null, true);
        }
        List<ParsedAtomicPredicate> parsed = parseWherePredicates(queryTables, whereSql);
        if (parsed.isEmpty()) {
            return estimateJoinCardinality(queryTables, joins, null, null, true);
        }
        boolean conjunctive = !whereSql.toUpperCase(Locale.ROOT).contains(" OR ");
        long[][] rowWeights = new long[queryTables.length][];
        for (int t = 0; t < queryTables.length; t++) {
            int n = queryTables[t].getRowCount();
            long[] w = new long[n];
            for (int r = 0; r < n; r++) {
                w[r] = evaluateParsedRow(t, r, parsed, conjunctive) ? 1L : 0L;
            }
            rowWeights[t] = w;
        }
        if (queryTables.length == 1 || joins.length == 0) {
            long sum = 0L;
            for (long w : rowWeights[0]) {
                sum += w;
            }
            return sum;
        }
        for (ResolvedJoin join : joins) {
            ColumnIndex leftCol = queryTables[join.leftTableOrd].getColumn(join.leftColIdx);
            ColumnIndex rightCol = queryTables[join.rightTableOrd].getColumn(join.rightColIdx);
            long[] sharedKeys = intersectSortedUnique(leftCol.getUniqueValues(), rightCol.getUniqueValues());
            if (sharedKeys.length == 0) {
                return 0L;
            }
            long[] leftFreq = new long[sharedKeys.length];
            long[] leftW = rowWeights[join.leftTableOrd];
            for (int r = 0; r < leftW.length; r++) {
                long w = leftW[r];
                if (w > 0 && !leftCol.isNullAt(r)) {
                    int kIdx = Arrays.binarySearch(sharedKeys, leftCol.getLongAt(r));
                    if (kIdx >= 0) {
                        leftFreq[kIdx] += w;
                    }
                }
            }
            long[] rightW = rowWeights[join.rightTableOrd];
            for (int r = 0; r < rightW.length; r++) {
                long w = rightW[r];
                if (w <= 0 || rightCol.isNullAt(r)) {
                    rightW[r] = 0L;
                    continue;
                }
                int kIdx = Arrays.binarySearch(sharedKeys, rightCol.getLongAt(r));
                rightW[r] = kIdx >= 0 ? w * leftFreq[kIdx] : 0L;
            }
        }
        long[] finalWeights = rowWeights[joins[joins.length - 1].rightTableOrd];
        long total = 0L;
        for (long w : finalWeights) {
            total += w;
        }
        return total;
    }

    private static boolean evaluateParsedRow(int tableOrd, int rowIdx, List<ParsedAtomicPredicate> predicates,
            boolean conjunctive) {
        boolean hasSlotOnTable = false;
        boolean acc = conjunctive;
        for (ParsedAtomicPredicate pred : predicates) {
            if (pred.tableOrd != tableOrd) {
                continue;
            }
            hasSlotOnTable = true;
            boolean match = pred.matches(rowIdx);
            if (conjunctive) {
                if (!match) {
                    return false;
                }
            } else {
                if (match) {
                    return true;
                }
                acc = false;
            }
        }
        return !hasSlotOnTable || acc;
    }

    private static List<ParsedAtomicPredicate> parseWherePredicates(TableIndex[] queryTables, String whereSql) {
        List<ParsedAtomicPredicate> list = new ArrayList<>(4);
        Matcher matcher = ATOMIC_PREDICATE_PATTERN.matcher(whereSql);
        while (matcher.find()) {
            String tName = matcher.group(1);
            String cName = matcher.group(2);
            String rawOp = matcher.group(3).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            String rawLit = matcher.group(4);
            int tOrd = findQueryTableOrdinal(queryTables, tName);
            if (tOrd < 0) {
                continue;
            }
            ColumnIndex col = queryTables[tOrd].findColumn(cName);
            if (col == null) {
                continue;
            }
            list.add(new ParsedAtomicPredicate(tOrd, col, rawOp, rawLit));
        }
        return list;
    }

    private static final class ResolvedJoin {
        private final int leftTableOrd;
        private final int leftColIdx;
        private final int rightTableOrd;
        private final int rightColIdx;

        ResolvedJoin(int leftTableOrd, int leftColIdx, int rightTableOrd, int rightColIdx) {
            this.leftTableOrd = leftTableOrd;
            this.leftColIdx = leftColIdx;
            this.rightTableOrd = rightTableOrd;
            this.rightColIdx = rightColIdx;
        }
    }

    private static final class PreparedJoin {
        private final int leftTableOrd;
        private final int rightTableOrd;
        private final int sharedKeyCount;
        private final int[] leftRowKeyIdx;
        private final int[] rightRowKeyIdx;
        private final long[] leftFreqBuffer;

        PreparedJoin(int leftTableOrd, int rightTableOrd, int sharedKeyCount, int[] leftRowKeyIdx,
                int[] rightRowKeyIdx) {
            this.leftTableOrd = leftTableOrd;
            this.rightTableOrd = rightTableOrd;
            this.sharedKeyCount = sharedKeyCount;
            this.leftRowKeyIdx = leftRowKeyIdx;
            this.rightRowKeyIdx = rightRowKeyIdx;
            this.leftFreqBuffer = new long[sharedKeyCount];
        }
    }

    private static final class SlotFilterState {
        private final int numSlots;
        private final int[] slotTableOrd;
        private final ColumnIndex[] slotCols;
        private final Operator[] slotOps;

        SlotFilterState(int numSlots, int[] slotTableOrd, ColumnIndex[] slotCols, List<PredicateSlot> slots) {
            this.numSlots = numSlots;
            this.slotTableOrd = slotTableOrd;
            this.slotCols = slotCols;
            this.slotOps = new Operator[numSlots];
            for (int i = 0; i < numSlots; i++) {
                this.slotOps[i] = slots.get(i).getOperator();
            }
        }

        boolean hasSlotOnTable(int tableOrd) {
            for (int j = 0; j < numSlots; j++) {
                if (slotTableOrd[j] == tableOrd) {
                    return true;
                }
            }
            return false;
        }

        boolean rowMatches(int tableOrd, int rowIdx, long[] theta, boolean conjunctive) {
            boolean hasSlot = false;
            boolean result = conjunctive;
            for (int j = 0; j < numSlots; j++) {
                if (slotTableOrd[j] != tableOrd) {
                    continue;
                }
                hasSlot = true;
                ColumnIndex col = slotCols[j];
                boolean match = !col.isNullAt(rowIdx) && slotOps[j].evaluate(col.getLongAt(rowIdx), theta[j]);
                if (conjunctive) {
                    if (!match) {
                        return false;
                    }
                } else {
                    if (match) {
                        return true;
                    }
                    result = false;
                }
            }
            return !hasSlot || result;
        }
    }

    private static final class ParsedAtomicPredicate {
        private final int tableOrd;
        private final ColumnIndex col;
        private final String op;
        private final String literal;
        private final long numericLiteral;

        ParsedAtomicPredicate(int tableOrd, ColumnIndex col, String op, String literal) {
            this.tableOrd = tableOrd;
            this.col = col;
            this.op = op;
            this.literal = literal != null && literal.startsWith("'") && literal.endsWith("'") && literal.length() >= 2
                    ? literal.substring(1, literal.length() - 1) : literal;
            long parsed = 0L;
            if (this.literal != null) {
                try {
                    parsed = Math.round(Double.parseDouble(this.literal));
                } catch (NumberFormatException ignored) {
                    parsed = this.literal.hashCode();
                }
            }
            this.numericLiteral = parsed;
        }

        boolean matches(int rowIdx) {
            if ("IS NULL".equals(op)) {
                return col.isNullAt(rowIdx);
            }
            if ("IS NOT NULL".equals(op)) {
                return !col.isNullAt(rowIdx);
            }
            if (col.isNullAt(rowIdx)) {
                return false;
            }
            if ("IS TRUE".equals(op)) {
                return col.getLongAt(rowIdx) != 0L;
            }
            if ("IS FALSE".equals(op)) {
                return col.getLongAt(rowIdx) == 0L;
            }
            if (col.getSqlType() == SqlType.VARCHAR && literal != null) {
                String val = col.getStringAt(rowIdx);
                if ("=".equals(op)) {
                    return literal.equals(val);
                }
                if ("!=".equals(op) || "<>".equals(op)) {
                    return !literal.equals(val);
                }
            }
            long cell = col.getLongAt(rowIdx);
            switch (op) {
            case "=":
                return cell == numericLiteral;
            case "!=":
            case "<>":
                return cell != numericLiteral;
            case "<":
                return cell < numericLiteral;
            case "<=":
                return cell <= numericLiteral;
            case ">":
                return cell > numericLiteral;
            case ">=":
                return cell >= numericLiteral;
            default:
                return true;
            }
        }
    }
}
