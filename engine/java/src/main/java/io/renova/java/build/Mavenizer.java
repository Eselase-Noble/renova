package io.renova.java.build;

import io.renova.core.engine.StageResult;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Gives a Java project without a Maven or Gradle build (Ant, an IDE project, sources beside a folder of jars) a
 * Maven build in the standard layout, in the migration's copy. The sources move to src/main/java and
 * src/test/java, the web content to src/main/webapp, and each jar the project carried becomes a declared
 * dependency: the published library when the jar can be shown to be one, so that an upgrade can change its
 * version, and otherwise the same file in a repository folder inside the project.
 */
public final class Mavenizer {

    static final String MAIN = "src/main/java";
    static final String TEST = "src/test/java";
    static final String MAIN_RESOURCES = "src/main/resources";
    static final String TEST_RESOURCES = "src/test/resources";
    static final String WEBAPP = "src/main/webapp";
    /** Libraries the project carried that nobody published, kept as a Maven repository inside the project. */
    static final String LOCAL_REPOSITORY = "renova-libs";
    static final String LOCAL_GROUP = "local";

    /** APIs the server supplies: compiled against, never packaged. */
    private static final Pattern PROVIDED = Pattern.compile("(?i)(javax\\.)?(servlet|jsp|el|ejb|jms|jta|javaee|j2ee|annotation|persistence|transaction)"
            + "([-_.]?api)?|jakarta\\..*-api|jboss-.*_spec|geronimo-.*_spec|catalina|tomcat-.*|weblogic.*|websphere.*");
    private static final Pattern TEST_ONLY = Pattern.compile("(?i)junit.*|hamcrest.*|mockito.*|easymock.*|powermock.*|testng.*|assertj.*|"
            + "dbunit.*|jmock.*|spring-test|htmlunit.*|selenium.*|cactus.*|strutstestcase.*|xmlunit.*");

    private Mavenizer() {
    }

    record Dependency(JarCoordinates.Gav gav, String scope, String from, boolean published) {
    }

    /**
     * @param online whether Maven Central may be asked which library a jar is
     * @return the stage to report; empty when the project is not one this applies to
     */
    public static Optional<StageResult> apply(Path root, boolean online) throws IOException {
        Optional<LegacyLayout> found = LegacyLayout.read(root);
        if (found.isEmpty()) {
            List<String> modules = LegacyLayout.modules(root);
            return modules.isEmpty() ? Optional.empty() : Optional.of(applyModules(root, modules, online));
        }
        LegacyLayout layout = found.get();
        List<String> details = new ArrayList<>();

        List<Dependency> dependencies = dependencies(root, layout.jars(), !layout.tests().isEmpty(), root, new JarCoordinates(online), details);
        try {
            restructure(root, layout, details);
        } catch (FileAlreadyExistsException e) {
            return Optional.of(new StageResult("build", StageResult.Status.FAILED, "two source folders hold the same file: "
                    + e.getMessage(), details));
        }
        Files.writeString(root.resolve("pom.xml"), pom(layout, dependencies, Files.isDirectory(root.resolve(TEST)), null));
        long published = dependencies.stream().filter(Dependency::published).count();
        details.add("pom.xml written: " + (layout.webApplication() ? "war" : "jar") + ", Java "
                + (layout.javaVersion() == null ? "level not declared (set to 8)" : layout.javaVersion()));
        if (layout.ant()) {
            details.add("build.xml is kept for reference; it describes the old layout and no longer builds the project");
        }
        return Optional.of(new StageResult("build", StageResult.Status.APPLIED, "Maven build generated from "
                + (layout.ant() ? "the Ant build" : "the project's folders") + ": " + dependencies.size() + " librar"
                + (dependencies.size() == 1 ? "y" : "ies") + " (" + published + " identified as published, "
                + (dependencies.size() - published) + " kept as files)", details));
    }

