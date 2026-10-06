package io.renova.java.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.scan.ScanContext;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code type: gradleImportWithoutDependency, imports: [jakarta.xml.bind], dependency: "jakarta.xml.bind:jakarta.xml.bind-api",
 * configuration?: implementation, unless?: ["jakarta.platform:jakarta.jakartaee-api"]} — one finding per Gradle
 * build file whose module's main sources import one of the packages while the build file names neither the
 * dependency nor one of the {@code unless} artifacts. The Gradle counterpart of {@code importWithoutDependency}:
 * after a namespace migration nothing supplies the new package until the build declares it. A dependency
 * written without a version is for builds where a platform (such as Spring Boot's) manages it.
 */
public final class GradleImportDependencyDetector implements DetectorFactory {

    @Override
    public String type() {
        return "gradleImportWithoutDependency";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        List<String> imports = params.requiredStrings("imports");
        String dependency = params.string("dependency");
        String[] parts = dependency.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Rule '" + rule.id() + "': dependency must be groupId:artifactId[:version]");
        }
        String ga = parts[0] + ":" + parts[1];
        List<String> unless = params.strings("unless");
        String configuration = params.optString("configuration").orElse("implementation");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Module module : ctx.model().modules()) {
                if (!"gradle".equals(module.fact("buildTool"))) {
                    continue;
                }
                Path buildFile = Path.of(module.buildFile());
                String build = String.join("\n", ctx.lines(buildFile));
                if (build.contains(ga) || unless.stream().anyMatch(build::contains)) {
                    continue;
                }
                String dir = module.path().equals(".") ? "" : module.path() + "/";
                String example = null;
                int files = 0;
                for (Path source : ctx.files(dir + "src/main/java/**/*.java")) {
                    boolean uses = ctx.lines(source).stream().flatMap(line -> ImportDetector.imports(line, false).stream())
                            .anyMatch(imported -> ImportDetector.matchesAny(imported, imports));
                    if (uses) {
                        files++;
                        example = example == null ? source.getFileName().toString() : example;
                    }
                }
                if (files == 0) {
                    continue;
                }
                Map<String, String> data = new LinkedHashMap<>();
                data.put("dependency", dependency);
                data.put("configuration", configuration);
                findings.add(ctx.finding(rule, ScanContext.toProjectPath(buildFile), DependencyDetector.lineOf(ctx, buildFile, "dependencies"),
                        String.join(", ", imports) + " is imported in " + files + " file(s), e.g. " + example + ", but " + ga
                                + " is not declared", data));
            }
            return findings;
        };
    }
}
