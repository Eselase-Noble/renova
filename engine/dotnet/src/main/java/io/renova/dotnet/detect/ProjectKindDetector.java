package io.renova.dotnet.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.dotnet.Sources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code type: projectKind, kinds: [aspnet, wpf], files?: ["Views/*.cshtml", "Web.config"]} — one finding per project
 * of one of the kinds. With {@code files}, also one per file of that project matching one of the patterns (which
 * are relative to the project's folder): everything a change to the whole project has to touch.
 */
public final class ProjectKindDetector implements DetectorFactory {

    @Override
    public String type() {
        return "projectKind";
    }

    @Override
    public Detector create(Rule rule) {
        List<String> kinds = rule.detectParams().requiredStrings("kinds");
        List<String> files = rule.detectParams().strings("files");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            List<Module> modules = ctx.model().modules();
            for (Module module : modules) {
                if (!kinds.contains(String.valueOf(module.fact("kind")))) {
                    continue;
                }
                findings.add(ctx.finding(rule, module.buildFile(), 0, module.fact("kind") + " project"));
                if (files.isEmpty()) {
                    continue;
                }
                String dir = module.path().equals(".") ? "" : module.path() + "/";
                // "**/x" also means x in the project's own folder, as it does at the root of a scan.
                List<String> globs = files.stream()
                        .flatMap(g -> g.startsWith("**/") ? java.util.stream.Stream.of(dir + g, dir + g.substring(3)) : java.util.stream.Stream.of(dir + g))
                        .toList();
                for (Path file : Sources.files(ctx, globs)) {
                    if (NamespacePackageDetector.owner(modules, file).equals(module)
                            && !io.renova.core.scan.ScanContext.toProjectPath(file).equals(module.buildFile())) {
                        findings.add(ctx.finding(rule, file, 0, "part of " + module.name()));
                    }
                }
            }
            return findings;
        };
    }
}
