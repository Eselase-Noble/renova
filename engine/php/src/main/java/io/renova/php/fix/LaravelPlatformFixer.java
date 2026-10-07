package io.renova.php.fix;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.playbook.Params;
import io.renova.core.spi.Fixer;
import io.renova.core.util.Proc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fix strategy {@code laravel-platform}: makes a PHP site that has no framework a Laravel application without
 * rewriting a page. The site's files move to a {@code legacy} folder inside a new Laravel application (the
 * skeleton {@code params.skeleton} names, {@code laravel/laravel:^13.0}), what a browser fetches as a file
 * (stylesheets, scripts, images) moves to {@code public}, and one controller answers every address no route
 * answers by running the page the site had there. The site then works as it did, at the addresses it had,
 * and its pages can become routes, controllers and views one at a time: by AI in the stage after this one,
 * or by a person.
 *
 * <p>A page is run in its own folder with PHP's own request variables, so it needs no change. Variables it
 * sets at the top of a file are no longer global, because the file is included by a method: the names the
 * site's functions ask for with {@code global} or {@code $GLOBALS} are found here and written to
 * {@code config/legacy.php}, and the controller makes those global before the page runs.
 */
public final class LaravelPlatformFixer implements Fixer {

    public static final String STRATEGY = "laravel-platform";
    public static final String LEGACY = "legacy";
    private static final Duration TIMEOUT = Duration.ofMinutes(20);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** What stays where it is: the copy's own history and scratch folder, and what Composer installed. */
    private static final Set<String> NOT_MOVED = Set.of(".git", ".renova", "vendor", "composer.json", "composer.lock",
            "renova-scenarios.yaml");
    /** Files a browser asks for by address and the web server answers without PHP. */
    private static final Set<String> ASSETS = Set.of("css", "js", "mjs", "map", "png", "jpg", "jpeg", "gif", "svg", "webp", "avif",
            "ico", "bmp", "woff", "woff2", "ttf", "eot", "otf", "pdf", "html", "htm", "mp3", "mp4", "webm", "ogg", "wav", "swf");
    private static final Set<String> ASSET_NAMES = Set.of("robots.txt", "sitemap.xml", "humans.txt", "ads.txt");
    /** Of the skeleton: an example page and its test, and guidance for tools that the site did not ask for. */
    private static final Set<String> NOT_COPIED = Set.of("README.md", "AGENTS.md", "CLAUDE.md", "resources/views/welcome.blade.php",
            "tests/Feature/ExampleTest.php", "composer.json");
    private static final Pattern GLOBAL = Pattern.compile("\\bglobal\\s+(\\$[^;]+);");
    private static final Pattern GLOBALS = Pattern.compile("\\$GLOBALS\\[\\s*['\"](\\w+)['\"]\\s*]");
    private static final Pattern VARIABLE = Pattern.compile("\\$(\\w+)");
    private static final String ROUTING = "health: '/up',";

    @Override
    public String strategy() {
        return STRATEGY;
    }

