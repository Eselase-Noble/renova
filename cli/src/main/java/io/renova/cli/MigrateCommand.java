package io.renova.cli;

import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.Analyzer;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.MigrationPlan;
import io.renova.core.engine.Migrator;
import io.renova.core.engine.Planner;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.engine.StageResult;
import io.renova.core.report.JsonReport;
import io.renova.core.report.MarkdownReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "migrate",
        description = "Migrate a copy of the project into --out. The source is never modified; each stage is a git commit.")
final class MigrateCommand implements Callable<Integer> {

    @Mixin
    ProjectOptions project;

    @Option(names = "--out", required = true, paramLabel = "DIR", description = "Empty or new directory for the migrated copy.")
    Path out;

    @Mixin
    AiOptions ai;

    @Option(names = "--max-ai-iterations", defaultValue = "3", description = "Build-repair rounds (default: ${DEFAULT-VALUE}).")
    int maxAiIterations;

    @Option(names = "--no-verify", description = "Skip building the migrated project.")
    boolean noVerify;

    @Option(names = "--skip-tests", description = "Build the migrated project without running its tests.")
    boolean skipTests;

    @Option(names = "--skip", split = ",", paramLabel = "STRATEGY", description = "Strategies to skip, e.g. --skip recipe,ai.")
    List<String> skip = new ArrayList<>();

    @Option(names = "--maven-settings", paramLabel = "FILE", description = "Maven settings.xml (e.g. for a private Nexus).")
    Path mavenSettings;

    @Option(names = "--offline", description = "Run Maven offline.")
    boolean offline;

    @Override
    public Integer call() throws Exception {
        PluginRegistry registry = PluginRegistry.load();
        AnalysisResult analysis = new Analyzer(registry).analyze(project.root(), project.playbook(registry));
        MigrationPlan plan = new Planner().plan(analysis);
        System.err.printf("Plan: %d steps for %d findings, %d%% automated%n",
                plan.steps().size(), analysis.findings().size(), Math.round(plan.automationRate() * 100));

        Map<String, String> tools = new LinkedHashMap<>();
        if (mavenSettings != null) {
            tools.put("maven.settings", mavenSettings.toString());
        }
        if (offline) {
            tools.put("maven.offline", "true");
        }
        if (skipTests) {
            tools.put("verify.skipTests", "true");
        }
        AiConfiguration aiConfig = AiConfiguration.load(registry, ai);
        System.err.println("AI: " + aiConfig.describe());
        MigrationOptions options = new MigrationOptions(out, aiConfig.aiSettings(), maxAiIterations, !noVerify, tools, skip);
        MigrationOutcome outcome = new Migrator(registry, msg -> System.err.println("» " + msg))
                .migrate(analysis, plan, options);

        Path reportDir = outcome.workspace().resolve(".renova");
        Files.writeString(reportDir.resolve("report.md"), MarkdownReport.render(analysis, plan, outcome));
        Files.writeString(reportDir.resolve("report.json"), JsonReport.render(analysis, plan, outcome));

        for (StageResult stage : outcome.stages()) {
            System.err.printf("  %-10s %-8s %s%n", stage.stage(), stage.status(), stage.summary());
        }
        if (outcome.verification() != null) {
            System.err.println("  build      " + (outcome.verification().success() ? "PASSES" : "FAILS ("
                    + outcome.verification().errors().size() + " build errors)"));
        }
        if (!outcome.manualSteps().isEmpty()) {
            System.err.println("  manual     " + outcome.manualSteps().size() + " step(s) listed in the report");
        }
        System.err.println("Workspace: " + outcome.workspace() + "  (git log for per-stage commits)");
        System.err.println("Report:    " + reportDir.resolve("report.md"));
        return outcome.verification() == null || outcome.verification().success() ? 0 : 1;
    }
}
