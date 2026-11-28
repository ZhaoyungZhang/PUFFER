package sqlancer.common.perfhint;

/** Small position-preserving lexer for generated SQL; not a SQL parser. */
public final class PerformanceSqlLexical {
    private PerformanceSqlLexical() {
    }

    /* Mask string literals, quoted identifiers, and comments without changing token offsets. */
    public static String maskLiterals(String sql) {
        char[] text = sql.toCharArray();
        for (int i = 0; i < text.length; i++) {
            char ch = text[i];
            if (ch == '\'' || ch == '"' || ch == '`') {
                char quote = ch;
                text[i] = ' ';
                while (++i < text.length) {
                    ch = text[i];
                    text[i] = ' ';
                    if (ch == '\\' && i + 1 < text.length) {
                        text[++i] = ' ';
                    } else if (ch == quote) {
                        if (i + 1 < text.length && text[i + 1] == quote) {
                            text[++i] = ' ';
                        } else {
                            break;
                        }
                    }
                }
            } else if (ch == '/' && i + 1 < text.length && text[i + 1] == '*') {
                text[i] = ' ';
                text[++i] = ' ';
                while (++i < text.length) {
                    ch = text[i];
                    text[i] = ' ';
                    if (ch == '*' && i + 1 < text.length && text[i + 1] == '/') {
                        text[++i] = ' ';
                        break;
                    }
                }
            } else if (ch == '#' || ch == '-' && i + 1 < text.length && text[i + 1] == '-') {
                while (i < text.length && text[i] != '\n') {
                    text[i++] = ' ';
                }
            }
        }
        return new String(text);
    }

    public static boolean hasCommaFrom(String sql) {
        String text = maskLiterals(sql).toUpperCase(java.util.Locale.ROOT);
        int depth = 0;
        boolean inFrom = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (depth == 0) {
                if (text.startsWith(" FROM ", i)) {
                    inFrom = true;
                } else if (inFrom && (text.startsWith(" WHERE ", i) || text.startsWith(" GROUP ", i)
                        || text.startsWith(" ORDER ", i) || text.startsWith(" LIMIT ", i))) {
                    return false;
                } else if (inFrom && ch == ',') {
                    return true;
                }
            }
        }
        return false;
    }
}
