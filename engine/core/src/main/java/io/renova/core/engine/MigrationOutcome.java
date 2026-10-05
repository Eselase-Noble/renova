package io.renova.core.engine;

import java.nio.file.Path;
import java.util.List;

/** @param verification null when verification was disabled or no verifier exists */
public record MigrationOutcome(Path workspace, List<StageResult> stages, VerifyResult verification,
                               List<PlanStep> manualSteps) {

    public MigrationOutcome {
        stages = List.copyOf(stages);
        manualSteps = List.copyOf(manualSteps);
    }
}
