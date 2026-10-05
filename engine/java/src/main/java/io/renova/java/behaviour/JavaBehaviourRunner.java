package io.renova.java.behaviour;

import io.renova.core.behaviour.AppDeployment;
import io.renova.core.behaviour.BehaviourRunner;
import io.renova.core.behaviour.DockerSandbox;
import io.renova.core.behaviour.Scenario;
import io.renova.core.engine.MigrationContext;
import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runs a Maven web application (one WAR module) on a servlet container: the original built from its
 * sources (the workspace's baseline commit) with the legacy JDK and deployed on the legacy container, the migrated
 * WAR from the verified build on the target container. Images come from the playbook's
 * {@code settings.behaviour}, so other targets need no code change.
 */
public final class JavaBehaviourRunner implements BehaviourRunner {

    static final String DEFAULT_BASELINE_BUILD = "maven:3.9-eclipse-temurin-8";
    static final String DEFAULT_BASELINE_SERVER = "tomcat:9.0-jdk8";
    static final String DEFAULT_CANDIDATE_SERVER = "tomcat:10.1-jdk21";
    private static final List<String> EE_PLATFORM = List.of("javax:javaee-api", "javax:javaee-web-api",
            "jakarta.platform:jakarta.jakartaee-api", "jakarta.platform:jakarta.jakartaee-web-api", "javax.ejb:", "jakarta.ejb:");

    @Override
    public Optional<String> unsupported(ProjectModel model) {
        List<Module> wars = warModules(model);
        if (model.modules().stream().anyMatch(m -> !"maven".equals(m.fact("buildTool")))) {
            return Optional.of("only Maven builds can be run side by side for now");
        }
        if (wars.isEmpty()) {
            return Optional.of("no web application (WAR) module to run");
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
        return EndpointDiscovery.discover(model, root, warModules(model).getFirst());
    }

    @Override
    public Deployments prepare(MigrationContext context, Path originalSource, Path workDir, Consumer<String> progress)
            throws Exception {
        Module war = warModules(context.project()).getFirst();
        Path workspace = context.workspace().root();
        Files.createDirectories(workDir);
        Playbook playbook = context.playbook();

        // Built in a copy, so the exported sources stay as they were.
        Path baselineSource = workDir.resolve("original-build");
        deleteRecursively(baselineSource);
        copyTree(originalSource, baselineSource);

        String buildImage = setting(playbook, "behaviour.baseline.build", DEFAULT_BASELINE_BUILD);
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

        Path baselineWar = copyWar(baselineSource.resolve(war.path()), workDir.resolve("baseline.war"));
        Path candidateWar = copyWar(workspace.resolve(war.path()), workDir.resolve("candidate.war"));
        String appPath = "/usr/local/tomcat/webapps/ROOT.war";
        return new Deployments(
                new AppDeployment(setting(playbook, "behaviour.baseline.server", DEFAULT_BASELINE_SERVER),
                        Map.of(baselineWar, appPath), 8080, "",
                        setting(playbook, "behaviour.baseline.platform", "Java 8, Tomcat 9")),
                new AppDeployment(setting(playbook, "behaviour.candidate.server", DEFAULT_CANDIDATE_SERVER),
                        Map.of(candidateWar, appPath), 8080, "",
                        setting(playbook, "behaviour.candidate.platform", "Java 21, Tomcat 10.1")));
    }

    /** JVM system properties for Tomcat, in {@code CATALINA_OPTS}. */
    @Override
    public Map<String, String> environment(Map<String, String> settings) {
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
        return Map.of("CATALINA_OPTS", opts.toString());
    }

    static List<Module> warModules(ProjectModel model) {
        return model.modules().stream().filter(m -> "war".equals(m.fact("packaging"))).toList();
    }

    private static Path copyWar(Path moduleDir, Path target) throws IOException {
        Path targetDir = moduleDir.resolve("target");
        if (!Files.isDirectory(targetDir)) {
            throw new IOException("No build output in " + targetDir + "; the project must be built first");
        }
        try (Stream<Path> files = Files.list(targetDir)) {
            Path war = files.filter(f -> f.getFileName().toString().endsWith(".war")).sorted().findFirst()
                    .orElseThrow(() -> new IOException("No WAR file in " + targetDir));
            return Files.copy(war, target, StandardCopyOption.REPLACE_EXISTING);
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
