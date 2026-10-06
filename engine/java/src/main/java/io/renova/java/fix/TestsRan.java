package io.renova.java.fix;

import io.renova.core.engine.BuildError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Catches a build that passes because it stopped running the tests. A migration can do that without anyone
 * noticing: tests moved to JUnit 5 are not found by a build plugin that only knows JUnit 4, the build reports
 * success, and the tests that were supposed to prove the migration never ran.
 */
final class TestsRan {

    private static final Pattern SUITE_TESTS = Pattern.compile("<testsuite\\b[^>]*\\btests=\"(\\d+)\"");
    private static final Pattern TEST_METHOD = Pattern.compile("@(Test|ParameterizedTest|RepeatedTest|TestFactory)\\b");

    private TestsRan() {
    }

    /**
     * An error when the build root has test classes and its reports show that none of their tests ran.
     *
     * @param reports the folder names test reports are written to: surefire-reports and failsafe-reports for
     *                Maven, test-results for Gradle
     */
    static Optional<BuildError> check(Path workspace, Path buildRoot, String buildFile, String... reports) {
        long testClasses = testClasses(buildRoot);
        if (testClasses == 0 || testsRun(buildRoot, reports) > 0) {
            return Optional.empty();
        }
        String file = workspace.relativize(buildRoot.resolve(buildFile)).toString().replace('\\', '/');
        return Optional.of(new BuildError(file, 0, "the build passes but ran none of the project's tests (" + testClasses
                + " test class(es) under src/test). The build's test plugin does not find them, usually because it is "
                + "older than the test framework the tests now use: with JUnit 5, Maven needs maven-surefire-plugin 2.22 "
                + "or later and Gradle needs useJUnitPlatform()."));
    }

    static long testClasses(Path buildRoot) {
        try (Stream<Path> files = Files.walk(buildRoot)) {
            return files.filter(p -> p.toString().endsWith(".java") && p.toString().replace('\\', '/').contains("/src/test/"))
                    .filter(p -> {
                        try {
                            return TEST_METHOD.matcher(Files.readString(p)).find();
                        } catch (IOException | RuntimeException e) {
                            return false;
                        }
                    }).count();
        } catch (IOException e) {
            return 0;
        }
    }

    static long testsRun(Path buildRoot, String... reports) {
        long total = 0;
        try (Stream<Path> files = Files.walk(buildRoot)) {
            for (Path report : files.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                String path = report.toString().replace('\\', '/');
                if (java.util.Arrays.stream(reports).noneMatch(folder -> path.contains("/" + folder + "/"))) {
                    continue;
                }
                try {
                    Matcher m = SUITE_TESTS.matcher(Files.readString(report));
                    if (m.find()) {
                        total += Long.parseLong(m.group(1));
                    }
                } catch (IOException | RuntimeException e) {
                    // An unreadable report counts for nothing.
                }
            }
        } catch (IOException e) {
            return 0;
        }
        return total;
    }
}
