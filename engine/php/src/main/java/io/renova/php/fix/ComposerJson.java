package io.renova.php.fix;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Edits to a composer.json as text, so its order, indentation and anything Renova does not know stay as they are. */
public final class ComposerJson {

    private ComposerJson() {
    }

    /** Sets the constraint of a package wherever it is required; a file that does not require it is unchanged. */
    public static String setConstraint(String json, String name, String constraint) {
        String out = json;
        for (String section : new String[] {"require", "require-dev"}) {
            int[] body = section(out, section);
            if (body == null) {
                continue;
            }
            Matcher m = entry(name).matcher(out).region(body[0], body[1]);
            if (m.find()) {
                out = out.substring(0, m.start(2)) + constraint + out.substring(m.end(2));
            }
        }
        return out;
    }

    public static boolean requires(String json, String name) {
        for (String section : new String[] {"require", "require-dev"}) {
            int[] body = section(json, section);
            if (body != null && entry(name).matcher(json).region(body[0], body[1]).find()) {
                return true;
            }
        }
        return false;
    }

    /** Adds a package to {@code require} or {@code require-dev}; a file that already requires it is unchanged. */
    public static String add(String json, String name, String constraint, boolean dev) {
        if (requires(json, name)) {
            return json;
        }
        String section = dev ? "require-dev" : "require";
        String newline = json.contains("\r\n") ? "\r\n" : "\n";
        int[] body = section(json, section);
        if (body == null) {
            // After "require" if there is one, else as the first key.
            int[] require = section(json, "require");
            int at = require != null ? json.indexOf('}', require[1]) + 1 : json.indexOf('{') + 1;
            String indent = "    ";
            String block = (require != null ? "," : "") + newline + indent + "\"" + section + "\": {" + newline + indent + indent
                    + "\"" + name + "\": \"" + constraint + "\"" + newline + indent + "}" + (require != null ? "" : ",");
            return json.substring(0, at) + block + json.substring(at);
        }
        String inside = json.substring(body[0], body[1]);
        Matcher last = Pattern.compile("(?m)^([ \\t]*)\"[^\"]+\"\\s*:\\s*\"[^\"]*\"[ \\t]*$").matcher(inside);
        int end = -1;
        String indent = "        ";
        while (last.find()) {
            end = last.end();
            indent = last.group(1);
        }
        if (end < 0) {
            return json.substring(0, body[0]) + newline + indent + "\"" + name + "\": \"" + constraint + "\"" + newline + "    "
                    + json.substring(body[1]);
        }
        int at = body[0] + end;
        return json.substring(0, at) + "," + newline + indent + "\"" + name + "\": \"" + constraint + "\"" + json.substring(at);
    }

    /** Removes a package from both sections, with the comma that went with it. */
    public static String remove(String json, String name) {
        String out = json;
        for (String section : new String[] {"require", "require-dev"}) {
            int[] body = section(out, section);
            if (body == null) {
                continue;
            }
            Matcher m = Pattern.compile("(?m)^[ \\t]*\"" + Pattern.quote(name) + "\"\\s*:\\s*\"[^\"]*\"[ \\t]*(,?)[ \\t]*\\R?")
                    .matcher(out).region(body[0], body[1]);
            if (m.find()) {
                boolean wasLast = m.group(1).isEmpty();
                out = out.substring(0, m.start()) + out.substring(m.end());
                if (wasLast) {
                    // The entry before it is the last now and must not end with a comma.
                    out = out.substring(0, m.start()).replaceFirst(",(\\s*)$", "$1") + out.substring(m.start());
                }
            }
        }
        return out;
    }

    /**
     * Sets {@code config.platform.php}, where a project pins the PHP that dependencies are resolved for; a file
     * without the pin is unchanged, because adding one would change how the project resolves.
     */
    public static String setPlatformPhp(String json, String version) {
        Matcher m = Pattern.compile("(\"platform\"\\s*:\\s*\\{[^}]*?\"php\"\\s*:\\s*\")[^\"]*(\")").matcher(json);
        return m.find() ? json.substring(0, m.end(1)) + version + json.substring(m.start(2)) : json;
    }

    /** Group 2 is the constraint of the entry for {@code name}. */
    private static Pattern entry(String name) {
        return Pattern.compile("(\"" + Pattern.quote(name) + "\"\\s*:\\s*\")([^\"]*)\"");
    }

    /** The start and end of the body of a top-level object such as "require": inside its braces. Null if absent. */
    static int[] section(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\\{").matcher(json);
        while (m.find()) {
            // Top level: exactly one brace is open before it.
            int depth = 0;
            boolean inString = false;
            for (int i = 0; i < m.start(); i++) {
                char c = json.charAt(i);
                if (inString && c == '\\') {
                    i++; // the character after a backslash is part of the string, a quote included ("App\\")
                } else if (c == '"') {
                    inString = !inString;
                } else if (!inString && c == '{') {
                    depth++;
                } else if (!inString && c == '}') {
                    depth--;
                }
            }
            if (depth != 1) {
                continue;
            }
            int close = json.indexOf('}', m.end());
            return close < 0 ? null : new int[] {m.end(), close};
        }
        return null;
    }
}
