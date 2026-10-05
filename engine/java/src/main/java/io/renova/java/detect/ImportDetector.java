package io.renova.java.detect;

import io.renova.core.model.Finding;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code type: import, prefixes: [javax.servlet], exclude?: [javax.annotation.processing], include?: [glob]}
 * — one finding per import statement (including JSP {@code page import}) of a matching package.
 */
public final class ImportDetector implements DetectorFactory {

    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;");
    private static final Pattern JSP_IMPORT = Pattern.compile("import\\s*=\\s*\"([^\"]+)\"");

    @Override
    public String type() {
        return "import";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        List<String> prefixes = params.requiredStrings("prefixes");
        List<String> exclude = params.strings("exclude");
        List<String> include = params.strings("include").isEmpty() ? List.of("**/*.java") : params.strings("include");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Path file : ctx.files(include)) {
                boolean jsp = !file.toString().endsWith(".java");
                List<String> lines = ctx.lines(file);
                for (int i = 0; i < lines.size(); i++) {
                    for (String imported : imports(lines.get(i), jsp)) {
                        if (matchesAny(imported, prefixes) && !matchesAny(imported, exclude)) {
                            findings.add(ctx.finding(rule, file, i + 1, imported));
                        }
                    }
                }
            }
            return findings;
        };
    }

    static List<String> imports(String line, boolean jsp) {
        if (!jsp) {
            Matcher m = JAVA_IMPORT.matcher(line);
            return m.find() ? List.of(m.group(1)) : List.of();
        }
        Matcher m = JSP_IMPORT.matcher(line);
        List<String> result = new ArrayList<>();
        while (m.find()) {
            for (String part : m.group(1).split(",")) {
                result.add(part.strip());
            }
        }
        return result;
    }

    /** Package-boundary match: "javax.servlet" matches "javax.servlet.http.X" but not "javax.servletx". */
    static boolean matchesAny(String imported, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (imported.equals(prefix) || imported.startsWith(prefix.endsWith(".") ? prefix : prefix + ".")) {
                return true;
            }
            if (prefix.endsWith("*") && imported.startsWith(prefix.substring(0, prefix.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
