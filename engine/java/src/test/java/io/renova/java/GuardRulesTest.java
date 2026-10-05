package io.renova.java;

import io.renova.core.ai.AiSettings;
import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.Analyzer;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.Migrator;
import io.renova.core.engine.Planner;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.engine.StageResult;
import io.renova.core.playbook.Playbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a migration with recipes and AI skipped, on a copy of the fixture whose pom looks like a
 * typical half-migrated build, so only the guard rules change it. No Maven or network involved.
 */
class GuardRulesTest {

    @Test
    void guardsFixScopePluginCompilerTargetAndMissingDependencyBeforeVerification(@TempDir Path tmp) throws Exception {
        Path project = copyFixture(tmp.resolve("project"));
        Path pom = project.resolve("pom.xml");
        // What the Jakarta recipe produced in the demo: the API moved to compile scope.
        Files.writeString(pom, Files.readString(pom)
                .replace("<groupId>javax.servlet</groupId>", "<groupId>jakarta.servlet</groupId>")
                .replace("<artifactId>javax.servlet-api</artifactId>", "<artifactId>jakarta.servlet-api</artifactId>")
                .replace("<scope>provided</scope>", "<scope>compile</scope>"));
        // What the Jakarta recipe produced in the code: javax.annotation (from the JDK) renamed.
        Path controller = project.resolve("src/main/java/com/acme/web/OrderController.java");
        Files.writeString(controller, Files.readString(controller).replace("javax.annotation.", "jakarta.annotation."));

        PluginRegistry registry = PluginRegistry.load();
        Playbook playbook = registry.defaultPlaybook(project);
        AnalysisResult analysis = new Analyzer(registry).analyze(project, playbook);
        assertThat(analysis.findings()).noneMatch(f -> f.ruleId().startsWith("guard-"));

        Path out = tmp.resolve("out");
        MigrationOutcome outcome = new Migrator(registry, m -> { }).migrate(analysis, new Planner().plan(analysis),
                new MigrationOptions(out, AiSettings.NONE, 0, false, Map.of(), List.of("recipe", "replace", "ai")));

        String migrated = Files.readString(out.resolve("pom.xml"));
        assertThat(migrated)
                .contains("<artifactId>jakarta.servlet-api</artifactId>\n            <version>3.1.0</version>\n            <scope>provided</scope>")
                .contains("<artifactId>maven-war-plugin</artifactId>\n                <version>3.4.0</version>")
                .contains("<maven.compiler.target>${maven.compiler.source}</maven.compiler.target>")
                .contains("        <dependency>\n"
                        + "            <groupId>jakarta.annotation</groupId>\n"
                        + "            <artifactId>jakarta.annotation-api</artifactId>\n"
                        + "            <version>2.1.1</version>\n"
                        + "            <scope>provided</scope>\n"
                        + "        </dependency>\n"
                        + "    </dependencies>");
        // The JBoss variant gets the same build fixes (its servlet API was never touched, so stays provided).
        assertThat(Files.readString(out.resolve("pom.jboss.xml")))
                .contains("maven-war-plugin", "<maven.compiler.target>", "jakarta.annotation-api");
        assertThat(Files.readString(pom)).doesNotContain("maven-war-plugin");

        assertThat(outcome.stages()).extracting(StageResult::stage).contains("guard", "guard maven");
        assertThat(outcome.stages()).filteredOn(s -> s.stage().equals("guard")).singleElement()
                .satisfies(s -> assertThat(s.summary()).startsWith("4 of "
                        + playbook.rules().stream().filter(r -> r.guard()).count() + " guard rule(s) found problems"));
    }

    private static Path copyFixture(Path target) throws Exception {
        Path source = Path.of(GuardRulesTest.class.getResource("/fixtures/legacy-webapp").toURI());
        try (Stream<Path> files = Files.walk(source)) {
            for (Path p : files.sorted(Comparator.naturalOrder()).toList()) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest);
                }
            }
        }
        return target;
    }
}
