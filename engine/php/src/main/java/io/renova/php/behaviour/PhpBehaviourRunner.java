package io.renova.php.behaviour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.renova.core.behaviour.AppDeployment;
import io.renova.core.behaviour.BehaviourRunner;
import io.renova.core.behaviour.DockerSandbox;
import io.renova.core.behaviour.Route;
import io.renova.core.behaviour.Scenario;
import io.renova.core.engine.MigrationContext;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;
import io.renova.core.util.Proc;
import io.renova.core.util.Versions;
import io.renova.php.ComposerFile;
import io.renova.php.Constraints;
import io.renova.php.PhpPlugin;
import io.renova.php.fix.PhpRuntimes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * Runs a PHP web application, the original beside the migrated one, each on PHP's own web server in a
 * container of the official PHP image: the original on the PHP it was written for, the migrated one on the
 * target's. A Laravel application gets an application key and an SQLite database of its own, filled by its
 * migrations, so that requests that read and write data can be compared; any other application is served from
 * its public folder as it is.
 *
 * <p>The original's dependencies are installed from its composer.lock; the migrated copy already has the ones
 * the verification installed. Images come from the playbook's {@code settings.behaviour} where it names them.
 */
public final class PhpBehaviourRunner implements BehaviourRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ROUTE = Pattern.compile(
            "Route::(get|post|put|patch|delete|any|view|resource|apiResource)\\(\\s*['\"]([^'\"]*)['\"]\\s*,\\s*([^;]*?)\\)\\s*(?:->|;)");
    private static final Pattern CONTROLLER = Pattern.compile("([A-Za-z_][\\w\\\\]*)(?:::class|@\\w+)");
    private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)\\??}");
    /** A key for the sandbox only: sessions and encrypted cookies need one, and both sides get the same. */
    private static final String SANDBOX_KEY = "base64:cmVub3ZhLXNhbmRib3gta2V5LTMyLWJ5dGVzLWxvbmc=";

    @Override
    public Optional<String> unsupported(ProjectModel model) {
        Path root = model.root();
        if (model.modules().size() > 1) {
            return Optional.of("several Composer projects in one folder; running one application per project for now");
        }
        if (documentRoot(root) == null) {
            return Optional.of("no web application to run: no artisan file, and no index.php in public, web, www or the project folder");
        }
        return Optional.empty();
    }

    @Override
    public List<Scenario> discover(ProjectModel model, Path root) {
        List<Scenario> scenarios = new ArrayList<>();
        Map<String, String> gets = new LinkedHashMap<>();
        gets.put("/", null);
        for (Route route : routes(model, root)) {
            if (route.method() == null || route.method().equals("GET")) {
                gets.putIfAbsent(VARIABLE.matcher(route.template()).replaceAll(m -> m.group(1).toLowerCase(java.util.Locale.ROOT)
                        .matches(".*(id|number|page|year)$") ? "1" : "sample"), route.handlerFile());
            }
        }
        gets.forEach((path, handler) -> scenarios.add(new Scenario("s" + (scenarios.size() + 1), "GET", path,
                handler == null ? "the application's front page" : "a route in " + handler, handler)));
        return scenarios;
    }

    /** Laravel's routes from routes/web.php and routes/api.php; for any other application, the pages in its public folder. */
    @Override
    public List<Route> routes(ProjectModel model, Path root) {
        List<Route> routes = new ArrayList<>();
        if (Files.isRegularFile(root.resolve("artisan"))) {
            for (String[] file : new String[][] {{"routes/web.php", ""}, {"routes/api.php", "/api"}, {"app/Http/routes.php", ""}}) {
                Path path = root.resolve(file[0]);
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Matcher m = ROUTE.matcher(read(path).replaceAll("(?m)^\\s*(//|#).*$", ""));
                while (m.find()) {
                    String template = (file[1] + "/" + m.group(2)).replaceAll("/+", "/").replaceAll("(?<=.)/$", "");
                    String handler = handler(root, m.group(3), file[0]);
                    switch (m.group(1)) {
                        case "resource", "apiResource" -> {
                            routes.add(new Route("GET", template, handler));
                            routes.add(new Route("POST", template, handler));
                            routes.add(new Route("GET", template + "/{id}", handler));
                        }
                        case "any", "view" -> routes.add(new Route(m.group(1).equals("view") ? "GET" : null, template, handler));
                        default -> routes.add(new Route(m.group(1).toUpperCase(java.util.Locale.ROOT), template, handler));
                    }
                }
            }
            return routes;
        }
        String docRoot = documentRoot(root);
        if (docRoot != null) {
            Path dir = docRoot.isEmpty() ? root : root.resolve(docRoot);
            try (Stream<Path> files = Files.walk(dir, 2)) {
                for (Path page : files.filter(f -> f.toString().endsWith(".php") && !PhpPlugin.produced(root.relativize(f))).sorted().toList()) {
                    String path = "/" + dir.relativize(page).toString().replace('\\', '/');
                    if (!path.equals("/index.php")) {
                        routes.add(new Route("GET", path, root.relativize(page).toString().replace('\\', '/')));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return routes;
    }

    @Override
    public Deployments prepare(MigrationContext context, Path originalSource, Path workDir, Consumer<String> progress) throws Exception {
        Path workspace = context.workspace().root();
        Playbook playbook = context.playbook();
        Files.createDirectories(workDir);

        // The original with the libraries it had: installed once from its lock file, reused in later rounds.
        Path baseline = workDir.resolve("original-build");
        Path key = workDir.resolve("baseline.key");
        String sourceKey = treeKey(originalSource);
        if (!Files.isDirectory(baseline) || !Files.isRegularFile(key) || !Files.readString(key).equals(sourceKey)) {
            deleteRecursively(baseline);
            copyTree(originalSource, baseline);
            if (Files.isRegularFile(baseline.resolve("composer.json"))) {
                installOriginal(baseline, progress);
            }
            Files.writeString(key, sourceKey);
        }
        if (!Files.isDirectory(workspace.resolve("vendor")) && Files.isRegularFile(originalSource.resolve("composer.json"))) {
            throw new IOException("The migrated copy has no vendor folder; it must be verified (composer update) before it can be run");
        }
        String baselinePhp = originalPhp(baseline);
        String target = playbook.targets().getOrDefault("php", "8.4");
        return new Deployments(
                deployment(setting(playbook, "behaviour.baseline.image", "php:" + baselinePhp + "-cli"), baseline, "PHP " + baselinePhp),
                deployment(setting(playbook, "behaviour.candidate.image", "php:" + target + "-cli"), workspace, "PHP " + target));
    }

    private static AppDeployment deployment(String image, Path app, String platform) {
        boolean laravel = Files.isRegularFile(app.resolve("artisan"));
        String docRoot = documentRoot(app);
        // The mount is read-only and the application writes (logs, caches, its database): it runs from a copy.
        StringBuilder start = new StringBuilder("cp -a /src /app && cd /app");
        Map<String, String> environment = new LinkedHashMap<>();
        if (laravel) {
            // Laravel's router script for PHP's own server: in the project until Laravel 8, in the framework since,
            // where it expects to be started from the public folder, as "artisan serve" does.
            String serve = Files.isRegularFile(app.resolve("server.php")) ? "exec php -S 0.0.0.0:8080 -t public server.php"
                    : "cd public && exec php -S 0.0.0.0:8080 ../vendor/laravel/framework/src/Illuminate/Foundation/resources/server.php";
            start.append(" && mkdir -p database storage/framework/cache/data storage/framework/sessions storage/framework/views bootstrap/cache")
                    .append(" && touch database/renova.sqlite && php artisan migrate --force --no-interaction")
                    .append(" ; ").append(serve);
            environment.putAll(Map.of("APP_KEY", SANDBOX_KEY, "APP_ENV", "local", "APP_DEBUG", "false", "APP_URL", "http://localhost",
                    "DB_CONNECTION", "sqlite", "DB_DATABASE", "/app/database/renova.sqlite", "LOG_CHANNEL", "stderr",
                    "SESSION_DRIVER", "file", "CACHE_DRIVER", "file"));
            environment.putAll(Map.of("CACHE_STORE", "file", "QUEUE_CONNECTION", "sync", "MAIL_MAILER", "array"));
        } else {
            start.append(" && exec php -S 0.0.0.0:8080 -t ").append(docRoot == null || docRoot.isEmpty() ? "." : docRoot);
        }
        return new AppDeployment(image, Map.of(app, "/src"), 8080, "", platform)
                .withEnvironment(environment).withCommand(List.of("sh", "-c", start.toString()));
    }

    /** JVM-style settings have no meaning here: a PHP application reads its settings from the environment. */
    @Override
    public Map<String, String> environment(ProjectModel model, Map<String, String> settings) {
        Map<String, String> environment = new LinkedHashMap<>();
        settings.forEach((name, value) -> environment.put(name.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "_"), value));
        return environment;
    }

    /** "public", "web", "www", "" for the project folder itself, or null when nothing can be served. */
    static String documentRoot(Path app) {
        if (Files.isRegularFile(app.resolve("artisan")) && Files.isDirectory(app.resolve("public"))) {
            return "public";
        }
        for (String dir : List.of("public", "web", "www", "public_html", "htdocs", "")) {
            if (Files.isRegularFile(app.resolve(dir).resolve("index.php"))) {
                return dir;
            }
        }
        return null;
    }

    /**
     * The PHP the original runs on: the lowest release that it and every library its lock file installed
     * accept, and no older than 7.2, the oldest the image exists for in a usable state.
     */
    static String originalPhp(Path app) throws IOException {
        if (!Files.isRegularFile(app.resolve("composer.json"))) {
            // A site from before Composer says nowhere which PHP it runs on: the last PHP 7, unless the playbook's
            // settings name another image.
            return "7.4";
        }
        String lowest = "7.2";
        ComposerFile composer = ComposerFile.read(app.resolve("composer.json"));
        if (composer.php() != null && Versions.isBelow(lowest, composer.php())) {
            lowest = composer.php();
        }
        Path lock = app.resolve("composer.lock");
        if (Files.isRegularFile(lock)) {
            JsonNode root = JSON.readTree(Files.readString(lock));
            for (String section : new String[] {"packages", "packages-dev"}) {
                for (JsonNode p : root.path(section)) {
                    String needs = Constraints.lowest(p.path("require").path("php").asText(null));
                    if (needs != null && Versions.isBelow(lowest, needs)) {
                        lowest = needs;
                    }
                }
            }
        }
        String[] parts = lowest.split("\\.");
        return parts[0] + "." + (parts.length > 1 ? parts[1] : "0");
    }

    /** The original's libraries, as its lock file has them, whatever PHP this machine has. */
    private static void installOriginal(Path dir, Consumer<String> progress) throws IOException, InterruptedException {
        PhpRuntimes.Runtime php = PhpRuntimes.newest().orElseThrow(() -> new IOException("PHP is not installed; Composer runs on it"));
        Path composer = PhpRuntimes.composer().orElseThrow(() -> new IOException("Composer is not installed"));
        progress.accept("Installing the original application's dependencies");
        List<String> command = new ArrayList<>(PhpRuntimes.composerCommand(php, composer, Files.isRegularFile(dir.resolve("composer.lock"))
                ? "install" : "update", "--no-interaction", "--no-progress", "--no-scripts", "--ignore-platform-reqs"));
        Proc.Result install = Proc.run(command, dir, Duration.ofMinutes(30), PhpRuntimes.environment());
        if (!install.ok() && install.output().contains("security advisories")) {
            // Composer 2.9 refuses releases with known advisories; the original is run as it was, in a sandbox.
            command.add("--no-security-blocking");
            install = Proc.run(command, dir, Duration.ofMinutes(30), PhpRuntimes.environment());
        }
        if (!install.ok()) {
            throw new IOException("Could not install the original application's dependencies: " + install.tail(12));
        }
    }

    private static String handler(Path root, String action, String routesFile) {
        Matcher controller = CONTROLLER.matcher(action);
        if (controller.find()) {
            String name = controller.group(1).replaceAll("^.*\\\\", "");
            try (Stream<Path> files = Files.walk(root.resolve("app"))) {
                Optional<Path> file = files.filter(f -> f.getFileName().toString().equals(name + ".php")).findFirst();
                if (file.isPresent()) {
                    return root.relativize(file.get()).toString().replace('\\', '/');
                }
            } catch (IOException | UncheckedIOException e) {
                // No app folder: the routes file is where the route is.
            }
        }
        return routesFile;
    }

    private static String setting(Playbook playbook, String path, String fallback) {
        Object value = playbook.setting(path);
        return value == null ? fallback : value.toString();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    /** Kept so that the sandbox's helper stays on the classpath of this module's tests. */
    static boolean dockerAvailable() {
        return DockerSandbox.available();
    }
}
