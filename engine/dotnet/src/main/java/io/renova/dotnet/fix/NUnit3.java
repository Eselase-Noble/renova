package io.renova.dotnet.fix;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What NUnit 3 removed from NUnit 2, rewritten so that each test checks what it checked before:
 *
 * <ul>
 *   <li>{@code [ExpectedException(typeof(X))]} becomes {@code Assert.Throws<X>} around the test's body. NUnit 2
 *       matched the exact type, as Assert.Throws does. An attribute with more arguments (a message to match)
 *       is left for a person.</li>
 *   <li>{@code [TestFixtureSetUp]} and {@code [TestFixtureTearDown]} become {@code [OneTimeSetUp]} and
 *       {@code [OneTimeTearDown]}.</li>
 *   <li>{@code Assert.IsNullOrEmpty} and {@code Assert.IsNotNullOrEmpty} become constraints.</li>
 *   <li>{@code [Ignore]} gets the reason NUnit 3 requires.</li>
 * </ul>
 */
public final class NUnit3 {

    /** The attribute by itself or beside others in one pair of brackets; group 1 is the exception type. */
    private static final Pattern EXPECTED = Pattern.compile("ExpectedException\\s*\\(\\s*typeof\\s*\\(\\s*([\\w.<>,\\s]+?)\\s*\\)\\s*\\)");

    private NUnit3() {
    }

    public static String upgrade(String source) {
        String code = source
                .replaceAll("\\bTestFixtureSetUp\\b", "OneTimeSetUp")
                .replaceAll("\\bTestFixtureTearDown\\b", "OneTimeTearDown")
                .replaceAll("Assert\\.IsNullOrEmpty\\s*\\(([^;]*?)\\)\\s*;", "Assert.That($1, Is.Null.Or.Empty);")
                .replaceAll("Assert\\.IsNotNullOrEmpty\\s*\\(([^;]*?)\\)\\s*;", "Assert.That($1, Is.Not.Null.And.Not.Empty);")
                .replaceAll("\\[Ignore\\]", "[Ignore(\"Ignored in the original tests\")]")
                .replaceAll("\\[Ignore\\(\\s*\\)\\]", "[Ignore(\"Ignored in the original tests\")]");
        while (true) {
            Matcher m = EXPECTED.matcher(code);
            if (!m.find()) {
                return code;
            }
            String rewritten = wrap(code, m);
            if (rewritten == null) {
                // Not a shape that can be rewritten safely: mark it so the loop ends, and unmark below.
                code = code.substring(0, m.start()) + "ExpectedException\u0000" + code.substring(m.start() + "ExpectedException".length());
                continue;
            }
            code = rewritten;
            if (!EXPECTED.matcher(code).find()) {
                return code.replace("ExpectedException\u0000", "ExpectedException");
            }
        }
    }

    /** Removes the attribute at {@code m} and wraps the body of the method that follows; null if it has none. */
    private static String wrap(String code, Matcher m) {
        // The method's body: the first '{' after the attribute's closing bracket and the parameter list.
        int bracket = code.indexOf(']', m.end());
        int parameters = bracket < 0 ? -1 : code.indexOf('(', bracket);
        int closeParameters = parameters < 0 ? -1 : matching(code, parameters, '(', ')');
        int open = closeParameters < 0 ? -1 : code.indexOf('{', closeParameters);
        if (open < 0 || !code.substring(closeParameters + 1, open).isBlank()) {
            return null;
        }
        int close = matching(code, open, '{', '}');
        if (close < 0) {
            return null;
        }
        String type = m.group(1).strip();
        String indent = lineIndent(code, open);
        String newline = code.contains("\r\n") ? "\r\n" : "\n";
        // The body ends with the indentation of its closing brace; that brace is written again below.
        String body = code.substring(open + 1, close).replaceFirst("[ \\t]*$", "");
        String wrapped = "{" + newline + indent + "    Assert.Throws<" + type + ">(() =>" + newline + indent + "    {"
                + body.replace(newline, newline + "    ") + indent + "});" + newline + indent + "}";
        // Out of the attribute list: alone in its brackets, or beside others.
        int listStart = code.lastIndexOf('[', m.start());
        String before = code.substring(listStart + 1, m.start());
        String after = code.substring(m.end(), bracket);
        String attributes;
        int removeFrom = listStart;
        int removeTo = bracket + 1;
        if (before.isBlank() && after.isBlank()) {
            attributes = "";
            // The whole line goes when nothing else is on it.
            int lineStart = code.lastIndexOf('\n', listStart) + 1;
            int lineEnd = code.indexOf('\n', bracket);
            if (code.substring(lineStart, listStart).isBlank() && lineEnd > 0 && code.substring(bracket + 1, lineEnd).isBlank()) {
                removeFrom = lineStart;
                removeTo = lineEnd + 1;
            }
        } else {
            String rest = (before.replaceFirst(",\\s*$", "") + (before.isBlank() ? after.replaceFirst("^\\s*,\\s*", "") : after)).strip();
            attributes = "[" + rest + "]";
        }
        return code.substring(0, removeFrom) + attributes + code.substring(removeTo, open) + wrapped + code.substring(close + 1);
    }

    /** The index of the bracket that closes the one at {@code from}, skipping strings, characters and comments. */
    private static int matching(String code, int from, char opening, char closing) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                boolean verbatim = c == '"' && i > 0 && code.charAt(i - 1) == '@';
                for (i++; i < code.length() && (code.charAt(i) != c || (verbatim && i + 1 < code.length() && code.charAt(i + 1) == c)); i++) {
                    if (verbatim && code.charAt(i) == c) {
                        i++;
                    } else if (!verbatim && code.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '/') {
                i = code.indexOf('\n', i);
                if (i < 0) {
                    return -1;
                }
            } else if (c == '/' && i + 1 < code.length() && code.charAt(i + 1) == '*') {
                i = code.indexOf("*/", i) + 1;
                if (i <= 0) {
                    return -1;
                }
            } else if (c == opening) {
                depth++;
            } else if (c == closing && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String lineIndent(String code, int index) {
        int start = code.lastIndexOf('\n', index) + 1;
        int end = start;
        while (end < code.length() && (code.charAt(end) == ' ' || code.charAt(end) == '\t')) {
            end++;
        }
        return code.substring(start, end);
    }
}
