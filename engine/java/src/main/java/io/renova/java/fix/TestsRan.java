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
        if (testClasses == 0) {
            return Optional.empty();
        }
        String file = workspace.relativize(buildRoot.resolve(buildFile)).toString().replace('\\', '/');
        if (testsRun(buildRoot, reports) > 0) {
            // Some ran. A build can still lose the rest: JUnit 4 tests beside new JUnit 5 ones are not run unless
            // the vintage engine is there, and nothing fails.
            java.util.List<String> missed = notRun(buildRoot, buildFile, reports);
            if (missed.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new BuildError(file, 0, "the build passes but " + missed.size() + " test class(es) did not run: "
                    + String.join(", ", missed.stream().limit(8).toList()) + (missed.size() > 8 ? ", …" : "")
                    + ". Tests of one framework are not found when the build runs another: JUnit 4 tests beside JUnit 5 "
                    + "ones need org.junit.vintage:junit-vintage-engine (test scope), or to be moved to JUnit 5."));
        }
        return Optional.of(new BuildError(file, 0, "the build passes but ran none of the project's tests (" + testClasses
                + " test class(es) under src/test). The build's test plugin does not find them, usually because it is "
                + "older than the test framework the tests now use: with JUnit 5, Maven needs maven-surefire-plugin 2.22 "
                + "or later and Gradle needs useJUnitPlatform()."));
    }

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    /** Settings that leave tests out on purpose; with them, a class without a report is not a finding. */
    private static final Pattern SELECTIVE = Pattern.compile("<excludes>|<includes>|<groups>|<excludedGroups>|<test>|<skipTests>|<skip>"
            + "|\\bexclude\\b|excludeTags|includeTags|\\bfilter\\s*\\{|excludeCategories|includeCategories");

    /**
     * Test classes that have tests and no report. For Maven only classes Surefire looks for by name are
     * counted; a build file that selects tests itself is not judged.
     */
    static java.util.List<String> notRun(Path buildRoot, String buildFile, String... reports) {
        boolean maven = buildFile.endsWith(".xml");
        java.util.Set<String> reported = new java.util.HashSet<>();
        java.util.List<String> missed = new java.util.ArrayList<>();
        try {
            if (SELECTIVE.matcher(Files.readString(buildRoot.resolve(buildFile))).find()) {
                return missed;
            }
            try (Stream<Path> files = Files.walk(buildRoot)) {
                for (Path report : files.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                    String path = report.toString().replace('\\', '/');
                    if (java.util.Arrays.stream(reports).anyMatch(folder -> path.contains("/" + folder + "/"))) {
                        reported.add(report.getFileName().toString().replaceFirst("^TEST-", "").replaceFirst("\\.xml$", ""));
                    }
                }
            }
            try (Stream<Path> files = Files.walk(buildRoot)) {
                for (Path test : files.filter(p -> p.toString().endsWith(".java")
                        && p.toString().replace('\\', '/').contains("/src/test/")).sorted().toList()) {
                    String name = test.getFileName().toString().replaceFirst("\\.java$", "");
                    if (maven && !name.matches("Test.*|.*Tests?|.*TestCase")) {
                        continue;
                    }
                    String source = Files.readString(test);
                    if (!TEST_METHOD.matcher(source).find() || Pattern.compile("\\babstract\\s+class\\s+" + name + "\\b").matcher(source).find()) {
                        continue;
                    }
                    Matcher pkg = PACKAGE.matcher(source);
                    String type = (pkg.find() ? pkg.group(1) + "." : "") + name;
                    // A class whose tests are all in nested classes is reported under their names.
                    if (reported.stream().noneMatch(r -> r.equals(type) || r.startsWith(type + "$"))) {
                        missed.add(type);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return java.util.List.of();
        }
        return missed;
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
