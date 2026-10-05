package io.renova.cli;

import io.renova.core.behaviour.BehaviourReport;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.Migrator;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.engine.StageResult;
import io.renova.core.rag.RagSettings;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

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

    @Option(names = "--verify-behaviour", description = "After a passing build, run the original and the migrated "
            + "application side by side in Docker and compare their answers.")
    boolean verifyBehaviour;

    @Option(names = "--skip", split = ",", paramLabel = "STRATEGY", description = "Strategies to skip, e.g. --skip recipe,ai.")
    List<String> skip = new ArrayList<>();

    @Option(names = "--rag", negatable = true,
            description = "Add retrieved context (related project code, curated migration knowledge) to AI requests. "
                    + "Needs no extra key. Default: the rag.enabled setting, else on.")
    Boolean rag;

    @Option(names = "--maven-settings", paramLabel = "FILE", description = "Maven settings.xml (e.g. for a private Nexus).")
    Path mavenSettings;

    @Option(names = "--offline", description = "Run Maven offline.")
    boolean offline;

    @Override
    public Integer call() throws Exception {
        PluginRegistry registry = PluginRegistry.load();
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
        if (verifyBehaviour) {
            tools.put(Migrator.VERIFY_BEHAVIOUR, "true");
        }
        AiConfiguration aiConfig = AiConfiguration.load(registry, ai);
        RagSettings ragSettings = aiConfig.ragSettings(rag);
        System.err.println("AI: " + aiConfig.describe() + (ragSettings.enabled() ? "; RAG on" : ""));
        MigrationOptions options = new MigrationOptions(out, aiConfig.aiSettings(), maxAiIterations, !noVerify, tools, skip,
                ragSettings);
        MigrationRun run = MigrationRun.execute(registry, project.root(), project.playbook(registry), options,
                msg -> System.err.println("» " + msg),
                (analysis, plan) -> System.err.printf("Plan: %d steps for %d findings, %d%% automated%n",
                        plan.steps().size(), analysis.findings().size(), Math.round(plan.automationRate() * 100)));
        MigrationOutcome outcome = run.outcome();
        Path reportDir = run.reportDir();

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
        if (outcome.behaviour() != null) {
            System.err.println("  behaviour  " + outcome.behaviour().status() + ": " + outcome.behaviour().summary()
                    + "  (" + reportDir.resolve("behaviour.md") + ")");
        }
        boolean buildOk = outcome.verification() == null || outcome.verification().success();
        boolean behaviourOk = outcome.behaviour() == null || outcome.behaviour().status() == BehaviourReport.Status.SAME
                || outcome.behaviour().status() == BehaviourReport.Status.SKIPPED;
        return buildOk && behaviourOk ? 0 : 1;
    }
}
