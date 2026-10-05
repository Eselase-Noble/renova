package io.renova.cli;

import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.Analyzer;
import io.renova.core.engine.MigrationPlan;
import io.renova.core.engine.Planner;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.report.JsonReport;
import io.renova.core.report.MarkdownReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "analyze", aliases = "assess",
        description = "Scan a project and print the migration assessment and plan. Never modifies the project.")
final class AnalyzeCommand implements Callable<Integer> {

    enum Format { md, json }

    @Mixin
    ProjectOptions project;

    @Option(names = {"-f", "--format"}, defaultValue = "md", description = "Output format: ${COMPLETION-CANDIDATES}.")
    Format format;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Write the report to a file instead of stdout.")
    Path output;

    @Override
    public Integer call() throws Exception {
        PluginRegistry registry = PluginRegistry.load();
        AnalysisResult analysis = new Analyzer(registry).analyze(project.root(), project.playbook(registry));
        MigrationPlan plan = new Planner().plan(analysis);
        String report = format == Format.json
                ? JsonReport.render(analysis, plan, null)
                : MarkdownReport.render(analysis, plan, null);
        if (output == null) {
            System.out.println(report);
        } else {
            Files.writeString(output, report);
            System.err.printf("%d findings, %d steps, %d%% automated. Report: %s%n", analysis.findings().size(),
                    plan.steps().size(), Math.round(plan.automationRate() * 100), output.toAbsolutePath());
        }
        return 0;
    }
}
