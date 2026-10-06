package io.renova.core.engine;

import io.renova.core.ai.AiUsage;
import io.renova.core.behaviour.BehaviourReport;

import java.nio.file.Path;
import java.util.List;

/**
 * @param verification null when verification was disabled or no verifier exists
 * @param aiUsage      requests, outcomes and tokens of the AI provider
 * @param repairRounds AI build-repair rounds that edited files and rebuilt
 * @param behaviour    side-by-side comparison of the original and migrated application; null when not run
 */
public record MigrationOutcome(Path workspace, List<StageResult> stages, VerifyResult verification,
                               List<PlanStep> manualSteps, AiUsage aiUsage, int repairRounds, BehaviourReport behaviour) {

    public MigrationOutcome {
        stages = List.copyOf(stages);
        manualSteps = List.copyOf(manualSteps);
        aiUsage = aiUsage == null ? AiUsage.NONE : aiUsage;
    }

    /** The stages that could not run at all, such as recipes whose tool failed: their changes were not made. */
    public List<StageResult> failedStages() {
        return stages.stream().filter(s -> s.status() == StageResult.Status.FAILED).toList();
    }

    /**
     * Whether the migration did what was asked: every stage ran, the build passes (when it was verified) and the
     * application behaves the same (when that was compared). A passing build is not enough on its own: when the
     * recipes could not run, the unchanged code still builds, and nothing was migrated.
     */
    public boolean passed() {
        boolean build = verification == null || verification.success();
        boolean same = behaviour == null || behaviour.status() == BehaviourReport.Status.SAME
                || behaviour.status() == BehaviourReport.Status.SKIPPED;
        return failedStages().isEmpty() && build && same;
    }

    public MigrationOutcome(Path workspace, List<StageResult> stages, VerifyResult verification, List<PlanStep> manualSteps) {
        this(workspace, stages, verification, manualSteps, AiUsage.NONE, 0, null);
    }
}
