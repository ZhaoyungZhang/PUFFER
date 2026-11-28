package sqlancer.common.perfhint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import sqlancer.common.perfhint.ExpandedPerformanceQuery.JoinType;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Result;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode.Family;

/** Shared behavioral contract, exercised against each native backend rather than a mock SQL renderer. */
public final class ExpandedQueryContract {
    private ExpandedQueryContract() {
    }

    public static void verify(Result<?, ?> query, List<String> names, Family family) {
        String sql = PerformanceSqlLexical.maskLiterals(query.sql);
        assertEquals(family, query.metadata.getFamily());
        assertTrue(query.sql.startsWith("SELECT "), query.sql);
        assertTrue(query.tables.size() >= 1 && query.tables.size() <= 3, query.sql);
        assertEquals(query.tables.size() - 1, query.joins.size());
        assertEquals(names.size(), names.stream().distinct().count());
        Matcher references = Pattern.compile("\\b(t[0-9]+)\\.").matcher(sql);
        while (references.find()) {
            assertTrue(names.contains(references.group(1)), query.sql);
        }
        assertFalse(Pattern.compile("(?i)\\b(RAND|UUID|CURRENT_USER|CURRENT_TIMESTAMP|CONNECTION_ID|DATABASE)\\s*\\(")
                .matcher(sql).find(), query.sql);
        for (int i = 0; i < query.joins.size(); i++) {
            assertEquals(i, query.joins.get(i).ordinal);
            if (query.joins.get(i).type == JoinType.CROSS || !query.joins.get(i).equality) {
                assertTrue(query.tables.size() <= 2, query.sql);
            }
        }
        if (family == Family.EXPRESSION) {
            assertTrue(query.metadata.getFeatures().contains("EXPRESSION_PROJECTION"));
        } else if (family == Family.AGGREGATE) {
            assertTrue(Pattern.compile("\\b(COUNT|SUM|MIN|MAX)\\(").matcher(sql).find(), query.sql);
            assertFalse(Pattern.compile("\\b(COUNT|SUM|MIN|MAX)\\(.*\\b(COUNT|SUM|MIN|MAX)\\(")
                    .matcher(sql.substring(0, sql.indexOf(" FROM ")).replaceAll(" AS ref[0-9]+, ", "\n")).find(),
                    "aggregate arguments must not contain nested aggregates: " + query.sql);
            if (query.where) {
                String where = sql.split(" WHERE ")[1].split(" GROUP BY | HAVING | ORDER BY | LIMIT ")[0];
                assertFalse(Pattern.compile("\\b(COUNT|SUM|MIN|MAX)\\(").matcher(where).find(), query.sql);
            }
        } else if (family == Family.JOIN) {
            assertTrue(query.tables.size() >= 2);
        }
        if (query.limit) {
            assertTrue(query.orderBy, query.sql);
            String order = sql.split(" ORDER BY ")[1].split(" LIMIT ")[0];
            assertEquals(IntStream.rangeClosed(1, query.projectionCount).mapToObj(Integer::toString)
                    .collect(Collectors.joining(", ")), order);
        }
        assertFalse(PerformanceSqlLexical.hasCommaFrom(query.sql), query.sql);
    }
}
