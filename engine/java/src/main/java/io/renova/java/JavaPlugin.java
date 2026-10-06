package io.renova.java;

import io.renova.core.behaviour.BehaviourRunner;
import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;
import io.renova.core.scan.ScanContext;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.spi.EcosystemPlugin;
import io.renova.core.spi.Fixer;
import io.renova.core.spi.RelatedFile;
import io.renova.core.spi.Verifier;
import io.renova.java.behaviour.JavaBehaviourRunner;
import io.renova.java.detect.DependencyDetector;
import io.renova.java.detect.DuplicateDependencyDetector;
import io.renova.java.detect.ImportDependencyDetector;
import io.renova.java.detect.ImportDetector;
import io.renova.java.detect.GradleImportDependencyDetector;
import io.renova.java.detect.GradlePluginDetector;
import io.renova.java.detect.JavaVersionDetector;
import io.renova.java.detect.MavenParentDetector;
import io.renova.java.detect.MavenPluginDetector;
import io.renova.java.detect.PomPropertyDetector;
import io.renova.java.detect.UnversionedDependencyDetector;
import io.renova.java.fix.GradleBuildFixer;
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

    /** Java code that is not laid out as any project Renova can read. */
    @Override
    public Optional<String> unsupportedReason(Path root) {
        if (supports(root)) {
            return Optional.empty();
        }
        boolean ant = Files.isRegularFile(root.resolve("build.xml"));
        boolean sources;
        try (java.util.stream.Stream<Path> files = Files.find(root, 6, (p, attrs) -> attrs.isRegularFile() && p.toString().endsWith(".java"))) {
            sources = files.findAny().isPresent();
        } catch (IOException | java.io.UncheckedIOException e) {
            sources = false;
        }
        if (!ant && !sources) {
            return Optional.empty();
        }
        return Optional.of((ant ? "This is an Ant project (build.xml)" : "This Java project has no build file")
                + ", and Renova did not find its sources: it looks at the folders the build compiles, then at src, "
                + "src/main/java, src/java, source and JavaSource. Point Renova at the folder that holds the sources' own "
                + "project, or add a pom.xml or build.gradle that builds them, then assess the project again.");
    }

    @Override
    public List<String> projectMarkers() {
        return List.of("pom.xml", "build.gradle", "build.gradle.kts", "build.xml");
    }

    @Override
    public boolean supports(Path root) {
        try {
            // Without a Maven or Gradle build, an Ant or IDE project is given one when it is migrated.
            return !buildFiles(root).isEmpty() || io.renova.java.build.LegacyLayout.read(root).isPresent();
        } catch (IOException | java.io.UncheckedIOException e) {
            return false;
        }
    }

    @Override
    public ProjectModel model(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        List<Module> modules = new ArrayList<>();
        Set<String> buildTools = new TreeSet<>();
        Set<String> javaVersions = new TreeSet<>();
        List<Path> buildFiles = buildFiles(base);
        if (buildFiles.isEmpty()) {
            legacyModule(base).ifPresent(module -> {
                modules.add(module);
                buildTools.add(module.fact("buildTool").toString());
                if (module.fact("javaVersion") != null) {
                    javaVersions.add(module.fact("javaVersion").toString());
                }
            });
        }
        for (Path buildFile : buildFiles) {
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

    /**
     * An Ant or IDE project as one module. Its libraries are the jars it carries; those that name their own
     * coordinates are listed as dependencies, so rules about libraries apply before a build file exists.
     */
    private static Optional<Module> legacyModule(Path base) throws IOException {
        Optional<io.renova.java.build.LegacyLayout> found = io.renova.java.build.LegacyLayout.read(base);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        io.renova.java.build.LegacyLayout layout = found.get();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("buildTool", layout.ant() ? "ant" : "none");
        facts.put("packaging", layout.webApplication() ? "war" : "jar");
        // A build that names no level compiles for whatever JDK ran it; such projects predate Java 9.
        facts.put("javaVersion", layout.javaVersion() == null ? "8" : layout.javaVersion());
        facts.put("dependencies", layout.jars().stream()
                .map(jar -> io.renova.java.build.JarCoordinates.embedded(base.resolve(jar)))
                .flatMap(Optional::stream).map(io.renova.java.build.JarCoordinates.Gav::coordinates).distinct().toList());
        facts.put("libraries", layout.jars().size());
        facts.put("generatedBuild", "A Maven build (pom.xml, standard layout) is generated in the migrated copy");
        if (layout.webApplication() && Files.isRegularFile(base.resolve(layout.webRoot()).resolve("WEB-INF/web.xml"))) {
            facts.put("servletSpec", "unknown");
        }
        return Optional.of(new Module(layout.name(), ".", layout.ant() ? "build.xml" : layout.sources().getFirst(), facts));
    }

    @Override
    public Optional<io.renova.core.engine.StageResult> prepare(Path workspace, Map<String, String> options) throws Exception {
        if (!buildFiles(workspace).isEmpty()) {
            return Optional.empty();
        }
        return io.renova.java.build.Mavenizer.apply(workspace, !"true".equals(options.get("maven.offline")));
    }

    @Override
    public List<DetectorFactory> detectors() {
        return List.of(new ImportDetector(), new DependencyDetector(), new JavaVersionDetector(),
                new MavenPluginDetector(), new PomPropertyDetector(), new ImportDependencyDetector(),
                new UnversionedDependencyDetector(), new DuplicateDependencyDetector(), new MavenParentDetector(), new GradlePluginDetector(), new GradleImportDependencyDetector());
    }

    @Override
    public List<Fixer> fixers() {
        return List.of(new OpenRewriteFixer(), new MavenPomFixer(), new GradleBuildFixer());
    }

    @Override
    public Optional<Verifier> verifier() {
        return Optional.of(new MavenVerifier());
    }

    /**
     * A source file's related file is the build file of the module that owns it (editable, so a
     * missing dependency can be added in the same edit). A module's build file is related to its
     * parent's build file (editable: versions are often managed there). A variant such as
     * pom.jboss.xml gets the module's pom.xml as a read-only reference to align with. Files under
     * WEB-INF also get the module's web.xml (editable), where container settings live.
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
        RelatedFile buildFile = new RelatedFile(owner.buildFile(), true, "build file of module " + owner.name());
        int webInf = file.indexOf("/WEB-INF/");
        String webXml = webInf < 0 ? null : file.substring(0, webInf) + "/WEB-INF/web.xml";
        if (webXml != null && !webXml.equals(file)) {
            // Container settings referenced from Spring or other WEB-INF descriptors often move to
            // web.xml (for example multipart limits), so offer it alongside the build file.
            return List.of(new RelatedFile(webXml, true, "web application descriptor of module " + owner.name()), buildFile);
        }
        return List.of(buildFile);
    }

    @Override
    public Optional<BehaviourRunner> behaviourRunner() {
        return Optional.of(new JavaBehaviourRunner());
    }

    @Override
    public boolean isTestFile(String file) {
        return file.startsWith("src/test/") || file.contains("/src/test/");
    }

    @Override
    public List<RelatedFile> referencedFiles(ProjectModel model, Path root, String file) {
        return JavaReferences.find(model, root, file);
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
        return List.of("playbooks/java/java8-to-21-jakarta-ee10.yaml", "playbooks/java/java-to-21-jakarta-ee11-spring7.yaml",
                "playbooks/java/spring-boot-3.yaml", "playbooks/java/spring-boot-4.yaml", "playbooks/java/micronaut-4.yaml", "playbooks/java/quarkus-3.yaml",
                "playbooks/java/java-to-17.yaml", "playbooks/java/java-to-21.yaml", "playbooks/java/java-to-25.yaml",
                // Add-ons: optional, combined with a target (java-to-21+junit5).
                "playbooks/java/addons/junit5.yaml", "playbooks/java/addons/mockito5.yaml", "playbooks/java/addons/log4j2.yaml",
                "playbooks/java/addons/commons-lang3.yaml", "playbooks/java/addons/commons-collections4.yaml",
                "playbooks/java/addons/httpclient5.yaml", "playbooks/java/addons/struts7.yaml");
    }

    /**
     * The path that fits what the project is: a Spring Boot 2 application goes to Spring Boot 3, and one already
     * on 3 to Spring Boot 4; one that uses
     * Java EE (javax) APIs or Spring 5 goes to Jakarta EE 10 and Spring 6; anything else only needs its Java
     * level raised, to the current long-term-support release most projects settle on. The other playbooks stay
     * available for whoever wants a different target.
     */
    @Override
    public String recommendedPlaybook(Path root, List<String> candidates) {
        boolean boot = false;
        boolean boot3 = false;
        boolean javaEe = false;
        boolean struts = false;
        boolean micronaut = false;
        boolean quarkus = false;
        try {
            for (Path buildFile : buildFiles(root)) {
                if (!buildFile.getFileName().toString().startsWith("pom")) {
                    String text = Files.readString(buildFile);
                    boot |= text.contains("org.springframework.boot");
                    micronaut |= text.contains("io.micronaut.application") || text.contains("io.micronaut.library");
                    quarkus |= text.contains("io.quarkus");
                    Matcher bootPlugin = GradlePluginDetector.declaration("org.springframework.boot").matcher(text);
                    // Already on Spring Boot 3: the next step is 4.
                    boot3 |= bootPlugin.find() && bootPlugin.group(1).matches("\\d.*")
                            && !io.renova.core.util.Versions.isBelow(bootPlugin.group(1), "3");
                    javaEe |= text.contains("javax.") || text.contains("org.springframework:spring-");
                    continue;
                }
                PomReader.Pom pom = PomReader.read(buildFile);
                // Quarkus comes in through its BOM and build plugin, which a dependency list does not show.
                quarkus |= Files.readString(buildFile).contains("quarkus-maven-plugin");
                micronaut |= pom.parent() != null && pom.parent().startsWith("io.micronaut");
                if (pom.parent() != null && pom.parent().startsWith("org.springframework.boot:")) {
                    boot = true;
                    // Already on Spring Boot 3: the next step is 4.
                    boot3 |= pom.parentVersion() != null && !pom.parentVersion().contains("${")
                            && !io.renova.core.util.Versions.isBelow(pom.parentVersion(), "3");
                }
                for (PomReader.Dependency d : pom.dependencies()) {
                    boot |= d.groupId().equals("org.springframework.boot");
                    struts |= d.groupId().equals("org.apache.struts");
                    javaEe |= d.groupId().startsWith("javax") || d.groupId().equals("jstl")
                            || (d.groupId().equals("org.springframework") && d.version() != null && !d.version().contains("${")
                            && io.renova.core.util.Versions.isBelow(d.version(), "6"));
                }
                javaEe |= pom.packaging().equals("war") && !boot;
            }
            Optional<Module> legacy = buildFiles(root).isEmpty() ? legacyModule(root) : Optional.empty();
            if (legacy.isPresent()) {
                javaEe |= "war".equals(legacy.get().fact("packaging"));
                struts |= legacy.get().fact("dependencies").toString().contains("org.apache.struts:struts2");
            }
            // Java EE APIs can come from a parent or the server without being declared here: look at the code too.
            javaEe = javaEe || (!boot && importsJavaEe(root));
        } catch (IOException | RuntimeException e) {
            // An unreadable build file: fall through to the plain Java path, which the analysis will report on.
        }
        String wanted = micronaut ? "micronaut-4" : quarkus ? "quarkus-3" : boot3 ? "spring-boot-4" : boot ? "spring-boot-3" : javaEe ? "java8-to-21-jakarta-ee10" : "java-to-21";
        if (!candidates.contains(wanted)) {
            return candidates.getFirst();
        }
        // A framework that must move with the target comes along as its add-on.
        return struts && wanted.contains("jakarta") ? wanted + "+struts7" : wanted;
    }

    private static final java.util.regex.Pattern JAVA_EE_IMPORT = java.util.regex.Pattern.compile(
            "^import\\s+javax\\.(servlet|persistence|ejb|ws\\.rs|faces|jms|enterprise|validation|xml\\.bind|xml\\.ws)\\.");
    private static final int SOURCES_TO_SAMPLE = 1500;

    /** Whether the code uses Java EE (javax) APIs, from the imports of a bounded number of source files. */
    private static boolean importsJavaEe(Path root) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.find(root, 12, (p, attrs) -> attrs.isRegularFile()
                && p.toString().endsWith(".java") && !p.toString().contains("/target/") && !p.toString().contains("/build/"))) {
            return files.limit(SOURCES_TO_SAMPLE).anyMatch(file -> {
                try (java.util.stream.Stream<String> lines = Files.lines(file)) {
                    // Imports sit at the top of a file; the class body is not read.
                    return lines.limit(80).anyMatch(line -> JAVA_EE_IMPORT.matcher(line).find());
                } catch (IOException | java.io.UncheckedIOException e) {
                    return false;
                }
            });
        }
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
