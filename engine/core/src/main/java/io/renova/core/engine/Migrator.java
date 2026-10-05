package io.renova.core.engine;

import io.renova.core.ai.AiFixer;
import io.renova.core.ai.AiProviderException;
import io.renova.core.ai.MeteredAiProvider;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.FixSpec;
import io.renova.core.playbook.Playbook;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Fixer;
import io.renova.core.spi.Verifier;
import io.renova.core.workspace.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Applies a plan to a copy of the project: deterministic stages first, then AI-assisted edits,
 * then verification with an AI repair loop. Each stage is committed in the workspace.
 */
public final class Migrator {

    /** Deterministic before judgement: AI sees code that recipes have already modernised. */
    private static final List<String> STAGE_ORDER = List.of(FixSpec.RECIPE, FixSpec.REPLACE, FixSpec.AI);
    private static final int MAX_GUARD_PASSES = 3;
    /** A repair round that edited files and rebuilt, as {@link AiFixer#repair} logs it. */
    private static final Pattern REPAIR_ROUND = Pattern.compile("^round \\d+: edited ");

    private final PluginRegistry registry;
    private final Consumer<String> progress;

    public Migrator(PluginRegistry registry, Consumer<String> progress) {
        this.registry = registry;
        this.progress = progress;
    }

    public MigrationOutcome migrate(AnalysisResult analysis, MigrationPlan plan, MigrationOptions options) throws Exception {
        // Create the provider first so bad credentials fail before any copying or building.
        try (MeteredAiProvider ai = new MeteredAiProvider(registry.ai(options.ai()))) {
            progress.accept("Copying project to " + options.outputDir());
            Workspace workspace = Workspace.create(analysis.project().root(), options.outputDir());
            MigrationOutcome outcome = run(new MigrationContext(workspace, analysis.project(), plan.playbook(), options, ai,
                    registry.plugin(plan.playbook().ecosystem())), plan);
            int rounds = (int) outcome.stages().stream().filter(s -> s.stage().equals("ai-repair"))
                    .flatMap(s -> s.details().stream()).filter(l -> REPAIR_ROUND.matcher(l).find()).count();
            return new MigrationOutcome(outcome.workspace(), outcome.stages(), outcome.verification(), outcome.manualSteps(),
                    ai.usage(), rounds);
        }
    }

    private MigrationOutcome run(MigrationContext context, MigrationPlan plan) throws Exception {
        String ecosystem = plan.playbook().ecosystem();
        MigrationOptions options = context.options();
        Workspace workspace = context.workspace();

        List<StageResult> stages = new ArrayList<>();
        applyPlan(context, plan, "", stages);
        List<PlanStep> manual = new ArrayList<>(plan.steps(FixSpec.MANUAL));
        manual.addAll(runGuards(context, stages));

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
        return new MigrationOutcome(workspace.root(), stages, verification, manual);
    }

    /** Runs each strategy's steps in stage order, committing after every stage. */
    private void applyPlan(MigrationContext context, MigrationPlan plan, String stagePrefix, List<StageResult> stages)
            throws Exception {
        String ecosystem = plan.playbook().ecosystem();
        Set<String> strategies = new LinkedHashSet<>(STAGE_ORDER);
        plan.steps().forEach(s -> strategies.add(s.strategy()));
        strategies.remove(FixSpec.MANUAL);

        for (String strategy : strategies) {
            List<PlanStep> steps = plan.steps(strategy);
            if (steps.isEmpty()) {
                continue;
            }
            String stage = stagePrefix + strategy;
            if (context.options().skipStrategies().contains(strategy)) {
                stages.add(StageResult.skipped(stage, "skipped by option"));
                continue;
            }
            Optional<Fixer> fixer = registry.fixer(ecosystem, strategy);
            if (fixer.isEmpty()) {
                stages.add(StageResult.skipped(stage, "no fixer installed for strategy '" + strategy + "'"));
                continue;
            }
            progress.accept("Stage " + stage + ": " + steps.size() + " step(s)");
            StageResult result;
            try {
                result = fixer.get().apply(context, steps);
            } catch (Exception e) {
                result = new StageResult(strategy, StageResult.Status.FAILED,
                        e.getMessage() == null ? e.toString() : e.getMessage(), List.of());
            }
            result = new StageResult(stage, result.status(), result.summary(), result.details());
            context.workspace().commitAll("renova: " + stage + " stage: " + result.summary());
            stages.add(result);
        }
    }

    /**
     * Checks the playbook's guard rules against the migrated workspace and fixes what they find, so
     * problems introduced by recipes or AI edits are caught before the build is verified.
     *
     * @return guard steps left for a person
     */
    private List<PlanStep> runGuards(MigrationContext context, List<StageResult> stages) throws Exception {
        Playbook playbook = context.playbook();
        List<Rule> guards = playbook.rules().stream().filter(Rule::guard).toList();
        if (guards.isEmpty()) {
            return List.of();
        }
        // Fixing one guard can create work for another (adding an API can call for its implementation),
        // so check again until nothing new is found.
        List<PlanStep> left = List.of();
        Set<String> previous = Set.of();
        for (int pass = 1; pass <= MAX_GUARD_PASSES; pass++) {
            progress.accept("Checking " + guards.size() + " guard rule(s) on the migrated code"
                    + (pass > 1 ? " (pass " + pass + ")" : ""));
            ProjectModel migrated = context.plugin().model(context.workspace().root());
            AnalysisResult check = new Analyzer(registry).check(migrated, playbook, guards);
            MigrationPlan guardPlan = new Planner().plan(check);
            String stage = pass == 1 ? "guard" : "guard pass " + pass;

            List<String> details = new ArrayList<>(check.warnings());
            check.findings().forEach(f -> details.add(f.ruleId() + ": " + f.file() + (f.line() > 0 ? ":" + f.line() : "")
                    + (f.evidence() == null ? "" : " (" + f.evidence() + ")")));
            Set<String> found = new LinkedHashSet<>();
            check.findings().forEach(f -> found.add(f.ruleId() + "|" + f.file() + "|" + f.evidence()));
            if (guardPlan.steps().isEmpty()) {
                if (pass == 1) {
                    stages.add(new StageResult(stage, StageResult.Status.APPLIED,
                            "all " + guards.size() + " guard rule(s) passed", details));
                }
                return List.of();
            }
            left = guardPlan.steps(FixSpec.MANUAL);
            if (found.equals(previous)) {
                break; // nothing changed since the last pass: the rest needs a person
            }
            stages.add(new StageResult(stage, StageResult.Status.PARTIAL, guardPlan.steps().size() + " of "
                    + guards.size() + " guard rule(s) found problems (" + check.findings().size() + " finding(s))", details));
            applyPlan(context, guardPlan, stage + " ", stages);
            if (guardPlan.steps().stream().allMatch(s -> s.strategy().equals(FixSpec.MANUAL))) {
                break;
            }
            previous = found;
        }
        return left;
    }
}
