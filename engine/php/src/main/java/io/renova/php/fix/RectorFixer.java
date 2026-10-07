package io.renova.php.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.spi.Fixer;
import io.renova.core.util.Proc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fix strategy {@code rector}: runs Rector, which rewrites PHP from its syntax tree as OpenRewrite does Java. A
 * rule's {@code recipes} are Rector set constants ({@code Rector\Set\ValueObject\LevelSetList::UP_TO_PHP_84}),
 * single rule classes, or {@code composer-based:phpunit}: the rules Rector picks itself for the version of a
 * library the project has installed. All of a stage's rules run in one pass.
 *
 * <p>The stage runs after the {@code composer} one (a playbook's composer rules are in categories A and E, its
 * Rector rules in B and C), so the libraries Rector reads, and chooses rules by, are the ones the project is
 * moving to.
 *
 * <p>Rector is not added to the project. It is installed once per version into Renova's cache folder, from the
 * packages the playbook names in {@code settings.rector.packages}, and run from there on the newest PHP on the
 * machine. The project's own dependencies are installed first where they are missing, without running their
 * scripts, so that Rector knows the types the code uses.
 */
public final class RectorFixer implements Fixer {

    private static final Duration TIMEOUT = Duration.ofMinutes(60);
    private static final java.util.regex.Pattern COULD_NOT_PROCESS = java.util.regex.Pattern.compile("Could not process \"([^\"]+)\" file");
    private static final List<String> DEFAULT_PACKAGES = List.of("rector/rector:2.7.0", "driftingly/rector-laravel:2.6.2");

    public static final String STRATEGY = "rector";

    @Override
    public String strategy() {
        return STRATEGY;
    }

    @Override
    public StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception {
        Path root = context.workspace().root();
        PhpRuntimes.Runtime php = PhpRuntimes.newest().orElse(null);
        Path composer = PhpRuntimes.composer().orElse(null);
        if (php == null || composer == null) {
            return new StageResult(STRATEGY, StageResult.Status.FAILED, (php == null ? "PHP" : "Composer") + " was not found on this "
                    + "machine; Rector, which rewrites the code, runs on them (RENOVA_PHP and RENOVA_COMPOSER name other places)", List.of());
        }
        List<String> details = new ArrayList<>();
        Path rector = install(context, php, composer, details);
        if (rector == null) {
            return new StageResult(STRATEGY, StageResult.Status.FAILED, "Rector could not be installed", details);
        }
        Set<String> recipes = new LinkedHashSet<>();
        steps.forEach(s -> recipes.addAll(s.rule().fix().recipes()));

        int applied = 0;
        boolean failed = false;
        for (io.renova.core.model.Module module : context.project().modules()) {
            Path dir = root.resolve(module.path()).normalize();
            boolean installed = Files.isRegularFile(dir.resolve("vendor/composer/installed.json"));
            if (!installed) {
                // The types the code uses, for Rector to read: what composer.json asks for now, on whatever PHP is here.
                Proc.Result install = Proc.run(PhpRuntimes.composerCommand(php, composer, "update", "--no-interaction", "--no-progress",
                        "--no-scripts", "--no-plugins", "--ignore-platform-reqs", "-W"), dir, TIMEOUT, PhpRuntimes.environment());
                installed = install.ok() && Files.isRegularFile(dir.resolve("vendor/composer/installed.json"));
                details.add(module.path() + ": the project's dependencies were " + (installed ? "installed for Rector to read"
                        : "not installable as composer.json asks for them now; Rector ran without them, and without the rules it "
                        + "chooses by installed version: " + PhpVerifier.composerProblem(install.output())));
            }
            Path config = context.workspace().lcDir().resolve("rector-" + module.name().replaceAll("\\W", "_") + ".php");
            boolean known = installed;
            Set<String> used = recipes.stream().filter(r -> known || !r.startsWith("composer-based:"))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            // A file Rector cannot read (a fixture in another language, a template) stops the whole run: it is
            // left as it is and the run repeated, a few times at most.
            List<String> unreadable = new ArrayList<>();
            Proc.Result run;
            for (int attempt = 0; ; attempt++) {
                Files.writeString(config, config(dir, used, unreadable), StandardCharsets.UTF_8);
                run = Proc.run(List.of(php.executable().toString(), "-d", "memory_limit=-1", rector.toString(), "process",
                        "--config", config.toString(), "--no-progress-bar", "--no-diffs", "--clear-cache"), dir, TIMEOUT, PhpRuntimes.environment());
                List<String> more = new ArrayList<>();
                java.util.regex.Matcher m = COULD_NOT_PROCESS.matcher(run.output().replaceAll("\\s*\\R\\s*", " "));
                while (m.find()) {
                    if (!unreadable.contains(m.group(1)) && !more.contains(m.group(1))) {
                        more.add(m.group(1));
                    }
                }
                if (run.ok() || more.isEmpty() || attempt == 5) {
                    break;
                }
                unreadable.addAll(more);
            }
            if (!unreadable.isEmpty()) {
                details.add(module.path() + ": left as they are, because Rector could not read them as PHP: " + String.join(", ", unreadable));
            }
            details.add("== " + module.path() + ": exit " + run.exitCode());
            details.add(run.tail(12));
            if (run.ok()) {
                applied++;
            } else {
                failed = true;
            }
        }
        return new StageResult(STRATEGY, failed ? (applied == 0 ? StageResult.Status.FAILED : StageResult.Status.PARTIAL)
                : StageResult.Status.APPLIED, recipes.size() + " Rector set(s) or rule(s) on " + applied + "/" + context.project().modules().size()
                + " project(s)", details);
    }

