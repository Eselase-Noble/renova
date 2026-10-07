package io.renova.php;

import io.renova.core.engine.Analyzer;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Playbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the plugin reads from Composer projects, and what the bundled playbooks find in them. */
class PhpPluginTest {

    private static void write(Path root, String file, String content) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static Path laravel8(Path root) throws Exception {
        write(root, "composer.json", """
                {
                    "name": "acme/shop",
                    "autoload": { "psr-4": { "App\\\\": "app/" } },
                    "require": {
                        "php": "^7.3|^8.0",
                        "ext-json": "*",
                        "fruitcake/laravel-cors": "^2.0",
                        "laravel/framework": "^8.75",
                        "laravel/tinker": "^2.5"
                    },
                    "require-dev": {
                        "facade/ignition": "^2.5",
                        "phpunit/phpunit": "^9.5.10"
                    }
                }
                """);
        write(root, "composer.lock", """
                { "packages": [ { "name": "laravel/framework", "version": "v8.83.27" }, { "name": "laravel/tinker", "version": "v2.10.1" } ],
                  "packages-dev": [ { "name": "phpunit/phpunit", "version": "9.6.38" } ] }
                """);
        write(root, "app/Http/Kernel.php", "<?php\nuse Fruitcake\\Cors\\HandleCors;\n");
        write(root, "vendor/laravel/framework/composer.json", "{ \"require\": { \"php\": \"^5.6\" } }");
        write(root, "vendor/x/y/src/Old.php", "<?php mysql_connect('h', 'u', 'p');\n");
        return root;
    }

    @Test
    void readsTheProjectFromComposerAndIgnoresWhatComposerInstalled(@TempDir Path root) throws Exception {
        List<Module> modules = new PhpPlugin().model(laravel8(root)).modules();

        assertThat(modules).hasSize(1);
        Module app = modules.getFirst();
        assertThat(app.name()).isEqualTo("acme/shop");
        assertThat(app.fact("php")).isEqualTo("7.3");
        assertThat(app.fact("framework")).isEqualTo("laravel 8");
        // Installed versions where the lock file has them, else the lowest the constraint allows; never extensions.
        assertThat(app.fact("packages")).isEqualTo(Map.of("fruitcake/laravel-cors", "2.0", "laravel/framework", "8.83.27",
                "laravel/tinker", "2.10.1", "facade/ignition", "2.5", "phpunit/phpunit", "9.6.38"));
    }

    @Test
    void aLaravelApplicationIsOfferedLaravelAndALibraryAPhpVersion(@TempDir Path root) throws Exception {
        PluginRegistry registry = PluginRegistry.load();
        Path app = laravel8(root.resolve("app"));
        Path library = root.resolve("library");
        write(library, "composer.json", "{ \"require\": { \"php\": \">=5.4.0\" }, \"require-dev\": { \"phpunit/phpunit\": \"^4.8.35|~5.7\" } }");

        assertThat(registry.defaultPlaybook(app).id()).isEqualTo("laravel-13");
        assertThat(registry.defaultPlaybook(library).id()).isEqualTo("php-to-8.4");
        assertThat(registry.playbooksFor(library)).extracting(Playbook::id)
                .contains("php-to-8.3", "php-to-8.4", "php-to-8.5", "laravel-11", "laravel-12", "laravel-13");

        List<Finding> findings = new Analyzer(registry).analyze(app, registry.defaultPlaybook(app)).findings();
        assertThat(findings).extracting(Finding::ruleId).contains("php-version", "laravel-framework", "laravel-code", "cors-package",
                "cors-middleware", "laravel-ignition", "phpunit-9", "phpunit-tests");
        // Tinker 2.10.1 is installed, and its constraint ^2.5 still cannot reach the release Laravel 13 needs.
        assertThat(findings).extracting(Finding::ruleId).contains("laravel-first-party");
        // Nothing under vendor is the project's code.
        assertThat(findings).noneMatch(f -> f.file().startsWith("vendor/"));

        assertThat(new Analyzer(registry).analyze(library, registry.defaultPlaybook(library)).findings())
                .extracting(Finding::ruleId).containsExactlyInAnyOrder("php-version", "php-language", "phpunit-9", "phpunit-tests");
    }