    /**
     * A build of several projects: each becomes a Maven module in the standard layout, under a parent pom
     * that lists them and declares the libraries they shared from folders above them. A project whose build
     * names another's folder depends on that module.
     */
    private static StageResult applyModules(Path root, List<String> modules, boolean online) throws IOException {
        List<String> details = new ArrayList<>();
        JarCoordinates coordinates = new JarCoordinates(online);
        Map<String, LegacyLayout> layouts = new LinkedHashMap<>();
        for (String module : modules) {
            layouts.put(module, LegacyLayout.read(root.resolve(module)).orElseThrow());
        }
        // Two projects with one name would be one artifact: the folder's name is used for both instead.
        Map<String, String> artifactIds = new LinkedHashMap<>();
        for (String module : modules) {
            String id = artifactId(layouts.get(module).name());
            boolean taken = layouts.entrySet().stream().anyMatch(e -> !e.getKey().equals(module) && artifactId(e.getValue().name()).equals(id));
            artifactIds.put(module, taken ? artifactId(module.replace('/', '-')) : id);
        }
        String name = root.toAbsolutePath().normalize().getFileName().toString();
        String parentId = artifactId(name);
        if (artifactIds.containsValue(parentId)) {
            parentId = parentId + "-parent";
        }
        boolean anyTests = layouts.values().stream().anyMatch(l -> !l.tests().isEmpty());

        // Libraries above the projects are every project's: declared once, in the parent.
        List<String> shared = LegacyLayout.jars(root).stream()
                .filter(jar -> modules.stream().noneMatch(m -> jar.startsWith(m + "/"))).toList();
        List<Dependency> sharedDependencies = dependencies(root, shared, anyTests, root, coordinates, details);
        int libraries = sharedDependencies.size();
        long published = sharedDependencies.stream().filter(Dependency::published).count();
        boolean files = sharedDependencies.stream().anyMatch(d -> !d.published());

        Map<String, List<Dependency>> own = new LinkedHashMap<>();
        for (String module : modules) {
            List<String> moduleDetails = new ArrayList<>();
            Path dir = root.resolve(module);
            List<Dependency> dependencies = new ArrayList<>(dependencies(dir, layouts.get(module).jars(), !layouts.get(module).tests().isEmpty(),
                    root, coordinates, moduleDetails));
            dependencies.removeIf(d -> sharedDependencies.stream().anyMatch(s -> s.gav().groupId().equals(d.gav().groupId())
                    && s.gav().artifactId().equals(d.gav().artifactId())));
            own.put(module, dependencies);
            libraries += dependencies.size();
            published += dependencies.stream().filter(Dependency::published).count();
            files |= dependencies.stream().anyMatch(d -> !d.published());
            try {
                restructure(dir, layouts.get(module), moduleDetails);
            } catch (FileAlreadyExistsException e) {
                moduleDetails.forEach(d -> details.add(module + ": " + d));
                return new StageResult("build", StageResult.Status.FAILED, "two source folders of " + module + " hold the same file: "
                        + e.getMessage(), details);
            }
            moduleDetails.forEach(d -> details.add(module + ": " + d));
        }
        for (String module : modules) {
            LegacyLayout layout = layouts.get(module);
            // A web application is not a library: what other projects take from it has to move to one by hand.
            List<String> uses = new ArrayList<>();
            for (String other : LegacyLayout.uses(root, module, modules)) {
                if (layouts.get(other).webApplication()) {
                    details.add(module + ": its build names " + other + ", a web application, which a Maven module cannot depend on; "
                            + "move the classes it needs to a module of their own");
                } else {
                    uses.add(artifactIds.get(other));
                    details.add(module + ": depends on " + other + " (its build names that folder)");
                }
            }
            String up = "../".repeat(module.split("/").length);
            Files.writeString(root.resolve(module).resolve("pom.xml"), pom(layout, own.get(module),
                    Files.isDirectory(root.resolve(module).resolve(TEST)), new InParent(artifactIds.get(module), parentId, up, uses, files)));
            details.add(module + "/pom.xml written: " + (layout.webApplication() ? "war" : "jar") + ", Java "
                    + (layout.javaVersion() == null ? "level not declared (set to 8)" : layout.javaVersion()));
        }
        Files.writeString(root.resolve("pom.xml"), parentPom(name, parentId, modules, sharedDependencies));
        details.add("pom.xml written: the parent of " + modules.size() + " module(s), " + String.join(", ", modules));
        details.add("the build.xml files are kept for reference; they describe the old layout and no longer build the project");
        return new StageResult("build", StageResult.Status.APPLIED, "Maven build generated from " + modules.size() + " Ant projects, as modules of "
                + "one parent: " + libraries + " librar" + (libraries == 1 ? "y" : "ies") + " (" + published + " identified as published, "
                + (libraries - published) + " kept as files)", details);
    }