    @Override
    public StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception {
        Path root = context.workspace().root();
        if (Files.isRegularFile(root.resolve("artisan"))) {
            return StageResult.skipped(STRATEGY, "the project is a Laravel application already");
        }
        Params params = steps.getFirst().rule().fix().params(steps.getFirst().rule().id());
        String skeleton = params.optString("skeleton").orElse("laravel/laravel:^13.0");
        List<String> details = new ArrayList<>();
        Path template = skeleton(skeleton, details);
        if (template == null) {
            return new StageResult(STRATEGY, StageResult.Status.FAILED, "the Laravel skeleton " + skeleton + " could not be downloaded", details);
        }
        if (!Files.readString(template.resolve("bootstrap/app.php")).contains(ROUTING)) {
            return new StageResult(STRATEGY, StageResult.Status.FAILED, "the skeleton " + skeleton + " registers its routes in a way this "
                    + "version of Renova does not know (bootstrap/app.php has no " + ROUTING + ")", details);
        }

        // 1. The site as it is goes into the legacy folder, whole: its pages find their includes where they were.
        String docRoot = documentRoot(root);
        Path legacy = root.resolve(LEGACY);
        Path staging = root.resolve(".renova").resolve("legacy-staging");
        Files.createDirectories(staging);
        int moved = 0;
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.sorted().toList()) {
                if (!NOT_MOVED.contains(entry.getFileName().toString())) {
                    Files.move(entry, staging.resolve(entry.getFileName().toString()));
                    moved++;
                }
            }
        }
        Files.move(staging, legacy);
        Path site = docRoot.isEmpty() ? legacy : legacy.resolve(docRoot);
        details.add(moved + " file(s) and folder(s) of the site moved to " + LEGACY + "/; its pages are served from "
                + LEGACY + (docRoot.isEmpty() ? "" : "/" + docRoot));

        // 2. The Laravel application around it.
        int copied = 0;
        try (Stream<Path> files = Files.walk(template)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = template.relativize(file).toString().replace('\\', '/');
                if (NOT_COPIED.contains(relative) || relative.startsWith(".git/")) {
                    continue;
                }
                Path target = root.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            }
        }
        details.add(copied + " file(s) of " + skeleton + " written around it");

        // 3. What the web server serves as files: out of the legacy folder, into public, at the same addresses.
        List<String> assets = new ArrayList<>();
        try (Stream<Path> files = Files.walk(site)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
                String relative = site.relativize(file).toString().replace('\\', '/');
                if (!(ASSETS.contains(extension) || ASSET_NAMES.contains(name)) || produced(site.relativize(file))) {
                    continue;
                }
                Path target = root.resolve("public").resolve(relative);
                Files.createDirectories(target.getParent());
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                assets.add(relative);
            }
        }
        if (!assets.isEmpty()) {
            details.add(assets.size() + " file(s) the browser fetches moved to public/: " + summary(assets));
        }

        // 4. The bridge: a controller for every address without a route, and what it needs to know.
        Set<String> globals = globals(legacy);
        write(root, "app/Http/Controllers/LegacySiteController.php", CONTROLLER);
        write(root, "routes/legacy.php", ROUTES_LEGACY);
        write(root, "routes/web.php", ROUTES_WEB.formatted(params.optString("csrfMiddleware")
                .orElse("Illuminate\\Foundation\\Http\\Middleware\\PreventRequestForgery")));
        write(root, "config/legacy.php", CONFIG.formatted(LEGACY + (docRoot.isEmpty() ? "" : "/" + docRoot),
                globals.stream().map(g -> "'" + g + "'").collect(java.util.stream.Collectors.joining(", "))));
        Path bootstrap = root.resolve("bootstrap/app.php");
        Files.writeString(bootstrap, Files.readString(bootstrap).replace(ROUTING, ROUTING + "\n"
                + "        // Written by Renova: after every route of the application, the pages the site had before Laravel.\n"
                + "        then: function (): void {\n"
                + "            require __DIR__.'/../routes/legacy.php';\n"
                + "        },"));
        write(root, "tests/Feature/LegacySiteTest.php", TEST);
        Files.createDirectories(root.resolve("resources/views"));
        write(root, "resources/views/.gitkeep", "");
        write(root, LEGACY + "/README-RENOVA.md", README.formatted(docRoot.isEmpty() ? LEGACY : LEGACY + "/" + docRoot));
        details.add("app/Http/Controllers/LegacySiteController.php, routes/legacy.php and config/legacy.php written; every address "
                + "without a route runs the page the site had there");
        if (!globals.isEmpty()) {
            details.add("made global before a page runs, because the site's functions ask for them: $" + String.join(", $", globals));
        }

        // 5. composer.json: Laravel's, with what the site's own asked for.
        String name = root.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        details.addAll(composer(root, template, legacy, name.isEmpty() ? "app" : name));

        // 6. Settings for this copy: a key of its own. The file is not part of the history (.gitignore), as in any Laravel application.
        Path example = root.resolve(".env.example");
        if (Files.isRegularFile(example) && !Files.exists(root.resolve(".env"))) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            Files.writeString(root.resolve(".env"), Files.readString(example)
                    .replaceFirst("(?m)^APP_KEY=.*$", "APP_KEY=base64:" + Base64.getEncoder().encodeToString(key)));
            details.add(".env written from .env.example with a new application key; run 'php artisan migrate' before the first request "
                    + "(sessions and the cache of pages written for Laravel are kept in its database)");
        }
        for (String rewrites : List.of(".htaccess", "web.config", "nginx.conf")) {
            if (Files.isRegularFile(site.resolve(rewrites))) {
                details.add("to check by hand: " + LEGACY + "/" + (docRoot.isEmpty() ? "" : docRoot + "/") + rewrites + " is no longer read. "
                        + "Addresses it rewrote need a route, and what it denied access to is not served from " + LEGACY + " unless it ends in .php");
            }
        }
        details.add("the web server must point at public/ and send every address that is not a file there to public/index.php, "
                + "addresses ending in .php too (Apache does with the .htaccess in public/; for nginx, 'try_files $uri /index.php?$query_string' "
                + "for every location)");
        return new StageResult(STRATEGY, StageResult.Status.APPLIED, "the site runs inside a Laravel application (" + skeleton + "): its pages in "
                + LEGACY + "/, answered at the addresses they had", details);
    }

    /** "public", "web", ... or "" for the folder itself: where the site's index.php is, as the web server was pointed at it. */
    static String documentRoot(Path site) {
        for (String dir : List.of("public", "web", "www", "public_html", "htdocs")) {
            if (Files.isRegularFile(site.resolve(dir).resolve("index.php")) && !Files.isRegularFile(site.resolve("index.php"))) {
                return dir;
            }
        }
        return "";
    }

    /** The variables the site's functions take from the global scope, in every PHP file of the site. */
    static Set<String> globals(Path legacy) throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(legacy)) {
            for (Path file : files.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().matches("(?i).*\\.(php|inc|phtml|php5)")
                    && !produced(legacy.relativize(f))).toList()) {
                String code = Files.readString(file, StandardCharsets.ISO_8859_1);
                Matcher statement = GLOBAL.matcher(code);
                while (statement.find()) {
                    Matcher variable = VARIABLE.matcher(statement.group(1));
                    while (variable.find()) {
                        names.add(variable.group(1));
                    }
                }
                Matcher array = GLOBALS.matcher(code);
                while (array.find()) {
                    names.add(array.group(1));
                }
            }
        }
        names.removeAll(Set.of("GLOBALS", "_GET", "_POST", "_COOKIE", "_SESSION", "_SERVER", "_FILES", "_REQUEST", "_ENV", "this"));
        return names;
    }

    /**
     * Laravel's composer.json under the site's name. A site that had a composer.json of its own keeps the
     * libraries it required and its autoloading, with paths that now start in the legacy folder.
     */
    private static List<String> composer(Path root, Path template, Path legacy, String name) throws IOException {
        List<String> details = new ArrayList<>();
        ObjectNode composer = (ObjectNode) JSON.readTree(Files.readString(template.resolve("composer.json")));
        composer.put("name", "site/" + name);
        composer.put("description", "A site moved into Laravel by Renova; its pages from before are in the " + LEGACY + " folder.");
        composer.remove("keywords");
        Path own = root.resolve("composer.json");
        JsonNode site = Files.isRegularFile(own) ? JSON.readTree(Files.readString(own)) : JSON.createObjectNode();
        if (!site.path("description").asText("").startsWith("Written by Renova")) {
            if (site.hasNonNull("name")) {
                composer.put("name", site.get("name").asText());
            }
            for (String section : new String[] {"require", "require-dev"}) {
                ObjectNode target = (ObjectNode) composer.with(section);
                site.path(section).fields().forEachRemaining(e -> {
                    if (!e.getKey().equals("php") && !target.has(e.getKey())) {
                        target.set(e.getKey(), e.getValue());
                        details.add("composer.json: " + e.getKey() + " " + e.getValue().asText() + " kept from the site's own");
                    }
                });
            }
            for (String section : new String[] {"autoload", "autoload-dev"}) {
                for (String kind : new String[] {"psr-4", "psr-0", "classmap", "files"}) {
                    JsonNode entries = site.path(section).path(kind);
                    if (entries.isObject()) {
                        ObjectNode target = (ObjectNode) composer.with(section).with(kind);
                        entries.fields().forEachRemaining(e -> {
                            if (e.getValue().isArray()) {
                                ArrayNode paths = target.putArray(e.getKey());
                                e.getValue().forEach(p -> paths.add(LEGACY + "/" + p.asText()));
                            } else {
                                target.put(e.getKey(), LEGACY + "/" + e.getValue().asText());
                            }
                        });
                    } else if (entries.isArray()) {
                        ArrayNode target = composer.with(section).withArray(kind);
                        entries.forEach(p -> target.add(LEGACY + "/" + p.asText()));
                    }
                }
            }
            if (site.has("autoload")) {
                details.add("composer.json: the site's autoloading kept, from " + LEGACY + "/. A page that includes vendor/autoload.php "
                        + "by a path of its own must include base_path('vendor/autoload.php') or nothing: Laravel has loaded it");
            }
            Files.move(own, legacy.resolve("composer.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(root.resolve("composer.lock"));
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indent = new DefaultIndenter("    ", "\n");
        printer.indentObjectsWith(indent);
        printer.indentArraysWith(indent);
        Files.writeString(own, JSON.writer(printer).writeValueAsString(composer).replace("\" : ", "\": ") + "\n", StandardCharsets.UTF_8);
        return details;
    }

    /** The skeleton from Renova's cache folder, downloaded there with Composer on first use; null with the reason in details. */
    private static Path skeleton(String spec, List<String> details) throws IOException, InterruptedException {
        Path cached = RectorFixer.cacheDir().resolve("php-tools").resolve("skeleton-" + spec.replaceAll("[^A-Za-z0-9.]+", "-"));
        if (Files.isRegularFile(cached.resolve("bootstrap/app.php"))) {
            return cached;
        }
        PhpRuntimes.Runtime php = PhpRuntimes.newest().orElse(null);
        Path composer = PhpRuntimes.composer().orElse(null);
        if (php == null || composer == null) {
            details.add((php == null ? "PHP" : "Composer") + " was not found on this machine; the skeleton is downloaded with them "
                    + "(RENOVA_PHP and RENOVA_COMPOSER name other places)");
            return null;
        }
        Files.createDirectories(cached.getParent());
        Path download = cached.resolveSibling(cached.getFileName() + ".download-" + ProcessHandle.current().pid());
        String[] nameVersion = spec.split(":", 2);
        List<String> arguments = new ArrayList<>(List.of("create-project", nameVersion[0], download.toString()));
        if (nameVersion.length > 1) {
            arguments.add(nameVersion[1]);
        }
        arguments.addAll(List.of("--no-install", "--no-scripts", "--no-interaction", "--no-progress", "--ignore-platform-reqs"));
        Proc.Result created = Proc.run(PhpRuntimes.composerCommand(php, composer, arguments.toArray(String[]::new)), cached.getParent(),
                TIMEOUT, PhpRuntimes.environment());
        if (!created.ok() || !Files.isRegularFile(download.resolve("bootstrap/app.php"))) {
            details.add("composer create-project " + spec + " failed:");
            details.add(created.tail(12));
            return null;
        }
        try {
            Files.move(download, cached);
        } catch (IOException e) {
            // Another migration put it there first.
            if (!Files.isRegularFile(cached.resolve("bootstrap/app.php"))) {
                throw e;
            }
        }
        details.add("Laravel skeleton " + spec + " downloaded into " + cached);
        return cached;
    }

    private static boolean produced(Path relative) {
        for (Path part : relative) {
            if (Set.of("vendor", "node_modules", ".git").contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String summary(List<String> files) {
        List<String> shown = files.stream().sorted(Comparator.naturalOrder()).limit(8).toList();
        return String.join(", ", shown) + (files.size() > shown.size() ? ", and " + (files.size() - shown.size()) + " more" : "");
    }

    private static void write(Path root, String file, String content) throws IOException {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static final String ROUTES_WEB = """
            <?php

            use Illuminate\\Support\\Facades\\Route;

            // Routes of the pages that have been rewritten for Laravel. An address without a route here is answered
            // by the page the site had before, from the legacy folder (routes/legacy.php).

            // Pages of the site from before Laravel, rewritten: they accept the requests they always accepted, so
            // their forms are not checked for a CSRF token, as they were not before. To protect a form, add @csrf
            // to it and move its route out of this group.
            Route::withoutMiddleware([\\%s::class])->group(function () {
                //
            });
            """;

    private static final String ROUTES_LEGACY = """
            <?php

            use App\\Http\\Controllers\\LegacySiteController;
            use Illuminate\\Support\\Facades\\Route;

            // Written by Renova. Loaded after every other route and outside the "web" middleware group: the pages
            // of the site from before Laravel keep PHP's own sessions and cookies, and their forms carry no CSRF token.
            Route::any('{path?}', LegacySiteController::class)->where('path', '.*')->name('legacy');
            """;

    private static final String CONFIG = """
            <?php

            // Written by Renova: how the pages the site had before Laravel are served (LegacySiteController).
            return [

                // The folder the web server used to be pointed at, from the application's folder.
                'root' => '%s',

                // The page a folder's address shows.
                'index' => ['index.php'],

                // Files that are run as pages. Anything else in the folder is not served.
                'extensions' => ['php'],

                // Variables that are global while a page runs, because functions of the site ask for them with
                // "global" or $GLOBALS. A page is included by a method, so what it sets is otherwise local.
                'globals' => [%s],

            ];
            """;

    private static final String README = """
            # The site from before Laravel

            Renova moved this site into a Laravel application without rewriting its pages. They are in `%s`, and
            `App\\Http\\Controllers\\LegacySiteController` runs them at the addresses they always had: `/product.php?code=1`
            still runs `product.php`, from its own folder, with `$_GET`, `$_POST`, `$_COOKIE` and `$_SESSION` as PHP gives them.
            Stylesheets, scripts and images moved to `public/`, at the same addresses.

            To make a page a Laravel page: add a route for its address in `routes/web.php`, a controller, and a Blade view;
            then delete the page here. Routes are matched before the legacy pages, so the site can move one page at a time.

            Keep in mind while both kinds of page exist:

            - Pages here use PHP's own session (`session_start()`); Laravel pages use Laravel's. A value one kind stores,
              the other does not see. Move the pages that share a session together.
            - Forms here are not checked for a CSRF token. A page rewritten for Laravel is not either while its route is
              in the group `routes/web.php` starts with; add `@csrf` to its form and move the route out of the group.
            - A variable a page sets at the top of a file is global only if it is listed in `config/legacy.php`.
              Renova listed the ones the site's functions ask for with `global`.
            - The web server must point at `public/` and send every address that is not a file there to
              `public/index.php`, addresses that end in `.php` too.

            When this folder has no pages left, delete it with `LegacySiteController`, `routes/legacy.php`,
            `config/legacy.php` and the `then:` entry in `bootstrap/app.php`.
            """;

    private static final String TEST = """
            <?php

            namespace Tests\\Feature;

            use Tests\\TestCase;

            /** The application answers, and an address neither a route nor a page of the old site has is not found. */
            class LegacySiteTest extends TestCase
            {
                public function test_the_application_is_up(): void
                {
                    $this->get('/up')->assertStatus(200);
                }

                public function test_an_address_without_a_route_or_a_page_is_not_found(): void
                {
                    $this->get('/no-such-page-'.uniqid().'.php')->assertStatus(404);
                    $this->get('/../artisan')->assertStatus(404);
                    $this->get('/README-RENOVA.md')->assertStatus(404);
                }
            }
            """;

    private static final String CONTROLLER = """
            <?php

            namespace App\\Http\\Controllers;

            use Illuminate\\Http\\Request;
            use Illuminate\\Http\\Response;

            /**
             * Serves the pages the site had before it was a Laravel application, from the legacy folder, at the
             * addresses they always had. Written by Renova; see config/legacy.php.
             *
             * A page runs as PHP ran it: in its own folder, with $_GET, $_POST, $_COOKIE and $_SESSION as they are,
             * and what it prints is the response. Routes of the application are matched first, so a page that has
             * been rewritten as a route, a controller and a view is simply deleted from the legacy folder.
             */
            class LegacySiteController extends Controller
            {
                public function __invoke(Request $request, string $path = ''): Response
                {
                    $root = realpath(base_path(config('legacy.root', 'legacy')));
                    $script = $root === false ? null : $this->page($root, $path);
                    if ($script === null) {
                        abort(404);
                    }

                    $_SERVER['DOCUMENT_ROOT'] = $root;
                    $_SERVER['SCRIPT_FILENAME'] = $script;
                    $_SERVER['SCRIPT_NAME'] = $_SERVER['PHP_SELF'] = '/'.str_replace(DIRECTORY_SEPARATOR, '/', substr($script, strlen($root) + 1));

                    // A page that ends with exit ends the request here, as it always did: PHP sends what it printed.
                    $folder = getcwd();
                    $level = ob_get_level();
                    ob_start();
                    try {
                        chdir(dirname($script));
                        self::run($script, (array) config('legacy.globals', []));
                    } finally {
                        $output = '';
                        while (ob_get_level() > $level) {
                            $output = ob_get_clean().$output;
                        }
                        chdir($folder);
                    }

                    $response = new Response($output, http_response_code() ?: 200);
                    foreach (headers_list() as $header) {
                        [$name, $value] = array_pad(explode(':', $header, 2), 2, '');
                        // Cookies the page set are sent by PHP itself.
                        if (strcasecmp($name, 'Set-Cookie') !== 0) {
                            $response->headers->set($name, trim($value), false);
                        }
                    }

                    return $response;
                }

                /** The page an address names: a PHP file inside the legacy folder, or the index page of a folder there. */
                private function page(string $root, string $path): ?string
                {
                    $file = realpath($root.DIRECTORY_SEPARATOR.$path);
                    if ($file !== false && is_dir($file)) {
                        foreach ((array) config('legacy.index', ['index.php']) as $index) {
                            if (is_file($file.DIRECTORY_SEPARATOR.$index)) {
                                $file = realpath($file.DIRECTORY_SEPARATOR.$index);
                                break;
                            }
                        }
                    }
                    if ($file === false || ! is_file($file) || ! str_starts_with($file, $root.DIRECTORY_SEPARATOR)) {
                        return null;
                    }
                    $extension = strtolower(pathinfo($file, PATHINFO_EXTENSION));

                    return in_array($extension, (array) config('legacy.extensions', ['php']), true) ? $file : null;
                }

                /** Runs the page with nothing of this class in its scope, and the site's global variables in it. */
                private static function run(string $__page, array $__globals): void
                {
                    foreach ($__globals as $__name) {
                        global $$__name;
                    }
                    unset($__name, $__globals);

                    require $__page;
                }
            }
            """;
}
