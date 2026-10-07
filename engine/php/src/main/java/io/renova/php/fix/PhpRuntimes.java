package io.renova.php.fix;

import io.renova.core.util.Proc;
import io.renova.core.util.Versions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The PHP interpreters on this machine and Composer. A migration is verified on the PHP it targets, which is
 * rarely the one on the PATH, so Renova looks where PHP versions are usually kept side by side.
 */
public final class PhpRuntimes {

    /** @param version as the interpreter reports it, e.g. "8.4.23" */
    public record Runtime(Path executable, String version) {
        public String minor() {
            String[] parts = version.split("\\.");
            return parts.length < 2 ? version : parts[0] + "." + parts[1];
        }
    }

    private static List<Runtime> cached;

    private PhpRuntimes() {
    }

    /**
     * Every PHP found: the folders in {@code RENOVA_PHP}, the PATH (php, php8.4, ...), {@code ~/.local/share/renova/php},
     * and the places Homebrew, Herd, phpbrew, asdf, XAMPP and Laragon install to. Oldest first.
     */
    public static synchronized List<Runtime> installed() {
        if (cached != null) {
            return cached;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String home = System.getProperty("user.home");
        List<Path> dirs = new ArrayList<>();
        for (String variable : List.of("RENOVA_PHP", "PATH")) {
            for (String dir : System.getenv().getOrDefault(variable, "").split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) {
                    dirs.add(Path.of(dir));
                }
            }
        }
        dirs.addAll(List.of(Path.of("/usr/bin"), Path.of("/usr/local/bin"), Path.of("/opt/homebrew/bin")));
        for (String parent : List.of(home + "/.local/share/renova/php", home + "/.phpbrew/php", home + "/.asdf/installs/php",
                "/opt/homebrew/opt", "/usr/local/opt", home + "/Library/Application Support/Herd/bin", "C:\\laragon\\bin\\php",
                "C:\\xampp", "C:\\php", "C:\\tools")) {
            Path dir = Path.of(parent);
            dirs.add(dir);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> children = Files.list(dir)) {
                    children.filter(Files::isDirectory).filter(c -> c.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains("php")
                            || c.getFileName().toString().matches("\\d.*")).forEach(c -> {
                        dirs.add(c);
                        dirs.add(c.resolve("bin"));
                    });
                } catch (IOException | java.io.UncheckedIOException e) {
                    // A folder that cannot be listed holds no PHP we can run.
                }
            }
        }
        Map<String, Runtime> byRealPath = new LinkedHashMap<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.filter(f -> f.getFileName().toString().matches(windows ? "php\\.exe" : "php(\\d(\\.?\\d)?)?")).toList()) {
                    if (!Files.isExecutable(file) || Files.isDirectory(file)) {
                        continue;
                    }
                    String real = file.toRealPath().toString();
                    if (!byRealPath.containsKey(real)) {
                        version(file).ifPresent(v -> byRealPath.put(real, new Runtime(file, v)));
                    }
                }
            } catch (IOException | java.io.UncheckedIOException e) {
                // Not a folder we can read.
            }
        }
        cached = byRealPath.values().stream().sorted((a, b) -> Versions.compare(a.version(), b.version())).toList();
        return cached;
    }

    /** The PHP to verify a migration to {@code target} ("8.4") on: that release, else the oldest newer one. */
    public static Optional<Runtime> forTarget(String target) {
        return installed().stream().filter(r -> !Versions.isBelow(r.minor(), target)).findFirst();
    }

    /** The newest PHP, for running tools such as Rector, which need a current one whatever the project targets. */
    public static Optional<Runtime> newest() {
        return installed().stream().max(Comparator.comparing(Runtime::version, Versions::compare));
    }

    public static String describe() {
        return installed().isEmpty() ? "none found" : String.join(", ", installed().stream().map(r -> "PHP " + r.version()
                + " (" + r.executable() + ")").toList());
    }

    /**
     * Composer: {@code RENOVA_COMPOSER}, else {@code composer} or {@code composer.phar} on the PATH. It is run by
     * the PHP a step needs rather than by whichever PHP its own launcher would pick.
     */
    public static Optional<Path> composer() {
        String named = System.getenv("RENOVA_COMPOSER");
        if (named != null && !named.isBlank() && Files.isRegularFile(Path.of(named))) {
            return Optional.of(Path.of(named));
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            for (String name : List.of("composer.phar", "composer")) {
                Path file = dir.isBlank() ? null : Path.of(dir, name);
                if (file != null && Files.isRegularFile(file)) {
                    return Optional.of(file);
                }
            }
        }
        return Optional.empty();
    }

    /** No questions, no time limit a slow mirror would hit, and no refusal to run as root in a container. */
    public static Map<String, String> environment() {
        return Map.of("COMPOSER_NO_INTERACTION", "1", "COMPOSER_ALLOW_SUPERUSER", "1", "COMPOSER_MEMORY_LIMIT", "-1",
                "COMPOSER_PROCESS_TIMEOUT", "1800", "COMPOSER_DISABLE_XDEBUG_WARN", "1");
    }

    /** The command that runs Composer on a given PHP. */
    public static List<String> composerCommand(Runtime php, Path composer, String... arguments) {
        List<String> command = new ArrayList<>(List.of(php.executable().toString(), composer.toString()));
        command.addAll(List.of(arguments));
        return command;
    }

    private static Optional<String> version(Path php) {
        try {
            Proc.Result result = Proc.run(List.of(php.toString(), "-r", "echo PHP_VERSION;"), php.toAbsolutePath().getParent(),
                    Duration.ofSeconds(20));
            String out = result.output().strip();
            return result.ok() && out.matches("\\d+\\.\\d+\\.\\d+.*") ? Optional.of(out.replaceAll("^(\\d+\\.\\d+\\.\\d+).*", "$1")) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
