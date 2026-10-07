package io.renova.dotnet.behaviour;

import io.renova.core.behaviour.AppDeployment;
import io.renova.core.behaviour.BehaviourRunner;
import io.renova.core.behaviour.Route;
import io.renova.core.behaviour.Scenario;
import io.renova.core.engine.MigrationContext;
import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;
import io.renova.core.util.Proc;
import io.renova.dotnet.DotnetPlugin;
import io.renova.dotnet.ProjectFile;
import io.renova.dotnet.Sources;
import io.renova.dotnet.Tfm;
import io.renova.dotnet.fix.Dotnet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs an ASP.NET Core application, the original beside the migrated one: each is published with the .NET SDK
 * on this machine and started in a container of Microsoft's ASP.NET runtime image for its own target framework
 * (3.1 for the original, say, and 10.0 for the migrated copy).
 *
 * <p>An application on classic ASP.NET cannot be run this way: it needs IIS on Windows. For those the original
 * and the result are compared on a Windows machine (see docs/windows-test-plan.md).
 */
public final class DotnetBehaviourRunner implements BehaviourRunner {

    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final Pattern CLASS_ROUTE = Pattern.compile("\\[Route\\(\\s*\"([^\"]*)\"");
    private static final Pattern ACTION = Pattern.compile("\\[Http(Get|Post|Put|Delete|Patch)(?:\\(\\s*\"([^\"]*)\"[^)]*\\))?\\s*]");
    private static final Pattern MINIMAL = Pattern.compile("\\.Map(Get|Post|Put|Delete|Patch)\\(\\s*\"([^\"]*)\"");
    private static final Pattern CONTROLLER = Pattern.compile("class\\s+(\\w+?)Controller\\b");
    private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)[^}]*}");

    @Override
    public Optional<String> unsupported(ProjectModel model) {
        List<Module> web = webProjects(model);
        if (web.size() == 1) {
            return Optional.empty();
        }
        if (web.size() > 1) {
            return Optional.of("several ASP.NET Core applications (" + web.stream().map(Module::name).toList()
                    + "); running one application per project for now");
        }
        return Optional.of(model.modules().stream().anyMatch(m -> List.of("aspnet", "webforms").contains(String.valueOf(m.fact("kind"))))
                ? "the original is a classic ASP.NET application, which runs on IIS on Windows only; compare it with the migrated "
                + "application there (benchmark/windows/Compare-AspNet.ps1)"
                : "no web application to run: no ASP.NET Core project");
    }

    @Override
    public List<Scenario> discover(ProjectModel model, Path root) {
        Map<String, String> gets = new LinkedHashMap<>();
        gets.put("/", null);
        for (Route route : routes(model, root)) {
            if (route.method() == null || route.method().equals("GET")) {
                gets.putIfAbsent(VARIABLE.matcher(route.template()).replaceAll(m -> m.group(1).toLowerCase(java.util.Locale.ROOT)
                        .matches(".*(id|number|page|year)$") ? "1" : "sample"), route.handlerFile());
            }
        }
        List<Scenario> scenarios = new ArrayList<>();
        gets.forEach((path, handler) -> scenarios.add(new Scenario("s" + (scenarios.size() + 1), "GET", path,
                handler == null ? "the application's root" : "a route in " + handler, handler)));
        return scenarios;
    }

    /** Controller actions ([Route] on the class, [HttpGet("...")] on the action) and minimal API endpoints (app.MapGet). */
    @Override
    public List<Route> routes(ProjectModel model, Path root) {
        Module web = webProjects(model).getFirst();
        Path dir = web.path().equals(".") ? root : root.resolve(web.path());
        List<Route> routes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path source : files.filter(f -> f.toString().endsWith(".cs") && !Sources.produced(dir.relativize(f))).sorted().toList()) {
                String code = Files.readString(source, StandardCharsets.ISO_8859_1).replaceAll("(?m)^\\s*//.*$", "");
                String file = root.relativize(source).toString().replace('\\', '/');
                Matcher minimal = MINIMAL.matcher(code);
                while (minimal.find()) {
                    routes.add(new Route(minimal.group(1).toUpperCase(java.util.Locale.ROOT), normalise("/" + minimal.group(2)), file));
                }
                Matcher controller = CONTROLLER.matcher(code);
                if (!controller.find()) {
                    continue;
                }
                Matcher classRoute = CLASS_ROUTE.matcher(code.substring(0, controller.start()));
                String prefix = classRoute.find() ? classRoute.group(1).replace("[controller]", controller.group(1).toLowerCase(java.util.Locale.ROOT)) : "";
                Matcher action = ACTION.matcher(code.substring(controller.start()));
                while (action.find()) {
                    String path = action.group(2) == null ? "" : action.group(2);
                    routes.add(new Route(action.group(1).toUpperCase(java.util.Locale.ROOT),
                            normalise(path.startsWith("/") || path.startsWith("~/") ? path.replaceFirst("^~", "") : "/" + prefix + "/" + path), file));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return routes;
    }

    @Override
    public Deployments prepare(MigrationContext context, Path originalSource, Path workDir, Consumer<String> progress) throws Exception {
        Path dotnet = Dotnet.executable().orElseThrow(() -> new IOException("The .NET SDK is not installed; the applications are published with it"));
        ProjectModel original = context.plugin().model(originalSource);
        Module web = webProjects(original).getFirst();
        Files.createDirectories(workDir);
        Playbook playbook = context.playbook();

        // The original does not change during a migration: published once, reused in later repair rounds.
        Path baseline = workDir.resolve("baseline-app");
        Path key = workDir.resolve("baseline.key");
        String sourceKey = treeKey(originalSource);
        if (!Files.isDirectory(baseline) || !Files.isRegularFile(key) || !Files.readString(key).equals(sourceKey)) {
            Path build = workDir.resolve("original-build");
            deleteRecursively(build);
            copyTree(originalSource, build);
            progress.accept("Publishing the original application");
            publish(dotnet, build.resolve(web.buildFile()), baseline);
            Files.writeString(key, sourceKey);
        }
        Path candidate = workDir.resolve("candidate-app");
        progress.accept("Publishing the migrated application");
        publish(dotnet, context.workspace().root().resolve(web.buildFile()), candidate);

        String before = runtime(ProjectFile.read(originalSource.resolve(web.buildFile())));
        ProjectFile migrated = ProjectFile.read(context.workspace().root().resolve(web.buildFile()));
        String after = runtime(migrated);
        return new Deployments(
                deployment(setting(playbook, "behaviour.baseline.image", "mcr.microsoft.com/dotnet/aspnet:" + before), baseline,
                        assembly(ProjectFile.read(originalSource.resolve(web.buildFile()))), ".NET " + before),
                deployment(setting(playbook, "behaviour.candidate.image", "mcr.microsoft.com/dotnet/aspnet:" + after), candidate,
                        assembly(migrated), ".NET " + after));
    }

    private static AppDeployment deployment(String image, Path published, String assembly, String platform) {
        // The mount is read-only and an application may write beside itself (logs, a local database): it runs from a copy.
        return new AppDeployment(image, Map.of(published, "/src"), 8080, "", platform)
                .withEnvironment(Map.of("ASPNETCORE_URLS", "http://0.0.0.0:8080", "ASPNETCORE_ENVIRONMENT", "Production",
                        "DOTNET_CLI_TELEMETRY_OPTOUT", "1", "DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "0"))
                .withCommand(List.of("sh", "-c", "cp -a /src /app && cd /app && exec dotnet " + assembly + ".dll"));
    }

    /** Settings as environment variables, the way ASP.NET Core reads them: "ConnectionStrings:Default" is ConnectionStrings__Default. */
    @Override
    public Map<String, String> environment(ProjectModel model, Map<String, String> settings) {
        Map<String, String> environment = new LinkedHashMap<>();
        settings.forEach((name, value) -> environment.put(name.replace(":", "__").replace(".", "__"), value));
        return environment;
    }

    static List<Module> webProjects(ProjectModel model) {
        return model.modules().stream().filter(m -> "web".equals(m.fact("kind"))).toList();
    }

    /** "3.1" for netcoreapp3.1, "10.0" for net10.0: the tag of the runtime image. */
    static String runtime(ProjectFile project) {
        return project.targetFrameworks().isEmpty() ? "10.0" : Tfm.version(project.targetFrameworks().getFirst());
    }

    private static String assembly(ProjectFile project) {
        String name = project.properties().get("AssemblyName");
        return name == null || name.isBlank() ? project.name() : name;
    }

    private static void publish(Path dotnet, Path project, Path output) throws IOException, InterruptedException {
        deleteRecursively(output);
        Proc.Result result = Proc.run(List.of(dotnet.toString(), "publish", project.toString(), "-c", "Release", "-o", output.toString(),
                "--nologo", "-v:q", "-p:UseAppHost=false", "-p:UseSharedCompilation=false"), project.getParent(), TIMEOUT, Dotnet.environment(dotnet));
        if (!result.ok()) {
            throw new IOException("Could not publish " + project.getFileName() + ": " + result.tail(15));
        }
    }

    private static String normalise(String path) {
        String p = path.replaceAll("/+", "/");
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    private static String setting(Playbook playbook, String path, String fallback) {
        Object value = playbook.setting(path);
        return value == null ? fallback : value.toString();
    }

    private static String treeKey(Path root) throws IOException {
        StringBuilder key = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                key.append(root.relativize(f)).append(':').append(Files.size(f)).append(';');
            }
        }
        return Integer.toHexString(key.toString().hashCode()) + "-" + key.length();
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.toList()) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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

    /** Keeps the plugin class referenced from here, where the runner is created for it. */
    static String ecosystem() {
        return DotnetPlugin.ID;
    }
}
