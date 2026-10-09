package io.renova.desktop;

import io.renova.core.ai.AiSettings;
import io.renova.core.config.UserConfig;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.config.AiPreferences;
import io.renova.desktop.service.DiffLines;
import io.renova.desktop.service.MigrationHistory;
import io.renova.desktop.service.MigrationResult;
import io.renova.desktop.service.Phases;
import io.renova.desktop.service.RecentProjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopServicesTest {

    private final PluginRegistry registry = PluginRegistry.load();

    @Test
    void aiPreferencesSaveToTheUserConfigAndNeverShowTheKey(@TempDir Path dir) throws Exception {
        AiPreferences prefs = new AiPreferences(registry, new UserConfig(dir.resolve("config.properties")), Map.of());
        assertThat(prefs.aiSettings()).isEqualTo(AiSettings.NONE);
        assertThat(prefs.view().rag()).isTrue();

        prefs.save(AiPreferences.PROVIDER, "anthropic");
        prefs.setKey("anthropic", "sk-ant-test-0123456789abcdefWXYZ");
        AiPreferences.View view = prefs.view();
        assertThat(view.provider()).isEqualTo("anthropic");
        AiPreferences.Provider anthropic = view.providers().stream().filter(p -> p.name().equals("anthropic")).findFirst().orElseThrow();
        assertThat(anthropic.keyConfigured()).isTrue();
        assertThat(anthropic.maskedKey()).doesNotContain("0123456789abcdef").endsWith("WXYZ");
        assertThat(anthropic.keySource()).isEqualTo("Renova settings");
        assertThat(prefs.aiSettings().apiKey().reveal()).isEqualTo("sk-ant-test-0123456789abcdefWXYZ");

        prefs.save(AiPreferences.PROVIDER, "");
        assertThat(prefs.aiSettings()).isEqualTo(AiSettings.NONE);
    }

    @Test
    void environmentVariablesWinLikeInTheCli(@TempDir Path dir) throws Exception {
        UserConfig config = new UserConfig(dir.resolve("config.properties"));
        config.set("ai.provider", "openai");
        AiPreferences prefs = new AiPreferences(registry, config,
                Map.of("RENOVA_AI_PROVIDER", "anthropic", "ANTHROPIC_API_KEY", "sk-ant-from-env-ABCD"));
        assertThat(prefs.aiSettings().provider()).isEqualTo("anthropic");
        assertThat(prefs.view().providers()).filteredOn(p -> p.name().equals("anthropic"))
                .singleElement().satisfies(p -> assertThat(p.keySource()).isEqualTo("environment"));
    }

    @Test
    void recentProjectsAreNewestFirstWithoutDuplicatesOrMissingFolders(@TempDir Path dir) throws Exception {
        RecentProjects recent = new RecentProjects(dir.resolve("recent.json"));
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        recent.opened(a);
        recent.opened(b);
        recent.opened(a);
        assertThat(recent.list()).extracting(RecentProjects.Entry::name).containsExactly("a", "b");
        Files.delete(b);
        assertThat(recent.list()).extracting(RecentProjects.Entry::name).containsExactly("a");
    }

    @Test
    void customEndpointsAreSavedValidatedAndCleared(@TempDir Path dir) throws Exception {
        AiPreferences prefs = new AiPreferences(registry, new UserConfig(dir.resolve("config.properties")), Map.of());
        prefs.setBaseUrl("openai", "https://gateway.example.com/v1");
        assertThat(prefs.view().providers()).filteredOn(p -> p.name().equals("openai"))
                .singleElement().satisfies(p -> assertThat(p.baseUrl()).isEqualTo("https://gateway.example.com/v1"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> prefs.setBaseUrl("openai", "gateway.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        prefs.setBaseUrl("openai", "");
        assertThat(prefs.view().providers()).filteredOn(p -> p.name().equals("openai"))
                .singleElement().satisfies(p -> assertThat(p.baseUrl()).isNull());
    }

    @Test
    void historyKeepsNewestFirstAndForgetsMissingCopies(@TempDir Path dir) throws Exception {
        MigrationHistory history = new MigrationHistory(dir.resolve("history.json"));
        Path one = Files.createDirectories(dir.resolve("one"));
        Path two = Files.createDirectories(dir.resolve("two"));
        history.add(entry(one, "PASSED"));
        history.add(entry(two, "FAILED"));
        history.add(entry(one, "FAILED"));
        assertThat(history.list()).extracting(MigrationHistory.Entry::workspace).containsExactly(one.toString(), two.toString());
        assertThat(history.list().getFirst().state()).isEqualTo("FAILED");

        Files.delete(two);
        assertThat(history.list()).hasSize(1);
        history.remove(one.toString());
        assertThat(history.list()).isEmpty();
    }

    @Test
    void resultsAreReadFromTheMigratedCopy(@TempDir Path dir) throws Exception {
        Path ws = dir.resolve("shop-migrated");
        Path renova = Files.createDirectories(ws.resolve(".renova"));
        Files.createDirectories(renova.resolve("ai"));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> MigrationResult.load(ws))).hasMessageContaining("not a finished migration");

        Files.writeString(renova.resolve("report.json"), """
                {"project": {"root": "/code/shop"}, "playbook": {"id": "java-8-to-21"},
                 "migration": {"verification": {"success": true, "errors": []}, "aiUsage": {"requests": 2}}}
                """);
        Files.writeString(renova.resolve("report.md"), "# Report");
        Files.writeString(renova.resolve("behaviour.json"), "{\"status\": \"DIFFERENT\", \"results\": []}");
        Files.writeString(renova.resolve("ai/002.md"), "second");
        Files.writeString(renova.resolve("ai/001.md"), "first");
        MigrationResult.saveProgress(ws, java.util.List.of("Copying", "Done"));

        MigrationResult r = MigrationResult.load(ws);
        assertThat(r.projectName()).isEqualTo("shop");
        assertThat(r.buildPasses()).isTrue();
        assertThat(r.state()).isEqualTo("FAILED");
        assertThat(r.markdown()).isEqualTo("# Report");
        assertThat(r.aiExchanges()).extracting(MigrationResult.AiExchange::markdown).containsExactly("first", "second");
        assertThat(r.progressLog()).containsExactly("Copying", "Done");
    }

    private static MigrationHistory.Entry entry(Path ws, String state) {
        return new MigrationHistory.Entry("shop", "/code/shop", ws.toString(), state, "2026-10-06T10:00:00Z",
                "2026-10-06T10:05:00Z", "Passes", "Not checked", 0, 0, 0.8);
    }

    @Test
    void phasesFollowTheProgressLines() {
        List<String> log = List.of("Plan: 7 steps for 20 findings, 80% automated", "Copying project to /tmp/out",
                "Stage recipe: 5 step(s)", "Stage replace: 1 step(s)", "Checking 10 guard rule(s) on the migrated code");

        // Running: everything before the phase of the last line is done; phases only some migrations have are not listed yet.
        assertThat(Phases.of(log, false, false, false, null, null)).extracting(Phases.Phase::key, Phases.Phase::state).containsExactly(
                org.assertj.core.groups.Tuple.tuple("plan", Phases.State.DONE),
                org.assertj.core.groups.Tuple.tuple("rewrite", Phases.State.DONE),
                org.assertj.core.groups.Tuple.tuple("guards", Phases.State.CURRENT),
                org.assertj.core.groups.Tuple.tuple("build", Phases.State.PENDING));

        // Finished with a failing build that AI could not repair, then no behaviour check.
        List<String> failed = new java.util.ArrayList<>(log);
        failed.addAll(List.of("Verifying build", "Build fails with 2 error(s); starting AI repair", "Finished: build FAILS"));
        assertThat(Phases.of(failed, true, true, false, false, null)).extracting(Phases.Phase::key, Phases.Phase::state).containsExactly(
                org.assertj.core.groups.Tuple.tuple("plan", Phases.State.DONE),
                org.assertj.core.groups.Tuple.tuple("rewrite", Phases.State.DONE),
                org.assertj.core.groups.Tuple.tuple("guards", Phases.State.DONE),
                org.assertj.core.groups.Tuple.tuple("build", Phases.State.FAILED),
                org.assertj.core.groups.Tuple.tuple("repair", Phases.State.WARN));

        // Without AI the engine still announces the AI stage; it is not a phase then.
        List<String> announced = List.of("Plan: 1 steps", "Stage recipe: 1 step(s)", "Stage ai: 4 step(s)");
        assertThat(Phases.of(announced, false, false, false, null, null)).extracting(Phases.Phase::key).doesNotContain("ai");
        assertThat(Phases.of(announced, true, false, false, null, null)).extracting(Phases.Phase::key).contains("ai");

        // Stopped by an error while rewriting: that phase failed, the rest never ran.
        assertThat(Phases.of(log.subList(0, 3), false, true, true, null, null)).extracting(Phases.Phase::state)
                .containsExactly(Phases.State.DONE, Phases.State.FAILED, Phases.State.SKIPPED, Phases.State.SKIPPED);

        // A behaviour difference is flagged on its own phase, with the line that reported it.
        List<String> differs = new java.util.ArrayList<>(log);
        differs.addAll(List.of("Verifying build", "Verifying behaviour: running both", "Sending 4 scenario(s) to both applications"));
        assertThat(Phases.of(differs, false, true, false, true, "DIFFERENT")).last()
                .satisfies(p -> {
                    assertThat(p.key()).isEqualTo("behaviour");
                    assertThat(p.state()).isEqualTo(Phases.State.WARN);
                    assertThat(p.note()).startsWith("Sending 4");
                });
    }

    @Test
    void diffLinesAreNumberedFromTheirHunk() {
        String diff = """
                commit abc
                Author: Renova

                    renova: recipe stage

                diff --git a/src/A.java b/src/A.java
                index 111..222 100644
                --- a/src/A.java
                +++ b/src/A.java
                @@ -10,4 +10,5 @@ class A {
                 keep
                -import javax.servlet.Filter;
                +import jakarta.servlet.Filter;
                +import jakarta.servlet.Servlet;
                 end
                diff --git a/new.txt b/new.txt
                new file mode 100644
                @@ -0,0 +1 @@
                +hello
                """;
        List<DiffLines.Line> lines = DiffLines.parse(diff, 1000);
        assertThat(lines).extracting(DiffLines.Line::kind).containsExactly(DiffLines.Kind.FILE, DiffLines.Kind.HUNK,
                DiffLines.Kind.CONTEXT, DiffLines.Kind.REMOVED, DiffLines.Kind.ADDED, DiffLines.Kind.ADDED, DiffLines.Kind.CONTEXT,
                DiffLines.Kind.FILE, DiffLines.Kind.NOTE, DiffLines.Kind.HUNK, DiffLines.Kind.ADDED);
        assertThat(lines.get(0).text()).isEqualTo("src/A.java");
        // keep is line 10 on both sides; the removed line is 11 before; the two added are 11 and 12 after; end is 12 and 13.
        assertThat(lines.subList(2, 7)).extracting(DiffLines.Line::before).containsExactly(10, 11, 0, 0, 12);
        assertThat(lines.subList(2, 7)).extracting(DiffLines.Line::after).containsExactly(10, 0, 11, 12, 13);
        assertThat(lines.get(3).text()).isEqualTo("import javax.servlet.Filter;");
        assertThat(lines.getLast().after()).isEqualTo(1);

        assertThat(DiffLines.parse(diff, 3)).hasSize(4).last().satisfies(l -> assertThat(l.kind()).isEqualTo(DiffLines.Kind.NOTE));
    }

    @Test
    void aDotnetProjectIsAssessedAndOfferedItsOwnTargetsOnly(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Billing.csproj"), "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>"
                + "<TargetFramework>net6.0</TargetFramework></PropertyGroup></Project>");
        Files.writeString(dir.resolve("Invoice.cs"), "class Invoice {}\n");
        io.renova.desktop.service.Engine engine = new io.renova.desktop.service.Engine();

        io.renova.desktop.service.Engine.Assessment assessment = engine.assess(dir);

        assertThat(assessment.playbook().id()).isEqualTo("dotnet-to-10");
        assertThat(assessment.plan().steps()).extracting(s -> s.rule().id()).containsExactly("target-framework");
        assertThat(assessment.plan().automationRate()).isEqualTo(1.0);
        assertThat(engine.registry().playbooksFor(dir)).extracting(io.renova.core.playbook.Playbook::id)
                .containsExactlyInAnyOrder("dotnet-to-10", "dotnet-to-8");
        assertThat(engine.registry().addonsFor(dir)).extracting(io.renova.core.playbook.Playbook::id).containsExactly("efcore");
        assertThat(engine.export(assessment, false)).contains(".NET → 10", "automated edit (`dotnet`)");
    }
}
