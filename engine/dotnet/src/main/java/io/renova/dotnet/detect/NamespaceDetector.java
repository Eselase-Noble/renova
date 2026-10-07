package io.renova.dotnet.detect;

import io.renova.core.model.Finding;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.dotnet.Sources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code type: namespace, prefixes: [System.Web], exclude?: [System.Web.Http], include?: [globs]} — one
 * finding per line of C# or Visual Basic that brings one of the namespaces in: {@code using X;},
 * {@code using static X.Y;}, {@code using A = X.Y;}, {@code global using X;}, Visual Basic's
 * {@code Imports X}, and Razor's {@code @using X}. A prefix matches the namespace and everything under it.
 */
public final class NamespaceDetector implements DetectorFactory {

    private static final Pattern USING = Pattern.compile(
            "^\\s*(?:@|global\\s+)?(?:using|Imports)\\s+(?:static\\s+)?(?:\\w+\\s*=\\s*)?([A-Za-z_][\\w.]*)\\s*;?\\s*(?://.*|'.*)?$");

    @Override
    public String type() {
        return "namespace";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        List<String> prefixes = params.requiredStrings("prefixes");
        List<String> exclude = params.strings("exclude");
        List<String> include = params.strings("include").isEmpty() ? Sources.CODE : params.strings("include");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Path file : Sources.files(ctx, include)) {
                List<String> lines = ctx.lines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String used = namespace(lines.get(i));
                    if (used != null && under(used, prefixes) && !under(used, exclude)) {
                        findings.add(ctx.finding(rule, file, i + 1, used));
                    }
                }
            }
            return findings;
        };
    }

    /** The namespace a line brings in, or null. */
    public static String namespace(String line) {
        Matcher m = USING.matcher(line);
        return m.matches() ? m.group(1) : null;
    }

    static boolean under(String namespace, List<String> prefixes) {
        return prefixes.stream().anyMatch(p -> namespace.equals(p) || namespace.startsWith(p + "."));
    }
}
