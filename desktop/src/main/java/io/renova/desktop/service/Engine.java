package io.renova.desktop.service;

import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.Analyzer;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.MigrationPlan;
import io.renova.core.engine.Migrator;
import io.renova.core.engine.Planner;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.playbook.Playbook;
import io.renova.core.report.JsonReport;
import io.renova.core.report.MarkdownReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** The Renova engine, in this process: nothing leaves the machine except AI requests on the user's own key. */
public final class Engine {

    private final PluginRegistry registry = PluginRegistry.load();

    /** A project's analysis and plan. */
    public record Assessment(Path root, Playbook playbook, AnalysisResult analysis, MigrationPlan plan) {
    }

    public PluginRegistry registry() {
        return registry;
    }

    public Assessment assess(Path root) throws Exception {
        return assess(root, null);
    }

    /** @param playbookRef a bundled playbook id or a playbook file; null for the one that applies */
    public Assessment assess(Path root, String playbookRef) throws Exception {
        Playbook playbook = playbookRef == null ? registry.defaultPlaybook(root) : registry.playbook(playbookRef);
        AnalysisResult analysis = new Analyzer(registry).analyze(root, playbook);
        return new Assessment(root, playbook, analysis, new Planner().plan(analysis));
    }

    /** The installed targets, for choosing one. */
    public java.util.List<Playbook> playbooks() {
        return registry.playbooks().stream().filter(p -> !p.addon()).toList();
    }

    /** The optional add-ons that can be combined with a target. */
    public java.util.List<Playbook> addons() {
        return registry.addons();
    }

    /** The assessment as the CLI's Markdown or JSON report. */
    public String export(Assessment a, boolean json) {
        return json ? JsonReport.render(a.analysis(), a.plan(), null) : MarkdownReport.render(a.analysis(), a.plan(), null);
    }

    /**
     * Runs the original and the migrated application side by side again, for a finished migration; writes the
     * behaviour reports in its .renova folder.
     */
    public io.renova.core.behaviour.BehaviourReport verifyBehaviour(Path workspace, String playbookId,
                                                                     java.util.Map<String, String> tools,
                                                                     Consumer<String> progress) throws Exception {
        io.renova.core.workspace.Workspace ws = io.renova.core.workspace.Workspace.open(workspace);
        Playbook playbook = registry.playbook(playbookId);
        io.renova.core.spi.EcosystemPlugin plugin = registry.plugin(playbook.ecosystem());
        MigrationOptions options = new MigrationOptions(workspace, null, 0, false, tools, java.util.List.of());
        return io.renova.core.behaviour.BehaviourVerifier.verify(new io.renova.core.engine.MigrationContext(ws,
                plugin.model(workspace), playbook, options, new io.renova.core.ai.NoAiProvider(), plugin), progress);
    }

    /** Migrates a copy into {@code options.outputDir()} and writes the reports, as the CLI does. */
    public MigrationOutcome migrate(Assessment assessment, MigrationOptions options, Consumer<String> progress) throws Exception {
        MigrationOutcome outcome = new Migrator(registry, progress).migrate(assessment.analysis(), assessment.plan(), options);
        Path reports = outcome.workspace().resolve(".renova");
        Files.writeString(reports.resolve("report.md"), MarkdownReport.render(assessment.analysis(), assessment.plan(), outcome));
        Files.writeString(reports.resolve("report.json"), JsonReport.render(assessment.analysis(), assessment.plan(), outcome));
        return outcome;
    }
}
