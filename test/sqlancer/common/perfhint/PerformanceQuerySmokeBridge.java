package sqlancer.common.perfhint;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.yaml.snakeyaml.Yaml;

import sqlancer.Randomly;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Join;
import sqlancer.common.perfhint.ExpandedPerformanceQuery.Result;
import sqlancer.common.perfhint.PerformanceQueryGenerationMode.Family;

/** Offline fixture exporter and stdin plan verifier. Neither mode opens a database connection. */
public final class PerformanceQuerySmokeBridge {
    private static final Map<String, String> TITLES = Map.of("mysql", "MySQL", "mariadb", "MariaDB", "tidb", "TiDB",
            "cockroachdb", "CockroachDB", "oceanbase", "OceanBase");

    private PerformanceQuerySmokeBridge() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("export")) {
            export(Path.of(args[1]));
        } else if (args.length == 1 && args[0].equals("verify")) {
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = input.readLine()) != null) {
                Map<String, String> request = new Yaml().load(line);
                try {
                    System.out.println(verify(request));
                } catch (ReflectiveOperationException | IllegalArgumentException e) {
                    System.out.println("{\"error\":\"plan_verification_failed\"}");
                    e.printStackTrace(System.err);
                }
                System.out.flush();
            }
        } else {
            throw new IllegalArgumentException("export <directory> | verify");
        }
    }

    private static void export(Path directory) throws Exception {
        Files.createDirectories(directory);
        for (Map.Entry<String, String> backend : TITLES.entrySet()) {
            String db = backend.getKey();
            String title = backend.getValue();
            String prefix = "sqlancer." + db + ".oracle.";
            Class<?> fixture = Class.forName(prefix + "Test" + title + "ExpandedQueryGenerator");
            List<Object> tables = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                tables.add(fixture.getMethod("table", String.class).invoke(null, "t" + i));
            }
            List<String> samples = new ArrayList<>();
            for (Family family : List.of(Family.EXPRESSION, Family.AGGREGATE, Family.JOIN)) {
                for (int index = 0; index < 100; index++) {
                    int seed = index + family.ordinal() * 1000;
                    Object state = fixture.getMethod("state", int.class).invoke(null, seed);
                    Class<?> generator = Class.forName(prefix + title + "ExpandedQueryGenerator");
                    Method generate = generator.getMethod("generate", state.getClass(), List.class, Family.class,
                            Consumer.class);
                    Consumer<String> rejected = reason -> {
                        throw new AssertionError("fixture generation: " + reason);
                    };
                    Result<?, ?> result = (Result<?, ?>) generate.invoke(null, state, tables, family, rejected);
                    List<String> hints = new ArrayList<>();
                    for (Object hint : candidates(db, title, result)) {
                        String descriptor = (String) call(hint, "getHintSql");
                        String alternative;
                        if (db.equals("cockroachdb")) {
                            alternative = (String) invoke(utils(db), "applyCandidate",
                                    new Class<?>[] { String.class, hint.getClass() }, result.sql, hint);
                        } else {
                            alternative = (String) invoke(utils(db), "injectHint",
                                    new Class<?>[] { String.class, String.class }, result.sql, descriptor);
                        }
                        if (!alternative.equals(result.sql)) {
                            hints.add("{\"sql_base64\":\"" + encode(alternative) + "\",\"descriptor_base64\":\""
                                    + encode(descriptor) + "\",\"family\":\"" + call(hint, "getHintFamily")
                                    + "\",\"type\":\"" + call(hint, "getHintType") + "\",\"tables\":\""
                                    + encode(String.join(",", stringList(call(hint, "getTableNames"))))
                                    + "\",\"indexes\":\""
                                    + encode(String.join(",", stringList(call(hint, "getIndexNames")))) + "\"}");
                        }
                    }
                    samples.add("{\"seed\":" + seed + ",\"sql_base64\":\"" + encode(result.sql) + "\",\"generation\":"
                            + result.metadata.toJson() + ",\"hints\":[" + String.join(",", hints) + "]}");
                }
            }
            Files.write(directory.resolve(db + ".ndjson"), samples, StandardCharsets.UTF_8);
        }
    }

    private static List<?> candidates(String db, String title, Result<?, ?> result) throws Exception {
        Class<?> generator = Class.forName("sqlancer." + db + ".gen." + title + "PerformanceHintGenerator");
        Method method = Arrays.stream(generator.getMethods()).filter(m -> m.getName().equals("generateCandidates"))
                .findFirst().orElseThrow();
        if (method.getParameterCount() == 4) {
            return (List<?>) method.invoke(null, result.tables, new Randomly(19), 3, 16);
        }
        Class<?> shapeClass = Class.forName(generator.getName() + "$QueryShape");
        Object shape;
        if (db.equals("mysql")) {
            shape = shapeClass.getConstructor(int.class, boolean.class, boolean.class, boolean.class, boolean.class)
                    .newInstance(result.tables.size(), !result.joins.isEmpty(), result.where, result.orderBy,
                            result.groupBy);
        } else if (db.equals("oceanbase")) {
            shape = shapeClass.getConstructor(boolean.class, boolean.class, boolean.class).newInstance(result.where,
                    !result.joins.isEmpty(), result.groupBy);
        } else {
            Class<?> joinClass = Class.forName(generator.getName() + "$JoinSpec");
            Class<?> joinType = Class.forName(generator.getName() + "$JoinType");
            List<Object> joins = new ArrayList<>();
            for (Join<?, ?> join : result.joins) {
                Object type = Arrays.stream(joinType.getEnumConstants())
                        .filter(v -> v.toString().equals(join.type.name())).findFirst().orElseThrow();
                joins.add(joinClass.getConstructor(int.class, joinType, String.class, String.class, boolean.class,
                        String.class, boolean.class, boolean.class, String.class, String.class)
                        .newInstance(join.ordinal, type, call(join.leftTable, "getName"),
                                call(join.rightTable, "getName"), true, join.type.token(), join.equality,
                                join.rightColumn != null && (boolean) call(join.rightColumn, "isPrimaryKey"),
                                join.leftColumn == null ? null : call(join.leftColumn, "getFullQualifiedName"),
                                join.rightColumn == null ? null : call(join.rightColumn, "getFullQualifiedName")));
            }
            shape = shapeClass.getConstructor(int.class, boolean.class, boolean.class, boolean.class, boolean.class,
                    List.class, boolean.class, boolean.class, Set.class).newInstance(result.tables.size(), result.where,
                            result.groupBy, result.orderBy, result.limit, joins, false, false, Set.of());
        }
        return (List<?>) method.invoke(null, result.tables, shape, new Randomly(19), 3, 16);
    }

    private static String verify(Map<String, String> request) throws ReflectiveOperationException {
        String db = request.get("dbms");
        Class<?> utility = utils(db);
        Object base = invoke(utility, "parsePlan", new Class<?>[] { String.class }, decode(request.get("base_plan")));
        Object hinted = invoke(utility, "parsePlan", new Class<?>[] { String.class }, decode(request.get("hint_plan")));
        boolean changed = (boolean) invoke(utility, "hasMeaningfulPlanDifference",
                new Class<?>[] { base.getClass(), hinted.getClass() }, base, hinted);
        boolean accepted = changed;
        if (db.equals("oceanbase")) {
            accepted &= (boolean) invoke(utility, "isHintAccepted", new Class<?>[] { String.class, String.class },
                    decode(request.get("extended_plan")), request.get("type"));
        } else if (db.equals("cockroachdb")) {
            CounterfactualCandidate candidate = CounterfactualCandidate.hint(decode(request.get("sql_base64")),
                    request.get("family"), request.get("type"), decode(request.get("descriptor_base64")),
                    csv(request.get("tables")), csv(request.get("indexes")));
            accepted &= (boolean) invoke(utility, "isHintAcceptedByPlan",
                    new Class<?>[] { CounterfactualCandidate.class, hinted.getClass() }, candidate, hinted);
        }
        return "{\"accepted\":" + accepted + ",\"plan_changed\":" + changed + ",\"base_signature\":\""
                + encode(planIdentity(base)) + "\",\"hint_signature\":\"" + encode(planIdentity(hinted)) + "\"}";
    }

    private static String planIdentity(Object plan) throws IllegalAccessException {
        List<String> identity = new ArrayList<>();
        for (Field field : plan.getClass().getDeclaredFields()) {
            if (Set.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                List<String> values = new ArrayList<>();
                for (Object value : (Set<?>) field.get(plan)) {
                    values.add(value.toString());
                }
                java.util.Collections.sort(values);
                identity.add(field.getName() + "=" + values);
            }
        }
        java.util.Collections.sort(identity);
        return String.join(";", identity);
    }

    private static Class<?> utils(String db) throws ClassNotFoundException {
        if (!TITLES.containsKey(db)) {
            throw new IllegalArgumentException("unknown backend");
        }
        return Class.forName("sqlancer." + db + ".oracle." + TITLES.get(db) + "PerformanceHintOracleUtils");
    }

    private static Object invoke(Class<?> target, String name, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        Method method = target.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object list) {
        return (List<String>) list;
    }

    private static List<String> csv(String encoded) {
        String value = decode(encoded);
        return value.isEmpty() ? List.of() : Arrays.asList(value.split(","));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
