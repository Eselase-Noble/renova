package io.renova.php.behaviour;

import io.renova.core.behaviour.Route;
import io.renova.core.behaviour.Scenario;
import io.renova.core.model.ProjectModel;
import io.renova.php.PhpPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** Which PHP applications are run side by side, with which requests and on which PHP; nothing here needs Docker. */
class PhpBehaviourRunnerTest {

    private final PhpBehaviourRunner runner = new PhpBehaviourRunner();

    private static void write(Path root, String file, String content) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    @Test
    void findsALaravelApplicationsRoutesAndTheControllersBehindThem(@TempDir Path root) throws Exception {
        write(root, "composer.json", "{ \"require\": { \"php\": \"^7.3|^8.0\", \"laravel/framework\": \"^8.75\" } }");
        write(root, "artisan", "#!/usr/bin/env php\n");
        write(root, "public/index.php", "<?php\n");
        write(root, "app/Http/Controllers/OrderController.php", "<?php\n");
        write(root, "routes/web.php", """
                <?php
                Route::get('/', function () { return view('welcome'); });
                // Route::get('/old', 'OldController@index');
                Route::view('/about', 'about');
                """);
        write(root, "routes/api.php", """
                <?php
                Route::get('/orders', [\\App\\Http\\Controllers\\OrderController::class, 'index'])->name('orders.index');
                Route::post('/orders', [OrderController::class, 'store']);
                Route::get('/orders/{id}', 'OrderController@show');
                Route::get('/reports/{name?}', [ReportController::class, 'show']);
                """);
        ProjectModel model = new PhpPlugin().model(root);

        assertThat(runner.unsupported(model)).isEmpty();
        List<Route> routes = runner.routes(model, root);
        assertThat(routes).extracting(Route::method, Route::template, Route::handlerFile).containsExactly(
                tuple("GET", "/", "routes/web.php"),
                tuple("GET", "/about", "routes/web.php"),
                tuple("GET", "/api/orders", "app/Http/Controllers/OrderController.php"),
                tuple("POST", "/api/orders", "app/Http/Controllers/OrderController.php"),
                tuple("GET", "/api/orders/{id}", "app/Http/Controllers/OrderController.php"),
                tuple("GET", "/api/reports/{name?}", "routes/api.php"));
        // Every GET once, with something in place of the variables.
        assertThat(runner.discover(model, root)).extracting(s -> s.steps().getFirst().path())
                .containsExactly("/", "/about", "/api/orders", "/api/orders/1", "/api/reports/sample");
        assertThat(PhpBehaviourRunner.documentRoot(root)).isEqualTo("public");
    }

    @Test
    void aLibraryIsNotRunAndAPlainSiteIsServedFromWhereItsIndexIs(@TempDir Path root) throws Exception {
        Path library = root.resolve("library");
        write(library, "composer.json", "{ \"require\": { \"php\": \">=5.4\" } }");
        write(library, "src/Router.php", "<?php\n");
        assertThat(runner.unsupported(new PhpPlugin().model(library))).get().asString().contains("no web application to run");

        Path site = root.resolve("site");
        write(site, "composer.json", "{ \"require\": { \"php\": \">=5.6\" } }");
        write(site, "index.php", "<?php echo 'home';\n");
        write(site, "contact.php", "<?php echo 'contact';\n");
        write(site, "vendor/autoload.php", "<?php\n");
        ProjectModel model = new PhpPlugin().model(site);
        assertThat(runner.unsupported(model)).isEmpty();
        assertThat(PhpBehaviourRunner.documentRoot(site)).isEmpty();
        assertThat(runner.discover(model, site)).extracting(Scenario::id, s -> s.steps().getFirst().path())
                .containsExactly(tuple("s1", "/"), tuple("s2", "/contact.php"));
    }

