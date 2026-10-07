package io.renova.php.fix;

import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.VerifyResult;
import io.renova.core.model.Module;
import io.renova.core.spi.Verifier;
import io.renova.core.util.Proc;
import io.renova.php.PhpPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Verifies a migrated PHP project on the PHP it targets: Composer resolves and installs the dependencies its
 * composer.json now asks for, every source file is checked to parse, and the project's tests are run with
 * PHPUnit or Pest. PHP has no compiler to catch the rest, so the tests carry more weight than in other ecosystems.
 */
public final class PhpVerifier implements Verifier {

    private static final Duration TIMEOUT = Duration.ofMinutes(60);
    private static final Pattern SYNTAX = Pattern.compile("(?:PHP )?(Parse|Fatal|Compile) error:\\s+(.*?) in (.+?) on line (\\d+)");
    private static final Pattern TESTCASE = Pattern.compile("(?s)<testcase\\b([^>]*?)(/>|>(.*?)</testcase>)");
    private static final Pattern FRAME = Pattern.compile("(?m)^\\s*(?:#\\d+\\s+)?(/[^\\s:(]+\\.php|[A-Za-z]:\\\\[^\\s:(]+\\.php)[:(](\\d+)");

    @Override
    public void preflight(MigrationContext context) {
        String target = target(context);
        String how = " Install it, or tell Renova where it is: folders in RENOVA_PHP are searched for php, beside the PATH and "
                + "~/.local/share/renova/php. Found: " + PhpRuntimes.describe() + ". Use --no-verify to migrate without checking.";
        if (target != null && PhpRuntimes.forTarget(target).isEmpty()) {
            throw new IllegalStateException("This migration targets PHP " + target + ", and no PHP " + target + " or newer is installed." + how);
        }
        if (PhpRuntimes.newest().isEmpty()) {
            throw new IllegalStateException("PHP is not installed on this machine, and the migrated project is checked with it." + how);
        }
        if (PhpRuntimes.composer().isEmpty()) {
            throw new IllegalStateException("Composer is not installed on this machine (https://getcomposer.org); the migrated project's "
                    + "dependencies are installed with it. RENOVA_COMPOSER names a composer.phar elsewhere.");
        }
    }

    @Override
    public VerifyResult verify(MigrationContext context) throws Exception {
        Path workspace = context.workspace().root();
        String target = target(context);
        List<String> needed = neededExtensions(workspace);
        PhpRuntimes.Runtime php = (target == null ? PhpRuntimes.newest() : PhpRuntimes.forTarget(target, needed))
                .orElseThrow(() -> new IOException("No PHP " + target + " or newer is installed"));
        Path composer = PhpRuntimes.composer().orElseThrow(() -> new IOException("Composer is not installed"));
        boolean skipTests = "true".equals(context.options().toolOption("verify.skipTests"));
        List<BuildError> errors = new ArrayList<>();
        StringBuilder log = new StringBuilder("Verified on PHP ").append(php.version()).append(" (").append(php.executable()).append(")\n");
        if (!php.missing(needed).isEmpty()) {
            log.append(VerifyResult.NOTE).append("PHP ").append(php.version()).append(" at ").append(php.executable()).append(" lacks ")
                    .append(String.join(", ", php.missing(needed))).append(", which the project or its tests use: failures that name a ")
                    .append("missing driver or function come from this machine, not from the migration. Install the extension, or put a ")
                    .append("PHP that has it in RENOVA_PHP.\n");
        }
        boolean success = true;
        int ran = 0;
        boolean expected = false;
        List<String> warnings = new ArrayList<>();
        int number = 0;
        for (Module module : new PhpPlugin().model(workspace).modules()) {
            Path dir = workspace.resolve(module.path()).normalize();
            String composerJson = module.buildFile();

            // 1. The dependencies the project now asks for, resolved for the target PHP.
            Proc.Result update = Proc.run(PhpRuntimes.composerCommand(php, composer, "update", "--no-interaction", "--no-progress", "-W"),
                    dir, TIMEOUT, PhpRuntimes.environment());
            log.append("== ").append(module.path()).append(": composer update exit ").append(update.exitCode()).append('\n');
            if (!update.ok()) {
                success = false;
                log.append(update.tail(40)).append('\n');
                errors.add(new BuildError(composerJson, 0, composerProblem(update.output())));
                continue;
            }

            // 2. Every file of the project parses on the target PHP.
            List<BuildError> syntax = lint(php, dir, workspace);
            if (!syntax.isEmpty()) {
                success = false;
                errors.addAll(syntax);
                log.append(syntax.size()).append(" file(s) do not parse\n");
                continue;
            }

            // 3. The project's own tests.
            Path runner = Files.isRegularFile(dir.resolve("vendor/bin/pest")) ? dir.resolve("vendor/bin/pest") : dir.resolve("vendor/bin/phpunit");
            if (skipTests || !Files.isRegularFile(runner)) {
                continue;
            }
            expected |= hasTests(dir);
            prepareLaravel(php, dir, log);
            Path results = context.workspace().lcDir().resolve("test-results").resolve(++number + ".xml");
            Files.createDirectories(results.getParent());
            Files.deleteIfExists(results);
            Proc.Result test = Proc.run(List.of(php.executable().toString(), "-d", "memory_limit=-1", runner.toString(),
                    "--log-junit", results.toString()), dir, TIMEOUT, PhpRuntimes.environment());
            log.append("== ").append(module.path()).append(": ").append(runner.getFileName()).append(" exit ").append(test.exitCode()).append('\n');
            TestRun run = testResults(results, workspace);
            ran += run.ran();
            if (!test.ok() && run.failures().isEmpty() && run.ran() > 0 && test.output().contains("OK, but there were issues!")) {
                // Every test passed; the runner has remarks about the suite itself (its configuration, how classes are named).
                Matcher remark = Pattern.compile("(?m)^\\d+\\) (.+)$").matcher(test.output());
                while (remark.find() && warnings.size() < 4) {
                    warnings.add(remark.group(1).replace(workspace.toString() + "/", "").strip());
                }
            } else if (!test.ok()) {
                success = false;
                log.append(test.tail(40)).append('\n');
                errors.addAll(run.failures().isEmpty() ? List.of(new BuildError(composerJson, 0, said(test.output()))) : run.failures());
            }
        }
        if (success && expected && !skipTests && ran == 0) {
            success = false;
            errors.add(new BuildError("composer.json", 0, "the project has tests and none of them ran: the test runner found nothing to run"));
        }
        if (success) {
            log.append(VerifyResult.NOTE).append("Checked on PHP ").append(php.version()).append(": dependencies install, every file parses")
                    .append(ran > 0 ? ", and " + ran + " test(s) ran and none failed." : skipTests ? "; tests were not run."
                            : ". The project has no tests to run: nothing shows that the code still does what it did.").append('\n');
        }
        if (success && !warnings.isEmpty()) {
            log.append(VerifyResult.NOTE).append("The test runner has remarks that failed no test: ").append(String.join(" | ", warnings)).append('\n');
        }
        return new VerifyResult(success, errors.stream().distinct().toList(), log.toString());
    }

