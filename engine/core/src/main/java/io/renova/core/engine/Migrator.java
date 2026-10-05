package io.renova.core.engine;

import io.renova.core.ai.AiFixer;
import io.renova.core.ai.AiProviderException;
import io.renova.core.ai.AiUsage;
import io.renova.core.ai.MeteredAiProvider;
import io.renova.core.behaviour.BehaviourCheckingVerifier;
import io.renova.core.behaviour.BehaviourErrors;
import io.renova.core.behaviour.BehaviourReport;
import io.renova.core.behaviour.BehaviourVerifier;
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
    /** Tool option that turns on behavioural verification after a passing build. */
    public static final String VERIFY_BEHAVIOUR = "verify.behaviour";
    /** Tool option: "false" reports behaviour differences without sending them to AI repair. */
    public static final String REPAIR_BEHAVIOUR = "repair.behaviour";
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
            int rounds = (int) outcome.stages().stream().filter(s -> s.stage().equals("ai-repair") || s.stage().equals("ai-behaviour-repair"))
                    .flatMap(s -> s.details().stream()).filter(l -> REPAIR_ROUND.matcher(l).find()).count();
            return new MigrationOutcome(outcome.workspace(), outcome.stages(), outcome.verification(), outcome.manualSteps(),
                    ai.usage(), rounds, outcome.behaviour());
        }
    }

    private MigrationOutcome run(MigrationContext context, MigrationPlan plan) throws Exception {
        String ecosystem = plan.playbook().ecosystem();
        MigrationOptions options = context.options();
        Workspace workspace = context.workspace();

        List<StageResult> stages = new ArrayList<>();
        applyPlan(context, plan, "", stages);
        List<PlanStep> manual = new ArrayList<>(plan.steps(FixSpec.MANUAL));
        manual.addAll(runGuards(context, stages, "guard", true));

        VerifyResult verification = null;
        Optional<Verifier> verifier = registry.plugin(ecosystem).verifier();
        if (options.verify() && verifier.isPresent()) {
            progress.accept("Verifying build");
            verification = verifier.get().verify(context);
            if (!verification.success() && context.ai().available() && options.maxAiIterations() > 0) {
                progress.accept("Build fails with " + verification.errors().size() + " error(s); starting AI repair");
                List<String> log = new ArrayList<>();
                try {
                    verification = new AiFixer().repair(context, verifier.get(), verification, options.maxAiIterations(), log,
                            guardsAfterRepair(context, stages, manual, "repair round "));
                } catch (AiProviderException e) {
                    log.add("stopped: " + e.getMessage());
                }
                stages.add(new StageResult("ai-repair",
                        verification.success() ? StageResult.Status.APPLIED : StageResult.Status.PARTIAL,
                        verification.success() ? "build repaired" : "build still failing", log));
            }
        }
        BehaviourReport behaviour = null;
        if ("true".equals(options.toolOption(VERIFY_BEHAVIOUR)) && verification != null && verification.success()) {
            progress.accept("Verifying behaviour: running the original and the migrated application side by side");
            behaviour = BehaviourVerifier.verify(context, progress);
            stages.add(behaviourStage("behaviour", behaviour));
            if (behaviour.status() == BehaviourReport.Status.DIFFERENT && context.ai().available()
                    && options.maxAiIterations() > 0 && !"false".equals(options.toolOption(REPAIR_BEHAVIOUR))) {
                // Differences go to the same repair loop as build errors, attributed to the files that handle the
                // requests; each round rebuilds (with tests) and compares again.
                progress.accept("Behaviour differs in " + behaviour.differing() + " place(s); starting AI repair");
                BehaviourCheckingVerifier checking = new BehaviourCheckingVerifier(verifier.get(),
                        c -> BehaviourVerifier.verify(c, progress));
                List<String> log = new ArrayList<>();
                VerifyResult repaired = new VerifyResult(false, BehaviourErrors.of(behaviour), behaviour.summary());
                try {
                    repaired = new AiFixer().repair(context, checking, repaired, options.maxAiIterations(), log,
                            guardsAfterRepair(context, stages, manual, "behaviour repair round "));
                } catch (AiProviderException e) {
                    log.add("stopped: " + e.getMessage());
                }
                if (checking.last() != null) {
                    behaviour = checking.last();
                }
                // A repair may break the build; then the build result is what the migration ends with.
                boolean buildBroken = !repaired.success() && repaired.errors().stream()
                        .noneMatch(e -> e.message().startsWith("behaviour differs"));
                if (buildBroken) {
                    verification = repaired;
                }
                stages.add(new StageResult("ai-behaviour-repair",
                        behaviour.status() == BehaviourReport.Status.SAME && !buildBroken ? StageResult.Status.APPLIED
                                : StageResult.Status.PARTIAL,
                        buildBroken ? "a repair broke the build" : "behaviour " + behaviour.status().name().toLowerCase(
                                java.util.Locale.ROOT) + ": " + behaviour.summary(), log));
            }
        }
        return new MigrationOutcome(workspace.root(), stages, verification, manual, AiUsage.NONE, 0, behaviour);
    }

    private static StageResult behaviourStage(String name, BehaviourReport behaviour) {
        return new StageResult(name, switch (behaviour.status()) {
            case SAME -> StageResult.Status.APPLIED;
            case DIFFERENT -> StageResult.Status.PARTIAL;
            case SKIPPED -> StageResult.Status.SKIPPED;
            case FAILED -> StageResult.Status.FAILED;
        }, behaviour.summary(), behaviour.results().stream().filter(r -> !r.same())
                .map(r -> r.label() + ": " + String.join("; ", r.differences())).toList());
    }

    /**
     * Repair edits can bring back what the guards prevent (a bundled server API, a missing implementation),
     * so the guards check each round's edits before the rebuild.
     */
    private AiFixer.RoundHook guardsAfterRepair(MigrationContext context, List<StageResult> stages, List<PlanStep> manual,
                                                String label) {
        return round -> {
            for (PlanStep step : runGuards(context, stages, "guard after " + label + round, false)) {
                if (manual.stream().noneMatch(m -> m.rule().id().equals(step.rule().id()))) {
                    manual.add(step);
                }
            }
        };
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
     * @param label       stage name of the first pass, e.g. "guard"; later passes add " pass N"
     * @param reportClean whether to add a stage when every guard passes
     * @return guard steps left for a person
     */
    private List<PlanStep> runGuards(MigrationContext context, List<StageResult> stages, String label, boolean reportClean)
            throws Exception {
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
                    + (label.equals("guard") ? "" : " (" + label.substring("guard ".length()) + ")")
                    + (pass > 1 ? " (pass " + pass + ")" : ""));
            ProjectModel migrated = context.plugin().model(context.workspace().root());
            AnalysisResult check = new Analyzer(registry).check(migrated, playbook, guards);
            MigrationPlan guardPlan = new Planner().plan(check);
            String stage = pass == 1 ? label : label + " pass " + pass;

            List<String> details = new ArrayList<>(check.warnings());
            check.findings().forEach(f -> details.add(f.ruleId() + ": " + f.file() + (f.line() > 0 ? ":" + f.line() : "")
                    + (f.evidence() == null ? "" : " (" + f.evidence() + ")")));
            Set<String> found = new LinkedHashSet<>();
            check.findings().forEach(f -> found.add(f.ruleId() + "|" + f.file() + "|" + f.evidence()));
            if (guardPlan.steps().isEmpty()) {
                if (pass == 1 && reportClean) {
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