    @Test
    void theOriginalRunsOnTheOldestPhpItAndItsLibrariesAccept(@TempDir Path root) throws Exception {
        write(root, "composer.json", "{ \"require\": { \"php\": \"^7.3|^8.0\" } }");
        assertThat(PhpBehaviourRunner.originalPhp(root)).isEqualTo("7.3");
        // One installed library needs more than the project asks for.
        write(root, "composer.lock", """
                { "packages": [ { "name": "a/b", "version": "1.0.0", "require": { "php": ">=7.2.5" } },
                                { "name": "c/d", "version": "2.0.0", "require": { "php": "^8.0.2" } } ], "packages-dev": [] }
                """);
        assertThat(PhpBehaviourRunner.originalPhp(root)).isEqualTo("8.0");
        // Images of PHP 5 are too old to be worth running anything on.
        write(root.resolve("old"), "composer.json", "{ \"require\": { \"php\": \">=5.4.0\" } }");
        assertThat(PhpBehaviourRunner.originalPhp(root.resolve("old"))).isEqualTo("7.2");
    }

    @Test
    void findsASymfonyApplicationsRoutesWrittenEitherWay(@TempDir Path root) throws Exception {
        write(root, "composer.json", "{ \"require\": { \"php\": \">=7.2.5\", \"symfony/framework-bundle\": \"5.4.*\" } }");
        write(root, "public/index.php", "<?php\n");
        write(root, "src/Controller/NoteController.php", """
                <?php
                namespace App\\Controller;

                /**
                 * @Route("/notes")
                 */
                class NoteController
                {
                    /**
                     * @Route("/", name="note_list", methods={"GET"})
                     */
                    public function list() {}

                    #[Route(path: '/{number}', name: 'note_show', requirements: ['number' => '\\d+'], methods: ['GET', 'HEAD'])]
                    public function show(int $number) {}

                    #[\\Symfony\\Component\\Routing\\Attribute\\Route('/search')]
                    public function search() {}
                }
                """);
        ProjectModel model = new PhpPlugin().model(root);

        assertThat(runner.routes(model, root)).extracting(Route::method, Route::template).containsExactly(
                tuple("GET", "/notes"), tuple("GET", "/notes/{number}"), tuple("HEAD", "/notes/{number}"), tuple(null, "/notes/search"));
        assertThat(runner.discover(model, root)).extracting(s -> s.steps().getFirst().path())
                .containsExactly("/", "/notes", "/notes/1", "/notes/search");
    }

    @Test
    void aFileOtherPagesIncludeIsNotAPageAndAMovedSitesPagesAreFoundInItsLegacyFolder(@TempDir Path tmp) throws Exception {
        Path site = Files.createDirectories(tmp.resolve("site"));
        write(site, "index.php", "<?php require 'includes/db.php'; include_once(dirname(__FILE__) . '/includes/layout.php');\n");
        write(site, "view.php", "<?php require 'includes/db.php';\n");
        write(site, "includes/db.php", "<?php function db() {}\n");
        write(site, "includes/layout.php", "<?php function top() {}\n");
        PhpBehaviourRunner runner = new PhpBehaviourRunner();

        assertThat(runner.routes(new io.renova.php.PhpPlugin().model(site), site)).extracting(Route::template)
                .containsExactly("/view.php");

        Path app = Files.createDirectories(tmp.resolve("app"));
        write(app, "artisan", "#!/usr/bin/env php\n");
        write(app, "composer.json", "{ \"require\": { \"laravel/framework\": \"^13.0\" } }");
        write(app, "config/legacy.php", "<?php return ['root' => 'legacy'];\n");
        write(app, "routes/web.php", "<?php\nRoute::match(['get', 'post'], '/new.php', [AppointmentController::class, 'create']);\n");
        write(app, "app/Http/Controllers/AppointmentController.php", "<?php\n");
        write(app, "legacy/view.php", "<?php require 'includes/db.php';\n");
        write(app, "legacy/includes/db.php", "<?php function db() {}\n");

        assertThat(runner.routes(new io.renova.php.PhpPlugin().model(app), app)).extracting(Route::template, Route::handlerFile)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("/new.php", "app/Http/Controllers/AppointmentController.php"),
                        org.assertj.core.groups.Tuple.tuple("/view.php", "legacy/view.php"));
    }
}
