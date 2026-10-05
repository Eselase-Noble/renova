package io.renova.java.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.scan.ScanContext;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.java.PomReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * {@code type: importWithoutDependency} — finds packages that a Maven module's main sources import
 * but that none of its build files declares. Typical after a namespace migration: Java 8 supplied
 * {@code javax.annotation}, but nothing supplies {@code jakarta.annotation} until it is declared.
 *
 * <pre>
 * detect:
 *   type: importWithoutDependency
 *   providedByAny: ["jakarta.platform:*"]            # umbrella artifacts that supply every package
 *   provides:
 *     - { package: jakarta.annotation, dependency: "jakarta.annotation:jakarta.annotation-api:2.1.1", scope: provided }
 * </pre>
 *
 * A package counts as declared when the module's build file, or a pom.xml in a parent directory
 * within the project, declares the artifact (or an umbrella) as a real dependency. Each build file
 * of the module (pom.xml and variants such as pom.jboss.xml) is checked separately. Findings carry
 * the coordinates in {@link Finding#data()} for the {@code addDependency} fix.
 */
public final class ImportDependencyDetector implements DetectorFactory {

    private record Mapping(String pkg, String groupId, String artifactId, String version, String scope) {
        String ga() {
            return groupId + ":" + artifactId;
        }
    }

    @Override
    public String type() {
        return "importWithoutDependency";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        List<Mapping> mappings = new ArrayList<>();
        for (Map<String, Object> m : params.maps("provides")) {
            String[] gav = String.valueOf(m.get("dependency")).split(":");
            if (m.get("package") == null || gav.length != 3) {
                throw new IllegalArgumentException("Rule '" + rule.id()
                        + "': each provides entry needs package and dependency (groupId:artifactId:version)");
            }
            mappings.add(new Mapping(String.valueOf(m.get("package")), gav[0], gav[1], gav[2],
                    m.get("scope") == null ? null : String.valueOf(m.get("scope"))));
        }
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("Rule '" + rule.id() + "': detect.provides is required");
        }
        // Longest package first, so jakarta.servlet.jsp wins over jakarta.servlet.
        mappings.sort((a, b) -> b.pkg().length() - a.pkg().length());
        List<Pattern> umbrellas = params.strings("providedByAny").stream().map(DependencyDetector::glob).toList();

        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Module module : ctx.model().modules()) {
                if (!"maven".equals(module.fact("buildTool"))) {
                    continue;
                }
                String dir = module.path().equals(".") ? "" : module.path() + "/";
                Map<Mapping, Set<String>> used = usedMappings(ctx, dir, mappings);
                if (used.isEmpty()) {
                    continue;
                }
                Set<String> inherited = ancestorDeclarations(ctx, module.path());
                for (Path buildFile : ctx.files(dir + "pom*.xml")) {
                    if (buildFile.getParent() != null && !(buildFile.getParent().toString().replace('\\', '/') + "/").equals(dir)) {
                        continue;
                    }
                    Set<String> declared = new TreeSet<>(inherited);
                    declared.addAll(declarations(ctx, buildFile));
                    boolean umbrella = declared.stream().anyMatch(ga -> umbrellas.stream().anyMatch(p -> p.matcher(ga).matches()));
                    if (umbrella) {
                        continue;
                    }
                    used.forEach((mapping, files) -> {
                        if (declared.contains(mapping.ga())) {
                            return;
                        }
                        Map<String, String> data = new LinkedHashMap<>();
                        data.put("groupId", mapping.groupId());
                        data.put("artifactId", mapping.artifactId());
                        data.put("version", mapping.version());
                        if (mapping.scope() != null) {
                            data.put("scope", mapping.scope());
                        }
                        String example = files.iterator().next();
                        findings.add(ctx.finding(rule, ScanContext.toProjectPath(buildFile),
                                DependencyDetector.lineOf(ctx, buildFile, "<dependencies>"),
                                mapping.pkg() + " is imported in " + files.size() + " file(s), e.g. "
                                        + example.substring(example.lastIndexOf('/') + 1) + ", but "
                                        + mapping.ga() + " is not declared", data));
                    });
                }
            }
            return findings;
        };
    }

    /** For each mapping imported by the module's main sources, the files importing it. */
    private static Map<Mapping, Set<String>> usedMappings(ScanContext ctx, String dir, List<Mapping> mappings) {
        Map<Mapping, Set<String>> used = new LinkedHashMap<>();
        for (Path source : ctx.files(dir + "src/main/java/**/*.java")) {
            for (String line : ctx.lines(source)) {
                for (String imported : ImportDetector.imports(line, false)) {
                    for (Mapping m : mappings) {
                        if (ImportDetector.matchesAny(imported, List.of(m.pkg()))) {
                            used.computeIfAbsent(m, k -> new TreeSet<>()).add(ScanContext.toProjectPath(source));
                            break;
                        }
                    }
                }
            }
        }
        return used;
    }

    /** Real (not managed) dependencies declared by pom.xml files in directories above the module. */
    private static Set<String> ancestorDeclarations(ScanContext ctx, String modulePath) {
        Set<String> declared = new TreeSet<>();
        if (modulePath.equals(".")) {
            return declared;
        }
        String path = modulePath;
        while (true) {
            int slash = path.lastIndexOf('/');
            path = slash < 0 ? "" : path.substring(0, slash);
            Path pom = Path.of(path.isEmpty() ? "pom.xml" : path + "/pom.xml");
            if (ctx.files(ScanContext.toProjectPath(pom)).contains(pom)) {
                declared.addAll(declarations(ctx, pom));
            }
            if (path.isEmpty()) {
                return declared;
            }
        }
    }

    private static Set<String> declarations(ScanContext ctx, Path pom) {
        Set<String> declared = new TreeSet<>();
        for (PomReader.Dependency d : DependencyDetector.readPom(ctx, pom).dependencies()) {
            if (!d.managed()) {
                declared.add(d.groupId() + ":" + d.artifactId());
            }
        }
        return declared;
    }
}
