package io.renova.core.engine;

import io.renova.core.ai.AiFixer;
import io.renova.core.ai.AiProvider;
import io.renova.core.ai.AiProviderException;
import io.renova.core.playbook.FixSpec;
import io.renova.core.spi.Fixer;
import io.renova.core.spi.Verifier;
import io.renova.core.workspace.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Applies a plan to a copy of the project: deterministic stages first, then AI-assisted edits,
 * then verification with an AI repair loop. Each stage is committed in the workspace.
 */
public final class Migrator {

    /** Deterministic before judgement: AI sees code that recipes have already modernised. */
    private static final List<String> STAGE_ORDER = List.of(FixSpec.RECIPE, FixSpec.REPLACE, FixSpec.AI);

    private final PluginRegistry registry;
    private final Consumer<String> progress;

    public Migrator(PluginRegistry registry, Consumer<String> progress) {
        this.registry = registry;
        this.progress = progress;
    }

    public MigrationOutcome migrate(AnalysisResult analysis, MigrationPlan plan, MigrationOptions options) throws Exception {
        // Create the provider first so bad credentials fail before any copying or building.
        try (AiProvider ai = registry.ai(options.ai())) {
            progress.accept("Copying project to " + options.outputDir());
            Workspace workspace = Workspace.create(analysis.project().root(), options.outputDir());
            return run(new MigrationContext(workspace, analysis.project(), plan.playbook(), options, ai,
                    registry.plugin(plan.playbook().ecosystem())), plan);
        }
    }

    private MigrationOutcome run(MigrationContext context, MigrationPlan plan) throws Exception {
        String ecosystem = plan.playbook().ecosystem();
        MigrationOptions options = context.options();
        Workspace workspace = context.workspace();

        Set<String> strategies = new LinkedHashSet<>(STAGE_ORDER);
        plan.steps().forEach(s -> strategies.add(s.strategy()));
        strategies.remove(FixSpec.MANUAL);

        List<StageResult> stages = new ArrayList<>();
        for (String strategy : strategies) {
            List<PlanStep> steps = plan.steps(strategy);
            if (steps.isEmpty()) {
                continue;
            }
            if (options.skipStrategies().contains(strategy)) {
                stages.add(StageResult.skipped(strategy, "skipped by option"));
                continue;
            }
            Optional<Fixer> fixer = registry.fixer(ecosystem, strategy);
            if (fixer.isEmpty()) {
                stages.add(StageResult.skipped(strategy, "no fixer installed for strategy '" + strategy + "'"));
                continue;
            }
            progress.accept("Stage " + strategy + ": " + steps.size() + " step(s)");
            StageResult result;
            try {
                result = fixer.get().apply(context, steps);
            } catch (Exception e) {
                result = new StageResult(strategy, StageResult.Status.FAILED,
                        e.getMessage() == null ? e.toString() : e.getMessage(), List.of());
            }
            workspace.commitAll("renova: " + strategy + " stage: " + result.summary());
            stages.add(result);
        }

        VerifyResult verification = null;
        Optional<Verifier> verifier = registry.plugin(ecosystem).verifier();
        if (options.verify() && verifier.isPresent()) {
            progress.accept("Verifying build");
            verification = verifier.get().verify(context);
            if (!verification.success() && context.ai().available() && options.maxAiIterations() > 0) {
                progress.accept("Build fails with " + verification.errors().size() + " error(s); starting AI repair");
                List<String> log = new ArrayList<>();
                try {
                    verification = new AiFixer().repair(context, verifier.get(), verification, options.maxAiIterations(), log);
                } catch (AiProviderException e) {
                    log.add("stopped: " + e.getMessage());
                }
                stages.add(new StageResult("ai-repair",
                        verification.success() ? StageResult.Status.APPLIED : StageResult.Status.PARTIAL,
                        verification.success() ? "build repaired" : "build still failing", log));
            }
        }
        return new MigrationOutcome(workspace.root(), stages, verification, plan.steps(FixSpec.MANUAL));
    }
}