    @Test
    void readsConstraintsAndKnowsTests() {
        assertThat(Constraints.lowest("^7.4|^8.0")).isEqualTo("7.4");
        assertThat(Constraints.lowest(">=5.4.0")).isEqualTo("5.4");
        assertThat(Constraints.lowest("~5.6.0 || ^7.0")).isEqualTo("5.6");
        assertThat(Constraints.lowest(">=7.2.5 <8.0")).isEqualTo("7.2.5");
        assertThat(Constraints.lowest("^4.8.35|~5.7")).isEqualTo("4.8.35");
        assertThat(Constraints.lowest("8.*")).isEqualTo("8.0");
        assertThat(Constraints.lowest("*")).isNull();
        assertThat(Constraints.lowest("dev-main")).isNull();

        PhpPlugin plugin = new PhpPlugin();
        assertThat(plugin.isTestFile("tests/Feature/OrdersTest.php")).isTrue();
        assertThat(plugin.isTestFile("test/Dispatcher/DispatcherTest.php")).isTrue();
        assertThat(plugin.isTestFile("src/RouteTest.php")).isTrue();
        assertThat(plugin.isTestFile("app/Models/Order.php")).isFalse();
        assertThat(plugin.isTestFile("src/Contest/Entry.php")).isFalse();
    }

    @Test
    void findsTheProjectsOwnClassesAFileUses(@TempDir Path root) throws Exception {
        write(root, "composer.json", "{ \"require\": { \"php\": \"^7.2\" } }");
        write(root, "src/Quote.php", """
                <?php
                namespace Acme\\Quotes;

                use Acme\\Shared\\Clock as Time;
                use Psr\\Log\\LoggerInterface;

                class Quote extends Document implements Priced
                {
                    public function summary(LoggerInterface $log)
                    {
                        return Money::plain($this->total()) . Time::now() . new Rate(1);
                    }
                }
                """);
        write(root, "src/Money.php", "<?php\nnamespace Acme\\Quotes;\nclass Money {}\n");
        write(root, "src/Document.php", "<?php\nnamespace Acme\\Quotes;\nabstract class Document {}\n");
        write(root, "src/Priced.php", "<?php\nnamespace Acme\\Quotes;\ninterface Priced {}\n");
        write(root, "src/Rate.php", "<?php\nnamespace Acme\\Quotes;\nfinal class Rate {}\n");
        write(root, "lib/Clock.php", "<?php\nnamespace Acme\\Shared;\nclass Clock {}\n");
        write(root, "src/Unused.php", "<?php\nnamespace Acme\\Quotes;\nclass Unused {}\n");
        write(root, "vendor/psr/log/LoggerInterface.php", "<?php\nnamespace Psr\\Log;\ninterface LoggerInterface {}\n");
        PhpPlugin plugin = new PhpPlugin();

        assertThat(plugin.referencedFiles(plugin.model(root), root, "src/Quote.php")).extracting(io.renova.core.spi.RelatedFile::path)
                .containsExactlyInAnyOrder("lib/Clock.php", "src/Document.php", "src/Priced.php", "src/Money.php", "src/Rate.php");
        assertThat(plugin.referencedFiles(plugin.model(root), root, "src/Quote.php")).allMatch(r -> !r.editable());
    }

    @Test
    void aSiteWithoutComposerIsAProjectAndGetsAComposerFileInTheCopy(@TempDir Path root) throws Exception {
        Path site = root.resolve("Old Shop");
        write(site, "index.php", "<?php echo 'home';\n");
        write(site, "includes/db.php", "<?php\n");
        PhpPlugin plugin = new PhpPlugin();

        assertThat(plugin.supports(site)).isTrue();
        assertThat(plugin.model(site).modules()).extracting(Module::buildFile, m -> m.fact("php")).containsExactly(
                org.assertj.core.groups.Tuple.tuple("composer.json", null));
        assertThat(PluginRegistry.load().defaultPlaybook(site).id()).isEqualTo("php-to-8.4");

        assertThat(plugin.prepare(site, Map.of())).get().extracting(io.renova.core.engine.StageResult::stage).isEqualTo("prepare");
        assertThat(Files.readString(site.resolve("composer.json"))).contains("\"name\": \"site/old-shop\"", "\"php\": \">=5.3\"");
        // Now an ordinary Composer project: nothing more to prepare.
        assertThat(plugin.prepare(site, Map.of())).isEmpty();
        assertThat(plugin.model(site).modules().getFirst().fact("php")).isEqualTo("5.3");

        // PHP files only further down: not a project Renova can place.
        Path nested = root.resolve("repo");
        write(nested, "apps/site/pages/index.php", "<?php\n");
        assertThat(plugin.supports(nested)).isFalse();
        assertThat(plugin.unsupportedReason(nested)).get().asString().contains("Point it at the folder");
    }
}
