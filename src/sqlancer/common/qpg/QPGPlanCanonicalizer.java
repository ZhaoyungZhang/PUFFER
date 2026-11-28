package sqlancer.common.qpg;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QPGPlanCanonicalizer {

    private static final Pattern JSON_OPERATION_FIELD = Pattern
            .compile("\"operation\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern OCEANBASE_NODE = Pattern.compile(
            "\"OPERATOR\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"(?:(?!\"OPERATOR\")[\\s\\S])*?\"NAME\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OCEANBASE_OPERATION_FIELD = Pattern
            .compile("\"OPERATOR\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_ADDRESS = Pattern.compile("0x[0-9a-fA-F]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PAREN_SUFFIX = Pattern.compile("\\s*\\([^)]*\\)");
    private static final Pattern USING_SUFFIX = Pattern.compile("\\s+using\\s+.+$");
    private static final Pattern ON_SUFFIX = Pattern.compile("\\s+on\\s+.+$");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]+");

    private QPGPlanCanonicalizer() {
    }

    public static String normalizeMySQLLikeJsonPlan(String explainJson) {
        if (explainJson == null) {
            return "";
        }
        Matcher matcher = JSON_OPERATION_FIELD.matcher(explainJson);
        List<String> normalizedOperations = new ArrayList<>();
        while (matcher.find()) {
            String normalizedOperation = normalizeOperation(unescapeJsonString(matcher.group(1)));
            if (!normalizedOperation.isEmpty()) {
                normalizedOperations.add(normalizedOperation);
            }
        }
        if (normalizedOperations.isEmpty()) {
            return explainJson.replaceAll("\\s+", " ").trim();
        }
        return String.join(";", normalizedOperations) + ";";
    }

    /**
     * OceanBase JSON plans expose stable operator/name pairs plus volatile IDs and estimates. Preserve the former and
     * intentionally discard the latter so QPG rewards structural plan changes rather than timing/statistics noise.
     */
    public static String normalizeOceanBaseJsonPlan(String explainJson) {
        if (explainJson == null) {
            return "";
        }
        List<String> normalizedNodes = new ArrayList<>();
        Matcher nodeMatcher = OCEANBASE_NODE.matcher(explainJson);
        while (nodeMatcher.find()) {
            String operator = normalizeOperation(unescapeJsonString(nodeMatcher.group(1)));
            String name = normalizeOceanBaseName(unescapeJsonString(nodeMatcher.group(2)));
            if (!operator.isEmpty()) {
                normalizedNodes.add(name.isEmpty() ? operator : operator + "@" + name);
            }
        }
        if (normalizedNodes.isEmpty()) {
            Matcher operatorMatcher = OCEANBASE_OPERATION_FIELD.matcher(explainJson);
            while (operatorMatcher.find()) {
                String operator = normalizeOperation(unescapeJsonString(operatorMatcher.group(1)));
                if (!operator.isEmpty()) {
                    normalizedNodes.add(operator);
                }
            }
        }
        if (normalizedNodes.isEmpty()) {
            return explainJson.replaceAll("\\s+", " ").trim();
        }
        return String.join(";", normalizedNodes) + ";";
    }

    private static String normalizeOperation(String rawOperation) {
        String operation = rawOperation.trim();
        int colonIndex = operation.indexOf(':');
        if (colonIndex != -1) {
            operation = operation.substring(0, colonIndex);
        }
        operation = PAREN_SUFFIX.matcher(operation).replaceAll("");
        operation = USING_SUFFIX.matcher(operation).replaceAll("");
        operation = ON_SUFFIX.matcher(operation).replaceAll("");
        operation = WHITESPACE.matcher(operation).replaceAll(" ").trim();
        return NON_ALPHANUMERIC.matcher(operation).replaceAll("");
    }

    private static String normalizeOceanBaseName(String rawName) {
        String name = HEX_ADDRESS.matcher(rawName).replaceAll("");
        name = WHITESPACE.matcher(name).replaceAll(" ").trim();
        return NON_ALPHANUMERIC.matcher(name).replaceAll("");
    }

    private static String unescapeJsonString(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/");
    }

}
