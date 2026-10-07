package io.renova.dotnet.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.dotnet.Sources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code type: namespaceWithoutPackage, prefixes: [System.Configuration], packages: [System.Configuration.ConfigurationManager],
 * exclude?: [...], exceptKinds?: [winforms, wpf]} (projects of those kinds are skipped) — one finding per project whose own code uses one of the namespaces while
 * the project has none of the packages. .NET Framework had these namespaces built in; modern .NET ships them
 * as NuGet packages, so the code is right and only the project file is missing a line.
 */
public final class NamespacePackageDetector implements DetectorFactory {

    private static final java.util.regex.Pattern PROJECT_IMPORT = java.util.regex.Pattern.compile("<Import\\s+Include=\"([\\w.]+)\"");

    @Override
    public String type() {
        return "namespaceWithoutPackage";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        List<String> prefixes = params.requiredStrings("prefixes");
        List<String> exclude = params.strings("exclude");
        List<String> packages = params.requiredStrings("packages").stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
        List<String> exceptKinds = params.strings("exceptKinds");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            List<Module> modules = ctx.model().modules();
            for (Module module : modules) {
                if (exceptKinds.contains(String.valueOf(module.fact("kind")))) {
                    continue;
                }
                if (module.fact("packages") instanceof List<?> used && used.stream()
                        .anyMatch(p -> packages.contains(p.toString().split(":")[0].toLowerCase(Locale.ROOT)))) {
                    continue;
                }
                String example = null;
                int files = 0;
                // Visual Basic may import a namespace for the whole project, in the project file.
                for (String line : ctx.lines(Path.of(module.buildFile()))) {
                    java.util.regex.Matcher imported = PROJECT_IMPORT.matcher(line);
                    if (imported.find() && NamespaceDetector.under(imported.group(1), prefixes)
                            && !NamespaceDetector.under(imported.group(1), exclude)) {
                        files++;
                        example = module.buildFile().substring(module.buildFile().lastIndexOf('/') + 1);
                        break;
                    }
                }
                for (Path source : Sources.files(ctx, Sources.CODE)) {
                    if (!owner(modules, source).equals(module)) {
                        continue;
                    }
                    boolean uses = ctx.lines(source).stream().map(NamespaceDetector::namespace)
                            .anyMatch(n -> n != null && NamespaceDetector.under(n, prefixes) && !NamespaceDetector.under(n, exclude));
                    if (uses) {
                        files++;
                        example = example == null ? source.getFileName().toString() : example;
                    }
                }
                if (files > 0) {
                    findings.add(ctx.finding(rule, module.buildFile(), 0, String.join(", ", prefixes) + " is used in " + files
                            + " file(s), e.g. " + example + ", and no package supplies it"));
                }
            }
            return findings;
        };
    }

    /** The project a source file belongs to: the one whose folder is the nearest above it. */
    static Module owner(List<Module> modules, Path source) {
        Module best = null;
        for (Module m : modules) {
            boolean inside = m.path().equals(".") || source.startsWith(Path.of(m.path()));
            if (inside && (best == null || m.path().length() > best.path().length() || best.path().equals("."))) {
                best = best != null && !best.path().equals(".") && m.path().equals(".") ? best : m;
            }
        }
        return best == null ? modules.getFirst() : best;
    }
}
