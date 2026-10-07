package io.renova.php.fix;

import io.renova.core.engine.BuildError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComposerJsonTest {

    private static final String JSON = """
            {
              "name": "nikic/fast-route",
              "autoload": {
                "psr-4": {
                  "FastRoute\\\\": "src/"
                }
              },
              "require": {
                "php": ">=5.4.0"
              },
              "require-dev": {
                "fzaninotto/faker": "^1.9",
                "phpunit/phpunit": "^4.8.35|~5.7"
              },
              "config": { "platform": { "php": "5.6.40" }, "require": { "php": "not this one" } }
            }
            """;

    @Test
    void editsConstraintsWhereTheyAreAndNothingElse() {
        // A key that ends in an escaped backslash, as every PSR-4 namespace does, comes before the sections.
        String php = ComposerJson.setConstraint(JSON, "php", "^8.4");
        assertThat(php).contains("\"php\": \"^8.4\"\n  },").contains("\"php\": \"not this one\"").contains("\"php\": \"5.6.40\"");
        assertThat(ComposerJson.setPlatformPhp(php, "8.4.0")).contains("\"platform\": { \"php\": \"8.4.0\" }");
        assertThat(ComposerJson.setConstraint(JSON, "phpunit/phpunit", "^9.6")).contains("\"phpunit/phpunit\": \"^9.6\"");
        assertThat(ComposerJson.setConstraint(JSON, "not/required", "^1.0")).isEqualTo(JSON);
        assertThat(ComposerJson.requires(JSON, "fzaninotto/faker")).isTrue();
    }

    @Test
    void addsAndRemovesPackagesKeepingTheJsonValid() {
        String replaced = ComposerJson.add(ComposerJson.remove(JSON, "fzaninotto/faker"), "fakerphp/faker", "^1.23", true);
        assertThat(replaced).contains("""
                  "require-dev": {
                    "phpunit/phpunit": "^4.8.35|~5.7",
                    "fakerphp/faker": "^1.23"
                  },
                """);
        // Removing the last entry takes the comma off the one before it.
        assertThat(ComposerJson.remove(JSON, "phpunit/phpunit")).contains("""
                  "require-dev": {
                    "fzaninotto/faker": "^1.9"
                  },
                """);
        assertThat(ComposerJson.add(JSON, "phpunit/phpunit", "^9", true)).isEqualTo(JSON);
        String withDev = ComposerJson.add("{\n    \"require\": {\n        \"php\": \"^8.1\"\n    }\n}\n", "phpunit/phpunit", "^11.5", true);
        assertThat(withDev).isEqualTo("""
                {
                    "require": {
                        "php": "^8.1"
                    },
                    "require-dev": {
                        "phpunit/phpunit": "^11.5"
                    }
                }
                """);
    }

    @Test
    void writesARectorConfigurationForTheProjectAlone() {
        String config = RectorFixer.config(Path.of("/work/app"), new LinkedHashSet<>(List.of(
                "Rector\\Set\\ValueObject\\LevelSetList::UP_TO_PHP_84", "composer-based:phpunit", "Acme\\Rector\\OwnRector")),
                List.of("test/fixtures/hack.php"));
        assertThat(config).contains("->withPaths(['/work/app'])", "'*/vendor/*'", ", '/work/app/test/fixtures/hack.php'])",
                "        \\Rector\\Set\\ValueObject\\LevelSetList::UP_TO_PHP_84,", "        \\Acme\\Rector\\OwnRector::class,",
                "->withComposerBased(phpunit: true);");
    }

    @Test
    void readsSyntaxErrorsComposerProblemsAndTestResults(@TempDir Path ws) throws Exception {
        assertThat(PhpVerifier.syntaxErrors("""
                No syntax errors detected in /work/ws/src/A.php
                PHP Parse error:  syntax error, unexpected token "{", expecting "(" in /work/ws/src/B.php on line 12
                Parse error: syntax error, unexpected token "{", expecting "(" in /work/ws/src/B.php on line 12
                """, Path.of("/work/ws"))).containsExactly(new BuildError("src/B.php", 12, "syntax error, unexpected token \"{\", expecting \"(\""));

        assertThat(PhpVerifier.composerProblem("""
                Loading composer repositories with package information
                Your requirements could not be resolved to an installable set of packages.

                  Problem 1
                    - Root composer.json requires laravel/tinker ^2.8 -> satisfiable by laravel/tinker[v2.8.0, ..., v2.11.1].
                """)).startsWith("Your requirements could not be resolved").contains("Problem 1 | - Root composer.json requires laravel/tinker ^2.8");

        Files.createDirectories(ws.resolve("app/Models"));
        Files.writeString(ws.resolve("app/Models/Order.php"), "<?php\n");
        Path report = ws.resolve("junit.xml");
        Files.writeString(report, """
                <testsuites><testsuite name="Feature">
                  <testcase name="test_lists_orders" file="%1$s/tests/Feature/OrdersTest.php" line="40" class="Tests\\Feature\\OrdersTest" time="0.01"/>
                  <testcase name="test_total" file="%1$s/tests/Unit/OrderTest.php" line="10" class="Tests\\Unit\\OrderTest" time="0.01">
                    <error type="TypeError">Tests\\Unit\\OrderTest::test_total
                TypeError: number_format(): Argument #1 ($num) must be of type float, string given

                %1$s/vendor/laravel/framework/src/Illuminate/Database/Eloquent/Model.php:2100
                %1$s/app/Models/Order.php:27
                %1$s/tests/Unit/OrderTest.php:13</error>
                  </testcase>
                </testsuite></testsuites>
                """.formatted(ws.toRealPath()));

        PhpVerifier.TestRun run = PhpVerifier.testResults(report, ws);

        assertThat(run.ran()).isEqualTo(2);
        // Blamed on the project code that threw, not the framework above it nor the test below.
        assertThat(run.failures()).containsExactly(new BuildError("app/Models/Order.php", 27,
                "test OrderTest::test_total failed: TypeError: number_format(): Argument #1 ($num) must be of type float, string given"));
        assertThat(run.failures().getFirst().fromFailedTest()).isTrue();
    }

    @Test
    void anOldPhpUnitConfigurationIsRewrittenForPhpUnit10() {
        String old = """
                <phpunit backupGlobals="false"
                         backupStaticAttributes="false"
                         colors="true"
                         convertErrorsToExceptions="true"
                         syntaxCheck="false"
                         bootstrap="test/bootstrap.php"
                        >
                    <testsuites>
                        <testsuite name="Tests">
                            <directory>./test/</directory>
                        </testsuite>
                    </testsuites>

                    <filter>
                        <whitelist processUncoveredFilesFromWhitelist="true">
                            <directory>./src/</directory>
                        </whitelist>
                    </filter>
                </phpunit>
                """;
        assertThat(PhpUnitConfigFixer.modernise(old)).isEqualTo("""
                <phpunit backupGlobals="false"
                         backupStaticProperties="false"
                         colors="true"
                         bootstrap="test/bootstrap.php"
                        >
                    <testsuites>
                        <testsuite name="Tests">
                            <directory>./test/</directory>
                        </testsuite>
                    </testsuites>

                    <source>
                        <include>
                            <directory>./src/</directory>
                        </include>
                    </source>
                </phpunit>
                """);
        String current = "<phpunit bootstrap=\"vendor/autoload.php\" colors=\"true\">\n    <source><include><directory>app</directory></include></source>\n</phpunit>\n";
        assertThat(PhpUnitConfigFixer.modernise(current)).isEqualTo(current);
    }
}
