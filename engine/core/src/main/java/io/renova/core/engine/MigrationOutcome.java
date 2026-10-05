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

    public MigrationOutcome(Path workspace, List<StageResult> stages, VerifyResult verification, List<PlanStep> manualSteps) {
        this(workspace, stages, verification, manualSteps, AiUsage.NONE, 0, null);
    }
}
