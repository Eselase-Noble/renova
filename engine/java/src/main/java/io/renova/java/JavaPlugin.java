package io.renova.java;

import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;
import io.renova.core.scan.ScanContext;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.spi.EcosystemPlugin;
import io.renova.core.spi.Fixer;
import io.renova.core.spi.RelatedFile;
import io.renova.core.spi.Verifier;
import io.renova.java.detect.DependencyDetector;
import io.renova.java.detect.ImportDependencyDetector;
import io.renova.java.detect.ImportDetector;
import io.renova.java.detect.JavaVersionDetector;
import io.renova.java.detect.MavenPluginDetector;
import io.renova.java.detect.PomPropertyDetector;
import io.renova.java.fix.MavenPomFixer;
import io.renova.java.fix.MavenVerifier;
import io.renova.java.fix.OpenRewriteFixer;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class JavaPlugin implements EcosystemPlugin {

    public static final String ID = "java";
    private static final Pattern GRADLE_JAVA = Pattern.compile("(?:sourceCompatibility|targetCompatibility|languageVersion)\\s*=?\\s*(?:JavaVersion\\.VERSION_|JavaLanguageVersion\\.of\\()?['\"]?([0-9._]+)");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Java (Maven/Gradle)";
    }

    @Override
    public boolean supports(Path root) {
        try {
            return !buildFiles(root).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public ProjectModel model(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        List<Module> modules = new ArrayList<>();
        Set<String> buildTools = new TreeSet<>();
        Set<String> javaVersions = new TreeSet<>();
        for (Path buildFile : buildFiles(base)) {
            Path dir = buildFile.getParent();
            String relDir = base.relativize(dir).toString().replace('\\', '/');
            String relFile = base.relativize(buildFile).toString().replace('\\', '/');
            Map<String, Object> facts = new LinkedHashMap<>();
            String name;
            if (buildFile.getFileName().toString().equals("pom.xml")) {
                buildTools.add("maven");
                PomReader.Pom pom = PomReader.read(buildFile);
                name = pom.artifactId();
                facts.put("buildTool", "maven");
                facts.put("packaging", pom.packaging());
                putIfNotNull(facts, "javaVersion", pom.javaVersion());
                facts.put("dependencies", pom.dependencies().stream().map(PomReader.Dependency::coordinates).toList());
                if (!pom.modules().isEmpty()) {
                    facts.put("modules", pom.modules());
                }
                List<String> variants = variantPoms(dir);
                if (!variants.isEmpty()) {
                    facts.put("buildVariants", variants);
                }
            } else {
                buildTools.add("gradle");
                name = dir.getFileName().toString();
                facts.put("buildTool", "gradle");
                Matcher m = GRADLE_JAVA.matcher(Files.readString(buildFile));
                if (m.find()) {
                    String v = m.group(1).replace('_', '.');
                    facts.put("javaVersion", v.startsWith("1.") ? v.substring(2) : v);
                }
            }
            webFacts(dir, facts);
            Object jv = facts.get("javaVersion");
            if (jv != null) {
                javaVersions.add(jv.toString());
            }
            modules.add(new Module(name, relDir.isEmpty() ? "." : relDir, relFile, facts));
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("buildTools", List.copyOf(buildTools));
        facts.put("javaVersions", List.copyOf(javaVersions));
        facts.put("modules", modules.size());
        facts.put("containers", containers(modules));
        facts.put("hasWrapper", Files.exists(base.resolve("mvnw")) || Files.exists(base.resolve("gradlew")));
        return new ProjectModel(base, ID, modules, facts);
    }

    @Override
    public List<DetectorFactory> detectors() {
        return List.of(new ImportDetector(), new DependencyDetector(), new JavaVersionDetector(),
                new MavenPluginDetector(), new PomPropertyDetector(), new ImportDependencyDetector());
    }

    @Override
    public List<Fixer> fixers() {
        return List.of(new OpenRewriteFixer(), new MavenPomFixer());
    }

    @Override
    public Optional<Verifier> verifier() {
        return Optional.of(new MavenVerifier());
    }

    /**
     * A source file's related file is the build file of the module that owns it (editable, so a
     * missing dependency can be added in the same edit). A module's build file is related to its
     * parent's build file (editable: versions are often managed there). A variant such as
     * pom.jboss.xml gets the module's pom.xml as a read-only reference to align with.
     */
    @Override
    public List<RelatedFile> relatedFiles(ProjectModel model, String file) {
        Module owner = owner(model, file, false);
        if (owner == null) {
            return List.of();
        }
        if (owner.buildFile().equals(file)) {
            Module parent = owner.path().equals(".") ? null : owner(model, owner.path(), true);
            return parent == null || !"maven".equals(parent.fact("buildTool")) ? List.of()
                    : List.of(new RelatedFile(parent.buildFile(), true,
                            "parent build file of module " + owner.name() + " (dependency and plugin versions may be managed here)"));
        }
        String dir = owner.path().equals(".") ? "" : owner.path() + "/";
        String name = file.substring(file.lastIndexOf('/') + 1);
        boolean variantPom = file.equals(dir + name) && name.startsWith("pom.") && name.endsWith(".xml");
        if (variantPom) {
            return List.of(new RelatedFile(owner.buildFile(), false,
                    "main build file of module " + owner.name() + ", already migrated; align the variant with it"));
        }
        return List.of(new RelatedFile(owner.buildFile(), true, "build file of module " + owner.name()));
    }

    /** The module with the longest path containing {@code file}; with {@code strict}, excluding a module at exactly that path. */
    private static Module owner(ProjectModel model, String file, boolean strict) {
        Module owner = null;
        for (Module m : model.modules()) {
            boolean contains = m.path().equals(".") ? !(strict && file.equals("."))
                    : file.startsWith(m.path() + "/") || (!strict && file.equals(m.path()));
            if (contains && (owner == null || m.path().length() > owner.path().length())) {
                owner = m;
            }
        }
        return owner;
    }

    @Override
    public List<String> bundledPlaybooks() {
        return List.of("playbooks/java/java8-to-21-jakarta-ee10.yaml");
    }

    /** pom.xml, build.gradle and build.gradle.kts files outside build output, sorted by path. */
    static List<Path> buildFiles(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return !dir.equals(root) && (ScanContext.IGNORED_DIRS.contains(name) || name.equals("src"))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                    result.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(null);
        return result;
    }

    private static List<String> variantPoms(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> n.startsWith("pom.") && n.endsWith(".xml") && !n.equals("pom.xml"))
                    .sorted()
                    .toList();
        }
    }

    private static void webFacts(Path moduleDir, Map<String, Object> facts) throws IOException {
        Path webInf = moduleDir.resolve("src/main/webapp/WEB-INF");
        Path webXml = webInf.resolve("web.xml");
        if (Files.isRegularFile(webXml)) {
            String content = Files.readString(webXml, java.nio.charset.StandardCharsets.ISO_8859_1);
            Matcher version = Pattern.compile("<web-app[^>]*\\sversion=\"([^\"]+)\"").matcher(content);
            facts.put("servletSpec", version.find() ? version.group(1) : content.contains("web-app_2_3") ? "2.3" : "unknown");
        }
        Set<String> containers = new LinkedHashSet<>();
        if (Files.exists(webInf.resolve("jboss-web.xml")) || Files.exists(webInf.resolve("jboss-deployment-structure.xml"))
                || Files.exists(moduleDir.resolve("src/main/webapp/META-INF/jboss-deployment-structure.xml"))) {
            containers.add("jboss");
        }
        if (Files.exists(moduleDir.resolve("src/main/webapp/META-INF/context.xml"))) {
            containers.add("tomcat");
        }
        if (!containers.isEmpty()) {
            facts.put("containers", List.copyOf(containers));
        }
    }

    private static List<String> containers(List<Module> modules) {
        Set<String> all = new TreeSet<>();
        for (Module m : modules) {
            if (m.fact("containers") instanceof List<?> list) {
                list.forEach(c -> all.add(c.toString()));
            }
            if (m.fact("servletSpec") != null) {
                all.add("servlet");
            }
        }
        return List.copyOf(all);
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
