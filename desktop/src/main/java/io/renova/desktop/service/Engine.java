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
        Playbook playbook = registry.defaultPlaybook(root);
        AnalysisResult analysis = new Analyzer(registry).analyze(root, playbook);
        return new Assessment(root, playbook, analysis, new Planner().plan(analysis));
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