    /**
     * A module of a generated multi-module build.
     *
     * @param up    the way from the module's folder to the parent's, "../" for a module directly below it
     * @param uses  artifactIds of the modules this one is compiled against
     * @param files whether the build keeps libraries as files, in the repository folder beside the parent pom
     */
    record InParent(String artifactId, String parentId, String up, List<String> uses, boolean files) {
    }

    /**
     * @param base       the folder the jar paths start in
     * @param repository the folder that gets the repository of libraries kept as files
     */
    private static List<Dependency> dependencies(Path base, List<String> jars, boolean hasTests, Path repository, JarCoordinates coordinates,
                                                 List<String> details) throws IOException {
        Path root = repository;
        Map<String, Dependency> byKey = new LinkedHashMap<>();
        for (String jar : jars) {
            Path file = base.resolve(jar);
            Optional<JarCoordinates.Gav> gav = coordinates.published(file);
            boolean published = gav.isPresent();
            if (!published) {
                String[] parts = JarCoordinates.nameAndVersion(file);
                gav = Optional.of(new JarCoordinates.Gav(LOCAL_GROUP, parts[0], parts[1]));
                Path folder = root.resolve(LOCAL_REPOSITORY).resolve(LOCAL_GROUP).resolve(parts[0]).resolve(parts[1]);
                Files.createDirectories(folder);
                Files.copy(file, folder.resolve(parts[0] + "-" + parts[1] + ".jar"), StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(folder.resolve(parts[0] + "-" + parts[1] + ".pom"), "<project>\n  <modelVersion>4.0.0</modelVersion>\n"
                        + "  <groupId>" + LOCAL_GROUP + "</groupId>\n  <artifactId>" + parts[0] + "</artifactId>\n  <version>" + parts[1]
                        + "</version>\n</project>\n");
            }
            JarCoordinates.Gav g = gav.get();
            String scope = PROVIDED.matcher(g.artifactId()).matches() ? "provided"
                    : hasTests && TEST_ONLY.matcher(g.artifactId()).matches() ? "test" : null;
            Dependency previous = byKey.putIfAbsent(g.groupId() + ":" + g.artifactId(), new Dependency(g, scope, jar, published));
            details.add(jar + " → " + g.coordinates() + (scope == null ? "" : " (" + scope + ")")
                    + (published ? "" : ", kept as a file in " + LOCAL_REPOSITORY)
                    + (previous == null ? "" : "; already declared from " + previous.from()));
            // The library is now a dependency; a copy left in WEB-INF/lib would be packaged beside the new version.
            Files.delete(file);
        }
        return List.copyOf(byKey.values());
    }

    private static void restructure(Path root, LegacyLayout layout, List<String> details) throws IOException {
        // Every folder is lifted out before any is put down: the new places are inside "src", which is often one
        // of the old ones. Tests first, so a test folder inside a source folder does not travel with the code.
        Path staging = Files.createTempDirectory(root, ".renova-move");
        Map<Path, String[]> lifted = new LinkedHashMap<>();
        int n = 0;
        for (String dir : layout.tests()) {
            lift(root, dir, TEST, staging.resolve("t" + n++), lifted);
        }
        for (String dir : layout.sources()) {
            lift(root, dir, MAIN, staging.resolve("s" + n++), lifted);
        }
        if (layout.webApplication()) {
            lift(root, layout.webRoot(), WEBAPP, staging.resolve("w"), lifted);
        }
        for (Map.Entry<Path, String[]> entry : lifted.entrySet()) {
            place(root, entry.getKey(), entry.getValue()[0], entry.getValue()[1], details);
        }
        deleteTree(staging);
        separateResources(root, MAIN, MAIN_RESOURCES, details);
        separateResources(root, TEST, TEST_RESOURCES, details);
    }

    private static void lift(Path root, String from, String to, Path staged, Map<Path, String[]> lifted) throws IOException {
        if (!from.equals(to) && Files.isDirectory(root.resolve(from))) {
            Files.move(root.resolve(from), staged);
            lifted.put(staged, new String[] {from, to});
        }
    }

    /** Puts a lifted folder's content into {@code to}, merging with what is there; the same file twice is an error. */
    private static void place(Path root, Path staged, String from, String to, List<String> details) throws IOException {
        Path target = root.resolve(to);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(staged)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            Path destination = target.resolve(staged.relativize(file));
            Files.createDirectories(destination.getParent());
            if (Files.exists(destination)) {
                throw new FileAlreadyExistsException(root.relativize(destination).toString());
            }
            Files.move(file, destination);
        }
        details.add(from + "/ → " + to + "/ (" + files.size() + " file(s))");
    }