    /** The Rector configuration for one run: the whole project but what is not its own code. */
    static String config(Path dir, Set<String> recipes) {
        return config(dir, recipes, List.of());
    }

    static String config(Path dir, Set<String> recipes, List<String> skipped) {
        String skip = skipped.stream().map(f -> ", '" + dir.resolve(f).toString().replace("\\", "\\\\").replace("'", "\\'") + "'")
                .collect(java.util.stream.Collectors.joining());
        String composerBased = recipes.stream().filter(r -> r.startsWith("composer-based:")).map(r -> r.substring("composer-based:".length()) + ": true")
                .collect(java.util.stream.Collectors.joining(", "));
        recipes = recipes.stream().filter(r -> !r.startsWith("composer-based:")).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> sets = recipes.stream().filter(r -> r.contains("::")).map(r -> "        \\" + r.replaceFirst("^\\\\", "") + ",").toList();
        List<String> rules = recipes.stream().filter(r -> !r.contains("::")).map(r -> "        \\" + r.replaceFirst("^\\\\", "") + "::class,").toList();
        return """
                <?php

                declare(strict_types=1);

                // Written by Renova for one migration; not part of the project.
                return \\Rector\\Config\\RectorConfig::configure()
                    ->withPaths([%s])
                    ->withSkip(['*/vendor/*', '*/node_modules/*', '*/storage/*', '*/bootstrap/cache/*', '*/.renova/*', '*.blade.php'%s])
                    ->withSets([
                %s
                    ])
                    ->withRules([
                %s
                    ])%s;
                """.formatted("'" + dir.toString().replace("\\", "\\\\").replace("'", "\\'") + "'", skip, String.join("\n", sets), String.join("\n", rules),
                composerBased.isEmpty() ? "" : "\n    ->withComposerBased(" + composerBased + ")");
    }

    /** Rector from the cache folder, installed there on first use; null (with the reason in details) if it cannot be. */
    private static Path install(MigrationContext context, PhpRuntimes.Runtime php, Path composer, List<String> details) throws Exception {
        List<String> packages = DEFAULT_PACKAGES;
        if (context.playbook().setting("rector.packages") instanceof List<?> named && !named.isEmpty()) {
            packages = named.stream().map(Object::toString).toList();
        }
        Path tools = cacheDir().resolve("php-tools").resolve(Integer.toHexString(String.join(",", packages).hashCode()));
        Path rector = tools.resolve("vendor/bin/rector");
        if (Files.isRegularFile(rector)) {
            return rector;
        }
        Files.createDirectories(tools);
        StringBuilder json = new StringBuilder("{\n    \"require\": {\n");
        for (int i = 0; i < packages.size(); i++) {
            String[] nameVersion = packages.get(i).split(":", 2);
            json.append("        \"").append(nameVersion[0]).append("\": \"").append(nameVersion.length > 1 ? nameVersion[1] : "*")
                    .append(i + 1 < packages.size() ? "\",\n" : "\"\n");
        }
        Files.writeString(tools.resolve("composer.json"), json.append("    },\n    \"config\": { \"allow-plugins\": false }\n}\n"), StandardCharsets.UTF_8);
        Proc.Result install = Proc.run(PhpRuntimes.composerCommand(php, composer, "update", "--no-interaction", "--no-progress", "--no-dev"),
                tools, TIMEOUT, PhpRuntimes.environment());
        if (!install.ok() || !Files.isRegularFile(rector)) {
            details.add("Installing " + packages + " into " + tools + " with PHP " + php.version() + " failed:");
            details.add(install.tail(15));
            return null;
        }
        details.add("Rector installed into " + tools);
        return rector;
    }

    static Path cacheDir() {
        if (System.getenv("RENOVA_CACHE_HOME") != null) {
            return Path.of(System.getenv("RENOVA_CACHE_HOME"));
        }
        if (System.getenv("XDG_CACHE_HOME") != null && !System.getenv("XDG_CACHE_HOME").isBlank()) {
            return Path.of(System.getenv("XDG_CACHE_HOME"), "renova");
        }
        if (System.getenv("LOCALAPPDATA") != null) {
            return Path.of(System.getenv("LOCALAPPDATA"), "renova", "cache");
        }
        return Path.of(System.getProperty("user.home"), ".cache", "renova");
    }
}
