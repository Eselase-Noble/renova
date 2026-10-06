package io.renova.java.fix;

import io.renova.core.engine.BuildError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a failing Gradle build says, as the errors the report and the AI repair loop work from. */
class GradleVerifierTest {

    @Test
    void compilerErrorsNameTheFileAndLine(@TempDir Path workspace) throws Exception {
        Path source = Files.createDirectories(workspace.resolve("app/src/main/java/x")).resolve("Codec.java");
        Files.writeString(source, "package x;\nclass Codec {}\n");
        String output = """
                > Task :app:compileJava FAILED
                %s:7: error: cannot find symbol
                    sun.misc.BASE64Encoder encoder;
                            ^
                  symbol:   class BASE64Encoder
                %s:7: error: cannot find symbol
                1 error

                FAILURE: Build failed with an exception.

                * What went wrong:
                Execution failed for task ':app:compileJava'.
                > Compilation failed; see the compiler error output for details.
                """.formatted(source, source);
        assertThat(GradleVerifier.parse(output, workspace))
                .containsExactly(new BuildError("app/src/main/java/x/Codec.java", 7, "cannot find symbol"));
    }

    @Test
    void aBrokenBuildScriptIsReportedWithGradlesReason(@TempDir Path workspace) throws Exception {
        Path script = workspace.resolve("build.gradle");
        Files.writeString(script, "plugins { id 'java' }\n");
        String output = """
                FAILURE: Build failed with an exception.

                * Where:
                Build file '%s' line: 14

                * What went wrong:
                A problem occurred evaluating root project 'shop'.
                > Could not find method compile() for arguments [junit:junit:4.13] on object of type DefaultDependencyHandler.

                * Try:
                > Run with --stacktrace option to get the stack trace.
                """.formatted(script);
        List<BuildError> errors = GradleVerifier.parse(output, workspace);
        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.file()).isEqualTo("build.gradle");
            assertThat(e.line()).isEqualTo(14);
            assertThat(e.message()).contains("Could not find method compile()");
        });
    }

    @Test
    void otherFailuresKeepWhatGradleSaysWentWrong(@TempDir Path workspace) {
        String output = """
                * What went wrong:
                Could not resolve all files for configuration ':compileClasspath'.
                > Could not find org.acme:internal-lib:1.0.

                * Try:
                > Run with --info
                """;
        assertThat(GradleVerifier.parse(output, workspace)).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("Could not find org.acme:internal-lib:1.0"));
        // Failing tests are read from their reports, not from this summary.
        assertThat(GradleVerifier.parse("* What went wrong:\nExecution failed for task ':test'.\n> There were failing tests.\n", workspace))
                .isEmpty();
    }

    @Test
    void failedTestsPointAtTheProjectCodeTheyFailedIn(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src/main/java/com/acme"));
        Files.writeString(workspace.resolve("src/main/java/com/acme/Rules.java"), "package com.acme;\nclass Rules {}\n");
        Path results = Files.createDirectories(workspace.resolve("build/test-results/test"));
        Files.writeString(results.resolve("TEST-com.acme.RulesTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.RulesTest" tests="2" failures="1" errors="0">
                  <testcase name="evaluates()" classname="com.acme.RulesTest" time="0.01">
                    <failure message="java.lang.NullPointerException: engine is null" type="java.lang.NullPointerException">java.lang.NullPointerException: engine is null
                	at com.acme.Rules.evaluate(Rules.java:23)
                	at com.acme.RulesTest.evaluates(RulesTest.java:12)
                </failure>
                  </testcase>
                  <testcase name="passes()" classname="com.acme.RulesTest" time="0.01"/>
                </testsuite>
                """);
        assertThat(GradleVerifier.testFailures(workspace, workspace)).containsExactly(new BuildError("src/main/java/com/acme/Rules.java", 23,
                "test RulesTest.evaluates failed: java.lang.NullPointerException: engine is null"));
    }
}
