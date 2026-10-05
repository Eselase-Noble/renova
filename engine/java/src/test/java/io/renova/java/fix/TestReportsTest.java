package io.renova.java.fix;

import io.renova.core.engine.BuildError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestReportsTest {

    private static void write(Path root, String rel, String content) throws Exception {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void attributesFailuresToTheProjectCodeThatThrew(@TempDir Path ws) throws Exception {
        write(ws, "batch/src/main/java/com/acme/batch/ReorderRules.java", "class ReorderRules {}");
        write(ws, "batch/src/test/java/com/acme/batch/ReorderRulesTest.java", "class ReorderRulesTest {}");
        write(ws, "core/src/test/java/com/acme/core/OnlyTest.java", "class OnlyTest {}");
        // Surefire 2.x report: class name in parentheses.
        write(ws, "batch/target/surefire-reports/com.acme.batch.ReorderRulesTest.txt", """
                -------------------------------------------------------------------------------
                Test set: com.acme.batch.ReorderRulesTest
                -------------------------------------------------------------------------------
                Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.056 sec <<< FAILURE!
                evaluatesTheConfiguredRule(com.acme.batch.ReorderRulesTest)  Time elapsed: 0.018 sec  <<< ERROR!
                java.lang.NullPointerException: Cannot invoke "javax.script.ScriptEngine.put(String, Object)" because "this.engine" is null
                \tat com.acme.batch.ReorderRules.needsReorder(ReorderRules.java:23)
                \tat com.acme.batch.ReorderRulesTest.evaluatesTheConfiguredRule(ReorderRulesTest.java:15)
                \tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
                """);
        // Surefire 3.x report: class only in the header; failure only in the test itself.
        write(ws, "core/target/surefire-reports/com.acme.core.OnlyTest.txt", """
                Test set: com.acme.core.OnlyTest
                Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.1 s <<< FAILURE! -- in com.acme.core.OnlyTest
                com.acme.core.OnlyTest.checksAnswer -- Time elapsed: 0.01 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <1> but was: <2>
                \tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:151)
                \tat com.acme.core.OnlyTest.checksAnswer(OnlyTest.java:9)
                """);
        List<BuildError> errors = TestReports.parse(ws, ws);
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0)).satisfies(e -> {
            assertThat(e.file()).isEqualTo("batch/src/main/java/com/acme/batch/ReorderRules.java");
            assertThat(e.line()).isEqualTo(23);
            assertThat(e.message()).startsWith("test ReorderRulesTest.evaluatesTheConfiguredRule failed: java.lang.NullPointerException")
                    .contains("(at ReorderRules.java:23)");
        });
        assertThat(errors.get(1)).satisfies(e -> {
            assertThat(e.file()).isEqualTo("core/src/test/java/com/acme/core/OnlyTest.java");
            assertThat(e.line()).isEqualTo(9);
            assertThat(e.message()).contains("expected: <1> but was: <2>");
        });
    }

    @Test
    void readsSurefire3ParameterisedTestsWithMultiLineMessages(@TempDir Path ws) throws Exception {
        write(ws, "src/main/java/a/Parser.java", "class Parser {}");
        write(ws, "src/test/java/a/ParserTest.java", "class ParserTest {}");
        write(ws, "target/surefire-reports/a.ParserTest.txt", """
                Test set: a.ParserTest
                Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.035 s <<< FAILURE! -- in a.ParserTest
                a.ParserTest.parsesFile(Path) -- Time elapsed: 0.030 s <<< FAILURE!
                java.lang.AssertionError:\s

                Expected size: 2 but was: 1
                \tat a.Parser.parse(Parser.java:40)
                \tat a.ParserTest.parsesFile(ParserTest.java:12)
                """);
        assertThat(TestReports.parse(ws, ws)).singleElement().satisfies(e -> {
            assertThat(e.file()).isEqualTo("src/main/java/a/Parser.java");
            assertThat(e.line()).isEqualTo(40);
            assertThat(e.message()).startsWith("test ParserTest.parsesFile failed: java.lang.AssertionError: Expected size: 2 but was: 1");
        });
    }

    @Test
    void attributesAFailedAssertionToTheClassUnderTest(@TempDir Path ws) throws Exception {
        write(ws, "common/src/main/java/com/acme/codec/TokenCodec.java", "class TokenCodec {}");
        write(ws, "common/src/test/java/com/acme/codec/TokenCodecTest.java", "class TokenCodecTest {}");
        write(ws, "common/target/surefire-reports/com.acme.codec.TokenCodecTest.txt", """
                Test set: com.acme.codec.TokenCodecTest
                com.acme.codec.TokenCodecTest.wrapsLongTokens -- Time elapsed: 0.01 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <a\nb> but was: <ab>
                \tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:151)
                \tat com.acme.codec.TokenCodecTest.wrapsLongTokens(TokenCodecTest.java:21)
                """);
        assertThat(TestReports.parse(ws, ws)).singleElement().satisfies(e -> {
            assertThat(e.file()).isEqualTo("common/src/main/java/com/acme/codec/TokenCodec.java");
            assertThat(e.message()).contains("expected: <a").contains("(at TokenCodecTest.java:21)");
        });
    }
}