    /**
     * Extensions the project says it needs (ext-* in composer.json) and the database driver its tests are
     * configured for, to choose between several installations of the same PHP and to explain a failure.
     */
    static List<String> neededExtensions(Path workspace) throws IOException {
        List<String> needed = new ArrayList<>();
        Path composer = workspace.resolve("composer.json");
        if (Files.isRegularFile(composer)) {
            Matcher ext = Pattern.compile("\"ext-([a-z0-9_]+)\"\\s*:").matcher(Files.readString(composer));
            while (ext.find()) {
                needed.add(ext.group(1).replace("zend-opcache", "zend opcache"));
            }
        }
        for (String config : List.of("phpunit.xml", "phpunit.xml.dist")) {
            Path file = workspace.resolve(config);
            if (Files.isRegularFile(file)) {
                String xml = Files.readString(file).replaceAll("(?s)<!--.*?-->", "");
                for (String[] driver : new String[][] {{"sqlite", "pdo_sqlite"}, {"mysql", "pdo_mysql"}, {"pgsql", "pdo_pgsql"}}) {
                    if (xml.matches("(?s).*DB_CONNECTION\"\\s+value=\"" + driver[0] + "\".*") && !needed.contains(driver[1])) {
                        needed.add(driver[1]);
                    }
                }
            }
        }
        return needed;
    }

    static String target(MigrationContext context) {
        return context.playbook().targets().get("php");
    }

