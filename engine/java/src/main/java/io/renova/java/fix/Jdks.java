package io.renova.java.fix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The JDKs installed on this machine. A migration needs more than one: the target's to build the result, and
 * sometimes an older one to run the project's own build tool before it is upgraded (a Gradle 7 wrapper does
 * not start on Java 21). Renova looks where JDKs are usually installed and picks the right one for each step,
 * so nobody has to switch JAVA_HOME by hand in the middle of a migration.
 */
public final class Jdks {

    /** @param feature the feature version: 8, 11, 17, 21, 25 */
    public record Jdk(Path home, int feature) {
    }

    private static final Pattern RELEASE = Pattern.compile("^JAVA_VERSION=\"(?:1\\.)?(\\d+)");

    private Jdks() {
    }

    /** JAVA_HOME and the JDK running Renova first, then every JDK found in the usual places; one per directory. */
    public static List<Jdk> installed() {
        return installed(System.getenv("JAVA_HOME"), Path.of(System.getProperty("user.home")), System.getenv("RENOVA_JDKS"));
    }

    /** @param extra more directories to look in, separated by the path separator (RENOVA_JDKS) */
    static List<Jdk> installed(String javaHome, Path userHome, String extra) {
        List<Path> candidates = new ArrayList<>();
        if (javaHome != null && !javaHome.isBlank()) {
            candidates.add(Path.of(javaHome));
        }
        candidates.add(Path.of(System.getProperty("java.home")));
        List<Path> parents = new ArrayList<>(List.of(userHome.resolve(".jdks"), userHome.resolve(".sdkman/candidates/java"),
                userHome.resolve(".gradle/jdks"), userHome.resolve(".m2/jdks"), Path.of("/usr/lib/jvm"), Path.of("/usr/java"),
                Path.of("/opt/java"), Path.of("/Library/Java/JavaVirtualMachines"), Path.of("C:\\Program Files\\Java"),
                Path.of("C:\\Program Files\\Eclipse Adoptium"), Path.of("C:\\Program Files\\Zulu")));
        if (extra != null && !extra.isBlank()) {
            for (String dir : extra.split(java.io.File.pathSeparator)) {
                candidates.add(Path.of(dir));
                parents.add(Path.of(dir));
            }
        }
        for (Path parent : parents) {
            if (!Files.isDirectory(parent)) {
                continue;
            }
            try (Stream<Path> children = Files.list(parent)) {
                children.sorted().forEach(child -> {
                    candidates.add(child);
                    // macOS bundles and some archives keep the JDK one or two levels down.
                    candidates.add(child.resolve("Contents/Home"));
                    try (Stream<Path> nested = Files.isDirectory(child) ? Files.list(child) : Stream.empty()) {
                        nested.filter(Files::isDirectory).forEach(candidates::add);
                    } catch (IOException e) {
                        // Unreadable: not a JDK we can use.
                    }
                });
            } catch (IOException e) {
                // Unreadable: skip.
            }
        }
        // Several names often lead to one JDK (symbolic links in /usr/lib/jvm); keep the first of each.
        Map<Path, Jdk> found = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            read(candidate).ifPresent(jdk -> {
                try {
                    found.putIfAbsent(jdk.home().toRealPath(), jdk);
                } catch (IOException e) {
                    found.putIfAbsent(jdk.home(), jdk);
                }
            });
        }
        return List.copyOf(found.values());
    }

    /** The directory as a JDK, if it has a compiler and says which version it is. */
    static Optional<Jdk> read(Path home) {
        if (!Files.isRegularFile(home.resolve("bin/javac")) && !Files.isRegularFile(home.resolve("bin/javac.exe"))) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(home.resolve("release"))) {
                Matcher m = RELEASE.matcher(line);
                if (m.find()) {
                    return Optional.of(new Jdk(home, Integer.parseInt(m.group(1))));
                }
            }
        } catch (IOException | RuntimeException e) {
            // No release file (very old JDKs): fall through.
        }
        return Optional.empty();
    }

    /**
     * The JDK to build a project for Java {@code target} with: exactly that version if it is installed (what the
     * project will run on), otherwise the oldest newer one, which can compile for it.
     */
    public static Optional<Jdk> forTarget(List<Jdk> jdks, int target) {
        return jdks.stream().filter(j -> j.feature() >= target).min(Comparator.comparingInt(Jdk::feature));
    }

    /** The newest installed JDK a Gradle of this version can run on. */
    public static Optional<Jdk> forGradle(List<Jdk> jdks, String gradleVersion) {
        int max = newestJavaFor(gradleVersion);
        return jdks.stream().filter(j -> j.feature() <= max && j.feature() >= 8).max(Comparator.comparingInt(Jdk::feature));
    }

    /** The newest Java a Gradle version runs on, from Gradle's compatibility table. */
    public static int newestJavaFor(String gradleVersion) {
        int[][] table = {{9, 1, 25}, {8, 14, 24}, {8, 10, 23}, {8, 8, 22}, {8, 5, 21}, {8, 3, 20}, {7, 6, 19}, {7, 5, 18},
                {7, 3, 17}, {7, 0, 16}, {6, 7, 15}, {6, 3, 14}, {6, 0, 13}, {5, 4, 12}, {5, 0, 11}, {4, 7, 10}, {4, 3, 9}};
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(gradleVersion);
        if (!m.find()) {
            return Integer.MAX_VALUE;
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        for (int[] row : table) {
            if (major > row[0] || (major == row[0] && minor >= row[1])) {
                return row[2];
            }
        }
        return 8;
    }

    /** The oldest Gradle release line Renova moves a wrapper to so that it runs on Java {@code target}. */
    public static String gradleFor(int target) {
        return target >= 25 ? "9.1.0" : "8.14.3";
    }

    public static String describe(List<Jdk> jdks) {
        return jdks.isEmpty() ? "none found" : jdks.stream().sorted(Comparator.comparingInt(Jdk::feature))
                .map(j -> "Java " + j.feature() + " (" + j.home() + ")").distinct().reduce((a, b) -> a + ", " + b).orElse("");
    }
}
