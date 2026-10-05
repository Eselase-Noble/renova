package io.renova.core;

import io.renova.core.ai.AiSettings;
import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.Analyzer;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.MigrationPlan;
import io.renova.core.engine.Migrator;
import io.renova.core.engine.Planner;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.engine.StageResult;
import io.renova.core.model.Module;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;
import io.renova.core.playbook.PlaybookLoader;
import io.renova.core.report.JsonReport;
import io.renova.core.report.MarkdownReport;
import io.renova.core.spi.EcosystemPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end through the core with a toy "text" ecosystem: proves the engine is not tied to Java.
 */
class EngineTest {

    static final class TextPlugin implements EcosystemPlugin {
        public String id() { return "text"; }
        public String displayName() { return "Plain text"; }
        public boolean supports(Path root) { return Files.isDirectory(root); }
        public ProjectModel model(Path root) {
            return new ProjectModel(root, "text", List.of(new Module("root", ".", "", Map.of())), Map.of());
        }
    }

    static final Playbook PLAYBOOK = PlaybookLoader.load(new ByteArrayInputStream("""
            id: text-demo
            name: Text demo
            ecosystem: text
            rules:
              - id: old-name
                title: Rename OldCo to NewCo
                category: B
                detect: { type: fileContains, include: "**/*.txt", pattern: "OldCo" }
                fix: { strategy: replace, include: "**/*.txt", find: "OldCo", replace: "NewCo" }
              - id: legacy-config
                category: A
                detect: { type: fileExists, include: "**/legacy.ini" }
                fix: { strategy: manual, hint: Convert to YAML }
            """.getBytes(StandardCharsets.UTF_8)), "test");

    final PluginRegistry registry = new PluginRegistry(List.of(new TextPlugin()), List.of());

    @Test
    void analyzesPlansAndMigratesACopy(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("readme.txt"), "Made by OldCo.\nOldCo rules.\n");
        Files.createDirectories(project.resolve("conf"));
        Files.writeString(project.resolve("conf/legacy.ini"), "[x]\n");
        Files.createDirectories(project.resolve("target"));
        Files.writeString(project.resolve("target/ignored.txt"), "OldCo");

        AnalysisResult analysis = new Analyzer(registry).analyze(project, PLAYBOOK);
        assertThat(analysis.findings()).extracting(f -> f.ruleId() + "@" + f.file() + ":" + f.line())
                .containsExactly("old-name@readme.txt:1", "old-name@readme.txt:2", "legacy-config@conf/legacy.ini:0");

        MigrationPlan plan = new Planner().plan(analysis);
        assertThat(plan.steps()).extracting(s -> s.rule().id()).containsExactly("legacy-config", "old-name");
        assertThat(plan.automationRate()).isEqualTo(2.0 / 3);

        Path out = tmp.resolve("out");
        MigrationOutcome outcome = new Migrator(registry, msg -> { })
                .migrate(analysis, plan, new MigrationOptions(out, AiSettings.NONE, 0, true, Map.of(), List.of()));

        assertThat(Files.readString(out.resolve("readme.txt"))).isEqualTo("Made by NewCo.\nNewCo rules.\n");
        assertThat(Files.readString(project.resolve("readme.txt"))).contains("OldCo");
        assertThat(out.resolve("target")).doesNotExist();
        assertThat(outcome.stages()).singleElement().extracting(StageResult::status).isEqualTo(StageResult.Status.APPLIED);
        assertThat(outcome.manualSteps()).extracting(s -> s.rule().id()).containsExactly("legacy-config");

        assertThat(MarkdownReport.render(analysis, plan, outcome)).contains("Rename OldCo to NewCo", "Manual follow-up");
        assertThat(JsonReport.render(analysis, plan, outcome)).contains("\"automationRate\"");
    }

    @Test
    void refusesToWriteIntoTheProjectOrANonEmptyDirectory(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("a.txt"), "OldCo");
        AnalysisResult analysis = new Analyzer(registry).analyze(project, PLAYBOOK);
        MigrationPlan plan = new Planner().plan(analysis);
        Migrator migrator = new Migrator(registry, msg -> { });

        assertThatThrownBy(() -> migrator.migrate(analysis, plan,
                new MigrationOptions(project.resolve("out"), AiSettings.NONE, 0, false, Map.of(), List.of())))
                .hasMessageContaining("outside the project");
        Path busy = Files.createDirectories(tmp.resolve("busy"));
        Files.writeString(busy.resolve("x"), "");
        assertThatThrownBy(() -> migrator.migrate(analysis, plan,
                new MigrationOptions(busy, AiSettings.NONE, 0, false, Map.of(), List.of())))
                .hasMessageContaining("not empty");
    }
}
