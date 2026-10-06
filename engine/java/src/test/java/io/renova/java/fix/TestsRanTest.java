package io.renova.java.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A build that passes without running the project's tests is not a passing build. */
class TestsRanTest {

    private static void testClass(Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("src/test/java/x"));
        Files.writeString(dir.resolve("CatalogueTest.java"), "package x;\nclass CatalogueTest {\n    @Test\n    void adds() {}\n}\n");
        Files.writeString(dir.resolve("Fixtures.java"), "package x;\nclass Fixtures {}\n");
    }

    private static void report(Path root, String folder, int tests) throws Exception {
        Path dir = Files.createDirectories(root.resolve(folder));
        Files.writeString(dir.resolve("TEST-x.CatalogueTest.xml"),
                "<?xml version=\"1.0\"?>\n<testsuite name=\"x.CatalogueTest\" time=\"0.01\" tests=\"" + tests + "\" errors=\"0\" failures=\"0\"/>\n");
    }

    @Test
    void testsThatExistButDidNotRunFailTheBuild(@TempDir Path root) throws Exception {
        testClass(root);
        // Surefire 2.12 with JUnit 5 tests: a report that says nothing ran.
        report(root, "target/surefire-reports", 0);
        assertThat(TestsRan.check(root, root, "pom.xml", "surefire-reports", "failsafe-reports")).hasValueSatisfying(error -> {
            assertThat(error.file()).isEqualTo("pom.xml");
            assertThat(error.message()).contains("ran none of the project's tests", "1 test class(es)", "maven-surefire-plugin 2.22");
        });
        // And no report at all.
        assertThat(TestsRan.check(root, root, "build.gradle", "test-results")).isPresent();
    }

    @Test
    void aTestClassLeftOutWhileOthersRunFailsTheBuild(@TempDir Path root) throws Exception {
        testClass(root);
        Path dir = root.resolve("src/test/java/x");
        // A JUnit 4 test beside a JUnit 5 one, a helper that is not looked for by name, and an abstract base.
        Files.writeString(dir.resolve("LegacyTest.java"), "package x;\npublic class LegacyTest {\n    @Test\n    public void old() {}\n}\n");
        Files.writeString(dir.resolve("Checks.java"), "package x;\nclass Checks {\n    @Test\n    void notFoundByName() {}\n}\n");
        Files.writeString(dir.resolve("BaseTest.java"), "package x;\nabstract class BaseTest {\n    @Test\n    void shared() {}\n}\n");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        report(root, "target/surefire-reports", 1);
        assertThat(TestsRan.check(root, root, "pom.xml", "surefire-reports", "failsafe-reports")).hasValueSatisfying(error ->
                assertThat(error.message()).contains("1 test class(es) did not run: x.LegacyTest", "junit-vintage-engine"));
        // A build that chooses its tests is not second-guessed.
        Files.writeString(root.resolve("pom.xml"), "<project><excludes><exclude>**/Legacy*</exclude></excludes></project>");
        assertThat(TestsRan.check(root, root, "pom.xml", "surefire-reports", "failsafe-reports")).isEmpty();
    }

    @Test
    void testsThatRanAreFine(@TempDir Path root) throws Exception {
        testClass(root);
        report(root, "target/surefire-reports", 4);
        assertThat(TestsRan.check(root, root, "pom.xml", "surefire-reports", "failsafe-reports")).isEmpty();
        report(root, "build/test-results/test", 4);
        assertThat(TestsRan.check(root, root, "build.gradle", "test-results")).isEmpty();
    }

    @Test
    void aProjectWithoutTestsIsNotBlamedForRunningNone(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/java/x"));
        Files.writeString(root.resolve("src/main/java/x/A.java"), "package x;\nclass A {}\n");
        assertThat(TestsRan.check(root, root, "pom.xml", "surefire-reports")).isEmpty();
    }
}
