package io.renova.cli;

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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Analyse, plan, migrate and write the reports: what {@code migrate} does, shared with {@code benchmark}. */
record MigrationRun(AnalysisResult analysis, MigrationPlan plan, MigrationOutcome outcome) {

    static MigrationRun execute(PluginRegistry registry, Path root, Playbook playbook, MigrationOptions options,
                                Consumer<String> progress, BiConsumer<AnalysisResult, MigrationPlan> onPlan) throws Exception {
        AnalysisResult analysis = new Analyzer(registry).analyze(root, playbook);
        MigrationPlan plan = new Planner().plan(analysis);
        onPlan.accept(analysis, plan);
        MigrationOutcome outcome = new Migrator(registry, progress).migrate(analysis, plan, options);
        Path reportDir = outcome.workspace().resolve(".renova");
        Files.writeString(reportDir.resolve("report.md"), MarkdownReport.render(analysis, plan, outcome));
        Files.writeString(reportDir.resolve("report.json"), JsonReport.render(analysis, plan, outcome));
        return new MigrationRun(analysis, plan, outcome);
    }

    Path reportDir() {
        return outcome.workspace().resolve(".renova");
    }
}