    /** Maven compiles one folder and copies another: what is not Java source moves to the resources folder. */
    private static void separateResources(Path root, String sources, String resources, List<String> details) throws IOException {
        Path folder = root.resolve(sources);
        if (!Files.isDirectory(folder)) {
            return;
        }
        List<Path> others;
        try (Stream<Path> walk = Files.walk(folder)) {
            others = walk.filter(p -> Files.isRegularFile(p) && !p.toString().endsWith(".java")).toList();
        }
        for (Path file : others) {
            Path destination = root.resolve(resources).resolve(folder.relativize(file));
            Files.createDirectories(destination.getParent());
            Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!others.isEmpty()) {
            details.add(others.size() + " file(s) that are not Java source: " + sources + "/ → " + resources + "/");
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static final String POM_START = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
            + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
            + "    <modelVersion>4.0.0</modelVersion>\n\n";

    /** The parent of a build of several projects: the modules, and the libraries all of them were given. */
    static String parentPom(String name, String artifactId, List<String> modules, List<Dependency> shared) {
        StringBuilder pom = new StringBuilder(POM_START)
                .append("    <!-- Generated by Renova from Ant builds (a build.xml in each module). Set the groupId and version your organisation uses. -->\n")
                .append("    <groupId>").append(LOCAL_GROUP).append(".project</groupId>\n")
                .append("    <artifactId>").append(artifactId).append("</artifactId>\n")
                .append("    <version>1.0.0</version>\n")
                .append("    <packaging>pom</packaging>\n")
                .append("    <name>").append(escape(name)).append("</name>\n\n")
                .append("    <modules>\n");
        modules.forEach(m -> pom.append("        <module>").append(m).append("</module>\n"));
        pom.append("    </modules>\n");
        if (shared.stream().anyMatch(d -> !d.published())) {
            pom.append(repositories("${project.basedir}/"));
        }
        pom.append(dependenciesXml(shared, List.of(), "    <!-- Libraries the projects shared from a folder above them: every module has them. -->\n"));
        return pom.append("</project>\n").toString();
    }

    private static String repositories(String folder) {
        return "\n    <repositories>\n"
                + "        <!-- Libraries the project carried as files and that are not published anywhere. -->\n"
                + "        <repository>\n            <id>project-libraries</id>\n            <url>file://" + folder
                + LOCAL_REPOSITORY + "</url>\n        </repository>\n    </repositories>\n";
    }

    private static String dependenciesXml(List<Dependency> dependencies, List<String> modules, String comment) {
        if (dependencies.isEmpty() && modules.isEmpty()) {
            return "";
        }
        StringBuilder xml = new StringBuilder("\n    <dependencies>\n").append(dependencies.isEmpty() ? "" : comment.replace("    <!--", "        <!--"));
        for (String module : modules) {
            xml.append("        <dependency>\n            <groupId>${project.groupId}</groupId>\n")
                    .append("            <artifactId>").append(module).append("</artifactId>\n")
                    .append("            <version>${project.version}</version>\n        </dependency>\n");
        }
        for (Dependency d : dependencies) {
            xml.append("        <dependency>\n            <groupId>").append(d.gav().groupId()).append("</groupId>\n")
                    .append("            <artifactId>").append(d.gav().artifactId()).append("</artifactId>\n")
                    .append("            <version>").append(d.gav().version()).append("</version>\n");
            if (d.scope() != null) {
                xml.append("            <scope>").append(d.scope()).append("</scope>\n");
            }
            xml.append("        </dependency>\n");
        }
        return xml.append("    </dependencies>\n").toString();
    }

    /** @param module where the pom is a module of a generated parent; null for a project of its own */
    static String pom(LegacyLayout layout, List<Dependency> dependencies, boolean tests, InParent module) {
        String level = layout.javaVersion() == null ? "8" : layout.javaVersion();
        String declared = level.matches("[1-8]") ? "1." + level : level;
        StringBuilder pom = new StringBuilder(POM_START);
        if (module == null) {
            pom.append("    <!-- Generated by Renova from ").append(layout.ant() ? "the Ant build (build.xml)" : "the project's folders")
                    .append(". Set the groupId and version your organisation uses. -->\n")
                    .append("    <groupId>").append(LOCAL_GROUP).append(".project</groupId>\n")
                    .append("    <artifactId>").append(artifactId(layout.name())).append("</artifactId>\n")
                    .append("    <version>1.0.0</version>\n");
        } else {
            pom.append("    <!-- Generated by Renova from the Ant build (build.xml). -->\n")
                    .append("    <parent>\n        <groupId>").append(LOCAL_GROUP).append(".project</groupId>\n")
                    .append("        <artifactId>").append(module.parentId()).append("</artifactId>\n")
                    .append("        <version>1.0.0</version>\n")
                    .append("        <relativePath>").append(module.up()).append("pom.xml</relativePath>\n    </parent>\n\n")
                    .append("    <artifactId>").append(module.artifactId()).append("</artifactId>\n");
        }
        pom.append("    <packaging>").append(layout.webApplication() ? "war" : "jar").append("</packaging>\n")
                .append("    <name>").append(escape(layout.name())).append("</name>\n\n")
                .append("    <properties>\n")
                .append("        <maven.compiler.source>").append(declared).append("</maven.compiler.source>\n")
                .append("        <maven.compiler.target>").append(declared).append("</maven.compiler.target>\n")
                .append("        <project.build.sourceEncoding>").append(layout.encoding() == null ? "UTF-8" : escape(layout.encoding()))
                .append("</project.build.sourceEncoding>\n")
                .append("    </properties>\n");
        if (module == null ? dependencies.stream().anyMatch(d -> !d.published()) : module.files()) {
            // In a module the folder is beside the parent pom; the same id replaces the one inherited from it,
            // whose path is read from the module's own folder.
            pom.append(repositories("${project.basedir}/" + (module == null ? "" : module.up())));
        }
        pom.append(dependenciesXml(dependencies, module == null ? List.of() : module.uses(), ""));
        pom.append("\n    <build>\n        <plugins>\n")
                .append(plugin("maven-compiler-plugin", "3.13.0", null));
        if (tests) {
            pom.append(plugin("maven-surefire-plugin", "3.2.5", null));
        }
        if (layout.webApplication()) {
            pom.append(plugin("maven-war-plugin", "3.4.0", "                    <failOnMissingWebXml>false</failOnMissingWebXml>\n"));
        }
        pom.append("        </plugins>\n    </build>\n</project>\n");
        return pom.toString();
    }

    private static String plugin(String artifactId, String version, String configuration) {
        return "            <plugin>\n                <groupId>org.apache.maven.plugins</groupId>\n                <artifactId>" + artifactId
                + "</artifactId>\n                <version>" + version + "</version>\n"
                + (configuration == null ? "" : "                <configuration>\n" + configuration + "                </configuration>\n")
                + "            </plugin>\n";
    }

    private static String artifactId(String name) {
        String id = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return id.isBlank() ? "project" : id;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