    /** Runs {@code php -l} over the project's own files; PHP 8.3 and later take many files at once. */
    static List<BuildError> lint(PhpRuntimes.Runtime php, Path dir, Path workspace) throws IOException, InterruptedException {
        List<String> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(f -> f.toString().endsWith(".php") && Files.isRegularFile(f) && !PhpPlugin.produced(dir.relativize(f))
                    && !hack(f)).map(Path::toString).sorted().toList();
        }
        List<BuildError> errors = new ArrayList<>();
        for (int from = 0; from < files.size(); from += 200) {
            List<String> command = new ArrayList<>(List.of(php.executable().toString(), "-d", "display_errors=1", "-d", "log_errors=0", "-l"));
            command.addAll(files.subList(from, Math.min(files.size(), from + 200)));
            Proc.Result result = Proc.run(command, dir, TIMEOUT);
            if (!result.ok()) {
                errors.addAll(syntaxErrors(result.output(), workspace));
            }
        }
        return errors;
    }

    /** A file in Hack, Facebook's dialect: named .php by old convention and not PHP (test fixtures, mostly). */
    private static boolean hack(Path file) {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(8), StandardCharsets.ISO_8859_1).stripLeading().startsWith("<?hh");
        } catch (IOException e) {
            return false;
        }
    }

    static List<BuildError> syntaxErrors(String output, Path workspace) {
        List<BuildError> errors = new ArrayList<>();
        Matcher m = SYNTAX.matcher(output);
        while (m.find()) {
            BuildError error = new BuildError(relative(m.group(3), workspace), Integer.parseInt(m.group(4)), m.group(2).strip());
            if (!errors.contains(error)) {
                errors.add(error);
            }
        }
        return errors;
    }

    /** What Composer said when it could not find versions that fit together. */
    static String composerProblem(String output) {
        List<String> lines = output.lines().toList();
        int from = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("could not be resolved") || lines.get(i).strip().startsWith("Problem 1")) {
                from = i;
                break;
            }
        }
        List<String> said = (from < 0 ? lines : lines.subList(from, lines.size())).stream().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("Use the option") && !l.startsWith("You can also try")).limit(12).toList();
        return said.isEmpty() ? "composer update failed without saying why; see the log" : String.join(" | ", said);
    }

    record TestRun(int ran, List<BuildError> failures) {
    }

    /** Reads the JUnit report PHPUnit or Pest wrote: how many tests ran, and each failure with where it threw. */
    static TestRun testResults(Path report, Path workspace) throws IOException {
        if (!Files.isRegularFile(report)) {
            return new TestRun(0, List.of());
        }
        int ran = 0;
        List<BuildError> failures = new ArrayList<>();
        PhpPlugin plugin = new PhpPlugin();
        Matcher testcase = TESTCASE.matcher(Files.readString(report, StandardCharsets.UTF_8));
        while (testcase.find()) {
            ran++;
            String body = testcase.group(3) == null ? "" : testcase.group(3);
            Matcher problem = Pattern.compile("(?s)<(failure|error)\\b[^>]*>(.*?)</\\1>").matcher(body);
            if (!problem.find()) {
                continue;
            }
            String text = unescape(problem.group(2)).strip();
            String file = relative(attribute(testcase.group(1), "file"), workspace);
            int line = attribute(testcase.group(1), "line").isEmpty() ? 0 : Integer.parseInt(attribute(testcase.group(1), "line"));
            // The project code that threw, where the trace names it, rather than the test that called it.
            Matcher frame = FRAME.matcher(text);
            while (frame.find()) {
                String at = relative(frame.group(1), workspace);
                if (!Path.of(at).isAbsolute() && !PhpPlugin.produced(Path.of(at)) && !plugin.isTestFile(at)) {
                    file = at;
                    line = Integer.parseInt(frame.group(2));
                    break;
                }
            }
            String name = attribute(testcase.group(1), "class").replaceAll("^.*\\\\", "") + "::" + attribute(testcase.group(1), "name");
            String message = text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith(attribute(testcase.group(1), "class")))
                    .findFirst().orElse(text).replaceAll("\\s+", " ");
            failures.add(new BuildError(file.isEmpty() ? null : file, line, "test " + name + " failed: "
                    + (message.length() > 400 ? message.substring(0, 400) + "…" : message)));
        }
        return new TestRun(ran, failures);
    }

    /**
     * A Laravel application's tests need an environment file with an application key. A project keeps
     * .env.example in version control and .env out of it, so the copy has none until this makes one.
     */
    private static void prepareLaravel(PhpRuntimes.Runtime php, Path dir, StringBuilder log) throws IOException, InterruptedException {
        if (!Files.isRegularFile(dir.resolve("artisan")) || Files.exists(dir.resolve(".env")) || !Files.isRegularFile(dir.resolve(".env.example"))) {
            return;
        }
        Files.copy(dir.resolve(".env.example"), dir.resolve(".env"));
        Proc.Result key = Proc.run(List.of(php.executable().toString(), "artisan", "key:generate", "--force", "--no-interaction"), dir,
                Duration.ofMinutes(5));
        log.append("Created .env from .env.example for the tests").append(key.ok() ? "" : " (no application key: " + said(key.output()) + ")").append('\n');
    }

    private static boolean hasTests(Path dir) throws IOException {
        for (String name : List.of("tests", "test", "Tests")) {
            Path tests = dir.resolve(name);
            if (Files.isDirectory(tests)) {
                try (Stream<Path> walk = Files.walk(tests)) {
                    if (walk.anyMatch(f -> f.getFileName().toString().endsWith("Test.php"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String said(String output) {
        List<String> lines = output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        List<String> errors = lines.stream().filter(l -> l.toLowerCase(java.util.Locale.ROOT).contains("error") || l.contains("Exception")).limit(4).toList();
        return errors.isEmpty() ? (lines.isEmpty() ? "no output" : lines.getLast()) : String.join(" | ", errors);
    }

    private static String relative(String file, Path workspace) {
        if (file == null || file.isBlank()) {
            return "";
        }
        Path path = Path.of(file.strip());
        Path root = workspace.toAbsolutePath().normalize();
        try {
            root = root.toRealPath();
            path = path.isAbsolute() && Files.exists(path) ? path.toRealPath() : path;
        } catch (IOException e) {
            // Compared as written.
        }
        return path.isAbsolute() && path.normalize().startsWith(root) ? root.relativize(path.normalize()).toString().replace('\\', '/')
                : file.strip().replace('\\', '/');
    }

    private static String attribute(String attributes, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attributes);
        return m.find() ? unescape(m.group(1)) : "";
    }

    private static String unescape(String xml) {
        return xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#039;", "'")
                .replace("&amp;", "&");
    }
}
