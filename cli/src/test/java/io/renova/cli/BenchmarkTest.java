package io.renova.cli;

import io.renova.core.ai.AiUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkTest {

    @Test
    void checksLookAtOneFileOrAGlobAndIgnoreBuildOutput(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve("web/WEB-INF"));
        Files.createDirectories(ws.resolve("core/target"));
        Files.writeString(ws.resolve("web/WEB-INF/web.xml"), "<max-file-size>10485760</max-file-size>");
        Files.writeString(ws.resolve("web/pom.xml"), "<argLine>-Xmx1g</argLine>");
        Files.writeString(ws.resolve("core/pom.xml"), "<project/>");
        Files.writeString(ws.resolve("core/target/pom.xml"), "--add-opens");

        assertThat(BenchmarkCommand.passes(ws, check("web/WEB-INF/web.xml", "10485760", null, null))).isTrue();
        assertThat(BenchmarkCommand.passes(ws, check("web/WEB-INF/missing.xml", "10485760", null, null))).isFalse();
        assertThat(BenchmarkCommand.passes(ws, check("**/pom.xml", null, "--add-opens", null))).isTrue();
        assertThat(BenchmarkCommand.passes(ws, check("**/pom.xml", null, "-Xmx", null))).isFalse();
        assertThat(BenchmarkCommand.passes(ws, check("**/web.xml", null, null, "max-file-size>\\d+<"))).isTrue();
    }

    @Test
    void suitesAreValidated(@TempDir Path dir) throws Exception {
        Path suite = dir.resolve("suite.yaml");
        Files.writeString(suite, """
                id: s
                apps:
                  - { id: a, path: a, checks: [ { file: x, contains: y, absent: z } ] }
                configurations:
                  - { id: ai }
                """);
        assertThatThrownBy(() -> BenchmarkSuite.load(suite)).hasMessageContaining("exactly one of contains, absent or matches");
        Files.writeString(suite, """
                apps: [ { id: a, path: a } ]
                configurations: [ { id: rag-only, rag: true } ]
                """);
        assertThatThrownBy(() -> BenchmarkSuite.load(suite)).hasMessageContaining("enables rag without ai");
    }

    @Test
    void bundledSuiteLoads() throws Exception {
        BenchmarkSuite suite = BenchmarkSuite.load(Path.of("../benchmark/suite.yaml"));
        assertThat(suite.configurations()).extracting(BenchmarkSuite.Configuration::id).containsExactly("deterministic", "ai", "ai-rag");
        assertThat(suite.apps()).extracting(BenchmarkSuite.App::id).contains("inventory-platform", "claims-portal");
    }

    @Test
    void scoreboardSummarisesByConfiguration() {
        BenchmarkSuite suite = new BenchmarkSuite("s", null, List.of(), List.of());
        List<BenchmarkResult> results = List.of(
                new BenchmarkResult("a", "ai", 1, "PASSES", 0, 2, 2, List.of(), 1,
                        new AiUsage(3, 2, 1, 0, 0, 1000, 200, 0, 0), 0, 0, 0.9, 12, "/w/a", null, "SAME", 0),
                new BenchmarkResult("b", "ai", 1, "FAILS", 3, 1, 2, List.of("x.xml contains \"y\""), 3,
                        new AiUsage(5, 4, 0, 1, 0, 3000, 900, 0, 0), 1, 1, 0.8, 30, "/w/b", null, "DIFFERENT", 2));
        assertThat(BenchmarkCommand.scoreboard(suite, results))
                .contains("| ai | 1/2 | 1/2 | 3/4 | 1/2 | 4 | 8 | 4000 / 1100 | 42s |")
                .contains("| b | ai | FAILS (3) | 1/2 | DIFFERENT (2) | 3 | 5 (4 / 0 / 1) |")
                .contains("- ai/b: check failed: x.xml contains \"y\"", "- ai/b: 1 AI edit(s) rejected");
    }

    private static BenchmarkSuite.Check check(String file, String contains, String absent, String matches) {
        return new BenchmarkSuite.Check(file, contains, absent, matches, null);
    }
}
