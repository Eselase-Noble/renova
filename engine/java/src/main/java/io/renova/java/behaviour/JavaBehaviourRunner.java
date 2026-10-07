package io.renova.java.behaviour;

import io.renova.core.behaviour.AppDeployment;
import io.renova.core.behaviour.BehaviourRunner;
import io.renova.core.behaviour.DockerSandbox;
import io.renova.core.behaviour.Route;
import io.renova.core.behaviour.Scenario;
import io.renova.core.engine.MigrationContext;
import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs a Maven web application, the original beside the migrated one. A Spring Boot application (one module
 * with a {@code @SpringBootApplication} class) runs by itself with {@code java -jar}: the original on the Java
 * release it was written for, the migrated one on the target's. Any other web application (one WAR module)
 * runs on a servlet container: the legacy container for the original, the target container for the migrated
 * WAR. The original is built from its sources (the workspace's baseline commit); the migrated artifact comes
 * from the verified build. Images come from the playbook's {@code settings.behaviour} where it names them, so
 * other targets need no code change.
 */
public final class JavaBehaviourRunner implements BehaviourRunner {

    static final String DEFAULT_BASELINE_BUILD = "maven:3.9-eclipse-temurin-8";
    static final String DEFAULT_BASELINE_SERVER = "tomcat:9.0-jdk8";
    static final String DEFAULT_CANDIDATE_SERVER = "tomcat:10.1-jdk21";
    private static final List<String> EE_PLATFORM = List.of("javax:javaee-api", "javax:javaee-web-api",
            "jakarta.platform:jakarta.jakartaee-api", "jakarta.platform:jakarta.jakartaee-web-api", "javax.ejb:", "jakarta.ejb:");

    /** The module that is the application, and whether it runs by itself or on a servlet container. */
    record App(Module module, boolean springBoot) {
    }

    @Override
    public Optional<String> unsupported(ProjectModel model) {
        List<Module> wars = warModules(model);
        if (model.modules().stream().anyMatch(m -> !"maven".equals(m.fact("buildTool")))) {
            return Optional.of("only Maven builds can be run side by side for now");
        }
        List<Module> boot = springBootModules(model);
        if (boot.size() == 1) {
            return Optional.empty();
        }
        if (boot.size() > 1) {
            return Optional.of("several Spring Boot applications (" + boot.stream().map(Module::name).toList()
                    + "); running one application per project for now");
        }
        if (wars.isEmpty()) {
            return Optional.of("no web application to run: no Spring Boot application and no WAR module");
        }
        if (wars.size() > 1) {
            return Optional.of("several WAR modules (" + wars.stream().map(Module::name).toList()
                    + "); running one application per project for now");
        }
        for (Module m : model.modules()) {
            if (m.fact("dependencies") instanceof List<?> deps) {
                for (Object dep : deps) {
                    if (EE_PLATFORM.stream().anyMatch(p -> dep.toString().startsWith(p))) {
                        return Optional.of("needs a Jakarta EE server (" + dep + "); only servlet containers (Tomcat) "
                                + "are supported for now");
                    }
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Scenario> discover(ProjectModel model, Path root) {
        return EndpointDiscovery.discover(model, root, application(model).module());
    }

    @Override
    public List<Route> routes(ProjectModel model, Path root) {
        return EndpointDiscovery.find(model, root, application(model).module()).routes();
    }

    @Override
    public Deployments prepare(MigrationContext context, Path originalSource, Path workDir, Consumer<String> progress)
            throws Exception {
        // The application is the one the original has: the migrated copy runs the same module.
        ProjectModel original = context.plugin().model(originalSource);
        App app = application(original);
        Module war = app.module();
        Path workspace = context.workspace().root();
        Files.createDirectories(workDir);
        Playbook playbook = context.playbook();
        int baselineJava = runtimeRelease(javaVersion(original, war));
        String buildImage = setting(playbook, "behaviour.baseline.build",
                app.springBoot() ? "maven:3.9-eclipse-temurin-" + baselineJava : DEFAULT_BASELINE_BUILD);

        // The original does not change during a migration: build it once, reuse it in later repair rounds.
        Path baselineWar = workDir.resolve(app.springBoot() ? "baseline-app.jar" : "baseline.war");
        Path key = workDir.resolve("baseline.key");
        String sourceKey = treeKey(originalSource);
        if (!Files.isRegularFile(baselineWar) || !Files.isRegularFile(key) || !Files.readString(key).equals(sourceKey)) {
            buildOriginal(context, originalSource, war, workDir, baselineWar, buildImage, app.springBoot(), progress);
            Files.writeString(key, sourceKey);
        }
        if (app.springBoot()) {
            Path candidateJar = copyArtifact(workspace.resolve(war.path()), workDir.resolve("candidate-app.jar"), true);
            String target = playbook.targets().get("java");
            int candidateJava = runtimeRelease(target == null ? "21" : target);
            return new Deployments(
                    springBoot(setting(playbook, "behaviour.baseline.runtime", "eclipse-temurin:" + baselineJava + "-jre"),
                            baselineWar, originalSource.resolve(war.path()), "Java " + baselineJava + ", Spring Boot"),
                    springBoot(setting(playbook, "behaviour.candidate.runtime", "eclipse-temurin:" + candidateJava + "-jre"),
                            candidateJar, workspace.resolve(war.path()), "Java " + candidateJava + ", Spring Boot"));
        }
        Path candidateWar = copyArtifact(workspace.resolve(war.path()), workDir.resolve("candidate.war"), false);
        String appPath = "/usr/local/tomcat/webapps/ROOT.war";
        return new Deployments(
                new AppDeployment(setting(playbook, "behaviour.baseline.server", DEFAULT_BASELINE_SERVER),
                        Map.of(baselineWar, appPath), 8080, "",
                        setting(playbook, "behaviour.baseline.platform", "Java 8, Tomcat 9")),
                new AppDeployment(setting(playbook, "behaviour.candidate.server", DEFAULT_CANDIDATE_SERVER),
                        Map.of(candidateWar, appPath), 8080, "",
                        setting(playbook, "behaviour.candidate.platform", "Java 21, Tomcat 10.1")));
    }

    /** A Spring Boot application run with {@code java -jar}, on the port and context path the sandbox expects. */
    private static AppDeployment springBoot(String image, Path jar, Path moduleDir, String platform) {
        String inContainer = "/app/application.jar";
        return new AppDeployment(image, Map.of(jar, inContainer), 8080, contextPath(moduleDir), platform)
                .withEnvironment(Map.of("SERVER_PORT", "8080"))
                .withCommand(List.of("java", "-jar", inContainer));
    }

    /**
     * JVM system properties: in {@code CATALINA_OPTS} for Tomcat, and in {@code JAVA_TOOL_OPTIONS}, which
     * every JVM reads, for an application that runs by itself.
     */
    @Override
    public Map<String, String> environment(ProjectModel model, Map<String, String> settings) {
        if (settings.isEmpty()) {
            return Map.of();
        }
        StringBuilder opts = new StringBuilder();
        settings.forEach((k, v) -> {
            if (k.contains(" ") || v.contains(" ")) {
                throw new IllegalArgumentException("Application settings for the sandbox cannot contain spaces: " + k + "=" + v);
            }
            opts.append(opts.isEmpty() ? "" : " ").append("-D").append(k).append('=').append(v);
        });
        return Map.of(application(model).springBoot() ? "JAVA_TOOL_OPTIONS" : "CATALINA_OPTS", opts.toString());
    }

    private void buildOriginal(MigrationContext context, Path originalSource, Module war, Path workDir, Path baselineWar,
                               String buildImage, boolean springBoot, Consumer<String> progress) throws Exception {
        // Built in a copy, so the exported sources stay as they were.
        Path baselineSource = workDir.resolve("original-build");
        deleteRecursively(baselineSource);
        copyTree(originalSource, baselineSource);

        progress.accept("Building the original application with " + buildImage);
        List<String> build = new ArrayList<>(List.of("docker", "run", "--rm"));
        build.addAll(userFlag());
        Path m2 = Path.of(System.getProperty("user.home"), ".m2");
        Files.createDirectories(m2);
        build.addAll(List.of("-e", "MAVEN_CONFIG=/var/maven/.m2", "-v", m2 + ":/var/maven/.m2",
                "-v", baselineSource.toAbsolutePath() + ":/src", "-w", "/src", buildImage,
                "mvn", "-B", "-q", "-Duser.home=/var/maven", "package", "-DskipTests"));
        String settings = context.options().toolOption("maven.settings");
        if (settings != null) {
            build.addAll(build.indexOf(buildImage), List.of("-v", Path.of(settings).toAbsolutePath() + ":/var/maven/settings.xml:ro"));
            build.addAll(List.of("-s", "/var/maven/settings.xml"));
        }
        if ("true".equals(context.options().toolOption("maven.offline"))) {
            build.add("-o");
        }
        run(build, workDir, 1800, "build the original application");
        copyArtifact(baselineSource.resolve(war.path()), baselineWar, springBoot);
    }

    /** Identifies the original sources by their files' paths and sizes. */
    private static String treeKey(Path root) throws IOException {
        StringBuilder key = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                key.append(root.relativize(f)).append(':').append(Files.size(f)).append(';');
            }
        }
        return Integer.toHexString(key.toString().hashCode()) + "-" + key.length();
    }

    static List<Module> warModules(ProjectModel model) {
        return model.modules().stream().filter(m -> "war".equals(m.fact("packaging"))).toList();
    }

    /** The application: the project's Spring Boot application if it has one, else its WAR module. */
    static App application(ProjectModel model) {
        List<Module> boot = springBootModules(model);
        return boot.isEmpty() ? new App(warModules(model).getFirst(), false) : new App(boot.getFirst(), true);
    }

    /** Modules that use Spring Boot and have a class that starts it; a library built on Spring Boot has none. */
    static List<Module> springBootModules(ProjectModel model) {
        List<Module> boot = new ArrayList<>();
        for (Module m : model.modules()) {
            Path dir = m.path().equals(".") ? model.root() : model.root().resolve(m.path());
            if (!"pom".equals(m.fact("packaging")) && startsSpringBoot(dir.resolve("src/main"))) {
                boot.add(m);
            }
        }
        return boot;
    }

    private static boolean startsSpringBoot(Path mainSources) {
        if (!Files.isDirectory(mainSources)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(mainSources)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".kt")).toList()) {
                if (Files.readString(f, StandardCharsets.ISO_8859_1).contains("@SpringBootApplication")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The module's Java level, else the project's lowest, else 8, which Spring Boot 2 assumes. */
    private static String javaVersion(ProjectModel model, Module module) {
        if (module.fact("javaVersion") != null) {
            return module.fact("javaVersion").toString();
        }
        return model.facts().get("javaVersions") instanceof List<?> versions && !versions.isEmpty()
                ? versions.getFirst().toString() : "8";
    }

    /** The long-term-support Java release that runs code of this level: images exist for these. */
    static int runtimeRelease(String javaVersion) {
        int level;
        try {
            String v = javaVersion.strip();
            level = Integer.parseInt(v.startsWith("1.") ? v.substring(2) : v.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 8;
        }
        return level <= 8 ? 8 : level <= 11 ? 11 : level <= 17 ? 17 : level <= 21 ? 21 : 25;
    }

    private static final Pattern CONTEXT_PATH_PROPERTY = Pattern.compile(
            "(?m)^\\s*server\\.(?:servlet\\.)?context-path\\s*[=:]\\s*(\\S+)\\s*$");
    private static final Pattern CONTEXT_PATH_YAML = Pattern.compile("(?m)^\\s+context-path:\\s*[\"']?([^\\s\"'#]+)");

    /** Where the application says it is served: application.properties, else application.yml, else the root. */
    static String contextPath(Path moduleDir) {
        Path resources = moduleDir.resolve("src/main/resources");
        try {
            Path properties = resources.resolve("application.properties");
            if (Files.isRegularFile(properties)) {
                Matcher m = CONTEXT_PATH_PROPERTY.matcher(Files.readString(properties, StandardCharsets.ISO_8859_1));
                if (m.find()) {
                    return trimSlash(m.group(1));
                }
            }
            for (String name : List.of("application.yml", "application.yaml")) {
                Path yaml = resources.resolve(name);
                if (Files.isRegularFile(yaml)) {
                    Matcher m = CONTEXT_PATH_YAML.matcher(Files.readString(yaml, StandardCharsets.ISO_8859_1));
                    if (m.find()) {
                        return trimSlash(m.group(1));
                    }
                }
            }
            return "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String trimSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /**
     * The module's built application: its WAR for a servlet container; for Spring Boot the archive the
     * build made executable, which is far larger than the plain one kept beside it.
     */
    private static Path copyArtifact(Path moduleDir, Path target, boolean springBoot) throws IOException {
        Path targetDir = moduleDir.resolve("target");
        if (!Files.isDirectory(targetDir)) {
            throw new IOException("No build output in " + targetDir + "; the project must be built first");
        }
        try (Stream<Path> files = Files.list(targetDir)) {
            List<Path> archives = files.filter(f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".war") || (springBoot && name.endsWith(".jar")
                        && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar") && !name.endsWith("-tests.jar"));
            }).sorted().toList();
            if (archives.isEmpty()) {
                throw new IOException("No " + (springBoot ? "application archive" : "WAR file") + " in " + targetDir);
            }
            Path artifact = archives.getFirst();
            if (springBoot) {
                for (Path a : archives) {
                    if (Files.size(a) > Files.size(artifact)) {
                        artifact = a;
                    }
                }
            }
            return Files.copy(artifact, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String setting(Playbook playbook, String path, String fallback) {
        Object value = playbook.setting(path);
        return value == null ? fallback : value.toString();
    }

    private static List<String> userFlag() throws IOException, InterruptedException {
        DockerSandbox.Result uid = DockerSandbox.exec(List.of("id", "-u"), null, 10);
        DockerSandbox.Result gid = DockerSandbox.exec(List.of("id", "-g"), null, 10);
        return uid.exit() == 0 && gid.exit() == 0 ? List.of("--user", uid.output().strip() + ":" + gid.output().strip())
                : List.of();
    }

    private static String run(List<String> command, Path dir, long timeoutSeconds, String what)
            throws IOException, InterruptedException {
        DockerSandbox.Result result = DockerSandbox.exec(command, dir, timeoutSeconds);
        if (result.exit() != 0) {
            String out = result.output().strip();
            throw new IOException("Could not " + what + ": " + (out.length() > 1500 ? "…" + out.substring(out.length() - 1500) : out));
        }
        return result.output();
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
